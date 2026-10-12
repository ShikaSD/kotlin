/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm.pipeline

import org.jetbrains.kotlin.cli.vm.config.vmHostFragments
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.hmppModuleStructure
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.declarations.getSingleMatchedExpectForActualOrNull
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.pipeline.SingleModuleFrontendOutput
import org.jetbrains.kotlin.ir.backend.jklib.JKlibIrMangler
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.JvmStandardClassIds
import org.jetbrains.kotlin.name.Name
import java.io.File

/**
 * The `expect` functions the JVM implements, by their signature, each with the internal name of the JVM facade its
 * `actual` is in. The fragments `-Xvm-host-fragment` names are analyzed beside the others, which pairs each `actual` there
 * with its `expect`, and are never converted to IR: a call of one of those `expect`s is a host import; see [hostImportKey].
 */
class VmHostActuals(private val facades: Map<String, String>) {
    /** The facade holding [function]'s `actual`, if [function] is an `expect` the JVM implements. */
    fun facadeOf(function: IrSimpleFunction): String? = facades[hostImportKey(function)]

    companion object {
        val NONE = VmHostActuals(emptyMap())
    }
}

/** The key [VmHostActuals] knows an `expect` by: its signature, which is the same before a klib is written and after it is read. */
private fun hostImportKey(function: IrSimpleFunction): String = with(JKlibIrMangler()) { function.signatureString(compatibleMode = false) }

/** Whether [output] is one of the fragments the JVM owns, which `-Xvm-host-fragment` names. */
internal fun CompilerConfiguration.isHostFragment(output: SingleModuleFrontendOutput): Boolean {
    val fragments = vmHostFragments.toSet()
    if (fragments.isEmpty()) return false
    val structure = hmppModuleStructure ?: return false
    val sources = structure.modules.filter { it.name in fragments }.flatMap { it.sources }.mapTo(hashSetOf(), ::canonicalPath)
    return output.fir.isNotEmpty() && output.fir.all { file -> file.sourceFile?.path?.let(::canonicalPath) in sources }
}

private fun canonicalPath(path: String): String = File(path).canonicalPath

/** The `actual` top-level functions of [hosts] by their `expect`, which [components] converted to IR. */
@OptIn(DirectDeclarationsAccess::class)
internal fun hostActuals(hosts: List<SingleModuleFrontendOutput>, components: Fir2IrComponents): VmHostActuals {
    val facades = hashMapOf<String, String>()
    for (host in hosts) {
        for (file in host.fir) {
            val facade = file.jvmFacadeName(host.session) ?: continue
            for (declaration in file.declarations) {
                if (declaration !is FirNamedFunction || !declaration.isActual) continue
                val expect = declaration.symbol.getSingleMatchedExpectForActualOrNull() ?: continue
                val function = components.declarationStorage.getIrFunctionSymbol(expect).owner as? IrSimpleFunction ?: continue
                facades[hostImportKey(function)] = facade
            }
        }
    }
    return VmHostActuals(facades)
}

/** The internal name of the JVM class holding [this] file's top-level functions, or null when it is a multifile class's part. */
private fun FirFile.jvmFacadeName(session: FirSession): String? {
    fun argument(classId: ClassId) = annotations.getAnnotationByClassId(classId, session)?.getStringArgument(Name.identifier("name"))
    if (annotations.getAnnotationByClassId(JvmStandardClassIds.JVM_MULTIFILE_CLASS_ID, session) != null) return null
    val packageName = argument(ClassId.topLevel(JvmStandardClassIds.JVM_PACKAGE_NAME))?.let(::FqName) ?: packageDirective.packageFqName
    val simpleName = argument(JvmStandardClassIds.JVM_NAME_CLASS_ID) ?: PackagePartClassUtils.getFilePartShortName(name)
    return packageName.child(Name.identifier(simpleName)).asString().replace('.', '/')
}
