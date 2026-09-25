/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jklib.test

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jklib.K2JKlibCompiler
import org.jetbrains.kotlin.jklib.test.vm.JKlibVmSourceRootConfigurator
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
class JKlibVmBackendTest {
    private val stdlibKlib: String
        get() = JKlibVmSourceRootConfigurator.vmStdlib.path

    private data class Compilation(val output: String, val exitCode: ExitCode)

    /** Compiles the module [name] from [files] against the modules named by [dependencies], compiled before it. */
    private fun compile(
        tempDir: File,
        files: Map<String, String>,
        name: String = "main",
        dependencies: List<String> = emptyList(),
    ): Compilation {
        val sources = File(tempDir, name).apply { mkdirs() }
        val paths = files.map { file -> File(sources, file.key).apply { writeText(file.value.trimIndent()) }.path }
        val klibs = listOf(stdlibKlib) + dependencies.map { File(tempDir, "$it.klib").path }
        val args = paths + listOf(
            "-d", File(tempDir, "$name.klib").path,
            "-module-name", name,
            "-no-stdlib",
            "-Xklib=${klibs.joinToString(File.pathSeparator)}",
            "-Xvm-output=${File(tempDir, "$name.vir").path}",
        )
        // A stream of this compilation's own: capturing System.err would race the other tests running in parallel.
        val messages = ByteArrayOutputStream()
        val exitCode = K2JKlibCompiler().exec(PrintStream(messages), *args.toTypedArray())
        return Compilation(messages.toString(), exitCode)
    }

    private fun compile(tempDir: File, source: String): Compilation = compile(tempDir, mapOf("box.kt" to source))

    /** Compiles [source], links it against the VM runtime and runs it, returning what `box()` answered. */
    private fun runBox(tempDir: File, source: String): Boolean = runBox(tempDir, listOf(mapOf("box.kt" to source)))

    /**
     * Compiles each of [modules], a module's files by name, against the ones before it, links them against the VM
     * runtime and runs them. The last module is `main`, holding `box()`; the others are `lib0`, `lib1` and so on.
     */
    private fun runBox(tempDir: File, modules: List<Map<String, String>>): Boolean {
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
        run(File(repo, "target/debug/link_ir"), container, *virs.toTypedArray(), runtime)
        val result = run(File(repo, "target/debug/execute"), container)
        return result.lines().any { it == "r0 = 1" }
    }

    private fun run(binary: File, vararg args: String): String {
        assertTrue(binary.canExecute()) { "$binary is missing: build the VM repository with `cargo build`" }
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
        val (output, exitCode) = compile(tempDir, "annotation class Marker")

        assertEquals(ExitCode.COMPILATION_ERROR, exitCode)
        assertTrue(output.contains("the VM backend does not support the annotation class Marker")) { output }
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
}
