package app.plexmods.patches

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.plexmods.patches.shared.Constants.COMPATIBILITY_PLEX
import app.plexmods.patches.shared.HermesBundle
import app.plexmods.patches.shared.HermesFormatException
import java.util.logging.Logger

/**
 * Plex ships its UI as a Hermes (React Native) bytecode bundle:
 * `assets/index.android.bundle` (bytecode version 96).
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
 * The release build strips `setPlexPassOverride`, so the override is never
 * `'on'` and all three fall through to real account state.
 *
 * The patch reads the bundle's function and string tables, finds the three
 * functions by name, and walks their bytecode for strict comparisons against
 * a register holding the string `'on'`. Each one has its opcode swapped for
 * the loose opposite (same operands and length, one byte, no layout change),
 * which inverts the check. The override is never `'on'`, so the inverted check
 * always takes the `'on'` path:
 * - `JStrictEqual` -> `JNotEqual`     (always jumps to the `'on'` path)
 * - `JStrictNotEqual` -> `JEqual`     (never jumps away from it)
 * - `StrictEq` -> `Neq`, `StrictNeq` -> `Eq` (value form used by unoptimised
 *   builds before a `JmpTrue`/`JmpFalse`)
 *
 * The loose opcodes double as the "already patched" marker; Plex's source only
 * uses strict comparisons here. Finding the checks by name rather than by file
 * offset means the patch keeps working across rebuilds and app updates that
 * move the code around.
 *
 * The bundle ends with a SHA-1 footer over everything before it (the Hermes
 * loader verifies it), so the footer is recomputed after the edits.
 */

/** Functions that read `plexPassOverride`; each must contain at least one `'on'` check. */
private val PREMIUM_CHECK_FUNCTIONS = listOf("useHasPlexPass", "getHasPlexPass", "useActiveSubscription")

private const val OVERRIDE_ON = "on"

private val OP_MOV = HermesBundle.opcode("Mov")
private val OP_MOV_LONG = HermesBundle.opcode("MovLong")
private val OP_LOAD_CONST_STRING = HermesBundle.opcode("LoadConstString")
private val OP_LOAD_CONST_STRING_LONG = HermesBundle.opcode("LoadConstStringLongIndex")

/** Strict comparison -> loose opposite with the same operand layout. */
private val FLIPS: Map<Int, Int> = listOf(
    "JStrictEqual" to "JNotEqual",
    "JStrictEqualLong" to "JNotEqualLong",
    "JStrictNotEqual" to "JEqual",
    "JStrictNotEqualLong" to "JEqualLong",
    "StrictEq" to "Neq",
    "StrictNeq" to "Eq",
).associate { (from, to) ->
    val a = HermesBundle.opcode(from)
    val b = HermesBundle.opcode(to)
    check(HermesBundle.OPCODE_OPERANDS[a] == HermesBundle.OPCODE_OPERANDS[b]) { "$from/$to layouts differ" }
    a to b
}

/** Opcodes a flipped check ends up as. */
private val FLIPPED = FLIPS.values.toSet()

/** Instructions that may write registers other than their first operand. */
private val MULTI_WRITE_OPS = setOf("GetPNameList", "GetNextPName", "IteratorBegin", "IteratorNext", "ResumeGenerator")
    .map { HermesBundle.opcode(it) }.toSet()

private class Site(val function: String, val insn: HermesBundle.Instruction, val alreadyPatched: Boolean)

