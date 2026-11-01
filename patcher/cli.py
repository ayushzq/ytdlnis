from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import asdict
from pathlib import Path

from . import __version__
from .engine import Options, PatchResult
from .patches import DEFAULT, REGISTRY, resolve
from .smali import find_candidates, smali_roots


def _markdown(results: list[PatchResult], opts: Options) -> str:
    out = ["## Instagram mod patch report", ""]
    out += ["| Patch | Rule | Status | Methods patched | Skipped |", "|---|---|---|---|---|"]
    for res in results:
        for r in res.rules:
            n = len(r.hits) if r.status in ("applied", "dry-run") else 0
            out.append(f"| `{res.name}` | `{r.rule_id}` | {r.status} | {n} | {len(r.skipped)} |")
    out.append("")
    for res in results:
        for r in res.rules:
            if r.hits and r.status in ("applied", "dry-run"):
                out += [f"### `{res.name}` / `{r.rule_id}` ({r.status})", ""]
                out += [f"- `{h.class_name}->{h.signature}` ({h.reason})" for h in r.hits]
                out.append("")
            if r.status == "too-broad":
                out += [
                    f"### `{res.name}` / `{r.rule_id}` matched {len(r.hits)} methods (> {opts.max_matches}); nothing changed",
                    "",
                    "Rule is too broad for this release - tighten its anchors or raise `--max-matches`.",
                    "",
                ]
            if r.skipped:
                out += [f"### Skipped by `{r.rule_id}` ({len(r.skipped)})", ""]
                out += [f"- `{h.class_name}->{h.signature}` - {h.reason}" for h in r.skipped[:30]]
                out.append("")
    return "\n".join(out)


def _annotate(level: str, title: str, msg: str) -> None:
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::{level} title={title}::{msg}")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m patcher", description="Patch an apktool-decoded Instagram APK.")
    ap.add_argument("decoded_dir", nargs="?", type=Path, help="directory produced by `apktool d`")
    ap.add_argument("-p", "--patches", default="all", help=f"comma list or 'all' ({', '.join(DEFAULT)})")
    ap.add_argument("--list", action="store_true", help="list available patches and exit")
    ap.add_argument("--dry-run", action="store_true", help="report matches without modifying files")
    ap.add_argument("--strict", action="store_true", help="exit 2 if any selected patch changed nothing")
    ap.add_argument("--allow-null-returns", action="store_true", help="also stub methods that return objects (returns null)")
    ap.add_argument("--no-ext", action="store_true", help="static patches only: no mod menu / runtime toggles (skips mod_menu)")
    ap.add_argument("--max-matches", type=int, default=40, help="per-rule safety cap (default 40)")
    ap.add_argument("--report-dir", type=Path, help="write report.json and report.md here")
    ap.add_argument("--version", action="version", version=__version__)
    args = ap.parse_args(argv)

    if args.list:
        for name, patch in REGISTRY.items():
            print(f"{name:20} {patch.description}")
            for rule in patch.rules:
                print(f"    - {rule.id}: {rule.description}")
        return 0

    root = args.decoded_dir
    if root is None or not root.is_dir():
        ap.error("decoded_dir must be an existing apktool output directory")
    if not smali_roots(root):
        print("error: no smali*/ directories found - decode with apktool WITHOUT --no-src", file=sys.stderr)
        return 1
    try:
        selected = resolve(args.patches)
    except KeyError as exc:
        print(f"error: {exc.args[0]}", file=sys.stderr)
        return 1

    if args.no_ext:
        selected = [p for p in selected if p.name != "mod_menu"]
    opts = Options(args.allow_null_returns, args.dry_run, args.max_matches, use_ext=not args.no_ext)
    print(f"Scanning smali for {len(selected)} patch(es)...")
    index = find_candidates(root, {p.name: p.needles for p in selected})

    results: list[PatchResult] = []
    for patch in selected:
        cands = index[patch.name]
        print(f"\n[{patch.name}] {patch.description} ({len(cands)} candidate files)")
        res = patch.apply(root, cands, opts)
        results.append(res)
        for r in res.rules:
            n = len(r.hits) if r.status in ("applied", "dry-run") else 0
            print(f"  - {r.rule_id}: {r.status} ({n} patched, {len(r.skipped)} skipped, {len(r.hits)} matched)")

    failed = [r.name for r in results if r.patched == 0 and not args.dry_run]
    for name in failed:
        _annotate("warning", f"{name}: nothing patched", "No rule matched this release. See the report; anchors may need updating.")
    if args.report_dir:
        args.report_dir.mkdir(parents=True, exist_ok=True)
        (args.report_dir / "report.json").write_text(json.dumps([asdict(r) for r in results], indent=2))
        (args.report_dir / "report.md").write_text(_markdown(results, opts))
    if failed:
        print(f"\nWARNING: no changes made by: {', '.join(failed)}", file=sys.stderr)
        if args.strict:
            return 2
    return 0
