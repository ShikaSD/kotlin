/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm.config

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object VmConfigurationKeys {
    val KLIB_DESTINATION = CompilerConfigurationKey.create<String>("klib destination")
    val KLIB_PATHS = CompilerConfigurationKey.create<List<String>>("KLIB_PATHS")
    val VM_OUTPUT = CompilerConfigurationKey.create<String>("VM binary IR output")
    val VM_HOST_FRAGMENTS = CompilerConfigurationKey.create<List<String>>("fragments the JVM owns")
}

var CompilerConfiguration.klibDestination: String?
    get() = get(VmConfigurationKeys.KLIB_DESTINATION)
    set(value) {
        putIfNotNull(VmConfigurationKeys.KLIB_DESTINATION, value)
    }

var CompilerConfiguration.klibPaths: List<String>
    get() = getList(VmConfigurationKeys.KLIB_PATHS)
    set(value) {
        put(VmConfigurationKeys.KLIB_PATHS, value)
    }

/** Where the module's binary IR for the VM goes; without it, the compilation writes only the klib. */
var CompilerConfiguration.vmOutput: String?
    get() = get(VmConfigurationKeys.VM_OUTPUT)
    set(value) {
        putIfNotNull(VmConfigurationKeys.VM_OUTPUT, value)
    }

/**
 * The fragments of the compilation the JVM owns. They are analyzed and actualized with the rest, so their `actual`s answer
 * the `expect`s of the others; the VM backend generates none of their code, and a call of one of their declarations is a
 * host import of the JVM's.
 */
var CompilerConfiguration.vmHostFragments: List<String>
    get() = getList(VmConfigurationKeys.VM_HOST_FRAGMENTS)
    set(value) {
        put(VmConfigurationKeys.VM_HOST_FRAGMENTS, value)
    }
