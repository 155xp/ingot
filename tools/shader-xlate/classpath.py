#!/usr/bin/env python3
"""Prints a classpath for running shader-xlate against the real Minecraft 26.3 classes.

Order: ./lib (LWJGL 3.4.3 from Maven Central, see fetch-libs.sh), the client jar, then every
non-native library from the version json, resolved from the Gradle/Loom cache or the vanilla
launcher's libraries dir. LWJGL entries from the version json are skipped (./lib wins).
"""
import glob, json, os, sys

here = os.path.dirname(os.path.abspath(__file__))
mc_version = os.environ.get("MC_VERSION", "26.3")
loom = os.path.expanduser(f"~/.gradle/caches/fabric-loom/{mc_version}")
client_jar = os.environ.get("MC_JAR", f"{loom}/minecraft-client.jar")
version_json = os.environ.get("MC_VERSION_JSON", f"{loom}/mojang_minecraft_info.json")
gradle = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
launcher = os.path.expanduser("~/Library/Application Support/minecraft/libraries")

cp = sorted(glob.glob(os.path.join(here, "lib", "*.jar")))
if not os.path.exists(client_jar):
    sys.exit(f"client jar not found: {client_jar} (set MC_JAR)")
cp.append(client_jar)
missing = []
for lib in json.load(open(version_json))["libraries"]:
    parts = lib["name"].split(":")
    if len(parts) != 3:
        continue  # natives classifier entries
    g, a, v = parts
    if g == "org.lwjgl":
        continue
    hits = glob.glob(f"{gradle}/{g}/{a}/{v}/*/{a}-{v}.jar")
    alt = f"{launcher}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.jar"
    if hits:
        cp.append(hits[0])
    elif os.path.exists(alt):
        cp.append(alt)
    else:
        missing.append(lib["name"])
if missing:
    print("warning: missing libraries: " + ", ".join(missing), file=sys.stderr)
print(":".join(cp))
