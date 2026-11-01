#!/usr/bin/env python3
"""Resolve, download and pin the benchmark mod sets.

    bench/fetch_mods.py                 download/verify everything in bench/mods.lock.json (resolves first if no lock)
    bench/fetch_mods.py --update        re-resolve the latest versions from the Modrinth API and rewrite the lock
    bench/fetch_mods.py --paths SET     ensure SET is downloaded, then print one absolute jar path per line

Resolution: for every project in bench/mods.json the newest version for gameVersion + loader is taken (release only,
unless the entry says "channel": "any"), then every REQUIRED dependency is resolved the same way, recursively.
Jars land in bench/mods-cache/ and are verified against the sha1 and sha512 that Modrinth reports; the lock pins exact
version ids, file names, URLs and hashes so later runs use identical jars.
"""

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone

BENCH = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(BENCH, "mods.json")
LOCK = os.path.join(BENCH, "mods.lock.json")
CACHE = os.path.join(BENCH, "mods-cache")
API = "https://api.modrinth.com/v2"
USER_AGENT = "ingot-bench/0.1 (Minecraft renderer benchmark harness)"


def api(path, **query):
    url = API + path
    if query:
        url += "?" + urllib.parse.urlencode({k: json.dumps(v) if isinstance(v, list) else v for k, v in query.items()})
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                time.sleep(2 ** attempt)
                continue
            raise
        except urllib.error.URLError:
            time.sleep(2 ** attempt)
    raise RuntimeError("Modrinth API request failed: " + url)


def newest(project, game, loader, channel):
    versions = api(f"/project/{project}/version", game_versions=[game], loaders=[loader], include_changelog="false")
    allowed = {"release"} if channel == "release" else {"release", "beta", "alpha"}
    candidates = [v for v in versions if v["version_type"] in allowed]
    candidates.sort(key=lambda v: v["date_published"], reverse=True)
    return candidates[0] if candidates else None


def primary_file(version):
    files = version["files"]
    for f in files:
        if f.get("primary"):
            return f
    return files[0]


def resolve(manifest):
    game, loader = manifest["gameVersion"], manifest["loader"]
    mods = {}  # project id -> lock entry
    sets = {}
    notes = []
    for set_name, entries in manifest["sets"].items():
        members = []
        queue = [(e["project"], e.get("channel", "release"), None, e.get("why", ""), None) for e in entries]
        while queue:
            project, channel, required_by, why, pinned_version = queue.pop(0)
            version = newest(project, game, loader, channel)
            if version is None and channel == "release":
                version = newest(project, game, loader, "any")
                if version is not None:
                    notes.append(f"{project}: no release for {game}, using {version['version_type']} {version['version_number']}")
            if version is None and pinned_version:
                version = api(f"/version/{pinned_version}")
                notes.append(f"{project}: no {game} version found, using the version pinned by {required_by}")
            if version is None:
                raise SystemExit(f"error: {project} has no {loader} version for {game}" + (f" (required by {required_by})" if required_by else ""))
            pid = version["project_id"]
            if pid in mods:
                if required_by and required_by not in mods[pid]["requiredBy"]:
                    mods[pid]["requiredBy"].append(required_by)
                if pid not in members:
                    members.append(pid)
                continue
            info = api(f"/project/{pid}")
            f = primary_file(version)
            mods[pid] = {
                "projectId": pid,
                "slug": info["slug"],
                "title": info["title"],
                "versionId": version["id"],
                "versionNumber": version["version_number"],
                "versionType": version["version_type"],
                "datePublished": version["date_published"],
                "gameVersions": version["game_versions"],
                "filename": f["filename"],
                "url": f["url"],
                "size": f["size"],
                "sha1": f["hashes"]["sha1"],
                "sha512": f["hashes"]["sha512"],
                "requiredBy": [required_by] if required_by else [],
                "why": why if not required_by else f"required dependency of {required_by}",
                "incompatible": [],
            }
            members.append(pid)
            print(f"  {info['slug']:<22} {version['version_number']:<34} {version['version_type']:<8} {f['filename']}"
                  + (f"   (required by {required_by})" if required_by else ""), file=sys.stderr)
            for dep in version.get("dependencies", []):
                dep_project = dep.get("project_id")
                if dep_project is None and dep.get("version_id"):
                    dep_project = api(f"/version/{dep['version_id']}")["project_id"]
                if dep_project is None:
                    continue
                if dep["dependency_type"] == "required":
                    queue.append((dep_project, "release", info["slug"], "", dep.get("version_id")))
                elif dep["dependency_type"] == "incompatible":
                    mods[pid]["incompatible"].append(dep_project)
        sets[set_name] = [mods[p]["slug"] for p in members]
        for pid in members:
            for bad in mods[pid]["incompatible"]:
                if bad in members:
                    notes.append(f"WARNING: {mods[pid]['slug']} declares {mods[bad]['slug']} incompatible")
    return {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "gameVersion": game,
        "loader": loader,
        "notes": notes,
        "sets": sets,
        "mods": {m["slug"]: m for m in sorted(mods.values(), key=lambda m: m["slug"])},
    }


