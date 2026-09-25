/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.InlineClassesUtils
import org.jetbrains.kotlin.backend.common.ir.BackendSymbols
import org.jetbrains.kotlin.backend.common.ir.KlibSharedVariablesManager
import org.jetbrains.kotlin.backend.common.ir.PreSerializationKlibSymbols
import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.backend.common.lower.ArrayConstructorLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.backend.common.lower.loops.ForLoopsLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationPopupLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.backend.common.lower.SharedVariablesLowering
import org.jetbrains.kotlin.backend.common.lower.TailrecLowering
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.backend.common.lower.inline.LocalClassesInInlineLambdasLowering
import org.jetbrains.kotlin.backend.vm.vmir.VmModule
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.inline.FunctionInlining
import org.jetbrains.kotlin.ir.inline.InlineFunctionResolver
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.functionSymbolAssociatedBy
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideOrSelf

/**
 * What the lowerings see of the compilation. Only what the ones [lowerForVm] runs use is provided; the rest fails loudly,
 * so a lowering added without its prerequisites says so.
 */
class VmLoweringContext(
    override val configuration: CompilerConfiguration,
    override val irBuiltIns: IrBuiltIns,
    override val diagnosticReporter: IrDiagnosticReporter,
) : CommonBackendContext {
    override val irFactory: IrFactory = IrFactoryImpl
    override var inVerbosePhase: Boolean = false

    override val symbols: BackendSymbols by lazy { VmSymbols(irBuiltIns) }

    override val typeSystem: IrTypeSystemContext = IrTypeSystemContextImpl(irBuiltIns)

    /** The VM backend compiles no value classes yet, so none is treated as one. */
    override val inlineClassesUtils: InlineClassesUtils = object : InlineClassesUtils {
        override fun isClassInlineLike(klass: IrClass): Boolean = false
    }

    override val innerClassesSupport: InnerClassesSupport
        get() = error("The VM backend lowers no inner classes yet")

    /** Boxes a captured variable something assigns as the klib backends do, in `kotlin.internal.SharedVariableBox`. */
    override val sharedVariablesManager: SharedVariablesManager by lazy { KlibSharedVariablesManager(SharedVariableSymbols(irBuiltIns)) }
}

/**
 * The symbols the lowerings [lowerForVm] runs read: the ranges and progressions `ForLoopsLowering` recognizes, which
 * [BackendSymbols] finds itself, and the function computing a progression's last element. The rest fail loudly.
 */
private class VmSymbols(irBuiltIns: IrBuiltIns) : BackendSymbols(irBuiltIns) {
    override val getProgressionLastElementByReturnType: Map<IrClassifierSymbol, IrSimpleFunctionSymbol> by CallableId(
        StandardNames.KOTLIN_INTERNAL_FQ_NAME, Name.identifier("getProgressionLastElement")
    ).functionSymbolAssociatedBy { it.returnType.classifierOrFail }

    override val throwNullPointerException: IrSimpleFunctionSymbol get() = unused()
    override val throwTypeCastException: IrSimpleFunctionSymbol get() = unused()
    override val throwKotlinNothingValueException: IrSimpleFunctionSymbol get() = unused()
    override val stringBuilder: IrClassSymbol get() = unused()
    override val coroutineSuspendedGetter: IrSimpleFunctionSymbol get() = unused()
    override val getContinuation: IrSimpleFunctionSymbol get() = unused()
    override val continuationClass: IrClassSymbol get() = unused()
    override val returnIfSuspended: IrSimpleFunctionSymbol get() = unused()
    override val functionAdapter: IrClassSymbol get() = unused()
    override val defaultConstructorMarker: IrClassSymbol get() = unused()
    override val throwUninitializedPropertyAccessException: IrSimpleFunctionSymbol get() = unused()
    override val throwUnsupportedOperationException: IrSimpleFunctionSymbol get() = unused()
    override val syntheticConstructorMarker: IrClassSymbol get() = unused()
    override val coroutineContextGetter: IrSimpleFunctionSymbol get() = unused()
    override val suspendCoroutineUninterceptedOrReturn: IrSimpleFunctionSymbol get() = unused()
    override val coroutineGetContext: IrSimpleFunctionSymbol get() = unused()

    private fun unused(): Nothing = error("The VM backend declares no such lowering symbol yet")
}

