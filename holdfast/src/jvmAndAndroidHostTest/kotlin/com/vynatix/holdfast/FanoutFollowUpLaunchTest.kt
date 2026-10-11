package com.vynatix.holdfast

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class LaunchFanoutStore : Store<LaunchFanoutStore>() {
    val trigger by state { 0 }
    val echo by state { 0 }
}

/**
 * The follow-up-action remedy the refusal message and GUIDE §4.4 teach for an
 * observer that must write back into its own store:
 * `store.scope.launch(Dispatchers.Default) { store action { … }.getOrThrow() }`.
 * It works because the launch dispatches: the action runs on another thread,
 * waits for the store and commits. On a dispatcher that runs the body inline
 * the launched action is inside the commit's fanout and is refused — the
 * documented caveat. Scopes are bound per store (`bindToScope`), never through
 * `Store.defaultScope`, which is settable once per process.
 */
class FanoutFollowUpLaunchTest {
    private val disposables = mutableListOf<Disposable>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
        scopes.forEach { it.cancel() }
    }

    private fun <T : Any> onCommit(
        state: State<T>,
        react: (T) -> Unit,
    ) {
        var initial = true
        disposables +=
            state.effect {
                if (initial) initial = false else react(this)
            }
    }

    private fun LaunchFanoutStore.bind(scope: CoroutineScope): LaunchFanoutStore {
        scopes += scope
        bindToScope(scope)
        return this
    }

    @Test fun followUpActionLaunchedOnADispatchingScopeCommits() {
        val s = LaunchFanoutStore().bind(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val result = AtomicReference<TransactionResult<*>?>(null)
        var job: Job? = null
        onCommit(s.trigger) { value ->
            job =
                s.scope.launch(Dispatchers.Default) {
                    val r = s action { echo mutate value * 5 }
                    result.set(r)
                    r.getOrThrow()
                }
        }

        s action { trigger mutate 1 }
        runBlocking { withTimeout(5_000) { assertNotNull(job).join() } }

        assertIs<TransactionResult.Success<*>>(result.get(), "a dispatched follow-up waits for the store, then commits")
        assertEquals(5, s.echo.value)
        assertEquals(1, s.trigger.value)
    }

    @Test fun followUpActionLaunchedOnAnInlineDispatcherIsRefused() {
        val caught = CopyOnWriteArrayList<Throwable>()
        val handler = CoroutineExceptionHandler { _, e -> caught += e }
        val s = LaunchFanoutStore().bind(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler))
        var launched: TransactionResult<*>? = null
        onCommit(s.trigger) { value ->
            s.scope.launch {
                val nested = s action { echo mutate value }
                launched = nested
                nested.getOrThrow()
            }
        }

        val r = s action { trigger mutate 1 }

        assertIs<TransactionResult.Success<*>>(r)
        val error = assertIs<TransactionResult.Error>(launched, "an inline launch runs inside the commit's fanout")
        val message = assertNotNull(error.exception.message)
        assertTrue("open a nested action on LaunchFanoutStore" in message, "unexpected: $message")
        assertEquals(0, s.echo.value)
        // `.getOrThrow()` is what keeps this from being silent: the refusal
        // becomes a coroutine failure that reaches the scope's handler.
        assertIs<IllegalStateException>(caught.single())
        assertTrue("has already applied its writes" in caught.single().message.orEmpty())
    }
}
