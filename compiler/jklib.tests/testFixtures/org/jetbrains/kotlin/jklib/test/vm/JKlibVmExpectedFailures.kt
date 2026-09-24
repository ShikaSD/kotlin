/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jklib.test.vm

import org.jetbrains.kotlin.test.WrappedException
import org.jetbrains.kotlin.test.model.TestFailureSuppressor
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * The box tests expected to fail on the VM, each with why, in [FILE]: most of Kotlin is still unsupported, and muting
 * with `IGNORE_BACKEND` would mean touching thousands of shared test files. A listed test that fails passes; a listed
 * test that passes fails, so the list only shrinks deliberately.
 *
 * With the system property [UPDATE_PROPERTY] set, nothing fails: each test's outcome is recorded instead, and the file
 * rewritten when the test JVM exits, the header counting the most common reasons.
 */
class JKlibVmExpectedFailures(testServices: TestServices) : TestFailureSuppressor(testServices) {
    private val test: String
        get() = testServices.moduleStructure.originalTestDataFiles.first().invariantSeparatorsPath.substringAfter(BOX_ROOT)

    override fun suppressIfNeeded(failedAssertions: List<WrappedException>): List<WrappedException> {
        if (updating) {
            outcomes[test] = reason(failedAssertions.first().cause)
            return emptyList()
        }
        return if (test in expected) emptyList() else failedAssertions
    }

    override fun checkIfTestShouldBeUnmuted() {
        if (updating) {
            outcomes[test] = PASSED
        } else if (test in expected) {
            throw AssertionError("$test passes on the VM now: remove it from $FILE")
        }
    }

    /** Why [failure] happened, stably across runs, so the file changes only as outcomes do. */
    private fun reason(failure: Throwable): String {
        val message = failure.message.orEmpty()
        Regex("the VM backend does not support (.*?) yet").find(message)?.let { return "unsupported: ${blocker(it.groupValues[1])}" }
        val line = message.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        if (Regex("^(link|run|timeout|wrong result)").containsMatchIn(line)) return line.take(200)
        // A frontend error, without where in which file it was found.
        val error = line.replace(Regex(" at \\S+:\\(\\d+,\\d+\\)"), "")
        return "${failure.javaClass.simpleName}: $error".take(160)
    }

    /**
     * What an unsupported construct is, without the rendering of where it was found, so failures group by kind: a call
     * by its callee's name, anything else by its IR node.
     */
    private fun blocker(what: String): String {
        Regex("^a call to (?:FUN|CONSTRUCTOR).*? name:(\\S+)").find(what)?.let { return "a call to ${it.groupValues[1]}" }
        return what.replace(Regex("\\b([A-Z_]{3,}) .*"), "$1 …").replace(Regex("<[^>]*>"), "").take(160)
    }

    companion object {
        const val FILE = "compiler/jklib.tests/testData/vmBoxFailures.txt"
        const val UPDATE_PROPERTY = "kotlin.vm.box.update"
        private const val BOX_ROOT = "compiler/testData/codegen/box/"
        private const val PASSED = ""

        private val updating = System.getProperty(UPDATE_PROPERTY) == "true"
        private val outcomes = ConcurrentHashMap<String, String>()

        /** The test JVM runs in the test project's directory, so the file is found as test data declared in the build. */
        private val file: File get() = ForTestCompileRuntime.transformTestDataPath(FILE)

        private val expected: Map<String, String> by lazy { read(file.takeIf { it.exists() }?.readText().orEmpty()) }

        init {
            if (updating) Runtime.getRuntime().addShutdownHook(Thread(::write))
        }

        private fun read(text: String): Map<String, String> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore(": ") to it.substringAfter(": ") }

        /** Merges this JVM's outcomes into the file, under a lock, since several test JVMs may exit at once. */
        private fun write() {
            RandomAccessFile(file, "rw").use { output ->
                output.channel.lock().use {
                    val bytes = ByteArray(output.length().toInt()).also(output::readFully)
                    val failures = read(String(bytes)).toMutableMap()
                    for (outcome in outcomes) {
                        if (outcome.value == PASSED) failures.remove(outcome.key) else failures[outcome.key] = outcome.value
                    }
                    val text = render(failures).toByteArray()
                    output.setLength(0)
                    output.write(text)
                }
            }
        }

        private fun render(failures: Map<String, String>): String = buildString {
            appendLine("# Box tests under compiler/testData/codegen/box expected to fail on the VM; see JKlibVmExpectedFailures.")
            appendLine("# ${failures.size} failures; the most common reasons:")
            failures.values.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).take(50).forEach {
                appendLine("#   %5d  %s".format(it.value, it.key))
            }
            appendLine()
            failures.toSortedMap().forEach { failure -> appendLine("${failure.key}: ${failure.value}") }
        }
    }
}
