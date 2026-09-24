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

    /** Directives a test needs something for that the VM backend cannot have yet: reflection, a JDK, Java sources. */
    private val inapplicable = listOf(
        "// TARGET_BACKEND:", "// FULL_JDK", "// WITH_REFLECT", "// WITH_COROUTINES",
        "// JVM_TARGET", "// ENABLE_JVM_PREVIEW", "// IGNORE_BACKEND: ANY",
    )

    /** A test that `DONT_TARGET_EXACT_BACKEND` keeps off a JVM backend checks a behaviour the VM does not model either. */
    private fun excludesJvm(text: String): Boolean =
        Regex("""// DONT_TARGET_EXACT_BACKEND:(.*)""").findAll(text).any { match ->
            match.groupValues[1].split(',', ' ').any { it.trim().startsWith("JVM") }
        }

    private enum class Outcome { PASS, WRONG_RESULT, RUNTIME_ERROR, LINK_ERROR, UNSUPPORTED, FRONTEND_ERROR, TIMEOUT }

    private class Result(val test: String, val withStdlib: Boolean, val shape: Shape, val outcome: Outcome, val detail: String)

    /** How a test's sources are split, which the report counts separately. */
    private enum class Shape { ONE_FILE, FILES, MODULES }

    private class TestModule(val name: String, val dependencies: List<String>, val friends: List<String>) {
        val files = mutableListOf<Pair<String, StringBuilder>>()
    }

    /**
     * The test's modules, dependencies first, each with its files, as `// MODULE: name(dependencies)(friends)` and
     * `// FILE: name` split it; text before a module's first file goes to a file of its own, named as the test for the
     * first module. Null for a test the
     * backend cannot compile: one with a file that is not Kotlin, or a multiplatform module, which the directive's
     * third group names the common modules of.
     */
    private fun modules(text: String, defaultFile: String = "main.kt"): List<TestModule>? {
        val modules = mutableListOf<TestModule>()
        val lines = text.lines()
        // Directives above a test's first module are the test's, not a module's of their own.
        val firstModule = lines.indexOfFirst { it.startsWith("// MODULE:") }
        if (firstModule > 0) return modules(lines.drop(firstModule).joinToString("\n"), defaultFile)
        for (line in lines) {
            val module = Regex("""^// MODULE: ([\w-]+)(?:\(([^)]*)\))?(?:\(([^)]*)\))?(\(.*)?$""").find(line)
            val file = Regex("""^// FILE: (\S+)""").find(line)
            when {
                module != null -> {
                    val [_, name, dependencies, friends, rest] = module.groupValues
                    if (rest.isNotEmpty()) return null
                    fun names(list: String) = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    modules += TestModule(name, names(dependencies), names(friends))
                }
                file != null -> {
                    if (!file.groupValues[1].endsWith(".kt")) return null
                    if (modules.isEmpty()) modules += TestModule("main", emptyList(), emptyList())
                    modules.last().files += file.groupValues[1] to StringBuilder()
                }
                else -> {
                    if (modules.isEmpty()) modules += TestModule("main", emptyList(), emptyList())
                    val current = modules.last()
                    if (current.files.isEmpty()) current.files += (if (modules.size == 1) defaultFile else "${current.name}.kt") to StringBuilder()
                    current.files.last().second.appendLine(line)
                }
            }
        }
        val byName = modules.associateBy { it.name }
        if (modules.any { module -> (module.dependencies + module.friends).any { it !in byName } }) return null
        val ordered = LinkedHashSet<TestModule>()
        fun visit(module: TestModule) {
            if (module in ordered) return
            (module.dependencies + module.friends).forEach { visit(byName.getValue(it)) }
            ordered += module
        }
        modules.forEach(::visit)
        return ordered.toList()
    }

    @Test
    fun report() {
        val suite = System.getenv("KOTLIN_VM_BOX_SUITE")
        val repo = System.getenv("KOTLIN_VM_REPO")
        assumeTrue(suite != null && repo != null, "KOTLIN_VM_BOX_SUITE and KOTLIN_VM_REPO select a run")
        val root = if (suite == "1") boxRoot else File(boxRoot, suite!!)
        val tests = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sorted().filter { file ->
            val text = file.readText()
            inapplicable.none { it in text } && !excludesJvm(text) && modules(text) != null
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
        val dir = Files.createTempDirectory("vm-box").toFile()
        try {
            val result = compileAndRun(test, repo, dir)
            // What a failure compiled to, for reading with the VM's `dump_ir`, beside its source.
            val keep = System.getenv("KOTLIN_VM_BOX_KEEP")
            if (keep != null && result.outcome in setOf(Outcome.WRONG_RESULT, Outcome.RUNTIME_ERROR, Outcome.LINK_ERROR)) {
                val base = File(keep, result.test.replace('/', '_').removeSuffix(".kt")).apply { parentFile.mkdirs() }
                dir.listFiles { file -> file.extension == "vir" }!!.forEach { vir ->
                    vir.copyTo(File("${base.path}.${vir.nameWithoutExtension}.vir"), overwrite = true)
                }
                File("${base.path}.kt").writeText(prepared(test.readText()))
            }
            return result
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun compileAndRun(test: File, repo: File, dir: File): Result {
        val name = test.relativeTo(boxRoot).path
        val text = test.readText()
        val withStdlib = "// WITH_STDLIB" in text
        val modules = modules(text, test.name)!!
        val shape = when {
            modules.size > 1 -> Shape.MODULES
            modules.single().files.size > 1 -> Shape.FILES
            else -> Shape.ONE_FILE
        }
        fun result(outcome: Outcome, detail: String) = Result(name, withStdlib, shape, outcome, detail)

        val stdlib = ForTestCompileRuntime.jklibStdlibForTests().path
        fun klib(module: String) = File(dir, "$module.klib").path
        for (module in modules) {
            val sources = File(dir, module.name).apply { mkdirs() }
            val paths = module.files.map { file ->
                File(sources, file.first).apply { parentFile.mkdirs() }.apply { writeText(prepared(file.second.toString())) }.path
            }
            val args = listOfNotNull(
                *paths.toTypedArray(), "-d", klib(module.name), "-module-name", module.name, "-no-stdlib",
                "-Xklib=${(listOf(stdlib) + (module.dependencies + module.friends).distinct().map(::klib)).joinToString(File.pathSeparator)}",
                module.friends.takeIf { it.isNotEmpty() }?.let { friends -> "-friend-modules=${friends.joinToString(File.pathSeparator) { klib(it) }}" },
                "-Xvm-output=${File(dir, "${module.name}.vir").path}",
                *languageFeatures(text).map { "-XXLanguage:$it" }.toTypedArray(),
            )
            val messages = ByteArrayOutputStream()
            val exitCode = try {
                K2JKlibCompiler().exec(PrintStream(messages), *args.toTypedArray())
            } catch (e: Throwable) {
                return result(Outcome.FRONTEND_ERROR, "compiler crashed: ${e.javaClass.simpleName}")
            }
            if (exitCode != ExitCode.OK) {
                val output = messages.toString()
                val unsupported = Regex("the VM backend does not support (.*?) yet").find(output)?.groupValues?.get(1)
                return if (unsupported != null) {
                    result(Outcome.UNSUPPORTED, blocker(unsupported))
                } else {
                    val error = output.lineSequence().firstOrNull { "error:" in it }?.substringAfter("error: ") ?: ""
                    result(Outcome.FRONTEND_ERROR, error.replace(Regex(" on receiver of type .*"), "").take(120))
                }
            }
        }

        // The VM starts from the first module's first function, which is the entry the module holding `box` has.
        val entry = modules.lastOrNull { module -> module.files.any { Regex("""fun box\(\)""").containsMatchIn(it.second) } }
            ?: return result(Outcome.FRONTEND_ERROR, "no box function")
        val virs = (listOf(entry) + (modules - entry)).map { File(dir, "${it.name}.vir").path }
        val container = File(dir, "main.vmc").path
        val link = exec(File(repo, "target/debug/link_ir"), container, *virs.toTypedArray(), File(repo, "runtime/src/runtime.ir").path)
            ?: return result(Outcome.TIMEOUT, "link")
        if (link.first != 0) return result(Outcome.LINK_ERROR, lastMessage(link.second).take(200))
        val execute = exec(File(repo, "target/debug/execute"), container)
            ?: return result(Outcome.TIMEOUT, "execute")
        return when {
            execute.second.lines().any { it == "r0 = 1" } -> result(Outcome.PASS, "")
            execute.first == 0 -> result(Outcome.WRONG_RESULT, "")
            else -> result(Outcome.RUNTIME_ERROR, lastMessage(execute.second).take(160))
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

    /** The last line of a binary's [output] that says what went wrong: a panic's message rather than its `note:`. */
    private fun lastMessage(output: String): String =
        output.lines().lastOrNull { it.isNotBlank() && !it.startsWith("note:") } ?: ""

    /** Runs [binary], answering its exit code and output, or null when it runs past a time limit. */
    private fun exec(binary: File, vararg args: String): Pair<Int, String>? {
        // Into a file, since reading a pipe would wait for a program that never ends before the limit could apply.
        val log = File.createTempFile("vm-exec", ".log")
        try {
            val process = ProcessBuilder(binary.path, *args).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            return process.exitValue() to log.readText()
        } finally {
            log.delete()
        }
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
        for (shape in Shape.entries) summary("$shape without WITH_STDLIB", results.filter { !it.withStdlib && it.shape == shape })

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
        for (outcome in listOf(Outcome.WRONG_RESULT, Outcome.RUNTIME_ERROR, Outcome.LINK_ERROR, Outcome.TIMEOUT)) {
            appendLine()
            appendLine("All $outcome:")
            results.filter { it.outcome == outcome }.forEach { appendLine("  ${it.test}  ${it.shape}  ${it.detail}") }
        }
    }
}
