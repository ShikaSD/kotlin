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
import org.jetbrains.kotlin.backend.common.lower.InlineClassDeclarationLowering
import org.jetbrains.kotlin.backend.common.lower.InlineClassUsageLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassConstructorCallsLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesLowering
import org.jetbrains.kotlin.backend.common.lower.InnerClassesMemberBodyLowering
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
import org.jetbrains.kotlin.descriptors.ValueClassBackendAgnosticApi
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.inline.FunctionInlining
import org.jetbrains.kotlin.ir.inline.InlineFunctionResolverReplacingCoroutineIntrinsics
import org.jetbrains.kotlin.ir.inline.InlineMode
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.inlineClassRepresentation
import org.jetbrains.kotlin.ir.functionSymbolAssociatedBy
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.ir.getterSymbol
import org.jetbrains.kotlin.ir.functionSymbol
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.name.FqName

/** The package of the VM standard library's own declarations, `platform-src/kotlin/vm/internal`. */
private val VM_INTERNAL_FQ_NAME = FqName("kotlin.vm.internal")

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

    override val symbols: VmSymbols by lazy { VmSymbols(irBuiltIns) }

    override val typeSystem: IrTypeSystemContext = IrTypeSystemContextImpl(irBuiltIns)

    override val inlineClassesUtils: InlineClassesUtils = object : InlineClassesUtils {
        override fun isClassInlineLike(klass: IrClass): Boolean = klass.isInlinedValueClass()
    }

    override val innerClassesSupport: InnerClassesSupport = VmInnerClassesSupport(irFactory)

    /** Boxes a captured variable something assigns as the klib backends do, in `kotlin.internal.SharedVariableBox`. */
    override val sharedVariablesManager: SharedVariablesManager by lazy { KlibSharedVariablesManager(SharedVariableSymbols(irBuiltIns)) }
}

/**
 * The symbols the lowerings [lowerForVm] runs read: the ranges and progressions `ForLoopsLowering` recognizes, which
 * [BackendSymbols] finds itself, the function computing a progression's last element, and the functions comparing arrays'
 * content. The rest fail loudly.
 */
class VmSymbols(irBuiltIns: IrBuiltIns) : BackendSymbols(irBuiltIns) {
    override val getProgressionLastElementByReturnType: Map<IrClassifierSymbol, IrSimpleFunctionSymbol> by CallableId(
        StandardNames.KOTLIN_INTERNAL_FQ_NAME, Name.identifier("getProgressionLastElement")
    ).functionSymbolAssociatedBy { it.returnType.classifierOrFail }

