import json
import tempfile
import unittest
from pathlib import Path

from patcher.cli import main
from patcher.engine import Options, Rule
from patcher.patches import REGISTRY, resolve
from patcher.smali import SmaliFile, count_params, find_candidates, make_stub

DEV = """.class public Lcom/ig/Dev;
.super Ljava/lang/Object;

.method public static A00(Lcom/ig/Session;)Z
    .locals 2
    const-string v0, "is_employee"
    invoke-static {p0, v0}, Lcom/ig/Cfg;->get(Lcom/ig/Session;Ljava/lang/String;)Z
    move-result v0
    return v0
.end method

.method public A01()Ljava/lang/String;
    .locals 1
    const-string v0, "is_employee"
    return-object v0
.end method
"""

GHOST = """.class public Lcom/ig/Net;
.super Ljava/lang/Object;

.method public static sendSeen(Ljava/lang/String;)V
    .locals 2
    const-string v0, "direct_v2/threads/%s/items/%s/seen/"
    invoke-static {v0}, Lcom/ig/Http;->post(Ljava/lang/String;)V
    return-void
.end method

.method public static typing(Ljava/lang/String;)V
    .locals 2
    const-string v0, "direct_v2/threads/%s/indicate_activity/"
    invoke-static {v0}, Lcom/ig/Http;->post(Ljava/lang/String;)V
    return-void
.end method

.method public H3T(LX/02qp;LX/0ntB;LX/0B3n;)V
    .locals 5
    .param p1, "cb"
    const-string v0, "IGDirectItemSeenMutation"
    const-string v1, "xig_direct_item_seen"
    sget-object v2, LX/0FXy;->A0U:LX/0FXy;
    invoke-interface {p2, v2, v3}, LX/0ntB;->Fu8(LX/0FXy;Ljava/lang/String;)V
    sget-object v4, LX/000B;->A01:Ljava/lang/Integer;
    invoke-static {p1, v4}, LX/0AEJ;->A00(LX/02qp;Ljava/lang/Integer;)V
    return-void
.end method

.method public static A0A(Ljava/lang/String;)V
    .locals 2
    const-string v0, "mark_thread_seen-"
    invoke-static {v0}, Lcom/ig/Http;->post(Ljava/lang/String;)V
    return-void
.end method

.method public static storySeen()Ljava/lang/Object;
    .locals 1
    const-string v0, "media/seen/"
    return-object v0
.end method
"""


def make_tree(root: Path) -> Path:
    d = root / "decoded" / "smali_classes2" / "com" / "ig"
    d.mkdir(parents=True)
    (d / "Dev.smali").write_text(DEV)
    (d / "Net.smali").write_text(GHOST)
    (d / "Other.smali").write_text(".class public Lcom/ig/Other;\n.super Ljava/lang/Object;\n")
    return root / "decoded"


class SmaliTests(unittest.TestCase):
    def test_methods_and_params(self):
        f = SmaliFile(Path("x.smali"), DEV)
        sigs = [m.signature for m in f.methods()]
        self.assertEqual(sigs, ["A00(Lcom/ig/Session;)Z", "A01()Ljava/lang/String;"])
        self.assertEqual(count_params("Lcom/a/B;[IJZ"), 4)
        self.assertEqual(f.class_name, "Lcom/ig/Dev;")

    def test_stubs(self):
        self.assertIn("return-void", "\n".join(make_stub("V")))
        self.assertIn("0x1", "\n".join(make_stub("Z", True)))
        self.assertIn("return-object", "\n".join(make_stub("Ljava/lang/Object;")))

    def test_candidates(self):
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            hits = find_candidates(root, {"a": ["is_employee"], "b": ["nope"]})
            self.assertEqual([p.name for p in hits["a"]], ["Dev.smali"])
            self.assertEqual(hits["b"], [])


