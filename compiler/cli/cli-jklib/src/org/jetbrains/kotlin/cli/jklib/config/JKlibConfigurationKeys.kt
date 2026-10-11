/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.jklib.config

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object JKlibConfigurationKeys {
    val JKLIB_OUTPUT_DESTINATION = CompilerConfigurationKey.create<String>("jklib output destination")
    val JKLIB_COMPILE_IR = CompilerConfigurationKey.create<Boolean>("jklib compile ir")
    val KLIB_PATHS = CompilerConfigurationKey.create<List<String>>("KLIB_PATHS")
    val JKLIB_TARGETS_VM = CompilerConfigurationKey.create<Boolean>("jklib targets the VM")
    val JKLIB_VM_OUTPUT = CompilerConfigurationKey.create<String>("jklib VM binary IR output")
    val JKLIB_VM_HOST_FRAGMENTS = CompilerConfigurationKey.create<List<String>>("jklib fragments the JVM owns")
}

var CompilerConfiguration.jklibOutputDestination: String?
    get() = get(JKlibConfigurationKeys.JKLIB_OUTPUT_DESTINATION)
    set(value) {
        putIfNotNull(JKlibConfigurationKeys.JKLIB_OUTPUT_DESTINATION, value)
    }

var CompilerConfiguration.jklibCompileIr: Boolean
    get() = get(JKlibConfigurationKeys.JKLIB_COMPILE_IR) ?: false
    set(value) {
        put(JKlibConfigurationKeys.JKLIB_COMPILE_IR, value)
    }

var CompilerConfiguration.klibPaths: List<String>
    get() = getList(JKlibConfigurationKeys.KLIB_PATHS)
    set(value) {
        put(JKlibConfigurationKeys.KLIB_PATHS, value)
    }

var CompilerConfiguration.jklibVmOutput: String?
    get() = get(JKlibConfigurationKeys.JKLIB_VM_OUTPUT)
    set(value) {
        putIfNotNull(JKlibConfigurationKeys.JKLIB_VM_OUTPUT, value)
    }

/**
 * The fragments of the compilation the JVM owns. They are analyzed and actualized with the rest, so their `actual`s answer
 * the `expect`s of the others; the VM backend generates none of their code, and a call of one of their declarations is a
 * host import of the JVM's.
 */
var CompilerConfiguration.jklibVmHostFragments: List<String>
    get() = getList(JKlibConfigurationKeys.JKLIB_VM_HOST_FRAGMENTS)
    set(value) {
        put(JKlibConfigurationKeys.JKLIB_VM_HOST_FRAGMENTS, value)
    }

/** Whether the compilation sees the built-in collection classes as the VM has them: with Kotlin's members only, not the JDK's. */
var CompilerConfiguration.jklibTargetsVm: Boolean
    get() = get(JKlibConfigurationKeys.JKLIB_TARGETS_VM) ?: false
    set(value) {
        put(JKlibConfigurationKeys.JKLIB_TARGETS_VM, value)
    }
