/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jklib.test.vm

import org.jetbrains.kotlin.cli.jklib.config.klibPaths
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.jklib.test.irText.JKlibSourceRootConfigurator
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

/**
 * Compiles every module against the VM's standard library, `:kotlin-stdlib-vm`, rather than the jklib test one, and
 * against the VM's kotlin.test, `:kotlin-test-vm`, which [JKlibVmBoxRunner] links every program with.
 */
class JKlibVmSourceRootConfigurator(testServices: TestServices) : JKlibSourceRootConfigurator(testServices) {
    override val stdlib: File
        get() = vmStdlib

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        super.configureCompilerConfiguration(configuration, module)
        configuration.klibPaths += kotlinTestKlib.path
    }

    companion object {
        /** The system property naming the VM stdlib's klib, which the build sets. */
        const val VM_STDLIB_PROPERTY = "kotlin.stdlib.vm.for.test"

        val vmStdlib: File
            get() = File(System.getProperty(VM_STDLIB_PROPERTY) ?: error("$VM_STDLIB_PROPERTY names no VM stdlib klib"))

        /** The system properties naming the VM kotlin.test's klib and binary IR, which the build sets. */
        const val KOTLIN_TEST_KLIB_PROPERTY = "kotlin.test.vm.klib"
        const val KOTLIN_TEST_VIR_PROPERTY = "kotlin.test.vm.vir"

        val kotlinTestKlib: File
            get() = File(System.getProperty(KOTLIN_TEST_KLIB_PROPERTY) ?: error("$KOTLIN_TEST_KLIB_PROPERTY names no klib"))

        val kotlinTestVir: File
            get() = File(System.getProperty(KOTLIN_TEST_VIR_PROPERTY) ?: error("$KOTLIN_TEST_VIR_PROPERTY names no binary IR"))
    }
}
