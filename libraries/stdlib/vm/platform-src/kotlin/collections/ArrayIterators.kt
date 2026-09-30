/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.collections

// What an array's `iterator()` returns: the builtin array classes have no bodies, so the VM backend compiles the member
// to a call of the `arrayIterator` overload for the array's class.

@PublishedApi
internal fun <T> arrayIterator(array: Array<T>): Iterator<T> = ArrayIterator(array)

private class ArrayIterator<T>(private val array: Array<T>) : Iterator<T> {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun next(): T = if (index < array.size) array[index++] else throw NoSuchElementException("$index")
}

@PublishedApi
internal fun arrayIterator(array: BooleanArray): BooleanIterator = BooleanArrayIterator(array)

private class BooleanArrayIterator(private val array: BooleanArray) : BooleanIterator() {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun nextBoolean(): Boolean = if (index < array.size) array[index++] else throw NoSuchElementException("$index")
}

@PublishedApi
internal fun arrayIterator(array: CharArray): CharIterator = CharArrayIterator(array)

private class CharArrayIterator(private val array: CharArray) : CharIterator() {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun nextChar(): Char = if (index < array.size) array[index++] else throw NoSuchElementException("$index")
}

@PublishedApi
internal fun arrayIterator(array: ByteArray): ByteIterator = ByteArrayIterator(array)

private class ByteArrayIterator(private val array: ByteArray) : ByteIterator() {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun nextByte(): Byte = if (index < array.size) array[index++] else throw NoSuchElementException("$index")
}

@PublishedApi
internal fun arrayIterator(array: ShortArray): ShortIterator = ShortArrayIterator(array)

private class ShortArrayIterator(private val array: ShortArray) : ShortIterator() {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun nextShort(): Short = if (index < array.size) array[index++] else throw NoSuchElementException("$index")
}

@PublishedApi
internal fun arrayIterator(array: IntArray): IntIterator = IntArrayIterator(array)

private class IntArrayIterator(private val array: IntArray) : IntIterator() {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun nextInt(): Int = if (index < array.size) array[index++] else throw NoSuchElementException("$index")
}

@PublishedApi
internal fun arrayIterator(array: LongArray): LongIterator = LongArrayIterator(array)

private class LongArrayIterator(private val array: LongArray) : LongIterator() {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun nextLong(): Long = if (index < array.size) array[index++] else throw NoSuchElementException("$index")
}

@PublishedApi
internal fun arrayIterator(array: FloatArray): FloatIterator = FloatArrayIterator(array)

private class FloatArrayIterator(private val array: FloatArray) : FloatIterator() {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun nextFloat(): Float = if (index < array.size) array[index++] else throw NoSuchElementException("$index")
}

@PublishedApi
internal fun arrayIterator(array: DoubleArray): DoubleIterator = DoubleArrayIterator(array)

private class DoubleArrayIterator(private val array: DoubleArray) : DoubleIterator() {
    private var index = 0
    override fun hasNext(): Boolean = index < array.size
    override fun nextDouble(): Double = if (index < array.size) array[index++] else throw NoSuchElementException("$index")
}
