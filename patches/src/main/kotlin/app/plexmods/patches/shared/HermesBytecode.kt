package app.plexmods.patches.shared

import java.security.MessageDigest

/** Thrown when a bundle can't be read as Hermes bytecode this reader understands. */
internal class HermesFormatException(message: String) : Exception(message)

/**
 * Minimal reader for Hermes bytecode (HBC) version 96, the format React Native
 * 0.76–0.80 ships in `assets/index.android.bundle`.
 *
 * Covers only what the patches need: the header, the function table, the
 * string table, an instruction walker and the SHA-1 footer. Layout follows
 * facebook/hermes `BytecodeFileFormat.h`; the opcode table is generated from
 * hermes-dec's `hbc96` definitions and checked against RN 0.80's `hermesc`.
 *
 * Operates on [data] in place; call [updateFooter] after editing it.
 */
internal class HermesBundle(val data: ByteArray) {
    class Function(val id: Int, val offset: Int, val size: Int, val nameId: Int)

    /** One decoded instruction. [offset] is the absolute file offset of the opcode byte. */
    class Instruction(val offset: Int, val opcode: Int, val length: Int, val operands: IntArray) {
        val name: String get() = OPCODE_NAMES[opcode]
    }

    val version: Int
    val fileLength: Int
    private val functionCount: Int
    private val stringCount: Int
    private val overflowStringCount: Int
    private val stringStorageSize: Int
    private val smallStringTableOffset: Int
    private val overflowStringTableOffset: Int
    private val stringStorageOffset: Int

    init {
        if (data.size < HEADER_SIZE + FOOTER_SIZE || u32(0) != MAGIC_LO || u32(4) != MAGIC_HI) {
            throw HermesFormatException("not a Hermes bytecode bundle")
        }
        version = u32(8)
        if (version != SUPPORTED_VERSION) {
            throw HermesFormatException(
                "Hermes bytecode version $version is not supported (expected $SUPPORTED_VERSION)",
            )
        }
        fileLength = u32(32)
        if (fileLength < HEADER_SIZE + FOOTER_SIZE || fileLength > data.size) {
            throw HermesFormatException("bad file length $fileLength (file is ${data.size} bytes)")
        }
        functionCount = count(40)
        val stringKindCount = count(44)
        val identifierCount = count(48)
        stringCount = count(52)
        overflowStringCount = count(56)
        stringStorageSize = count(60)

        // Sections follow the 128-byte header, each aligned to 4 bytes.
        var pos = HEADER_SIZE.toLong() + functionCount.toLong() * SMALL_FUNC_HEADER_SIZE
        pos = align4(pos) + stringKindCount.toLong() * 4
        pos = align4(pos) + identifierCount.toLong() * 4
        pos = align4(pos)
        smallStringTableOffset = pos.toInt()
        pos += stringCount.toLong() * 4
        pos = align4(pos)
        overflowStringTableOffset = pos.toInt()
        pos += overflowStringCount.toLong() * 8
        pos = align4(pos)
        stringStorageOffset = pos.toInt()
        if (pos + stringStorageSize > fileLength) {
            throw HermesFormatException("string storage runs past the end of the file")
        }
    }

    // region Strings

    /** Ids of every string-table entry whose contents equal [value]. */
    fun findStringIds(value: String): List<Int> {
        val ids = ArrayList<Int>()
        for (id in 0 until stringCount) {
            if (stringEquals(id, value)) ids.add(id)
        }
        return ids
    }

    fun stringAt(id: Int): String {
        val entry = stringEntry(id)
        val sb = StringBuilder(entry.length)
        for (i in 0 until entry.length) sb.append(charAt(entry, i))
        return sb.toString()
    }

    private class StringEntry(val isUtf16: Boolean, val offset: Int, val length: Int)

    private fun stringEntry(id: Int): StringEntry {
        if (id < 0 || id >= stringCount) throw HermesFormatException("string id $id out of range")
        val raw = u32(smallStringTableOffset + id * 4)
        val isUtf16 = raw and 1 != 0
        var offset = (raw ushr 1) and 0x7FFFFF
        var length = raw ushr 24
        if (length == 0xFF) {
            if (offset >= overflowStringCount) throw HermesFormatException("bad overflow string index")
            val at = overflowStringTableOffset + offset * 8
            offset = u32(at)
            length = u32(at + 4)
        }
        val bytes = if (isUtf16) length.toLong() * 2 else length.toLong()
        if (offset < 0 || length < 0 || offset + bytes > stringStorageSize) {
            throw HermesFormatException("string $id runs past string storage")
        }
        return StringEntry(isUtf16, stringStorageOffset + offset, length)
    }

