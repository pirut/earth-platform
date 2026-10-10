#!/usr/bin/env python3
"""Offline Copernicus range import/decoding/georegistration tests."""
import contextlib
import io
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest.mock import patch
import numpy as np
import import_copernicus_everest as importer


class CopernicusImportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.output = importer.OUTPUT
        cls.report = json.loads((cls.output / 'import-report.json').read_text())
        cls.lock = json.loads((cls.output / 'source-lock.json').read_text())

    def copy(self, temp):
        target = Path(temp) / 'data'
        shutil.copytree(self.output, target)
        return target

    def offline(self, output):
        with patch.object(importer, 'fetch_range', side_effect=AssertionError('Network in offline build')), patch.object(importer, 'bounded_download', side_effect=AssertionError('Network in offline build')), contextlib.redirect_stdout(io.StringIO()):
            return importer.rebuild(output)

    def test_offline_rebuild_byte_identical(self):
        paths = ['everest.egp', 'world.properties', 'import-report.json', 'SHA256SUMS']
        with tempfile.TemporaryDirectory() as temp:
            output = self.copy(temp)
            expected = {path: (output/path).read_bytes() for path in paths}
            for path in paths: (output/path).unlink()
            self.offline(output)
            for path in paths: self.assertEqual((output/path).read_bytes(), expected[path])

    def test_two_independent_decoders_match(self):
        array, tags = importer.decode_block((self.output/'source-header.bin').read_bytes(), (self.output/'source-block-3.bin').read_bytes())
        self.assertEqual(array.shape, (1024, 1024))
        self.assertEqual(float(array.max()), 8737.7880859375)
        self.assertEqual(tuple(int(i) for i in np.unravel_index(array.argmax(), array.shape)), (39, 260))
        self.assertEqual(tags[33922], (0, 0, 0, 86, 28, 0))

    def test_pixel_is_point_has_no_half_pixel_shift(self):
        payload = (self.output/'everest.egp').read_bytes()
        magic, width, height, west, north, dx, dy = struct.unpack('>III4d', payload[:44])
        self.assertEqual(magic, 0x45475031)
        self.assertEqual((width, height), (180, 180))
        self.assertEqual(west, 86+3260/3600)
        self.assertEqual(north, 28)
        self.assertEqual((dx,dy), (1/3600,1/3600))
        self.assertEqual(len(payload), 44+4*width*height)
        self.assertTrue(west < 86.925 < west+(width-1)*dx)
        self.assertTrue(north-(height-1)*dy < 27.9881 < north)

    def test_egp_exact_original_samples(self):
        block,_ = importer.decode_block((self.output/'source-header.bin').read_bytes(), (self.output/'source-block-3.bin').read_bytes())
        actual = np.frombuffer((self.output/'everest.egp').read_bytes()[44:],dtype='>f4').reshape(180,180)
        np.testing.assert_array_equal(actual, block[0:180,188:368])
        self.assertAlmostEqual(float(actual.max()), self.report['originalTileXmlMaximumMeters'], places=6)
        self.assertTrue(self.report['metadataMaximumAgreesWithDecodedBlock'])

    def test_source_blocks_stay_bounded(self):
        self.assertLess(self.report['sourceRangeBytes'], 1_300_000)
        self.assertEqual(self.report['sourceRangeBytes'], 1_276_683)
        self.assertEqual(self.report['sourceWholeFileBytesNotDownloaded'], 43_039_634)
        self.assertEqual(self.report['sourceBlock']['validWidth'], 528)
        self.assertEqual(self.report['sourceBlock']['maximumOriginalColumn'], 3332)
        self.assertEqual(self.report['sourceBlock']['maximumOriginalRow'], 39)

    def test_tampered_source_block_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            output=self.copy(temp);path=output/'source-block-3.bin';raw=bytearray(path.read_bytes());raw[-1]^=1;path.write_bytes(raw)
            with self.assertRaisesRegex(ValueError,'hash/size mismatch'):self.offline(output)

    def test_wrong_source_identity_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            output=self.copy(temp);path=output/'source-lock.json';lock=json.loads(path.read_text());lock['sourceEtag']='different';path.write_text(json.dumps(lock))
            with self.assertRaisesRegex(ValueError,'source identity'):self.offline(output)

    def test_full_file_fallback_is_rejected_before_read(self):
        class Response(io.BytesIO):
            status=200
            headers={'Content-Length':'43039634'}
            def read(self,*args):raise AssertionError('Must refuse full response without reading')
        with patch.object(importer.urllib.request,'urlopen',return_value=Response()):
            with self.assertRaisesRegex(ValueError,'whole-file fallback'):importer.fetch_range(0,16383)

    def test_range_budget_is_enforced(self):
        with patch.object(importer.urllib.request,'urlopen',side_effect=AssertionError('Oversized request must never reach network')):
            with self.assertRaisesRegex(ValueError,'2 MiB'):importer.fetch_range(0,3*1024*1024)

    def test_license_evidence_included(self):
        self.assertTrue((self.output/'provenance/License-COPDEM-30.pdf').read_bytes().startswith(b'%PDF'))
        self.assertTrue((self.output/'provenance/source-eula.pdf').read_bytes().startswith(b'%PDF'))
        self.assertIn('EPSG::3855',(self.output/'provenance/source-metadata.xml').read_text())

if __name__=='__main__':unittest.main(verbosity=2)
