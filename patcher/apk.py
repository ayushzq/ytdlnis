"""APK sanity helpers used by the workflow.

  python -m patcher.apk kind  FILE   -> prints apk | bundle | unknown
  python -m patcher.apk check FILE   -> verifies dex + Instagram-looking manifest
"""
from __future__ import annotations

import argparse
import re
import sys
import zipfile


def kind(path: str) -> str:
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
    except zipfile.BadZipFile:
        return "unknown"
    if any(re.fullmatch(r"classes\d*\.dex", n) for n in names):
        return "apk"
    if any(n.endswith(".apk") for n in names):
        return "bundle"
    return "unknown"


def check(path: str, allow_other: bool = False) -> int:
    if kind(path) != "apk":
        print(f"error: {path} is not a valid APK (no classes.dex)", file=sys.stderr)
        return 1
    with zipfile.ZipFile(path) as z:
        manifest = z.read("AndroidManifest.xml")
    needle = b"com.instagram"
    if needle not in manifest and needle.decode().encode("utf-16-le") not in manifest:
        msg = "APK manifest does not reference com.instagram"
        if not allow_other:
            print(f"error: {msg} (use --allow-other to override)", file=sys.stderr)
            return 3
        print(f"warning: {msg}", file=sys.stderr)
    print("APK looks fine")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m patcher.apk")
    ap.add_argument("command", choices=["kind", "check"])
    ap.add_argument("file")
    ap.add_argument("--allow-other", action="store_true")
    args = ap.parse_args(argv)
    if args.command == "kind":
        print(kind(args.file))
        return 0
    return check(args.file, args.allow_other)


if __name__ == "__main__":
    raise SystemExit(main())
