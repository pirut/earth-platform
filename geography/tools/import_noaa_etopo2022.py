#!/usr/bin/env python3
"""Bounded NOAA ETOPO 2022 importer. No credentials, scraping or global download.

Offline (default): verify pinned raw TIFFs and rebuild EGP1 packs/manifests.
--download: retrieve missing pinned raw files, with hard 4 MiB per-file limit.
--initialize: explicitly establish a new local provenance lock; never refresh a lock.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import io
import json
import math
from pathlib import Path
import struct
import sys
import urllib.parse
import urllib.request

import numpy as np
from PIL import Image

STEP = 1 / 240
MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
EXPORT_URL = "https://gis.ngdc.noaa.gov/arcgis/rest/services/DEM_mosaics/DEM_all/ImageServer/exportImage"
CONFIG_URL = "https://www.ncei.noaa.gov/maps/grid-extract/js/app/datasets.json"
PRODUCT_URL = "https://www.ncei.noaa.gov/products/etopo-global-relief-model"
METADATA_URL = "https://data.noaa.gov/metaview/page?header=none&view=getDataView&xml=NOAA/NESDIS/NGDC/MGG/DEM//iso/xml/etopo_2022.xml"
DATASET_NAME = "ETOPO_2022 (Ice Surface; 15 arcseconds)"
MOSAIC_NAME = "ETOPO_2022_v1_15s_surface_elev"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[1] / "data" / "noaa-etopo2022-proof"
# Grid-edge coordinates are integer multiples of 15 arcseconds, not sample centers.
REGIONS = (
    {"id": "san-francisco", "westSteps": -29440, "northSteps": 9120,
     "width": 120, "height": 120, "landmark": [37.7749, -122.4194]},
    {"id": "everest", "westSteps": 20832, "northSteps": 6744,
     "width": 60, "height": 60, "landmark": [27.9881, 86.9250]},
    {"id": "challenger-deep", "westSteps": 34152, "northSteps": 2784,
     "width": 120, "height": 96, "landmark": [11.3733, 142.5917]},
)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def bounded_download(url: str, limit: int = MAX_DOWNLOAD_BYTES) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "EarthPlatform-ETOPO-proof/1.0 (bounded educational data extraction)"})
    with urllib.request.urlopen(request, timeout=90) as response:
        content_length = response.headers.get("Content-Length")
        if content_length and int(content_length) > limit:
            raise ValueError(f"Remote Content-Length exceeds {limit} bytes")
        body = response.read(limit + 1)
        if len(body) > limit:
            raise ValueError(f"Remote response exceeds {limit} bytes")
        return body


def bounds(region: dict) -> tuple[float, float, float, float]:
    return (region["westSteps"] / 240,
            (region["northSteps"] - region["height"]) / 240,
            (region["westSteps"] + region["width"]) / 240,
            region["northSteps"] / 240)


def export_url(region: dict) -> str:
    parameters = {
        "bbox": ",".join(format(value, ".17g") for value in bounds(region)),
        "bboxSR": "4326", "size": f'{region["width"]},{region["height"]}',
        "imageSR": "4326", "format": "tiff", "pixelType": "F32",
        "interpolation": "RSP_NearestNeighbor", "compression": "LZ77",
        "renderingRule": '{"rasterFunction":"none"}',
        "mosaicRule": json.dumps({"where": f"Name='{MOSAIC_NAME}'"}, separators=(",", ":")),
        "f": "image",
    }
    return EXPORT_URL + "?" + urllib.parse.urlencode(parameters)


def read_tiff(raw: bytes, region: dict) -> np.ndarray:
    if raw[:4] not in (b"II*\x00", b"MM\x00*"):
        raise ValueError(f'{region["id"]}: NOAA did not return a TIFF: {raw[:200]!r}')
    with Image.open(io.BytesIO(raw)) as image:
        if image.size != (region["width"], region["height"]) or image.mode != "F":
            raise ValueError(f"Unexpected TIFF dimensions/type: {image.size}, {image.mode}")
        tags = image.tag_v2
        scale, tiepoint = tags.get(33550), tags.get(33922)
        west, _, _, north = bounds(region)
        if not scale or not tiepoint:
            raise ValueError("TIFF missing geographic scale/tiepoint tags")
        expected = (STEP, STEP, 0, 0, west, north)
        actual = (scale[0], scale[1], tiepoint[0], tiepoint[1], tiepoint[3], tiepoint[4])
        if any(not math.isclose(a, e, rel_tol=0, abs_tol=1e-10) for a, e in zip(actual, expected)):
            raise ValueError(f"TIFF geographic metadata differs: {actual} != {expected}")
        directory = tags.get(34735)
        if not directory or len(directory) != 4 + 4 * directory[3]:
            raise ValueError("Invalid GeoTIFF key directory")
        keys = {directory[i]: tuple(directory[i + 1:i + 4]) for i in range(4, len(directory), 4)}
        if keys.get(1025) != (0, 1, 1) or keys.get(2048) != (0, 1, 4326):
            raise ValueError("GeoTIFF must explicitly be PixelIsArea, EPSG:4326")
        if tags.get(339) != (3,) or tags.get(258) != (32,):
            raise ValueError("GeoTIFF must be float32")
        array = np.asarray(image, dtype=np.float32).copy()
        no_data = tags.get(42113)
        if no_data is not None:
            try:
                sentinel = np.float32(str(no_data).strip("\x00"))
            except ValueError as exception:
                raise ValueError(f"Unrecognized TIFF no-data: {no_data}") from exception
            array[array == sentinel] = np.nan
        # ArcGIS may encode no-data using float32 extremes without a GDAL_NODATA tag.
        array[~np.isfinite(array) | (np.abs(array) > 100_000)] = np.nan
    if not np.any(np.isfinite(array)):
        raise ValueError(f'{region["id"]}: all samples are no-data')
    return array


def statistics(array: np.ndarray) -> dict:
    valid = array[np.isfinite(array)]
    return {"sampleCount": int(array.size), "validSamples": int(valid.size),
            "noDataSamples": int(array.size - valid.size),
            "minimumMeters": float(valid.min()), "maximumMeters": float(valid.max()),
            "meanMeters": float(valid.astype(np.float64).mean()),
            "negativeSamples": int(np.count_nonzero(valid < 0)),
            "positiveSamples": int(np.count_nonzero(valid > 0))}


def bilinear(array: np.ndarray, region: dict, latitude: float, longitude: float) -> float:
    x = (longitude - (region["westSteps"] + .5) / 240) / STEP
    y = ((region["northSteps"] - .5) / 240 - latitude) / STEP
    if not (0 <= x <= array.shape[1] - 1 and 0 <= y <= array.shape[0] - 1):
        raise ValueError("Landmark outside sample-center bounds")
    x0 = min(int(math.floor(x)), array.shape[1] - 2)
    y0 = min(int(math.floor(y)), array.shape[0] - 2)
    tx, ty = x - x0, y - y0
    return float((float(array[y0, x0]) * (1 - tx) + float(array[y0, x0 + 1]) * tx) * (1 - ty)
                 + (float(array[y0 + 1, x0]) * (1 - tx) + float(array[y0 + 1, x0 + 1]) * tx) * ty)


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n").encode()


def service_evidence_requests() -> dict[str, str]:
    base = EXPORT_URL.rsplit("/", 1)[0]
    requests = {
        "catalog": base + "/query?" + urllib.parse.urlencode({"where": f"Name='{MOSAIC_NAME}'", "outFields": "*", "returnGeometry": "false", "f": "json"}),
        "raster-info": base + "/2527/info?f=json",
    }
    for region in REGIONS:
        indices = ((0, 0), (region["width"] // 2, region["height"] // 2), (region["width"] - 1, region["height"] - 1))
        points = [[(region["westSteps"] + x + .5) / 240, (region["northSteps"] - y - .5) / 240] for x, y in indices]
        parameters = {"geometry": json.dumps({"points": points, "spatialReference": {"wkid": 4326}}),
                      "geometryType": "esriGeometryMultipoint", "mosaicRule": json.dumps({"where": f"Name='{MOSAIC_NAME}'"}),
                      "interpolation": "RSP_NearestNeighbor", "pixelSize": json.dumps({"x": STEP, "y": STEP, "spatialReference": {"wkid": 4326}}),
                      "outFields": "Name,CellsizeArcseconds", "returnFirstValueOnly": "false", "f": "json"}
        requests["samples-" + region["id"]] = base + "/getSamples?" + urllib.parse.urlencode(parameters)
    return requests


def capture_service_evidence(output: Path) -> dict:
    evidence = {}
    for name, url in service_evidence_requests().items():
        raw = bounded_download(url)
        value = json.loads(raw)
        if "error" in value:
            raise ValueError(f"NOAA source-selection API error: {value['error']}")
        path = "provenance/service-" + name + ".json"
        write_atomic(output / path, raw)
        evidence[name] = {"url": url, "path": path, "sha256": digest(raw)}
    validate_service_evidence(output, evidence)
    return evidence


def validate_service_evidence(output: Path, evidence: dict) -> None:
    values = {}
    requests = service_evidence_requests()
    if set(evidence) != set(requests):
        raise ValueError("Missing required NOAA source-selection evidence")
    for name, url in requests.items():
        record = evidence[name]
        if record["url"] != url or record["path"] != "provenance/service-" + name + ".json":
            raise ValueError("Source-selection evidence URL/path mismatch")
        raw = (output / record["path"]).read_bytes()
        if digest(raw) != record["sha256"]:
            raise ValueError("Source-selection evidence hash mismatch")
        values[name] = json.loads(raw)
    features = values["catalog"].get("features", [])
    if len(features) != 1:
        raise ValueError("ETOPO source filter did not select exactly one raster")
    attributes = features[0]["attributes"]
    if attributes.get("OBJECTID") != 2527 or attributes.get("Name") != MOSAIC_NAME or attributes.get("CellsizeArcseconds") != 15:
        raise ValueError("ETOPO source identity changed; review before importing")
    info = values["raster-info"]
    spatial_reference = info.get("extent", {}).get("spatialReference", {})
    if info.get("bandCount") != 1 or info.get("pixelType") != "F32" or spatial_reference.get("latestWkid") != 4326 or spatial_reference.get("latestVcsWkid") != 3855:
        raise ValueError("ETOPO source raster type/CRS/datum mismatch")
    if not all(math.isclose(info[key], STEP, rel_tol=0, abs_tol=1e-15) for key in ("pixelSizeX", "pixelSizeY")):
        raise ValueError("ETOPO source raster resolution mismatch")
    for region in REGIONS:
        source = read_tiff((output / f'regions/{region["id"]}/raw.tif').read_bytes(), region)
        samples = values["samples-" + region["id"]].get("samples", [])
        if len(samples) != 3:
            raise ValueError("Expected exactly three source-identity checks per region")
        indices = ((0, 0), (region["width"] // 2, region["height"] // 2), (region["width"] - 1, region["height"] - 1))
        for index, (sample, (x, y)) in enumerate(zip(samples, indices)):
            if sample.get("rasterId") != 2527 or sample.get("locationId") != index or sample.get("attributes", {}).get("Name") != MOSAIC_NAME:
                raise ValueError("Unexpected fallback raster in NOAA source-selection checks")
            if not math.isclose(float(sample["value"]), float(source[y, x]), rel_tol=0, abs_tol=1e-5):
                raise ValueError("Exported pixel differs from independently queried source raster")


def write_pack(output: Path, region: dict, array: np.ndarray, tile_id: str, x_start: int, x_end: int) -> dict:
    tile = array[:, x_start:x_end]
    height, width = tile.shape
    west = (region["westSteps"] + x_start + .5) / 240
    north = (region["northSteps"] - .5) / 240
    header = struct.pack(">III4d", 0x45475031, width, height, west, north, STEP, STEP)
    body = header + tile.astype(">f4").tobytes(order="C")
    path = f'regions/{region["id"]}/{tile_id}.egp'
    write_atomic(output / path, body)
    return {"id": tile_id, "path": path, "sha256": digest(body), "west": west,
            "north": north, "stepLon": STEP, "stepLat": STEP,
            "width": width, "height": height, "region": region["id"],
            "statistics": statistics(tile)}


def write_manifest(output: Path, tiles: list[dict], filename: str, origin_lat: float, origin_lon: float, standard_parallel: float) -> None:
    properties = {
        "world.id": "noaa-etopo2022-proof" + ("-global" if origin_lat == 0 else "-sf"),
        "world.seed": "20221009", "generator.version": "surface-fill-v1",
        "palette.version": "geography-palette-v1",
        "projection.id": "equirectangular-v1", "projection.originLat": origin_lat,
        "projection.originLon": origin_lon, "projection.standardParallel": standard_parallel,
        "interpolation": "bilinear-v1", "dataset.id": "noaa-etopo-2022-surface-15s",
        "dataset.release": "ETOPO_2022_v1_15s_surface_elev", "dataset.datum": "EPSG:3855 (EGM2008 orthometric height, meters)",
        "dataset.resolutionArcSeconds": "15", "dataset.license": "CC0-1.0",
        "dataset.source": PRODUCT_URL, "dataset.quality": "native-15-arcsecond-public-global-relief; bounded-three-region-proof",
        "dataset.attribution": "NOAA National Centers for Environmental Information (2022). ETOPO 2022 15 Arc-Second Global Relief Model. DOI:10.25921/fd45-gt74.",
        "tile.count": len(tiles),
    }
    lines = ["# EGP1 sample centers; horizontal EPSG:4326; elevation meters EGM2008.",
             "# Region-only proof: missing coverage must fail explicitly; never synthesize Earth."]
    for key, value in properties.items():
        lines.append(f"{key}={value}")
    for index, tile in enumerate(tiles):
        for key in ("id", "path", "sha256", "west", "north", "stepLon", "stepLat", "width", "height"):
            lines.append(f"tile.{index}.{key}={tile[key]}")
    write_atomic(output / filename, ("\n".join(lines) + "\n").encode("ascii"))


def initialize_lock(output: Path) -> dict:
    config_raw = bounded_download(CONFIG_URL)
    config = json.loads(config_raw)
    entries = [entry for entry in config if entry.get("name") == DATASET_NAME]
    if len(entries) != 1 or not math.isclose(float(entries[0]["resolution"]), STEP, rel_tol=0, abs_tol=1e-15):
        raise ValueError("Official extractor configuration no longer matches expected dataset")
    if MOSAIC_NAME not in entries[0]["urlTemplate"] or not entries[0]["urlTemplate"].startswith(EXPORT_URL):
        raise ValueError("Official extractor configuration has an unexpected source")
    write_atomic(output / "provenance" / "noaa-datasets.json", config_raw)
    metadata_raw = bounded_download(METADATA_URL)
    if b"CC0-1.0" not in metadata_raw or b"3855" not in metadata_raw or b"fd45-gt74" not in metadata_raw:
        raise ValueError("Official metadata does not contain expected license, datum and DOI evidence")
    write_atomic(output / "provenance" / "noaa-metadata.html", metadata_raw)
    # The static config is data only. No downloaded JavaScript is executed.
    lock = {"schema": "earth-noaa-import-lock-v1", "retrievalDateUtc": datetime.now(timezone.utc).date().isoformat(),
            "datasetId": "noaa-etopo-2022-surface-15s", "release": MOSAIC_NAME,
            "productUrl": PRODUCT_URL, "metadataUrl": METADATA_URL,
            "doi": "10.25921/fd45-gt74", "license": "CC0-1.0",
            "licenseUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
            "horizontalCrs": "EPSG:4326", "verticalCrs": "EPSG:3855",
            "verticalDatum": "EGM2008 orthometric height", "units": "meters",
            "nativeResolutionArcSeconds": 15, "variant": "ice surface",
            "sourceConfiguration": {"url": CONFIG_URL, "path": "provenance/noaa-datasets.json", "sha256": digest(config_raw), "selectedEntry": entries[0]},
            "sourceMetadata": {"url": METADATA_URL, "path": "provenance/noaa-metadata.html", "sha256": digest(metadata_raw)},
            "maximumDownloadBytesPerRegion": MAX_DOWNLOAD_BYTES, "regions": []}
    def fetch(region: dict) -> tuple[dict, bytes]:
        url = export_url(region)
        raw = bounded_download(url)
        read_tiff(raw, region)  # Validate before saving any source bytes.
        return {**region, "url": url, "path": f'regions/{region["id"]}/raw.tif',
                "sha256": digest(raw), "bytes": len(raw), "bboxEdges": list(bounds(region))}, raw
    with ThreadPoolExecutor(max_workers=3) as executor:
        for record, raw in executor.map(fetch, REGIONS):
            write_atomic(output / record["path"], raw)
            lock["regions"].append(record)
            print(f'Downloaded {record["id"]}: {len(raw):,} bytes; SHA256 {record["sha256"]}', flush=True)
    lock["sourceSelectionEvidence"] = capture_service_evidence(output)
    write_atomic(output / "source-lock.json", json_bytes(lock))
    return lock


def run(output: Path, download: bool, initialize: bool) -> dict:
    lock_path = output / "source-lock.json"
    if initialize:
        if lock_path.exists():
            raise ValueError("Refusing to replace an existing provenance lock; use a new output directory")
        lock = initialize_lock(output)
    else:
        if not lock_path.exists():
            raise ValueError("Missing source-lock.json; use --initialize explicitly for first import")
        lock = json.loads(lock_path.read_text())
    if lock.get("schema") != "earth-noaa-import-lock-v1":
        raise ValueError("Unknown provenance lock schema")
    for key, path, url in (("sourceConfiguration", "provenance/noaa-datasets.json", CONFIG_URL),
                           ("sourceMetadata", "provenance/noaa-metadata.html", METADATA_URL)):
        record = lock[key]
        if record["path"] != path or record["url"] != url:
            raise ValueError("Unexpected provenance evidence path or URL")
        if digest((output / path).read_bytes()) != record["sha256"]:
            raise ValueError(f"Provenance evidence hash mismatch: {path}")
    records = lock.get("regions", [])
    if len(records) != len(REGIONS):
        raise ValueError("Provenance lock must contain exactly the three bounded proof regions")
    tiles, reports, arrays = [], [], {}
    for region, record in zip(REGIONS, records):
        if any(record.get(key) != value for key, value in region.items()) or record["url"] != export_url(region):
            raise ValueError("Lock region differs from bounded hardcoded request")
        expected_path = f'regions/{region["id"]}/raw.tif'
        if record.get("path") != expected_path:
            raise ValueError("Lock source path differs from expected local path")
        path = output / expected_path
        if not path.exists():
            if not download:
                raise ValueError(f"Offline source missing: {path}; use --download to retrieve its pinned URL")
            raw = bounded_download(record["url"])
            if digest(raw) != record["sha256"]:
                raise ValueError("Downloaded TIFF differs from pinned bytes; no cache update performed")
            write_atomic(path, raw)
        raw = path.read_bytes()
        if len(raw) > MAX_DOWNLOAD_BYTES or digest(raw) != record["sha256"] or len(raw) != record["bytes"]:
            raise ValueError(f"Source hash/size mismatch: {path}")
        array = read_tiff(raw, region)
        arrays[region["id"]] = array
        tile_ranges = [("san-francisco-west", 0, 62), ("san-francisco-east", 60, 120)] if region["id"] == "san-francisco" else [(region["id"], 0, region["width"])]
        for tile_id, x_start, x_end in tile_ranges:
            tiles.append(write_pack(output, region, array, tile_id, x_start, x_end))
        latitude, longitude = region["landmark"]
        reports.append({"id": region["id"], "rawSha256": digest(raw),
                        "rawBytes": len(raw), "bboxEdges": list(bounds(region)),
                        "statistics": statistics(array),
                        "landmark": {"latitude": latitude, "longitude": longitude,
                                     "bilinearElevationMeters": bilinear(array, region, latitude, longitude)}})
    if not (np.nanmin(arrays["san-francisco"]) < 0 < np.nanmax(arrays["san-francisco"])):
        raise ValueError("San Francisco coast proof has no land/ocean sign transition")
    if np.nanmax(arrays["everest"]) < 8000:
        raise ValueError("Everest proof unexpectedly lacks high-mountain elevations")
    if np.nanmin(arrays["challenger-deep"]) > -10000:
        raise ValueError("Mariana proof unexpectedly lacks deep-ocean elevations")
    validate_service_evidence(output, lock["sourceSelectionEvidence"])
    write_manifest(output, tiles, "world.properties", 37.7749, -122.4194, 37.7749)
    write_manifest(output, tiles, "world-global.properties", 0, 0, 0)
    report = {"schema": "earth-noaa-import-report-v1", "nativeResolutionArcSeconds": 15,
              "regions": reports, "tiles": tiles,
              "seam": {"region": "san-francisco", "overlapColumns": 2,
                       "overlapSourceColumns": [60, 61], "copiedBitIdentically": True},
              "totalDownloadedRasterBytes": sum(record["bytes"] for record in records),
              "caveat": "One-meter game blocks interpolate native 15-arcsecond samples. Neither summit nor trench exact extrema are guaranteed; coverage is only these three regions."}
    write_atomic(output / "import-report.json", json_bytes(report))
    pinned_paths = sorted([record["path"] for record in records]
                          + [tile["path"] for tile in tiles]
                          + ["source-lock.json", "import-report.json", "world.properties", "world-global.properties",
                             "provenance/noaa-datasets.json", "provenance/noaa-metadata.html"]
                          + [record["path"] for record in lock["sourceSelectionEvidence"].values()])
    checksum_lines = [f"{digest((output / path).read_bytes())}  {path}" for path in pinned_paths]
    write_atomic(output / "SHA256SUMS", ("\n".join(checksum_lines) + "\n").encode("ascii"))
    print(json.dumps({"report": str(output / "import-report.json"), "regions": reports}, indent=2))
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--download", action="store_true", help="Fetch only missing raw TIFFs, checking their existing pinned hashes")
    parser.add_argument("--initialize", action="store_true", help="Create the initial bounded import lock in a directory without one")
    args = parser.parse_args()
    try:
        run(args.output.resolve(), args.download, args.initialize)
    except (OSError, ValueError, KeyError) as exception:
        print(f"Import failed: {exception}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
