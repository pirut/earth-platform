#!/usr/bin/env python3
"""Offline source and import regression tests; no access credentials or network needed."""
from pathlib import Path
import io
import json
import shutil
import struct
import tempfile
import unittest
from unittest import mock
import numpy as np
import import_worldcover as wc


class WorldCoverImportTest(unittest.TestCase):
    def test_offline_rebuild_is_byte_exact(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for artifact in wc.source_artifacts():
                destination = output / artifact['path']
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(wc.OUTPUT / artifact['path'], destination)
            with mock.patch('urllib.request.build_opener', side_effect=AssertionError('Offline import used network')):
                report = wc.rebuild(output)
            self.assertEqual(report['sourceRangeBytes'], 192049)
            self.assertEqual(report['nativeCells'], 2097152)
            self.assertTrue(report['independentDecodersAgreeBitExactly'])
            for line in (wc.OUTPUT / 'SHA256SUMS').read_text().splitlines():
                expected, path = line.split('  ', 1)
                self.assertEqual(wc.digest((output / path).read_bytes()), expected, path)
            before = (output / 'SHA256SUMS').read_bytes()
            wc.rebuild(output)
            self.assertEqual(before, (output / 'SHA256SUMS').read_bytes())

    def test_source_codes_and_original_georeferencing(self):
        expected = [
            {10:462098,20:8124,30:127390,40:198,50:49843,60:4684,80:391655,90:4584},
            {60:26532,70:1021126,80:771,100:147},
        ]
        for region, histogram in zip(wc.REGIONS, expected):
            root = wc.OUTPUT / region['id']
            array, tags = wc.decode_block((root/'source-header.bin').read_bytes(), (root/'source-block.bin').read_bytes(), region)
            self.assertEqual(dict(zip(*np.unique(array, return_counts=True))), histogram)
            raw = (root/'landcover.elc').read_bytes()
            magic, w, h, west, north, dx, dy = struct.unpack('>III4d', raw[:44])
            self.assertEqual((magic,w,h,dx,dy), (0x454c4331,1024,1024,wc.STEP,wc.STEP))
            self.assertEqual(west, region['west']+region['blockCol']*1024*wc.STEP)
            self.assertEqual(north, region['north']-region['blockRow']*1024*wc.STEP)
            self.assertEqual(raw[44:], array.tobytes())
            # Source bounds are area corners; source-cell centers require a half-pixel offset.
            self.assertNotEqual(west, west + wc.STEP/2)
            self.assertEqual(tags[42113], '0')

    def test_corruption_and_lock_edit_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            shutil.copytree(wc.OUTPUT, output, dirs_exist_ok=True)
            path = output/'marin-headlands/source-block.bin'
            data = bytearray(path.read_bytes()); data[100] ^= 1; path.write_bytes(data)
            with self.assertRaisesRegex(ValueError, 'checksum/size'):
                wc.rebuild(output)
            shutil.copyfile(wc.OUTPUT/'marin-headlands/source-block.bin', path)
            lock = json.loads((output/'source-lock.json').read_text()); lock['epoch']='2025'
            (output/'source-lock.json').write_text(json.dumps(lock))
            with self.assertRaisesRegex(ValueError, 'Provenance lock'):
                wc.rebuild(output)

    def test_range_limits_fail_before_request(self):
        with mock.patch('urllib.request.build_opener', side_effect=AssertionError('Unexpected network')):
            for start,end in [(0,wc.MAX_RANGE_BYTES),(-1,2),(10,9),(0,wc.REGIONS[0]['size'])]:
                with self.assertRaises(ValueError): wc.fetch_range(wc.REGIONS[0],start,end)

    def test_refuses_whole_file_or_changed_object(self):
        region=wc.REGIONS[0]
        class Response(io.BytesIO):
            def __init__(self,status,content_range,etag):
                super().__init__(b'0123'); self.status=status
                self.headers={'Content-Range':content_range,'ETag':etag}
            def read(self,*args):
                raise AssertionError('Must reject invalid headers before reading payload')
        for response in [Response(200,f'bytes 0-3/{region["size"]}',region['etag']),
                         Response(206,'bytes 0-3/99',region['etag']),
                         Response(206,f'bytes 0-3/{region["size"]}','changed')]:
            opener=mock.Mock(); opener.open.return_value=response
            with mock.patch('urllib.request.build_opener',return_value=opener):
                with self.assertRaises(ValueError): wc.fetch_range(region,0,3)


if __name__=='__main__': unittest.main(verbosity=2)
