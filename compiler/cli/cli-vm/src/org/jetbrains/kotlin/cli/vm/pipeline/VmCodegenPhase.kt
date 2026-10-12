/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm.pipeline

import org.jetbrains.kotlin.backend.vm.VmCodegenException
import org.jetbrains.kotlin.backend.vm.VmLoweringContext
import org.jetbrains.kotlin.backend.vm.compileToVm
import org.jetbrains.kotlin.backend.vm.vmir.VmIrWriter
import org.jetbrains.kotlin.cli.CliDiagnostics.IO_ERROR
import org.jetbrains.kotlin.cli.CliDiagnostics.VM_COMPILATION_ERROR
import org.jetbrains.kotlin.cli.CliDiagnostics.VM_COMPILATION_WARNING
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.vm.config.vmHostFragments
import org.jetbrains.kotlin.cli.vm.config.vmOutput
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.report
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.hmppModuleStructure
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import java.io.File
import java.io.IOException

/** Lowers and generates the linked main module as binary IR for the VM, into the file `-Xvm-output` names. */
object VmCodegenPhase : PipelinePhase<VmIrCompilationArtifact, VmIrCompilationArtifact>(
    name = "VmCodegenPhase",
    postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
) {
    override fun executePhase(input: VmIrCompilationArtifact): VmIrCompilationArtifact? {
        val configuration = input.configuration
        val output = configuration.vmOutput ?: return input
        val moduleName = configuration.moduleName ?: JvmProtoBufUtil.DEFAULT_MODULE_NAME

        val module = try {
            val diagnosticReporter = KtDiagnosticReporterWithImplicitIrBasedContext(
                configuration.diagnosticsCollector, configuration.languageVersionSettings,
            )
            val context = VmLoweringContext(configuration, input.pluginContext.irBuiltIns, diagnosticReporter)
            val isStdlib = configuration.languageVersionSettings.getFlag(AnalysisFlags.stdlibCompilation)
            val hostFiles = hostFiles(input.moduleFragment, configuration)
            compileToVm(input.moduleFragment, moduleName, context, isStdlib, hostFiles) { stub ->
                configuration.report(VM_COMPILATION_WARNING, "the VM backend generated a stub for $stub")
            }
        } catch (e: VmCodegenException) {
            configuration.report(VM_COMPILATION_ERROR, e.message ?: "VM code generation failed")
            return null
        }
        try {
            File(output).writeBytes(VmIrWriter.write(module))
        } catch (e: IOException) {
            configuration.report(IO_ERROR, "Failed to write $output: ${e.message}")
            return null
        }
        return input
    }

    /** The files of [module] from the fragments `-Xvm-host-fragment` names, which the JVM owns. */
    private fun hostFiles(module: IrModuleFragment, configuration: CompilerConfiguration): Set<IrFile> {
        val fragments = configuration.vmHostFragments.toSet()
        val structure = configuration.hmppModuleStructure ?: return emptySet()
        val sources = structure.modules.filter { it.name in fragments }.flatMap { it.sources }.mapTo(hashSetOf(), ::canonicalPath)
        return module.files.filterTo(hashSetOf()) { canonicalPath(it.fileEntry.name) in sources }
    }

    private fun canonicalPath(path: String): String = File(path).canonicalPath
}
