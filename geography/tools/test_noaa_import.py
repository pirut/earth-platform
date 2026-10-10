#!/usr/bin/env python3
"""Offline tests for the cached real NOAA proof, importer bounds and EGP1 bytes."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

import import_noaa_etopo2022 as importer


class NoaaImportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = importer.DEFAULT_OUTPUT
        cls.lock = json.loads((cls.data / "source-lock.json").read_text())
        cls.report = json.loads((cls.data / "import-report.json").read_text())

    def copy_data(self, temporary):
        target = Path(temporary) / "data"
        shutil.copytree(self.data, target)
        return target

    def run_offline(self, data):
        with patch.object(importer, "bounded_download", side_effect=AssertionError("Offline import attempted network")), contextlib.redirect_stdout(io.StringIO()):
            return importer.run(data, download=False, initialize=False)

    def test_real_sources_pinned_and_bounded(self):
        self.assertEqual(len(self.lock["regions"]), 3)
        self.assertLess(self.report["totalDownloadedRasterBytes"], 150_000)
        for region, record in zip(importer.REGIONS, self.lock["regions"]):
            raw = (self.data / record["path"]).read_bytes()
            self.assertEqual(hashlib.sha256(raw).hexdigest(), record["sha256"])
            self.assertLess(len(raw), importer.MAX_DOWNLOAD_BYTES)
            self.assertEqual(record["url"], importer.export_url(region))
            array = importer.read_tiff(raw, region)
            self.assertEqual(array.shape, (region["height"], region["width"]))
            self.assertTrue(np.all(np.isfinite(array)))

    def test_egp_big_endian_and_center_coordinates(self):
        for tile in self.report["tiles"]:
            raw = (self.data / tile["path"]).read_bytes()
            magic, width, height, west, north, dx, dy = struct.unpack(">III4d", raw[:44])
            self.assertEqual(magic, 0x45475031)
            self.assertEqual(len(raw), 44 + 4 * width * height)
            self.assertEqual((width, height), (tile["width"], tile["height"]))
            self.assertEqual((west, north, dx, dy), (tile["west"], tile["north"], tile["stepLon"], tile["stepLat"]))
            region = next(region for region in importer.REGIONS if region["id"] == tile["region"])
            source = importer.read_tiff((self.data / f'regions/{region["id"]}/raw.tif').read_bytes(), region)
            x_start = 60 if tile["id"] == "san-francisco-east" else 0
            actual = np.frombuffer(raw[44:], dtype=">f4").reshape(height, width)
            np.testing.assert_array_equal(actual, source[:, x_start:x_start + width])
            self.assertAlmostEqual(west, (region["westSteps"] + x_start + .5) / 240, places=12)
            self.assertAlmostEqual(north, (region["northSteps"] - .5) / 240, places=12)
            self.assertEqual(hashlib.sha256(raw).hexdigest(), tile["sha256"])

    def test_seam_samples_are_bit_identical(self):
        west, east = self.report["tiles"][:2]
        a = np.frombuffer((self.data / west["path"]).read_bytes()[44:], dtype=">f4").reshape(west["height"], west["width"])
        b = np.frombuffer((self.data / east["path"]).read_bytes()[44:], dtype=">f4").reshape(east["height"], east["width"])
        self.assertEqual(a[:, -2:].tobytes(), b[:, :2].tobytes())
        self.assertAlmostEqual(west["west"] + (west["width"] - 2) * west["stepLon"], east["west"], places=12)
        # Bilinear values agree inside the overlap for arbitrary fractional rows/columns.
        for ty in (.1, .37, .999):
            for tx in (.01, .4, .99):
                def value(array, column):
                    return (float(array[42, column]) * (1 - tx) + float(array[42, column + 1]) * tx) * (1 - ty) + (float(array[43, column]) * (1 - tx) + float(array[43, column + 1]) * tx) * ty
                self.assertEqual(value(a, a.shape[1] - 2), value(b, 0))

    def test_offline_rebuild_is_byte_identical(self):
        generated = [tile["path"] for tile in self.report["tiles"]] + ["world.properties", "world-global.properties", "import-report.json", "SHA256SUMS"]
        with tempfile.TemporaryDirectory() as temporary:
            target = self.copy_data(temporary)
            before = {path: (target / path).read_bytes() for path in generated}
            for path in generated:
                (target / path).unlink()
            self.run_offline(target)
            for path in generated:
                self.assertEqual((target / path).read_bytes(), before[path], path)

    def test_tampered_raw_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = self.copy_data(temporary)
            path = target / self.lock["regions"][0]["path"]
            raw = bytearray(path.read_bytes())
            raw[-1] ^= 1
            path.write_bytes(raw)
            with self.assertRaisesRegex(ValueError, "hash/size mismatch"):
                self.run_offline(target)

    def test_missing_raw_fails_without_network_or_fake_terrain(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = self.copy_data(temporary)
            (target / self.lock["regions"][0]["path"]).unlink()
            with self.assertRaisesRegex(ValueError, "Offline source missing"):
                self.run_offline(target)

    def test_changed_download_cannot_replace_pin(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = self.copy_data(temporary)
            path = target / self.lock["regions"][0]["path"]
            path.unlink()
            with patch.object(importer, "bounded_download", return_value=b"different bytes"):
                with self.assertRaisesRegex(ValueError, "differs from pinned"):
                    importer.run(target, download=True, initialize=False)
            self.assertFalse(path.exists())

    def test_invalid_tiff_rejected(self):
        with self.assertRaisesRegex(ValueError, "did not return a TIFF"):
            importer.read_tiff(b'{"error":"unavailable"}', importer.REGIONS[0])
        raw = (self.data / self.lock["regions"][0]["path"]).read_bytes()
        with self.assertRaisesRegex(ValueError, "dimensions/type"):
            importer.read_tiff(raw, importer.REGIONS[1])
        wrong_region = dict(importer.REGIONS[0], westSteps=-29441)
        with self.assertRaisesRegex(ValueError, "geographic metadata differs"):
            importer.read_tiff(raw, wrong_region)

    def test_initialization_never_overwrites_lock(self):
        with patch.object(importer, "bounded_download", side_effect=AssertionError("Must not download")):
            with self.assertRaisesRegex(ValueError, "Refusing to replace"):
                importer.run(self.data, download=True, initialize=True)

    def test_actual_land_ocean_mountain_and_depth_ranges(self):
        regions = {region["id"]: region for region in self.report["regions"]}
        sf = regions["san-francisco"]["statistics"]
        self.assertLess(sf["minimumMeters"], 0)
        self.assertGreater(sf["maximumMeters"], 0)
        self.assertGreater(sf["negativeSamples"], 1000)
        self.assertGreater(sf["positiveSamples"], 1000)
        self.assertGreater(regions["everest"]["statistics"]["maximumMeters"], 8000)
        self.assertLess(regions["challenger-deep"]["statistics"]["minimumMeters"], -10000)
        self.assertEqual(sum(region["statistics"]["noDataSamples"] for region in regions.values()), 0)

    def test_download_limit_is_enforced(self):
        class Response(io.BytesIO):
            headers = {}
        with patch.object(importer.urllib.request, "urlopen", return_value=Response(b"12345")):
            with self.assertRaisesRegex(ValueError, "exceeds 4 bytes"):
                importer.bounded_download("https://www.ncei.noaa.gov/", limit=4)

    def test_provenance_evidence_tampering_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = self.copy_data(temporary)
            (target / "provenance/noaa-metadata.html").write_text("fake license")
            with self.assertRaisesRegex(ValueError, "Provenance evidence hash mismatch"):
                self.run_offline(target)

    def test_fallback_raster_identity_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = self.copy_data(temporary)
            lock = json.loads((target / "source-lock.json").read_text())
            record = lock["sourceSelectionEvidence"]["samples-everest"]
            values = json.loads((target / record["path"]).read_text())
            values["samples"][0]["rasterId"] = 999
            raw = importer.json_bytes(values)
            (target / record["path"]).write_bytes(raw)
            record["sha256"] = importer.digest(raw)
            (target / "source-lock.json").write_bytes(importer.json_bytes(lock))
            with self.assertRaisesRegex(ValueError, "Unexpected fallback raster"):
                self.run_offline(target)

    def test_wrong_source_vertical_datum_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = self.copy_data(temporary)
            lock = json.loads((target / "source-lock.json").read_text())
            record = lock["sourceSelectionEvidence"]["raster-info"]
            values = json.loads((target / record["path"]).read_text())
            values["extent"]["spatialReference"]["latestVcsWkid"] = 5773
            raw = importer.json_bytes(values)
            (target / record["path"]).write_bytes(raw)
            record["sha256"] = importer.digest(raw)
            (target / "source-lock.json").write_bytes(importer.json_bytes(lock))
            with self.assertRaisesRegex(ValueError, "type/CRS/datum mismatch"):
                self.run_offline(target)


if __name__ == "__main__":
    unittest.main(verbosity=2)
