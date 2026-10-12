/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.cli.vm.config.klibDestination
import org.jetbrains.kotlin.cli.vm.config.klibPaths
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.configuration.addSourcesForDependsOnClosure
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import java.io.File

/**
 * Compiles every module against the VM's standard library, `:kotlin-stdlib-vm`, and the VM's kotlin.test,
 * `:kotlin-test-vm`, which [VmBoxRunner] links every program with.
 */
class VmSourceRootConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        configuration.addSourcesForDependsOnClosure(module, testServices)

        configuration.klibPaths += vmStdlib.path
        configuration.klibPaths += kotlinTestKlib.path

        val tempDir = testServices.temporaryDirectoryManager.getOrCreateTempDirectory("klib-output")
        configuration.klibDestination = File(tempDir, "${module.name}.klib").absolutePath

        // The test infrastructure registers a module's dependencies as JVM class directories, but the compilation
        // resolves only klibs: each dependency compiled before this module, to the path this configurator gave it.
        configuration.klibPaths += module.regularDependencies.map { File(tempDir, "${it.dependencyModule.name}.klib").absolutePath }
    }

    companion object {
        /** The system property naming the VM stdlib's klib, which the build sets. */
        const val VM_STDLIB_PROPERTY = "kotlin.stdlib.vm.for.test"

        val vmStdlib: File
            get() = File(System.getProperty(VM_STDLIB_PROPERTY) ?: error("$VM_STDLIB_PROPERTY names no VM stdlib klib"))

        /** The system property naming the VM stdlib's binary IR, which every program links. */
        const val VM_STDLIB_VIR_PROPERTY = "kotlin.stdlib.vm.vir"

        val vmStdlibVir: File
            get() = File(System.getProperty(VM_STDLIB_VIR_PROPERTY) ?: error("$VM_STDLIB_VIR_PROPERTY names no binary IR"))

        /** The system properties naming the VM kotlin.test's klib and binary IR, which the build sets. */
        const val KOTLIN_TEST_KLIB_PROPERTY = "kotlin.test.vm.klib"
        const val KOTLIN_TEST_VIR_PROPERTY = "kotlin.test.vm.vir"

        val kotlinTestKlib: File
            get() = File(System.getProperty(KOTLIN_TEST_KLIB_PROPERTY) ?: error("$KOTLIN_TEST_KLIB_PROPERTY names no klib"))

        val kotlinTestVir: File
            get() = File(System.getProperty(KOTLIN_TEST_VIR_PROPERTY) ?: error("$KOTLIN_TEST_VIR_PROPERTY names no binary IR"))
    }
}
