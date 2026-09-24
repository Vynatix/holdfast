@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

// Store attachments: library machinery that lives exactly as long as one
// store and must hear of its lifecycle (issue #20 plan PR 10). The slot a
// `:holdfast-coroutines` hydrator attaches through, and the one issue #21's
// tree builds its registry membership on: a store joins a tree by attaching,
// a second tree's attach finds the first one's attachment and fails fast, and
// the tree hears of the store's dispose and drops it.

/**
 * Library machinery attached to one [Store] for the rest of the store's life
 * (see [internalAttachIfAbsent]), found again through its
 * [StoreAttachmentKey], and told of the store's lifecycle: [onStoreReset]
 * inside every [reset], and [onStoreDisposed] once, last.
 *
 * An attachment can be created while its store is still being constructed —
 * from a base class's `init` block, before any subclass has declared its
 * states — so it must not capture the store's states or declarations when it
 * is created: it looks them up when it needs them, once the store exists.
 *
 * Every member has a default, so an attachment overrides only what it needs.
 */
@StoreInternalApi
interface StoreAttachment {
    /**
     * The store was disposed. Called once, as the last step of [Store.dispose]
     * — after every state, bridge, middleware and the subclass's `onDispose`
     * are gone, so the store refuses every entrypoint by then — on the thread
     * that disposed it, holding no lock `dispose()` took but under any lock
     * its caller holds. Disposed from inside an action, observer or reset of
     * this store (including another attachment's [onStoreReset]), that caller
     * still holds this store's `transactionLock` (and, from an action body or
     * a reset, its `middlewareLock`). Only after a top-level `dispose()` may it
     * call into other stores; otherwise it must not open an action or frame on
     * another store or wait for another thread's store work. After that this
     * attachment hears nothing more from the store. A throwing callback goes
     * to the store's `uncaughtObserverHandler` (else the default log, with a
     * line of its own) and the other attachments are still told.
     */
    fun onStoreDisposed() {}

    /**
     * The store was reset ([reset], or a reset staged into an `atomic` frame).
     * Called inside the reset's transaction, on the thread running it, once
     * every declared state's reset is staged: a read of the store's states
     * sees their reset values, and a write (`mutate`) stages into the reset —
     * it commits, or rolls back, with it. Unlike the initializers the reset
     * re-runs, this may write. A throwing callback fails the reset, which
     * rolls back whole, and the attachments after this one are not told. A
     * sterile [restore] does not call it: that store was restored, not reset.
     */
    fun onStoreReset() {}

    /**
     * The keys under which this attachment persists its store — the
     * key-value key an overlay writes the store's snapshot to, say — so that a
     * self-check can find two stores persisted under one key, or a persisted
     * store without a pinned name (issue #21, T6). Empty by default: this
     * attachment persists nothing.
     */
    val persistenceKeys: Set<String> get() = emptySet()
}

/**
 * The typed address of one kind of [StoreAttachment] on a store: a store
 * holds at most one attachment per key, of the key's type [A].
 *
 * Keys compare by identity, never by [name]: two keys with one name are two
 * separate slots. Declare each key once, as a top-level `val`, next to the
 * attachment it addresses. [name] only appears in messages.
 */
@StoreInternalApi
class StoreAttachmentKey<A : StoreAttachment>(
    val name: String,
) {
    override fun toString(): String = "StoreAttachmentKey($name)"
}

/**
 * The attachment under [key], or `null` when none is attached: never
 * attached, or dropped by [Store.dispose]. Reads the attachments without
 * taking a lock, and never throws, even after `dispose()` (it answers `null`
 * then).
 */
@StoreInternalApi
fun <A : StoreAttachment> Store<*>.internalAttachment(key: StoreAttachmentKey<A>): A? = attachmentSlot.get(key)

/**
 * The attachment under [key]: the one already attached, else the one
 * [create] builds, attached now. Under races there is one winner: every
 * caller gets the same instance, and only the call that attaches runs
 * [create] — under the attachment slot's own lock, so a racing caller waits
 * for it instead of building a second attachment that would have to be
 * thrown away after doing whatever its construction does. A caller that must be the only
 * one to attach under [key] (a store belongs to one tree, issue #21) compares
 * the result with its own.
 *
 * [create] runs under the slot's lock, which other attaches to this store
 * and [Store.dispose] wait for — some of them while holding this store's
 * `transactionLock`, `middlewareLock` or a state's initializer latch (see
 * [AttachmentSlot] for its place in the lock order). So [create] may only
 * build the attachment, register internal states
 * ([Store.registerInternalState], which takes the state registry's lock) and
 * attach under another key of this store (attached first). It must not:
 *  - take this store's `transactionLock` or `middlewareLock`: no `action`,
 *    `atomic`, `mutate`/`update`, [reset], [restore], [Store.dispose],
 *    `middlewares` or `clearMiddleware`;
 *  - materialize a state: no read of a declared state that may not have
 *    been read yet, and no [snapshot]. That state's initializer may be
 *    running on a thread that is waiting for this slot;
 *  - touch another store, or wait for another thread.
 *
 * It may not attach under [key] itself (an [IllegalStateException]). If it
 * throws, nothing is attached and the next call runs it again.
 *
 * Works while the store is being constructed — from a base class's `init`
 * block, before any subclass state is declared — because the slot is
 * independent of the state registry; an attachment made there looks the
 * store's states up later (see [StoreAttachment]). Attaching is not a write:
 * it works inside actions, observers and state initializers (a [create]
 * still may not read a state, above), but not while holding the state
 * registry's lock or a per-state lock (no attach from a `Bridge.publish`).
 * An attach racing `dispose()` either lands before the store closes its
 * slot, and is told of the dispose, or throws.
 *
 * @throws IllegalStateException if the store is disposed.
 */
