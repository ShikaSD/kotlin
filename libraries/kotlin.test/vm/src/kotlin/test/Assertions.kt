/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.test

// The assertions the codegen box tests use, until the VM stdlib has what kotlin.test's common sources need: `Result`,
// preconditions, `kotlin.math` and the collection and array functions. The messages are kotlin.test's. Where it takes
// `message: String? = null`, this declares an overload without the message, since the VM backend cannot yet call a
// function leaving out a default argument; and nothing goes through an `Asserter`, whose methods take lambdas.

/** Throws an [AssertionError] with the given [message]. */
public fun fail(): Nothing = fail(null)

/** Throws an [AssertionError] with the given [message]. */
public fun fail(message: String?): Nothing = throw AssertionError(message)

/** Asserts that the expression is `true`. */
public fun assertTrue(actual: Boolean): Unit = assertTrue(actual, null)

/** Asserts that the expression is `true` with an optional [message]. */
public fun assertTrue(actual: Boolean, message: String?) {
    if (!actual) fail(message ?: "Expected value to be true.")
}

/** Asserts that the expression is `false`. */
public fun assertFalse(actual: Boolean): Unit = assertFalse(actual, null)

/** Asserts that the expression is `false` with an optional [message]. */
public fun assertFalse(actual: Boolean, message: String?) {
    if (actual) fail(message ?: "Expected value to be false.")
}

/** Asserts that the [expected] value is equal to the [actual] value. */
public fun <T> assertEquals(expected: T, actual: T): Unit = assertEquals(expected, actual, null)

/** Asserts that the [expected] value is equal to the [actual] value, with an optional [message]. */
public fun <T> assertEquals(expected: T, actual: T, message: String?) {
    if (expected != actual) fail(messagePrefix(message) + "Expected <$expected>, actual <$actual>.")
}

/** Asserts that the [actual] value is not equal to the illegal value. */
public fun <T> assertNotEquals(illegal: T, actual: T): Unit = assertNotEquals(illegal, actual, null)

/** Asserts that the [actual] value is not equal to the illegal value, with an optional [message]. */
public fun <T> assertNotEquals(illegal: T, actual: T, message: String?) {
    if (illegal == actual) fail(messagePrefix(message) + "Illegal value: <$actual>.")
}

/** Asserts that [expected] is the same instance as [actual]. */
public fun <T> assertSame(expected: T, actual: T): Unit = assertSame(expected, actual, null)

/** Asserts that [expected] is the same instance as [actual], with an optional [message]. */
public fun <T> assertSame(expected: T, actual: T, message: String?) {
    if (expected !== actual) fail(messagePrefix(message) + "Expected <$expected>, actual <$actual> is not same.")
}

/** Asserts that [actual] is not the same instance as [illegal]. */
public fun <T> assertNotSame(illegal: T, actual: T): Unit = assertNotSame(illegal, actual, null)

/** Asserts that [actual] is not the same instance as [illegal], with an optional [message]. */
public fun <T> assertNotSame(illegal: T, actual: T, message: String?) {
    if (illegal === actual) fail(messagePrefix(message) + "Expected not same as <$actual>.")
}

/** Asserts that the [actual] value is `null`. */
public fun assertNull(actual: Any?): Unit = assertNull(actual, null)

/** Asserts that the [actual] value is `null`, with an optional [message]. */
public fun assertNull(actual: Any?, message: String?) {
    if (actual != null) fail(messagePrefix(message) + "Expected value to be null, but was: <$actual>.")
}

/** Asserts that the [actual] value is not `null`. */
public fun <T : Any> assertNotNull(actual: T?): T = assertNotNull(actual, null)

/** Asserts that the [actual] value is not `null`, with an optional [message]. */
public fun <T : Any> assertNotNull(actual: T?, message: String?): T {
    if (actual == null) fail(messagePrefix(message) + "Expected value to be not null.")
    return actual
}

/** Asserts that [value] is of type [T]. */
public inline fun <reified T> assertIs(value: Any?): T = assertIs(value, null)

/** Asserts that [value] is of type [T], with an optional [message]. */
public inline fun <reified T> assertIs(value: Any?, message: String?): T {
    if (value !is T) fail(messagePrefix(message) + "Expected value to be of the expected type, actual <$value>.")
    return value
}

/** Asserts that [value] is not of type [T]. */
public inline fun <reified T> assertIsNot(value: Any?): Unit = assertIsNot<T>(value, null)

/** Asserts that [value] is not of type [T], with an optional [message]. */
public inline fun <reified T> assertIsNot(value: Any?, message: String?) {
    if (value is T) fail(messagePrefix(message) + "Expected value to not be of the given type, actual <$value>.")
}

/** Asserts that given function [block] returns the given [expected] value. */
public inline fun <T> expect(expected: T, block: () -> T): Unit = assertEquals(expected, block())

/** Asserts that given function [block] fails by throwing an exception, and returns it. */
public inline fun assertFails(block: () -> Unit): Throwable = assertFails(null, block)

/** Asserts that given function [block] fails by throwing an exception, and returns it, with an optional [message]. */
public inline fun assertFails(message: String?, block: () -> Unit): Throwable {
    try {
        block()
    } catch (e: Throwable) {
        return e
    }
    fail(messagePrefix(message) + "Expected an exception to be thrown, but was completed successfully.")
}

/** Asserts that a [block] fails with an exception of type [T], and returns it. */
public inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T = assertFailsWith(null, block)

/** Asserts that a [block] fails with an exception of type [T], and returns it, with an optional [message]. */
public inline fun <reified T : Throwable> assertFailsWith(message: String?, block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        fail(messagePrefix(message) + "Expected an exception of the expected type to be thrown, but was $e")
    }
    fail(messagePrefix(message) + "Expected an exception to be thrown, but was completed successfully.")
}

@PublishedApi
internal fun messagePrefix(message: String?): String = if (message == null) "" else "$message. "
