# Instagram Mod Patcher

Automated, GitHub-Actions-driven patcher that turns a **stock Instagram APK you supply** into a modded build.
No Instagram code is stored in this repository; the pipeline is:

```
stock APK ──apktool d──▶ smali ──python patcher──▶ patched smali ──apktool b──▶ uber-apk-signer ──▶ signed APK
```

| Patch | What it does |
|---|---|
| `developer_options` | Forces the employee / internal gates to `true` so the internal **Developer Options / MobileConfig** menu becomes reachable. |
| `ghost_mode` | Hides **story seen**, **DM seen** and the **typing indicator** (also available separately: `ghost_story_seen`, `ghost_dm_seen`, `ghost_typing`). |
| `mod_menu` | Adds a small draggable **M** button on every screen. Tap it for toggles (story seen, DM seen, typing, ads), per-feature *"blocked N×"* counters (proof that a patch really fires), a list of what was patched in the build, and an **Open Developer Options** shortcut. |
| `mod_menu` (screen features) | Also hides feed/reels rows labelled **Ad / Sponsored** (collapsed on screen, independent of Instagram's obfuscation), adds a **↓ download button** on every visible post/reel (and the story viewer) that saves the photo/video to `Pictures|Movies/InstagramMod`, and an **Inspect screen** tool that writes a debug dump (`Download/igmod-inspect-*.txt`) for tuning. |
| `block_ads` | Neutralises ad-injection endpoints and ad-loading feature flags for sponsored content. |

> **Read this first - patches are best-effort.** Instagram's code is obfuscated and changes every release.
> The patcher does not use fixed class names; it finds methods through stable *string constants*
> (API paths such as `indicate_activity`, flags such as `is_employee`). That is robust across versions but
> not guaranteed. Every run produces a **patch report** (job summary + `patch-report-N.json`) showing exactly
> which methods were rewritten, which were skipped, and which rules matched nothing. A build with
> "0 patched" for a patch means that patch does **nothing** in that build.
>
> Techniques: **rewrite** prefixes the endpoint string with `ghost_blocked/` so the request 404s (used for
> story/DM seen and ad endpoints - never nulls anything); **stub** replaces a void/boolean method body
> (typing sender, employee gates).

## Running the workflow

1. Get a stock Instagram APK (single `.apk`, or a `.apkm` / `.xapk` split bundle - bundles are merged automatically).
   You need a **direct download link** (not an HTML page), e.g. a file host or your own storage.
   Alternatively commit the file to a **private** copy of this repo (`git add -f input/instagram.apk`).
2. Go to **Actions → Build Instagram Mod → Run workflow**.
3. Fill in the inputs:

   | Input | Meaning |
   |---|---|
   | `apk_url` | Direct URL of the stock APK/APKM/XAPK. |
   | `apk_path` | Used when `apk_url` is empty: repo path of a committed APK, e.g. `input/instagram.apk`. |
   | `patches` | `all` (default) or a comma list, e.g. `ghost_mode,block_ads`. |
   | `allow_null_returns` | Only affects `action="default"` stubs of object-returning methods (returns null). Leave OFF unless a report tells you to; ghost mode and ad blocking use safe string rewriting and don't need it. |
   | `mod_menu` | ON by default. Compiles `ext/mod/*.java` into an extra `classesN.dex` and hooks it into Instagram. OFF = static patches only (no button, no toggles). |
   | `discover` | Adds a list of candidate anchor strings (seen / employee / dev-options) to the run summary. Handy when a new release breaks a rule. |
   | `strict` | Fail the run if a selected patch changed nothing. |
   | `create_release` | Publish the APK as a pre-release. Off by default - the APK contains Instagram's code, so don't publish it from a public repo. |

4. When the run finishes, open it and download the **Instagram-Mod-N** artifact (kept 14 days). The run
   summary contains the patch report.

Optional repository secrets for stable signing (otherwise a debug key is used): `KEYSTORE_BASE64`,
`KEYSTORE_ALIAS`, `KEYSTORE_PASSWORD`, `KEYSTORE_KEY_PASSWORD`.

### Installing
The mod is signed with a different key, so it **cannot update over the stock app**: uninstall stock Instagram
first (or expect an install conflict). Facebook/Google sign-in flows tied to the original signature may not work;
use username/password login.

## Tuning patches when a release doesn't match

1. Run with `strict` off and read the report: which rules say `no-match`, `too-broad`, or list *skipped* methods?
2. Decode locally and look for the anchor:
   ```bash
   java -jar apktool.jar d -f -r -o decoded instagram.apk
   grep -rln "indicate_activity" decoded/smali*
   ```
3. Edit the rule in `patcher/patches/*.py` (add strings / adjust `return_types`, `max_instructions`).
4. Check without modifying anything: `python -m patcher decoded --patches ghost_typing --dry-run --report-dir out`.

## Local usage

```bash
python -m patcher --list
python -m patcher decoded --patches all --report-dir report [--allow-null-returns] [--dry-run] [--strict]
python -m unittest discover -s tests -v      # unit tests (synthetic smali, no APK needed)
```
Requirements: Python 3.9+ (stdlib only), Java 17 + apktool + uber-apk-signer for the build steps (the workflow installs them).

## Layout

```
.github/workflows/build-mod.yml   manual build workflow (workflow_dispatch)
.github/workflows/ci.yml          unit tests on push / PR
patcher/smali.py                  smali parsing, stubs, parallel file index
patcher/engine.py                 Rule / Patch engine
patcher/patches/                  one module per patch (developer_options, ghost_mode, block_ads)
patcher/fixzip.py                 restores stored zip entries, adds the extension dex
ext/mod/*.java                    runtime half (hooks, floating button, menu)
patcher/apk.py                    APK/bundle detection + sanity check
patcher/cli.py                    `python -m patcher`
tests/                            unit tests
```

### Adding a patch
Create `patcher/patches/my_patch.py` exposing a `Patch` with one or more `Rule`s, then register it in
`patcher/patches/__init__.py`.

## Legal / risk notes
Modifying Instagram violates its Terms of Use and can get an account restricted or banned - use a throwaway
account. This project only contains patching scripts; you are responsible for the APK you feed it and for
not redistributing Instagram's code. Not affiliated with Meta. Provided as-is under the MIT license.
