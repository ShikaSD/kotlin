/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.backend.common.ir.PreSerializationSymbols
import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.backend.common.lower.TailrecLowering
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.backend.vm.vmir.VmModule
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.inline.FunctionInlining
import org.jetbrains.kotlin.ir.inline.InlineFunctionResolver
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideOrSelf

/**
 * What the lowerings see of the compilation. Only what the ones [lowerForVm] runs use is provided; the rest fails loudly,
 * so a lowering added without its prerequisites says so.
 */
class VmLoweringContext(
    override val configuration: CompilerConfiguration,
    override val irBuiltIns: IrBuiltIns,
    override val diagnosticReporter: IrDiagnosticReporter,
) : LoweringContext {
    override val irFactory: IrFactory = IrFactoryImpl
    override var inVerbosePhase: Boolean = false

    override val symbols: PreSerializationSymbols
        get() = error("The VM backend declares no lowering symbols yet")

    override val sharedVariablesManager: SharedVariablesManager
        get() = error("The VM backend does not lower shared variables yet")
}

/**
 * Every inline function with a body to copy: the module's own, and those the klibs it depends on carry. One from a jar
 * has none, and its call is left for code generation to report.
 *
 * A jklib holds IR as the frontend produced it, so a function from one gets the [upgrade] the module's own code got
 * before its body is copied: the inliner only inlines a lambda upgraded to a rich function reference.
 */
private class VmInlineFunctionResolver(private val upgrade: UpgradeCallableReferences) : InlineFunctionResolver() {
    private val upgraded = hashSetOf<IrFunction>()

    override fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction? {
        if (!symbol.isBound) return null
        val function = symbol.owner.resolveFakeOverrideOrSelf().takeIf { it.isInline } ?: return null
        if (upgraded.add(function)) upgrade.lower(function)
        return function
    }
}

/** The lowerings between the linked IR and [VmModuleGenerator], in the order they run. */
fun lowerForVm(context: VmLoweringContext, module: IrModuleFragment) {
    val upgrade = UpgradeCallableReferences(context, upgradeSamConversions = false)
    // Substituted, so a primitive type argument stays unboxed in the copy.
    val inlining = object : FunctionInlining(context, VmInlineFunctionResolver(upgrade), substituteNonReifiedTypeParameters = true) {}
    module.files.forEach(upgrade::lower)
    module.files.forEach(inlining::lower)
    // After inlining, so a tail call an inlined lambda holds becomes a jump too.
    val tailrec = TailrecLowering(context)
    module.files.forEach { tailrec.lower(it) }
}

/** Lowers [module] and generates it as one VM module named [moduleName]. */
fun compileToVm(module: IrModuleFragment, moduleName: String, context: VmLoweringContext): VmModule {
    lowerForVm(context, module)
    return VmModuleGenerator(context.irBuiltIns, moduleName).generate(module)
}
