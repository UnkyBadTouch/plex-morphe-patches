package app.plexmods.patches

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.plexmods.patches.shared.Constants.COMPATIBILITY_PLEX
import java.security.MessageDigest

/**
 * Plex ships its UI as a Hermes (React Native) bytecode bundle:
 * `assets/index.android.bundle` (bytecode version 96 for app 2026.19.1/2026.20.0).
 *
 * Every Plex Pass check funnels through three functions that honour a
 * `plexPassOverride` debug flag with the same shape:
 *
 * ```
 * if (override === 'on')  return true;   // or hasPlexPass = true
 * if (override === 'off') return false;
 * return <real subscription state>;
 * ```
 *
 * Per app version, the compare sites (file offsets into the bundle):
 * - `useHasPlexPass`           (JStrictEqual after LoadConstString r1, 'on')
 * - `getHasPlexPass`           (same shape)
 * - `useActiveSubscription`    (after LoadConstTrue r7; sets the `hasPlexPass`
 *   field of the returned object)
 *
 * The release build strips `setPlexPassOverride` (it is a no-op returning
 * `undefined`), so the override is always `null` and all three fall through
 * to real account state. Each compare is a 4-byte `JStrictEqual` (0xbc).
 * Inverting it to `JNotEqual` (0xba) makes the condition true for every value
 * except `'on'` — i.e. always, since the override can never be `'on'` — so
 * the "has Plex Pass" path is taken unconditionally.
 *
 * One byte per site, no layout changes. The bundle ends with a SHA-1 footer
 * over everything before it (the Hermes loader verifies it), so the footer is
 * recomputed after the flips.
 */
private data class Site(
    val opcodeOffset: Long,
    val preceding: ByteArray,
    val from: Byte,
    val to: Byte,
) {
    fun matches(data: ByteArray): Boolean {
        val at = opcodeOffset.toInt()
        if (at !in preceding.size until data.size || data[at] != from) return false
        for ((i, b) in preceding.withIndex()) {
            if (data[at - preceding.size + i] != b) return false
        }
        return true
    }
}

private val JSTRICTEQ = 0xBC.toByte()
private val JNOTEQUAL = 0xBA.toByte()

/** One entry per known app version; the first full match wins. */
private val SITE_SETS = listOf(
    // Plex 2026.20.0
    listOf(
        // useHasPlexPass @ 0x6f715f: LoadConstString r1, 'on' ; JStrictEqual -> true-branch
        Site(0x6F7193L, byteArrayOf(0x73, 0x01, 0x52, 0x5A), JSTRICTEQ, JNOTEQUAL),
        // getHasPlexPass @ 0x6f71a9: same shape
        Site(0x6F71F1L, byteArrayOf(0x73, 0x01, 0x52, 0x5A), JSTRICTEQ, JNOTEQUAL),
        // useActiveSubscription @ 0xa29850: LoadConstTrue r7 ; JStrictEqual -> hasPlexPass=true
        Site(0xA298F9L, byteArrayOf(0x78, 0x07), JSTRICTEQ, JNOTEQUAL),
    ),
    // Plex 2026.19.1
    listOf(
        // useHasPlexPass @ 0x61369f ('on' is string_id 23266 here)
        Site(0x6136D3L, byteArrayOf(0x73, 0x01, 0xE2.toByte(), 0x5A), JSTRICTEQ, JNOTEQUAL),
        // getHasPlexPass @ 0x6136e9
        Site(0x613731L, byteArrayOf(0x73, 0x01, 0xE2.toByte(), 0x5A), JSTRICTEQ, JNOTEQUAL),
        // useActiveSubscription @ 0xa16ea2
        Site(0xA16F4BL, byteArrayOf(0x78, 0x07), JSTRICTEQ, JNOTEQUAL),
    ),
)

private const val HERMES_MAGIC = 0x1F1903C103BC1FC6UL
private const val HERMES_VERSION = 96L

@Suppress("unused")
val premiumUnlockPatch = rawResourcePatch(
    name = "Premium unlock",
    description = "Unlocks Plex Pass features client-side (auto Skip Intros, downloads, ...) and makes the patched output sideload-installable on any device (strips split-install constraints).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PLEX)

    execute {
        val bundle = get("assets/index.android.bundle")
        val data = bundle.readBytes()

        if (data.size < 64 ||
            (readU32(data, 0).toULong() or (readU32(data, 4).toULong() shl 32)) != HERMES_MAGIC
        ) {
            throw PatchException("assets/index.android.bundle is not a Hermes bytecode bundle")
        }
        if (readU32(data, 8) != HERMES_VERSION) {
            throw PatchException(
                "Hermes bytecode version ${readU32(data, 8)} not supported (expected $HERMES_VERSION)"
            )
        }

        val sites = SITE_SETS.firstOrNull { set -> set.all { site -> site.matches(data) } }
            ?: throw PatchException("No known premium-check layout matches this bundle")

        for (site in sites) {
            data[site.opcodeOffset.toInt()] = site.to
        }

        val sha = MessageDigest.getInstance("SHA-1")
            .digest(data.copyOfRange(0, data.size - 20))
        System.arraycopy(sha, 0, data, data.size - 20, 20)

        bundle.writeBytes(data)

        // Plex's base manifest carries split-install constraints that break
        // sideload installs of the patched output (INSTALL_FAILED_MISSING_SPLIT /
        // INVALID_APK on devices that don't match the patching phone's ABI):
        // - requiredSplitTypes/splitTypes: PMS demands config splits that a
        //   standalone install doesn't have (empty string is not "none" — it
        //   counts as one unnamed type, so the attributes must be removed).
        // - extractNativeLibs=false: only valid for unextracted, page-aligned
        //   split libs; flip so installd extracts ours instead of failing.
        val manifest = get("AndroidManifest.xml")
        val xml = manifest.readText()
            .replace(Regex("""\s+android:requiredSplitTypes="[^"]*""""), "")
            .replace(Regex("""\s+android:splitTypes="[^"]*""""), "")
            .replace("""android:extractNativeLibs="false"""", """android:extractNativeLibs="true"""")
        manifest.writeText(xml)
    }
}

private fun readU32(data: ByteArray, at: Int): Long =
    (data[at].toLong() and 0xff) or
        ((data[at + 1].toLong() and 0xff) shl 8) or
        ((data[at + 2].toLong() and 0xff) shl 16) or
        ((data[at + 3].toLong() and 0xff) shl 24)
