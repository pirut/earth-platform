#!/usr/bin/env python3
"""Packs Natural Earth's 1:50m country borders (public domain) into src/main/resources/earthplatform/countries.bin.

usage: countries.py ne_50m_admin_0_countries.geojson
Get the input from https://github.com/nvkelso/natural-earth-vector (geojson/ne_50m_admin_0_countries.geojson).

Format, gzip compressed: "EPC1", country count (varint); per country its English name (varint length, UTF-8),
ring count (varint), and per ring the point count (varint) then longitude and latitude in 1/10000 degree,
each as a zigzag varint delta from the ring's previous point (the first from 0, 0).
"""
import gzip, json, sys, pathlib

def varint(out, n):
    while True:
        b = n & 0x7F
        n >>= 7
        if n: out.append(b | 0x80)
        else: out.append(b); return

def zigzag(n): return (n << 1) ^ (n >> 63)

def main():
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    out = bytearray(b"EPC1")
    features = data["features"]
    varint(out, len(features))
    for f in features:
        props = f["properties"]
        name = (props.get("NAME_EN") or props["NAME"]).encode("utf-8")
        varint(out, len(name)); out += name
        geom = f["geometry"]
        polygons = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
        rings = [ring for polygon in polygons for ring in polygon]
        varint(out, len(rings))
        for ring in rings:
            points = []
            for lon, lat in ring:
                p = (round(lon * 10000), round(lat * 10000))
                if not points or points[-1] != p: points.append(p)
            varint(out, len(points))
            px = py = 0
            for x, y in points:
                varint(out, zigzag(x - px)); varint(out, zigzag(y - py)); px, py = x, y
    target = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/earthplatform/countries.bin"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(gzip.compress(bytes(out), 9, mtime=0))
    print(target, target.stat().st_size, "bytes,", len(features), "countries")

main()
