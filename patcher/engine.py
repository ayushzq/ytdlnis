"""Rule/Patch engine: finds methods by string / field anchors and rewrites their bodies."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from .smali import Method, SmaliFile, count_params, make_stub

ACTIONS = {"true", "false", "default", "rewrite", "inject"}
REWRITE_PREFIX = "ghost_blocked/"


@dataclass(frozen=True)
class Rule:
    """Selects methods and replaces their body with a trivial stub.

    A method matches when ANY configured criterion matches:
      * strings       - a const-string operand contains one of these literals
      * string_regex  - a const-string operand matches this regex
      * field_regex   - an iget/sget field reference matches this regex

    action:
      "true"    - body becomes `return true`  (requires return_types == ("Z",))
      "false"   - body becomes `return false` / 0
      "default" - void -> return-void, primitives -> 0, references -> null
      "inject"  - insert `inject` smali lines at the start of the method (e.g. an early,
                  graceful return). `require` pins the exact code the injection was written
                  for; if any anchor is missing the method is skipped, not patched.
      "rewrite" - keep the method, but prefix the matching string constants with
                  "ghost_blocked/" so requests hit a dead path (404) instead of the real
                  endpoint. Safe for any return type; nothing is nulled.

    feature: when set (and the extension dex is used), the patch is wrapped in a call to
    Lmod/Hooks so the in-app mod menu can switch it on/off and count how often it fired.
    """

    id: str
    description: str
    action: str = "default"
    strings: tuple[str, ...] = ()
    string_regex: str | None = None
    field_regex: str | None = None
    return_types: tuple[str, ...] | None = None
    prefilter: tuple[str, ...] = ()  # plain substrings used to pre-select files
    max_instructions: int | None = None
    max_params: int | None = None
    inject: tuple[str, ...] = ()   # smali lines (no indent) inserted at method start
    require: tuple[str, ...] = ()  # substrings that must ALL appear in the method body (version pin)
    feature: str = ""  # runtime toggle in the mod menu: story | dm | typing | ads ("" = always on)

    def __post_init__(self) -> None:
        if self.action not in ACTIONS:
            raise ValueError(f"rule {self.id}: bad action {self.action!r}")
        if not (self.strings or self.string_regex or self.field_regex):
            raise ValueError(f"rule {self.id}: no match criteria")
        if self.action == "true" and self.return_types != ("Z",):
            raise ValueError(f"rule {self.id}: action 'true' needs return_types=('Z',)")
        if not self.needles:
            raise ValueError(f"rule {self.id}: regex rules need prefilter substrings")
        if self.action == "inject" and not self.inject:
            raise ValueError(f"rule {self.id}: 'inject' needs inject lines")
        if self.action == "rewrite" and (self.field_regex or not (self.strings or self.string_regex)):
            raise ValueError(f"rule {self.id}: 'rewrite' needs string criteria only")

    @property
    def needles(self) -> tuple[str, ...]:
        return tuple(dict.fromkeys(self.strings + self.prefilter))

    def string_matches(self, s: str) -> bool:
        if any(n in s for n in self.strings):
            return True
        return bool(self.string_regex and re.search(self.string_regex, s))

    def _reason(self, f: SmaliFile, m: Method) -> str | None:
        if self.action == "rewrite":
            for s, wrapped in f.hooked_strings(m):
                if self.string_matches(s) and not wrapped and not s.startswith(REWRITE_PREFIX):
                    return f'string "{s[:80]}"'
            return None
        for s in f.strings(m):
            if self.string_matches(s):
                return f'string "{s[:80]}"'
        if self.field_regex:
            for ref in f.field_refs(m):
                if re.search(self.field_regex, ref):
                    return f"field {ref}"
        return None

    @property
    def markers(self) -> tuple[str, str]:
        return f"# ghost-inject:{self.id}", f"# ghost-guard:{self.id}"

    def check(self, f: SmaliFile, m: Method, allow_null: bool, use_ext: bool = True) -> tuple[str, str] | None:
        if not m.is_concrete or m.name in ("<init>", "<clinit>"):
            return None
        if self.action != "rewrite" and any(mk in line for line in f.body(m) for mk in self.markers):
            return None  # already patched
        reason = self._reason(f, m)
        if reason is None:
            return None
        if self.max_params is not None and count_params(m.params) > self.max_params:
            return "skipped", f"{reason}; too many parameters (> {self.max_params})"
        if self.max_instructions is not None:
            size = f.instruction_count(m)
            if size > self.max_instructions:
                return "skipped", f"{reason}; too large ({size} instructions > {self.max_instructions})"
        if self.action == "rewrite":
            return "match", reason
        if self.feature and use_ext and not f.has_locals(m):
            return "skipped", f"{reason}; method has no .locals line (cannot add a guard)"
        if self.return_types is not None and m.ret not in self.return_types:
            return "skipped", f"{reason}; return type {m.ret} not allowed by rule"
        if self.action == "inject":
            text = "\n".join(f.body(m))
            missing = [r for r in self.require if r not in text]
            if missing:
                return "skipped", f"{reason}; version pin failed, missing: {missing[0][:70]}"
            return "match", reason
        if m.returns_reference and not allow_null:
            return "skipped", f"{reason}; returns {m.ret} (needs --allow-null-returns)"
        return "match", reason


@dataclass
class Options:
    allow_null_returns: bool = False
    dry_run: bool = False
    max_matches: int = 40
    use_ext: bool = True  # wrap feature rules in Lmod/Hooks calls (needs the extension dex)


@dataclass
class Hit:
    file: str
    class_name: str
    signature: str
    reason: str


@dataclass
class RuleResult:
    rule_id: str
    description: str
    status: str  # applied | dry-run | no-match | too-broad
    hits: list[Hit] = field(default_factory=list)
    skipped: list[Hit] = field(default_factory=list)


@dataclass
class PatchResult:
    name: str
    rules: list[RuleResult]

    @property
    def patched(self) -> int:
        return sum(len(r.hits) for r in self.rules if r.status == "applied")


class Patch:
    def __init__(self, name: str, description: str, rules: Iterable[Rule]) -> None:
        self.name = name
        self.description = description
        self.rules = list(rules)

    @property
    def needles(self) -> set[str]:
        return {n for rule in self.rules for n in rule.needles}

    def apply(self, root: Path, candidates: list[Path], opts: Options) -> PatchResult:
        results: list[RuleResult] = []
        for rule in self.rules:
            matches: list[tuple[SmaliFile, Method, str]] = []
            skipped: list[Hit] = []
            for path in candidates:
                f = SmaliFile(path)
                if not any(n in f.text for n in rule.needles):
                    continue
                for m in f.methods():
                    verdict = rule.check(f, m, opts.allow_null_returns, opts.use_ext)
                    if verdict is None:
                        continue
                    kind, reason = verdict
                    if kind == "match":
                        matches.append((f, m, reason))
                    else:
                        skipped.append(_hit(root, f, m, reason))

            hits = [_hit(root, f, m, why) for f, m, why in matches]
            if not matches:
                status = "no-match"
            elif len(matches) > opts.max_matches:
                status = "too-broad"
            elif opts.dry_run:
                status = "dry-run"
            else:
                by_file: dict[Path, tuple[SmaliFile, list[Method]]] = {}
                for f, m, _ in matches:
                    by_file.setdefault(f.path, (f, []))[1].append(m)
                for f, methods in by_file.values():
                    for m in sorted(methods, key=lambda x: x.start, reverse=True):
                        hooked = bool(rule.feature) and opts.use_ext
                        if rule.action == "rewrite":
                            f.rewrite_strings(m, rule.string_matches, REWRITE_PREFIX, rule.feature if hooked else "")
                        elif rule.action == "inject":
                            if hooked:
                                f.guarded_insert(m, rule.feature, rule.id, list(rule.inject))
                            else:
                                f.inject_at_start(m, ["    " + rule.markers[0], *("    " + x for x in rule.inject)])
                        else:
                            stub = make_stub(m.ret, boolean=rule.action == "true")
                            if hooked:
                                lines = [x.strip() for x in stub if x.strip() and not x.strip().startswith(".locals")]
                                f.guarded_insert(m, rule.feature, rule.id, lines)
                            else:
                                f.replace_body(m, stub)
                    f.save()
                status = "applied"
            results.append(RuleResult(rule.id, rule.description, status, hits, skipped))
        return PatchResult(self.name, results)


def _hit(root: Path, f: SmaliFile, m: Method, reason: str) -> Hit:
    return Hit(str(f.path.relative_to(root)), f.class_name, m.signature, reason)