/** The one symbol [KlibSharedVariablesManager] reads; the rest fail loudly, as [VmSymbols] does. */
@OptIn(InternalSymbolFinderAPI::class)
private class SharedVariableSymbols(irBuiltIns: IrBuiltIns) : PreSerializationKlibSymbols {
    override val genericSharedVariableBox = PreSerializationKlibSymbols.SharedVariableBoxClassInfo(
        irBuiltIns.symbolFinder.findClass(ClassId(StandardNames.KOTLIN_INTERNAL_FQ_NAME, Name.identifier("SharedVariableBox")))
            ?: error("The standard library declares no kotlin.internal.SharedVariableBox")
    )

    override val throwUninitializedPropertyAccessException: IrSimpleFunctionSymbol get() = unused()
    override val throwUnsupportedOperationException: IrSimpleFunctionSymbol get() = unused()
    override val syntheticConstructorMarker: IrClassSymbol get() = unused()
    override val coroutineContextGetter: IrSimpleFunctionSymbol get() = unused()
    override val suspendCoroutineUninterceptedOrReturn: IrSimpleFunctionSymbol get() = unused()
    override val coroutineGetContext: IrSimpleFunctionSymbol get() = unused()

    private fun unused(): Nothing = error("The VM backend declares no such lowering symbol yet")
}

/**
 * Every inline function with a body to copy: the module's own, and those the klibs it depends on carry. One from a jar
 * has none, and its call is left for code generation to report.
 *
 * A jklib holds IR as the frontend produced it, so before its body is copied, a function from one gets the lowerings
 * the module's own code got ahead of inlining: the [upgrade], since the inliner only inlines a lambda upgraded to a rich
 * function reference, and the [arrayConstructors].
 */
private class VmInlineFunctionResolver(
    private val upgrade: UpgradeCallableReferences,
    private val arrayConstructors: ArrayConstructorLowering,
) : InlineFunctionResolver() {
    private val lowered = hashSetOf<IrFunction>()

    override fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction? {
        if (!symbol.isBound) return null
        val function = symbol.owner.resolveFakeOverrideOrSelf().takeIf { it.isInline } ?: return null
        if (lowered.add(function)) {
            upgrade.lower(function)
            function.body?.let { arrayConstructors.lower(it, function) }
        }
        return function
    }
}

/** The lowerings between the linked IR and [VmModuleGenerator], in the order they run. */
fun lowerForVm(context: VmLoweringContext, module: IrModuleFragment) {
    val upgrade = UpgradeCallableReferences(context, upgradeSamConversions = false)
    // `Array(size) { … }` and the like become a loop, ahead of inlining: the constructor is inline, but has no body.
    val arrayConstructors = ArrayConstructorLowering(context)
    val resolver = VmInlineFunctionResolver(upgrade, arrayConstructors)
    // Substituted, so a primitive type argument stays unboxed in the copy.
    val inlining = object : FunctionInlining(context, resolver, substituteNonReifiedTypeParameters = true) {}
    module.files.forEach(upgrade::lower)
    module.files.forEach { arrayConstructors.lower(it) }
    // Out of the lambdas inlining copies first: a copied class would still override the original's members.
    val localClassesInInlineLambdas = LocalClassesInInlineLambdasLowering(context)
    module.files.forEach { localClassesInInlineLambdas.lower(it) }
    module.files.forEach(inlining::lower)
    // After inlining, so a tail call an inlined lambda holds becomes a jump too.
    val tailrec = TailrecLowering(context)
    module.files.forEach { tailrec.lower(it) }
    // A `for` over an array, a range or a progression becomes a loop counting its index or its value, rather than
    // calling an iterator; after inlining, so a loop an inlined body holds is counted too.
    val forLoops = ForLoopsLowering(context)
    module.files.forEach { forLoops.lower(it) }
    // A local function becomes a function of its enclosing class or file taking what it captures as parameters, and a
    // local class keeps what it captures in fields; boxing what is also assigned first lets every copy reach it.
    val shared = SharedVariablesLowering(context)
    module.files.forEach { shared.lower(it) }
    val local = LocalDeclarationsLowering(context)
    module.files.forEach { local.lower(it) }
    val popup = LocalDeclarationPopupLowering(context)
    module.files.forEach { popup.lower(it) }
}

/** Lowers [module] and generates it as one VM module named [moduleName]. */
fun compileToVm(module: IrModuleFragment, moduleName: String, context: VmLoweringContext): VmModule {
    lowerForVm(context, module)
    return VmModuleGenerator(context.irBuiltIns, moduleName).generate(module)
}
