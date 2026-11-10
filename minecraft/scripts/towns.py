#!/usr/bin/env python3
"""Packs GeoNames' towns of 1,000 people or more (CC BY 4.0) into src/main/resources/earthplatform/towns.bin.

usage: towns.py cities1000.txt admin1CodesASCII.txt countryInfo.txt
Get the inputs from https://download.geonames.org/export/dump/ (cities1000.zip holds cities1000.txt).

Format, gzip compressed: "EPT1"; region count (varint) and each region's name (varint length, UTF-8),
"State, Country" or just "Country"; town count (varint); then per town, most people first: its name
(varint length, UTF-8), its search key (varint length, ASCII a-z and 0-9 only, from the name with its accents off, or GeoNames' ASCII name;
length 0 when the name is plain ASCII and the key is just the name in lower case with everything but a-z and 0-9 dropped),
its region's index (varint), and latitude and longitude in 1/1000 degree (zigzag varints), about 100 m.
Towns beyond 80 degrees north or south are left out: the Earth world stops there.
"""
import gzip, re, sys, pathlib, unicodedata

def varint(out, n):
    while True:
        b = n & 0x7F
        n >>= 7
        if n: out.append(b | 0x80)
        else: out.append(b); return

def zigzag(n): return (n << 1) ^ (n >> 63)

def text(out, s):
    varint(out, len(s)); out += s

def main():
    towns_file, admin_file, country_file = sys.argv[1:4]
    states = {}
    for line in open(admin_file, encoding="utf-8"):
        code, name = line.rstrip("\n").split("\t")[:2]
        states[code] = name
    countries = {}
    for line in open(country_file, encoding="utf-8"):
        if line.startswith("#"): continue
        parts = line.rstrip("\n").split("\t")
        countries[parts[0]] = parts[4]
    regions, towns = {}, []
    for line in open(towns_file, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        name, ascii_name, lat, lon, cc, admin1, people = p[1], p[2], float(p[4]), float(p[5]), p[8], p[10], int(p[14] or 0)
        # The key is the name with its accents off when that leaves plain letters ("Zürich" is found by
        # "zurich", where GeoNames' ASCII name is "Zuerich"); otherwise GeoNames' ASCII name ("Gießen").
        bare = "".join(c for c in unicodedata.normalize("NFD", name) if not unicodedata.combining(c))
        key = re.sub("[^a-z0-9]", "", (bare if bare.isascii() else ascii_name).lower())
        if not key or abs(lat) > 80: continue
        country = countries.get(cc, cc)
        state = states.get(cc + "." + admin1)
        region = state + ", " + country if state and state != name and state != country else country
        towns.append((-people, name, key, regions.setdefault(region, len(regions)), round(lat * 1000), round(lon * 1000)))
    towns.sort()
    out = bytearray(b"EPT1")
    varint(out, len(regions))
    for region in regions: text(out, region.encode("utf-8"))
    varint(out, len(towns))
    for _, name, key, region, lat, lon in towns:
        text(out, name.encode("utf-8"))
        text(out, b"" if name.isascii() and key == re.sub("[^a-z0-9]", "", name.lower()) else key.encode("ascii")); varint(out, region)
        varint(out, zigzag(lat)); varint(out, zigzag(lon))
    target = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/earthplatform/towns.bin"
    target.write_bytes(gzip.compress(bytes(out), 9, mtime=0))
    print(f"{len(towns)} towns, {len(regions)} regions, {target.stat().st_size} bytes")

if __name__ == "__main__":
    main()
