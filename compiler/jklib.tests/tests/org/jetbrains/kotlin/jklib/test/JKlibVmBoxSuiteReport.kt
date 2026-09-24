/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jklib.test

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jklib.K2JKlibCompiler
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.test.util.KtTestUtil
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the codegen box tests through the VM backend and writes what happened to each: a measure of how much of Kotlin
 * the backend covers, and a ranking of what blocks the rest. It asserts nothing.
 *
 * Runs only when `KOTLIN_VM_BOX_SUITE` is set, since a full run takes minutes; its value, if not `1`, names the
 * subdirectory of `compiler/testData/codegen/box` to run. It needs a built VM checkout named by `KOTLIN_VM_REPO`, and
 * writes its report where `KOTLIN_VM_BOX_REPORT` says, or to `build/vm-box-report.txt` under the working directory.
 * Run it with `--no-configuration-cache`: a cached configuration keeps the environment the test task was configured
 * with, so a changed variable would not reach it.
 */
class JKlibVmBoxSuiteReport {
    private val boxRoot = KtTestUtil.getTestDataFileLocatedInCompilerTestData("codegen/box")

    /** Directives a test needs something for that the VM backend cannot have yet: several files, reflection, a JDK. */
    private val inapplicable = listOf(
        "// FILE:", "// MODULE:", "// TARGET_BACKEND:", "// FULL_JDK", "// WITH_REFLECT", "// WITH_COROUTINES",
        "// JVM_TARGET", "// ENABLE_JVM_PREVIEW", "// IGNORE_BACKEND: ANY",
    )

    private enum class Outcome { PASS, WRONG_RESULT, RUNTIME_ERROR, LINK_ERROR, UNSUPPORTED, FRONTEND_ERROR, TIMEOUT }

    private class Result(val test: String, val withStdlib: Boolean, val outcome: Outcome, val detail: String)

    @Test
    fun report() {
        val suite = System.getenv("KOTLIN_VM_BOX_SUITE")
        val repo = System.getenv("KOTLIN_VM_REPO")
        assumeTrue(suite != null && repo != null, "KOTLIN_VM_BOX_SUITE and KOTLIN_VM_REPO select a run")
        val root = if (suite == "1") boxRoot else File(boxRoot, suite!!)
        val tests = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sorted().filter { file ->
            val text = file.readText()
            inapplicable.none { it in text }
        }.toList()

        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val results = tests.map { test -> pool.submit<Result> { run(test, File(repo!!)) } }.map { it.get() }
        pool.shutdown()

        val report = File(System.getenv("KOTLIN_VM_BOX_REPORT") ?: "build/vm-box-report.txt").absoluteFile
        report.parentFile.mkdirs()
        report.writeText(render(results))
        println("VM box suite: ${results.count { it.outcome == Outcome.PASS }} of ${results.size} pass; report in $report")
    }