@StoreInternalApi
fun <A : StoreAttachment> Store<*>.internalAttachIfAbsent(
    key: StoreAttachmentKey<A>,
    create: () -> A,
): A {
    checkNotDisposed()
    return attachmentSlot.attachIfAbsent(key, create)
}

/**
 * Every attachment of this store, in attach order: the order [Store.dispose]
 * and [reset] tell them in. A copy, read without taking a lock; empty after
 * `dispose()`, and never throws.
 */
@StoreInternalApi
fun Store<*>.internalAttachments(): List<StoreAttachment> = attachmentSlot.all()

/**
 * One store's attachments, by key, in attach order.
 *
 * [attachIfAbsent] and [close] serialize on [lock], the slot's own lock. Its
 * place in the store's lock order is `transactionLock` → `middlewareLock` →
 * initializer latch → slot lock → `propertiesLock` → per-state locks
 * (`bridgeLock`, `stateLock`, `observersLock`). It is taken while
 * `transactionLock` and `middlewareLock` are held (an attach from an action
 * body, or from a reset's [StoreAttachment.onStoreReset]), while
 * `transactionLock` is held (an attach from commit fanout; the close in
 * [Store.dispose]), and while an initializer's latch is held (an attach from
 * an initializer). The `create`
 * it runs may take the locks to its right — `propertiesLock`, to register
 * an internal state — so it is never taken while holding `propertiesLock` or
 * a per-state lock, and a `create` must not take a lock to its left: no
 * action, frame or dispose, no middleware change, and no first read of a
 * state (see [internalAttachIfAbsent]). Lookups ([get], [all]) read the
 * published map without the lock, so telling the attachments of a reset,
 * under `transactionLock`, never waits for an attach in progress.
 *
 * Never touches the store's declarations: it is complete before any subclass
 * code runs, so attaching works from a base class's `init` block.
 */
internal class AttachmentSlot(
    private val store: Store<*>,
) {
    private val lock = StoreLock()

    /** Every attachment by key, in attach order. Replaced as a whole under [lock], never changed in place. */
    @kotlin.concurrent.Volatile
    private var attached: Map<StoreAttachmentKey<*>, StoreAttachment> = emptyMap()

    /** Set once, by [close]; an attach after it fails. Written under [lock], read without it. */
    @kotlin.concurrent.Volatile
    private var closed = false

    /** The keys whose `create` runs right now, on the thread holding [lock]. Guarded by [lock]. */
    private val creating = HashSet<StoreAttachmentKey<*>>()

    fun <A : StoreAttachment> get(key: StoreAttachmentKey<A>): A? {
        // Only attachIfAbsent puts a value under a key: create's result, an A.
        @Suppress("UNCHECKED_CAST")
        return attached[key] as A?
    }

    fun all(): List<StoreAttachment> = attached.values.toList()

    /** See [internalAttachIfAbsent]. */
    fun <A : StoreAttachment> attachIfAbsent(
        key: StoreAttachmentKey<A>,
        create: () -> A,
    ): A {
        lock.withLock {
            check(!closed) { "store disposed" }
            get(key)?.let { return it }
            // Only the thread holding the lock can be creating, so a key already
            // here is this thread's own create attaching its own key.
            check(creating.add(key)) {
                "${store.displayName}: the create of attachment '${key.name}' attaches '${key.name}' itself"
            }
            val created =
                try {
                    create()
                } finally {
                    creating.remove(key)
                }
            // Defensive: only this thread can have closed the slot meanwhile,
            // through a create that disposed its own store. That is forbidden
            // (dispose takes transactionLock under this lock, the reverse of
            // an attach from an action on another thread), but should one do
            // it, its result is dropped rather than left in a closed slot.
            check(!closed) { "store disposed" }
            attached = LinkedHashMap(attached).apply { put(key, created) }
            return created
        }
    }

    /**
     * Refuse every later attach and drop every attachment, returning them in
     * attach order for [notifyDisposed]. Called once, by [Store.dispose].
     */
    fun close(): List<StoreAttachment> =
        lock.withLock {
            closed = true
            all().also { attached = emptyMap() }
        }

    /**
     * Tell each attachment [notify], in attach order, as long as the slot is
     * open: once [close] has run — the store was disposed from inside an
     * earlier [notify] — the rest are not told, since they have already heard
     * of the dispose. A throwing [notify] propagates, and the rest are not told.
     */
    fun forEachWhileOpen(notify: (StoreAttachment) -> Unit) {
        for (attachment in all()) {
            if (closed) return
            notify(attachment)
        }
    }

    /**
     * Tell each of [attachments] that the store is disposed, in order, each
     * once and isolated: a throwing callback goes to the store's
     * `uncaughtObserverHandler` (else the default log, with a dispose line of
     * its own, not the post-commit one), a handler that throws for it is
     * ignored — `dispose()` never throws — and the next one is told either way.
     */
    fun notifyDisposed(attachments: List<StoreAttachment>) {
        for (attachment in attachments) {
            runCatching { attachment.onStoreDisposed() }
                .onFailure { failure ->
                    runCatching { store.reportUncaughtFailure(failure) { disposeFailureMessage(attachment) } }
                }
        }
    }

    private fun disposeFailureMessage(attachment: StoreAttachment): String =
        "Holdfast: library machinery attached to ${store.displayName} " +
            "(${attachment::class.simpleName ?: "a StoreAttachment"}) failed in onStoreDisposed; the store is " +
            "disposed regardless. Set uncaughtObserverHandler on the store to handle these failures yourself, " +
            "or to silence them."
}
