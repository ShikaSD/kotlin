/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm.vmir

/*
 * The VM's IR before register allocation: a mirror of the VM repository's `ir::Module`, written out by
 * [VmIrWriter] in the format its docs/IR_FORMAT.md specifies. Values and blocks are numbered by position,
 * as the format numbers them, and a value is a mutable virtual register rather than an SSA value.
 */

sealed interface VmType {
    data object I64 : VmType
    data object F64 : VmType

    /** A `Float`: its bits in the register's low half, the high half zero. */
    data object F32 : VmType
    data object I8 : VmType
    data object I16 : VmType
    data object I32 : VmType
    data object U16 : VmType

    /** A `Boolean` in an array: one byte, and an array of it is not an array of [I8]. */
    data object Bool : VmType
    data class Ref(val classIndex: Int) : VmType
}

data class VmFunctionSymbol(val name: String, val paramTypes: List<VmType>, val returnType: VmType?)

class VmModule(val name: String?) {
    val classes = mutableListOf<VmClass>()
    val selectors = mutableListOf<VmSelector>()
    val externals = mutableListOf<VmFunctionSymbol>()
    /** Functions another module defines, which the VM linker finds by name among the program's modules. */
    val imports = mutableListOf<VmFunctionSymbol>()
    val globals = mutableListOf<VmGlobal>()
    val functions = mutableListOf<VmFunction>()
    val initializers = mutableListOf<VmInitializer>()
}

class VmClass(
    val name: String,
    val superClass: Int?,
    val interfaces: List<Int>,
    val isOpen: Boolean,
    val isAbstract: Boolean,
    val kind: VmClassKind,
    /** An interface a JVM object may implement, which the VM asks the JVM about for one. */
    val isHost: Boolean = false,
)

sealed interface VmClassKind {
    /** A class: the fields it declares, after which the VM lays out none of its superclass's, and the methods it supplies. */
    class Class(val fields: List<VmField>, val methods: List<VmMethod>) : VmClassKind

    /**
     * An array, which is its element and nothing more: its class has no name, supertype or interfaces, and every module's
     * array of one element is one type.
     */
    class Array(val element: VmType) : VmClassKind
    data object Interface : VmClassKind

    /** A class another module declares, with the fields of its own the module's code names, which the linker finds by name. */
    class Imported : VmClassKind {
        val fields = mutableListOf<VmField>()
    }
}

data class VmField(val name: String, val type: VmType)

/** A field as an instruction names it: field [index] of the ones the class [owner] lists. */
data class VmFieldRef(val owner: Int, val index: Int)

data class VmMethod(val selector: Int, val function: VmFunctionSymbol)

data class VmSelector(
    val name: String,
    val imported: Boolean,
    val receiver: Int,
    val paramTypes: List<VmType>,
    val returnType: VmType?,
)

class VmGlobal(val name: String, val type: VmType, val start: VmGlobalStart)

sealed interface VmGlobalStart {
    data object Zero : VmGlobalStart
    class Constant(val bits: Long) : VmGlobalStart
    class Owned(val owner: VmOwner) : VmGlobalStart
    data object Imported : VmGlobalStart
}

sealed interface VmOwner {
    data object File : VmOwner
    data class Class(val classIndex: Int) : VmOwner
}

class VmInitializer(val function: VmFunctionSymbol, val owner: VmOwner, val fingerprint: Long)

@JvmInline
value class VmValue(val index: Int)

class VmFunction(val symbol: VmFunctionSymbol, val owner: VmOwner?) {
    /** The name an embedder calls the function by, unique across the linked program. */
    var export: String? = null
    val valueTypes = mutableListOf<VmType>()
    val params = mutableListOf<VmValue>()
    val blocks = mutableListOf<VmBlock>()
    val handlers = mutableListOf<VmHandler>()
    var entry: Int = 0

    fun newValue(type: VmType): VmValue {
        valueTypes += type
        return VmValue(valueTypes.size - 1)
    }

