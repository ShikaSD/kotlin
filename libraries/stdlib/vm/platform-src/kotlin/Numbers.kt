/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

// Reinterpreting a value's bits is a copy between registers, which the VM backend compiles each call to.

public actual external fun Double.toRawBits(): Long

public actual external fun Double.Companion.fromBits(bits: Long): Double
