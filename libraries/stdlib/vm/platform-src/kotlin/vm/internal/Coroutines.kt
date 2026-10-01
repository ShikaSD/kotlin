/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.vm.internal

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.CoroutineImplVm
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

// Wasm's stack-switching coroutine library over the VM's `vm.builtin.coroutine.*` built-ins. A suspend function takes no
// continuation; the running coroutine's completion is reached through the owner the VM holds for it, a `CoroutineImplVm`.

/** The coroutine the innermost running continuation belongs to. */
@Suppress("UNCHECKED_CAST")
internal fun <T> currentCoroutine(): CoroutineImplVm<*, T> = vm.builtin.coroutine.current() as CoroutineImplVm<*, T>

@PublishedApi
internal inline fun <T> suspendCoroutineUninterceptedOrReturn(noinline block: (Continuation<T>) -> Any?): T =
    suspendCoroutineUninterceptedOrReturnImpl(block)

@PublishedApi
internal inline fun getCoroutineContext(): CoroutineContext = getCoroutineContextImpl()

@PublishedApi
internal fun getCoroutineContextImpl(): CoroutineContext = currentCoroutine<Any?>().context

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal fun <T> suspendCoroutineUninterceptedOrReturnImpl(block: (Continuation<T>) -> Any?): T {
    val blockContinuation = getBlockKotlinContinuation<T>()

    val blockResult = block(blockContinuation)
    if (blockResult !== COROUTINE_SUSPENDED) return blockResult as T

    suspendAfterBlock(blockContinuation)

    return getBlockKotlinContinuationResult(blockContinuation)
}

/** The continuation `block` resumes, which continues the running coroutine; see `CoroutineImplVm.pendingSuspend`. */
private fun <T> getBlockKotlinContinuation(): CoroutineImplVm<T, T> {
    val owner = currentCoroutine<T>()
    val blockContinuation = CoroutineImplVm<T, T>(owner.resultContinuation, owner.continuation, owner)
    blockContinuation.pendingSuspend = true
    return blockContinuation
}

@Suppress("UNCHECKED_CAST")
private fun <T> getBlockKotlinContinuationResult(blockContinuation: CoroutineImplVm<T, T>): T {
    val e = blockContinuation.exception
    if (e != null) throw e
    return blockContinuation.result as T
}

/**
 * Suspends for a block that answered `COROUTINE_SUSPENDED`, as a state machine does. A block that has resumed already
 * leaves the resumption to whoever ran the coroutine; see `CoroutineImplVm.resumedBeforeSuspending`.
 */
private fun <T> suspendAfterBlock(blockContinuation: CoroutineImplVm<T, T>) {
    if (blockContinuation.pendingSuspend) {
        blockContinuation.pendingSuspend = false
    } else {
        currentCoroutine<T>().resumedBeforeSuspending = true
    }
    vm.builtin.coroutine.suspend(COROUTINE_SUSPENDED)
}

@PublishedApi
internal fun <T> createCoroutineVm0(f: suspend () -> T, completion: Continuation<T>): CoroutineImplVm<Unit, T> {
    @Suppress("UNCHECKED_CAST")
    val function = f as () -> Any?
    return startedBy(function, completion)
}

@PublishedApi
internal fun <R, T> createCoroutineVm1(f: suspend R.() -> T, receiver: R, completion: Continuation<T>): CoroutineImplVm<Unit, T> {
    @Suppress("UNCHECKED_CAST")
    val function = f as (R) -> Any?
    return startedBy({ function(receiver) }, completion)
}

@PublishedApi
internal fun <R, P, T> createCoroutineVm2(
    f: suspend R.(P) -> T,
    receiver: R,
    param: P,
    completion: Continuation<T>,
): CoroutineImplVm<Unit, T> {
    @Suppress("UNCHECKED_CAST")
    val function = f as (R, P) -> Any?
    return startedBy({ function(receiver, param) }, completion)
}

private fun <T> startedBy(function: () -> Any?, completion: Continuation<T>): CoroutineImplVm<Unit, T> {
    val coroutine = CoroutineImplVm<Unit, T>(completion)
    coroutine.continuation = vm.builtin.coroutine.new(function, coroutine)
    return coroutine
}

/**
 * Runs [coroutine] until it suspends, answering `COROUTINE_SUSPENDED`, or returns, answering its result. One that
 * suspended after its block had resumed it continues to its next suspension or its end, as a resumption does.
 */
@PublishedApi
internal fun startCoroutineVm(coroutine: CoroutineImplVm<Unit, *>): Any? {
    val outcome = vm.builtin.coroutine.resume(coroutine.continuation!!, null)
    if (outcome !== COROUTINE_SUSPENDED || !coroutine.resumedBeforeSuspending) return outcome
    coroutine.resumedBeforeSuspending = false
    coroutine.resumeWith(Result.success(Unit))
    return COROUTINE_SUSPENDED
}
