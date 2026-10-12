/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.vm.K2VmCompiler
import org.jetbrains.kotlin.vm.test.VmSourceRootConfigurator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * The VM backend behind `-Xvm-output`. A test that runs its output needs a built checkout of the VM repository, named by
 * the `KOTLIN_VM_REPO` environment variable, and is skipped without one.
 */
class VmBackendTest {
    private val stdlibKlib: String
        get() = VmSourceRootConfigurator.vmStdlib.path

    private data class Compilation(val output: String, val exitCode: ExitCode)

    /**
     * Compiles the module [name] from [files] against the modules named by [dependencies], compiled before it. With
     * [hostFiles], [files] are the common fragment and [hostFiles] the JVM's, which the VM backend generates nothing of.
     */
    private fun compile(
        tempDir: File,
        files: Map<String, String>,
        name: String = "main",
        dependencies: List<String> = emptyList(),
        targetsVm: Boolean = true,
        extraArgs: List<String> = emptyList(),
        hostFiles: Map<String, String> = emptyMap(),
    ): Compilation {
        val sources = File(tempDir, name).apply { mkdirs() }
        val paths = files.map { file -> File(sources, file.key).apply { writeText(file.value.trimIndent()) }.path }
        val hostSources = File(tempDir, "$name-jvm").apply { mkdirs() }
        val hostPaths = hostFiles.map { file -> File(hostSources, file.key).apply { writeText(file.value.trimIndent()) }.path }
        val fragmentArgs = if (hostPaths.isEmpty()) emptyList() else listOf(
            "-Xfragments=common,jvm",
            "-Xfragment-refines=jvm:common",
            "-Xfragment-sources=${(paths.map { "common:$it" } + hostPaths.map { "jvm:$it" }).joinToString(",")}",
            "-Xvm-host-fragment=jvm",
        )
        val klibs = listOf(stdlibKlib) + dependencies.map { File(tempDir, "$it.klib").path }
        val args = paths + hostPaths + listOf(
            "-d", File(tempDir, "$name.klib").path,
            "-module-name", name,
            "-no-stdlib",
            "-Xklib=${klibs.joinToString(File.pathSeparator)}",
        ) + fragmentArgs + extraArgs + if (targetsVm) listOf("-Xvm-output=${File(tempDir, "$name.vir").path}") else emptyList()
        // A stream of this compilation's own: capturing System.err would race the other tests running in parallel.
        val messages = ByteArrayOutputStream()
        val exitCode = K2VmCompiler().exec(PrintStream(messages), *args.toTypedArray())
        return Compilation(messages.toString(), exitCode)
    }

    private fun compile(tempDir: File, source: String): Compilation = compile(tempDir, mapOf("box.kt" to source))

    /** Compiles [source], links it against the VM runtime and runs it, returning what `box()` answered. */
    private fun runBox(tempDir: File, source: String): Boolean = runBox(tempDir, listOf(mapOf("box.kt" to source)))

    /**
     * Compiles each of [modules], a module's files by name, against the ones before it, links them against the VM
     * runtime and runs them. The last module is `main`, holding `box()`; the others are `lib0`, `lib1` and so on.
     */
    private fun runBox(tempDir: File, modules: List<Map<String, String>>): Boolean =
        runOutput(tempDir, modules).lines().any { it == "r0 = 1" }

    /** What running [modules] as [runBox] does writes, the program's own output first. */
    private fun runOutput(tempDir: File, modules: List<Map<String, String>>): String {
        val repo = System.getProperty("kotlin.vm.repo")
        assumeTrue(repo != null, "KOTLIN_VM_REPO names no VM checkout to run on")
        val names = modules.indices.map { if (it == modules.lastIndex) "main" else "lib$it" }
        modules.forEachIndexed { index, files ->
            val compilation = compile(tempDir, files, names[index], names.take(index))
            assertEquals(ExitCode.OK, compilation.exitCode) { "Compilation of ${names[index]} failed: ${compilation.output}" }
        }

        val container = File(tempDir, "main.vmc").path
        val runtime = File(repo, "runtime/src/runtime.ir").path
        // The VM starts from the first module's first function: `main`'s entry.
        val virs = names.asReversed().map { File(tempDir, "$it.vir").path }
        val stdlib = VmSourceRootConfigurator.vmStdlibVir.path
        run(File(repo, "target/box_tests/link_ir"), container, *virs.toTypedArray(), stdlib, runtime)
        return run(File(repo, "target/box_tests/execute"), container)
    }

    /** [source] compiled, as the VM's `dump_ir` prints the module. */
    private fun dumpIr(tempDir: File, source: String): String = dumpIr(tempDir, mapOf("box.kt" to source))

    /** [files] compiled as one module, beside the JVM's [hostFiles], as the VM's `dump_ir` prints it. */
    private fun dumpIr(tempDir: File, files: Map<String, String>, hostFiles: Map<String, String> = emptyMap()): String {
        val repo = System.getProperty("kotlin.vm.repo")
        assumeTrue(repo != null, "KOTLIN_VM_REPO names no VM checkout to run on")
        val compilation = compile(tempDir, files, hostFiles = hostFiles)
        assertEquals(ExitCode.OK, compilation.exitCode) { "Compilation failed: ${compilation.output}" }
        return run(File(repo, "target/box_tests/dump_ir"), File(tempDir, "main.vir").path)
    }

    private fun run(binary: File, vararg args: String): String {
        assertTrue(binary.canExecute()) { "$binary is missing: build the VM repository with `cargo build --profile box_tests`" }
        val process = ProcessBuilder(binary.path, *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(1, TimeUnit.MINUTES)) { "$binary timed out" }
        assertEquals(0, process.exitValue()) { "$binary failed: $output" }
        return output
    }

