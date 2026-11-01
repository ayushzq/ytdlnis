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

.method public static H3T(Ljava/lang/String;)V
    .locals 2
    const-string v0, "xig_direct_item_seen"
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
        self.assertEqual(res.patched, 4)
        self.assertNotIn("xig_direct_item_seen", net)
        # typing sender (void) is stubbed
        self.assertNotIn("indicate_activity", net)
        # seen endpoints are redirected, methods keep their bodies/return types
        self.assertIn('"ghost_blocked/direct_v2/threads/%s/items/%s/seen/"', net)
        self.assertIn('"ghost_blocked/media/seen/"', net)
        self.assertIn("return-object v0", net)
        self.assertEqual([h for r in res.rules for h in r.skipped], [])

    def test_rewrite_is_idempotent(self):
        with tempfile.TemporaryDirectory() as t:
            root = make_tree(Path(t))
            patch = REGISTRY["ghost_story_seen"]
            cands = find_candidates(root, {"p": patch.needles})["p"]
            patch.apply(root, cands, Options())
            res = patch.apply(root, cands, Options())
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
            res = patch.apply(root, cands, Options())
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
        self.assertEqual([p.name for p in resolve("all")], ["developer_options", "ghost_mode", "block_ads"])
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

    def test_requires_smali(self):
        with tempfile.TemporaryDirectory() as t:
            self.assertEqual(main([t]), 1)


if __name__ == "__main__":
    unittest.main()
