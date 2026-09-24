/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.backend.vm.vmir.*
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride

/**
 * Generates one function's body. A Kotlin variable is one VM value assigned as often as the variable is, since VM values
 * are registers rather than SSA values.
 *
 * Code after a jump is generated into a block nothing branches to rather than skipped, so an expression never has to
 * ask whether it is reachable.
 */
internal class VmFunctionGenerator private constructor(
    private val gen: VmModuleGenerator,
    /** Null for a function only the VM has, such as a file's initializer. */
    private val irFunction: IrFunction?,
    symbol: VmFunctionSymbol,
    owner: VmOwner?,
) {
    constructor(gen: VmModuleGenerator, irFunction: IrFunction) :
            this(gen, irFunction, gen.functionSymbol(irFunction), gen.ownerOf(irFunction))

    private val function = VmFunction(symbol, owner)
    private val values = hashMapOf<IrValueSymbol, VmValue>()
    private val loops = hashMapOf<IrLoop, LoopTargets>()
    private val returnableBlocks = hashMapOf<IrReturnTargetSymbol, ReturnableBlock>()

    /** The block being generated into, or null right after a terminator. */
    private var current: VmBlock? = function.newBlock()

    private class LoopTargets(val continueTarget: VmBlock, val breakTarget: VmBlock)

    /** An inlined body: a `return` to it leaves its value in [result] and jumps to [exit]. */
    private class ReturnableBlock(val exit: VmBlock, val result: VmValue?)

    /** The object a constructor initializes, which it takes before its declared parameters. */
    private val constructed: VmValue? = (irFunction as? IrConstructor)?.let { constructor ->
        function.newValue(gen.anyType).also {
            function.params += it
            values[constructor.constructedClass.thisReceiver!!.symbol] = it
        }
    }

    fun generate(): VmFunction {
        val irFunction = irFunction!!
        for (parameter in irFunction.parameters) {
            val value = function.newValue(gen.vmType(parameter.type)!!)
            function.params += value
            values[parameter.symbol] = value
        }
        when (val body = irFunction.body) {
            is IrBlockBody -> body.statements.forEach(::statement)
            else -> unsupported("the body of ${irFunction.render()}")
        }
        // Only a Unit function falls off its end; any other open block here is unreachable.
        if (current != null) terminate(VmTerminator.Return(function.symbol.returnType?.let { constant(it, 0) }))
        return function
    }

    /** The body of a file's initializer: each field's initializer stored to its global, in declaration order. */
    private fun generateFileInitializer(fields: List<IrField>): VmFunction {
        for (field in fields) {
            emit(VmInstruction.StoreGlobal(gen.globalOf(field), operand(field.initializer!!.expression)))
        }
        terminate(VmTerminator.Return(null))
        return function
    }

    /**
     * The body of a class's initializer: an enum class's entries, then the objects it owns. An entry gets its name and
     * ordinal before its constructor runs, and is stored once it has run, as the JVM stores it.
     */
    private fun generateClassInitializer(parts: List<IrClass>): VmFunction {
        for (declaration in parts) {
            if (declaration.kind == ClassKind.ENUM_CLASS) {
                declaration.declarations.filterIsInstance<IrEnumEntry>().forEachIndexed { ordinal, entry ->
                    createEnumEntry(entry, ordinal)
                }
            } else {
                val classIndex = gen.classIndex(declaration)!!
                val constructor = declaration.primaryConstructor ?: unsupported("the object ${declaration.name} without a constructor")
                val instance = newValue(VmType.Ref(classIndex)).also { emit(VmInstruction.NewObject(it, classIndex)) }
                emit(VmInstruction.StoreGlobal(gen.objectInstance(declaration), instance))
                emit(VmInstruction.Call(null, gen.staticTarget(constructor).callee, listOf(instance)))
            }
        }
        terminate(VmTerminator.Return(null))
        return function
    }

    private fun createEnumEntry(entry: IrEnumEntry, ordinal: Int) {
        val call = entry.initializerExpression?.expression as? IrEnumConstructorCall
            ?: unsupported("the enum entry ${entry.name} without a constructor call")
        val constructor = call.symbol.owner
        val classIndex = gen.classIndex(constructor.constructedClass)!!
        val args = call.arguments.map { operand(it ?: unsupported("a default argument")) }
        val instance = newValue(VmType.Ref(classIndex)).also { emit(VmInstruction.NewObject(it, classIndex)) }
        val name = newValue(gen.stringType).also { emit(VmInstruction.StringConstant(it, entry.name.asString())) }
        emit(VmInstruction.StoreField(instance, ENUM_NAME_FIELD, name))
        emit(VmInstruction.StoreField(instance, ENUM_ORDINAL_FIELD, constant(VmType.I64, ordinal.toLong())))
        emit(VmInstruction.Call(null, gen.staticTarget(constructor).callee, listOf(instance) + args))
        emit(VmInstruction.StoreGlobal(gen.enumEntryGlobal(entry), instance))
    }

    companion object {
        /** Where the runtime's `Enum` lays out an entry's name and ordinal, ahead of every field an enum declares. */
        private const val ENUM_NAME_FIELD = 0
        private const val ENUM_ORDINAL_FIELD = 1

        /** Unowned, as every initializer is. */
        fun classInitializer(gen: VmModuleGenerator, symbol: VmFunctionSymbol, parts: List<IrClass>): VmFunction =
            VmFunctionGenerator(gen, null, symbol, owner = null).generateClassInitializer(parts)

        /** Unowned, as every initializer is: an owned function is one whose calls run the initializer. */
        fun fileInitializer(gen: VmModuleGenerator, symbol: VmFunctionSymbol, fields: List<IrField>): VmFunction =
            VmFunctionGenerator(gen, null, symbol, owner = null).generateFileInitializer(fields)
    }

    private fun block(): VmBlock = current ?: function.newBlock().also { current = it }

    private fun emit(instruction: VmInstruction) {
        block().instructions += instruction
    }

    private fun terminate(terminator: VmTerminator) {
        block().terminator = terminator
        current = null
    }

    private fun jumpTo(target: VmBlock) {
        if (current != null) terminate(VmTerminator.Jump(target.index))
    }

    private fun startBlock(block: VmBlock) {
        jumpTo(block)
        current = block
    }

    private fun newValue(type: VmType) = function.newValue(type)

    private fun constant(type: VmType, bits: Long): VmValue = newValue(type).also { emit(VmInstruction.Constant(it, bits)) }

    private fun binary(op: VmBinaryOp, lhs: VmValue, rhs: VmValue, type: VmType = VmType.I64): VmValue =
        newValue(type).also { emit(VmInstruction.Binary(op, it, lhs, rhs)) }

    private fun statement(statement: IrStatement) {
        when (statement) {
            is IrVariable -> {
                val value = newValue(vmType(statement.type))
                values[statement.symbol] = value
                statement.initializer?.let { initializer -> emit(VmInstruction.Copy(value, operand(initializer))) }
            }
            is IrExpression -> expression(statement)
            else -> unsupported("the statement ${statement.render()}")
        }
    }

    private fun vmType(type: IrType): VmType = gen.vmType(type) ?: unsupported("a value of type ${type.render()}")

    /** The value of [expression], which must have one: a placeholder when the expression never completes. */
    private fun operand(expression: IrExpression): VmValue = expression(expression) ?: newValue(vmType(expression.type))

    /** The value of [expression], or null when it has none: its type is `Unit` or `Nothing`. */
    private fun expression(expression: IrExpression): VmValue? = when (expression) {
        is IrConst -> constant(expression)
        is IrGetValue -> getValue(expression)
        is IrSetValue -> {
            val value = operand(expression.value)
            emit(VmInstruction.Copy(values[expression.symbol] ?: unsupported("a captured variable"), value))
            null
        }
        is IrReturn -> {
            ret(expression)
            null
        }
        is IrWhen -> whenExpression(expression)
        is IrWhileLoop -> whileLoop(expression)
        is IrDoWhileLoop -> doWhileLoop(expression)
        is IrBreak -> {
            terminate(VmTerminator.Jump(loopTargets(expression).breakTarget.index))
            null
        }
        is IrContinue -> {
            terminate(VmTerminator.Jump(loopTargets(expression).continueTarget.index))
            null
        }
        is IrReturnableBlock -> returnableBlock(expression)
        is IrContainerExpression -> container(expression)
        is IrCall -> call(expression)
        is IrConstructorCall -> constructorCall(expression)
        is IrDelegatingConstructorCall -> {
            delegatingConstructorCall(expression)
            null
        }
        // Only an enum's or an entry's constructor delegates with one; an entry's creation is its class initializer's.
        is IrEnumConstructorCall -> {
            delegatingConstructorCall(expression)
            null
        }
        is IrGetEnumValue -> newValue(gen.anyType).also { emit(VmInstruction.LoadGlobal(it, gen.enumEntryGlobal(expression.symbol.owner))) }
        is IrInstanceInitializerCall -> {
            instanceInitializers(expression.classSymbol.owner)
            null
        }
        is IrGetField -> {
            val field = expression.symbol.owner
            val receiver = expression.receiver
            val dst = newValue(vmType(field.type))
            if (receiver == null) {
                emit(VmInstruction.LoadGlobal(dst, gen.globalOf(field)))
            } else {
                emit(VmInstruction.LoadField(dst, operand(receiver), gen.fieldIndex(field)))
            }
            dst
        }
        is IrSetField -> {
            val field = expression.symbol.owner
            val receiver = expression.receiver
            if (receiver == null) {
                emit(VmInstruction.StoreGlobal(gen.globalOf(field), operand(expression.value)))
            } else {
                val obj = operand(receiver)
                emit(VmInstruction.StoreField(obj, gen.fieldIndex(field), operand(expression.value)))
            }
            null
        }
        is IrTypeOperatorCall -> typeOperator(expression)
        is IrGetObjectValue -> {
            if (expression.type.isUnit()) {
                null
            } else {
                newValue(gen.anyType).also { emit(VmInstruction.LoadGlobal(it, gen.objectInstance(expression.symbol.owner))) }
            }
        }
        else -> unsupported("the expression ${expression.render()}")
    }

    private fun ret(expression: IrReturn) {
        val target = expression.returnTargetSymbol
        if (target == irFunction?.symbol) {
            val value = if (function.symbol.returnType == null) {
                expression(expression.value)
                null
            } else {
                operand(expression.value)
            }
            terminate(VmTerminator.Return(value))
            return
        }
        val block = returnableBlocks[target] ?: unsupported("a return from a lambda that is not inlined")
        if (block.result == null) {
            expression(expression.value)
        } else {
            emit(VmInstruction.Copy(block.result, operand(expression.value)))
        }
        jumpTo(block.exit)
    }

    private fun returnableBlock(expression: IrReturnableBlock): VmValue? {
        val result = gen.vmType(expression.type)?.let(::newValue)
        val exit = function.newBlock()
        returnableBlocks[expression.symbol] = ReturnableBlock(exit, result)
        val value = container(expression)
        if (result != null && value != null) emit(VmInstruction.Copy(result, value))
        startBlock(exit)
        return result
    }

    private fun loopTargets(jump: IrBreakContinue): LoopTargets = loops[jump.loop] ?: unsupported("a jump out of an inlined lambda")

    private fun constant(expression: IrConst): VmValue {
        if (expression.kind == IrConstKind.String) {
            return newValue(gen.stringType).also { emit(VmInstruction.StringConstant(it, expression.value as String)) }
        }
        val type = vmType(expression.type)
        val bits = when (expression.kind) {
            IrConstKind.Null -> 0L
            IrConstKind.Boolean -> if (expression.value as Boolean) 1L else 0L
            IrConstKind.Char -> (expression.value as Char).code.toLong()
            IrConstKind.Byte, IrConstKind.Short, IrConstKind.Int, IrConstKind.Long -> (expression.value as Number).toLong()
            IrConstKind.Double -> (expression.value as Double).toRawBits()
            IrConstKind.Float, IrConstKind.String -> unsupported("the constant ${expression.render()}")
        }
        return constant(type, bits)
    }

    /** A `var`'s current value is copied out, so a later assignment cannot reach an operand already read. */
    private fun getValue(expression: IrGetValue): VmValue {
        val value = values[expression.symbol] ?: unsupported("a captured value")
        if ((expression.symbol.owner as? IrVariable)?.isVar != true) return value
        return newValue(function.valueTypes[value.index]).also { emit(VmInstruction.Copy(it, value)) }
    }

    private fun container(expression: IrContainerExpression): VmValue? {
        var result: VmValue? = null
        expression.statements.forEachIndexed { index, statement ->
            if (index == expression.statements.lastIndex && statement is IrExpression) result = expression(statement)
            else statement(statement)
        }
        return if (gen.vmType(expression.type) == null) null else result
    }

    private fun whenExpression(expression: IrWhen): VmValue? {
        val result = gen.vmType(expression.type)?.let(::newValue)
        val join = function.newBlock()
        for (branch in expression.branches) {
            val body = function.newBlock()
            if (branch is IrElseBranch) {
                startBlock(body)
            } else {
                val next = function.newBlock()
                terminate(VmTerminator.Branch(operand(branch.condition), body.index, next.index))
                current = body
                branchResult(branch.result, result)
                jumpTo(join)
                current = next
                continue
            }
            branchResult(branch.result, result)
            jumpTo(join)
            break
        }
        startBlock(join)
        return result
    }

    private fun branchResult(expression: IrExpression, result: VmValue?) {
        if (result == null) {
            expression(expression)
        } else {
            expression(expression)?.let { emit(VmInstruction.Copy(result, it)) }
        }
    }

    private fun whileLoop(loop: IrWhileLoop): VmValue? {
        val header = function.newBlock()
        val body = function.newBlock()
        val exit = function.newBlock()
        loops[loop] = LoopTargets(continueTarget = header, breakTarget = exit)
        startBlock(header)
        terminate(VmTerminator.Branch(operand(loop.condition), body.index, exit.index))
        current = body
        loop.body?.let(::expression)
        jumpTo(header)
        current = exit
        return null
    }

    private fun doWhileLoop(loop: IrDoWhileLoop): VmValue? {
        val body = function.newBlock()
        val condition = function.newBlock()
        val exit = function.newBlock()
        loops[loop] = LoopTargets(continueTarget = condition, breakTarget = exit)
        startBlock(body)
        loop.body?.let(::expression)
        startBlock(condition)
        terminate(VmTerminator.Branch(operand(loop.condition), body.index, exit.index))
        current = exit
        return null
    }

    private fun typeOperator(expression: IrTypeOperatorCall): VmValue? = when (expression.operator) {
        IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
            expression(expression.argument)
            null
        }
        IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL -> {
            val from = expression.argument.type
            val to = expression.typeOperand
            if (vmType(from) != vmType(to) && (from.isPrimitiveType() || to.isPrimitiveType())) {
                if (from.isPrimitiveType()) unsupported("boxing ${from.render()} into ${to.render()}")
                unsupported("unboxing ${from.render()} into ${to.render()}")
            }
            operand(expression.argument)
        }
        IrTypeOperator.INSTANCEOF -> instanceOf(operand(expression.argument), expression.typeOperand)
        IrTypeOperator.NOT_INSTANCEOF -> not(instanceOf(operand(expression.argument), expression.typeOperand))
        IrTypeOperator.CAST -> cast(operand(expression.argument), expression.typeOperand)
        IrTypeOperator.SAFE_CAST -> safeCast(operand(expression.argument), expression.typeOperand)
        else -> unsupported("the type operator ${expression.operator}")
    }

    private fun referenceType(type: IrType): IrType {
        if (type.isPrimitiveType() || type.isNullablePrimitiveType()) unsupported("a type test against the primitive ${type.render()}")
        return type
    }

    /** Whether [value] is an instance of [type]: null is one only of a nullable type. */
    private fun instanceOf(value: VmValue, type: IrType): VmValue {
        val tested = gen.testedClass(referenceType(type))
        val isInstance = if (tested == null) {
            not(isNull(value))
        } else {
            newValue(VmType.I64).also { emit(VmInstruction.RefTest(it, value, tested)) }
        }
        return if (type.isMarkedNullable()) binary(VmBinaryOp.OR, isInstance, isNull(value)) else isInstance
    }

    /** `as`: the VM's cast lets null through, so a cast to a non-null type checks for it first. */
    private fun cast(value: VmValue, type: IrType): VmValue {
        val tested = gen.testedClass(referenceType(type))
        if (!type.isMarkedNullable()) throwIfNull(value)
        if (tested == null) return value
        return newValue(VmType.Ref(tested)).also { emit(VmInstruction.RefCast(it, value, tested)) }
    }

    private fun throwIfNull(value: VmValue) {
        val fail = function.newBlock()
        val next = function.newBlock()
        terminate(VmTerminator.Branch(isNull(value), fail.index, next.index))
        current = fail
        val exception = newValue(VmType.Ref(gen.nullPointerClass)).also { emit(VmInstruction.NewObject(it, gen.nullPointerClass)) }
        terminate(VmTerminator.Throw(exception))
        current = next
    }

    /** `as?`: the value when it is an instance of the non-null [type], null otherwise. */
    private fun safeCast(value: VmValue, type: IrType): VmValue {
        val result = newValue(gen.anyType)
        val instance = function.newBlock()
        val other = function.newBlock()
        val join = function.newBlock()
        terminate(VmTerminator.Branch(instanceOf(value, type.makeNotNull()), instance.index, other.index))
        current = instance
        emit(VmInstruction.Copy(result, value))
        jumpTo(join)
        current = other
        emit(VmInstruction.Constant(result, 0))
        startBlock(join)
        return result
    }

    private fun call(call: IrCall): VmValue? {
        val callee = call.symbol.owner
        intrinsic(call, callee)?.let { return it.value }
        val target = gen.callTarget(callee, isSuperCall = call.superQualifierSymbol != null)
        val args = call.arguments.map { operand(it ?: unsupported("a default argument")) }
        val returnType = target.symbol.returnType
        val type = call.type
        val expected = gen.vmType(type)
        if (returnType != null && expected != null && (returnType is VmType.Ref) != (expected is VmType.Ref)) {
            unsupported("a call returning ${type.render()} through a generic declaration, which needs boxing")
        }
        val dst = returnType?.let(::newValue)
        emit(VmInstruction.Call(dst, target.callee, args))
        return dst
    }

    /** Allocates the object, then runs the constructor on it with the arguments already evaluated. */
    private fun constructorCall(call: IrConstructorCall): VmValue {
        val constructor = call.symbol.owner
        val classIndex = gen.classIndex(constructor.constructedClass) ?: unsupported("constructing ${constructor.constructedClass.name}")
        val args = call.arguments.map { operand(it ?: unsupported("a default argument")) }
        val obj = newValue(VmType.Ref(classIndex)).also { emit(VmInstruction.NewObject(it, classIndex)) }
        emit(VmInstruction.Call(null, gen.staticTarget(constructor).callee, listOf(obj) + args))
        return obj
    }

    /** `super(...)` or `this(...)`: another constructor run on the object this one initializes. */
    private fun delegatingConstructorCall(call: IrFunctionAccessExpression) {
        val constructor = call.symbol.owner as IrConstructor
        // `Any`'s constructor does nothing, and `Enum`'s name and ordinal are stored by whatever creates the entry, so
        // the runtime declares neither.
        if (with(gen) { constructor.constructedClass.isAny() || constructor.constructedClass.isEnumBase() }) return
        val args = call.arguments.map { operand(it ?: unsupported("a default argument")) }
        emit(VmInstruction.Call(null, gen.staticTarget(constructor).callee, listOf(constructed!!) + args))
    }

    /** Property initializers and `init` blocks, in the order the class declares them. */
    private fun instanceInitializers(declaration: IrClass) {
        val self = constructed!!
        for (member in declaration.declarations) {
            when (member) {
                is IrProperty -> member.backingField?.let { initializeField(self, it) }
                is IrField -> initializeField(self, member)
                is IrAnonymousInitializer -> member.body.statements.forEach(::statement)
                else -> {}
            }
        }
    }

    private fun initializeField(self: VmValue, field: IrField) {
        val initializer = field.initializer ?: return
        emit(VmInstruction.StoreField(self, gen.fieldIndex(field), operand(initializer.expression)))
    }

    /** What an intrinsic produced: a value, or none for one returning `Unit`. */
    private class Intrinsic(val value: VmValue?)

    /** Generates [call] when its callee is an operator of the built-ins, or null when it is an ordinary call. */
    private fun intrinsic(call: IrCall, callee: IrSimpleFunction): Intrinsic? {
        val builtIns = gen.irBuiltIns
        val symbol = call.symbol
        val args = call.arguments.map { it ?: unsupported("a default argument to ${callee.render()}") }
        val value = when (symbol) {
            builtIns.eqeqeqSymbol -> binary(VmBinaryOp.EQ, operand(args[0]), operand(args[1]))
            builtIns.eqeqSymbol -> equals(args[0], args[1])
            builtIns.booleanNotSymbol -> not(operand(args[0]))
            in builtIns.ieee754equalsFunByOperandType.values -> compare(Comparison.EQ, args[0], args[1])
            in builtIns.lessFunByOperandType.values -> compare(Comparison.LT, args[0], args[1])
            in builtIns.lessOrEqualFunByOperandType.values -> compare(Comparison.LE, args[0], args[1])
            in builtIns.greaterFunByOperandType.values -> compare(Comparison.GT, args[0], args[1])
            in builtIns.greaterOrEqualFunByOperandType.values -> compare(Comparison.GE, args[0], args[1])
            else -> {
                enumMember(callee, args)?.let { return Intrinsic(it) }
                val owner = callee.parentClassOrNull?.defaultType
                if (callee.body != null || owner == null || !owner.isPrimitiveType()) return null
                primitiveMember(callee, owner.getPrimitiveType()!!, args)
            }
        }
        return Intrinsic(value)
    }

    /** `Enum`'s final members, which read the fields every entry has, or null for any other callee. */
    private fun enumMember(callee: IrSimpleFunction, args: List<IrExpression>): VmValue? {
        val implementation = callee.resolveFakeOverride() ?: return null
        if (with(gen) { implementation.parentClassOrNull?.isEnumBase() } != true) return null
        return when (implementation.name.asString()) {
            "<get-name>" -> newValue(gen.stringType).also { emit(VmInstruction.LoadField(it, operand(args[0]), ENUM_NAME_FIELD)) }
            "<get-ordinal>" -> newValue(VmType.I64).also { emit(VmInstruction.LoadField(it, operand(args[0]), ENUM_ORDINAL_FIELD)) }
            "compareTo" -> {
                val lhs = operand(args[0])
                val rhs = operand(args[1])
                val left = newValue(VmType.I64).also { emit(VmInstruction.LoadField(it, lhs, ENUM_ORDINAL_FIELD)) }
                val right = newValue(VmType.I64).also { emit(VmInstruction.LoadField(it, rhs, ENUM_ORDINAL_FIELD)) }
                binary(VmBinaryOp.SUB, left, right)
            }
            else -> null
        }
    }

    private fun not(value: VmValue): VmValue = binary(VmBinaryOp.EQ, value, constant(VmType.I64, 0))

    private enum class Comparison { EQ, LT, LE, GT, GE }

    private fun compare(comparison: Comparison, lhsExpression: IrExpression, rhsExpression: IrExpression): VmValue {
        val type = primitive(lhsExpression.type)
        val lhs = operand(lhsExpression)
        val rhs = operand(rhsExpression)
        if (type == PrimitiveType.DOUBLE) {
            val op = when (comparison) {
                Comparison.EQ -> VmBinaryOp.EQ
                Comparison.LT -> VmBinaryOp.LT_S
                Comparison.LE -> VmBinaryOp.LE_S
                Comparison.GT -> VmBinaryOp.GT_S
                Comparison.GE -> VmBinaryOp.GE_S
            }
            return binary(op, lhs, rhs)
        }
        // Integral values are held sign-extended (a Char zero-extended), so a signed compare orders every kind. The VM
        // has no integer `le`/`ge`, and for integers they are the negated `gt`/`lt`.
        return when (comparison) {
            Comparison.EQ -> binary(VmBinaryOp.EQ, lhs, rhs)
            Comparison.LT -> binary(VmBinaryOp.LT_S, lhs, rhs)
            Comparison.GT -> binary(VmBinaryOp.GT_S, lhs, rhs)
            Comparison.LE -> not(binary(VmBinaryOp.GT_S, lhs, rhs))
            Comparison.GE -> not(binary(VmBinaryOp.LT_S, lhs, rhs))
        }
    }

    private fun primitive(type: IrType): PrimitiveType {
        if (!type.isPrimitiveType()) unsupported("an operand of type ${type.render()}")
        return type.getPrimitiveType()!!
    }

    /** `==`: the values' equality for primitives, and `equals` behind a null check for references. */
    private fun equals(lhsExpression: IrExpression, rhsExpression: IrExpression): VmValue {
        val lhsType = lhsExpression.type
        val rhsType = rhsExpression.type
        if (lhsType.isPrimitiveType() && rhsType.isPrimitiveType()) {
            if (primitive(lhsType) == PrimitiveType.DOUBLE) unsupported("`==` on Double outside IEEE 754 comparisons")
            return binary(VmBinaryOp.EQ, operand(lhsExpression), operand(rhsExpression))
        }
        if (lhsType.isPrimitiveType() || rhsType.isPrimitiveType() || lhsType.isNullablePrimitiveType() || rhsType.isNullablePrimitiveType()) {
            unsupported("`==` between ${lhsType.render()} and ${rhsType.render()}")
        }
        if (rhsExpression.isNullConstant()) return isNull(operand(lhsExpression))
        if (lhsExpression.isNullConstant()) return isNull(operand(rhsExpression))

        val lhs = operand(lhsExpression)
        val rhs = operand(rhsExpression)
        val result = newValue(VmType.I64)
        val lhsNull = function.newBlock()
        val call = function.newBlock()
        val join = function.newBlock()
        terminate(VmTerminator.Branch(isNull(lhs), lhsNull.index, call.index))
        current = lhsNull
        emit(VmInstruction.Copy(result, isNull(rhs)))
        jumpTo(join)
        current = call
        emit(VmInstruction.Call(result, VmCallee.Virtual(gen.equalsSelector), listOf(lhs, rhs)))
        startBlock(join)
        return result
    }

    private fun IrExpression.isNullConstant() = this is IrConst && kind == IrConstKind.Null

    private fun isNull(value: VmValue): VmValue = newValue(VmType.I64).also { emit(VmInstruction.IsNull(it, value)) }

    /** A member of a primitive class: its operators and conversions. */
    private fun primitiveMember(callee: IrSimpleFunction, receiverType: PrimitiveType, args: List<IrExpression>): VmValue? {
        val name = callee.name.asString()
        val receiver = operand(args[0])
        val resultType = callee.returnType.takeIf { it.isPrimitiveType() }?.getPrimitiveType()
        fun argument() = operand(args[1])
        fun resultType() = resultType ?: unsupported("${receiverType.typeName}.$name")

        return when (name) {
            "plus" -> arithmetic(VmBinaryOp.ADD, receiver, receiverType, args[1], resultType())
            "minus" -> arithmetic(VmBinaryOp.SUB, receiver, receiverType, args[1], resultType())
            "times" -> arithmetic(VmBinaryOp.MUL, receiver, receiverType, args[1], resultType())
            "div" -> arithmetic(VmBinaryOp.DIV_S, receiver, receiverType, args[1], resultType())
            "rem" -> arithmetic(VmBinaryOp.REM_S, receiver, receiverType, args[1], resultType())
            "unaryPlus" -> receiver
            "unaryMinus" -> {
                val type = resultType()
                val value = if (type.isFloating()) receiver else widen(receiver, receiverType, type)
                narrow(newValue(value.vmType()).also { emit(VmInstruction.Unary(VmUnaryOp.NEG, it, value)) }, type)
            }
            "inc" -> narrow(binary(VmBinaryOp.ADD, receiver, one(receiverType), receiver.vmType()), receiverType)
            "dec" -> narrow(binary(VmBinaryOp.SUB, receiver, one(receiverType), receiver.vmType()), receiverType)
            "compareTo" -> {
                if (receiverType.isFloating() || primitive(args[1].type).isFloating()) unsupported("compareTo on floating-point numbers")
                val other = argument()
                binary(VmBinaryOp.SUB, binary(VmBinaryOp.GT_S, receiver, other), binary(VmBinaryOp.LT_S, receiver, other))
            }
            "and" -> binary(VmBinaryOp.AND, receiver, argument())
            "or" -> binary(VmBinaryOp.OR, receiver, argument())
            "xor" -> binary(VmBinaryOp.XOR, receiver, argument())
            "inv" -> newValue(VmType.I64).also { emit(VmInstruction.Unary(VmUnaryOp.NOT, it, receiver)) }
            "not" -> not(receiver)
            "shl", "shr", "ushr" -> shift(name, receiver, receiverType, argument())
            "toByte", "toShort", "toInt", "toLong", "toChar", "toDouble" -> convert(receiver, receiverType, resultType())
            else -> unsupported("${receiverType.typeName}.$name")
        }
    }

    private fun PrimitiveType.isFloating() = this == PrimitiveType.DOUBLE || this == PrimitiveType.FLOAT

    private fun VmValue.vmType(): VmType = function.valueTypes[index]

    private fun one(type: PrimitiveType): VmValue =
        if (type.isFloating()) constant(VmType.F64, 1.0.toRawBits()) else constant(VmType.I64, 1)

    /** An integral value as a floating-point one when [to] is floating; the integral kinds share one representation. */
    private fun widen(value: VmValue, from: PrimitiveType, to: PrimitiveType): VmValue {
        if (!to.isFloating() || from.isFloating()) return value
        return newValue(VmType.F64).also { emit(VmInstruction.Convert(it, value)) }
    }

    private fun arithmetic(
        op: VmBinaryOp,
        receiver: VmValue,
        receiverType: PrimitiveType,
        argument: IrExpression,
        resultType: PrimitiveType,
    ): VmValue {
        val argumentType = primitive(argument.type)
        val lhs = widen(receiver, receiverType, resultType)
        val rhs = widen(operand(argument), argumentType, resultType)
        if (resultType.isFloating()) {
            if (op == VmBinaryOp.REM_S) unsupported("`rem` on floating-point numbers")
            return binary(op, lhs, rhs, VmType.F64)
        }
        return narrow(binary(op, lhs, rhs), resultType)
    }

    /** Brings an integral result back into [type]'s range: sign-extended from its width, or zero-extended for `Char`. */
    private fun narrow(value: VmValue, type: PrimitiveType): VmValue = when (type) {
        PrimitiveType.INT -> signExtend(value, 32)
        PrimitiveType.SHORT -> signExtend(value, 16)
        PrimitiveType.BYTE -> signExtend(value, 8)
        PrimitiveType.CHAR -> binary(VmBinaryOp.AND, value, constant(VmType.I64, 0xFFFF))
        PrimitiveType.LONG, PrimitiveType.BOOLEAN, PrimitiveType.DOUBLE, PrimitiveType.FLOAT -> value
    }

    private fun signExtend(value: VmValue, bits: Int): VmValue {
        val shift = constant(VmType.I64, (64 - bits).toLong())
        return binary(VmBinaryOp.SHR_S, binary(VmBinaryOp.SHL, value, shift), shift)
    }

    /** Shifts as the JVM does: an `Int` shift uses the count's low five bits, a `Long` one its low six, as the VM does. */
    private fun shift(name: String, receiver: VmValue, receiverType: PrimitiveType, count: VmValue): VmValue {
        val isInt = when (receiverType) {
            PrimitiveType.INT -> true
            PrimitiveType.LONG -> false
            else -> unsupported("${receiverType.typeName}.$name")
        }
        val masked = if (isInt) binary(VmBinaryOp.AND, count, constant(VmType.I64, 31)) else count
        return when (name) {
            "shl" -> narrow(binary(VmBinaryOp.SHL, receiver, masked), receiverType)
            "shr" -> binary(VmBinaryOp.SHR_S, receiver, masked)
            else -> {
                val bits = if (isInt) binary(VmBinaryOp.AND, receiver, constant(VmType.I64, 0xFFFF_FFFFL)) else receiver
                narrow(binary(VmBinaryOp.SHR_U, bits, masked), receiverType)
            }
        }
    }

    private fun convert(value: VmValue, from: PrimitiveType, to: PrimitiveType): VmValue {
        if (from.isFloating() == to.isFloating()) return narrow(value, to)
        if (to.isFloating()) return widen(value, from, to)
        // The VM's conversion saturates and takes NaN to zero, which is the JVM's rule for Long only.
        if (to != PrimitiveType.LONG) unsupported("converting ${from.typeName} to ${to.typeName}")
        return newValue(VmType.I64).also { emit(VmInstruction.Convert(it, value)) }
    }
}
