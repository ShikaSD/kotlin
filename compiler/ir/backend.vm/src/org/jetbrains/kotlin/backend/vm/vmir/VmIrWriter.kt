/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm.vmir

/**
 * Writes a [VmModule] in the binary IR format of the VM repository's docs/IR_FORMAT.md, which its
 * `ir/src/binary.rs` reads. The tags below are that file's; a change to either side changes both.
 */
object VmIrWriter {
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), 'R'.code.toByte())
    private const val VERSION = 1

    fun write(module: VmModule): ByteArray {
        val body = ModuleEncoder(module)
        body.module()

        val out = ByteSink()
        out.bytes(MAGIC)
        out.u32LittleEndian(VERSION)
        out.varint(body.strings.size.toLong())
        for (string in body.strings) {
            val bytes = string.encodeToByteArray()
            out.varint(bytes.size.toLong())
            out.bytes(bytes)
        }
        out.bytes(body.out.toByteArray())
        return out.toByteArray()
    }
}

private object Tag {
    const val TYPE_NONE = 0L
    const val TYPE_I64 = 1L
    const val TYPE_F64 = 2L
    const val TYPE_I8 = 3L
    const val TYPE_I16 = 4L
    const val TYPE_I32 = 5L
    const val TYPE_U16 = 6L
    const val TYPE_REF = 7L
    const val TYPE_F32 = 8L
    const val TYPE_BOOL = 9L

    const val OWNER_NONE = 0L
    const val OWNER_FILE = 1L
    const val OWNER_CLASS = 2L

    const val CLASS = 0L
    const val ARRAY = 1L
    const val INTERFACE = 2L
    const val IMPORTED_CLASS = 3L

    const val FLAG_OPEN = 1L
    const val FLAG_ABSTRACT = 2L

    const val GLOBAL_ZERO = 0L
    const val GLOBAL_CONSTANT = 1L
    const val GLOBAL_OWNED = 2L
    const val GLOBAL_IMPORTED = 3L

    const val CONSTANT = 0L
    const val STRING_CONSTANT = 1L
    const val COPY = 2L
    const val BINARY = 3L
    const val UNARY = 4L
    const val CONVERT = 5L
    const val LOAD = 6L
    const val STORE = 7L
    const val NEW_OBJECT = 8L
    const val NEW_ARRAY = 9L
    const val LOAD_FIELD = 10L
    const val STORE_FIELD = 11L
    const val ARRAY_LEN = 12L
    const val ARRAY_GET = 13L
    const val ARRAY_SET = 14L
    const val IS_NULL = 15L
    const val REF_TEST = 16L
    const val REF_CAST = 17L
    const val LOAD_GLOBAL = 18L
    const val STORE_GLOBAL = 19L
    const val CALL = 20L
    const val REF_FUNC = 21L

    const val CALLEE_STATIC = 0L
    const val CALLEE_VIRTUAL = 1L
    const val CALLEE_VALUE = 2L

    const val JUMP = 0L
    const val BRANCH = 1L
    const val RETURN = 2L
    const val THROW = 3L
}

