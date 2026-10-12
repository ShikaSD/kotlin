/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm

import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.vm.pipeline.VmCliPipeline
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.metadata.builtins.BuiltInsBinaryVersion
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms

/**
 * The entry point of the Kotlin VM compilation: a klib with references to jars, and with `-Xvm-output`, the module's binary IR for the VM.
 */
@OptIn(ObsoleteDescriptorBasedAPI::class)
class K2VmCompiler : CLICompiler<K2VmCompilerArguments>() {
    override val platform: TargetPlatform
        get() = JvmPlatforms.defaultJvmPlatform

    override fun createArguments(): K2VmCompilerArguments = K2VmCompilerArguments()

    public override fun doExecutePhased(
        arguments: K2VmCompilerArguments,
        services: Services,
        basicMessageCollector: MessageCollector,
    ): ExitCode = VmCliPipeline(defaultPerformanceManager).execute(arguments, services, basicMessageCollector)

    override fun executableScriptFileName(): String = "kotlinc"

    public override fun createMetadataVersion(versionArray: IntArray): BinaryVersion = BuiltInsBinaryVersion(*versionArray)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            doMain(K2VmCompiler(), args)
        }
    }
}
