/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.getOrSetIfNull

private var IrClass.outerThisField: IrField? by irAttribute(copyByDefault = false)

private var IrConstructor.innerClassConstructor: IrConstructor? by irAttribute(copyByDefault = false)

private var IrClass.originalInnerClassPrimaryConstructor: IrConstructor? by irAttribute(copyByDefault = false)

/**
 * What the shared inner-class lowerings ask of the backend: an inner class keeps its outer instance in a field of its
 * own, `$this`, which each constructor takes first, as `$outer`. As the JS and Wasm backends do it.
 */
class VmInnerClassesSupport(private val irFactory: IrFactory) : InnerClassesSupport {
    override fun getOuterThisField(innerClass: IrClass): IrField {
        require(innerClass.isInner) { "${innerClass.name} is not inner" }
        return innerClass::outerThisField.getOrSetIfNull {
            val outerClass = innerClass.parent as? IrClass ?: error("The inner class ${innerClass.name} has no outer class")
            irFactory.buildField {
                origin = IrDeclarationOrigin.FIELD_FOR_OUTER_THIS
                name = Name.identifier("\$this")
                type = outerClass.defaultType
                visibility = DescriptorVisibilities.PROTECTED
                isFinal = true
            }.also { it.parent = innerClass }
        }
    }

    override fun getInnerClassConstructorWithOuterThisParameter(innerClassConstructor: IrConstructor): IrConstructor {
        val innerClass = innerClassConstructor.parent as IrClass
        require(innerClass.isInner) { "${innerClass.name} is not inner" }
        return innerClassConstructor::innerClassConstructor.getOrSetIfNull {
            withOuterThisParameter(innerClassConstructor)
        }.also {
            if (innerClassConstructor.isPrimary) innerClass.originalInnerClassPrimaryConstructor = innerClassConstructor
        }
    }

    override fun getInnerClassOriginalPrimaryConstructorOrNull(innerClass: IrClass): IrConstructor? {
        require(innerClass.isInner) { "${innerClass.name} is not inner" }
        return innerClass.originalInnerClassPrimaryConstructor
    }

    private fun withOuterThisParameter(oldConstructor: IrConstructor): IrConstructor {
        val irClass = oldConstructor.parent as IrClass
        val newConstructor = irFactory.buildConstructor {
            updateFrom(oldConstructor)
            returnType = oldConstructor.returnType
        }.also {
            it.parent = oldConstructor.parent
            it.annotations = oldConstructor.annotations
        }
        newConstructor.copyTypeParametersFrom(oldConstructor)
        val outer = buildValueParameter(newConstructor) {
            origin = IrDeclarationOrigin.DEFINED
            name = Name.identifier("\$outer")
            type = (irClass.parent as IrClass).defaultType
            kind = IrParameterKind.Regular
        }
        newConstructor.parameters += listOf(outer) + oldConstructor.nonDispatchParameters.map { it.copyTo(newConstructor) }
        return newConstructor
    }
}
