#!/usr/bin/env python3
"""Import one bounded Everest window from two HTTP ranges of a Copernicus GLO-30 COG.

Default is strictly offline. --initialize makes a new provenance lock; every payload
request must honor HTTP 206, Content-Range and the expected source ETag. No whole-tile fallback.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import io
import json
import math
from pathlib import Path
import struct
import urllib.request
import xml.etree.ElementTree as ET
import zlib

import numpy as np
from PIL import Image, TiffImagePlugin
from import_noaa_etopo2022 import bounded_download, digest, json_bytes, statistics, write_atomic

OUTPUT = Path(__file__).resolve().parents[1] / "data" / "copernicus-everest-proof"
NAME = "Copernicus_DSM_COG_10_N27_00_E086_00_DEM"
URL = f"https://copernicus-dem-30m.s3.amazonaws.com/{NAME}/{NAME}.tif"
EXPECTED_ETAG = '"37c621f4f1b33740ea8f5997922a3f03"'
EXPECTED_SIZE = 43039634
EXPECTED_HEADER_SHA = "0d76d7f76ce86f91ab7ba9edfc5433e25e897223dd6ca76bc69455a8bc865a4c"
EXPECTED_BLOCK_SHA = "3ae196de8c0c4f9e2977815e20ca140d4c8fdfceff5a6985f3b8c473e10484af"
STEP = 1 / 3600
BLOCK_INDEX = 3
# Exact original TIFF sample indices: PixelIsPoint (0,0) is (86 E,28 N).
COL_START, ROW_START, WIDTH, HEIGHT = 3260, 0, 180, 180
DOCUMENTS = {
    "aws-readme.html": "https://copernicus-dem-30m.s3.amazonaws.com/readme.html",
    "aws-registry.html": "https://registry.opendata.aws/copernicus-dem/",
    "source-metadata.xml": f"https://copernicus-dem-30m.s3.amazonaws.com/{NAME}/Copernicus_DSM_10_N27_00_E086_00.xml",
    "source-eula.pdf": f"https://copernicus-dem-30m.s3.amazonaws.com/{NAME}/INFO/eula_F.pdf",
    "License-COPDEM-30.pdf": "https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf",
}
NOTICE = "produced using Copernicus WorldDEM-30 \u00a9 DLR e.V. 2010-2014 and \u00a9 Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved"
DISCLAIMER = "The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30"


def fetch_range(start: int, end: int) -> bytes:
    length = end - start + 1
    if length < 1 or length > 2 * 1024 * 1024:
        raise ValueError("Requested range exceeds 2 MiB cap")
    request = urllib.request.Request(URL, headers={"Range": f"bytes={start}-{end}", "If-Match": EXPECTED_ETAG})
    with urllib.request.urlopen(request, timeout=90) as response:
        if response.status != 206 or response.headers.get("Content-Range") != f"bytes {start}-{end}/{EXPECTED_SIZE}":
            raise ValueError("Server did not honor exact bounded range; refusing whole-file fallback")
        if response.headers.get("ETag") != EXPECTED_ETAG:
            raise ValueError("Source object ETag changed; review dataset before importing")
        raw = response.read(length + 1)
        if len(raw) != length:
            raise ValueError("Wrong range response length")
        return raw


def header_tags(raw: bytes) -> dict:
    if len(raw) != 16384:
        raise ValueError("Unexpected pinned header size")
    image = Image.open(io.BytesIO(raw))
    tags = dict(image.tag_v2)
    expected = {256: 3600, 257: 3600, 258: (32,), 259: 8, 317: 3,
                322: 1024, 323: 1024, 339: (3,), 277: 1}
    if any(tags.get(key) != value for key, value in expected.items()):
        raise ValueError("Unexpected scalar COG encoding or dimensions")
    keys = tags[34735]
    directory = {keys[i]: tuple(keys[i+1:i+4]) for i in range(4, len(keys), 4)}
    if directory.get(1025) != (0, 1, 2) or directory.get(2048) != (0, 1, 4326):
        raise ValueError("Expected original PixelIsPoint WGS84 samples")
    if tags[33922] != (0, 0, 0, 86, 28, 0) or not all(math.isclose(v, STEP, rel_tol=0, abs_tol=1e-16) for v in tags[33550][:2]):
        raise ValueError("Original COG tiepoint or spacing changed")
    return tags


def decode_block(header: bytes, compressed: bytes) -> tuple[np.ndarray, dict]:
    tags = header_tags(header)
    if len(compressed) != tags[325][BLOCK_INDEX]:
        raise ValueError("Wrong compressed source block length")
    # Independent predictor-3 decoder: deflate, cumulative-byte reconstruction,
    # reverse byte-plane shuffle, then interpret big-endian float32.
    decoder = zlib.decompressobj()
    unpacked = decoder.decompress(compressed, 1024 * 1024 * 4 + 1)
    if len(unpacked) != 1024 * 1024 * 4 or not decoder.eof or decoder.unused_data:
        raise ValueError("Unexpected decompressed block size/stream")
    rows = np.frombuffer(unpacked, dtype=np.uint8).reshape(1024, 4096)
    unpredicted = np.cumsum(rows, axis=1, dtype=np.uint8)
    independently_decoded = unpredicted.reshape(1024, 4, 1024).transpose(0, 2, 1).copy().view(">f4").reshape(1024, 1024)
    # Cross-check every sample through Pillow/libtiff using unchanged compressed
    # source bytes in a one-block TIFF wrapper. This catches predictor/endian bugs.
    ifd = TiffImagePlugin.ImageFileDirectory_v2()
    for key in (256, 257, 258, 259, 262, 277, 284, 317, 322, 323, 339, 33550, 33922, 34735, 34736, 34737):
        ifd[key] = tags[key]
    ifd[256], ifd[257], ifd[324], ifd[325] = 1024, 1024, (0,), (len(compressed),)
    ifd[33922] = (0., 0., 0., 86 + 3072 * STEP, 28., 0.)
    body = ifd.tobytes(offset=8)
    ifd[324] = (8 + len(body),)
    updated = ifd.tobytes(offset=8)
    if len(body) != len(updated):
        raise ValueError("Unstable TIFF wrapper offsets")
    wrapper = b"II*\x00" + struct.pack("<I", 8) + updated + compressed
    decoded = np.asarray(Image.open(io.BytesIO(wrapper)), dtype=np.float32)
    if not np.array_equal(decoded, independently_decoded):
        raise ValueError("Independent float-predictor decoder differs from Pillow/libtiff")
    return decoded, tags


def initialize(output: Path) -> dict:
    if (output / "source-lock.json").exists():
        raise ValueError("Refusing to replace existing source lock")
    # Resume incomplete initialization only from exact pre-verified source bytes.
    header_path, block_path = output / "source-header.bin", output / "source-block-3.bin"
    header = header_path.read_bytes() if header_path.exists() else fetch_range(0, 16383)
    if digest(header) != EXPECTED_HEADER_SHA:
        raise ValueError("Original COG header differs from reviewed source")
    tags = header_tags(header)
    start, count = tags[324][BLOCK_INDEX], tags[325][BLOCK_INDEX]
    block = block_path.read_bytes() if block_path.exists() else fetch_range(start, start + count - 1)
    if digest(block) != EXPECTED_BLOCK_SHA:
        raise ValueError("Original COG data block differs from reviewed source")
    decode_block(header, block)
    artifacts = []
    for name, raw, begin, end in (("source-header.bin", header, 0, 16383),
                                  ("source-block-3.bin", block, start, start + count - 1)):
        write_atomic(output / name, raw)
        artifacts.append({"path": name, "url": URL, "range": [begin, end], "bytes": len(raw), "sha256": digest(raw)})
    for name, url in DOCUMENTS.items():
        raw = bounded_download(url, limit=1024 * 1024)
        if name.endswith(".pdf") and not raw.startswith(b"%PDF"):
            raise ValueError("License endpoint did not return PDF")
        path = "provenance/" + name
        write_atomic(output / path, raw)
        artifacts.append({"path": path, "url": url, "bytes": len(raw), "sha256": digest(raw)})
    lock = {"schema": "earth-copernicus-range-lock-v1", "retrievalDateUtc": datetime.now(timezone.utc).date().isoformat(),
            "sourceUrl": URL, "sourceEtag": EXPECTED_ETAG, "sourceTotalBytes": EXPECTED_SIZE,
            "sourceDataset": "COP-DEM_GLO-30-DGED", "release": "AWS mirror of Copernicus DEM 2021 release",
            "sourceTile": NAME, "sourceBlockIndex": BLOCK_INDEX, "sourceOriginalPixelIsPoint": True,
            "windowOriginalIndices": {"columnStart": COL_START, "rowStart": ROW_START, "width": WIDTH, "height": HEIGHT},
            "horizontalCrs": "EPSG:4326", "verticalCrs": "EPSG:3855", "units": "meters",
            "verticalDatumEvidence": "Original tile XML explicitly declares EGM2008 geoid, EPSG::3855 and meter units; mirrored TIFF omits vertical CRS tags. No datum transform.",
            "nativeResolutionArcSeconds": 1, "licenseUrl": DOCUMENTS["License-COPDEM-30.pdf"],
            "artifacts": artifacts}
    write_atomic(output / "source-lock.json", json_bytes(lock))
    return lock


def rebuild(output: Path, initialize_requested: bool = False) -> dict:
    lock = initialize(output) if initialize_requested else json.loads((output / "source-lock.json").read_text())
    if lock.get("schema") != "earth-copernicus-range-lock-v1" or lock.get("sourceUrl") != URL or lock.get("sourceEtag") != EXPECTED_ETAG:
        raise ValueError("Unexpected source identity in Copernicus lock")
    if lock.get("sourceBlockIndex") != BLOCK_INDEX or lock.get("windowOriginalIndices") != {"columnStart": COL_START, "rowStart": ROW_START, "width": WIDTH, "height": HEIGHT}:
        raise ValueError("Unexpected source block/window selection")
    expected_paths = {"source-header.bin", "source-block-3.bin"} | {"provenance/" + name for name in DOCUMENTS}
    if {record["path"] for record in lock["artifacts"]} != expected_paths:
        raise ValueError("Unexpected provenance artifact list")
    for artifact in lock["artifacts"]:
        raw = (output / artifact["path"]).read_bytes()
        if len(raw) != artifact["bytes"] or digest(raw) != artifact["sha256"]:
            raise ValueError("Source/provenance artifact hash/size mismatch: " + artifact["path"])
    block, tags = decode_block((output / "source-header.bin").read_bytes(), (output / "source-block-3.bin").read_bytes())
    if digest((output / "source-header.bin").read_bytes()) != EXPECTED_HEADER_SHA or digest((output / "source-block-3.bin").read_bytes()) != EXPECTED_BLOCK_SHA:
        raise ValueError("Source bytes differ from independently reviewed COG")
    metadata = ET.fromstring((output / "provenance/source-metadata.xml").read_bytes())
    if not any(node.attrib.get("codeSpace") == "EPSG::3855" for node in metadata.iter()):
        raise ValueError("Original tile metadata missing EGM2008 vertical CRS")
    source_maximum = [float(node.text) for node in metadata.iter() if node.tag.endswith("}maxValue") and node.attrib.get("units") == "meter"]
    if len(source_maximum) != 1 or not math.isclose(source_maximum[0], float(block.max()), rel_tol=0, abs_tol=1e-6):
        raise ValueError("Decoded block maximum differs from independently supplied original-tile metadata")
    if not any(node.text == "Copernicus_DSM_10_N27_00_E086_00_DEM.tif" for node in metadata.iter()):
        raise ValueError("Original tile XML identity mismatch")
    block_column_start = (BLOCK_INDEX % 4) * 1024
    block_row_start = (BLOCK_INDEX // 4) * 1024
    x0, y0 = COL_START - block_column_start, ROW_START - block_row_start
    array = block[y0:y0 + HEIGHT, x0:x0 + WIDTH].copy()
    if array.shape != (HEIGHT, WIDTH) or not np.all(np.isfinite(array)) or np.min(array) <= 0:
        raise ValueError("Unexpected Everest window shape or missing/padding values")
    west, north = 86 + COL_START * STEP, 28 - ROW_START * STEP
    payload = struct.pack(">III4d", 0x45475031, WIDTH, HEIGHT, west, north, STEP, STEP) + array.astype(">f4").tobytes()
    write_atomic(output / "everest.egp", payload)
    props = {"world.id": "copernicus-everest-proof", "world.seed": 20221009, "generator.version": "surface-fill-v1", "palette.version": "geography-palette-v1",
             "projection.id": "equirectangular-v1", "projection.originLat": 27.9881, "projection.originLon": 86.925, "projection.standardParallel": 27.9881,
             "interpolation": "bilinear-v1", "dataset.id": "copernicus-dem-glo30-2021", "dataset.release": "AWS-2021-mirror-N27E086",
             "dataset.datum": "EPSG:3855 (EGM2008 orthometric height, meters; original tile XML)", "dataset.resolutionArcSeconds": 1,
             "dataset.license": "Copernicus-WorldDEM-30-Free-License (see included license and notices)", "dataset.source": URL,
             "dataset.quality": "native-1-arcsecond-DSM; bounded-Everest-window; original-XML-datum-and-extrema-verified",
             "dataset.attribution": NOTICE, "tile.count": 1, "tile.0.id": "everest-glo30", "tile.0.path": "everest.egp", "tile.0.sha256": digest(payload),
             "tile.0.west": west, "tile.0.north": north, "tile.0.stepLon": STEP, "tile.0.stepLat": STEP, "tile.0.width": WIDTH, "tile.0.height": HEIGHT}
    # java.util.Properties(InputStream) uses ISO-8859-1; ASCII escape copyright.
    lines = [f"{key}={str(value).replace(chr(169), r'\u00a9')}" for key, value in props.items()]
    write_atomic(output / "world.properties", ("\n".join(lines) + "\n").encode("ascii"))
    valid_block = block[:, :3600 - block_column_start]
    py, px = (int(value) for value in np.unravel_index(valid_block.argmax(), valid_block.shape))
    cy, cx = (int(value) for value in np.unravel_index(array.argmax(), array.shape))
    latitude, longitude = 27.9881, 86.925
    x, y = (longitude - west) / STEP, (north - latitude) / STEP
    ix, iy, tx, ty = math.floor(x), math.floor(y), x % 1, y % 1
    elevation = (float(array[iy, ix])*(1-tx)+float(array[iy, ix+1])*tx)*(1-ty)+(float(array[iy+1, ix])*(1-tx)+float(array[iy+1, ix+1])*tx)*ty
    report = {"schema": "earth-copernicus-import-report-v1", "sourceRangeBytes": 16384 + len((output / "source-block-3.bin").read_bytes()),
              "sourceWholeFileBytesNotDownloaded": EXPECTED_SIZE, "twoIndependentDecodersAgreeBitExactly": True,
              "originalTileXmlMaximumMeters": source_maximum[0], "originalTileXmlVerticalCrs": "EPSG:3855",
              "originalTileXmlVersion": "02", "metadataMaximumAgreesWithDecodedBlock": True,
              "sourceBlock": {"index": BLOCK_INDEX, "columnStart": block_column_start, "rowStart": block_row_start,
                              "storedWidth": 1024, "storedHeight": 1024, "validWidth": valid_block.shape[1], "statisticsExcludingPadding": statistics(valid_block),
                              "maximumOriginalColumn": block_column_start + px, "maximumOriginalRow": py,
                              "maximumLatitude": 28-py*STEP, "maximumLongitude": 86+(block_column_start+px)*STEP},
              "window": {"originalColumnStart": COL_START, "originalRowStart": ROW_START, "width": WIDTH, "height": HEIGHT,
                         "westSampleCenter": west, "northSampleCenter": north, "stepDegrees": STEP, "statistics": statistics(array),
                         "maximumWindowColumn": cx, "maximumWindowRow": cy},
              "landmark": {"latitude": latitude, "longitude": longitude, "originalFractionalColumn": (longitude-86)/STEP,
                           "originalFractionalRow": (28-latitude)/STEP, "bilinearElevationMeters": elevation},
              "note": "PixelIsPoint tiepoint is already a sample center; no half-cell shift. Entire downloaded native block was checked; 8737.8 m maximum is actual source data, not a forced survey-height correction."}
    write_atomic(output / "import-report.json", json_bytes(report))
    paths = sorted(expected_paths | {"source-lock.json", "everest.egp", "world.properties", "import-report.json"})
    write_atomic(output / "SHA256SUMS", ("\n".join(f"{digest((output/path).read_bytes())}  {path}" for path in paths)+"\n").encode("ascii"))
    print(json.dumps(report, indent=2))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--initialize", action="store_true")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    try:
        rebuild(args.output.resolve(), args.initialize)
    except (OSError, ValueError, KeyError) as exception:
        parser.exit(1, f"Import failed: {exception}\n")

if __name__ == "__main__":
    main()
