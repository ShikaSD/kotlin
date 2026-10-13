/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm.pipeline

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.pipeline.Fir2IrPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.FrontendPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.PipelineArtifactWithExitCode
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.pipeline.AllModulesFrontendOutput
import org.jetbrains.kotlin.fir.pipeline.Fir2IrActualizedResult

data class VmFrontendPipelineArtifact(
    override val frontendOutput: AllModulesFrontendOutput,
    override val configuration: CompilerConfiguration,
    /** The module data of the klibs the module is compiled against, the VM libraries; see [FirVmLibraries]. */
    val vmLibraries: Set<FirModuleData>,
    val projectEnvironment: VfsBasedProjectEnvironment,
    val rootDisposable: Disposable,
) : FrontendPipelineArtifact() {

    override fun withNewFrontendOutputImpl(newFrontendOutput: AllModulesFrontendOutput): FrontendPipelineArtifact {
        return copy(frontendOutput = newFrontendOutput)
    }

    @CliPipelineInternals(OPT_IN_MESSAGE)
    override fun withCompilerConfiguration(newConfiguration: CompilerConfiguration): PipelineArtifact {
        return copy(configuration = newConfiguration)
    }
}

data class VmFir2IrPipelineArtifact(
    override val result: Fir2IrActualizedResult,
    override val configuration: CompilerConfiguration,
    /** The fragments the VM runs, without the JVM's. */
    val frontendOutput: AllModulesFrontendOutput,
    val vmLibraries: Set<FirModuleData>,
    val hostActuals: VmHostActuals,
    val projectEnvironment: VfsBasedProjectEnvironment,
    val rootDisposable: Disposable,
) : Fir2IrPipelineArtifact() {
    @CliPipelineInternals(OPT_IN_MESSAGE)
    override fun withCompilerConfiguration(newConfiguration: CompilerConfiguration): PipelineArtifact {
        return copy(configuration = newConfiguration)
    }
}

data class VmSerializationArtifact(
    val outputKlibPath: String,
    override val configuration: CompilerConfiguration,
    val hostActuals: VmHostActuals,
    val projectEnvironment: VfsBasedProjectEnvironment,
    val rootDisposable: Disposable,
    /**
     * The IR the klib was written from, which [VmCodegenPhase] compiles for the VM and [VmProxyPhase] generates the proxies
     * of; null when the klib has no IR.
     */
    val fir2IrResult: Fir2IrActualizedResult? = null,
    val vmLibraries: Set<FirModuleData> = emptySet(),
    override val exitCode: ExitCode = ExitCode.OK,
) : PipelineArtifactWithExitCode() {
    @CliPipelineInternals(OPT_IN_MESSAGE)
    override fun withCompilerConfiguration(newConfiguration: CompilerConfiguration): PipelineArtifact {
        return copy(configuration = newConfiguration)
    }
}

