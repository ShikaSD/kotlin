/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

/** Prints [stackTraceToString] of this throwable to the standard output. */
public actual fun Throwable.printStackTrace(): Unit = println(stackTraceToString())

/** The description of this throwable followed by that of each throwable in its [Throwable.cause] chain. */
@SinceKotlin("1.4")
public actual fun Throwable.stackTraceToString(): String {
    val description = StringBuilder(toString())
    var cause = this.cause
    while (cause != null) {
        description.append("\nCaused by: ").append(cause.toString())
        cause = cause.cause
    }
    return description.toString()
}

@SinceKotlin("1.1")
@kotlin.internal.HidesMembers
public actual fun Throwable.addSuppressed(exception: Throwable) {
    (this as java.lang.Throwable).addSuppressed(exception)
}

@SinceKotlin("1.4")
public actual val Throwable.suppressedExceptions: List<Throwable>
    get() = (this as java.lang.Throwable).suppressed.asList()
