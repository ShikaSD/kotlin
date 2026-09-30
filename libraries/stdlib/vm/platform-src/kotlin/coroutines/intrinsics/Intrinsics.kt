/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines.intrinsics

import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor

// The VM backend compiles no suspend function yet, so nothing can start a coroutine.

@Suppress("NOTHING_TO_INLINE")
public actual inline fun <T> (suspend () -> T).startCoroutineUninterceptedOrReturn(completion: Continuation<T>): Any? =
    coroutinesUnsupported()

@Suppress("NOTHING_TO_INLINE")
public actual inline fun <R, T> (suspend R.() -> T).startCoroutineUninterceptedOrReturn(
    receiver: R,
    completion: Continuation<T>
): Any? = coroutinesUnsupported()

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(
    receiver: R,
    param: P,
    completion: Continuation<T>
): Any? = coroutinesUnsupported()

public actual fun <T> (suspend () -> T).createCoroutineUnintercepted(completion: Continuation<T>): Continuation<Unit> =
    coroutinesUnsupported()

public actual fun <R, T> (suspend R.() -> T).createCoroutineUnintercepted(
    receiver: R,
    completion: Continuation<T>
): Continuation<Unit> = coroutinesUnsupported()

public actual fun <T> Continuation<T>.intercepted(): Continuation<T> =
    context[ContinuationInterceptor]?.interceptContinuation(this) ?: this

@PublishedApi
internal fun coroutinesUnsupported(): Nothing = throw UnsupportedOperationException("The VM does not run coroutines yet")
