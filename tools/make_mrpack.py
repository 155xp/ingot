#!/usr/bin/env python3
"""Builds a Modrinth modpack (.mrpack) with Ingot as the only mod, ready to import into the Modrinth App.

    ./gradlew build && python3 tools/make_mrpack.py            # writes build/mrpack/Ingot-<version>.mrpack

The pack is Fabric Loader + Ingot (from build/libs) and a starting options.txt / config/ingot.properties:
Auto power mode (Battery mode on battery power), Unlimited Max Framerate for Performance mode, a long render distance
with a shorter simulation distance (the integrated server ticks far fewer chunks). The JVM arguments in JVM_ARGS
cannot travel inside an .mrpack; tools/make_mrpack.py prints them for the instance's Java settings.
"""
import hashlib
import json
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "build" / "mrpack"

# Measured with bench/run.sh (configs ingot-g1 vs ingot, 120 FPS cap, RD 16): the vanilla launcher's flags with G1 in
# place of ZGC and no pre-touched heap used 2.7 GB instead of 5.8 GB (physical footprint) at the same CPU energy and
# frame times (G1 young pauses about 3 ms).
JVM_ARGS = "-XX:+UseG1GC -XX:MaxGCPauseMillis=40 -XX:+UseCompactObjectHeaders -XX:+UseStringDeduplication"
MEMORY_MB = 4096

OPTIONS = {
    # Minecraft applies a graphics preset's own distances after reading options.txt; Custom keeps these.
    "graphicsPreset": '"custom"',
    "renderDistance": "32",
    "simulationDistance": "8",
    "maxFps": "260",
    "enableVsync": "false",
    "inactivityFpsLimit": '"afk"',
    "prioritizeChunkUpdates": "0",
}

INGOT_PROPERTIES = {
    "powerMode": "auto",
    "batteryFps": "0",
    "batteryResolution": "full",
    "batteryEfficiencyCores": "true",
    "performanceFps": "-1",
    "performanceResolution": "full",
    "throttleWhenOccluded": "true",
}


def gradle_property(name):
    text = (ROOT / "gradle.properties").read_text()
    match = re.search(r"^" + re.escape(name) + r"\s*=\s*(\S+)", text, re.M)
    if not match:
        sys.exit(f"gradle.properties has no {name}")
    return match.group(1)


def main():
    version = gradle_property("mod_version")
    jar = ROOT / "build" / "libs" / f"ingot-{version}.jar"
    if not jar.is_file():
        sys.exit(f"{jar} is missing; run ./gradlew build first")
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version,
        "name": "Ingot",
        "summary": "Minecraft on Apple Metal: Performance, Battery and Auto power modes (Options > Video Settings).",
        "files": [],
        "dependencies": {
            "minecraft": gradle_property("minecraft_version"),
            "fabric-loader": gradle_property("loader_version"),
        },
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / f"Ingot-{version}.mrpack"
    options = "".join(f"{key}:{value}\n" for key, value in OPTIONS.items())
    properties = "# Ingot settings; also editable in game: Options > Video Settings > Ingot Settings...\n" + "".join(
        f"{key}={value}\n" for key, value in INGOT_PROPERTIES.items())
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as pack:
        pack.writestr("modrinth.index.json", json.dumps(index, indent=2) + "\n")
        pack.write(jar, f"overrides/mods/{jar.name}")
        pack.writestr("overrides/options.txt", options)
        pack.writestr("overrides/config/ingot.properties", properties)
        icon = ROOT / "src" / "main" / "resources" / "assets" / "ingot" / "icon.png"
        if icon.is_file():
            pack.write(icon, "icon.png")
    sha1 = hashlib.sha1(out.read_bytes()).hexdigest()
    print(f"wrote {out} (sha1 {sha1})")
    print(f"Instance settings > Java and memory: memory {MEMORY_MB} MB, Java arguments:\n  {JVM_ARGS}")


if __name__ == "__main__":
    main()
