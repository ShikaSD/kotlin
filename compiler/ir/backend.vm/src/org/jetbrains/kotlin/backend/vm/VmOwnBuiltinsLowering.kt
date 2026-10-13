/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrExternalPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Compiling the standard library, which declares the built-ins itself, the frontend resolves some calls to the copy of a
 * built-in its metadata describes, such as `intArrayOf`. Each such call is retargeted to the function of the module with
 * that function's signature.
 */
internal class VmOwnBuiltinsLowering(private val libraries: VmLibraries) {
    fun lower(module: IrModuleFragment) {
        val own = hashMapOf<IdSignature, IrSimpleFunctionSymbol>()
        module.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (!declaration.isExpect) libraries.publicSignature(declaration)?.let { own[it] = declaration.symbol }
                super.visitSimpleFunction(declaration)
            }
        })
        val retargeted = hashMapOf<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol?>()
        module.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val callee = expression.symbol
                if (callee.owner.getPackageFragment() !is IrExternalPackageFragment) return expression
                retargeted.getOrPut(callee) { libraries.publicSignature(callee.owner)?.let(own::get) }?.let { expression.symbol = it }
                return expression
            }
        })
    }
}