def file_ok(path, mod):
    if not os.path.isfile(path) or os.path.getsize(path) != mod["size"]:
        return False
    sha1, sha512 = hashlib.sha1(), hashlib.sha512()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            sha1.update(chunk)
            sha512.update(chunk)
    return sha1.hexdigest() == mod["sha1"] and sha512.hexdigest() == mod["sha512"]


def check_lock_matches_manifest(lock):
    """The lock must cover exactly what mods.json asks for; a stale lock would silently benchmark the old mod set."""
    with open(MANIFEST) as fh:
        manifest = json.load(fh)
    problems = []
    if (lock.get("gameVersion"), lock.get("loader")) != (manifest["gameVersion"], manifest["loader"]):
        problems.append(f"lock is for {lock.get('gameVersion')}/{lock.get('loader')}, mods.json asks for "
                        f"{manifest['gameVersion']}/{manifest['loader']}")
    for name, entries in manifest["sets"].items():
        locked = set(lock.get("sets", {}).get(name, []))
        locked_ids = {lock["mods"][slug]["projectId"] for slug in locked if slug in lock.get("mods", {})}
        for e in entries:
            if e["project"] not in locked and e["project"] not in locked_ids:
                problems.append(f"set '{name}': {e['project']} is in mods.json but not in the lock")
    for name in lock.get("sets", {}):
        if name not in manifest["sets"]:
            problems.append(f"set '{name}' is in the lock but no longer in mods.json")
    if problems:
        raise SystemExit("error: bench/mods.lock.json does not match bench/mods.json:\n  " + "\n  ".join(problems)
                         + "\nRun bench/fetch_mods.py --update to resolve the sets again (this changes the benchmarked jars).")


def download(url, path):
    tmp = path + ".part"
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
                while True:
                    chunk = resp.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk)
            os.replace(tmp, path)
            return
        except (urllib.error.URLError, OSError) as e:
            if attempt == 3:
                raise SystemExit(f"error: could not download {url}: {e}")
            time.sleep(2 ** attempt)


def ensure(lock, set_names=None):
    os.makedirs(CACHE, exist_ok=True)
    wanted = set()
    for name in set_names or lock["sets"].keys():
        if name not in lock["sets"]:
            raise SystemExit(f"error: unknown mod set '{name}' (have: {', '.join(lock['sets'])})")
        wanted.update(lock["sets"][name])
    for slug in sorted(wanted):
        mod = lock["mods"][slug]
        path = os.path.join(CACHE, mod["filename"])
        if file_ok(path, mod):
            continue
        print(f"  downloading {mod['filename']}", file=sys.stderr)
        download(mod["url"], path)
        if not file_ok(path, mod):
            os.remove(path)
            raise SystemExit(f"error: hash mismatch for {mod['filename']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--update", action="store_true", help="re-resolve from the Modrinth API and rewrite the lock")
    ap.add_argument("--paths", metavar="SET", help="print the absolute jar paths of SET (after ensuring them)")
    args = ap.parse_args()

    if args.update or not os.path.isfile(LOCK):
        with open(MANIFEST) as fh:
            manifest = json.load(fh)
        print(f"resolving mod sets for {manifest['gameVersion']} / {manifest['loader']} from Modrinth:", file=sys.stderr)
        lock = resolve(manifest)
        for note in lock["notes"]:
            print("  note: " + note, file=sys.stderr)
        with open(LOCK, "w") as fh:
            json.dump(lock, fh, indent=2)
            fh.write("\n")
        print(f"wrote {os.path.relpath(LOCK)}", file=sys.stderr)
    with open(LOCK) as fh:
        lock = json.load(fh)
    check_lock_matches_manifest(lock)

    if args.paths:
        ensure(lock, [args.paths])
        for slug in lock["sets"][args.paths]:
            print(os.path.join(CACHE, lock["mods"][slug]["filename"]))
    else:
        ensure(lock)
        print(f"mods-cache ok ({sum(len(v) for v in lock['sets'].values())} jars across {len(lock['sets'])} set(s))", file=sys.stderr)


if __name__ == "__main__":
    main()
