/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package androidx.compose.compiler.plugins.kotlin.lower

import androidx.compose.compiler.plugins.kotlin.FeatureFlags
import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.setSourceRange
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.SpecialNames

class ComposableFunctionReferenceLowering(
    context: IrPluginContext,
    metrics: ModuleMetrics,
    stabilityInferencer: StabilityInferencer,
    featureFlags: FeatureFlags,
) : AbstractComposeLowering(
    context,
    metrics,
    stabilityInferencer,
    featureFlags
) {
    override fun lower(irModule: IrModuleFragment) {
        irModule.transformChildrenVoid()
    }

    private val functionReferenceIrClass = getTopLevelClass(ClassId.fromString("kotlin/jvm/internal/FunctionReferenceImpl"))

    override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
        val fqName = expression.type.classFqName
        if (fqName == null || !fqName.asString().startsWith("androidx.compose.runtime.internal.KComposableFunction")) {
            return super.visitFunctionReference(expression)
        }

        // generate class
        val cls = context.irFactory.buildClass {
            setSourceRange(expression)
            visibility = DescriptorVisibilities.LOCAL
            origin = JvmLoweredDeclarationOrigin.FUNCTION_REFERENCE_IMPL
            name = SpecialNames.NO_NAME_PROVIDED
        }.apply {
            // add function reference impl type
            // add function supertype (@Composable () -> Unit)
            superTypes = listOf(
                functionReferenceIrClass.owner.defaultType,
                expression.type
            )
            val constructor = addConstructor {}
            DeclarationIrBuilder(context, constructor.symbol).irBlockBody {
                +irDelegatingConstructorCall(functionReferenceIrClass.constructors.single { it.owner.parameters.size == 6 }.owner).apply {
                    arguments[0] = irConst(expression.symbol.owner.parameters.size + 2)
                    arguments[1] = irNull()
                    arguments[2] = irNull()
                    arguments[3] = irConst(expression.symbol.owner.name.asString())
                    arguments[4] = irConst("()V") // todo: fill the signature
                    arguments[5] = irConst(0)
                }
                +IrInstanceInitializerCallImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    symbol,
                    defaultType
                )
            }

            // implement invoke
        }
        // call class constructor

        return TODO()
    }
}
