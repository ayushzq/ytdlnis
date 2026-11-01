"""Generate ext/mod/BuildInfo.java from the patch report.

  python -m patcher.mkinfo report.json BuildInfo.java [--version V]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .patches import REGISTRY

FEATURES = ["story", "dm", "typing", "ads"]


def feature_of(rule_id: str) -> str:
    for patch in REGISTRY.values():
        for rule in patch.rules:
            if rule.id == rule_id and rule.feature:
                return rule.feature
    return ""


def build(report: list[dict], version: str) -> str:
    hooks = {f: 0 for f in FEATURES}
    lines = []
    for patch in report:
        for r in patch["rules"]:
            n = len(r["hits"]) if r["status"] == "applied" else 0
            feat = feature_of(r["rule_id"])
            if feat in hooks:
                hooks[feat] += n
            lines.append(f"{patch['name']}/{r['rule_id']}: {r['status']} ({n})")
    summary = "\\n".join(x.replace("\\", "\\\\").replace('"', '\\"') for x in lines)
    arr = ", ".join(str(hooks[f]) for f in FEATURES)
    return (
        "package mod;\n\n"
        "final class BuildInfo {\n"
        f'    static final String VERSION = "{version}";\n'
        f"    static final int[] HOOKS = {{{arr}}};\n"
        f'    static final String SUMMARY = "{summary}";\n\n'
        "    private BuildInfo() {}\n}\n"
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m patcher.mkinfo")
    ap.add_argument("report")
    ap.add_argument("out", type=Path)
    ap.add_argument("--version", default="build")
    args = ap.parse_args(argv)
    report = json.loads(Path(args.report).read_text())
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(build(report, args.version))
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