    /** The standard library's `contentEquals` by the array type it compares, which an annotation's `equals` calls. */
    val arraysContentEquals: Map<IrType, IrSimpleFunctionSymbol> by CallableId(
        StandardNames.COLLECTIONS_PACKAGE_FQ_NAME, Name.identifier("contentEquals")
    ).functionSymbolAssociatedBy(
        condition = { it.hasShape(extensionReceiver = true, regularParameters = 1) && it.parameters[0].type.isNullable() },
        getKey = { it.parameters[0].type.makeNotNull() },
    )

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
    override val coroutineContextGetter: IrSimpleFunctionSymbol by CallableId(
        StandardNames.COROUTINES_PACKAGE_FQ_NAME, Name.identifier("coroutineContext")
    ).getterSymbol()
    override val suspendCoroutineUninterceptedOrReturn: IrSimpleFunctionSymbol by CallableId(
        VM_INTERNAL_FQ_NAME, Name.identifier("suspendCoroutineUninterceptedOrReturn")
    ).functionSymbol()
    override val coroutineGetContext: IrSimpleFunctionSymbol by CallableId(
        VM_INTERNAL_FQ_NAME, Name.identifier("getCoroutineContext")
    ).functionSymbol()

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
 * has none, and its call is left for code generation to report. `suspendCoroutineUninterceptedOrReturn` and
 * `coroutineContext`, which the common library leaves to the backend, are the VM standard library's versions.
 *
 * A jklib holds IR as the frontend produced it, so before its body is copied, a function from one gets the lowerings
 * the module's own code got ahead of inlining: the [upgrade], since the inliner only inlines a lambda upgraded to a rich
 * function reference, and the [arrayConstructors].
 */
private class VmInlineFunctionResolver(
    context: VmLoweringContext,
    private val upgrade: UpgradeCallableReferences,
    private val arrayConstructors: ArrayConstructorLowering,
) : InlineFunctionResolverReplacingCoroutineIntrinsics<VmLoweringContext>(context, InlineMode.ALL_INLINE_FUNCTIONS) {
    private val lowered = hashSetOf<IrFunction>()

    override fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction? {
        val function = super.getFunctionDeclaration(symbol) ?: return null
        if (lowered.add(function)) {
            upgrade.lower(function)
            function.body?.let { arrayConstructors.lower(it, function) }
        }
        return function
    }
}

/** The lowerings between the linked IR and [VmModuleGenerator], in the order they run. */
fun lowerForVm(context: VmLoweringContext, module: IrModuleFragment) {
    // First, so the members it generates get every lowering that follows, such as an unsigned member's.
    val annotations = VmAnnotationImplementationLowering(context)
    module.files.forEach { annotations.lower(it) }
    val upgrade = UpgradeCallableReferences(context, upgradeSamConversions = false)
    // `Array(size) { … }` and the like become a loop, ahead of inlining: the constructor is inline, but has no body.
    val arrayConstructors = ArrayConstructorLowering(context)
    val resolver = VmInlineFunctionResolver(context, upgrade, arrayConstructors)
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
    // A SAM conversion becomes a class implementing the interface, after inlining, which can copy one.
    val sam = VmSingleAbstractMethodLowering(context)
    module.files.forEach { sam.lower(it) }
    // A `for` over an array, a range or a progression becomes a loop counting its index or its value, rather than
    // calling an iterator; after inlining, so a loop an inlined body holds is counted too.
    val forLoops = ForLoopsLowering(context)
    module.files.forEach { forLoops.lower(it) }
    // Before the local declarations, which then capture the `this` an entry's reference becomes.
    val enumEntryReferences = VmEnumEntryReferencesLowering()
    module.files.forEach { enumEntryReferences.lower(it) }
    // A local function becomes a function of its enclosing class or file taking what it captures as parameters, and a
    // local class keeps what it captures in fields; boxing what is also assigned first lets every copy reach it.
    val shared = SharedVariablesLowering(context)
    module.files.forEach { shared.lower(it) }
    val local = LocalDeclarationsLowering(context)
    module.files.forEach { local.lower(it) }
    val popup = LocalDeclarationPopupLowering(context)
    module.files.forEach { popup.lower(it) }
    // An inner class keeps its outer instance in a field, which each of its constructors takes first; after local
    // declarations, which can be inner too.
    val innerClasses = InnerClassesLowering(context)
    val innerClassBodies = InnerClassesMemberBodyLowering(context)
    val innerClassConstructorCalls = InnerClassConstructorCallsLowering(context)
    module.files.forEach { innerClasses.lower(it) }
    module.files.forEach { innerClassBodies.lower(it) }
    module.files.forEach { innerClassConstructorCalls.lower(it) }
    // A value class held as its underlying value has each member as a function of that value, which a call of the member
    // becomes; the class itself remains as the box, whose members delegate to those functions. See [isInlinedValueClass].
    val inlineClassDeclarations = InlineClassDeclarationLowering(context)
    val inlineClassUsages = InlineClassUsageLowering(context)
    module.files.forEach { inlineClassDeclarations.lower(it) }
    module.files.forEach { inlineClassUsages.lower(it) }
}

/**
 * Whether values of this class are held as its underlying value, and boxed only where a reference is expected: a value
 * class whose underlying value is a primitive, or another such class. Boxing it is then decided by the registers' types
 * as a primitive's is; a value class over a reference stays an ordinary class, which only its identity tells apart.
 */
@OptIn(ValueClassBackendAgnosticApi::class)
internal fun IrClass.isInlinedValueClass(): Boolean {
    if (!isValue) return false
    val underlying = inlineClassRepresentation(treatCompatibleFullValueClassesAsInline = false)?.underlyingType ?: return false
    if (underlying.isMarkedNullable()) return false
    return underlying.isPrimitiveType() || underlying.classOrNull?.owner?.isInlinedValueClass() == true
}

/**
 * Lowers [module] and generates it as one VM module named [moduleName]. Compiling the standard library, [isStdlib],
 * [reportStub] is told of each function generated as a stub; see [VmModuleGenerator].
 */
fun compileToVm(
    module: IrModuleFragment,
    moduleName: String,
    context: VmLoweringContext,
    isStdlib: Boolean = false,
    reportStub: (String) -> Unit = {},
): VmModule {
    lowerForVm(context, module)
    return VmModuleGenerator(context.irBuiltIns, moduleName, isStdlib, reportStub).generate(module)
}
