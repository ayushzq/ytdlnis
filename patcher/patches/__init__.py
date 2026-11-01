"""Patch registry. Add a module here to add a patch."""
from __future__ import annotations

from ..engine import Patch
from . import block_ads, developer_options, ghost_mode, mod_menu

REGISTRY: dict[str, Patch] = {
    p.name: p
    for p in [mod_menu.PATCH, developer_options.PATCH, *ghost_mode.PATCHES, block_ads.PATCH]
}

# What "--patches all" runs (ghost_* sub-patches are already part of ghost_mode).
DEFAULT: list[str] = ["mod_menu", "developer_options", "ghost_mode", "block_ads"]


def resolve(spec: str) -> list[Patch]:
    names = DEFAULT if spec.strip().lower() == "all" else [n.strip() for n in spec.split(",") if n.strip()]
    unknown = [n for n in names if n not in REGISTRY]
    if unknown:
        raise KeyError(f"unknown patch(es): {', '.join(unknown)}; available: {', '.join(REGISTRY)}")
    seen: dict[str, Patch] = {}
    for n in names:
        seen.setdefault(n, REGISTRY[n])
    return list(seen.values())
