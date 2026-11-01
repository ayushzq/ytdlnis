"""Restore ZIP entry compression that apktool's rebuild loses.

Android 11+ refuses to install APKs whose resources.arsc (and, with
extractNativeLibs=false, native .so files) are compressed. We copy the original's
"stored" decisions onto the rebuilt APK.

  python -m patcher.fixzip STOCK.apk BUILT.apk OUT.apk
"""
from __future__ import annotations

import argparse
import re
import sys
import zipfile


def restore(stock: str, built: str, out: str, extra_dex: str | None = None) -> dict[str, int]:
    with zipfile.ZipFile(stock) as z:
        stored = {i.filename for i in z.infolist() if i.compress_type == zipfile.ZIP_STORED}
    changed = 0
    with zipfile.ZipFile(built) as zin, zipfile.ZipFile(out, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info)
            new = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            new.external_attr = info.external_attr
            if info.filename.endswith("/"):
                zout.writestr(new, b"")
                continue
            want = zipfile.ZIP_STORED if info.filename in stored else info.compress_type
            if want == zipfile.ZIP_STORED and info.compress_type != zipfile.ZIP_STORED:
                changed += 1
            new.compress_type = want
            zout.writestr(new, data)
        added = ""
        if extra_dex:
            nums = [int(m.group(1) or 1) for i in zin.infolist() if (m := re.fullmatch(r"classes(\d*)\.dex", i.filename))]
            added = f"classes{max(nums, default=1) + 1}.dex"
            info = zipfile.ZipInfo(added, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(extra_dex, "rb") as fh:
                zout.writestr(info, fh.read())
    return {"entries": len(zin.infolist()), "restored_to_stored": changed, "added_dex": added}


def report(stock: str, apk: str) -> int:
    """Prints compression state of the install-critical entries; returns 1 if suspicious."""
    bad = 0
    with zipfile.ZipFile(stock) as a, zipfile.ZipFile(apk) as b:
        ref = {i.filename: i.compress_type for i in a.infolist()}
        for info in b.infolist():
            n = info.filename
            critical = n == "resources.arsc" or n.endswith(".so")
            if critical and ref.get(n) == zipfile.ZIP_STORED and info.compress_type != zipfile.ZIP_STORED:
                print(f"  BAD: {n} is compressed but stored in the original")
                bad += 1
        so = sum(1 for i in b.infolist() if i.filename.endswith(".so"))
        arsc = b.getinfo("resources.arsc").compress_type == zipfile.ZIP_STORED if "resources.arsc" in b.namelist() else None
    print(f"  resources.arsc stored: {arsc}; native libs: {so}; problems: {bad}")
    return 1 if bad else 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m patcher.fixzip")
    ap.add_argument("stock")
    ap.add_argument("built")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--add-dex", help="extra dex to add as the next classesN.dex")
    ap.add_argument("--check", action="store_true", help="only report on BUILT against STOCK")
    args = ap.parse_args(argv)
    if args.check:
        return report(args.stock, args.built)
    if not args.out:
        ap.error("OUT required")
    print(restore(args.stock, args.built, args.out, args.add_dex))
    return 0


if __name__ == "__main__":
    sys.exit(main())
