"""Restore ZIP entry compression that apktool's rebuild loses.

Android 11+ refuses to install APKs whose resources.arsc (and, with
extractNativeLibs=false, native .so files) are compressed. We copy the original's
"stored" decisions onto the rebuilt APK.

  python -m patcher.fixzip STOCK.apk BUILT.apk OUT.apk
"""
from __future__ import annotations

import argparse
import sys
import zipfile


def restore(stock: str, built: str, out: str) -> dict[str, int]:
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
    return {"entries": len(zin.infolist()), "restored_to_stored": changed}


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
    ap.add_argument("--check", action="store_true", help="only report on BUILT against STOCK")
    args = ap.parse_args(argv)
    if args.check:
        return report(args.stock, args.built)
    if not args.out:
        ap.error("OUT required")
    print(restore(args.stock, args.built, args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
