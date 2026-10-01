/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.isAnonymousObject
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * A reference to an enum entry from the body of the class the entry has becomes the `this` of that class, where one is in
 * scope: the entry is stored to its global once its constructor has run, so the global is not set yet while the body
 * initializes. A reference in the arguments of the entry's own constructor call stays a global read, as `this` does not
 * exist before the superclass constructor has run.
 */
internal class VmEnumEntryReferencesLowering : FileLoweringPass, IrElementTransformerVoidWithContext() {
    private val constructingEnums = arrayListOf<IrDeclarationParent>()

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitEnumConstructorCall(expression: IrEnumConstructorCall): IrExpression {
        constructingEnums.push(expression.symbol.owner.parent)
        val call = super.visitEnumConstructorCall(expression)
        constructingEnums.pop()
        return call
    }

    override fun visitGetEnumValue(expression: IrGetEnumValue): IrExpression {
        val entryClass = expression.symbol.owner.correspondingClass ?: return expression
        if (entryClass.parent == constructingEnums.lastOrNull()) return expression
        if (!isThisAccessible(entryClass)) return expression
        val receiver = thisOfClass(entryClass) ?: return expression
        return IrGetValueImpl(expression.startOffset, expression.endOffset, expression.type, receiver.symbol)
    }

    /** Whether the scopes between the reference and [entryClass] are all ones that can reach an instance of it. */
    private fun isThisAccessible(entryClass: IrClass): Boolean {
        for (scope in allScopes.asReversed()) {
            when (val element = scope.irElement) {
                entryClass -> return true
                is IrClass -> if (!element.isInner && !element.isAnonymousObject) return false
                is IrField -> if (element.isStatic) return false
                is IrFunction -> if (element.dispatchReceiverParameter == null && element.visibility != DescriptorVisibilities.LOCAL) return false
                else -> {}
            }
        }
        return false
    }

    private fun thisOfClass(entryClass: IrClass): IrValueParameter? {
        for (scope in allScopes.asReversed()) {
            when (val element = scope.irElement) {
                is IrFunction -> element.dispatchReceiverParameter?.let { if (it.type.classOrNull == entryClass.symbol) return it }
                is IrClass -> if (element == entryClass) return element.thisReceiver
                else -> {}
            }
        }
        return null
    }
}
