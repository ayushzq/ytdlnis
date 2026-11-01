"""Hook the in-app mod menu (floating button) into every Instagram screen.

Instagram's own activities extend com.instagram.base.activity.IgFragmentActivity, a
non-obfuscated class. We make its onResume() call Lmod/Hooks;->onResume(Activity), which
adds the draggable button. Everything on the Java side lives in ext/mod/*.java and is
compiled into an extra classesN.dex by the workflow.
"""
from __future__ import annotations

from pathlib import Path

from ..engine import Options, Patch, PatchResult, RuleResult
from ..smali import HOOKS, SmaliFile

TARGET = "com/instagram/base/activity/IgFragmentActivity.smali"
CALL = f"invoke-static/range {{p0 .. p0}}, {HOOKS}->onResume(Landroid/app/Activity;)V"
MARK = "# ghost-hook:onResume"


class ModMenuPatch(Patch):
    def __init__(self) -> None:
        super().__init__("mod_menu", "In-app mod menu: floating button with toggles, counters and a Developer Options shortcut.", [])

    @property
    def needles(self) -> set[str]:
        return {"\x00no-scan"}  # located by path, not by content

    def apply(self, root: Path, candidates: list[Path], opts: Options) -> PatchResult:
        def result(status: str, note: str) -> PatchResult:
            from ..engine import Hit

            hit = Hit(TARGET, "", "onResume()V", note)
            return PatchResult(self.name, [RuleResult("activity_hook", self.description, status, [hit] if status in ("applied", "dry-run") else [], [] if status != "skipped" else [hit])])

        files = [p for d in root.glob("smali*") for p in [d / TARGET] if p.is_file()]
        if not files:
            return result("no-match", "IgFragmentActivity not found")
        f = SmaliFile(files[0])
        if any(MARK in line for line in f.lines):
            return result("applied", "already hooked")
        if opts.dry_run:
            return result("dry-run", "would hook")
        for m in f.methods():
            if m.name == "onResume" and m.params == "" and m.ret == "V" and m.is_concrete:
                f.inject_at_start(m, [f"    {MARK}", f"    {CALL}"])
                f.save()
                return result("applied", "injected into existing onResume")
        sup = next((ln.split()[-1] for ln in f.lines if ln.startswith(".super")), None)
        if not sup:
            return result("no-match", "no .super line")
        end = max(i for i, ln in enumerate(f.lines) if ln.strip())
        f.lines[end + 1 : end + 1] = [
            "",
            ".method protected onResume()V",
            "    .locals 0",
            f"    {MARK}",
            f"    invoke-super {{p0}}, {sup}->onResume()V",
            f"    {CALL}",
            "    return-void",
            ".end method",
        ]
        f.save()
        return result("applied", f"added onResume override (super {sup})")


PATCH = ModMenuPatch()
