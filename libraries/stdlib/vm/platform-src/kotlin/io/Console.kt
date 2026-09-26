/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.io

public actual fun println() {
    printString("\n")
}

public actual fun println(message: Any?) {
    printString("$message\n")
}

public actual fun print(message: Any?) {
    printString("$message")
}

/** The VM has no input to read from yet. */
@SinceKotlin("1.6")
public actual fun readln(): String = throw ReadAfterEOFException("EOF has already been reached")

/** The VM has no input to read from yet. */
@SinceKotlin("1.6")
public actual fun readlnOrNull(): String? = null

/** Writes [message] to the VM's output, which the VM backend compiles to the runtime's `vm.builtin.io.print`. */
private external fun printString(message: String)
