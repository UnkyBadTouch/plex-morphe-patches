# Plex Morphe Patches

**[➕ Add in Morphe](https://github.com/UnkyBadTouch/plex-morphe-patches)** · [⬇️ Download .mpp](https://github.com/UnkyBadTouch/plex-morphe-patches/releases/latest)

Morphe patch bundle for the Plex Android app (`com.plexapp.android`).

## About

Client-side Plex Pass unlock. Plex gates most "premium" features (auto Skip
Intros, Downloads Queue, Plex Pass badges/upsell suppression) behind a single
boolean: `hasPlexPass`. This patch flips that boolean to `true` inside the
React Native Hermes bytecode bundle, so the app behaves as if the signed-in
account has a Plex Pass.

**Scope:** client-side only. Plex Pass entitlements are enforced server-side for
media-server features (hardware transcoding, DVR, etc.). This patch only removes
the in-app gating; it does not grant a real subscription.

## ✨ Use

Add this repo as a patch source in the Morphe app:

**Morphe → Settings → Patches → Add source** → paste:

```
https://github.com/UnkyBadTouch/plex-morphe-patches
```

Then patch Plex (`com.plexapp.android`) with the **Premium unlock** patch
(enabled by default). Supported: `2026.20.0`, `2026.19.1`.

No Morphe app? Grab the `.mpp` directly from
[releases](https://github.com/UnkyBadTouch/plex-morphe-patches/releases)
and load it as a local patch file.

## How it works

Plex ships its entire UI as a Hermes (React Native) bytecode file:
`assets/index.android.bundle` (bytecode version 96; targets: 2026.20.0, 2026.19.1).

Every Plex Pass check in the bundle funnels through three functions that each
read a `plexPassOverride` debug flag:

| Function                | 2026.20.0 | 2026.19.1 |
|-------------------------|-----------|-----------|
| `useHasPlexPass`        | `0x6f7193`| `0x6136d3`|
| `getHasPlexPass`        | `0x6f71f1`| `0x613731`|
| `useActiveSubscription` | `0xa298f9`| `0xa16f4b`|

(offsets = file offset of the `JStrictEqual` opcode; the `'on'` string id
differs per version, so the patch also asserts the preceding instruction bytes)

Each does, in bytecode:

```
if (override === 'on')  return true;    // "has Plex Pass" path
if (override === 'off') return false;
return <real subscription state>;       // falls through to account data
```

The release build strips `setPlexPassOverride` (replaced with a no-op returning
`undefined`), so the override can never be `'on'`; it is always `null` and all
three functions fall through to real account state.

Each compare is a single 4-byte `JStrictEqual` instruction (opcode `0xbc`).
The patch inverts it to `JNotEqual` (opcode `0xba`) — one byte per site, no
layout change — so the condition is true for every value except `'on'`. Since
the override is never `'on'`, the "has Plex Pass" path is taken unconditionally.

After the flips the patch recomputes the bundle's trailing SHA-1 footer (the
Hermes loader verifies it) and writes the modified file back.

The patch also cleans the decoded `AndroidManifest.xml` so the patched output
sideloads on any device: removes `requiredSplitTypes`/`splitTypes` (empty
string still counts as a required type → `INSTALL_FAILED_MISSING_SPLIT`) and
sets `extractNativeLibs="true"` (Plex ships `false` for unextracted split
libs → `INSTALL_FAILED_INVALID_APK` when the output is installed standalone).

The patch validates the Hermes magic (`0x1F1903C103BC1FC6`) and bytecode
version (`96`) before touching anything, and asserts the exact bytes at each
site, so it fails loudly (rather than corrupting the file) on a future app
update that changes the bytecode layout.

## Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.2.0](https://github.com/UnkyBadTouch/plex-morphe-patches/releases/tag/v1.2.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 Plex&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 2026.20.0 | 2026.19.1 |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Premium unlock](#premium-unlock) | Unlocks Plex Pass features client-side (auto Skip Intros, downloads, ...) and makes the patched output sideload-installable on any device (strips split-install constraints). |  |

</details>

<!-- PATCHES_END -->

## 🚀 Getting development started

1. [Setup](https://github.com/MorpheApp/morphe-documentation/blob/main/docs/morphe-development/README.md) your development environment including adding a GitHub PAT as described [here](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md#-prepare-the-environment).
2. Enable "Allow GitHub Actions to create and approve pull requests" in your repo Settings > Actions > General > Workflow permissions.

## 🧑‍💻 Dev usage

- **Make all changes to the `dev` branch.**
- Build locally with `./gradlew buildAndroid`; the `.mpp` bundle lands in `patches/build/libs/patches-*.mpp`.
- Apply it locally with [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop).
- Always use [Semantic commit](https://kapeli.com/cheat_sheets/Semantic_Commits.docset/Contents/Resources/Documents/index) messages:
  - `feat: ...` / `fix: ...` — generates a pre-release automatically.
  - `chore: ...` — no release.
- Merge `dev` → `main` (no squash) for a stable release. `release.yml` handles everything; do not create releases by hand.

### Building locally

```
./gradlew buildAndroid
```

Output: `patches/build/libs/patches-*.mpp`. Apply with Morphe Desktop like any
other patch bundle.

### Manual verification (no Morphe toolchain)

The three flipped bytes and the SHA-1 footer update can be verified with the
Python [hermes-dec](https://github.com/P1sec/hermes-dec) toolchain:

```python
import hashlib

# (bundle_bytes, [(offset, ...)]) — pick the layout matching your app version
layouts = {
    "2026.20.0": [0x6F7193, 0x6F71F1, 0xA298F9],
    "2026.19.1": [0x6136D3, 0x613731, 0xA16F4B],
}

d = bytearray(open("index.android.bundle", "rb").read())
for off in layouts["2026.20.0"]:  # or layouts["2026.19.1"]
    assert d[off] == 0xBC
    d[off] = 0xBA
d[-20:] = hashlib.sha1(d[:-20]).digest()
open("index.android.bundle", "wb").write(d)
```

Re-disassemble with `hbc-disassembler` and confirm the three sites now read
`JNotEqual` instead of `JStrictEqual`.

## 📜 License

GPLv3. See [LICENSE](LICENSE).
