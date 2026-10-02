/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.AnnotationImplementationMemberGenerator
import org.jetbrains.kotlin.backend.common.lower.AnnotationImplementationTransformer
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isEquals
import org.jetbrains.kotlin.ir.util.isHashCode
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.isToString
import org.jetbrains.kotlin.ir.util.isUnsignedArray
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * An annotation class gets the `equals`, `hashCode` and `toString` of an annotation, and stays the class its instances
 * are of: `A(1)` is a call of its own constructor, as in the JS and Wasm backends, which this follows.
 */
internal class VmAnnotationImplementationLowering(private val context: VmLoweringContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(VmAnnotationImplementationTransformer(context))
    }
}

private class VmAnnotationImplementationTransformer(context: VmLoweringContext) :
    // The member generator only enters and leaves scopes of the symbol table, which can be empty.
    AnnotationImplementationTransformer(context, SymbolTable(null, context.irFactory), null) {
    private val arraysContentEquals = context.symbols.arraysContentEquals

    override fun visitConstructorCall(expression: IrConstructorCall): IrExpression = expression

    override fun chooseConstructor(implClass: IrClass, expression: IrConstructorCall): IrConstructor =
        error("An annotation instance is made by the annotation class's own constructor")

    override fun implementAnnotationPropertiesAndConstructor(implClass: IrClass, annotationClass: IrClass, generatedConstructor: IrConstructor) =
        error("An annotation class has no separate implementation class")

    override fun visitClassNew(declaration: IrClass): IrStatement {
        if (declaration.isAnnotationClass) implementMembers(declaration)
        return super.visitClassNew(declaration)
    }

    private fun implementMembers(annotationClass: IrClass) {
        val generator = AnnotationImplementationMemberGenerator(
            context, symbolTable, annotationClass,
            nameForToString = "@" + annotationClass.fqNameWhenAvailable!!.asString(),
            forbidDirectFieldAccess = forbidDirectFieldAccessInMethods,
        ) { type, a, b -> generatedEquals(this, type, a, b) }

        val equals = annotationClass.functions.single { it.isEquals() }
        val hashCode = annotationClass.functions.single { it.isHashCode() }
        val toString = annotationClass.functions.single { it.isToString() }
        for (function in listOf(equals, hashCode, toString)) {
            function.isFakeOverride = false
            function.dispatchReceiverParameter?.type = annotationClass.defaultType
        }
        generateFunctionBodies(annotationClass, annotationClass, equals, hashCode, toString, generator)
    }

    override fun getArrayContentEqualsSymbol(type: IrType): IrFunctionSymbol =
        when {
            type.isPrimitiveArray() || type.isUnsignedArray() -> arraysContentEquals[type]
            else -> arraysContentEquals.entries.singleOrNull { [key, _] -> key.isArray() }?.value
        } ?: error("The standard library declares no contentEquals for ${type.render()}")
}