@Suppress("unused")
val premiumUnlockPatch = rawResourcePatch(
    name = "Premium unlock",
    description = "Unlocks Plex Pass features client-side (auto Skip Intros, downloads, ...) and makes the patched output sideload-installable on any device (strips split-install constraints).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PLEX)

    execute {
        val bundleFile = get("assets/index.android.bundle")
        val data = bundleFile.readBytes()
        val bundle = try {
            HermesBundle(data)
        } catch (e: HermesFormatException) {
            throw PatchException("assets/index.android.bundle: ${e.message}")
        }

        val sites = try {
            findPremiumChecks(bundle)
        } catch (e: HermesFormatException) {
            throw PatchException("Could not read assets/index.android.bundle: ${e.message}")
        }

        val logger = Logger.getLogger("PremiumUnlock")
        val pending = sites.filter { !it.alreadyPatched }
        if (pending.isEmpty()) {
            logger.info("Premium checks already patched (${sites.size} sites); bundle left unchanged")
        } else {
            for (site in pending) data[site.insn.offset] = FLIPS.getValue(site.insn.opcode).toByte()
            bundle.updateFooter()
            bundleFile.writeBytes(data)
            logger.info(
                "Patched ${pending.size} premium check(s): " +
                    pending.joinToString { "${it.function}@0x${it.insn.offset.toString(16)} ${it.insn.name}" },
            )
        }

        // Plex's base manifest carries split-install constraints that break
        // sideload installs of the patched output (INSTALL_FAILED_MISSING_SPLIT /
        // INVALID_APK on devices that don't match the patching phone's ABI):
        // - requiredSplitTypes/splitTypes: PMS demands config splits that a
        //   standalone install doesn't have (empty string is not "none" — it
        //   counts as one unnamed type, so the attributes must be removed).
        // - extractNativeLibs=false: only valid for unextracted, page-aligned
        //   split libs; flip so installd extracts ours instead of failing.
        //
        // Morphe Manager's APKM merge already strips these. A raw resource patch
        // usually runs with the manifest left binary and not staged as a file, so
        // only clean it up when it is there as decoded text.
        val manifest = get("AndroidManifest.xml")
        if (!manifest.isFile || !isTextXml(manifest)) {
            logger.info("AndroidManifest.xml not decoded; leaving split cleanup to the APKM merge")
            return@execute
        }
        val xml = manifest.readText()
            .replace(Regex("""\s+android:requiredSplitTypes="[^"]*""""), "")
            .replace(Regex("""\s+android:splitTypes="[^"]*""""), "")
            .replace("""android:extractNativeLibs="false"""", """android:extractNativeLibs="true"""")
        manifest.writeText(xml)
    }
}

/** True for a text XML file; false for binary AXML, which must not be edited as text. */
private fun isTextXml(file: java.io.File): Boolean {
    val head = ByteArray(64)
    val read = file.inputStream().use { it.read(head) }
    if (read <= 0) return false
    return String(head, 0, read, Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\t', '\r', '\n')
        .startsWith("<")
}

/**
 * Finds the `override === 'on'` checks in every premium-check function.
 * Throws a [PatchException] naming what is missing if any function has none.
 */
private fun findPremiumChecks(bundle: HermesBundle): List<Site> {
    val onIds = bundle.findStringIds(OVERRIDE_ON).toSet()
    if (onIds.isEmpty()) {
        throw PatchException("No premium-check layout matches this bundle: the string '$OVERRIDE_ON' is not in it")
    }

    val sites = ArrayList<Site>()
    val problems = ArrayList<String>()
    for (name in PREMIUM_CHECK_FUNCTIONS) {
        val functions = bundle.functionsNamed(bundle.findStringIds(name))
        if (functions.isEmpty()) {
            problems.add("$name: no function with this name")
            continue
        }
        val found = functions.flatMap { findOnChecks(bundle, it, name, onIds) }
        if (found.isEmpty()) {
            problems.add("$name: ${functions.size} function(s) found, none compares against '$OVERRIDE_ON'")
            continue
        }
        sites.addAll(found)
    }
    if (problems.isNotEmpty()) {
        throw PatchException("No premium-check layout matches this bundle (${problems.joinToString("; ")})")
    }
    return sites
}

/**
 * Walks [fn] in order, tracking which registers hold the string `'on'`, and
 * returns every strict (or already flipped) comparison against one of them.
 */
private fun findOnChecks(
    bundle: HermesBundle,
    fn: HermesBundle.Function,
    name: String,
    onIds: Set<Int>,
): List<Site> {
    val onRegs = HashSet<Int>()
    val sites = ArrayList<Site>()
    for (insn in bundle.instructions(fn)) {
        val op = insn.opcode
        val args = insn.operands
        val types = HermesBundle.OPCODE_OPERANDS[op]
        when {
            op == OP_LOAD_CONST_STRING || op == OP_LOAD_CONST_STRING_LONG ->
                if (args[1] in onIds) onRegs.add(args[0]) else onRegs.remove(args[0])

            op == OP_MOV || op == OP_MOV_LONG ->
                if (args[1] in onRegs) onRegs.add(args[0]) else onRegs.remove(args[0])

            op in FLIPS || op in FLIPPED -> {
                // Operands 1 and 2 are the compared registers for both the jump
                // and the value forms; the value forms also write operand 0.
                if (args[1] in onRegs || args[2] in onRegs) {
                    sites.add(Site(name, insn, alreadyPatched = op in FLIPPED))
                }
                if (types[0] == 'r') onRegs.remove(args[0])
            }

            // Other jumps only read registers.
            types.isNotEmpty() && (types[0] == 'a' || types[0] == 'A') -> Unit

            op in MULTI_WRITE_OPS ->
                for ((i, type) in types.withIndex()) if (type == 'r' || type == 'R') onRegs.remove(args[i])

            // Everything else writes its first register operand, if any.
            types.isNotEmpty() && (types[0] == 'r' || types[0] == 'R') -> onRegs.remove(args[0])
        }
    }
    return sites
}
