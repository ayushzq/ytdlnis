"""Anchor discovery: list const-strings in the decoded app that look interesting.

  python -m patcher.discover DECODED --out discover.md
Used by the workflow so a human (or Claude) can pick anchors for a new Instagram release.
"""
from __future__ import annotations

import argparse
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .smali import SmaliFile, iter_smali_files

GROUPS: dict[str, str] = {
    "dm_seen": r"(?:direct|thread|item).*(?:seen|read_receipt|mark_read)|(?:seen|read_receipt).*(?:thread|item|direct)|mark_(?:thread|visual|item)\w*",
    "story_seen": r"(?:media|reel|story|stories)\w*/?\w*seen|seen\w*(?:reel|story)",
    "employee_dev": r"is_?(?:ig_)?employee|is_?internal|internal_?(?:settings|user|build)|dev(?:eloper)?_?options|mobile_?config_?(?:override|debug)|qe_?sandbox",
    "typing": r"indicate_activity|typing_?indicator|activity_?indicator",
}


def scan(root: Path, groups: dict[str, str], limit: int) -> dict[str, list[str]]:
    compiled = {k: re.compile(v, re.I) for k, v in groups.items()}
    coarse = re.compile("|".join(f"(?:{v})" for v in groups.values()).encode("utf-8"), re.I)
    found: dict[str, list[str]] = {k: [] for k in groups}

    def work(path: Path) -> list[tuple[str, str]]:
        data = path.read_bytes()
        if not coarse.search(data):
            return []
        f = SmaliFile(path, data.decode("utf-8", "surrogateescape"))
        out = []
        for m in f.methods():
            for s in f.strings(m):
                for k, rx in compiled.items():
                    if rx.search(s):
                        out.append((k, f"`{f.class_name}->{m.signature}` : \"{s[:120]}\""))
        return out

    files = list(iter_smali_files(root))
    with ThreadPoolExecutor(max_workers=8) as pool:
        for hits in pool.map(work, files):
            for k, line in hits:
                if len(found[k]) < limit and line not in found[k]:
                    found[k].append(line)
    return found


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m patcher.discover")
    ap.add_argument("decoded_dir", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--limit", type=int, default=150, help="max lines per group")
    args = ap.parse_args(argv)
    if not args.decoded_dir.is_dir():
        print("error: decoded_dir not found", file=sys.stderr)
        return 1
    found = scan(args.decoded_dir, GROUPS, args.limit)
    lines = ["## Anchor discovery", ""]
    for k, items in found.items():
        lines += [f"### {k} ({len(items)}{'+' if len(items) >= args.limit else ''})", ""]
        lines += [f"- {i}" for i in items] or ["- (nothing found)"]
        lines.append("")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines))
    print(f"wrote {args.out} ({sum(len(v) for v in found.values())} entries)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