    fun newBlock(protectedBy: Int? = null): VmBlock =
        VmBlock(blocks.size, protectedBy).also { blocks += it }
}

class VmBlock(val index: Int, var protectedBy: Int?) {
    val instructions = mutableListOf<VmInstruction>()

    /** Null only while the block is still being generated. */
    var terminator: VmTerminator? = null
}

/** A `catch` clause. [next] is the clause tried when this one does not match, and must be a later one. */
class VmHandler(val catchClass: Int, val block: Int, val exception: VmValue, var next: Int?)

sealed interface VmInstruction {
    class Constant(val dst: VmValue, val bits: Long) : VmInstruction
    class StringConstant(val dst: VmValue, val value: String) : VmInstruction
    class Copy(val dst: VmValue, val src: VmValue) : VmInstruction
    class Binary(val op: VmBinaryOp, val dst: VmValue, val lhs: VmValue, val rhs: VmValue) : VmInstruction
    class Unary(val op: VmUnaryOp, val dst: VmValue, val src: VmValue) : VmInstruction
    class Convert(val dst: VmValue, val src: VmValue) : VmInstruction
    class Load(val dst: VmValue, val base: VmValue, val offset: VmValue, val width: VmWidth) : VmInstruction
    class Store(val base: VmValue, val offset: VmValue, val src: VmValue, val width: VmWidth) : VmInstruction
    class NewObject(val dst: VmValue, val classIndex: Int) : VmInstruction
    class NewArray(val dst: VmValue, val classIndex: Int, val length: VmValue) : VmInstruction
    class LoadField(val dst: VmValue, val obj: VmValue, val field: VmFieldRef) : VmInstruction
    class StoreField(val obj: VmValue, val field: VmFieldRef, val value: VmValue) : VmInstruction
    class ArrayLength(val dst: VmValue, val array: VmValue) : VmInstruction
    class ArrayGet(val dst: VmValue, val array: VmValue, val index: VmValue) : VmInstruction
    class ArraySet(val array: VmValue, val index: VmValue, val value: VmValue) : VmInstruction
    class IsNull(val dst: VmValue, val src: VmValue) : VmInstruction
    class RefTest(val dst: VmValue, val src: VmValue, val classIndex: Int) : VmInstruction
    class RefCast(val dst: VmValue, val src: VmValue, val classIndex: Int) : VmInstruction
    class LoadGlobal(val dst: VmValue, val global: Int) : VmInstruction
    class StoreGlobal(val global: Int, val value: VmValue) : VmInstruction
    class Call(val dst: VmValue?, val callee: VmCallee, val args: List<VmValue>) : VmInstruction
    class RefFunc(val dst: VmValue, val function: VmFunctionSymbol, val captures: List<VmValue>) : VmInstruction
}

sealed interface VmCallee {
    class Static(val symbol: VmFunctionSymbol) : VmCallee

    /** The receiver is the call's first argument. */
    class Virtual(val selector: Int) : VmCallee

    /** The function value is the call's first argument. */
    data object Value : VmCallee
}

sealed interface VmTerminator {
    class Jump(val target: Int) : VmTerminator
    class Branch(val condition: VmValue, val ifTrue: Int, val ifFalse: Int) : VmTerminator
    class Return(val value: VmValue?) : VmTerminator
    class Throw(val value: VmValue) : VmTerminator
}

/** In the format's order: an entry's ordinal is its code. */
enum class VmBinaryOp {
    ADD, SUB, MUL, DIV_S, DIV_U, REM_S, REM_U, AND, OR, XOR, SHL, SHR_S, SHR_U,
    EQ, LT_S, LT_U, GT_S, GT_U, LE_S, LE_U, GE_S, GE_U,
}

/** In the format's order: an entry's ordinal is its code. */
enum class VmUnaryOp { NOT, NEG }

/** In the format's order: an entry's ordinal is its code. */
enum class VmWidth { BYTE, QWORD }