private class ByteSink {
    private var buffer = ByteArray(256)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun byte(value: Int) {
        ensure(1)
        buffer[size++] = value.toByte()
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun u32LittleEndian(value: Int) {
        for (shift in 0 until 32 step 8) byte(value ushr shift)
    }

    /** Unsigned LEB128 of [value]'s 64 bits. */
    fun varint(value: Long) {
        var rest = value
        while (true) {
            val low = (rest and 0x7F).toInt()
            rest = rest ushr 7
            if (rest == 0L) {
                byte(low)
                return
            }
            byte(low or 0x80)
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

private class ModuleEncoder(private val module: VmModule) {
    val out = ByteSink()
    val strings = mutableListOf<String>()
    private val stringIndices = hashMapOf<String, Int>()
    private val symbolIndices = hashMapOf<VmFunctionSymbol, Int>()

    private fun varint(value: Long) = out.varint(value)
    private fun varint(value: Int) = out.varint(value.toLong())
    private fun optional(index: Int?) = varint(if (index == null) 0L else index + 1L)
    private fun value(value: VmValue) = varint(value.index)
    private fun optionalValue(value: VmValue?) = optional(value?.index)

    private fun string(string: String) {
        varint(stringIndices.getOrPut(string) { strings.add(string); strings.size - 1 })
    }

    private fun optionalString(string: String?) {
        if (string == null) {
            varint(0)
        } else {
            varint(1)
            string(string)
        }
    }

    private fun type(type: VmType) {
        when (type) {
            VmType.I64 -> varint(Tag.TYPE_I64)
            VmType.F64 -> varint(Tag.TYPE_F64)
            VmType.F32 -> varint(Tag.TYPE_F32)
            VmType.I8 -> varint(Tag.TYPE_I8)
            VmType.I16 -> varint(Tag.TYPE_I16)
            VmType.I32 -> varint(Tag.TYPE_I32)
            VmType.U16 -> varint(Tag.TYPE_U16)
            VmType.Bool -> varint(Tag.TYPE_BOOL)
            is VmType.Ref -> {
                varint(Tag.TYPE_REF)
                varint(type.classIndex)
            }
        }
    }

    private fun optionalType(type: VmType?) {
        if (type == null) varint(Tag.TYPE_NONE) else type(type)
    }

    private fun types(types: List<VmType>) {
        varint(types.size)
        types.forEach(::type)
    }

    private fun optionalOwner(owner: VmOwner?) {
        when (owner) {
            null -> varint(Tag.OWNER_NONE)
            VmOwner.File -> varint(Tag.OWNER_FILE)
            is VmOwner.Class -> {
                varint(Tag.OWNER_CLASS)
                varint(owner.classIndex)
            }
        }
    }

    private fun symbol(symbol: VmFunctionSymbol) {
        varint(symbolIndices[symbol] ?: error("${symbol.name} is missing from the symbol table"))
    }

    fun module() {
        varint(module.classes.size)
        varint(module.selectors.size)

        val symbols = symbolTable()
        varint(symbols.size)
        symbols.forEachIndexed { index, symbol ->
            symbolIndices[symbol] = index
            string(symbol.name)
            types(symbol.paramTypes)
            optionalType(symbol.returnType)
        }

        optionalString(module.name)
        module.classes.forEach(::classDeclaration)
        for (selector in module.selectors) {
            string(selector.name)
            optionalString(selector.module)
            varint(selector.receiver)
            types(selector.paramTypes)
            optionalType(selector.returnType)
        }
        varint(module.externals.size)
        module.externals.forEach(::symbol)
        varint(module.imports.size)
        for (import in module.imports) {
            string(import.module)
            symbol(import.symbol)
        }
        varint(module.globals.size)
        module.globals.forEach(::global)
        varint(module.functions.size)
        module.functions.forEach(::function)
        varint(module.initializers.size)
        for (initializer in module.initializers) {
            symbol(initializer.function)
            optionalOwner(initializer.owner)
            varint(initializer.fingerprint)
        }
    }

    /** Every function symbol the module names, once each, in first-use order. */
    private fun symbolTable(): List<VmFunctionSymbol> {
        val table = LinkedHashSet<VmFunctionSymbol>()
        table += module.externals
        module.imports.mapTo(table) { it.symbol }
        for (declaration in module.classes) {
            val kind = declaration.kind
            if (kind is VmClassKind.Class) kind.methods.mapTo(table) { it.function }
        }
        module.functions.mapTo(table) { it.symbol }
        module.initializers.mapTo(table) { it.function }
        for (function in module.functions) {
            for (block in function.blocks) {
                for (instruction in block.instructions) {
                    when (instruction) {
                        is VmInstruction.Call -> (instruction.callee as? VmCallee.Static)?.let { table += it.symbol }
                        is VmInstruction.RefFunc -> table += instruction.function
                        else -> {}
                    }
                }
            }
        }
        return table.toList()
    }

    private fun classDeclaration(declaration: VmClass) {
        string(declaration.name)
        optional(declaration.superClass)
        varint(declaration.interfaces.size)
        declaration.interfaces.forEach(::varint)
        val open = if (declaration.isOpen) Tag.FLAG_OPEN else 0L
        val abstract = if (declaration.isAbstract) Tag.FLAG_ABSTRACT else 0L
        varint(open or abstract)
        when (val kind = declaration.kind) {
            is VmClassKind.Class -> {
                varint(Tag.CLASS)
                varint(kind.fields.size)
                for (field in kind.fields) {
                    string(field.name)
                    type(field.type)
                }
                varint(kind.methods.size)
                for (method in kind.methods) {
                    varint(method.selector)
                    symbol(method.function)
                }
            }
            is VmClassKind.Array -> {
                varint(Tag.ARRAY)
                type(kind.element)
            }
            VmClassKind.Interface -> varint(Tag.INTERFACE)
            is VmClassKind.Imported -> {
                varint(Tag.IMPORTED_CLASS)
                string(kind.module)
            }
        }
    }

    private fun global(global: VmGlobal) {
        string(global.name)
        type(global.type)
        when (val start = global.start) {
            VmGlobalStart.Zero -> varint(Tag.GLOBAL_ZERO)
            is VmGlobalStart.Constant -> {
                varint(Tag.GLOBAL_CONSTANT)
                varint(start.bits)
            }
            is VmGlobalStart.Owned -> {
                varint(Tag.GLOBAL_OWNED)
                optionalOwner(start.owner)
            }
            is VmGlobalStart.Imported -> {
                varint(Tag.GLOBAL_IMPORTED)
                string(start.module)
            }
        }
    }

    private fun function(function: VmFunction) {
        symbol(function.symbol)
        optionalOwner(function.owner)
        optionalString(function.export)
        types(function.valueTypes)
        varint(function.params.size)
        function.params.forEach(::value)
        varint(function.blocks.size)
        varint(function.handlers.size)
        varint(function.entry)
        function.handlers.forEachIndexed { index, handler ->
            val next = handler.next
            require(next == null || next > index) {
                "${function.symbol.name}: handler $index must link to a later handler"
            }
            varint(handler.catchClass)
            varint(handler.block)
            value(handler.exception)
            optional(next)
        }
        for (block in function.blocks) {
            optional(block.protectedBy)
            varint(block.instructions.size)
            block.instructions.forEach(::instruction)
            terminator(block.terminator ?: error("${function.symbol.name}: block ${block.index} has no terminator"))
        }
    }

    private fun terminator(terminator: VmTerminator) {
        when (terminator) {
            is VmTerminator.Jump -> {
                varint(Tag.JUMP)
                varint(terminator.target)
            }
            is VmTerminator.Branch -> {
                varint(Tag.BRANCH)
                value(terminator.condition)
                varint(terminator.ifTrue)
                varint(terminator.ifFalse)
            }
            is VmTerminator.Return -> {
                varint(Tag.RETURN)
                optionalValue(terminator.value)
            }
            is VmTerminator.Throw -> {
                varint(Tag.THROW)
                value(terminator.value)
            }
        }
    }

    private fun instruction(instruction: VmInstruction) {
        when (instruction) {
            is VmInstruction.Constant -> {
                varint(Tag.CONSTANT)
                value(instruction.dst)
                varint(instruction.bits)
            }
            is VmInstruction.StringConstant -> {
                varint(Tag.STRING_CONSTANT)
                value(instruction.dst)
                varint(instruction.value.length)
                for (unit in instruction.value) varint(unit.code)
            }
            is VmInstruction.Copy -> {
                varint(Tag.COPY)
                value(instruction.dst)
                value(instruction.src)
            }
            is VmInstruction.Binary -> {
                varint(Tag.BINARY)
                varint(instruction.op.ordinal)
                value(instruction.dst)
                value(instruction.lhs)
                value(instruction.rhs)
            }
            is VmInstruction.Unary -> {
                varint(Tag.UNARY)
                varint(instruction.op.ordinal)
                value(instruction.dst)
                value(instruction.src)
            }
            is VmInstruction.Convert -> {
                varint(Tag.CONVERT)
                value(instruction.dst)
                value(instruction.src)
            }
            is VmInstruction.Load -> {
                varint(Tag.LOAD)
                value(instruction.dst)
                value(instruction.base)
                value(instruction.offset)
                varint(instruction.width.ordinal)
            }
            is VmInstruction.Store -> {
                varint(Tag.STORE)
                value(instruction.base)
                value(instruction.offset)
                value(instruction.src)
                varint(instruction.width.ordinal)
            }
            is VmInstruction.NewObject -> {
                varint(Tag.NEW_OBJECT)
                value(instruction.dst)
                varint(instruction.classIndex)
            }
            is VmInstruction.NewArray -> {
                varint(Tag.NEW_ARRAY)
                value(instruction.dst)
                varint(instruction.classIndex)
                value(instruction.length)
            }
            is VmInstruction.LoadField -> {
                varint(Tag.LOAD_FIELD)
                value(instruction.dst)
                value(instruction.obj)
                varint(instruction.field)
            }
            is VmInstruction.StoreField -> {
                varint(Tag.STORE_FIELD)
                value(instruction.obj)
                varint(instruction.field)
                value(instruction.value)
            }
            is VmInstruction.ArrayLength -> {
                varint(Tag.ARRAY_LEN)
                value(instruction.dst)
                value(instruction.array)
            }
            is VmInstruction.ArrayGet -> {
                varint(Tag.ARRAY_GET)
                value(instruction.dst)
                value(instruction.array)
                value(instruction.index)
            }
            is VmInstruction.ArraySet -> {
                varint(Tag.ARRAY_SET)
                value(instruction.array)
                value(instruction.index)
                value(instruction.value)
            }
            is VmInstruction.IsNull -> {
                varint(Tag.IS_NULL)
                value(instruction.dst)
                value(instruction.src)
            }
            is VmInstruction.RefTest -> {
                varint(Tag.REF_TEST)
                value(instruction.dst)
                value(instruction.src)
                varint(instruction.classIndex)
            }
            is VmInstruction.RefCast -> {
                varint(Tag.REF_CAST)
                value(instruction.dst)
                value(instruction.src)
                varint(instruction.classIndex)
            }
            is VmInstruction.LoadGlobal -> {
                varint(Tag.LOAD_GLOBAL)
                value(instruction.dst)
                varint(instruction.global)
            }
            is VmInstruction.StoreGlobal -> {
                varint(Tag.STORE_GLOBAL)
                varint(instruction.global)
                value(instruction.value)
            }
            is VmInstruction.Call -> {
                varint(Tag.CALL)
                optionalValue(instruction.dst)
                when (val callee = instruction.callee) {
                    is VmCallee.Static -> {
                        varint(Tag.CALLEE_STATIC)
                        symbol(callee.symbol)
                    }
                    is VmCallee.Virtual -> {
                        varint(Tag.CALLEE_VIRTUAL)
                        varint(callee.selector)
                    }
                    VmCallee.Value -> varint(Tag.CALLEE_VALUE)
                }
                varint(instruction.args.size)
                instruction.args.forEach(::value)
            }
            is VmInstruction.RefFunc -> {
                varint(Tag.REF_FUNC)
                value(instruction.dst)
                symbol(instruction.function)
                varint(instruction.captures.size)
                instruction.captures.forEach(::value)
            }
        }
    }
}