    private fun charAt(entry: StringEntry, i: Int): Char =
        if (entry.isUtf16) {
            ((data[entry.offset + i * 2].toInt() and 0xFF) or
                ((data[entry.offset + i * 2 + 1].toInt() and 0xFF) shl 8)).toChar()
        } else {
            (data[entry.offset + i].toInt() and 0xFF).toChar()
        }

    private fun stringEquals(id: Int, value: String): Boolean {
        val entry = stringEntry(id)
        if (entry.length != value.length) return false
        for (i in 0 until entry.length) {
            if (charAt(entry, i) != value[i]) return false
        }
        return true
    }

    // endregion

    // region Functions

    fun function(id: Int): Function {
        if (id < 0 || id >= functionCount) throw HermesFormatException("function id $id out of range")
        val at = HEADER_SIZE + id * SMALL_FUNC_HEADER_SIZE
        val w0 = u32(at)
        val w1 = u32(at + 4)
        val w2 = u32(at + 8)
        val flags = data[at + 15].toInt() and 0xFF

        var offset = w0 and 0x1FFFFFF
        var size = w1 and 0x7FFF
        var nameId = w1 ushr 15
        if (flags and FLAG_OVERFLOWED != 0) {
            // The full-width header lives elsewhere; its position is split
            // across the small header's offset (low 16) and infoOffset fields.
            val large = ((w2 and 0x1FFFFFF) shl 16) or (offset and 0xFFFF)
            if (large < 0 || large + 16 > fileLength) throw HermesFormatException("bad large header for function $id")
            offset = u32(large)
            size = u32(large + 8)
            nameId = u32(large + 12)
        }
        if (offset < 0 || size < 0 || offset.toLong() + size > fileLength) {
            throw HermesFormatException("function $id bytecode runs past the end of the file")
        }
        return Function(id, offset, size, nameId)
    }

    /** All functions whose name is one of [nameIds]. */
    fun functionsNamed(nameIds: Collection<Int>): List<Function> {
        if (nameIds.isEmpty()) return emptyList()
        val wanted = HashSet(nameIds)
        val result = ArrayList<Function>()
        for (id in 0 until functionCount) {
            // Cheap pre-check on the small header before decoding fully.
            val at = HEADER_SIZE + id * SMALL_FUNC_HEADER_SIZE
            val overflowed = data[at + 15].toInt() and FLAG_OVERFLOWED != 0
            if (!overflowed && (u32(at + 4) ushr 15) !in wanted) continue
            val fn = function(id)
            if (fn.nameId in wanted) result.add(fn)
        }
        return result
    }