    @Test
    fun testOutputIsBinaryIr(@TempDir tempDir: File) {
        val (output, exitCode) = compile(tempDir, """fun box(): String = "OK"""")
        assertEquals(ExitCode.OK, exitCode) { "Compilation failed: $output" }

        val magic = File(tempDir, "main.vir").readBytes().copyOf(4).decodeToString()
        assertEquals("VMIR", magic)
    }

    @Test
    fun testUnsupportedDeclarationIsReported(@TempDir tempDir: File) {
        val (output, exitCode) = compile(tempDir, "fun f(x: Any) = x::class")

        assertEquals(ExitCode.COMPILATION_ERROR, exitCode)
        assertTrue(output.contains("the VM backend does not support the expression GET_CLASS")) { output }
    }


    @Test
    fun testBoxReturningOk(@TempDir tempDir: File) {
        assertTrue(runBox(tempDir, """fun box(): String = "OK""""))
    }

    @Test
    fun testBoxReturningAnotherString(@TempDir tempDir: File) {
        assertFalse(runBox(tempDir, """fun box(): String = "fail""""))
    }

    @Test
    fun testRecursiveCall(@TempDir tempDir: File) {
        val source = """
            fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

            fun box(): String = if (fib(20) == 6765) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testWhileLoopWithBreakAndContinue(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var sum = 0
                var i = 0
                while (true) {
                    i++
                    if (i % 2 == 0) continue
                    if (i > 9) break
                    sum += i
                }
                return if (sum == 25) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testIntArithmeticWraps(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val max = 2147483647
                val min = max + 1
                return if (min == -2147483648 && max * 2 == -2 && min / -1 == min) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testIntShiftsUseTheLowFiveBits(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val x = -8
                return if (x shl 33 == -16 && x shr 1 == -4 && x ushr 28 == 15) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testComparisonsOfEveryKind(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val a = 3
                val b = 5
                val ok = a < b && a <= b && b > a && b >= a && a <= 3 && b >= 5 && a != b && !(a > b)
                return if (ok) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testDoubleArithmetic(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val x = 1.5 * 4 - 1.0
                return if (x == 5.0 && x / 2 > 2.4 && (7).toDouble() > x) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCharArithmetic(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val c = 'a' + 2
                return if (c == 'c' && c - 'a' == 2) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFieldsFromConstructorParameters(@TempDir tempDir: File) {
        val source = """
            class Point(val x: Int, val y: Int) {
                fun sum() = x + y
            }

            fun box(): String = if (Point(3, 4).sum() == 7) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testVirtualDispatchThroughSuperclass(@TempDir tempDir: File) {
        val source = """
            open class Shape {
                open fun area(): Int = 0
            }

            class Square(val side: Int) : Shape() {
                override fun area() = side * side
            }

            fun measure(shape: Shape) = shape.area()

            fun box(): String = if (measure(Square(3)) == 9 && measure(Shape()) == 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAbstractMethodCalledFromBaseClass(@TempDir tempDir: File) {
        val source = """
            abstract class Animal {
                abstract fun legs(): Int
                fun describe() = legs() * 10
            }

            class Dog : Animal() {
                override fun legs() = 4
            }

            fun box(): String = if (Dog().describe() == 40) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testInterfaceDefaultMethod(@TempDir tempDir: File) {
        val source = """
            interface Greeter {
                fun base(): Int = 1
                fun twice() = base() * 2
            }

            class Plain : Greeter

            class Loud : Greeter {
                override fun base() = 5
            }

            fun twice(greeter: Greeter) = greeter.twice()

            fun box(): String = if (twice(Plain()) == 2 && twice(Loud()) == 10) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testInitializationRunsInDeclarationOrder(@TempDir tempDir: File) {
        val source = """
            class Counter(start: Int) {
                var value = start
                init {
                    value = value * 10
                }
                val doubled = value * 2

                constructor() : this(7)
            }

            fun box(): String = if (Counter().doubled == 140 && Counter(1).value == 10) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testSuperCall(@TempDir tempDir: File) {
        val source = """
            open class Base {
                open fun id() = 1
            }

            class Derived : Base() {
                override fun id() = super.id() + 10
            }

            fun box(): String = if (Derived().id() == 11) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testTypeTestsAndSafeCast(@TempDir tempDir: File) {
        val source = """
            open class A
            class B : A()

            fun kind(x: Any): Int = if (x is B) 2 else if (x is A) 1 else 0

            fun box(): String {
                val notB: Any = A()
                return if (kind(B()) == 2 && kind(A()) == 1 && kind("s") == 0 && notB as? B == null) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEqualsOverrideAnswersEquality(@TempDir tempDir: File) {
        val source = """
            class Id(val v: Int) {
                override fun equals(other: Any?): Boolean = other is Id && other.v == v
                override fun hashCode(): Int = v
            }

            fun box(): String = if (Id(1) == Id(1) && Id(1) != Id(2)) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testDefaultToStringIsNameAndHash(@TempDir tempDir: File) {
        val source = """
            class Point {
                override fun hashCode(): Int = 255
            }

            fun box(): String = if (Point().toString() == "Point@ff") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** As the JVM names a nested class: after the classes nesting it, joined by `$`. */
    @Test
    fun testDefaultToStringNamesANestedClassAsTheJvm(@TempDir tempDir: File) {
        val source = """
            class Outer {
                class Inner {
                    override fun hashCode(): Int = 255
                }
            }

            fun box(): String = if (Outer.Inner().toString() == "Outer\${'$'}Inner@ff") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testPropertySetter(@TempDir tempDir: File) {
        val source = """
            class Box {
                var v = 0
                    set(value) {
                        field = value * 2
                    }
            }

            fun box(): String {
                val b = Box()
                b.v = 3
                return if (b.v == 6) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testInlineFunctionWithLambda(@TempDir tempDir: File) {
        val source = """
            inline fun sumOf(n: Int, f: (Int) -> Int): Int {
                var sum = 0
                var i = 0
                while (i < n) {
                    sum += f(i)
                    i++
                }
                return sum
            }

            fun box(): String = if (sumOf(4) { it * it } == 14) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testNonLocalReturnFromInlinedLambda(@TempDir tempDir: File) {
        val source = """
            inline fun upTo(n: Int, f: (Int) -> Unit) {
                var i = 0
                while (i < n) {
                    f(i)
                    i++
                }
            }

            fun firstSquareOver(limit: Int): Int {
                upTo(10) { if (it * it > limit) return it }
                return -1
            }

            fun box(): String = if (firstSquareOver(20) == 5 && firstSquareOver(1000) == -1) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLabeledReturnFromInlinedLambda(@TempDir tempDir: File) {
        val source = """
            inline fun transform(value: Int, f: (Int) -> Int): Int = f(value)

            fun box(): String {
                val r = transform(5) {
                    if (it > 3) return@transform 1
                    2
                }
                return if (r == 1) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testStdlibScopeFunctions(@TempDir tempDir: File) {
        val source = """
            class Point {
                var x = 0
            }

            fun box(): String {
                val p = Point().apply { x = 3 }
                val same = p.also { it.x = it.x + 1 }
                val other = p.let { Point() }
                return if (same === p && p.x == 4 && other !== p && run { "OK" } == "OK") "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testReifiedTypeParameter(@TempDir tempDir: File) {
        val source = """
            open class A
            class B : A()

            inline fun <reified T> isA(x: Any): Boolean = x is T

            fun box(): String = if (isA<B>(B()) && !isA<B>(A()) && isA<A>(B())) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Inlining substitutes a non-reified type parameter too, so a primitive type argument needs no boxing. */
    @Test
    fun testInlinedGenericOverPrimitive(@TempDir tempDir: File) {
        val source = """
            inline fun <T> transform(value: T, f: (T) -> T): T = f(value)

            fun box(): String = if (5.let { it + 1 } == 6 && transform(20) { it * 2 } == 40 && run { 7 } == 7) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testTopLevelVarWithConstantStart(@TempDir tempDir: File) {
        val source = """
            var count = 5

            fun bump() {
                count++
            }

            fun box(): String {
                bump()
                bump()
                return if (count == 7) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testTopLevelPropertiesInitializeInDeclarationOrder(@TempDir tempDir: File) {
        val source = """
            val a = compute()
            val b = a * 10

            fun compute() = 6 * 7

            fun box(): String = if (a == 42 && b == 420) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFileInitializerRunsOnce(@TempDir tempDir: File) {
        val source = """
            var calls = 0
            val value = sideEffect()

            fun sideEffect(): Int {
                calls++
                return 3
            }

            fun box(): String = if (value + value == 6 && calls == 1) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testObjectIsASingleton(@TempDir tempDir: File) {
        val source = """
            object Counter {
                var n = 0
                fun next(): Int {
                    n++
                    return n
                }
            }

            fun box(): String {
                Counter.next()
                return if (Counter.next() == 2 && Counter === Counter) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The instance is stored before its constructor runs, so the constructor can reach it. */
    @Test
    fun testObjectSeesItselfDuringConstruction(@TempDir tempDir: File) {
        val source = """
            object Self {
                val me: Any = Self
            }

            fun box(): String = if (Self.me === Self) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCompanionObject(@TempDir tempDir: File) {
        val source = """
            class Temperature(val celsius: Int) {
                companion object {
                    val zero = Temperature(0)
                    fun of(celsius: Int) = Temperature(celsius)
                }
            }

            fun box(): String = if (Temperature.of(5).celsius == 5 && Temperature.zero.celsius == 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** As on the JVM, where the companion is the outer class's static, creating the outer class creates it. */
    @Test
    fun testCompanionIsCreatedWithTheOuterClass(@TempDir tempDir: File) {
        val source = """
            var created = 0

            class Outer {
                companion object {
                    init {
                        created++
                    }
                }
            }

            fun box(): String {
                val before = created
                Outer()
                return if (before == 0 && created == 1) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** As a call to a facade class's static does on the JVM, a call to the file's function runs its initializer. */
    @Test
    fun testCallingAFileFunctionRunsItsInitializer(@TempDir tempDir: File) {
        val source = """
            var initialized = 0
            val marker = mark()

            fun mark(): Int {
                initialized = 1
                return 0
            }

            fun box(): String = if (initialized == 1) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEnumEntriesHaveNameAndOrdinal(@TempDir tempDir: File) {
        val source = """
            enum class Color { RED, GREEN }

            fun box(): String {
                val ok = Color.GREEN.ordinal == 1 && Color.RED.name == "RED" && Color.RED === Color.RED && Color.RED != Color.GREEN
                return if (ok) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEnumToStringIsItsName(@TempDir tempDir: File) {
        val source = """
            enum class Color { RED, GREEN }

            fun describe(value: Any) = value.toString()

            fun box(): String = if (describe(Color.GREEN) == "GREEN") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEnumComparesByOrdinal(@TempDir tempDir: File) {
        val source = """
            enum class Size { SMALL, MEDIUM, LARGE }

            fun box(): String = if (Size.SMALL < Size.LARGE && Size.LARGE.compareTo(Size.MEDIUM) == 1) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEnumWithConstructorParametersAndMembers(@TempDir tempDir: File) {
        val source = """
            enum class Planet(val mass: Int) {
                SMALL(1), BIG(100);

                fun twice() = mass * 2
            }

            fun box(): String = if (Planet.BIG.twice() == 200 && Planet.SMALL.mass == 1) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEnumEntryWithBody(@TempDir tempDir: File) {
        val source = """
            enum class Op {
                PLUS {
                    override fun apply(a: Int, b: Int) = a + b
                },
                TIMES {
                    override fun apply(a: Int, b: Int) = a * b
                };

                abstract fun apply(a: Int, b: Int): Int
            }

            fun box(): String = if (Op.PLUS.apply(3, 4) == 7 && Op.TIMES.apply(3, 4) == 12 && Op.TIMES.ordinal == 1) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testWhenOverEnum(@TempDir tempDir: File) {
        val source = """
            enum class Color { RED, GREEN, BLUE }

            fun code(c: Color) = when (c) {
                Color.RED -> 1
                Color.GREEN -> 2
                else -> 3
            }

            fun box(): String = if (code(Color.RED) == 1 && code(Color.GREEN) == 2 && code(Color.BLUE) == 3) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testThrowAndCatch(@TempDir tempDir: File) {
        val source = """
            fun checked(x: Int): Int {
                if (x < 0) throw IllegalArgumentException("negative")
                return x
            }

            fun box(): String {
                try {
                    checked(-1)
                } catch (e: IllegalArgumentException) {
                    return if (e.message == "negative") "OK" else "fail: message"
                }
                return "fail: nothing thrown"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFirstMatchingCatchClauseWins(@TempDir tempDir: File) {
        val source = """
            fun classify(e: Throwable): Int = try {
                throw e
            } catch (x: IllegalStateException) {
                1
            } catch (x: RuntimeException) {
                2
            } catch (x: Throwable) {
                3
            }

            fun box(): String {
                val ok = classify(IllegalStateException()) == 1 && classify(IllegalArgumentException()) == 2 && classify(Error()) == 3
                return if (ok) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The VM raises its own classes for these, and they are the JVM classes a Kotlin program catches. */
    @Test
    fun testImplicitErrorsAreCaughtAsJvmClasses(@TempDir tempDir: File) {
        val source = """
            fun divide(a: Int, b: Int) = a / b

            fun box(): String {
                var caught = 0
                try {
                    divide(1, 0)
                } catch (e: ArithmeticException) {
                    caught++
                }
                val absent: Any? = null
                try {
                    absent!!
                } catch (e: NullPointerException) {
                    caught++
                }
                return if (caught == 2) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFinallyRunsOnNormalExit(@TempDir tempDir: File) {
        val source = """
            var log = 0

            fun box(): String {
                try {
                    log += 1
                } finally {
                    log += 10
                }
                return if (log == 11) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFinallyRunsWhenAnExceptionPassesThrough(@TempDir tempDir: File) {
        val source = """
            var log = 0

            fun fail() {
                try {
                    throw IllegalStateException()
                } finally {
                    log += 10
                }
            }

            fun box(): String {
                try {
                    fail()
                } catch (e: IllegalStateException) {
                    log += 1
                }
                return if (log == 11) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFinallyRunsOnReturnAfterTheValueIsComputed(@TempDir tempDir: File) {
        val source = """
            var log = 0

            fun value(): Int {
                try {
                    return log + 5
                } finally {
                    log = 100
                }
            }

            fun box(): String = if (value() == 5 && log == 100) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFinallyRunsOnBreak(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var log = 0
                while (true) {
                    try {
                        break
                    } finally {
                        log += 1
                    }
                }
                return if (log == 1) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testTryIsAnExpression(@TempDir tempDir: File) {
        val source = """
            fun parse(ok: Boolean): Int = if (ok) 42 else throw NumberFormatException()

            fun box(): String {
                val good = try { parse(true) } catch (e: NumberFormatException) { -1 }
                val bad = try { parse(false) } catch (e: NumberFormatException) { -1 }
                return if (good == 42 && bad == -1) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCustomExceptionClass(@TempDir tempDir: File) {
        val source = """
            class MyError(val code: Int) : RuntimeException("custom")

            fun box(): String {
                try {
                    throw MyError(7)
                } catch (e: RuntimeException) {
                    val ok = e is MyError && e.code == 7 && e.message == "custom" && e.toString() == "MyError: custom"
                    return if (ok) "OK" else "fail"
                }
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCauseOnlyConstructorTakesTheCauseAsMessage(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val cause = IllegalStateException("inner")
                val outer = RuntimeException(cause)
                val ok = outer.cause === cause && outer.message == "java.lang.IllegalStateException: inner"
                return if (ok) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testExhaustiveWhenOverEnum(@TempDir tempDir: File) {
        val source = """
            enum class Color { RED, GREEN }

            fun code(c: Color) = when (c) {
                Color.RED -> 1
                Color.GREEN -> 2
            }

            fun box(): String = if (code(Color.RED) == 1 && code(Color.GREEN) == 2) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLateinitPropertyThrowsUntilAssigned(@TempDir tempDir: File) {
        val source = """
            class Holder {
                lateinit var name: String
            }

            fun box(): String {
                val holder = Holder()
                val message = try {
                    holder.name
                } catch (e: UninitializedPropertyAccessException) {
                    e.message
                }
                holder.name = "set"
                val ok = message == "lateinit property name has not been initialized" && holder.name == "set"
                return if (ok) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testStringPlus(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val nothing: Any? = null
                return if ("a" + "b" == "ab" && "x" + nothing == "xnull") "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testStringTemplateOfReferencesAndConstants(@TempDir tempDir: File) {
        val source = """
            class Named {
                override fun toString() = "named"
            }

            fun box(): String {
                val n = Named()
                val s = "${'$'}n is ${'$'}{2}nd, not ${'$'}{null}"
                return if (s == "named is 2nd, not null") "OK" else s
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The inner `try` does not catch, so its `finally` runs, and then the outer `catch`. */
    @Test
    fun testInnerFinallyRunsBeforeOuterCatch(@TempDir tempDir: File) {
        val source = """
            var log = ""

            fun box(): String {
                try {
                    try {
                        throw IllegalStateException()
                    } catch (e: IllegalArgumentException) {
                        log += "wrong"
                    } finally {
                        log += "f"
                    }
                } catch (e: IllegalStateException) {
                    log += "c"
                }
                return if (log == "fc") "OK" else log
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testPrimitiveHeldAsAny(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val x: Any = 5
                return if (x == 5 && x is Int && (x as Int) + 1 == 6) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testNullablePrimitive(@TempDir tempDir: File) {
        val source = """
            fun find(present: Boolean): Int? = if (present) 3 else null

            fun box(): String = if (find(true) == 3 && find(false) == null && find(true)!! + 1 == 4) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testGenericClassOverPrimitive(@TempDir tempDir: File) {
        val source = """
            class Box<T>(val value: T)

            fun box(): String = if (Box(5).value + 1 == 6 && Box(2.5).value * 2 == 5.0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The override takes and returns `Int` where the interface's `T` is a reference, so its body unboxes and boxes. */
    @Test
    fun testOverrideTakingAPrimitiveForAGenericParameter(@TempDir tempDir: File) {
        val source = """
            interface Transform<T> {
                fun apply(x: T): T
            }

            class Doubler : Transform<Int> {
                override fun apply(x: Int) = x * 2
            }

            fun <T> applyTo(transform: Transform<T>, x: T): T = transform.apply(x)

            fun box(): String = if (applyTo(Doubler(), 21) == 42 && Doubler().apply(4) == 8) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testTemplateFormatsPrimitives(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val n = 1
                val s = "${'$'}{n + 1}, ${'$'}{n * 2.5}, ${'$'}{'c'}, ${'$'}{n > 0}, ${'$'}{-9L - n}"
                return if (s == "2, 2.5, c, true, -10") "OK" else s
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testBoxedEqualityComparesClassAndValue(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val a: Any = 1000
                val b: Any = 1000
                val long: Any = 1000L
                return if (a == b && a != long) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Statically a `Double`, `==` is IEEE 754's; boxed, it is `Double.equals`, under which NaN equals itself. */
    @Test
    fun testDoubleEqualityOfPrimitivesAndBoxes(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val zero = 0.0
                val nan = zero / zero
                val boxed: Any = nan
                val other: Any = nan
                return if (nan != nan && boxed == other) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testTailrecFunctionRunsAsALoop(@TempDir tempDir: File) {
        val source = """
            tailrec fun sum(x: Long, acc: Long): Long = if (x == 0L) acc else sum(x - 1, acc + x)

            fun box(): String = if (sum(1000000, 0) == 500000500000L) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** What follows a `return` in the same expression is unreachable, and reads a value nothing defines. */
    @Test
    fun testCodeAfterAnExpressionThatNeverCompletes(@TempDir tempDir: File) {
        val source = """
            fun pick(first: Boolean): Int {
                val x: Int = if (first) return 1 else return 2
                return x
            }

            fun box(): String = if (pick(true) == 1 && pick(false) == 2) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A smart cast leaves the receiver's static type nullable, so the member's parameter type unboxes it. */
    @Test
    fun testArithmeticOnASmartCastNullableByte(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var b: Byte? = 0
                if (b != null) b--
                return if (b == (-1).toByte()) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testNegatingADoubleFlipsTheSignOfZero(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val zero = 0.0
                val negative = -zero
                return if (1.0 / negative < 0.0) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Statically `Double?`, `==` is still IEEE 754's: `0.0 == -0.0`, where `Double.equals` would say no. */
    @Test
    fun testNullableDoublesCompareAsIeee(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val zero: Double? = 0.0
                val negative: Double? = -0.0
                val none: Double? = null
                return if (zero == negative && zero != none && none == null) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `when` over a `Double` tests each case with `==`, which is IEEE 754's: `-0.0` matches `0.0`. */
    @Test
    fun testWhenOverADoubleComparesAsIeee(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val negative = -0.0
                return when (negative) {
                    0.0 -> "OK"
                    else -> "fail"
                }
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testWhenOverANullableDoubleComparesAsIeee(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val negative: Double? = -0.0
                return when (negative) {
                    0.0 -> "OK"
                    else -> "fail"
                }
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testExtensionPropertiesOverloadedByReceiver(@TempDir tempDir: File) {
        val source = """
            val Int.kind: String get() = "O"
            val Long.kind: String get() = "K"

            fun box(): String = 1.kind + 1L.kind
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testPrivateOverloads(@TempDir tempDir: File) {
        val source = """
            private fun kind(x: Int) = "O"
            private fun kind(x: Long) = "K"

            fun box(): String = kind(1) + kind(1L)
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `Enum`'s own `name` answers the interface's selector, which the runtime cannot. */
    @Test
    fun testEnumImplementsAnInterfaceWithItsName(@TempDir tempDir: File) {
        val source = """
            interface Named {
                val name: String
            }

            enum class E : Named { OK }

            fun box(): String {
                val named: Named = E.OK
                return named.name
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testClassNamedLikeAJvmClass(@TempDir tempDir: File) {
        val source = """
            enum class Enum { OK }

            class Integer(val value: String)

            fun box(): String = if (Integer(Enum.OK.name).value == "OK") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A template of constants is one literal, so two of the same text are one string, as on the JVM. */
    @Test
    fun testConstantTemplatesAreOneString(@TempDir tempDir: File) {
        val source = """
            object A {
                const val tag = "${'$'}"
                val text = "1234${'$'}tag"
            }

            object B {
                const val tag = "${'$'}"
                val text = "1234${'$'}tag"
            }

            fun box(): String = if (A.text === B.text) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testBoxingABooleanTwiceGivesOneBox(@TempDir tempDir: File) {
        val source = """
            fun box(value: Boolean): Any = value

            fun box(): String = if (box(true) === box(true) && box(true) !== box(false)) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCallsAFunctionOfAnotherModule(@TempDir tempDir: File) {
        val lib = """fun greeting(suffix: String) = "O" + suffix"""
        val main = """fun box(): String = greeting("K")"""
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    /** The property's initializer is the other module's to run, on the first call to its getter. */
    @Test
    fun testReadsATopLevelPropertyOfAnotherModule(@TempDir tempDir: File) {
        val lib = """
            fun join(a: String, b: String) = a + b
            val computed = join("O", "K")
        """
        val main = """fun box(): String = computed"""
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    @Test
    fun testConstructsAClassOfAnotherModule(@TempDir tempDir: File) {
        val lib = """
            class Box(val value: String) {
                fun twice() = value + value
            }
        """
        val main = """fun box(): String = if (Box("OK").twice() == "OKOK") "OK" else "fail" """
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    /** The subclass lays out its superclass's fields first, private ones included, as the other module lays them out. */
    @Test
    fun testSubclassesAClassOfAnotherModule(@TempDir tempDir: File) {
        val lib = """
            open class Base(private val first: String) {
                fun first() = first
            }
        """
        val main = """
            class Derived(val second: String) : Base("O")

            fun box(): String = Derived("K").let { it.first() + it.second }
        """
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    @Test
    fun testOverridesAMethodOfAnotherModule(@TempDir tempDir: File) {
        val lib = """
            open class Greeter {
                open fun greet() = "fail"
            }

            fun greet(greeter: Greeter) = greeter.greet()
        """
        val main = """
            class Custom : Greeter() {
                override fun greet() = "OK"
            }

            fun box(): String = greet(Custom())
        """
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    @Test
    fun testInheritsAMethodOfAnotherModule(@TempDir tempDir: File) {
        val lib = """
            abstract class Named {
                abstract val name: String
                open fun describe() = "O" + name
            }
        """
        val main = """
            class K : Named() {
                override val name = "K"
            }

            fun box(): String {
                val named: Named = K()
                return named.describe()
            }
        """
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    @Test
    fun testImplementsAnInterfaceOfAnotherModule(@TempDir tempDir: File) {
        val lib = """
            interface Source {
                fun next(): String
            }

            fun twice(source: Source) = source.next() + source.next()
        """
        val main = """
            class Letters : Source {
                private var count = 0
                override fun next() = if (count++ == 0) "O" else "K"
            }

            fun box(): String = twice(Letters())
        """
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    @Test
    fun testTypeTestAgainstAClassOfAnotherModule(@TempDir tempDir: File) {
        val lib = """
            open class Animal
            class Cat : Animal()
        """
        val main = """
            fun isAnimal(x: Any) = x is Animal
            fun isCat(x: Any) = x is Cat

            fun box(): String = if (isAnimal(Cat()) && isCat(Cat()) && !isCat(Animal()) && !isAnimal("x")) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    @Test
    fun testUsesAnObjectOfAnotherModule(@TempDir tempDir: File) {
        val lib = """
            object Registry {
                var count = 0
            }
        """
        val main = """
            fun box(): String {
                Registry.count++
                Registry.count++
                return if (Registry.count == 2) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    @Test
    fun testUsesAnEnumOfAnotherModule(@TempDir tempDir: File) {
        val lib = """enum class Answer { NO, OK }"""
        val main = """
            fun box(): String = when (val answer = Answer.OK) {
                Answer.NO -> "fail"
                Answer.OK -> answer.name
            }
        """
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    /** A module two levels down is imported from directly, by the module that declares what is used. */
    @Test
    fun testUsesAModuleThroughAnother(@TempDir tempDir: File) {
        val base = """open class Base { open fun value() = "fail" }"""
        val middle = """open class Middle : Base() { override fun value() = "OK" }"""
        val main = """
            class Last : Middle()

            fun box(): String {
                val base: Base = Last()
                return base.value()
            }
        """
        assertTrue(runBox(tempDir, listOf(mapOf("base.kt" to base), mapOf("middle.kt" to middle), mapOf("main.kt" to main))))
    }

    /** The JVM's `AssertionError(Object)` takes the object's `String.valueOf` as its message. */
    @Test
    fun testAssertionErrorFromAnObjectShowsIt(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val detail: Any? = 42
                val nothing: Any? = null
                val ok = AssertionError(detail).message == "42" && AssertionError(nothing).message == "null"
                return if (ok) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A throwable given to the JVM's `AssertionError(Object)` is its cause too. */
    @Test
    fun testAssertionErrorFromAThrowableHasItAsCause(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val cause = IllegalStateException("inner")
                val detail: Any? = cause
                val error = AssertionError(detail)
                return if (error.cause === cause && AssertionError("x" as Any?).cause == null) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLambdaCalledThroughAValue(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val next = { x: Int -> x + 1 }
                return if (next(41) == 42) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLambdaCapturesAValue(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val base = 40
                val add = { x: Int -> x + base }
                return if (add(2) == 42) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The lambda and its enclosing function share a captured `var`, through its box. */
    @Test
    fun testLambdaWritesACapturedVar(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var count = 0
                val increment = { count++ }
                increment()
                increment()
                return if (count == 2) "OK" else "fail: ${'$'}count"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLambdaSeesACapturedVarWrittenAfterCreation(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var result = "fail"
                val read = { result }
                result = "OK"
                return read()
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testNestedLambdaCapturesAnEnclosingLambdasVariable(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val outer = { prefix: String ->
                    val suffix = "K"
                    val inner = { prefix + suffix }
                    inner()
                }
                return outer("O")
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLambdaCapturesThis(@TempDir tempDir: File) {
        val source = """
            class Greeter(val name: String) {
                fun greeting(): () -> String = { name }
            }

            fun box(): String = Greeter("OK").greeting()()
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testReferenceToATopLevelFunction(@TempDir tempDir: File) {
        val source = """
            fun twice(x: Int) = x * 2

            fun box(): String {
                val f = ::twice
                return if (f(21) == 42) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The receiver is bound when the reference is made, and the value holds it. */
    @Test
    fun testBoundReference(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val prefix = "O"
                val append = prefix::plus
                return append("K")
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Through a generic caller the argument and result are boxed, as a caller knowing the types passes them. */
    @Test
    fun testGenericCallerPassesAPrimitiveThroughALambda(@TempDir tempDir: File) {
        val source = """
            fun <T> applyTo(f: (T) -> T, x: T): T = f(x)

            fun box(): String = if (applyTo({ x: Int -> x * 2 }, 21) == 42) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A lambda returning Unit still answers the call through the value, from a `return` as from its end. */
    @Test
    fun testLambdaReturningUnit(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var log = "fail"
                val record: (String) -> Unit = record@{
                    if (it == "no") return@record
                    log = it
                }
                record("no")
                record("OK")
                return log
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** As on the JVM, a field initialized to its default is left alone, so an `init` block running earlier keeps its write. */
    @Test
    fun testFieldInitializerOfTheDefaultValueIsSkipped(@TempDir tempDir: File) {
        val source = """
            class Counter(start: Int) {
                init { set(start) }
                fun set(value: Int) { count = value }
                var count: Int = 0
            }

            fun box(): String = if (Counter(1).count == 1) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A `val` a lambda called in place initializes lives in a box, as a captured `var` does. */
    @Test
    fun testLambdaInitializesACapturedVal(@TempDir tempDir: File) {
        val source = """
            import kotlin.contracts.*

            @OptIn(ExperimentalContracts::class)
            fun runOnce(action: () -> Unit) {
                contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
                action()
            }

            fun box(): String {
                val result: String
                runOnce { result = "OK" }
                return result
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLambdaInAnInitBlockWritesACapturedVar(@TempDir tempDir: File) {
        val source = """
            fun call(block: () -> Unit) = block()

            class Holder {
                val value: Int
                init {
                    var local = 1
                    call { local = 42 }
                    value = local
                }
            }

            fun box(): String = if (Holder().value == 42) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLocalFunction(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                fun ok() = "OK"
                return ok()
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLocalFunctionCapturesAValue(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val prefix = "O"
                fun complete(suffix: String) = prefix + suffix
                return complete("K")
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLocalFunctionWritesACapturedVar(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var count = 0
                fun increment() { count++ }
                increment()
                increment()
                return if (count == 2) "OK" else "fail: ${'$'}count"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testRecursiveLocalFunction(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)
                return if (fib(20) == 6765) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLocalClass(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                class Pair(val first: String, val second: String) {
                    fun joined() = first + second
                }
                return Pair("O", "K").joined()
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLocalClassCapturesAValue(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val suffix = "K"
                class Greeting(val prefix: String) {
                    fun text() = prefix + suffix
                }
                return Greeting("O").text()
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Two anonymous objects in one file are two classes, however they are named. */
    @Test
    fun testAnonymousObjectsImplementAnInterface(@TempDir tempDir: File) {
        val source = """
            interface Text { fun text(): String }

            fun o(): Text = object : Text { override fun text() = "O" }
            fun k(): Text = object : Text { override fun text() = "K" }

            fun box(): String = o().text() + k().text()
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnonymousObjectCapturesThis(@TempDir tempDir: File) {
        val source = """
            interface Text { fun text(): String }

            class Holder(val value: String) {
                fun asText(): Text = object : Text { override fun text() = value }
            }

            fun box(): String = Holder("OK").asText().text()
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A local class named as a class of the module is a class of its own, whichever file comes first. */
    @Test
    fun testLocalClassNamedAsAModuleClass(@TempDir tempDir: File) {
        val local = """
            fun local(): String {
                class Name { fun text() = "O" }
                return Name().text()
            }
        """
        val public = """
            class Name { fun text() = "K" }

            fun box(): String = local() + Name().text()
        """
        assertTrue(runBox(tempDir, listOf(mapOf("a.kt" to local, "b.kt" to public))))
    }

    /** A primitive `===` a reference is boxed first, so a cached box is identical to the value boxed again. */
    @Test
    fun testIdentityOfAPrimitiveAndABox(@TempDir tempDir: File) {
        val source = """
            fun same(a: Any) = a === (1 == 1)

            fun box(): String = if (same(true)) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testDefaultArgument(@TempDir tempDir: File) {
        val source = """
            fun greet(suffix: String = "K") = "O" + suffix

            fun box(): String = greet()
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testDefaultReadsAnEarlierParameter(@TempDir tempDir: File) {
        val source = """
            fun next(a: Int, b: Int = a + 1) = b

            fun box(): String = if (next(41) == 42) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testDefaultReadsThis(@TempDir tempDir: File) {
        val source = """
            class Greeter(val name: String) {
                fun greet(who: String = name) = who
            }

            fun box(): String = Greeter("OK").greet()
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testConstructorDefaultArgument(@TempDir tempDir: File) {
        val source = """
            class Box(val value: String = "OK")

            fun box(): String = Box().value
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testSuperConstructorCallLeavesOutAnArgument(@TempDir tempDir: File) {
        val source = """
            open class Base(val value: String = "OK")
            class Derived : Base()

            fun box(): String = Derived().value
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEnumEntryLeavesOutAnArgument(@TempDir tempDir: File) {
        val source = """
            enum class E(val value: String = "OK") { A }

            fun box(): String = E.A.value
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An override declares no default of its own, and a call to it gets the overridden declaration's. */
    @Test
    fun testOverrideTakesTheBaseDeclarationsDefault(@TempDir tempDir: File) {
        val source = """
            open class Base {
                open fun pick(x: String = "OK") = "fail"
            }

            class Derived : Base() {
                override fun pick(x: String) = x
            }

            fun box(): String {
                val base: Base = Derived()
                return if (base.pick() == "OK" && Derived().pick() == "OK") "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testInterfaceMethodDefault(@TempDir tempDir: File) {
        val source = """
            interface Picker {
                fun pick(x: String = "OK"): String
            }

            class Echo : Picker {
                override fun pick(x: String) = x
            }

            fun box(): String {
                val picker: Picker = Echo()
                return picker.pick()
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testDefaultArgumentOfAnotherModule(@TempDir tempDir: File) {
        val lib = """fun greet(suffix: String = "K") = "O" + suffix"""
        val main = """fun box(): String = greet()"""
        assertTrue(runBox(tempDir, listOf(mapOf("lib.kt" to lib), mapOf("main.kt" to main))))
    }

    /** A local class in a lambda an inline function takes is one class, whatever copies of the lambda inlining makes. */
    @Test
    fun testLocalClassInAnInlinedLambda(@TempDir tempDir: File) {
        val source = """
            inline fun <T> make(block: () -> T): T = block()

            fun box(): String {
                return make {
                    open class Base {
                        open fun text(suffix: String = "K") = "O" + suffix
                    }
                    class Derived : Base() {
                        override fun text(suffix: String) = super.text(suffix)
                    }
                    Derived()
                }.text()
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** 2^24 + 1 is no float, so adding 1 to 2^24 leaves it, where a double would not. */
    @Test
    fun testFloatArithmeticIsSinglePrecision(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val big = 16777216f
                return if (big + 1f == big && big.toDouble() + 1.0 != big.toDouble()) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A float prints with its own shortest digits, not those of the double it widens to. */
    @Test
    fun testFloatInATemplate(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val tenth = 0.1f
                val text = "${'$'}tenth"
                return if (text == "0.1") "OK" else "fail: ${'$'}text"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFloatToIntSaturates(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val huge = 1e20f
                val nan = 0f / 0f
                val ok = 2.75f.toInt() == 2 && huge.toInt() == Int.MAX_VALUE && (-huge).toInt() == Int.MIN_VALUE && nan.toInt() == 0
                return if (ok) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testDoubleToIntSaturates(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val huge = 1e20
                val ok = (-2.75).toInt() == -2 && huge.toInt() == Int.MAX_VALUE && (-huge).toInt() == Int.MIN_VALUE
                return if (ok) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Through a double, 2^53 + 2^29 + 1 would round twice and land on 2^53. */
    @Test
    fun testLongToFloatRoundsOnce(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val value = 9007199791611905L
                return if (value.toFloat() == 9.0071998E15f && value.toFloat() != 9007199254740992L.toFloat()) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFloatWidensAndDoubleNarrows(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val tenth = 0.1f
                val widened = tenth.toDouble()
                return if (widened != 0.1 && widened.toFloat() == tenth && 0.1.toFloat() == tenth) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testNegatingAFloatFlipsTheSignOfZero(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val zero = 0f
                return if (1f / -zero < 0f) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFloatRemainder(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val a = 5.5f
                val b = 2f
                return if (a % b == 1.5f && -a % b == -1.5f) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testNullableFloatsCompareAsIeee(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val zero: Float? = 0f
                val negative: Float? = -0f
                return if (zero == negative) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** As `Float.equals`: `0.0f` and `-0.0f` differ, and NaN equals itself. */
    @Test
    fun testBoxedFloatsEqualByBits(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val zero: Any = 0f
                val negative: Any = -0f
                val nan: Any = 0f / 0f
                val otherNan: Any = 0f / 0f
                return if (zero != negative && nan == otherNan) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testIntArrayHoldsWhatIsStored(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val numbers = IntArray(3)
                numbers[1] = -7
                return if (numbers[1] == -7 && numbers[0] == 0) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testArraySizeIsItsLength(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (LongArray(5).size == 5) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Four bytes an element, which a read gives back as the float's bits. */
    @Test
    fun testFloatArrayHoldsWhatIsStored(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val numbers = FloatArray(2)
                numbers[0] = -1.5f
                return if (numbers[0] == -1.5f) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testArrayOfHoldsItsElements(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val words = arrayOf("O", "K")
                return words[0] + words[1]
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An `Array<Int>` holds boxes, which a read unboxes. */
    @Test
    fun testArrayOfPrimitivesHoldsBoxes(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val numbers: Array<Int> = arrayOf(1, 2)
                numbers[1] = numbers[0] + 40
                return if (numbers[1] == 41) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testPrimitiveArrayOfHoldsItsElements(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val chars = charArrayOf('O', 'K')
                return "" + chars[0] + chars[1]
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testArrayOfNullsHoldsNulls(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val words = arrayOfNulls<String>(2)
                return if (words.size == 2 && words[1] == null) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `Array(size) { … }` runs its lambda for each index in turn; see `ArrayConstructorLowering`. */
    @Test
    fun testArrayConstructorFillsEachIndex(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val squares = IntArray(4) { it * it }
                return if (squares[3] == 9 && squares[1] == 1) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testVarargArrivesAsAnArray(@TempDir tempDir: File) {
        val source = """
            fun sum(vararg numbers: Int): Int {
                var total = 0
                var i = 0
                while (i < numbers.size) {
                    total += numbers[i]
                    i++
                }
                return total
            }

            fun box(): String = if (sum(1, 2, 3) == 6) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A spread array's elements take their place among the others, in order. */
    @Test
    fun testSpreadCopiesItsElements(@TempDir tempDir: File) {
        val source = """
            fun join(vararg words: String): String {
                var result = ""
                var i = 0
                while (i < words.size) {
                    result += words[i]
                    i++
                }
                return result
            }

            fun box(): String {
                val middle = arrayOf("b", "c")
                return if (join("a", *middle, "d") == "abcd") "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testLeftOutVarargIsEmpty(@TempDir tempDir: File) {
        val source = """
            class Items(vararg val items: Int)

            fun box(): String = if (Items().items.size == 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testTypeTestTellsArraysApart(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val flags: Any = BooleanArray(1)
                return if (flags is BooleanArray && flags !is ByteArray) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An `Array<String>` is an `Array<Any>`, as the JVM's arrays are covariant. */
    @Test
    fun testArrayOfSubclassIsArrayOfSuperclass(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val words: Any = arrayOf("a")
                return if (words is Array<*> && (words as Array<Any>).size == 1) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** As the JVM names an `int[]`. */
    @Test
    fun testArrayToStringIsItsJvmNameAndHash(@TempDir tempDir: File) {
        val source = """
            fun hex(value: Int): String {
                val digits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
                var rest = value.toLong() and 0xFFFFFFFFL
                var text = ""
                do {
                    text = "" + digits[(rest and 15L).toInt()] + text
                    rest = rest shr 4
                } while (rest != 0L)
                return text
            }

            fun box(): String {
                val numbers = IntArray(1)
                return if (numbers.toString() == "[I@" + hex(numbers.hashCode())) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A `for` over an array counts its index rather than calling an iterator; see `ForLoopsLowering`. */
    @Test
    fun testForOverPrimitiveArrayVisitsEachElement(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var sum = 0
                for (n in intArrayOf(1, 2, 3)) sum += n
                return if (sum == 6) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testForOverArrayVisitsElementsInOrder(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var text = ""
                for (word in arrayOf("O", "K")) text += word
                return text
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testForOverClosedRangeIncludesItsEnd(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var sum = 0
                for (i in 1..4) sum += i
                return if (sum == 10) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testForUntilExcludesItsEnd(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var sum = 0
                for (i in 0 until 4) sum += i
                return if (sum == 6) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testSeededRandomRepeatsItsSequence(@TempDir tempDir: File) {
        val source = """
            import kotlin.random.Random

            fun box(): String = if (Random(7).nextInt() == Random(7).nextInt()) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The default random is seeded by the VM's `vm.builtin.random.seed`. */
    @Test
    fun testDefaultRandomStaysInItsBound(@TempDir tempDir: File) {
        val source = """
            import kotlin.random.Random

            fun box(): String {
                val n = Random.nextInt(10)
                return if (n >= 0 && n < 10) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testDownToCountsDown(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var text = ""
                for (i in 3 downTo 1) text += i
                return if (text == "321") "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testStepSkipsBetweenValues(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var sum = 0
                for (i in 0..10 step 5) sum += i
                return if (sum == 15) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `in` on a range object calls its `contains`, in the standard library's VM module. */
    @Test
    fun testRangeContainsItsValues(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val range = 1..10
                return if (5 in range && 11 !in range) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCoerceInClampsToTheBounds(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (15.coerceIn(0, 10) == 10 && (-3).coerceIn(0, 10) == 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A lambda is a `vm.builtin.Function`, so an array of function types holds them. */
    @Test
    fun testArrayOfLambdasHoldsThem(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val answers = Array(2) { i -> { i + 40 } }
                return if (answers[1]() == 41) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testIntToStringIsItsDecimalDigits(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if ((-42).toString() == "-42") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Every `append` of one value appends its text, a `Char` its one unit. */
    @Test
    fun testStringBuilderAppendsEachValue(@TempDir tempDir: File) {
        val source = """
            fun box(): String = StringBuilder().append("O").append('K').toString()
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `appendLine` is inline in the standard library, so its `append`s land in the program. */
    @Test
    fun testAppendLineEndsTheLine(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val text = StringBuilder().appendLine("a").toString()
                return if (text == "a\n") "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `println` writes to the VM's output, through the runtime's `vm.builtin.io.print`. */
    @Test
    fun testPrintlnWritesALine(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                println("hello from the VM")
                return "OK"
            }
        """
        assertTrue(runOutput(tempDir, listOf(mapOf("box.kt" to source))).lines().contains("hello from the VM"))
    }

    /** An inner class keeps its outer instance, which its members read the outer class's state through. */
    @Test
    fun testInnerClassReadsItsOuterInstance(@TempDir tempDir: File) {
        val source = """
            class Outer(val text: String) {
                inner class Inner {
                    fun read() = text
                }
            }

            fun box(): String = Outer("OK").Inner().read()
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testInnerClassSecondaryConstructorTakesTheOuterInstance(@TempDir tempDir: File) {
        val source = """
            class Outer(val prefix: String) {
                inner class Inner(val suffix: String) {
                    constructor() : this("K")
                    fun read() = prefix + suffix
                }
            }

            fun box(): String = Outer("O").Inner().read()
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A lambda converted to a `fun interface` is an instance of it, which calls the lambda. */
    @Test
    fun testLambdaConvertsToAFunInterface(@TempDir tempDir: File) {
        val source = """
            fun interface Transform {
                fun apply(value: Int): Int
            }

            fun twice(transform: Transform, value: Int) = transform.apply(transform.apply(value))

            fun box(): String = if (twice({ it + 20 }, 1) == 41) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A function value in a variable converts too, through the constructor-like call. */
    @Test
    fun testFunctionValueConvertsToAFunInterface(@TempDir tempDir: File) {
        val source = """
            fun interface Transform {
                fun apply(value: Int): Int
            }

            fun box(): String {
                val add: (Int) -> Int = { it + 1 }
                val transform = Transform(add)
                return if (transform.apply(40) == 41) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `compareTo` orders the two zeros, which `<` does not tell apart. */
    @Test
    fun testDoubleCompareToOrdersNegativeZeroFirst(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if ((-0.0).compareTo(0.0) < 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `compareTo` ranks NaN above every other value, even though no IEEE comparison ranks it. */
    @Test
    fun testDoubleCompareToRanksNaNAboveInfinity(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (Double.NaN.compareTo(Double.POSITIVE_INFINITY) > 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A float's bits are sign-extended before they are compared, so a negative float's bits order as negative. */
    @Test
    fun testFloatCompareToOrdersNegativeZeroFirst(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if ((-0.0f).compareTo(0.0f) < 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFloatToRawBitsOfANegativeFloatIsNegative(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if ((-1.0f).toRawBits() == -1082130432) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `copyInto` copies through the VM's `vm.builtin.array.copy`. */
    @Test
    fun testCopyIntoCopiesARangeToAnOffset(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val to = intArrayOf(1, 2, 3, 4).copyInto(IntArray(4), destinationOffset = 1, startIndex = 1, endIndex = 3)
                return if (to.contentEquals(intArrayOf(0, 2, 3, 0))) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testMutableListGrowsAsItIsAddedTo(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val list = mutableListOf<Int>()
                for (i in 0 until 100) list.add(i)
                return if (list.size == 100 && list[99] == 99) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testForLoopIteratesAList(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var text = ""
                for (s in listOf("O", "K")) text += s
                return text
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testHashMapFindsWhatWasPut(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val map = hashMapOf("a" to 1, "b" to 2)
                map["c"] = 3
                return if (map["b"] == 2 && map["c"] == 3 && map["d"] == null) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A `Comparator` is the runtime's `vm.runtime.Comparator`, which `sortedWith` calls through its `compare`. */
    @Test
    fun testSortedWithOrdersByTheComparator(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val sorted = listOf(3, 1, 2).sortedWith(Comparator { a, b -> b - a })
                return if (sorted == listOf(3, 2, 1)) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Changing a list while iterating it raises the runtime's `java.util.ConcurrentModificationException`. */
    @Test
    fun testModifyingAListWhileIteratingItThrows(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val list = mutableListOf(1, 2)
                try {
                    for (i in list) list.add(i)
                } catch (e: ConcurrentModificationException) {
                    return "OK"
                }
                return "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `Unit` passed where a value is expected still defines the argument's register, which the allocator requires. */
    @Test
    fun testUnitPassesAsAGenericArgument(@TempDir tempDir: File) {
        val source = """
            fun <T> one(value: T): Int = 1

            fun box(): String = if (one(Unit) == 1) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A variable of type `Unit` holds the standard library's object, which a call returning nothing leaves to be loaded. */
    @Test
    fun testUnitVariableHoldsTheUnitObject(@TempDir tempDir: File) {
        val source = """
            fun nothing() {}

            fun box(): String {
                val unit = nothing()
                val value: Any? = unit
                return if (value is Unit) "OK" else "fail: " + value
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testUnitValueIsAUnit(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val value: Any = Unit
                return if (value is Unit) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testUnitEqualsTheResultOfACall(@TempDir tempDir: File) {
        val source = """
            fun nothing() {}

            fun sameValue(a: Any?, b: Any?) = a != null && a == b

            fun box(): String = if (sameValue(nothing(), Unit)) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testUnitInAStringTemplate(@TempDir tempDir: File) {
        val source = """
            fun nothing() {}

            fun box(): String = if ("[${'$'}{nothing()}]" == "[kotlin.Unit]") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCastToUnitRejectsAnotherValue(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val value: Any = "text"
                try {
                    value as Unit
                } catch (e: ClassCastException) {
                    return "OK"
                }
                return "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** As on the JVM, whose `invoke` is erased, a function value returning Unit answers the `Unit` object to a generic caller. */
    @Test
    fun testFunctionValueReturningUnitAnswersTheUnitObject(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val noop: () -> Unit = {}
                @Suppress("UNCHECKED_CAST")
                val result = (noop as () -> Any?)()
                return if (result is Unit) "OK" else "fail: " + result
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A Unit function overriding a generic one answers the `Unit` object, which the generic caller reads as a value. */
    @Test
    fun testUnitOverrideOfAGenericFunctionAnswersTheUnitObject(@TempDir tempDir: File) {
        val source = """
            interface Source<T> {
                fun next(): T
            }

            class Quiet : Source<Unit> {
                override fun next() {}
            }

            fun <T> take(source: Source<T>): Any? = source.next()

            fun box(): String = if (take(Quiet()) === Unit) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testFieldOfTypeUnit(@TempDir tempDir: File) {
        val source = """
            class Holder {
                val unit: Unit = Unit
            }

            fun box(): String = if (Holder().unit === Unit) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testTopLevelPropertyOfTypeUnit(@TempDir tempDir: File) {
        val source = """
            val unit: Unit = Unit

            fun box(): String = if (unit === Unit) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A generic call answering null, typed as a primitive, is only unboxed where a primitive's slot takes it. */
    @Test
    fun testNullFromAGenericCallIsStoredToANullablePrimitive(@TempDir tempDir: File) {
        val source = """
            @Suppress("UNCHECKED_CAST")
            fun <T> nothing(): T = null as T

            fun box(): String {
                val value: Int? = nothing<Int>()
                return if (value == null) "OK" else "fail: " + value
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testNullFromAGenericCallInABranchIsStoredToANullablePrimitive(@TempDir tempDir: File) {
        val source = """
            @Suppress("UNCHECKED_CAST")
            fun <T> nothing(): T = null as T

            var flag = true

            fun box(): String {
                val value: Int? = if (flag) nothing<Int>() else 0
                return if (value == null) "OK" else "fail: " + value
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testNullFromAGenericCallIsReturnedFromAnInlineFunctionAsANullablePrimitive(@TempDir tempDir: File) {
        val source = """
            @Suppress("UNCHECKED_CAST")
            fun <T> nothing(): T = null as T

            inline fun nullableInt(): Int? = nothing<Int>()

            fun box(): String = if (nullableInt() == null) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An array's `iterator()` is the VM standard library's `arrayIterator`, the builtin class having no body. */
    @Test
    fun testArrayIteratorWalksTheArray(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val iterator = arrayOf("O", "K").iterator()
                var text = ""
                while (iterator.hasNext()) text += iterator.next()
                return text
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testIntArrayIteratorWalksTheArray(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val iterator = intArrayOf(2, 3).iterator()
                var product = 1
                while (iterator.hasNext()) product *= iterator.nextInt()
                return if (product == 6) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testMutableListOfHoldsItsElements(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val list = mutableListOf("O", "K")
                return list[0] + list[1]
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The loops lowering checks a `step` it cannot see is positive, throwing what `step` itself would. */
    @Test
    fun testNonPositiveStepThrowsIllegalArgument(@TempDir tempDir: File) {
        val source = """
            fun sum(step: Int): Int {
                var sum = 0
                for (i in 0..10 step step) sum += i
                return sum
            }

            fun box(): String {
                try {
                    sum(0)
                } catch (e: IllegalArgumentException) {
                    return "OK"
                }
                return "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `String`'s members are the runtime's, which answers them through the selectors they override. */
    @Test
    fun testStringLengthOfAStringValue(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val s: String = "abc"
                return if (s.length == 3) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testRangeUntilExcludesItsEnd(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val range = 0..<3
                return if (range.last == 2) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `equals` on a `Double` is its box's, which compares bits, so NaN equals itself. */
    @Test
    fun testDoubleEqualsHoldsForNaN(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (Double.NaN.equals(Double.NaN)) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A value class over a primitive is held as the primitive, its members taking the value itself. */
    @Test
    fun testValueClassMemberReadsItsValue(@TempDir tempDir: File) {
        val source = """
            @JvmInline
            value class Meters(val value: Int) {
                operator fun plus(other: Meters) = Meters(value + other.value)
            }

            fun box(): String = if ((Meters(1) + Meters(2)).value == 3) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Where a reference is expected, a value class held unboxed is boxed in its class, which a type test sees. */
    @Test
    fun testBoxedValueClassIsAnInstanceOfItsClass(@TempDir tempDir: File) {
        val source = """
            @JvmInline
            value class Meters(val value: Int)

            fun box(): String {
                val boxed: Any = Meters(1)
                return if (boxed is Meters && boxed.value == 1) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A value class of the standard library's, whose members another module names by the member they were made from. */
    @Test
    fun testUIntArithmeticWrapsUnsigned(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if ((0u - 1u).toString() == "4294967295") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testListOfValueClassesHoldsBoxes(@TempDir tempDir: File) {
        val source = """
            @JvmInline
            value class Meters(val value: Int)

            fun box(): String = if (listOf(Meters(4), Meters(2)).sumOf { it.value } == 6) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `as T` checks T's erasure, as the JVM's does; T's bound is nullable, so null passes. */
    @Test
    fun testCastToATypeParameterLetsNullThrough(@TempDir tempDir: File) {
        val source = """
            @Suppress("UNCHECKED_CAST")
            fun <T> cast(value: Any?): T = value as T

            fun box(): String = if (cast<String?>(null) == null) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCastToABoundedTypeParameterChecksTheBound(@TempDir tempDir: File) {
        val source = """
            @Suppress("UNCHECKED_CAST")
            fun <T : Number> cast(value: Any): T = value as T

            fun box(): String {
                try {
                    cast<Int>("not a number")
                } catch (e: ClassCastException) {
                    return "OK"
                }
                return "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testToStringOfNullIsNull(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val value: Any? = null
                return if (value.toString() == "null") "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A data class hashes an array property by its content, through the standard library's `contentHashCode`. */
    @Test
    fun testDataClassHashesAnArraysContent(@TempDir tempDir: File) {
        val source = """
            data class Data(val values: IntArray)

            fun box(): String =
                if (Data(intArrayOf(1, 2)).hashCode() == Data(intArrayOf(1, 2)).hashCode()) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The loop over unsigned values calls `UInt.toInt`, inline only, after inlining: the standard library has its body. */
    @Test
    fun testForOverAnUnsignedRangeVisitsEachValue(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                var sum = 0u
                for (i in 1u..3u) sum += i
                return if (sum == 6u) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `joinToString` appends to the standard library's `StringBuilder`, now Wasm and Native's Kotlin class. */
    @Test
    fun testJoinToStringSeparatesTheElements(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (listOf(1, 2, 3).joinToString(", ") == "1, 2, 3") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testTrimIndentRemovesTheCommonIndent(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if ("\n    a\n      b\n".trimIndent() == "a\n  b") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testStringBuilderInsertsAtAnIndex(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (StringBuilder("OK").insert(1, "-").toString() == "O-K") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testUppercaseMapsEachChar(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if ("ok".uppercase() == "OK") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testToIntParsesDecimalDigits(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if ("-42".toInt() == -42) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** Wasm and Native's regex engine, in Kotlin. */
    @Test
    fun testRegexMatchesAPattern(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (Regex("a+b").matches("aaab")) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `sqrt` is Wasm's, over the VM's `vm.builtin.f64.sqrt`. */
    @Test
    fun testSqrtIsTheSquareRoot(@TempDir tempDir: File) {
        val source = """
            import kotlin.math.sqrt

            fun box(): String = if (sqrt(2.25) == 1.5) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A data class's `toString` shows an array property's content, through the standard library's `contentToString`. */
    @Test
    fun testDataClassToStringShowsAnArraysContent(@TempDir tempDir: File) {
        val source = """
            data class Data(val values: IntArray)

            fun box(): String = if (Data(intArrayOf(1, 2)).toString() == "Data(values=[1, 2])") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An enum class's `values()`, whose body the frontend leaves to the backend, is an array of its entries. */
    @Test
    fun testEnumValuesListsTheEntriesInOrder(@TempDir tempDir: File) {
        val source = """
            enum class E { A, B }

            fun box(): String = if (E.values().joinToString("") { it.name } == "AB") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEnumEntriesListsTheEntriesInOrder(@TempDir tempDir: File) {
        val source = """
            enum class E { A, B }

            fun box(): String = if (E.entries.map { it.name } == listOf("A", "B")) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEnumValueOfFindsTheEntryByName(@TempDir tempDir: File) {
        val source = """
            enum class E { A, B }

            fun box(): String = if (E.valueOf("B") == E.B) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEnumValueOfAnUnknownNameThrows(@TempDir tempDir: File) {
        val source = """
            enum class E { A }

            fun box(): String {
                try {
                    E.valueOf("C")
                } catch (e: IllegalArgumentException) {
                    return "OK"
                }
                return "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The entry being constructed is not stored yet, so its body reaches itself through `this`, from an inner class too. */
    @Test
    fun testEnumEntryReachesItselfFromAnInnerClassWhileConstructed(@TempDir tempDir: File) {
        val source = """
            enum class Test {
                FOO {
                    inner class Inner {
                        val entry = FOO
                    }

                    val inner = Inner()

                    override fun reachesItself() = inner.entry === this
                };

                abstract fun reachesItself(): Boolean
            }

            fun box(): String = if (Test.FOO.reachesItself()) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `String.Companion` has no state, so an extension of it takes nothing for it. */
    @Test
    fun testExtensionOfStringsCompanionSortsIgnoringCase(@TempDir tempDir: File) {
        val source = """
            fun box(): String =
                if (listOf("b", "A").sortedWith(String.CASE_INSENSITIVE_ORDER) == listOf("A", "b")) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A vararg of an unsigned type is its array class, which wraps the signed array holding the elements. */
    @Test
    fun testUnsignedArrayOfHoldsItsElements(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (ulongArrayOf(1uL, 2uL)[1] == 2uL) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An unsigned value above `Long.MAX_VALUE` in another radix, whose digits the VM's `ulongToString` writes. */
    @Test
    fun testULongToStringInARadix(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (ULong.MAX_VALUE.toString(16) == "ffffffffffffffff") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A `sequence` block suspends at each `yield`, which the iterator's `next` resumes. */
    @Test
    fun testSequenceYieldsItsValuesInOrder(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val values = sequence {
                    yield(1)
                    yield(2)
                    yield(3)
                }.toList()
                return if (values == listOf(1, 2, 3)) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `suspendCoroutine` parks the coroutine until its continuation, which the code outside holds, is resumed. */
    @Test
    fun testSuspendCoroutineResumesWithTheValueGivenLater(@TempDir tempDir: File) {
        val source = """
            import kotlin.coroutines.*

            fun box(): String {
                var parked: Continuation<Int>? = null
                var result = 0
                val block: suspend () -> Unit = {
                    result = suspendCoroutine { parked = it }
                }
                block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
                if (result != 0) return "fail: resumed early"
                parked!!.resume(42)
                return if (result == 42) "OK" else "fail: " + result
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A coroutine resumed with an exception gets it at its suspension point, where a `catch` can take it. */
    @Test
    fun testResumeWithExceptionReachesTheCatchAroundTheSuspension(@TempDir tempDir: File) {
        val source = """
            import kotlin.coroutines.*

            fun box(): String {
                var parked: Continuation<Int>? = null
                var caught: String? = null
                val block: suspend () -> Unit = {
                    try {
                        suspendCoroutine<Int> { parked = it }
                    } catch (e: IllegalStateException) {
                        caught = e.message
                    }
                }
                block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
                parked!!.resumeWithException(IllegalStateException("boom"))
                return if (caught == "boom") "OK" else "fail: " + caught
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `coroutineContext` is the context the coroutine was started with. */
    @Test
    fun testCoroutineContextIsTheOneTheCoroutineStartedWith(@TempDir tempDir: File) {
        val source = """
            import kotlin.coroutines.*

            class Tag : AbstractCoroutineContextElement(Key) {
                companion object Key : CoroutineContext.Key<Tag>
            }

            fun box(): String {
                val tag = Tag()
                var seen: Tag? = null
                val block: suspend () -> Unit = { seen = coroutineContext[Tag] }
                block.startCoroutine(Continuation(tag) { it.getOrThrow() })
                return if (seen === tag) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A suspend lambda with a receiver runs on the receiver `startCoroutine` is given. */
    @Test
    fun testStartCoroutineOfALambdaWithAReceiverPassesTheReceiver(@TempDir tempDir: File) {
        val source = """
            import kotlin.coroutines.*

            fun box(): String {
                var result = ""
                val block: suspend String.() -> Unit = { result = this + "K" }
                block.startCoroutine("O", Continuation(EmptyCoroutineContext) { it.getOrThrow() })
                return result
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A suspend function that does not suspend is a call answering its value. */
    @Test
    fun testSuspendFunctionCalledFromALambdaAnswersItsValue(@TempDir tempDir: File) {
        val source = """
            import kotlin.coroutines.*

            suspend fun answer(): Int = 42

            fun box(): String {
                var result = 0
                val block: suspend () -> Unit = { result = answer() }
                block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
                return if (result == 42) "OK" else "fail: " + result
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An exception a coroutine does not catch is what its completion is resumed with. */
    @Test
    fun testCompletionReceivesTheExceptionTheCoroutineThrows(@TempDir tempDir: File) {
        val source = """
            import kotlin.coroutines.*

            fun box(): String {
                var failure: Throwable? = null
                val block: suspend () -> Unit = { throw IllegalStateException("boom") }
                block.startCoroutine(Continuation(EmptyCoroutineContext) { failure = it.exceptionOrNull() })
                return if (failure?.message == "boom") "OK" else "fail: " + failure
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A block that resumes the coroutine and then answers `COROUTINE_SUSPENDED` suspends the one starting it, as a state machine does. */
    @Test
    fun testStartingACoroutineWhoseBlockResumedItSuspendsTheStarter(@TempDir tempDir: File) {
        val source = """
            import kotlin.coroutines.*
            import kotlin.coroutines.intrinsics.*

            suspend fun resumedHere(): String = suspendCoroutineUninterceptedOrReturn { continuation ->
                continuation.resume("OK")
                COROUTINE_SUSPENDED
            }

            fun box(): String {
                val block: suspend () -> String = { resumedHere() }
                val result = block.startCoroutineUninterceptedOrReturn(Continuation(EmptyCoroutineContext) { })
                return if (result === COROUTINE_SUSPENDED) "OK" else "fail: " + result
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCompletionReceivesTheResultOfACoroutineWhoseBlockResumedItEarly(@TempDir tempDir: File) {
        val source = """
            import kotlin.coroutines.*
            import kotlin.coroutines.intrinsics.*

            suspend fun resumedHere(): String = suspendCoroutineUninterceptedOrReturn { continuation ->
                continuation.resume("OK")
                COROUTINE_SUSPENDED
            }

            fun box(): String {
                var completed: String? = null
                val block: suspend () -> String = { resumedHere() }
                block.startCoroutineUninterceptedOrReturn(Continuation(EmptyCoroutineContext) { completed = it.getOrThrow() })
                return completed ?: "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testResumedCoroutineContinuesPastABlockThatResumedItEarly(@TempDir tempDir: File) {
        val source = """
            import kotlin.coroutines.*
            import kotlin.coroutines.intrinsics.*

            suspend fun resumedHere(): String = suspendCoroutineUninterceptedOrReturn { continuation ->
                continuation.resume("OK")
                COROUTINE_SUSPENDED
            }

            fun box(): String {
                var parked: Continuation<Unit>? = null
                var completed: String? = null
                val block: suspend () -> String = {
                    suspendCoroutine<Unit> { parked = it }
                    resumedHere()
                }
                block.startCoroutine(Continuation(EmptyCoroutineContext) { completed = it.getOrThrow() })
                parked!!.resume(Unit)
                return completed ?: "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An array index of a type parameter bounded by `Int` is held boxed, and unboxed to read the element. */
    @Test
    fun testArrayReadAtAnIndexOfABoundedTypeParameter(@TempDir tempDir: File) {
        val source = """
            fun <T : Int> at(xs: IntArray, i: T): Int = xs[i]

            fun box(): String = if (at(intArrayOf(5, 7), 1) == 7) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An array index of a type parameter bounded by `Int` is held boxed, and unboxed to write the element. */
    @Test
    fun testArrayWriteAtAnIndexOfABoundedTypeParameter(@TempDir tempDir: File) {
        val source = """
            fun <T : Int> put(xs: IntArray, i: T) {
                xs[i] = 9
            }

            fun box(): String {
                val xs = intArrayOf(5, 7)
                put(xs, 1)
                return if (xs[1] == 9) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A data class compares its `Double` components as `Double.equals` does, under which NaN equals itself. */
    @Test
    fun testDataClassOfDoublesEqualsNaN(@TempDir tempDir: File) {
        val source = """
            data class A(val x: Double)

            fun box(): String {
                val zero = 0.0
                return if (A(zero / zero) == A(zero / zero)) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A data class compares its `Double` components as `Double.equals` does, under which `0.0` is not `-0.0`. */
    @Test
    fun testDataClassOfDoublesDistinguishesSignedZeros(@TempDir tempDir: File) {
        val source = """
            data class A(val x: Double)

            fun box(): String = if (A(0.0) != A(-0.0)) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A data class compares its `Float` components as `Float.equals` does, under which NaN equals itself. */
    @Test
    fun testDataClassOfFloatsEqualsNaN(@TempDir tempDir: File) {
        val source = """
            data class A(val x: Float)

            fun box(): String {
                val zero = 0f
                return if (A(zero / zero) == A(zero / zero)) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A data class compares its `Float` components as `Float.equals` does, under which `0.0f` is not `-0.0f`. */
    @Test
    fun testDataClassOfFloatsDistinguishesSignedZeros(@TempDir tempDir: File) {
        val source = """
            data class A(val x: Float)

            fun box(): String = if (A(0f) != A(-0f)) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A data class compares its `Double?` components as `Double.equals` does, under which NaN equals itself. */
    @Test
    fun testDataClassOfNullableDoublesEqualsNaN(@TempDir tempDir: File) {
        val source = """
            data class A(val x: Double?)

            fun box(): String {
                val zero = 0.0
                return if (A(zero / zero) == A(zero / zero)) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `..` on a `Byte` held boxed takes its bound as a `Byte`, not as the `Int` of the range it makes. */
    @Test
    fun testRangeToOnABoxedByte(@TempDir tempDir: File) {
        val source = """
            fun <T : Byte> outside(a: T?, b: T?, c: T): Boolean = a != null && b != null && c !in a..b

            fun box(): String = if (outside(1.toByte(), 2.toByte(), 0.toByte())) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A spread `UIntArray` copies the elements of the array its box wraps. */
    @Test
    fun testSpreadOfAnUIntArrayIntoAVararg(@TempDir tempDir: File) {
        val source = """
            fun second(vararg values: UInt): UInt = values[1]

            fun box(): String = if (second(*uintArrayOf(1u, 2u, 3u)) == 2u) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A string of constants only formats an unsigned literal as unsigned, though it is held as a signed `Long`. */
    @Test
    fun testUnsignedLongLiteralInAStringOfConstants(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if ("${'$'}{0xFEDCBA9876543210UL}" == "18364758544493064720") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A constant piece of a string with computed ones formats an unsigned literal as unsigned. */
    @Test
    fun testUnsignedLongLiteralInAStringWithAComputedPiece(@TempDir tempDir: File) {
        val source = """
            fun box(): String {
                val n = 1
                return if ("${'$'}n ${'$'}{0xFEDCBA9876543210UL}" == "1 18364758544493064720") "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** An unsigned constant narrower than `Long` formats without its sign extension. */
    @Test
    fun testUnsignedByteConstantInAString(@TempDir tempDir: File) {
        val source = """
            const val MAX: UByte = 0xFFu

            fun box(): String = if ("${'$'}MAX" == "255") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The name of a literal enum entry is a constant, so reading it does not run the enum's initializer. */
    @Test
    fun testEnumNameOfALiteralEntryDoesNotInitializeTheEnum(@TempDir tempDir: File) {
        val source = """
            var result = "OK"

            enum class E {
                A;

                init {
                    result = "fail"
                }
            }

            fun box(): String {
                if (E.A.name != "A") return "fail name"
                return result
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnnotationClassCanBeDeclared(@TempDir tempDir: File) {
        val source = """
            annotation class Marker

            fun box(): String = "OK"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testClassNestedInAnnotationClassCanBeUsed(@TempDir tempDir: File) {
        val source = """
            annotation class Anno {
                class Inner {
                    fun ok(): String = "OK"
                }
            }

            fun box(): String = Anno.Inner().ok()
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testCompanionOfAnnotationClassCanBeUsed(@TempDir tempDir: File) {
        val source = """
            annotation class Anno {
                companion object {
                    fun ok(): String = "OK"
                }
            }

            fun box(): String = Anno.ok()
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testInstantiatedAnnotationReadsItsMember(@TempDir tempDir: File) {
        val source = """
            annotation class A(val x: Int)

            fun box(): String = if (A(42).x == 42) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testInstantiatedAnnotationReadsItsDefaultMember(@TempDir tempDir: File) {
        val source = """
            annotation class A(val x: Int = 7)

            fun box(): String = if (A().x == 7) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnnotationInstancesWithEqualMembersAreEqual(@TempDir tempDir: File) {
        val source = """
            annotation class A(val x: Int, val s: String)

            fun box(): String = if (A(1, "a") == A(1, "a")) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnnotationInstancesWithDifferentMembersAreNotEqual(@TempDir tempDir: File) {
        val source = """
            annotation class A(val x: Int)

            fun box(): String = if (A(1) != A(2)) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnnotationInstanceIsNotEqualToAnotherAnnotation(@TempDir tempDir: File) {
        val source = """
            annotation class A(val x: Int)
            annotation class B(val x: Int)

            fun box(): String = if ((A(1) as Any) != (B(1) as Any)) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }


    /** Array members are compared by content, not by identity. */
    @Test
    fun testAnnotationInstancesWithEqualArraysAreEqual(@TempDir tempDir: File) {
        val source = """
            annotation class A(val xs: IntArray)

            fun box(): String = if (A(intArrayOf(1, 2)) == A(intArrayOf(1, 2))) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnnotationInstancesWithDifferentArraysAreNotEqual(@TempDir tempDir: File) {
        val source = """
            annotation class A(val xs: IntArray)

            fun box(): String = if (A(intArrayOf(1, 2)) != A(intArrayOf(1, 3))) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnnotationInstancesWithEqualReferenceArraysAreEqual(@TempDir tempDir: File) {
        val source = """
            annotation class A(val xs: Array<String>)

            fun box(): String = if (A(arrayOf("a", "b")) == A(arrayOf("a", "b"))) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEqualAnnotationInstancesHaveEqualHashCodes(@TempDir tempDir: File) {
        val source = """
            annotation class A(val x: Int, val s: String)

            fun box(): String = if (A(1, "a").hashCode() == A(1, "a").hashCode()) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testEqualAnnotationInstancesWithArraysHaveEqualHashCodes(@TempDir tempDir: File) {
        val source = """
            annotation class A(val xs: IntArray)

            fun box(): String = if (A(intArrayOf(1, 2)).hashCode() == A(intArrayOf(1, 2)).hashCode()) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }


    /** The `java.lang.annotation.Annotation` contract: the sum over the members of 127 times the name's hash code, xor the value's. */
    @Test
    fun testAnnotationHashCodeIsTheSumOfItsMembersHashCodes(@TempDir tempDir: File) {
        val source = """
            annotation class A(val x: Int, val s: String)

            fun box(): String {
                val expected = ((127 * "x".hashCode()) xor 5) + ((127 * "s".hashCode()) xor "a".hashCode())
                return if (A(5, "a").hashCode() == expected) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnnotationToStringNamesItsMembers(@TempDir tempDir: File) {
        val source = """
            annotation class A(val x: Int, val s: String)

            fun box(): String = if (A(1, "a").toString() == "@A(x=1, s=a)") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnnotationToStringShowsAnArrayMembersElements(@TempDir tempDir: File) {
        val source = """
            annotation class A(val xs: IntArray)

            fun box(): String = if (A(intArrayOf(1, 2)).toString() == "@A(xs=[1, 2])") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    @Test
    fun testAnnotationToStringOfNestedAnnotation(@TempDir tempDir: File) {
        val source = """
            annotation class Inner(val x: Int)
            annotation class Outer(val inner: Inner)

            fun box(): String = if (Outer(Inner(1)).toString() == "@Outer(inner=@Inner(x=1))") "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `intArrayOf()` with no elements is an empty array. */
    @Test
    fun testIntArrayOfNothingIsEmpty(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (intArrayOf().size == 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** `arrayOf()` with no elements is an empty array. */
    @Test
    fun testArrayOfNothingIsEmpty(@TempDir tempDir: File) {
        val source = """
            fun box(): String = if (arrayOf<String>().size == 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A call leaving out a vararg of an unsigned type passes an empty array of it. */
    @Test
    fun testLeftOutUnsignedVarargIsEmpty(@TempDir tempDir: File) {
        val source = """
            fun count(vararg xs: UInt): Int = xs.size

            fun box(): String = if (count() == 0) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A class with a field of type `Nothing` constructs until the field's initializer throws. */
    @Test
    fun testFieldOfTypeNothing(@TempDir tempDir: File) {
        val source = """
            class Never {
                val never: Nothing = throw IllegalStateException("never")
            }

            fun box(): String = try {
                Never()
                "fail"
            } catch (e: IllegalStateException) {
                "OK"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A local of a type parameter that inlining substitutes with `Nothing` has a slot. */
    @Test
    fun testLocalOfTypeNothingFromInlining(@TempDir tempDir: File) {
        val source = """
            inline fun <T> evaluate(f: () -> T): T {
                val result = f()
                return result
            }

            fun box(): String = try {
                evaluate { throw IllegalStateException("never") }
            } catch (e: IllegalStateException) {
                "OK"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A safe call of an extension on `Nothing` on `null` keeps its receiver in a slot, and answers null. */
    @Test
    fun testSafeCallOfAnExtensionOnNothing(@TempDir tempDir: File) {
        val source = """
            fun Nothing.ignored(): String = "fail"

            fun box(): String = if (null?.ignored() == null) "OK" else "fail"
        """
        assertTrue(runBox(tempDir, source))
    }

    /** A field of type `Nothing` takes no slot in its class. */
    @Test
    fun testFieldOfTypeNothingHasNoSlot(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, """
            class Never {
                val kept = 1
                val never: Nothing = throw IllegalStateException("never")
            }
        """)
        assertTrue("field kept" in dump) { dump }
        assertFalse("field never" in dump) { dump }
    }

    /** A top-level property of type `Nothing` takes no global. */
    @Test
    fun testTopLevelPropertyOfTypeNothingHasNoGlobal(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, """
            val never: Nothing = throw IllegalStateException("never")
        """)
        assertFalse("never" in dump.lines().filter { it.startsWith("global") }.joinToString()) { dump }
    }

    /** A class implementing `Iterator` has the common members only, so it links without the JDK's `forEachRemaining`. */
    @Test
    fun testClassImplementingIteratorInheritsNoJavaDefault(@TempDir tempDir: File) {
        val source = """
            class Countdown(var n: Int) : Iterator<Int> {
                override fun hasNext(): Boolean = n > 0
                override fun next(): Int = n--
            }

            fun box(): String {
                var sum = 0
                for (x in Countdown(3)) sum += x
                return if (sum == 6) "OK" else "fail"
            }
        """
        assertTrue(runBox(tempDir, source))
    }

    /** The VM sees the common members of the built-in collections only: the JDK's `forEachRemaining` does not resolve. */
    @Test
    fun testJdkMappedMemberDoesNotResolve(@TempDir tempDir: File) {
        val (output, exitCode) = compile(tempDir, "fun f(it: Iterator<Int>) = it.forEachRemaining { }")

        assertEquals(ExitCode.COMPILATION_ERROR, exitCode)
        assertTrue(output.contains("unresolved reference 'forEachRemaining'")) { output }
    }

    /** A line of the dump naming the exports. */
    private fun exportsOf(dump: String): List<String> = dump.lines().filter { it.startsWith("fn ") && " binds " in it }.map { it.substringAfter(" binds ") }

    /** A public top-level function crosses to the JVM by the name the JVM compiles its method to. */
    @Test
    fun testPublicTopLevelFunctionIsExportedUnderItsJvmName(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            fun greeting(name: String): String = name
        """))
        assertEquals(listOf("\"jvm:sample/GreetingKt.greeting(Ljava/lang/String;)Ljava/lang/String;\" {"), exportsOf(dump)) { dump }
    }

    /** `@file:JvmName` names the JVM class holding the file's functions. */
    @Test
    fun testFileJvmNameNamesTheExportOwner(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            @file:JvmName("Api")
            package sample

            fun answer(): Int = 42
        """))
        assertEquals(listOf("\"jvm:sample/Api.answer()I\" {"), exportsOf(dump)) { dump }
    }

    /** `@JvmName` on a function names its JVM method. */
    @Test
    fun testFunctionJvmNameNamesTheExportMethod(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            @JvmName("hello")
            fun greeting(): Int = 1
        """))
        assertEquals(listOf("\"jvm:sample/GreetingKt.hello()I\" {"), exportsOf(dump)) { dump }
    }

    /** A file in the root package has a facade class with no package. */
    @Test
    fun testFileInTheRootPackageHasNoPackageInTheExport(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to "fun answer(): Int = 42"))
        assertEquals(listOf("\"jvm:GreetingKt.answer()I\" {"), exportsOf(dump)) { dump }
    }

    /** Each primitive has its descriptor letter. */
    @Test
    fun testExportDescriptorOfPrimitives(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            fun mix(a: Int, b: Long, c: Short, d: Byte, e: Char, f: Boolean, g: Float, h: Double): Int = a
        """))
        assertEquals(listOf("\"jvm:sample/GreetingKt.mix(IJSBCZFD)I\" {"), exportsOf(dump)) { dump }
    }

    /** A nullable primitive is its box. */
    @Test
    fun testExportDescriptorOfNullablePrimitive(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            fun orNull(a: Int?): Boolean? = null
        """))
        assertEquals(
            listOf("\"jvm:sample/GreetingKt.orNull(Ljava/lang/Integer;)Ljava/lang/Boolean;\" {"), exportsOf(dump),
        ) { dump }
    }

    /** `Unit` is the JVM's `void`. */
    @Test
    fun testExportDescriptorOfUnitResult(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            fun nothingToSay() {}
        """))
        assertEquals(listOf("\"jvm:sample/GreetingKt.nothingToSay()V\" {"), exportsOf(dump)) { dump }
    }

    /** An extension receiver is the first parameter. */
    @Test
    fun testExportDescriptorOfExtensionReceiver(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            fun String.shout(times: Int): String = this
        """))
        assertEquals(listOf("\"jvm:sample/GreetingKt.shout(Ljava/lang/String;I)Ljava/lang/String;\" {"), exportsOf(dump)) { dump }
    }

    @Test
    fun testPrivateFunctionIsNotExported(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            private fun hidden(): Int = 1
        """))
        assertEquals(emptyList<String>(), exportsOf(dump)) { dump }
    }

    /** The JVM mangles an internal function's name with its module's, which is not covered. */
    @Test
    fun testInternalFunctionIsNotExported(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            internal fun inside(): Int = 1
        """))
        assertEquals(emptyList<String>(), exportsOf(dump)) { dump }
    }

    /** A class's members are not exported yet. */
    @Test
    fun testMemberFunctionIsNotExported(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            class Greeter {
                fun greet(): Int = 1
            }
        """))
        assertEquals(emptyList<String>(), exportsOf(dump)) { dump }
    }

    /** A type outside the primitives and `String` has no covered descriptor, so its function is not exported. */
    @Test
    fun testFunctionOfUncoveredTypeIsNotExported(@TempDir tempDir: File) {
        val dump = dumpIr(tempDir, mapOf("Greeting.kt" to """
            package sample

            fun sum(numbers: List<Int>): Int = 0
        """))
        assertEquals(emptyList<String>(), exportsOf(dump)) { dump }
    }

    /** A VM compilation, like any other, needs an `actual` for each `expect`; the JVM's fragment holds it. */
    @Test
    fun testExpectFunctionWithoutActualIsReported(@TempDir tempDir: File) {
        val (output, exitCode) = compile(tempDir, "expect fun platformName(): String")

        assertEquals(ExitCode.COMPILATION_ERROR, exitCode)
        assertTrue(output.contains("has no 'actual' declaration")) { output }
    }

    /** Outside a VM compilation, an `expect` declaration without an `actual` is an error too. */
    @Test
    fun testExpectFunctionWithoutActualIsReportedOutsideTheVm(@TempDir tempDir: File) {
        val (output, exitCode) = compile(
            tempDir, mapOf("box.kt" to "expect fun platformName(): String"), targetsVm = false, extraArgs = listOf("-Xmulti-platform"),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, exitCode)
        assertTrue(output.contains("has no 'actual' declaration")) { output }
    }

    /** An `expect` class whose `actual` is the JVM's has no class in the module. */
    @Test
    fun testExpectClassWithAnActualOnTheJvmDeclaresNoClass(@TempDir tempDir: File) {
        val dump = dumpIr(
            tempDir,
            mapOf("Platform.kt" to "package sample\n\nexpect class Logger(tag: String) {\n  fun log(message: String)\n}"),
            hostFiles = mapOf(
                "Platform.jvm.kt" to "package sample\n\nactual class Logger actual constructor(tag: String) {\n  actual fun log(message: String) {}\n}",
            ),
        )
        assertFalse(dump.lines().any { it.contains("Logger") }) { dump }
    }

    /** The JVM's fragment is analyzed, not compiled for the VM. */
    @Test
    fun testFunctionOfTheJvmFragmentIsNotGenerated(@TempDir tempDir: File) {
        val dump = dumpIr(
            tempDir,
            mapOf("Greeting.kt" to "package sample\n\nfun greeting(): String = \"Hello\""),
            hostFiles = mapOf("Main.kt" to "package sample\n\nfun helper(): Int = 1"),
        )
        assertFalse("helper" in dump) { dump }
    }

    /** A call of an `expect` function is a call of its `actual`, which the host runs. */
    @Test
    fun testCallOfExpectFunctionIsAHostImportOfItsActual(@TempDir tempDir: File) {
        val dump = dumpIr(
            tempDir,
            mapOf(
                "Platform.kt" to "package sample\n\nexpect fun platformName(): String",
                "Greeting.kt" to "package sample\n\nfun greeting(): String = platformName()",
            ),
            hostFiles = mapOf("Platform.jvm.kt" to "package sample\n\nactual fun platformName(): String = \"JVM\""),
        )
        val externs = dump.lines().filter { it.startsWith("extern ") }
        val name = "jvm_sample_Platform_jvmKt.platformName__Ljava_lang_String_"
        assertEquals(
            listOf("extern @$name() -> @vm.builtin.String binds \"jvm:sample/Platform_jvmKt.platformName()Ljava/lang/String;\""), externs,
        ) { dump }
        assertTrue("call @$name()" in dump) { dump }
    }

    /** Neither the `expect` function nor its `actual` has code in the module to export. */
    @Test
    fun testExpectFunctionIsNotExported(@TempDir tempDir: File) {
        val dump = dumpIr(
            tempDir,
            mapOf("Platform.kt" to "package sample\n\nexpect fun platformName(): String"),
            hostFiles = mapOf("Platform.jvm.kt" to "package sample\n\nactual fun platformName(): String = \"JVM\""),
        )
        assertEquals(emptyList<String>(), exportsOf(dump)) { dump }
    }

    /** The host import is in the facade of the file the `actual` is in, whatever that file is called. */
    @Test
    fun testHostImportIsNamedAfterTheActualsOwnFile(@TempDir tempDir: File) {
        val dump = dumpIr(
            tempDir,
            mapOf("Env.kt" to "package sample\n\nexpect fun home(): String\n\nfun path(): String = home()"),
            hostFiles = mapOf("JvmEnvironment.kt" to "package sample\n\nactual fun home(): String = \"/\""),
        )
        assertTrue("binds \"jvm:sample/JvmEnvironmentKt.home()Ljava/lang/String;\"" in dump) { dump }
    }

    /** The host import is in the facade `@file:JvmName` names. */
    @Test
    fun testHostImportTakesTheJvmNameOfTheActualsFile(@TempDir tempDir: File) {
        val dump = dumpIr(
            tempDir,
            mapOf("Env.kt" to "package sample\n\nexpect fun home(): String\n\nfun path(): String = home()"),
            hostFiles = mapOf("Env.jvm.kt" to "@file:JvmName(\"Environment\")\npackage sample\n\nactual fun home(): String = \"/\""),
        )
        assertTrue("binds \"jvm:sample/Environment.home()Ljava/lang/String;\"" in dump) { dump }
    }

    /** A call of an `actual` whose JVM name is not covered is reported. */
    @Test
    fun testCallOfActualOfUncoveredTypeIsReported(@TempDir tempDir: File) {
        val (output, exitCode) = compile(
            tempDir,
            mapOf("Names.kt" to "expect fun names(): List<String>\n\nfun count(): Int = names().size"),
            hostFiles = mapOf("Names.jvm.kt" to "actual fun names(): List<String> = emptyList()"),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, exitCode)
        assertTrue(output.contains("the VM backend does not support calling")) { output }
    }
}
