/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.jklib.pipeline

import org.jetbrains.kotlin.backend.vm.VmCodegenException
import org.jetbrains.kotlin.backend.vm.VmLoweringContext
import org.jetbrains.kotlin.backend.vm.compileToVm
import org.jetbrains.kotlin.backend.vm.vmir.VmIrWriter
import org.jetbrains.kotlin.cli.CliDiagnostics.IO_ERROR
import org.jetbrains.kotlin.cli.CliDiagnostics.VM_COMPILATION_ERROR
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.jklib.config.jklibVmOutput
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.report
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import java.io.File
import java.io.IOException

/** Lowers and generates the linked main module as binary IR for the VM, into the file `-Xvm-output` names. */
object JKlibVmCodegenPhase : PipelinePhase<JKlibIrCompilationArtifact, JKlibIrCompilationArtifact>(
    name = "JKlibVmCodegenPhase",
    postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
) {
    override fun executePhase(input: JKlibIrCompilationArtifact): JKlibIrCompilationArtifact? {
        val configuration = input.configuration
        val output = configuration.jklibVmOutput ?: return input
        val moduleName = configuration.moduleName ?: JvmProtoBufUtil.DEFAULT_MODULE_NAME

        val module = try {
            val diagnosticReporter = KtDiagnosticReporterWithImplicitIrBasedContext(
                configuration.diagnosticsCollector, configuration.languageVersionSettings,
            )
            val context = VmLoweringContext(configuration, input.pluginContext.irBuiltIns, diagnosticReporter)
            compileToVm(input.moduleFragment, moduleName, context)
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
}
