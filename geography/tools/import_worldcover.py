#!/usr/bin/env python3
"""Rebuild two pinned native WorldCover blocks offline; --download restores missing reviewed artifacts.

Only explicit HTTPS byte ranges from official ESA WorldCover COGs are requested.
No full-tile fallback, credentials, accounts, paid API, reclassification or terrain-height changes.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import urllib.request
import warnings
import xml.etree.ElementTree as ET
import zlib
import numpy as np
from PIL import Image, TiffImagePlugin

OUTPUT = Path(__file__).resolve().parents[1] / "data" / "worldcover-2021-proof"
BASE = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/"
STEP = 1 / 12000
BLOCK_SIZE = 1024
HEADER_SIZE = 65536
MAX_RANGE_BYTES = 256 * 1024
CLASS_NAMES = {0: "Unknown/no data", 10: "Tree cover", 20: "Shrubland", 30: "Grassland", 40: "Cropland",
               50: "Built-up", 60: "Bare/sparse vegetation", 70: "Snow and ice", 80: "Permanent water bodies",
               90: "Herbaceous wetland", 95: "Mangroves", 100: "Moss and lichen"}
ATTRIBUTION = "\u00a9 ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium"
CITATION = "Zanaga, D. et al. (2022). ESA WorldCover 10 m 2021 v200. https://doi.org/10.5281/zenodo.7254221"
LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"
REGIONS = (
    {"id": "marin-headlands", "tile": "N36W123", "west": -123, "north": 39, "blockCol": 5, "blockRow": 13,
     "size": 87597650, "etag": '"03bae261ff9ee76a719e982cb46dd1f6-11"', "blockRange": [55804197, 55852710],
     "headerSha256": "edd56b9fb81aa13995a5b012c749be36b7e7db8a45cdfb003e5e89da841e3795",
     "blockSha256": "424f3ae9079cf3aae9b48fc1e517e73c82349e7339286b4b7bcea25544e5d02b"},
    {"id": "everest", "tile": "N27E084", "west": 84, "north": 30, "blockCol": 34, "blockRow": 23,
     "size": 104225123, "etag": '"2fc1bebafb313302624ba67497976a52-13"', "blockRange": [82568980, 82581442],
     "headerSha256": "22d250f8faeaf9755ac0cd26d4fa912bc7db65206adffd678964d639a5ce7831",
     "blockSha256": "e9bba7ea38d8ad8ffd5242c2708a735462a012fb1440db1353855235ced39ffa"},
)
DOCUMENTS = (
    {"path": "provenance/aws-registry.html", "url": "https://registry.opendata.aws/esa-worldcover-vito/",
     "bytes": 12413, "sha256": "90f417590c11506b0280f8b89d0c00fb44a53817e1aa2ae70d0ef814134e54b1"},
    {"path": "provenance/data-access.html", "url": "https://esa-worldcover.org/en/data-access",
     "bytes": 49866, "sha256": "7645b74f70102799bad26dc751706f630cfee7e3e3d60bc25f343053a7a57d5e"},
    {"path": "provenance/WorldCover_PUM_V2.0.pdf", "url": "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/docs/WorldCover_PUM_V2.0.pdf",
     "bytes": 4102952, "sha256": "4301a3d95260d88bd4315f43ccf2a12ef74ad391109b9f36e22b6e51d8490107"},
)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def json_bytes(data) -> bytes:
    return (json.dumps(data, sort_keys=True, indent=2, ensure_ascii=True) + "\n").encode("ascii")


def write_atomic(path: Path, raw: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(raw)
    temporary.replace(path)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ValueError("Redirect refused; review the source identity before changing download policy")


def source_url(region: dict) -> str:
    return BASE + f'ESA_WorldCover_10m_2021_v200_{region["tile"]}_Map.tif'


def fetch_range(region: dict, start: int, end: int) -> bytes:
    length = end - start + 1
    if length < 1 or length > MAX_RANGE_BYTES or start < 0 or end >= region["size"]:
        raise ValueError("Source range exceeds reviewed bounds")
    request = urllib.request.Request(source_url(region), headers={"Range": f"bytes={start}-{end}", "If-Match": region["etag"]})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=90) as response:
        if response.status != 206 or response.headers.get("Content-Range") != f'bytes {start}-{end}/{region["size"]}':
            raise ValueError("Exact byte range not honored; whole-file fallback is forbidden")
        if response.headers.get("ETag") != region["etag"]:
            raise ValueError("Remote object identity changed")
        raw = response.read(length + 1)
        if len(raw) != length:
            raise ValueError("Wrong source range response size")
        return raw


def header_tags(raw: bytes, region: dict) -> dict:
    if len(raw) != HEADER_SIZE or raw[:4] != b"II*\x00":
        raise ValueError("Expected pinned little-endian classic TIFF header")
    stream = io.BytesIO(raw)
    directory = TiffImagePlugin.ImageFileDirectory_v2(ifh=stream.read(8))
    stream.seek(struct.unpack("<I", raw[4:8])[0])
    # Read only the IFD, never ask a decoder to allocate the 36000 x 36000 source image.
    with warnings.catch_warnings():
        warnings.simplefilter("error")
        directory.load(stream)
        tags = dict(directory)
    expected = {256: 36000, 257: 36000, 258: (8,), 259: 8, 262: 3, 277: 1, 284: 1,
                317: 1, 322: BLOCK_SIZE, 323: BLOCK_SIZE, 339: (1,), 42113: "0"}
    if any(tags.get(k) != v for k, v in expected.items()):
        raise ValueError("Unexpected WorldCover Map raster encoding")
    keys = tags[34735]
    if len(keys) != 4 + 4 * keys[3]:
        raise ValueError("Invalid GeoTIFF key directory")
    geo = {keys[i]: tuple(keys[i + 1:i + 4]) for i in range(4, len(keys), 4)}
    if geo.get(1025) != (0, 1, 1) or geo.get(2048) != (0, 1, 4326):
        raise ValueError("Expected PixelIsArea EPSG:4326")
    if tags[33922] != (0, 0, 0, region["west"], region["north"], 0) or tags[33550] != (STEP, STEP, 0):
        raise ValueError("Original source georeferencing changed")
    if len(tags[324]) != 1296 or len(tags[325]) != 1296:
        raise ValueError("Unexpected native tile layout")
    index = region["blockRow"] * 36 + region["blockCol"]
    if [tags[324][index], tags[324][index] + tags[325][index] - 1] != region["blockRange"]:
        raise ValueError("Source block offsets differ from reviewed range")
    metadata = {node.attrib.get("name"): node.text for node in ET.fromstring(tags[42112])}
    if metadata.get("algorithm_version") != "V2.0.0" or "ESA WorldCover project 2021" not in metadata.get("copyright", ""):
        raise ValueError("Source release/attribution metadata differs")
    return tags


def decode_block(header: bytes, compressed: bytes, region: dict) -> tuple[np.ndarray, dict]:
    if digest(header) != region["headerSha256"] or digest(compressed) != region["blockSha256"]:
        raise ValueError("Source bytes differ from pinned WorldCover data")
    tags = header_tags(header, region)
    decoder = zlib.decompressobj()
    unpacked = decoder.decompress(compressed, BLOCK_SIZE * BLOCK_SIZE + 1)
    if len(unpacked) != BLOCK_SIZE * BLOCK_SIZE or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("Wrong compressed block size or trailing stream")
    independent = np.frombuffer(unpacked, dtype=np.uint8).reshape(BLOCK_SIZE, BLOCK_SIZE)
    if not set(np.unique(independent).tolist()) <= CLASS_NAMES.keys():
        raise ValueError("Unknown categorical code; refusing to relabel it")
    # Independent Pillow/libtiff decoding of the unchanged DEFLATE payload in a one-block wrapper.
    ifd = TiffImagePlugin.ImageFileDirectory_v2()
    for tag in (256, 257, 258, 259, 262, 277, 284, 317, 320, 322, 323, 339):
        ifd[tag] = tags[tag]
    ifd[256], ifd[257], ifd[324], ifd[325] = BLOCK_SIZE, BLOCK_SIZE, (0,), (len(compressed),)
    encoded_ifd = ifd.tobytes(offset=8)
    ifd[324] = (8 + len(encoded_ifd),)
    updated_ifd = ifd.tobytes(offset=8)
    if len(encoded_ifd) != len(updated_ifd):
        raise ValueError("Unstable one-block TIFF wrapper")
    wrapper = b"II*\x00" + struct.pack("<I", 8) + updated_ifd + compressed
    with Image.open(io.BytesIO(wrapper)) as image:
        independent_tiff = np.array(image, dtype=np.uint8)
    if not np.array_equal(independent, independent_tiff):
        raise ValueError("Independent decoders disagree")
    return independent.copy(), tags


def source_artifacts() -> list[dict]:
    artifacts = []
    for region in REGIONS:
        for filename, start, end, sha in (("source-header.bin", 0, HEADER_SIZE - 1, region["headerSha256"]),
                                         ("source-block.bin", *region["blockRange"], region["blockSha256"])):
            artifacts.append({"path": region["id"] + "/" + filename, "url": source_url(region),
                              "range": [start, end], "bytes": end - start + 1, "sha256": sha})
    return artifacts + list(DOCUMENTS)


def expected_lock() -> dict:
    return {"schema": "earth-worldcover-range-lock-v1", "retrievalDateUtc": "2026-10-09",
            "dataset": "ESA WorldCover 10 m 2021 v200", "datasetDoi": "https://doi.org/10.5281/zenodo.7254221",
            "epoch": "2021-01-01/2021-12-31", "horizontalCrs": "EPSG:4326", "verticalData": "none",
            "nativeStepDegrees": STEP, "originalRasterType": "PixelIsArea", "sampling": "pixel-area-containing-cell-v1",
            "license": "CC-BY-4.0", "licenseUrl": LICENSE_URL, "attribution": ATTRIBUTION,
            "modifications": "Two native 1024 by 1024 blocks extracted and losslessly repacked into ELC1; codes unchanged.",
            "regions": list(REGIONS), "artifacts": source_artifacts()}


def restore_missing(output: Path):
    for artifact in source_artifacts():
        path = output / artifact["path"]
        if path.exists():
            continue
        if "range" in artifact:
            region = next(region for region in REGIONS if artifact["path"].startswith(region["id"] + "/"))
            raw = fetch_range(region, *artifact["range"])
        else:
            with urllib.request.build_opener(NoRedirect).open(artifact["url"], timeout=90) as response:
                if response.status != 200:
                    raise ValueError("Unexpected documentation HTTP status")
                raw = response.read(artifact["bytes"] + 1)
        if len(raw) != artifact["bytes"] or digest(raw) != artifact["sha256"]:
            raise ValueError("Downloaded artifact changed; retain original provenance snapshot: " + artifact["path"])
        write_atomic(path, raw)


def rebuild(output: Path, download: bool = False) -> dict:
    if download:
        restore_missing(output)
    expected = expected_lock()
    lock_path = output / "source-lock.json"
    if lock_path.exists() and json.loads(lock_path.read_text()) != expected:
        raise ValueError("Provenance lock differs from reviewed constants")
    for artifact in expected["artifacts"]:
        raw = (output / artifact["path"]).read_bytes()
        if len(raw) != artifact["bytes"] or digest(raw) != artifact["sha256"]:
            raise ValueError("Pinned artifact checksum/size mismatch: " + artifact["path"])
    props = {"format": "earth-landcover-pack-v1", "sampling": "pixel-area-containing-cell-v1",
             "dataset.id": "esa-worldcover-2021-v200", "dataset.epoch": "2021", "dataset.crs": "EPSG:4326",
             "dataset.license": "CC-BY-4.0", "dataset.licenseUrl": LICENSE_URL,
             "dataset.source": "https://doi.org/10.5281/zenodo.7254221", "dataset.attribution": ATTRIBUTION,
             "dataset.citation": CITATION, "dataset.modifications": expected["modifications"], "tile.count": len(REGIONS)}
    reports, controls, derived = [], [], []
    for i, region in enumerate(REGIONS):
        directory = output / region["id"]
        array, tags = decode_block((directory / "source-header.bin").read_bytes(), (directory / "source-block.bin").read_bytes(), region)
        col, row = region["blockCol"] * BLOCK_SIZE, region["blockRow"] * BLOCK_SIZE
        west, north = region["west"] + col * STEP, region["north"] - row * STEP
        payload = struct.pack(">III4d", 0x454C4331, BLOCK_SIZE, BLOCK_SIZE, west, north, STEP, STEP) + array.tobytes()
        relative = region["id"] + "/landcover.elc"
        write_atomic(output / relative, payload)
        derived.append(relative)
        props.update({f"tile.{i}.id": region["id"], f"tile.{i}.path": relative, f"tile.{i}.sha256": digest(payload)})
        codes, counts = np.unique(array, return_counts=True)
        class_counts = {str(int(code)): int(count) for code, count in zip(codes, counts)}
        reports.append({"id": region["id"], "sourceUrl": source_url(region), "sourceBlockIndex": row // BLOCK_SIZE * 36 + col // BLOCK_SIZE,
                        "originalColumnStart": col, "originalRowStart": row, "width": BLOCK_SIZE, "height": BLOCK_SIZE,
                        "westEdge": west, "northEdge": north, "eastEdge": west + BLOCK_SIZE * STEP,
                        "southEdge": north - BLOCK_SIZE * STEP, "stepDegrees": STEP, "classCounts": class_counts,
                        "originalCodeBytesSha256": digest(array.tobytes()), "elcSha256": digest(payload)})
        # One source-cell-center check per present class, nearest the block center; these are data checks, not surveyed landmarks.
        yy, xx = np.indices(array.shape)
        distance = (yy - 512)**2 + (xx - 512)**2
        for code in codes:
            y, x = np.unravel_index(np.where(array == code, distance, np.iinfo(np.int64).max).argmin(), array.shape)
            controls.append([region["id"], north - (int(y) + 0.5) * STEP, west + (int(x) + 0.5) * STEP, int(code), col + int(x), row + int(y)])
    report = {"schema": "earth-worldcover-import-report-v1", "sourceRangeBytes": sum(a["bytes"] for a in expected["artifacts"] if "range" in a),
              "wholeSourceObjectBytesNotDownloaded": sum(r["size"] for r in REGIONS),
              "nativeCells": len(REGIONS) * BLOCK_SIZE * BLOCK_SIZE, "independentDecodersAgreeBitExactly": True,
              "noTerrainElevationChanges": True, "regions": reports,
              "limitations": ["Satellite-classified 2021 land cover, not full biomes, climate, species, forest inventory or geology.",
                              "Permanent-water labels have no water level, depth, coastline datum or tide stage; keep existing water logic separate.",
                              "Snow and glacier shadows may be misclassified as water; Everest water labels are not verified lakes.",
                              "No-data/outside coverage stays unknown; no grass fallback. No subpixel measured detail is invented."]}
    write_atomic(lock_path, json_bytes(expected))
    write_atomic(output / "import-report.json", json_bytes(report))
    properties_text = "\n".join(f"{k}={str(v).replace(chr(169), r'\u00a9')}" for k, v in props.items()) + "\n"
    write_atomic(output / "landcover.properties", properties_text.encode("ascii"))
    controls_text = "region\tlatitude\tlongitude\tclassCode\tsourceColumn\tsourceRow\n" + "".join("\t".join(str(v) for v in row) + "\n" for row in controls)
    write_atomic(output / "control-points.tsv", controls_text.encode("ascii"))
    paths = sorted([a["path"] for a in expected["artifacts"]] + derived + ["source-lock.json", "import-report.json", "landcover.properties", "control-points.tsv"])
    write_atomic(output / "SHA256SUMS", ("".join(f"{digest((output / path).read_bytes())}  {path}\n" for path in paths)).encode("ascii"))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="Restore only missing pinned source bytes, with strict transfer caps")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    try:
        print(json.dumps(rebuild(args.output.resolve(), args.download), indent=2))
    except (OSError, ValueError, KeyError, zlib.error) as error:
        parser.exit(1, f"WorldCover import failed: {error}\n")


if __name__ == "__main__":
    main()