    /**
     * Decodes [fn]'s instructions in order. Stops at the first `SwitchImm`
     * jump table, which Hermes stores after the instruction stream.
     */
    fun instructions(fn: Function): List<Instruction> {
        val result = ArrayList<Instruction>()
        var end = fn.offset + fn.size
        var pc = fn.offset
        while (pc < end) {
            val opcode = data[pc].toInt() and 0xFF
            if (opcode >= OPCODE_OPERANDS.size) {
                throw HermesFormatException("unknown opcode 0x${opcode.toString(16)} at 0x${pc.toString(16)}")
            }
            val types = OPCODE_OPERANDS[opcode]
            val length = OPCODE_LENGTHS[opcode]
            if (pc + length > end) throw HermesFormatException("truncated instruction at 0x${pc.toString(16)}")

            val operands = IntArray(types.length)
            var at = pc + 1
            for ((i, type) in types.withIndex()) {
                operands[i] = when (type) {
                    'r', 'b' -> data[at].toInt() and 0xFF
                    'a' -> data[at].toInt() // signed
                    's' -> (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
                    'R', 'i', 'A', 'I' -> u32(at)
                    else -> 0 // 'd': double immediate, not needed
                }
                at += operandSize(type)
            }
            result.add(Instruction(pc, opcode, length, operands))

            if (opcode == OP_SWITCH_IMM) {
                // SwitchImm <value>, <jump table offset>, <default target>, <min>, <max>
                val table = pc + operands[1]
                if (table in (pc + length) until end) end = table
            }
            pc += length
        }
        return result
    }

    // endregion

    // region Footer

    /** True when the trailing SHA-1 footer matches the contents. */
    fun footerValid(): Boolean {
        val sha = MessageDigest.getInstance("SHA-1").apply { update(data, 0, fileLength - FOOTER_SIZE) }.digest()
        for (i in 0 until FOOTER_SIZE) {
            if (sha[i] != data[fileLength - FOOTER_SIZE + i]) return false
        }
        return true
    }

    /** Recomputes the SHA-1 footer the Hermes loader checks. */
    fun updateFooter() {
        val sha = MessageDigest.getInstance("SHA-1").apply { update(data, 0, fileLength - FOOTER_SIZE) }.digest()
        System.arraycopy(sha, 0, data, fileLength - FOOTER_SIZE, FOOTER_SIZE)
    }

    // endregion

    fun u32(at: Int): Int =
        (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)

    private fun count(at: Int): Int {
        val value = u32(at)
        if (value < 0 || value > fileLength) throw HermesFormatException("bad header field at $at")
        return value
    }

    private fun align4(pos: Long) = (pos + 3) and 3L.inv()

    companion object {
        const val SUPPORTED_VERSION = 96
        private const val MAGIC_LO = 0x03BC1FC6 // 0x1F1903C103BC1FC6, little-endian
        private const val MAGIC_HI = 0x1F1903C1
        private const val HEADER_SIZE = 128
        private const val SMALL_FUNC_HEADER_SIZE = 16
        private const val FOOTER_SIZE = 20
        private const val FLAG_OVERFLOWED = 0x20

        /**
         * v96 opcodes in order as "Name:operands". Operand codes: r=Reg8, R=Reg32,
         * b=UInt8, s=UInt16, i=UInt32, a=Addr8, A=Addr32, I=Imm32, d=Double.
         */
        private val OPCODES = arrayOf(
            "Unreachable:", "NewObjectWithBuffer:rssss", "NewObjectWithBufferLong:rssii",
            "NewObject:r", "NewObjectWithParent:rr", "NewArrayWithBuffer:rsss",
            "NewArrayWithBufferLong:rssi", "NewArray:rs", "Mov:rr", "MovLong:RR", "Negate:rr",
            "Not:rr", "BitNot:rr", "TypeOf:rr", "Eq:rrr", "StrictEq:rrr", "Neq:rrr", "StrictNeq:rrr",
            "Less:rrr", "LessEq:rrr", "Greater:rrr", "GreaterEq:rrr", "Add:rrr", "AddN:rrr", "Mul:rrr",
            "MulN:rrr", "Div:rrr", "DivN:rrr", "Mod:rrr", "Sub:rrr", "SubN:rrr", "LShift:rrr",
            "RShift:rrr", "URshift:rrr", "BitAnd:rrr", "BitXor:rrr", "BitOr:rrr", "Inc:rr", "Dec:rr",
            "InstanceOf:rrr", "IsIn:rrr", "GetEnvironment:rb", "StoreToEnvironment:rbr",
            "StoreToEnvironmentL:rsr", "StoreNPToEnvironment:rbr", "StoreNPToEnvironmentL:rsr",
            "LoadFromEnvironment:rrb", "LoadFromEnvironmentL:rrs", "GetGlobalObject:r",
            "GetNewTarget:r", "CreateEnvironment:r", "CreateInnerEnvironment:rri",
            "DeclareGlobalVar:i", "ThrowIfHasRestrictedGlobalProperty:i", "GetByIdShort:rrbb",
            "GetById:rrbs", "GetByIdLong:rrbi", "TryGetById:rrbs", "TryGetByIdLong:rrbi",
            "PutById:rrbs", "PutByIdLong:rrbi", "TryPutById:rrbs", "TryPutByIdLong:rrbi",
            "PutNewOwnByIdShort:rrb", "PutNewOwnById:rrs", "PutNewOwnByIdLong:rri",
            "PutNewOwnNEById:rrs", "PutNewOwnNEByIdLong:rri", "PutOwnByIndex:rrb",
            "PutOwnByIndexL:rri", "PutOwnByVal:rrrb", "DelById:rrs", "DelByIdLong:rri", "GetByVal:rrr",
            "PutByVal:rrr", "DelByVal:rrr", "PutOwnGetterSetterByVal:rrrrb", "GetPNameList:rrrr",
            "GetNextPName:rrrrr", "Call:rrb", "Construct:rrb", "Call1:rrr", "CallDirect:rbs",
            "Call2:rrrr", "Call3:rrrrr", "Call4:rrrrrr", "CallLong:rri", "ConstructLong:rri",
            "CallDirectLongIndex:rbi", "CallBuiltin:rbb", "CallBuiltinLong:rbi",
            "GetBuiltinClosure:rb", "Ret:r", "Catch:r", "DirectEval:rrb", "Throw:r", "ThrowIfEmpty:rr",
            "Debugger:", "AsyncBreakCheck:", "ProfilePoint:s", "CreateClosure:rrs",
            "CreateClosureLongIndex:rri", "CreateGeneratorClosure:rrs",
            "CreateGeneratorClosureLongIndex:rri", "CreateAsyncClosure:rrs",
            "CreateAsyncClosureLongIndex:rri", "CreateThis:rrr", "SelectObject:rrr", "LoadParam:rb",
            "LoadParamLong:ri", "LoadConstUInt8:rb", "LoadConstInt:rI", "LoadConstDouble:rd",
            "LoadConstBigInt:rs", "LoadConstBigIntLongIndex:ri", "LoadConstString:rs",
            "LoadConstStringLongIndex:ri", "LoadConstEmpty:r", "LoadConstUndefined:r",
            "LoadConstNull:r", "LoadConstTrue:r", "LoadConstFalse:r", "LoadConstZero:r",
            "CoerceThisNS:rr", "LoadThisNS:r", "ToNumber:rr", "ToNumeric:rr", "ToInt32:rr",
            "AddEmptyString:rr", "GetArgumentsPropByVal:rrr", "GetArgumentsLength:rr",
            "ReifyArguments:r", "CreateRegExp:riii", "SwitchImm:riAii", "StartGenerator:",
            "ResumeGenerator:rr", "CompleteGenerator:", "CreateGenerator:rrs",
            "CreateGeneratorLongIndex:rri", "IteratorBegin:rr", "IteratorNext:rrr", "IteratorClose:rb",
            "Jmp:a", "JmpLong:A", "JmpTrue:ar", "JmpTrueLong:Ar", "JmpFalse:ar", "JmpFalseLong:Ar",
            "JmpUndefined:ar", "JmpUndefinedLong:Ar", "SaveGenerator:a", "SaveGeneratorLong:A",
            "JLess:arr", "JLessLong:Arr", "JNotLess:arr", "JNotLessLong:Arr", "JLessN:arr",
            "JLessNLong:Arr", "JNotLessN:arr", "JNotLessNLong:Arr", "JLessEqual:arr",
            "JLessEqualLong:Arr", "JNotLessEqual:arr", "JNotLessEqualLong:Arr", "JLessEqualN:arr",
            "JLessEqualNLong:Arr", "JNotLessEqualN:arr", "JNotLessEqualNLong:Arr", "JGreater:arr",
            "JGreaterLong:Arr", "JNotGreater:arr", "JNotGreaterLong:Arr", "JGreaterN:arr",
            "JGreaterNLong:Arr", "JNotGreaterN:arr", "JNotGreaterNLong:Arr", "JGreaterEqual:arr",
            "JGreaterEqualLong:Arr", "JNotGreaterEqual:arr", "JNotGreaterEqualLong:Arr",
            "JGreaterEqualN:arr", "JGreaterEqualNLong:Arr", "JNotGreaterEqualN:arr",
            "JNotGreaterEqualNLong:Arr", "JEqual:arr", "JEqualLong:Arr", "JNotEqual:arr",
            "JNotEqualLong:Arr", "JStrictEqual:arr", "JStrictEqualLong:Arr", "JStrictNotEqual:arr",
            "JStrictNotEqualLong:Arr", "Add32:rrr", "Sub32:rrr", "Mul32:rrr", "Divi32:rrr",
            "Divu32:rrr", "Loadi8:rrr", "Loadu8:rrr", "Loadi16:rrr", "Loadu16:rrr", "Loadi32:rrr",
            "Loadu32:rrr", "Store8:rrr", "Store16:rrr", "Store32:rrr"
        )

        val OPCODE_NAMES: Array<String> = Array(OPCODES.size) { OPCODES[it].substringBefore(':') }
        val OPCODE_OPERANDS: Array<String> = Array(OPCODES.size) { OPCODES[it].substringAfter(':') }
        val OPCODE_LENGTHS: IntArray = IntArray(OPCODES.size) { op ->
            var length = 1
            for (type in OPCODE_OPERANDS[op]) length += operandSize(type)
            length
        }

        fun opcode(name: String): Int {
            val op = OPCODE_NAMES.indexOf(name)
            check(op >= 0) { "unknown opcode $name" }
            return op
        }

        private val OP_SWITCH_IMM = opcode("SwitchImm")

        private fun operandSize(type: Char) = when (type) {
            'r', 'b', 'a' -> 1
            's' -> 2
            'R', 'i', 'A', 'I' -> 4
            'd' -> 8
            else -> throw IllegalStateException("bad operand type $type")
        }
    }
}
