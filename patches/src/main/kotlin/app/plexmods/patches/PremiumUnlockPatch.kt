package app.plexmods.patches

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.plexmods.patches.shared.Constants.COMPATIBILITY_PLEX
import java.security.MessageDigest

/**
 * Plex ships its UI as a Hermes (React Native) bytecode bundle:
 * `assets/index.android.bundle` (bytecode version 96 for app 2026.20.0).
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
 * Sites (file offsets into the bundle):
 * - `useHasPlexPass`           @ 0x6f716b, compare at +0x34
 * - `getHasPlexPass`           @ 0x6f71b5, compare at +0x48
 * - `useActiveSubscription`    @ 0xa2985c, compare at +0xa9 (sets the
 *   `hasPlexPass` field of the returned object)
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
)

private val SITES = listOf(
    // useHasPlexPass: LoadConstString r1, 'on' ; JStrictEqual -> true-branch
    Site(0x6F7193L, byteArrayOf(0x73, 0x01, 0x52, 0x5A), 0xBC.toByte(), 0xBA.toByte()),
    // getHasPlexPass: LoadConstString r1, 'on' ; JStrictEqual -> true-branch
    Site(0x6F71F1L, byteArrayOf(0x73, 0x01, 0x52, 0x5A), 0xBC.toByte(), 0xBA.toByte()),
    // useActiveSubscription: LoadConstTrue r7 ; JStrictEqual -> hasPlexPass=true
    Site(0xA298F9L, byteArrayOf(0x78, 0x07), 0xBC.toByte(), 0xBA.toByte()),
)

private const val HERMES_MAGIC = 0x1F1903C103BC1FC6UL
private const val HERMES_VERSION = 96L

@Suppress("unused")
val premiumUnlockPatch = rawResourcePatch(
    name = "Premium unlock",
    description = "Unlocks Plex Pass features client-side (auto Skip Intros, downloads, ...).",
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

        for (site in SITES) {
            val at = site.opcodeOffset.toInt()
            for ((i, b) in site.preceding.withIndex()) {
                if (data[at - site.preceding.size + i] != b) {
                    throw PatchException(
                        "Unexpected bytecode at 0x${(at - site.preceding.size + i).toString(16)}"
                    )
                }
            }
            if (data[at] != site.from) {
                throw PatchException("Expected 0x${site.from.toInt().and(0xff).toString(16)} at 0x${at.toString(16)}")
            }
            data[at] = site.to
        }

        val sha = MessageDigest.getInstance("SHA-1")
            .digest(data, 0, data.size - 20)
        System.arraycopy(sha, 0, data, data.size - 20, 20)

        bundle.writeBytes(data)
    }
}

private fun readU32(data: ByteArray, at: Int): Long =
    (data[at].toLong() and 0xff) or
        ((data[at + 1].toLong() and 0xff) shl 8) or
        ((data[at + 2].toLong() and 0xff) shl 16) or
        ((data[at + 3].toLong() and 0xff) shl 24)
