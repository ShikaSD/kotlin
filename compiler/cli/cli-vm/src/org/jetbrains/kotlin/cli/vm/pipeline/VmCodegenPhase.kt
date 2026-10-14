/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm.pipeline

import org.jetbrains.kotlin.backend.vm.VmCodegenException
import org.jetbrains.kotlin.backend.vm.VmLoweringContext
import org.jetbrains.kotlin.backend.vm.compileToVm
import org.jetbrains.kotlin.backend.vm.vmir.VmIrWriter
import org.jetbrains.kotlin.backend.vm.vmir.VmModule
import org.jetbrains.kotlin.cli.CliDiagnostics.IO_ERROR
import org.jetbrains.kotlin.cli.CliDiagnostics.VM_COMPILATION_ERROR
import org.jetbrains.kotlin.cli.CliDiagnostics.VM_COMPILATION_WARNING
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.vm.config.vmOutput
import org.jetbrains.kotlin.cli.vm.config.vmProxies
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.report
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import java.io.File
import java.io.IOException

/** Lowers and generates the module as binary IR for the VM, into the file `-Xvm-output` names. */
object VmCodegenPhase : PipelinePhase<VmSerializationArtifact, VmSerializationArtifact>(
    name = "VmCodegenPhase",
    postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
) {
    override fun executePhase(input: VmSerializationArtifact): VmSerializationArtifact? {
        val configuration = input.configuration
        val output = configuration.vmOutput ?: return input
        val module = try {
            compileModuleToVm(input)
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

/**
 * The module the klib of [input] was written from, lowered and generated for the VM, or a [VmCodegenException] for what
 * the backend does not support. Lowering changes the IR in place, which nothing reads after it.
 */
fun compileModuleToVm(input: VmSerializationArtifact): VmModule {
    val configuration = input.configuration
    val fir2IrResult = input.fir2IrResult ?: error("The module was compiled to no IR")
    val moduleName = configuration.moduleName ?: JvmProtoBufUtil.DEFAULT_MODULE_NAME
    val diagnosticReporter = KtDiagnosticReporterWithImplicitIrBasedContext(
        configuration.diagnosticsCollector, configuration.languageVersionSettings,
    )
    val libraries = FirVmLibraries(input.vmLibraries, fir2IrResult.irBuiltIns)
    val context = VmLoweringContext(configuration, fir2IrResult.irBuiltIns, diagnosticReporter, libraries)
    val isStdlib = configuration.languageVersionSettings.getFlag(AnalysisFlags.stdlibCompilation)
    return compileToVm(
        fir2IrResult.irModuleFragment, moduleName, context, isStdlib, input.hostActuals::facadeOf,
        reportStub = { stub -> configuration.report(VM_COMPILATION_WARNING, "the VM backend generated a stub for $stub") },
        hasProxies = configuration.vmProxies != null,
    )
}