    private fun run(test: File, repo: File): Result {
        val name = test.relativeTo(boxRoot).path
        val text = test.readText()
        val withStdlib = "// WITH_STDLIB" in text
        val dir = Files.createTempDirectory("vm-box").toFile()
        try {
            val vir = File(dir, "main.vir")
            val source = File(dir, test.name).apply { writeText(prepared(text)) }
            val args = arrayOf(
                source.path, "-d", File(dir, "main.klib").path, "-module-name", "main", "-no-stdlib",
                "-Xklib=${ForTestCompileRuntime.jklibStdlibForTests().path}", "-Xvm-output=${vir.path}",
                *languageFeatures(text).map { "-XXLanguage:$it" }.toTypedArray(),
            )
            val messages = ByteArrayOutputStream()
            val exitCode = try {
                K2JKlibCompiler().exec(PrintStream(messages), *args)
            } catch (e: Throwable) {
                return Result(name, withStdlib, Outcome.FRONTEND_ERROR, "compiler crashed: ${e.javaClass.simpleName}")
            }
            if (exitCode != ExitCode.OK) {
                val output = messages.toString()
                val unsupported = Regex("the VM backend does not support (.*?) yet").find(output)?.groupValues?.get(1)
                return if (unsupported != null) {
                    Result(name, withStdlib, Outcome.UNSUPPORTED, blocker(unsupported))
                } else {
                    val error = output.lineSequence().firstOrNull { "error:" in it }?.substringAfter("error: ") ?: ""
                    Result(name, withStdlib, Outcome.FRONTEND_ERROR, error.replace(Regex(" on receiver of type .*"), "").take(120))
                }
            }

            val container = File(dir, "main.vmc").path
            val link = exec(File(repo, "target/debug/link_ir"), container, vir.path, File(repo, "runtime/src/runtime.ir").path)
                ?: return Result(name, withStdlib, Outcome.TIMEOUT, "link")
            if (link.first != 0) return Result(name, withStdlib, Outcome.LINK_ERROR, link.second.lineSequence().first().take(200))
            val execute = exec(File(repo, "target/debug/execute"), container)
                ?: return Result(name, withStdlib, Outcome.TIMEOUT, "execute")
            return when {
                execute.second.lines().any { it == "r0 = 1" } -> Result(name, withStdlib, Outcome.PASS, "")
                execute.first == 0 -> Result(name, withStdlib, Outcome.WRONG_RESULT, "")
                else -> Result(name, withStdlib, Outcome.RUNTIME_ERROR, execute.second.lines().lastOrNull { it.isNotBlank() }?.take(160) ?: "")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The test as the test infrastructure compiles it: without the diagnostics it marks up, and with the annotation a
     * value class needs on the JVM where the test leaves a placeholder for one.
     */
    private fun prepared(text: String): String =
        text.replace(Regex("<!>|<![^>]*!>"), "").replace("OPTIONAL_JVM_INLINE_ANNOTATION", "@kotlin.jvm.JvmInline")

    /** The features a test's `// LANGUAGE:` directives enable or disable, as `+Feature` or `-Feature`. */
    private fun languageFeatures(text: String): List<String> =
        Regex("^// LANGUAGE:(.*)$", RegexOption.MULTILINE).findAll(text)
            .flatMap { it.groupValues[1].split(' ', ',') }
            .map { it.trim() }
            .filter { it.startsWith("+") || it.startsWith("-") }
            .toList()

    /** Runs [binary], answering its exit code and output, or null when it runs past a time limit. */
    private fun exec(binary: File, vararg args: String): Pair<Int, String>? {
        val process = ProcessBuilder(binary.path, *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return process.exitValue() to output
    }

    /**
     * What an unsupported construct is, without the rendering of where it was found, so reports group by kind: a call
     * by its callee's name, anything else by its IR node.
     */
    private fun blocker(what: String): String {
        Regex("^a call to (?:FUN|CONSTRUCTOR).*? name:(\\S+)").find(what)?.let { return "a call to ${it.groupValues[1]}" }
        return what.replace(Regex("\\b([A-Z_]{3,}) .*"), "$1 …").replace(Regex("<[^>]*>"), "").take(120)
    }

    private fun render(results: List<Result>): String = buildString {
        fun summary(title: String, group: List<Result>) {
            appendLine("$title: ${group.size} tests")
            for (outcome in Outcome.entries) {
                val count = group.count { it.outcome == outcome }
                if (count > 0) appendLine("  %-15s %5d  %5.1f%%".format(outcome, count, 100.0 * count / group.size))
            }
        }
        summary("All", results)
        summary("Without WITH_STDLIB", results.filter { !it.withStdlib })
        summary("With WITH_STDLIB", results.filter { it.withStdlib })

        for (outcome in listOf(Outcome.UNSUPPORTED, Outcome.FRONTEND_ERROR, Outcome.LINK_ERROR, Outcome.RUNTIME_ERROR)) {
            appendLine()
            appendLine("Top $outcome:")
            results.filter { it.outcome == outcome }.groupBy { it.detail }
                .entries.sortedByDescending { it.value.size }.take(40)
                .forEach { group ->
                    appendLine("  %5d  %s".format(group.value.size, group.key))
                    appendLine("         e.g. ${group.value.take(3).joinToString { it.test }}")
                }
        }
        appendLine()
        appendLine("Passing:")
        results.filter { it.outcome == Outcome.PASS }.forEach { appendLine("  ${it.test}") }
        appendLine()
        appendLine("Wrong result:")
        results.filter { it.outcome == Outcome.WRONG_RESULT }.forEach { appendLine("  ${it.test}") }
    }
}
