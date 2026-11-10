#!/usr/bin/env python3
"""Makes the one-click install packs: a Modrinth pack (.mrpack) for NeoForge and one for Fabric.

usage: modpack.py <version> <neoforge jar> <fabric jar> <out dir> [name suffix]

A launcher that opens .mrpack files (Modrinth App, Prism Launcher, ATLauncher and others) sets up
Minecraft 1.21.1, the mod loader, Earth Platform, Sodium, Iris and the Photon shader pack from one
file, with Photon switched on. The Earth Platform jar rides inside the pack; the other mods and the
shader pack are not copied: the pack names each one's own download on Modrinth, with its size and
checksums, and the launcher fetches it from there. They are the versions the release is built and
tested against (see .github/workflows/release.yml). A pack can't set how much memory the game gets:
that stays a launcher setting (about 6 GB with shaders).
"""
import json, pathlib, sys, zipfile

MINECRAFT = "1.21.1"
NEOFORGE = "21.1.259"
FABRIC_LOADER = "0.16.14"

def pin(path, url, size, sha1, sha512):
    return {"path": path, "hashes": {"sha1": sha1, "sha512": sha512}, "env": {"client": "required", "server": "unsupported"},
            "downloads": [url], "fileSize": size}

PHOTON = pin("shaderpacks/photon_v1.3b.zip", "https://cdn.modrinth.com/data/lLqFfGNs/versions/gUv7fBPN/photon_v1.3b.zip", 3800987,
             "d975d25c9686de5f10e8b86c8518613cdf3f040a",
             "40bcbac9c656f2965f543e4b35cd0698723b2db8b67662f1b53c14223cfbef7d60d7fbf0176f73fcadd0d1e19ba60d951ed77e9eece8c8df7dca4d2be6315309")

PACKS = {
    "neoforge": {"loader": {"neoforge": NEOFORGE}, "files": [
        pin("mods/sodium-neoforge-0.8.13+mc1.21.1.jar",
            "https://cdn.modrinth.com/data/AANobbMI/versions/uMOpc5uV/sodium-neoforge-0.8.13%2Bmc1.21.1.jar", 1292934,
            "1757289be21ed18db5b0d90703fa3db1b2de5693",
            "4f537696af95411e9daf15795fb5fcbc49913d488a96fa912fa8f1d7db408710995e8a1461190f3cd0f8217bf9e35e9539176592428ed587ca2d7aa84450a390"),
        pin("mods/iris-neoforge-1.8.14-beta.1+mc1.21.1.jar",
            "https://cdn.modrinth.com/data/YL57xq9U/versions/KduFYu4t/iris-neoforge-1.8.14-beta.1%2Bmc1.21.1.jar", 2540723,
            "1bd129cf7bce65d6b1c0543045f0620c98a0f885",
            "33fddb430e16222902eac75b8b3d57c381b0f3cc729f3b56c4628df12ca108f23a0b11e2feb167923d6d14d1fc7e8ee1a977ec421b19d1951bdca74c4092b9c5"),
        PHOTON]},
    "fabric": {"loader": {"fabric-loader": FABRIC_LOADER}, "files": [
        pin("mods/fabric-api-0.116.18+1.21.1.jar",
            "https://cdn.modrinth.com/data/P7dR8mSH/versions/5SwzqWUu/fabric-api-0.116.18%2B1.21.1.jar", 2453884,
            "584d303f646287ee6be059ecb2fc451255be9ee9",
            "9f721c253ae2e9823cb548e8ec132c9928f80a65184e9b8dcfc489a8d632df20cbfea1e65ff8a4333bdb295c41fbe2228b43e4d41c7355d42ed4637a785840a1"),
        pin("mods/sodium-fabric-0.8.13+mc1.21.1.jar",
            "https://cdn.modrinth.com/data/AANobbMI/versions/SMxNOGZ6/sodium-fabric-0.8.13%2Bmc1.21.1.jar", 1574609,
            "003c114c85ca88ef3362e018deb6aca0c682d6a1",
            "8614a374593698069a80ac74de8cd9c28f3928d16819a0e0d6320b538e823f7e54e8be06d5ea5a9c268beb0e02073209d1f831a02dede851588b972367680853"),
        pin("mods/iris-fabric-1.8.14-beta.1+mc1.21.1.jar",
            "https://cdn.modrinth.com/data/YL57xq9U/versions/bAo1Qhte/iris-fabric-1.8.14-beta.1%2Bmc1.21.1.jar", 2791343,
            "6776c0340845887477bfa463acc736ce7fcb6de5",
            "a7fbb629793c52f0be8b049f787cb598879239b1ad8e1de62e103c8b9efff140e3232b93ef1f14e505d262897d8cf9505b1126396429ad4056bff969c8674e52"),
        PHOTON]},
}

# Iris starts with Photon on. Nothing else is preset: the player's own options stay theirs.
IRIS = "enableShaders=true\nshaderPack=photon_v1.3b.zip\n"

def main():
    version, jars, out = sys.argv[1], {"neoforge": sys.argv[2], "fabric": sys.argv[3]}, pathlib.Path(sys.argv[4])
    suffix = sys.argv[5] if len(sys.argv) > 5 else version
    out.mkdir(parents=True, exist_ok=True)
    for loader, pack in PACKS.items():
        index = {"formatVersion": 1, "game": "minecraft", "versionId": version,
                 "name": "Earth Platform", "summary": "The real Earth in Minecraft, with Sodium, Iris and the Photon shader pack set up.",
                 "files": pack["files"], "dependencies": {"minecraft": MINECRAFT, **pack["loader"]}}
        target = out / f"earth-platform-{loader}-{suffix}.mrpack"
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zip:
            # Fixed dates, so the same inputs give the same file.
            def add(name, data):
                zip.writestr(zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)), data, zipfile.ZIP_DEFLATED)
            add("modrinth.index.json", json.dumps(index, indent=2))
            add("overrides/config/iris.properties", IRIS)
            jar = pathlib.Path(jars[loader])
            add("overrides/mods/" + jar.name, jar.read_bytes())
        print(f"{target} {target.stat().st_size} bytes")

if __name__ == "__main__":
    main()
