/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jklib.test.vm

import org.jetbrains.kotlin.test.model.BinaryArtifactHandler
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Links every module's binary IR with the VM runtime, from the VM checkout [vmRepository] names, and runs `box()`.
 * A failure's message starts with the step that failed, which is what [JKlibVmExpectedFailures] groups failures by.
 */
class JKlibVmBoxRunner(testServices: TestServices) : BinaryArtifactHandler<JKlibVmArtifact>(
    testServices, JKlibVmArtifactKind, failureDisablesNextSteps = true, doNotRunIfThereWerePreviousFailures = true,
) {
    private val modules = mutableListOf<Pair<TestModule, JKlibVmArtifact>>()

    override fun processModule(module: TestModule, info: JKlibVmArtifact) {
        modules += module to info
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        if (someAssertionWasFailed || modules.isEmpty()) return
        val repository = File(vmRepository ?: error("$VM_REPOSITORY_PROPERTY names no VM checkout"))
        // The VM starts from the first module's first function, which is the entry the module holding `box` has.
        val entry = modules.last { entry -> entry.first.files.any { "fun box()" in it.originalContent } }
        val virs = (listOf(entry) + (modules - entry)).map { it.second.file.path }
        val container = File(testServices.temporaryDirectoryManager.getOrCreateTempDirectory("vm-output"), "main.vmc").path

        val runtime = File(repository, "runtime/src/runtime.ir").path
        val link = exec(File(repository, "target/debug/link_ir"), container, *virs.toTypedArray(), runtime)
            ?: throw AssertionError("timeout: link")
        if (link.first != 0) throw AssertionError("link: ${lastMessage(link.second)}")
        val execute = exec(File(repository, "target/debug/execute"), container)
            ?: throw AssertionError("timeout: execute")
        when {
            execute.second.lines().any { it == "r0 = 1" } -> {}
            execute.first == 0 -> throw AssertionError("wrong result")
            else -> throw AssertionError("run: ${lastMessage(execute.second)}")
        }
    }

    /** The last line of a binary's [output] that says what went wrong: a panic's message rather than its `note:`. */
    private fun lastMessage(output: String): String =
        output.lines().lastOrNull { it.isNotBlank() && !it.startsWith("note:") } ?: ""

    /** Runs [binary], answering its exit code and output, or null when it runs past a time limit. */
    private fun exec(binary: File, vararg args: String): Pair<Int, String>? {
        check(binary.canExecute()) { "$binary is missing: build the VM checkout with `cargo build`" }
        // Into a file, since reading a pipe would wait for a program that never ends before the limit could apply.
        val log = File.createTempFile("vm-exec", ".log", testServices.temporaryDirectoryManager.getOrCreateTempDirectory("vm-output"))
        val process = ProcessBuilder(binary.path, *args).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return process.exitValue() to log.readText()
    }

    companion object {
        /** The system property naming the VM checkout the tests link and run with; the build sets it from `KOTLIN_VM_REPO`. */
        const val VM_REPOSITORY_PROPERTY = "kotlin.vm.repo"

        val vmRepository: String? get() = System.getProperty(VM_REPOSITORY_PROPERTY)
    }
}
