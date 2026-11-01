"""Cross-reference dump: show the code around interesting string constants.

  python -m patcher.xref DECODED --strings "Dev Options,is_employee" --out xref.md

1. finds classes that contain one of the exact strings and dumps the methods holding them;
2. finds every other method that references those classes (new-instance, sget, invoke...)
   and dumps their (trimmed) smali, so gates like `if-eqz` checks become visible.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from .smali import SmaliFile, find_candidates, iter_smali_files


def trim(lines: list[str], limit: int) -> list[str]:
    out = []
    for line in lines:
        s = line.strip()
        if not s or s.startswith((".line", ".locals", ".registers", ".param", ".end param", "#", ".prologue")):
            continue
        out.append(s)
    if len(out) > limit:
        out = out[:limit] + [f"... ({len(out) - limit} more lines)"]
    return out


def run(
    root: Path, strings: list[str], max_classes: int, max_methods: int, max_lines: int,
    per_string: int = 2, name_regex: str | None = None,
) -> str:
    cands = find_candidates(root, {"s": strings})["s"]
    defining: dict[str, list[tuple[str, list[str]]]] = {}
    taken: dict[str, set[str]] = {s: set() for s in strings}  # classes already chosen per string
    for path in cands:
        f = SmaliFile(path)
        for m in f.methods():
            if not m.is_concrete:
                continue
            hit = [x for x in f.strings(m) if x in taken and (f.class_name in taken[x] or len(taken[x]) < per_string)]
            if hit and len(defining) < max_classes + len(strings):
                for x in hit:
                    taken[x].add(f.class_name)
                defining.setdefault(f.class_name, []).append((m.signature, trim(f.body(m), max_lines)))

    md = ["## Xref analysis", "", f"strings: `{', '.join(strings)}`", ""]
    if name_regex:
        rx = re.compile(name_regex)
        names = sorted({str(p.relative_to(root)) for p in iter_smali_files(root) if rx.search(str(p))})
        md += [f"### Classes whose path matches `{name_regex}` ({len(names)})", ""]
        md += [f"- {n}" for n in names[:300]] + [""]
    types = list(defining)[:max_classes]
    refs = find_candidates(root, {t: [t] for t in types}) if types else {}
    for t in types:
        md += [f"### class `{t}`", "", "**Methods containing the string(s):**", ""]
        for sig, body in defining[t][:max_methods]:
            md += [f"`{t}->{sig}`", "```", *body, "```", ""]
        md += ["**Referenced from:**", ""]
        shown = 0
        for path in refs.get(t, []):
            f = SmaliFile(path)
            if f.class_name == t:
                continue
            for m in f.methods():
                if not m.is_concrete or t not in "\n".join(f.body(m)):
                    continue
                md += [f"`{f.class_name}->{m.signature}`", "```", *trim(f.body(m), max_lines), "```", ""]
                shown += 1
                if shown >= max_methods:
                    break
            if shown >= max_methods:
                break
        if shown == 0:
            md += ["(no references found)", ""]
    return "\n".join(md)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m patcher.xref")
    ap.add_argument("decoded_dir", type=Path)
    ap.add_argument("--strings", required=True, help="comma-separated exact const-string values")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--max-classes", type=int, default=8)
    ap.add_argument("--max-methods", type=int, default=6)
    ap.add_argument("--max-lines", type=int, default=70)
    ap.add_argument("--per-string", type=int, default=2, help="max classes picked per string")
    ap.add_argument("--names", help="also list class files whose path matches this regex")
    args = ap.parse_args(argv)
    if not args.decoded_dir.is_dir():
        print("error: decoded_dir not found", file=sys.stderr)
        return 1
    strings = [x for x in args.strings.split(",") if x]
    text = run(args.decoded_dir, strings, args.max_classes, args.max_methods, args.max_lines, args.per_string, args.names)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text)
    print(f"wrote {args.out} ({len(text.splitlines())} lines)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
