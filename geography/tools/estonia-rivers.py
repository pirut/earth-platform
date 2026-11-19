#!/usr/bin/env python3
"""Pack ETAK survey river centrelines for the game.

Input: eelis_tervikvooluveed_pohiteljed.gpkg extracted from "Estonian Nature
Information System and ETAK watercourses combined", 2026-11-14 snapshot.
https://geoportaal.maaruum.ee/eng/Spatial-Data/Estonian-Topographic-Database/Download-Topographic-Data-p618.html
Requires pyproj. Usage: estonia-rivers.py INPUT.gpkg OUTPUT.bin.gz
"""
import argparse
import gzip
import sqlite3
import struct
from pathlib import Path

from pyproj import Transformer


def points(blob):
    """Read a 2D LineString from the GeoPackage's geometry record."""
    envelope = (0, 32, 48, 48, 64)[(blob[3] >> 1) & 7]
    offset = 8 + envelope
    order = "<" if blob[offset] else ">"
    kind, count = struct.unpack_from(order + "II", blob, offset + 1)
    if kind != 2 or len(blob) != offset + 9 + count * 16:
        raise ValueError("Expected a 2D LineString")
    return zip(*struct.iter_unpack(order + "dd", blob[offset + 9:]))


def simplify(line, tolerance):
    """Keep the course within tolerance of its surveyed shape."""
    keep = {0, len(line) - 1}
    ranges = [(0, len(line) - 1)]
    while ranges:
        first, last = ranges.pop()
        x, z = line[first]
        dx, dz = line[last][0] - x, line[last][1] - z
        length = dx * dx + dz * dz
        furthest, split = tolerance * tolerance, None
        for i in range(first + 1, last):
            px, pz = line[i][0] - x, line[i][1] - z
            t = min(1, max(0, (px * dx + pz * dz) / length)) if length else 0
            distance = (px - t * dx) ** 2 + (pz - t * dz) ** 2
            if distance > furthest:
                furthest, split = distance, i
        if split is not None:
            keep.add(split)
            ranges.extend(((first, split), (split, last)))
    return [line[i] for i in sorted(keep)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    mercator = Transformer.from_crs(3301, 3857, always_xy=True)
    lines = []
    with sqlite3.connect(args.input) as db:
        for (geometry,) in db.execute("""
                SELECT geom FROM eelis_tervikvooluveed_pohiteljed
                WHERE kkr_tyyp_tekst = 'Jõgi' AND geom IS NOT NULL
                ORDER BY id
                """):
            x, y = mercator.transform(*points(geometry))
            # Metre precision in Web Mercator is about half a metre on the ground here.
            line = []
            for point in zip(map(round, x), map(round, (-n for n in y))):
                if not line or point != line[-1]:
                    line.append(point)
            if len(line) > 1:
                lines.append(simplify(line, 1.5))

    with args.output.open("wb") as output, gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0) as data:
        data.write(struct.pack(">I", len(lines)))
        for line in lines:
            data.write(struct.pack(">I", len(line)))
            x, z = line[0]
            data.write(struct.pack(">ii", x, z))
            for next_x, next_z in line[1:]:
                data.write(struct.pack(">hh", next_x - x, next_z - z))
                x, z = next_x, next_z


if __name__ == "__main__":
    main()
