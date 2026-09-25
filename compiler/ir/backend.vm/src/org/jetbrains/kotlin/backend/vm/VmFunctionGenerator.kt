/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.backend.vm.vmir.*
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isFunctionOrKFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.util.OperatorNameConventions

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

    /** The head handler protecting a block created now, by creation order, or null outside every `try`. */
    private var protection: Int? = null

    /** How many `try` regions enclose each handler, by creation order; see [renumberHandlers]. */
    private val handlerDepths = mutableListOf<Int>()
    private var tryDepth = 0

    /** The `finally` blocks around the code being generated, innermost last. */
    private val finallyFrames = mutableListOf<FinallyFrame>()

    /** A `finally` body, and the protection and depth around its `try`, which are what it runs under. */
    private class FinallyFrame(val body: IrExpression, val protection: Int?, val tryDepth: Int)

    /** The block being generated into, or null right after a terminator. */
    private var current: VmBlock? = newBlock()

    /** A loop's jump targets, and how many `finally` blocks enclosed the loop. */
    private class LoopTargets(val continueTarget: VmBlock, val breakTarget: VmBlock, val finallyDepth: Int)

    /** An inlined body: a `return` to it leaves its value in [result] and jumps to [exit]. */
    private class ReturnableBlock(val exit: VmBlock, val result: VmValue?, val type: IrType, val finallyDepth: Int)

    /** The object a constructor initializes, which it takes before its declared parameters. */
    private val constructed: VmValue? = (irFunction as? IrConstructor)?.let { constructor ->
        function.newValue(gen.anyType).also {
            function.params += it
            values[constructor.constructedClass.thisReceiver!!.symbol] = it
        }
    }

    fun generate(): VmFunction {
        val irFunction = irFunction!!
        // A parameter arrives as the signature says, which is the overridden declaration's: an override taking a
        // primitive where that takes a reference gets a box. Reading it would unbox it anyway; unboxing it here does
        // so once rather than at every read.
        val paramTypes = function.symbol.paramTypes.drop(if (constructed != null) 1 else 0)
        irFunction.parameters.forEachIndexed { index, parameter ->
            val incoming = function.newValue(paramTypes[index])
            function.params += incoming
            values[parameter.symbol] = normalize(incoming, parameter.type)
        }
        return generateBody(irFunction)
    }

    /**
     * A lambda's body as a function of its own, taking what it [captured] first, as held where it was created, then
     * its `invoke`'s parameters: as they are for the ones bound at creation, and as references for the rest, which
     * [functionValueCall] passes boxed.
     */
    private fun generateLambda(captured: List<IrValueSymbol>): VmFunction {
        val irFunction = irFunction!!
        val paramTypes = function.symbol.paramTypes
        captured.forEachIndexed { index, symbol ->
            values[symbol] = function.newValue(paramTypes[index]).also { function.params += it }
        }
        irFunction.parameters.forEachIndexed { index, parameter ->
            val incoming = function.newValue(paramTypes[captured.size + index])
            function.params += incoming
            values[parameter.symbol] = normalize(incoming, parameter.type)
        }
        return generateBody(irFunction)
    }

    /** [irFunction]'s default arguments' stub; see [VmModuleGenerator.defaultStub]. */
    private fun generateDefaultStub(): VmFunction {
        val irFunction = irFunction!!
        val paramTypes = function.symbol.paramTypes.drop(if (constructed != null) 1 else 0)
        val incoming = irFunction.parameters.mapIndexed { index, parameter ->
            function.newValue(paramTypes[index]).also {
                function.params += it
                values[parameter.symbol] = it
            }
        }
        val mask = function.newValue(VmType.I64).also { function.params += it }
        // In order, as a default may read the parameters before it.
        irFunction.parameters.forEachIndexed { index, parameter ->
            val default = parameter.defaultValue ?: return@forEachIndexed
            val missing = newBlock()
            val given = newBlock()
            terminate(VmTerminator.Branch(binary(VmBinaryOp.AND, mask, constant(VmType.I64, 1L shl index)), missing.index, given.index))
            current = missing
            emit(VmInstruction.Copy(incoming[index], operandAs(default.expression, parameter.type, paramTypes[index])))
            startBlock(given)
        }
        val result = when (irFunction) {
            is IrConstructor -> {
                emit(VmInstruction.Call(null, gen.staticTarget(irFunction).callee, listOf(constructed!!) + incoming))
                null
            }
            // Through the selector for an open function, so an override is what the defaults are passed to.
            is IrSimpleFunction -> {
                val target = gen.callTarget(irFunction, isSuperCall = false)
                target.symbol.returnType?.let(::newValue).also { emit(VmInstruction.Call(it, target.callee, incoming)) }
            }
        }
        terminate(VmTerminator.Return(result))
        return finish()
    }

    private fun generateBody(irFunction: IrFunction): VmFunction {
        when (val body = irFunction.body) {
            is IrBlockBody -> body.statements.forEach(::statement)
            else -> unsupported("the body of ${irFunction.render()}")
        }
        // Only a Unit function falls off its end; any other open block here is unreachable.
        if (current != null) terminate(VmTerminator.Return(function.symbol.returnType?.let { constant(it, 0) }))
        return finish()
    }

    private fun finish(): VmFunction {
        clearUnreachableBlocks()
        renumberHandlers()
        return function
    }

    /**
     * Code after an expression that never completes lands in a block nothing reaches, and may read the placeholder
     * [operand] answered for it, which nothing defines. Such a block only returns, so every value read is defined.
     */
    private fun clearUnreachableBlocks() {
        val reached = BooleanArray(function.blocks.size)
        val pending = ArrayDeque(listOf(function.entry))
        while (pending.isNotEmpty()) {
            val index = pending.removeLast()
            if (reached[index]) continue
            reached[index] = true
            val block = function.blocks[index]
            when (val terminator = block.terminator) {
                is VmTerminator.Jump -> pending += terminator.target
                is VmTerminator.Branch -> pending += listOf(terminator.ifTrue, terminator.ifFalse)
                is VmTerminator.Return, is VmTerminator.Throw, null -> {}
            }
            var handler = block.protectedBy
            while (handler != null) {
                pending += function.handlers[handler].block
                handler = function.handlers[handler].next
            }
        }
        for (block in function.blocks) {
            if (reached[block.index]) continue
            block.instructions.clear()
            val returnType = function.symbol.returnType
            block.terminator = VmTerminator.Return(returnType?.let { type ->
                function.newValue(type).also { block.instructions += VmInstruction.Constant(it, 0) }
            })
        }
    }

    /**
     * The VM requires a handler's `next` to name a later handler, which is what makes every chain end. Handlers are
     * created outermost first, so they are renumbered deepest first: a clause then precedes its siblings after it and
     * every clause of the regions enclosing it.
     */
    private fun renumberHandlers() {
        val order = function.handlers.indices.sortedWith(compareByDescending<Int> { handlerDepths[it] }.thenBy { it })
        val renumbered = IntArray(order.size)
        order.forEachIndexed { new, old -> renumbered[old] = new }
        val handlers = function.handlers.toList()
        function.handlers.clear()
        for (old in order) {
            val handler = handlers[old]
            function.handlers += VmHandler(handler.catchClass, handler.block, handler.exception, handler.next?.let { renumbered[it] })
        }
        for (block in function.blocks) block.protectedBy = block.protectedBy?.let { renumbered[it] }
    }

    private fun newBlock(): VmBlock = function.newBlock(protection)

    /** The body of a file's initializer: each field's initializer stored to its global, in declaration order. */
    private fun generateFileInitializer(fields: List<IrField>): VmFunction {
        for (field in fields) {
            emit(VmInstruction.StoreGlobal(gen.globalOf(field), operandAs(field.initializer!!.expression, field.type)))
        }
        terminate(VmTerminator.Return(null))
        return finish()
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
        return finish()
    }

    private fun createEnumEntry(entry: IrEnumEntry, ordinal: Int) {
        val call = entry.initializerExpression?.expression as? IrEnumConstructorCall
            ?: unsupported("the enum entry ${entry.name} without a constructor call")
        val constructor = call.symbol.owner
        val classIndex = gen.classIndex(constructor.constructedClass)!!
        val [target, args] = constructorInvocation(call, constructor)
        val instance = newValue(VmType.Ref(classIndex)).also { emit(VmInstruction.NewObject(it, classIndex)) }
        val name = newValue(gen.stringType).also { emit(VmInstruction.StringConstant(it, entry.name.asString())) }
        emit(VmInstruction.StoreField(instance, ENUM_NAME_FIELD, name))
        emit(VmInstruction.StoreField(instance, ENUM_ORDINAL_FIELD, constant(VmType.I64, ordinal.toLong())))
        emit(VmInstruction.Call(null, VmCallee.Static(target), listOf(instance) + args))
        emit(VmInstruction.StoreGlobal(gen.enumEntryGlobal(entry), instance))
    }

    companion object {
        /** Where the runtime's boxes hold their value, their only field. */
        private const val BOX_VALUE_FIELD = 0

        /** Where the runtime's `kotlin.internal.SharedVariableBox` holds its variable, its only field. */
        private const val SHARED_BOX_ELEMENT_FIELD = 0

        /** Where the runtime's `Enum` lays out an entry's name and ordinal, ahead of every field an enum declares. */
        internal const val ENUM_NAME_FIELD = 0
        internal const val ENUM_ORDINAL_FIELD = 1

        /** Unowned, as every initializer is. */
        fun defaultStub(gen: VmModuleGenerator, function: IrFunction, symbol: VmFunctionSymbol): VmFunction =
            VmFunctionGenerator(gen, function, symbol, owner = null).generateDefaultStub()

        fun classInitializer(gen: VmModuleGenerator, symbol: VmFunctionSymbol, parts: List<IrClass>): VmFunction =
            VmFunctionGenerator(gen, null, symbol, owner = null).generateClassInitializer(parts)

        /** Unowned, as every initializer is: an owned function is one whose calls run the initializer. */
        fun fileInitializer(gen: VmModuleGenerator, symbol: VmFunctionSymbol, fields: List<IrField>): VmFunction =
            VmFunctionGenerator(gen, null, symbol, owner = null).generateFileInitializer(fields)
    }

    private fun block(): VmBlock = current ?: newBlock().also { current = it }

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
                statement.initializer?.let { initializer -> emit(VmInstruction.Copy(value, operandAs(initializer, statement.type))) }
            }
            is IrExpression -> expression(statement)
            else -> unsupported("the statement ${statement.render()}")
        }
    }

    private fun vmType(type: IrType): VmType = gen.vmType(type) ?: unsupported("a value of type ${type.render()}")

    /** The value of [expression], which must have one: a placeholder when the expression never completes. */
    private fun operand(expression: IrExpression): VmValue = expression(expression) ?: newValue(vmType(expression.type))

    /** The value of [expression] as a slot of [type] holds it, boxed or unboxed as the two differ. */
    private fun operandAs(expression: IrExpression, type: IrType, slot: VmType? = gen.vmType(type)): VmValue =
        coerce(operand(expression), expression.type, slot ?: vmType(type), type)

    /**
     * [value], of Kotlin type [from], as a slot of VM type [slot] and Kotlin type [to] holds it. A primitive in a
     * reference's slot is boxed, and a reference in a primitive's slot unboxed; a null there throws, as on the JVM.
     */
    private fun coerce(value: VmValue, from: IrType, slot: VmType, to: IrType): VmValue {
        val held = value.vmType()
        return when {
            held is VmType.Ref && slot !is VmType.Ref -> unbox(value, to)
            held !is VmType.Ref && slot is VmType.Ref -> box(value, from)
            else -> value
        }
    }

    /**
     * [value] as an expression of [type] is held. Only a reference can differ: the result of a call through a generic
     * declaration, or of `!!` on a nullable primitive, whose type is a primitive.
     */
    private fun normalize(value: VmValue, type: IrType): VmValue {
        val slot = gen.vmType(type) ?: return value
        return if (value.vmType() is VmType.Ref && slot !is VmType.Ref) unbox(value, type) else value
    }

    private fun box(value: VmValue, type: IrType): VmValue {
        val kind = type.getPrimitiveType() ?: error("Boxing a value of the non-primitive ${type.render()}")
        val valueOf = gen.valueOf(kind)
        return newValue(valueOf.returnType!!).also { emit(VmInstruction.Call(it, VmCallee.Static(valueOf), listOf(value))) }
    }

    /** A box's value: the cast fails for a box of another class, and the load for null. */
    private fun unbox(value: VmValue, type: IrType): VmValue {
        val kind = type.getPrimitiveType() ?: error("Unboxing into the non-primitive ${type.render()}")
        val boxClass = gen.boxClass(kind)
        val box = newValue(VmType.Ref(boxClass)).also { emit(VmInstruction.RefCast(it, value, boxClass)) }
        return newValue(gen.vmType(type.makeNotNull())!!).also { emit(VmInstruction.LoadField(it, box, BOX_VALUE_FIELD)) }
    }

    /** The value of [expression], or null when it has none: its type is `Unit` or `Nothing`. */
    private fun expression(expression: IrExpression): VmValue? = rawExpression(expression)?.let { normalize(it, expression.type) }

    private fun rawExpression(expression: IrExpression): VmValue? = when (expression) {
        is IrConst -> constant(expression)
        is IrGetValue -> getValue(expression)
        is IrSetValue -> {
            val variable = values[expression.symbol] ?: unsupported("a captured variable")
            emit(VmInstruction.Copy(variable, operandAs(expression.value, expression.symbol.owner.type)))
            null
        }
        is IrRichFunctionReference -> functionValue(expression)
        is IrReturn -> {
            ret(expression)
            null
        }
        is IrWhen -> whenExpression(expression)
        is IrWhileLoop -> whileLoop(expression)
        is IrDoWhileLoop -> doWhileLoop(expression)
        is IrBreak -> {
            val targets = loopTargets(expression)
            runFinallyBlocksDownTo(targets.finallyDepth)
            jumpTo(targets.breakTarget)
            null
        }
        is IrContinue -> {
            val targets = loopTargets(expression)
            runFinallyBlocksDownTo(targets.finallyDepth)
            jumpTo(targets.continueTarget)
            null
        }
        is IrThrow -> {
            terminate(VmTerminator.Throw(operand(expression.value)))
            null
        }
        is IrTry -> tryExpression(expression)
        is IrStringConcatenation -> concatenation(expression)
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
            field.correspondingPropertySymbol?.owner?.takeIf { it.isLateinit }?.let { throwIfUninitialized(dst, it) }
            dst
        }
        is IrSetField -> {
            val field = expression.symbol.owner
            val receiver = expression.receiver
            if (receiver == null) {
                emit(VmInstruction.StoreGlobal(gen.globalOf(field), operandAs(expression.value, field.type)))
            } else {
                val obj = operand(receiver)
                emit(VmInstruction.StoreField(obj, gen.fieldIndex(field), operandAs(expression.value, field.type)))
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
            val returnType = function.symbol.returnType
            val value = when {
                returnType == null -> {
                    expression(expression.value)
                    null
                }
                // A lambda returning Unit still answers a value, null; see [functionValue].
                gen.vmType(irFunction.returnType) == null -> {
                    expression(expression.value)
                    constant(returnType, 0)
                }
                else -> operandAs(expression.value, irFunction.returnType, returnType)
            }
            runFinallyBlocksDownTo(0)
            terminate(VmTerminator.Return(value))
            return
        }
        val block = returnableBlocks[target] ?: unsupported("a return from a lambda that is not inlined")
        if (block.result == null) {
            expression(expression.value)
        } else {
            emit(VmInstruction.Copy(block.result, operandAs(expression.value, block.type)))
        }
        runFinallyBlocksDownTo(block.finallyDepth)
        jumpTo(block.exit)
    }

    /**
     * Runs, innermost first, each `finally` a jump leaves: every one enclosing the code but not its target, which
     * [depth] of them enclose. Each runs as its own `try`'s surroundings do, so an exception in it is not caught by the
     * handler that runs it on an exception.
     */
    private fun runFinallyBlocksDownTo(depth: Int) {
        if (finallyFrames.size <= depth || current == null) return
        val frames = finallyFrames.toList()
        val savedProtection = protection
        val savedTryDepth = tryDepth
        for (index in frames.lastIndex downTo depth) {
            val frame = frames[index]
            while (finallyFrames.size > index) finallyFrames.removeAt(finallyFrames.lastIndex)
            protection = frame.protection
            tryDepth = frame.tryDepth
            startBlock(newBlock())
            expression(frame.body)
        }
        finallyFrames.clear()
        finallyFrames += frames
        protection = savedProtection
        tryDepth = savedTryDepth
    }

    private fun returnableBlock(expression: IrReturnableBlock): VmValue? {
        val result = gen.vmType(expression.type)?.let(::newValue)
        val exit = newBlock()
        returnableBlocks[expression.symbol] = ReturnableBlock(exit, result, expression.type, finallyFrames.size)
        val value = container(expression)
        if (result != null && value != null) {
            val last = expression.statements.last() as IrExpression
            emit(VmInstruction.Copy(result, coerce(value, last.type, result.vmType(), expression.type)))
        }
        startBlock(exit)
        return result
    }

    private fun tryExpression(expression: IrTry): VmValue? {
        val result = gen.vmType(expression.type)?.let(::newValue)
        val join = newBlock()
        val finally = expression.finallyExpression
        if (finally == null) {
            tryCatch(expression, result, join)
            current = join
            return result
        }

        // `try { try { A } catch { B } } catch (t: Throwable) { F; throw t }`, with `F` on every way out besides.
        val outerProtection = protection
        val outerDepth = tryDepth
        val normalExit = newBlock()
        val rethrow = newBlock()
        val exception = newValue(VmType.Ref(gen.throwableClass))
        tryDepth++
        val catchAll = addHandler(gen.throwableClass, exception, rethrow, next = outerProtection)
        protection = catchAll
        finallyFrames += FinallyFrame(finally, outerProtection, outerDepth)
        tryCatch(expression, result, normalExit)
        finallyFrames.removeAt(finallyFrames.lastIndex)
        protection = outerProtection
        tryDepth = outerDepth

        current = normalExit
        expression(finally)
        jumpTo(join)
        current = rethrow
        expression(finally)
        terminate(VmTerminator.Throw(exception))
        current = join
        return result
    }

    /** The `try` body and its `catch` clauses, each leaving its value in [result] and jumping to [exit]. */
    private fun tryCatch(expression: IrTry, result: VmValue?, exit: VmBlock) {
        val outer = protection
        tryDepth++
        val clauses = expression.catches.map { catch ->
            val catchClass = gen.testedClass(catch.catchParameter.type) ?: gen.throwableClass
            val exception = newValue(VmType.Ref(catchClass))
            values[catch.catchParameter.symbol] = exception
            val block = newBlock()
            Clause(catch, addHandler(catchClass, exception, block, next = outer))
        }
        // Clauses are tried in order, then whatever encloses the `try`.
        clauses.zipWithNext { clause, next -> function.handlers[clause.handler].next = next.handler }
        if (clauses.isNotEmpty()) protection = clauses.first().handler
        startBlock(newBlock())
        branchResult(expression.tryResult, result, expression.type)
        jumpTo(exit)
        protection = outer
        tryDepth--

        for (clause in clauses) {
            current = function.blocks[function.handlers[clause.handler].block]
            branchResult(clause.catch.result, result, expression.type)
            jumpTo(exit)
        }
    }

    private class Clause(val catch: IrCatch, val handler: Int)

    private fun addHandler(catchClass: Int, exception: VmValue, block: VmBlock, next: Int?): Int {
        function.handlers += VmHandler(catchClass, block.index, exception, next)
        handlerDepths += tryDepth
        return function.handlers.lastIndex
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
            IrConstKind.Float -> floatBits(expression.value as Float)
            IrConstKind.String -> unsupported("the constant ${expression.render()}")
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
        val join = newBlock()
        for (branch in expression.branches) {
            val body = newBlock()
            // A branch on a constant `true` is an `else` too, and no branch after it runs.
            if (branch is IrElseBranch || (branch.condition as? IrConst)?.value == true) {
                startBlock(body)
            } else {
                val next = newBlock()
                terminate(VmTerminator.Branch(operand(branch.condition), body.index, next.index))
                current = body
                branchResult(branch.result, result, expression.type)
                jumpTo(join)
                current = next
                continue
            }
            branchResult(branch.result, result, expression.type)
            jumpTo(join)
            break
        }
        startBlock(join)
        return result
    }

    /** Generates [expression], leaving its value in [result], which holds values of [type]. */
    private fun branchResult(expression: IrExpression, result: VmValue?, type: IrType) {
        if (result == null) {
            expression(expression)
        } else {
            expression(expression)?.let { emit(VmInstruction.Copy(result, coerce(it, expression.type, result.vmType(), type))) }
        }
    }

    private fun whileLoop(loop: IrWhileLoop): VmValue? {
        val header = newBlock()
        val body = newBlock()
        val exit = newBlock()
        loops[loop] = LoopTargets(continueTarget = header, breakTarget = exit, finallyDepth = finallyFrames.size)
        startBlock(header)
        terminate(VmTerminator.Branch(operand(loop.condition), body.index, exit.index))
        current = body
        loop.body?.let(::expression)
        jumpTo(header)
        current = exit
        return null
    }

    private fun doWhileLoop(loop: IrDoWhileLoop): VmValue? {
        val body = newBlock()
        val condition = newBlock()
        val exit = newBlock()
        loops[loop] = LoopTargets(continueTarget = condition, breakTarget = exit, finallyDepth = finallyFrames.size)
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
        IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.IMPLICIT_NOTNULL -> operandAs(expression.argument, expression.typeOperand)
        IrTypeOperator.INSTANCEOF -> instanceOf(reference(expression.argument), expression.typeOperand)
        IrTypeOperator.NOT_INSTANCEOF -> not(instanceOf(reference(expression.argument), expression.typeOperand))
        IrTypeOperator.CAST -> cast(reference(expression.argument), expression.typeOperand)
        IrTypeOperator.SAFE_CAST -> safeCast(reference(expression.argument), expression.typeOperand)
        else -> unsupported("the type operator ${expression.operator}")
    }

    /** The value of [expression] as a reference, boxed if it is a primitive: what a type test or cast reads. */
    private fun reference(expression: IrExpression): VmValue = operandAs(expression, gen.irBuiltIns.anyNType, gen.anyType)

    /** Whether [value] is an instance of [type]: null is one only of a nullable type. */
    private fun instanceOf(value: VmValue, type: IrType): VmValue {
        val tested = gen.testedClass(type)
        val isInstance = if (tested == null) {
            not(isNull(value))
        } else {
            newValue(VmType.I64).also { emit(VmInstruction.RefTest(it, value, tested)) }
        }
        return if (type.isMarkedNullable()) binary(VmBinaryOp.OR, isInstance, isNull(value)) else isInstance
    }

    /** `as`: the VM's cast lets null through, so a cast to a non-null type checks for it first. */
    private fun cast(value: VmValue, type: IrType): VmValue {
        val tested = gen.testedClass(type)
        if (!type.isMarkedNullable()) throwIfNull(value)
        if (tested == null) return value
        return newValue(VmType.Ref(tested)).also { emit(VmInstruction.RefCast(it, value, tested)) }
    }

    /**
     * A string template, or `+` on strings: each piece's `toString` in turn. A constant piece is formatted here; a number
     * computed at run time needs formatting the runtime does not have yet.
     */
    private fun concatenation(expression: IrStringConcatenation): VmValue {
        // Only constants: one literal, which is interned, as the JVM's compilers fold such a concatenation.
        if (expression.arguments.all { it is IrConst }) {
            val text = expression.arguments.joinToString("") { (it as IrConst).value.toString() }
            return newValue(gen.stringType).also { emit(VmInstruction.StringConstant(it, text)) }
        }
        var result: VmValue? = null
        for (argument in expression.arguments) {
            if (argument is IrConst && argument.kind != IrConstKind.Null) {
                val text = newValue(gen.stringType)
                emit(VmInstruction.StringConstant(text, argument.value.toString()))
                result = result?.let { plus(it, text) } ?: text
                continue
            }
            val start = result ?: newValue(gen.stringType).also { emit(VmInstruction.StringConstant(it, "")) }
            result = plus(start, reference(argument))
        }
        return result ?: newValue(gen.stringType).also { emit(VmInstruction.StringConstant(it, "")) }
    }

    private fun plus(string: VmValue, other: VmValue): VmValue =
        newValue(gen.stringType).also { emit(VmInstruction.Call(it, VmCallee.Static(gen.stringPlus), listOf(string, other))) }

    /** `String.plus(Any?)`: [string] then [argument]'s `toString`. */
    private fun plus(string: VmValue, argument: IrExpression): VmValue {
        return plus(string, reference(argument))
    }

    /** Throws a new instance of the runtime class [className], with [message]. */
    private fun throwNew(className: String, message: VmValue?) {
        val classIndex = gen.runtimeClassNamed(className)
        val exception = newValue(VmType.Ref(classIndex)).also { emit(VmInstruction.NewObject(it, classIndex)) }
        initThrowable(exception, message, cause = null)
        terminate(VmTerminator.Throw(exception))
    }

    private fun initThrowable(exception: VmValue, message: VmValue?, cause: VmValue?) {
        val args = listOf(exception, message ?: constant(gen.stringType, 0), cause ?: constant(gen.anyType, 0))
        emit(VmInstruction.Call(null, VmCallee.Static(gen.throwableInit), args))
    }

    /**
     * Runs one of the JVM's `Throwable` constructors, which take a message, a cause or both, on [exception]. The one
     * taking only a cause makes the message the cause's `toString`, or null for a null cause. `AssertionError`'s taking
     * an `Object` makes the message its `String.valueOf`, and the object the cause too when it is a throwable.
     */
    private fun constructThrowable(exception: VmValue, constructor: IrConstructor, args: List<VmValue>) {
        var message: VmValue? = null
        var cause: VmValue? = null
        constructor.parameters.forEachIndexed { index, parameter ->
            when {
                parameter.type.isString() || parameter.type.isNullableString() -> message = args[index]
                gen.testedClass(parameter.type) == gen.throwableClass -> cause = args[index]
                parameter.type.isAny() || parameter.type.isNullableAny() -> {
                    val detail = args[index]
                    message = plus(newValue(gen.stringType).also { emit(VmInstruction.StringConstant(it, "")) }, detail)
                    val throwable = newValue(gen.anyType).also { emit(VmInstruction.Constant(it, 0)) }
                    val isThrowable = newBlock()
                    val join = newBlock()
                    terminate(VmTerminator.Branch(instanceOf(detail, gen.irBuiltIns.throwableType), isThrowable.index, join.index))
                    current = isThrowable
                    emit(VmInstruction.Copy(throwable, detail))
                    startBlock(join)
                    cause = throwable
                }
                else -> unsupported("the constructor ${constructor.render()}")
            }
        }
        val causeOnly = cause
        if (message == null && causeOnly != null) {
            val described = newValue(gen.stringType)
            val present = newBlock()
            val join = newBlock()
            emit(VmInstruction.Constant(described, 0))
            terminate(VmTerminator.Branch(isNull(causeOnly), join.index, present.index))
            current = present
            emit(VmInstruction.Call(described, VmCallee.Virtual(gen.toStringSelector), listOf(causeOnly)))
            startBlock(join)
            message = described
        }
        initThrowable(exception, message, cause)
    }

    /** A `lateinit` property holds null until it is assigned, and reading it before then throws. */
    private fun throwIfUninitialized(value: VmValue, property: IrProperty) {
        val fail = newBlock()
        val next = newBlock()
        terminate(VmTerminator.Branch(isNull(value), fail.index, next.index))
        current = fail
        val message = newValue(gen.stringType)
        emit(VmInstruction.StringConstant(message, "lateinit property ${property.name.asString()} has not been initialized"))
        throwNew("kotlin.UninitializedPropertyAccessException", message)
        current = next
    }

    private fun throwIfNull(value: VmValue) {
        val fail = newBlock()
        val next = newBlock()
        terminate(VmTerminator.Branch(isNull(value), fail.index, next.index))
        current = fail
        val exception = newValue(VmType.Ref(gen.nullPointerClass)).also { emit(VmInstruction.NewObject(it, gen.nullPointerClass)) }
        terminate(VmTerminator.Throw(exception))
        current = next
    }

    /** `as?`: the value when it is an instance of the non-null [type], null otherwise. */
    private fun safeCast(value: VmValue, type: IrType): VmValue {
        val result = newValue(gen.anyType)
        val instance = newBlock()
        val other = newBlock()
        val join = newBlock()
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
        functionValueCall(call, callee)?.let { return it.value }
        sharedBoxAccess(call, callee)?.let { return it.value }
        if (call.arguments.any { it == null }) {
            if (call.superQualifierSymbol != null) unsupported("a super call leaving out a default argument")
            val stub = gen.defaultStub(defaultsDeclaration(callee))
            val dst = stub.returnType?.let(::newValue)
            emit(VmInstruction.Call(dst, VmCallee.Static(stub), defaultArguments(call, defaultsDeclaration(callee), stub, leading = 0)))
            return dst
        }
        val target = gen.callTarget(callee, isSuperCall = call.superQualifierSymbol != null)
        val dst = target.symbol.returnType?.let(::newValue)
        emit(VmInstruction.Call(dst, target.callee, arguments(call, callee, target.symbol.paramTypes)))
        return dst
    }

    /**
     * A lambda or a function reference as a function value: its `invoke` becomes a function of its own, taking the
     * variables it captures, then the values bound at creation, which the value holds, then the arguments, which a
     * call passes. As on the JVM, whose `invoke` is erased, the arguments and the result are references, so a generic
     * caller and one knowing the types pass the same; a lambda returning Unit answers null.
     */
    private fun functionValue(reference: IrRichFunctionReference): VmValue {
        val invoke = reference.invokeFunction
        if (invoke.isSuspend) unsupported("a suspend lambda")
        val captured = freeVariables(invoke)
        val captures = captured.map { values[it] ?: unsupported("capturing ${it.owner.render()}") }
        val boundCount = reference.boundValues.size
        val bound = reference.boundValues.mapIndexed { index, value -> operandAs(value, invoke.parameters[index].type) }
        val paramTypes = captures.map { it.vmType() } + invoke.parameters.mapIndexed { index, parameter ->
            if (index < boundCount) vmType(parameter.type) else gen.anyType
        }
        val symbol = VmFunctionSymbol(gen.uniqueFunctionName("${function.symbol.name}\$lambda"), paramTypes, gen.anyType)
        gen.addFunction(VmFunctionGenerator(gen, invoke, symbol, owner = null).generateLambda(captured))
        return newValue(gen.anyType).also { emit(VmInstruction.RefFunc(it, symbol, captures + bound)) }
    }

    /**
     * A captured variable's box, which `SharedVariablesLowering` makes of the standard library's
     * `kotlin.internal.SharedVariableBox` and the runtime implements: the box holds the variable as a reference.
     */
    private fun sharedBoxAccess(call: IrCall, callee: IrSimpleFunction): Intrinsic? {
        if (callee.parentClassOrNull?.let(gen::isSharedVariableBox) != true) return null
        val box = operand(call.arguments[0]!!)
        return when (callee.name.asString()) {
            "<get-element>" -> Intrinsic(newValue(gen.anyType).also { emit(VmInstruction.LoadField(it, box, SHARED_BOX_ELEMENT_FIELD)) })
            "<set-element>" -> {
                emit(VmInstruction.StoreField(box, SHARED_BOX_ELEMENT_FIELD, reference(call.arguments[1]!!)))
                Intrinsic(null)
            }
            else -> unsupported("${callee.render()}")
        }
    }

    /**
     * `===`: the values' equality for two primitives, and the references' otherwise, a primitive boxed as the JVM boxes
     * it, so a value `valueOf` caches is the same box as another of it.
     */
    private fun identityEquals(lhs: IrExpression, rhs: IrExpression): VmValue {
        val primitives = gen.vmType(lhs.type) !is VmType.Ref && gen.vmType(rhs.type) !is VmType.Ref
        return if (primitives) binary(VmBinaryOp.EQ, operand(lhs), operand(rhs)) else binary(VmBinaryOp.EQ, reference(lhs), reference(rhs))
    }

    /** `invoke` on a function type, which calls through the value; see [functionValue] for what crosses the call. */
    private fun functionValueCall(call: IrCall, callee: IrSimpleFunction): Intrinsic? {
        val receiver = call.dispatchReceiver ?: return null
        if (callee.name != OperatorNameConventions.INVOKE || !receiver.type.isFunctionOrKFunction()) return null
        val args = call.arguments.map { reference(it ?: unsupported("a default argument")) }
        val result = newValue(gen.anyType)
        emit(VmInstruction.Call(result, VmCallee.Value, args))
        return Intrinsic(result.takeIf { gen.vmType(call.type) != null })
    }

    /** The values [function] uses that something enclosing it declares: what a lambda of it captures. */
    private fun freeVariables(function: IrFunction): List<IrValueSymbol> {
        val declared = hashSetOf<IrValueSymbol>()
        val used = LinkedHashSet<IrValueSymbol>()
        function.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitValueParameter(declaration: IrValueParameter) {
                declared += declaration.symbol
                declaration.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                declared += declaration.symbol
                declaration.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                used += expression.symbol
            }

            override fun visitSetValue(expression: IrSetValue) {
                used += expression.symbol
                expression.acceptChildrenVoid(this)
            }
        })
        return used.filter { it !in declared }
    }

    /** Whether a field's constant initializer is what the field holds before it runs: zero, false or null. */
    private fun IrConst.isDefaultValue(): Boolean = when (kind) {
        IrConstKind.Null -> true
        IrConstKind.Boolean -> value == false
        IrConstKind.Char -> value == 0.toChar()
        IrConstKind.Byte, IrConstKind.Short, IrConstKind.Int, IrConstKind.Long -> (value as Number).toLong() == 0L
        IrConstKind.Double -> (value as Double).toRawBits() == 0L
        IrConstKind.Float -> (value as Float).toRawBits() == 0
        IrConstKind.String -> false
    }

    /**
     * The declaration of [callee] or of what it overrides that declares its default arguments: the first found following
     * each supertype in the order the class lists them, all the way up, as the JVM finds them.
     */
    private fun defaultsDeclaration(callee: IrFunction): IrFunction {
        fun find(function: IrFunction): IrFunction? = when {
            function.parameters.any { it.defaultValue != null } -> function
            function is IrSimpleFunction -> function.overriddenSymbols.firstNotNullOfOrNull { find(it.owner) }
            else -> null
        }
        return find(callee) ?: error("No declaration of ${callee.render()} declares its defaults")
    }

    /**
     * What a call leaving arguments out passes the [stub] of [declaration]'s defaults, after the [leading] values a
     * constructor's takes: the arguments given as its parameters take them, zero for each left out, and the mask
     * naming those.
     */
    private fun defaultArguments(call: IrFunctionAccessExpression, declaration: IrFunction, stub: VmFunctionSymbol, leading: Int): List<VmValue> {
        var mask = 0L
        val args = call.arguments.mapIndexed { index, argument ->
            val slot = stub.paramTypes[leading + index]
            if (argument == null) {
                // A vararg left out has no default, but an empty array.
                if (declaration.parameters[index].defaultValue == null) unsupported("an empty vararg")
                mask = mask or (1L shl index)
                constant(slot, 0)
            } else {
                operandAs(argument, declaration.parameters[index].type, slot)
            }
        }
        return args + constant(VmType.I64, mask)
    }

    /**
     * The function a constructor call runs on the object it creates, and the arguments after the object, evaluated now,
     * ahead of the object: the constructor, or its defaults' stub when the call leaves arguments out.
     */
    private fun constructorInvocation(call: IrFunctionAccessExpression, constructor: IrConstructor): Pair<VmFunctionSymbol, List<VmValue>> {
        if (call.arguments.any { it == null }) {
            val stub = gen.defaultStub(constructor)
            return stub to defaultArguments(call, constructor, stub, leading = 1)
        }
        return gen.staticTarget(constructor).symbol to constructorArguments(call, constructor)
    }

    /** A call's arguments as its callee's signature takes them, boxed or unboxed where the two differ. */
    private fun arguments(call: IrFunctionAccessExpression, callee: IrFunction, slots: List<VmType>): List<VmValue> =
        call.arguments.mapIndexed { index, argument ->
            operandAs(argument ?: unsupported("a default argument"), callee.parameters[index].type, slots[index])
        }

    /** Allocates the object, then runs the constructor on it with the arguments already evaluated. */
    private fun constructorCall(call: IrConstructorCall): VmValue {
        val constructor = call.symbol.owner
        if (gen.isSharedVariableBox(constructor.constructedClass)) {
            val boxClass = gen.sharedVariableBoxClass
            val box = newValue(VmType.Ref(boxClass)).also { emit(VmInstruction.NewObject(it, boxClass)) }
            emit(VmInstruction.StoreField(box, SHARED_BOX_ELEMENT_FIELD, reference(call.arguments[0]!!)))
            return box
        }
        gen.runtimeThrowable(constructor.constructedClass)?.let { throwable ->
            val args = constructorArguments(call, constructor)
            val exception = newValue(VmType.Ref(throwable)).also { emit(VmInstruction.NewObject(it, throwable)) }
            constructThrowable(exception, constructor, args)
            return exception
        }
        val [target, args] = constructorInvocation(call, constructor)
        val classIndex = gen.classIndex(constructor.constructedClass) ?: unsupported("constructing ${constructor.constructedClass.name}")
        val obj = newValue(VmType.Ref(classIndex)).also { emit(VmInstruction.NewObject(it, classIndex)) }
        emit(VmInstruction.Call(null, VmCallee.Static(target), listOf(obj) + args))
        return obj
    }

    /** `super(...)` or `this(...)`: another constructor run on the object this one initializes. */
    private fun delegatingConstructorCall(call: IrFunctionAccessExpression) {
        val constructor = call.symbol.owner as IrConstructor
        // `Any`'s constructor does nothing, and `Enum`'s name and ordinal are stored by whatever creates the entry, so
        // the runtime declares neither.
        if (with(gen) { constructor.constructedClass.isAny() || constructor.constructedClass.isEnumBase() }) return
        if (gen.runtimeThrowable(constructor.constructedClass) != null) {
            constructThrowable(constructed!!, constructor, constructorArguments(call, constructor))
            return
        }
        val [target, args] = constructorInvocation(call, constructor)
        emit(VmInstruction.Call(null, VmCallee.Static(target), listOf(constructed!!) + args))
    }

    /** A constructor's arguments as its parameters hold them; a throwable's runtime constructor takes references. */
    private fun constructorArguments(call: IrFunctionAccessExpression, constructor: IrConstructor): List<VmValue> =
        call.arguments.mapIndexed { index, argument ->
            operandAs(argument ?: unsupported("a default argument"), constructor.parameters[index].type)
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
        // As on the JVM, which leaves such a field to its default: an initializer running earlier may have set it.
        if ((initializer.expression as? IrConst)?.isDefaultValue() == true) return
        emit(VmInstruction.StoreField(self, gen.fieldIndex(field), operandAs(initializer.expression, field.type)))
    }

    /** What an intrinsic produced: a value, or none for one returning `Unit`. */
    private class Intrinsic(val value: VmValue?)

    /** Generates [call] when its callee is an operator of the built-ins, or null when it is an ordinary call. */
    private fun intrinsic(call: IrCall, callee: IrSimpleFunction): Intrinsic? {
        val builtIns = gen.irBuiltIns
        val symbol = call.symbol
        // No intrinsic declares a default, so a call leaving an argument out is an ordinary one.
        val args = call.arguments.map { it ?: return null }
        val value = when (symbol) {
            builtIns.eqeqeqSymbol -> identityEquals(args[0], args[1])
            builtIns.eqeqSymbol -> equals(args[0], args[1])
            builtIns.booleanNotSymbol -> not(operand(args[0]))
            // `!!`.
            builtIns.checkNotNullSymbol -> operand(args[0]).also(::throwIfNull)
            // An exhaustive `when` that matched nothing: only a class changed since compilation gets here.
            builtIns.noWhenBranchMatchedExceptionSymbol -> {
                throwNew("kotlin.NoWhenBranchMatchedException", message = null)
                return Intrinsic(null)
            }
            in builtIns.ieee754equalsFunByOperandType.values -> compare(Comparison.EQ, callee, args)
            in builtIns.lessFunByOperandType.values -> compare(Comparison.LT, callee, args)
            in builtIns.lessOrEqualFunByOperandType.values -> compare(Comparison.LE, callee, args)
            in builtIns.greaterFunByOperandType.values -> compare(Comparison.GT, callee, args)
            in builtIns.greaterOrEqualFunByOperandType.values -> compare(Comparison.GE, callee, args)
            else -> {
                enumMember(callee, args)?.let { return Intrinsic(it) }
                when (callee.fqNameWhenAvailable?.asString()) {
                    // What the frontend calls when an exhaustive `when` matches nothing; the message is the JVM's.
                    "kotlin.internal.throwNoWhenBranchMatchedException" -> {
                        val prefix = newValue(gen.stringType)
                        emit(VmInstruction.StringConstant(prefix, "No branch matched for subject: "))
                        throwNew("kotlin.NoWhenBranchMatchedException", plus(prefix, args[0]))
                        return Intrinsic(null)
                    }
                    "kotlin.String.plus" -> return Intrinsic(plus(operand(args[0]), args[1]))
                }
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

    /**
     * A comparison built-in, its operands taken as its parameters are typed: the primitive, or its box where the
     * parameter is nullable, which only `ieee754equals` has.
     */
    private fun compare(comparison: Comparison, callee: IrSimpleFunction, args: List<IrExpression>): VmValue {
        val lhsType = callee.parameters[0].type
        val type = lhsType.getPrimitiveType() ?: unsupported("comparing ${lhsType.render()}")
        if (lhsType.isMarkedNullable() || callee.parameters[1].type.isMarkedNullable()) {
            if (comparison != Comparison.EQ) unsupported("ordering nullable ${lhsType.render()}")
            return nullableIeeeEquals(operandAs(args[0], lhsType), operandAs(args[1], callee.parameters[1].type), lhsType)
        }
        val lhs = operandAs(args[0], lhsType)
        val rhs = operandAs(args[1], callee.parameters[1].type)
        if (type.isFloating()) {
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

    /**
     * IEEE 754 equality of two possibly null boxes of [type]: both null are equal, one null is unequal, and two numbers
     * compare as numbers, so `0.0 == -0.0` and NaN equals nothing.
     */
    private fun nullableIeeeEquals(lhs: VmValue, rhs: VmValue, type: IrType): VmValue {
        val result = newValue(VmType.I64)
        val lhsNull = isNull(lhs)
        val rhsNull = isNull(rhs)
        val someNull = newBlock()
        val numbers = newBlock()
        val join = newBlock()
        terminate(VmTerminator.Branch(binary(VmBinaryOp.OR, lhsNull, rhsNull), someNull.index, numbers.index))
        current = someNull
        emit(VmInstruction.Copy(result, binary(VmBinaryOp.AND, lhsNull, rhsNull)))
        jumpTo(join)
        current = numbers
        val primitive = type.makeNotNull()
        emit(VmInstruction.Copy(result, binary(VmBinaryOp.EQ, normalize(lhs, primitive), normalize(rhs, primitive))))
        startBlock(join)
        return result
    }

    private fun primitive(type: IrType): PrimitiveType {
        if (!type.isPrimitiveType()) unsupported("an operand of type ${type.render()}")
        return type.getPrimitiveType()!!
    }

    /** `==`: the values' equality for primitives, and `equals` behind a null check for references. */
    private fun equals(lhsExpression: IrExpression, rhsExpression: IrExpression): VmValue {
        val lhsType = lhsExpression.type
        val rhsType = rhsExpression.type
        // Two doubles, statically, are equal as IEEE 754 says, boxed or not; a box of `Any` type answers `equals`.
        if (lhsType.isPrimitiveType() && rhsType.isPrimitiveType()) {
            return binary(VmBinaryOp.EQ, operand(lhsExpression), operand(rhsExpression))
        }
        if (lhsType.getPrimitiveType() == PrimitiveType.DOUBLE && rhsType.getPrimitiveType() == PrimitiveType.DOUBLE) {
            return nullableIeeeEquals(reference(lhsExpression), reference(rhsExpression), lhsType)
        }
        if (rhsExpression.isNullConstant()) return isNull(reference(lhsExpression))
        if (lhsExpression.isNullConstant()) return isNull(reference(rhsExpression))

        val lhs = reference(lhsExpression)
        val rhs = reference(rhsExpression)
        val result = newValue(VmType.I64)
        val lhsNull = newBlock()
        val call = newBlock()
        val join = newBlock()
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
        // Operands as the member's parameters type them: a smart-cast receiver can still be statically nullable, and
        // so held boxed.
        val receiver = operandAs(args[0], callee.parameters[0].type)
        val resultType = callee.returnType.takeIf { it.isPrimitiveType() }?.getPrimitiveType()
        fun argument() = operandAs(args[1], callee.parameters[1].type)
        fun resultType() = resultType ?: unsupported("${receiverType.typeName}.$name")

        return when (name) {
            "plus" -> arithmetic(VmBinaryOp.ADD, receiver, receiverType, args[1], callee.parameters[1].type, resultType())
            "minus" -> arithmetic(VmBinaryOp.SUB, receiver, receiverType, args[1], callee.parameters[1].type, resultType())
            "times" -> arithmetic(VmBinaryOp.MUL, receiver, receiverType, args[1], callee.parameters[1].type, resultType())
            "div" -> arithmetic(VmBinaryOp.DIV_S, receiver, receiverType, args[1], callee.parameters[1].type, resultType())
            "rem" -> arithmetic(VmBinaryOp.REM_S, receiver, receiverType, args[1], callee.parameters[1].type, resultType())
            "unaryPlus" -> receiver
            "unaryMinus" -> {
                val type = resultType()
                if (type.isFloating()) {
                    negateFloating(receiver, type)
                } else {
                    val value = widen(receiver, receiverType, type)
                    narrow(newValue(value.vmType()).also { emit(VmInstruction.Unary(VmUnaryOp.NEG, it, value)) }, type)
                }
            }
            "inc" -> narrow(binary(VmBinaryOp.ADD, receiver, one(receiverType), receiver.vmType()), receiverType)
            "dec" -> narrow(binary(VmBinaryOp.SUB, receiver, one(receiverType), receiver.vmType()), receiverType)
            "compareTo" -> {
                if (receiverType.isFloating() || primitive(callee.parameters[1].type).isFloating()) unsupported("compareTo on floating-point numbers")
                val other = argument()
                binary(VmBinaryOp.SUB, binary(VmBinaryOp.GT_S, receiver, other), binary(VmBinaryOp.LT_S, receiver, other))
            }
            "and" -> binary(VmBinaryOp.AND, receiver, argument())
            "or" -> binary(VmBinaryOp.OR, receiver, argument())
            "xor" -> binary(VmBinaryOp.XOR, receiver, argument())
            "inv" -> newValue(VmType.I64).also { emit(VmInstruction.Unary(VmUnaryOp.NOT, it, receiver)) }
            "not" -> not(receiver)
            "shl", "shr", "ushr" -> shift(name, receiver, receiverType, argument())
            "toByte", "toShort", "toInt", "toLong", "toChar", "toFloat", "toDouble" -> convert(receiver, receiverType, resultType())
            else -> unsupported("${receiverType.typeName}.$name")
        }
    }

    /** The VM negates integers only, so a floating value's sign bit is flipped, which is exact for zeros and NaN too. */
    private fun negateFloating(value: VmValue, type: PrimitiveType): VmValue {
        val bits = newValue(VmType.I64).also { emit(VmInstruction.Copy(it, value)) }
        val sign = if (type == PrimitiveType.FLOAT) 0x8000_0000L else Long.MIN_VALUE
        val flipped = binary(VmBinaryOp.XOR, bits, constant(VmType.I64, sign))
        return newValue(gen.primitiveVmType(type)).also { emit(VmInstruction.Copy(it, flipped)) }
    }

    private fun PrimitiveType.isFloating() = this == PrimitiveType.DOUBLE || this == PrimitiveType.FLOAT

    private fun VmValue.vmType(): VmType = function.valueTypes[index]

    private fun one(type: PrimitiveType): VmValue = when (type) {
        PrimitiveType.DOUBLE -> constant(VmType.F64, 1.0.toRawBits())
        PrimitiveType.FLOAT -> constant(VmType.F32, floatBits(1.0f))
        else -> constant(VmType.I64, 1)
    }

    /**
     * A value of kind [from] as one of [to], which it widens to: an integral value as a floating one, and a `Float` as
     * a `Double`. The integral kinds share one representation.
     */
    private fun widen(value: VmValue, from: PrimitiveType, to: PrimitiveType): VmValue {
        if (!to.isFloating() || from == to) return value
        return newValue(gen.primitiveVmType(to)).also { emit(VmInstruction.Convert(it, value)) }
    }

    private fun arithmetic(
        op: VmBinaryOp,
        receiver: VmValue,
        receiverType: PrimitiveType,
        argument: IrExpression,
        parameterType: IrType,
        resultType: PrimitiveType,
    ): VmValue {
        val argumentType = primitive(parameterType)
        val lhs = widen(receiver, receiverType, resultType)
        val rhs = widen(operandAs(argument, parameterType), argumentType, resultType)
        if (resultType.isFloating()) {
            if (op == VmBinaryOp.REM_S && resultType == PrimitiveType.DOUBLE) unsupported("`rem` on doubles")
            return binary(op, lhs, rhs, gen.primitiveVmType(resultType))
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
        if (!from.isFloating() && !to.isFloating()) return narrow(value, to)
        // Between floating kinds, and from an integral one, the VM rounds once, to the nearest.
        if (to.isFloating()) return if (from == to) value else newValue(gen.primitiveVmType(to)).also { emit(VmInstruction.Convert(it, value)) }
        // The VM's conversion saturates at a Long's bounds and takes NaN to zero, as the JVM's `d2l` and `f2l` do; the
        // JVM's `d2i` and `f2i` saturate at an Int's, and a narrower kind is that Int narrowed.
        val long = newValue(VmType.I64).also { emit(VmInstruction.Convert(it, value)) }
        if (to == PrimitiveType.LONG) return long
        return narrow(clampToInt(long), to)
    }

    /** [value], a Long, held to an Int's range. */
    private fun clampToInt(value: VmValue): VmValue {
        val result = newValue(VmType.I64).also { emit(VmInstruction.Copy(it, value)) }
        for ([bound, below] in listOf(Int.MIN_VALUE.toLong() to true, Int.MAX_VALUE.toLong() to false)) {
            val limit = constant(VmType.I64, bound)
            val beyond = newBlock()
            val within = newBlock()
            val op = if (below) VmBinaryOp.LT_S else VmBinaryOp.GT_S
            terminate(VmTerminator.Branch(binary(op, result, limit), beyond.index, within.index))
            current = beyond
            emit(VmInstruction.Copy(result, limit))
            startBlock(within)
        }
        return result
    }
}