class PatchTests(unittest.TestCase):
    def run_patch(self, name, **kw):
        kw.setdefault("use_ext", False)
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            patch = REGISTRY[name]
            cands = find_candidates(root, {name: patch.needles})[name]
            res = patch.apply(root, cands, Options(**kw))
            texts = {p.name: p.read_text() for p in root.rglob("*.smali")}
            return res, texts

    def test_developer_options_forces_true(self):
        res, texts = self.run_patch("developer_options")
        self.assertEqual(res.patched, 1)
        self.assertIn("const/4 v0, 0x1", texts["Dev.smali"])
        self.assertIn('const-string v0, "is_employee"', texts["Dev.smali"])  # String getter untouched

    def test_ghost_rewrites_and_stubs(self):
        res, texts = self.run_patch("ghost_mode")
        net = texts["Net.smali"]
        self.assertEqual(res.patched, 5)
        self.assertNotIn("mark_thread_seen-", net)  # enqueue method stubbed
        # mutation executor: pinned early exit injected before the first instruction
        head = net.split(".method public H3T", 1)[1].split("const-string v0", 1)[0]
        self.assertIn("# ghost-inject:dm_item_seen_mutation_abort", head)
        self.assertIn("LX/0ntB;->Fu8", head)
        self.assertIn("move-object/from16 v2, p2", head)
        self.assertLess(head.index(".param p1"), head.index("ghost-inject"))
        # typing sender (void) is stubbed
        self.assertNotIn("indicate_activity", net)
        # seen endpoints are redirected, methods keep their bodies/return types
        self.assertIn('"ghost_blocked/direct_v2/threads/%s/items/%s/seen/"', net)
        self.assertIn('"ghost_blocked/media/seen/"', net)
        self.assertIn("return-object v0", net)
        self.assertEqual([h for r in res.rules for h in r.skipped], [])

    def test_inject_is_pinned_and_idempotent(self):
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            net = root / "smali_classes2/com/ig/Net.smali"
            patch = REGISTRY["ghost_dm_seen"]
            cands = find_candidates(root, {"p": patch.needles})["p"]
            patch.apply(root, cands, Options(use_ext=False))
            once = net.read_text()
            patch.apply(root, cands, Options(use_ext=False))
            self.assertEqual(once.count("ghost-inject:dm_item_seen_mutation_abort"), 1)
            # a different release (pin anchor missing) must be skipped, not patched
            net.write_text(once.replace("ghost-inject", "x").replace("LX/0AEJ;->A00", "LX/0ZZZ;->A00")
                           .replace("    sget-object v0, LX/0FXy;->A0U:LX/0FXy;\n    const/4 v1, 0x0\n    move-object/from16 v2, p2\n", ""))
            res = patch.apply(root, cands, Options(use_ext=False))
            skipped = [h.reason for r in res.rules for h in r.skipped]
            self.assertTrue(any("version pin failed" in x for x in skipped))

    def test_hooked_mode_wraps_instead_of_replacing(self):
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            net = root / "smali_classes2/com/ig/Net.smali"
            patch = REGISTRY["ghost_mode"]
            cands = find_candidates(root, {"p": patch.needles})["p"]
            patch.apply(root, cands, Options())  # use_ext defaults to True
            text = net.read_text()
            # string endpoints: const-string stays, wrapped by the runtime hook through the same register
            self.assertIn('const-string v0, "media/seen/"\n    invoke-static/range {v0 .. v0}, Lmod/Hooks;->story(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0', text)
            self.assertIn("Lmod/Hooks;->dm(Ljava/lang/String;)Ljava/lang/String;", text)
            # stubbed methods keep their code behind a guard on the feature flag
            self.assertIn("invoke-static {}, Lmod/Hooks;->typing()Z", text)
            self.assertIn("if-eqz v0, :ghost_go_typing_send_stub", text)
            self.assertIn(":ghost_go_typing_send_stub", text)
            self.assertIn('const-string v0, "direct_v2/threads/%s/indicate_activity/"', text)  # original body kept
            # mutation executor: early exit guarded by dm flag; .locals raised only when needed
            self.assertIn("invoke-static {}, Lmod/Hooks;->dm()Z", text)
            # idempotent
            patch.apply(root, cands, Options())
            self.assertEqual(net.read_text(), text)

    def test_guard_bumps_locals_zero(self):
        f = SmaliFile(Path("x.smali"), ".class public Lx/A;\n.super Ljava/lang/Object;\n\n"
                      ".method public static a(Ljava/lang/String;)V\n    .locals 0\n    return-void\n.end method\n")
        m = f.methods()[0]
        f.guarded_insert(m, "typing", "r1", ["return-void"])
        text = "\n".join(f.lines)
        self.assertIn(".locals 1", text)
        self.assertLess(text.index("ghost-guard:r1"), text.index("return-void\n    :ghost_go_r1"))

    def test_mod_menu_hook_added_and_injected(self):
        from patcher.patches import mod_menu
        with tempfile.TemporaryDirectory() as t:
            root = Path(t) / "decoded"
            d = root / "smali_classes3/com/instagram/base/activity"
            d.mkdir(parents=True)
            target = d / "IgFragmentActivity.smali"
            target.write_text(".class public Lcom/instagram/base/activity/IgFragmentActivity;\n"
                              ".super Landroidx/fragment/app/FragmentActivity;\n")
            res = mod_menu.PATCH.apply(root, [], Options())
            self.assertEqual(res.patched, 1)
            text = target.read_text()
            self.assertIn("invoke-super {p0}, Landroidx/fragment/app/FragmentActivity;->onResume()V", text)
            self.assertIn("Lmod/Hooks;->onResume(Landroid/app/Activity;)V", text)
            mod_menu.PATCH.apply(root, [], Options())  # idempotent
            self.assertEqual(target.read_text().count("ghost-hook:onResume"), 1)
            # existing onResume gets the call injected at its start
            target.write_text(".class public Lcom/instagram/base/activity/IgFragmentActivity;\n.super Ljava/lang/Object;\n\n"
                              ".method protected onResume()V\n    .locals 1\n    const/4 v0, 0x0\n    return-void\n.end method\n")
            mod_menu.PATCH.apply(root, [], Options())
            body = target.read_text().split(".locals 1", 1)[1]
            self.assertLess(body.index("Lmod/Hooks;"), body.index("const/4 v0"))

    def test_mkinfo(self):
        from patcher.mkinfo import build
        report = [{"name": "ghost_mode", "rules": [
            {"rule_id": "story_seen_endpoint", "status": "applied", "hits": [{}]},
            {"rule_id": "dm_seen_endpoint", "status": "applied", "hits": [{}, {}, {}]},
            {"rule_id": "typing_send_stub", "status": "no-match", "hits": []}]}]
        java = build(report, "t1")
        self.assertIn("HOOKS = {1, 3, 0, 0}", java)
        self.assertIn("ghost_mode/story_seen_endpoint: applied (1)", java)

    def test_fixzip_adds_next_dex(self):
        import zipfile
        from patcher.fixzip import restore
        with tempfile.TemporaryDirectory() as t:
            stock, built, out, dex = (str(Path(t) / n) for n in ("s.apk", "b.apk", "o.apk", "x.dex"))
            Path(dex).write_bytes(b"dex\n035\x00")
            for p in (stock, built):
                with zipfile.ZipFile(p, "w") as z:
                    z.writestr("classes.dex", b"1")
                    z.writestr("classes2.dex", b"2")
                    z.writestr("classes10.dex", b"3")
            self.assertEqual(restore(stock, built, out, dex)["added_dex"], "classes11.dex")
            with zipfile.ZipFile(out) as z:
                self.assertIn("classes11.dex", z.namelist())

    def test_rewrite_is_idempotent(self):
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            patch = REGISTRY["ghost_story_seen"]
            cands = find_candidates(root, {"p": patch.needles})["p"]
            patch.apply(root, cands, Options(use_ext=False))
            res = patch.apply(root, cands, Options(use_ext=False))
            self.assertEqual(res.patched, 0)
            self.assertNotIn("ghost_blocked/ghost_blocked/", (root / "smali_classes2/com/ig/Net.smali").read_text())

    def test_typing_ignores_log_messages(self):
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            (root / "smali_classes2/com/ig/Rt.smali").write_text(
                ".class public Lcom/ig/Rt;\n.super Ljava/lang/Object;\n\n"
                ".method public handle()V\n    .locals 1\n"
                '    const-string v0, "indicate_activity: in addOrReplace"\n    return-void\n.end method\n'
            )
            patch = REGISTRY["ghost_typing"]
            cands = find_candidates(root, {"p": patch.needles})["p"]
            res = patch.apply(root, cands, Options(use_ext=False))
            self.assertIn("indicate_activity: in addOrReplace", (root / "smali_classes2/com/ig/Rt.smali").read_text())
            self.assertEqual(res.patched, 1)  # only the sender in Net.smali

    def test_null_stub_needs_opt_in(self):
        rule = Rule(id="r", description="r", strings=("media/seen/",))
        from patcher.engine import Patch
        patch = Patch("t", "t", [rule])
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            cands = find_candidates(root, {"t": patch.needles})["t"]
            res = patch.apply(root, cands, Options())
            self.assertEqual(res.patched, 0)
            self.assertEqual(len(res.rules[0].skipped), 1)
            res = patch.apply(root, cands, Options(allow_null_returns=True))
            self.assertEqual(res.patched, 1)

    def test_dry_run_changes_nothing(self):
        res, texts = self.run_patch("ghost_mode", dry_run=True)
        self.assertEqual(res.patched, 0)
        self.assertIn("indicate_activity", texts["Net.smali"])
        self.assertNotIn("ghost_blocked", texts["Net.smali"])

    def test_too_broad_cap(self):
        res, texts = self.run_patch("ghost_mode", max_matches=0)
        self.assertTrue(any(r.status == "too-broad" for r in res.rules))

    def test_rule_validation(self):
        with self.assertRaises(ValueError):
            Rule(id="x", description="x", action="true", strings=("a",))
        with self.assertRaises(ValueError):
            Rule(id="x", description="x", string_regex="a")
        with self.assertRaises(ValueError):
            Rule(id="x", description="x", action="rewrite", field_regex="a", prefilter=("a",))

    def test_resolve(self):
        self.assertEqual([p.name for p in resolve("all")], ["mod_menu", "developer_options", "ghost_mode", "block_ads"])
        with self.assertRaises(KeyError):
            resolve("nope")


