/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.vm.internal

// The float operations Wasm's `kotlin.math` takes from Wasm instructions, which the build points here instead: the VM's
// `vm.builtin.f64.*` and `vm.builtin.f32.*` built-ins, and the Wasm instructions' semantics for the rest.

@Suppress("NOTHING_TO_INLINE")
internal inline fun f64_sqrt(x: Double): Double = vm.builtin.f64.sqrt(x)
@Suppress("NOTHING_TO_INLINE")
internal inline fun f64_ceil(x: Double): Double = vm.builtin.f64.ceil(x)
@Suppress("NOTHING_TO_INLINE")
internal inline fun f64_floor(x: Double): Double = vm.builtin.f64.floor(x)
@Suppress("NOTHING_TO_INLINE")
internal inline fun f64_truncate(x: Double): Double = vm.builtin.f64.trunc(x)

@Suppress("NOTHING_TO_INLINE")
internal inline fun f32_sqrt(x: Float): Float = vm.builtin.f32.sqrt(x)
@Suppress("NOTHING_TO_INLINE")
internal inline fun f32_ceil(x: Float): Float = vm.builtin.f32.ceil(x)
@Suppress("NOTHING_TO_INLINE")
internal inline fun f32_floor(x: Float): Float = vm.builtin.f32.floor(x)
@Suppress("NOTHING_TO_INLINE")
internal inline fun f32_truncate(x: Float): Float = vm.builtin.f32.trunc(x)

internal fun f64_abs(x: Double): Double = Double.fromBits(x.toRawBits() and Long.MAX_VALUE)

internal fun f64_copysign(x: Double, sign: Double): Double =
    Double.fromBits((x.toRawBits() and Long.MAX_VALUE) or (sign.toRawBits() and Long.MIN_VALUE))

/** NaN if either is, and `-0.0` below `0.0`, as Wasm's `f64.min`. */
internal fun f64_min(a: Double, b: Double): Double = when {
    a != a -> a
    b != b -> b
    a == b -> if (a.toRawBits() < 0) a else b
    else -> if (a < b) a else b
}

/** NaN if either is, and `0.0` above `-0.0`, as Wasm's `f64.max`. */
internal fun f64_max(a: Double, b: Double): Double = when {
    a != a -> a
    b != b -> b
    a == b -> if (a.toRawBits() < 0) b else a
    else -> if (a > b) a else b
}

internal fun f32_abs(x: Float): Float = Float.fromBits(x.toRawBits() and Int.MAX_VALUE)

internal fun f32_copysign(x: Float, sign: Float): Float =
    Float.fromBits((x.toRawBits() and Int.MAX_VALUE) or (sign.toRawBits() and Int.MIN_VALUE))

internal fun f32_min(a: Float, b: Float): Float = when {
    a != a -> a
    b != b -> b
    a == b -> if (a.toRawBits() < 0) a else b
    else -> if (a < b) a else b
}

internal fun f32_max(a: Float, b: Float): Float = when {
    a != a -> a
    b != b -> b
    a == b -> if (a.toRawBits() < 0) b else a
    else -> if (a > b) a else b
}
