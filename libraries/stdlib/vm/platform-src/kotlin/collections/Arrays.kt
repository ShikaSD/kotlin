/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.collections

// A VM array starts out null in every element, which is what "uninitialized" reads as.

@Suppress("NOTHING_TO_INLINE", "UNCHECKED_CAST")
@PublishedApi
internal actual inline fun <E> arrayOfUninitializedElements(size: Int): Array<E> {
    require(size >= 0) { "capacity must be non-negative." }
    return arrayOfNulls<Any?>(size) as Array<E>
}

internal actual fun <E> Array<E>.resetRange(fromIndex: Int, toIndex: Int) {
    @Suppress("UNCHECKED_CAST")
    val elements = this as Array<Any?>
    for (index in fromIndex until toIndex) elements[index] = null
}

internal actual fun <E> Array<E>.resetAt(index: Int) {
    @Suppress("UNCHECKED_CAST")
    (this as Array<Any?>)[index] = null
}

/**
 * Copies [count] elements of the array [from], from [fromIndex] on, into the array [to] at [toIndex], as
 * `System.arraycopy` does. The VM backend compiles it to the runtime's `vm.builtin.array.copy`.
 */
internal external fun copyArray(from: Any, fromIndex: Int, to: Any, toIndex: Int, count: Int)