class CliTests(unittest.TestCase):
    def test_cli_end_to_end(self):
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            rep = Path(t) / "rep"
            code = main([str(root), "-p", "developer_options,ghost_dm_seen", "--report-dir", str(rep)])
            self.assertEqual(code, 0)
            data = json.loads((rep / "report.json").read_text())
            self.assertEqual([d["name"] for d in data], ["developer_options", "ghost_dm_seen"])
            self.assertTrue((rep / "report.md").read_text().startswith("## Instagram mod"))

    def test_strict_fails_when_nothing_matches(self):
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            self.assertEqual(main([str(root), "-p", "block_ads", "--strict"]), 2)
            self.assertEqual(main([str(root), "-p", "block_ads"]), 0)

    def test_discover(self):
        from patcher.discover import main as discover
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            out = Path(t) / "d.md"
            self.assertEqual(discover([str(root), "--out", str(out)]), 0)
            text = out.read_text()
            self.assertIn("is_employee", text)
            self.assertIn("indicate_activity", text)

    def test_xref(self):
        from patcher.xref import main as xref
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            (root / "smali_classes2/com/ig/User.smali").write_text(
                ".class public Lcom/ig/User;\n.super Ljava/lang/Object;\n\n"
                ".method public show()V\n    .locals 1\n    new-instance v0, Lcom/ig/Dev;\n    return-void\n.end method\n"
            )
            out = Path(t) / "x.md"
            self.assertEqual(xref([str(root), "--strings", "is_employee", "--out", str(out)]), 0)
            text = out.read_text()
            self.assertIn("class `Lcom/ig/Dev;`", text)
            self.assertIn("Lcom/ig/User;->show()V", text)

    def test_large_method_reported(self):
        rule = Rule(id="r", description="r", action="true", return_types=("Z",), strings=("is_employee",), max_instructions=1)
        from patcher.engine import Patch
        patch = Patch("t", "t", [rule])
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            cands = find_candidates(root, {"t": patch.needles})["t"]
            res = patch.apply(root, cands, Options())
            self.assertEqual(res.patched, 0)
            self.assertIn("too large", res.rules[0].skipped[0].reason)

    def test_fixzip_restores_stored_entries(self):
        import zipfile
        from patcher.fixzip import restore, report
        with tempfile.TemporaryDirectory() as t:
            stock, built, out = (str(Path(t) / n) for n in ("s.apk", "b.apk", "o.apk"))
            with zipfile.ZipFile(stock, "w") as z:
                z.writestr("resources.arsc", b"a" * 2000, compress_type=zipfile.ZIP_STORED)
                z.writestr("lib/arm64-v8a/liba.so", b"b" * 2000, compress_type=zipfile.ZIP_STORED)
                z.writestr("classes.dex", b"c" * 2000, compress_type=zipfile.ZIP_DEFLATED)
            with zipfile.ZipFile(built, "w", zipfile.ZIP_DEFLATED) as z:
                for n in ("resources.arsc", "lib/arm64-v8a/liba.so", "classes.dex"):
                    z.writestr(n, b"x" * 2000)
            self.assertEqual(report(stock, built), 1)
            self.assertEqual(restore(stock, built, out)["restored_to_stored"], 2)
            self.assertEqual(report(stock, out), 0)
            with zipfile.ZipFile(out) as z:
                self.assertEqual(z.getinfo("classes.dex").compress_type, zipfile.ZIP_DEFLATED)
                self.assertEqual(z.read("resources.arsc"), b"x" * 2000)

    def test_requires_smali(self):
        with tempfile.TemporaryDirectory() as t:
            self.assertEqual(main([t]), 1)


if __name__ == "__main__":
    unittest.main()
