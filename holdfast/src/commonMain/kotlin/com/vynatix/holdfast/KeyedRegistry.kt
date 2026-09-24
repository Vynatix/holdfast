@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.atomicfu.atomic

// The registry of one store's keyed state families (issue #20, R7; plan D18),
// kept beside the declared states (StateRegistry.kt) and under the same lock,
// so a family and a state share one namespace; and the library hooks issue
// #21 builds on: an entry's address (family and key) and a membership
// listener told of every entry created and evicted.

/**
 * One store's keyed state families, by name in declaration order. Guarded by
 * [registry]'s lock, the store's `propertiesLock`, which [StateRegistry]
 * holds while it checks that a state's name is not a family's (and this
 * class while it checks the reverse).
 */
internal class KeyedRegistry(
    private val registry: StateRegistry,
) {
    private val families = LinkedHashMap<String, KeyedFamily<*, *>>()

    /**
     * Bumped every time a family is declared (a local family can be declared
     * at any time). With each family's [KeyedFamily.createdVersion] and
     * [commitsApplied], what a capture checks to tell whether it can have
     * missed an entry ([captureConsistent]).
     */
    private val declaredFamilies = atomic(0L)

    /**
     * Bumped by every commit that applies writes or evictions of this store,
     * and by every inbound bridge write (`MutableState.applyFromBridge`), just
     * before its write bracket opens (FrameCommit.kt). Creating an entry is
     * not a commit, so no write bracket covers it: a capture that listed the
     * entries before one came to life can only show a write made after the
     * creation — which would tear it — if this (or another captured store's)
     * moved too ([captureConsistent]).
     */
    private val commits = atomic(0L)

    /** Whether a capture holds the creation of this store's entries back ([CreationHoldBack]). */
    val creation = CreationHoldBack(registry)

    private val listenersLock = StoreLock()
    private var listeners: List<KeyedMembershipListener> = emptyList()

    /**
     * Record [candidate], or return the family it repeats — the same
     * declaration running again (see [StateRegistry.declare]); any other
     * second declaration of its name, as a family or as a state, fails.
     */
    fun <K : Any, T : Any> declare(candidate: KeyedFamily<K, T>): KeyedFamily<K, T> =
        registry.lock.withLock {
            val existing = families[candidate.name]
            val state = registry.declaration(candidate.name)
            when {
                state != null -> error(clashMessage(candidate.name, "a state (${state.describeSite()})"))
                existing == null ->
                    candidate.also {
                        families[it.name] = it
                        declaredFamilies.incrementAndGet()
                    }
                existing.local == candidate.local && (existing.local || existing.property == candidate.property) -> {
                    @Suppress("UNCHECKED_CAST")
                    existing as KeyedFamily<K, T>
                }
                else -> error(clashMessage(candidate.name, "a keyed state family"))
            }
        }

    /** The family named [name], if any. */
    fun family(name: String): KeyedFamily<*, *>? = registry.lock.withLock { families[name] }

    /** See [declaredFamilies]; read without a lock. */
    val familiesDeclaredVersion: Long get() = declaredFamilies.value

    /** See [commits]; read without a lock. */
    val commitsApplied: Long get() = commits.value

    /** A commit (or an inbound bridge write) of this store is about to open its write bracket; see [commits]. */
    fun commitApplying() {
        commits.incrementAndGet()
    }

    /** Every family, in declaration order. */
    fun familiesInOrder(): List<KeyedFamily<*, *>> = registry.lock.withLock { families.values.toList() }

    /**
     * Drop every family and entry and every membership listener (the store is
     * being disposed), and return the entries' states for the caller to shut
     * down outside the lock. The caller holds the registry lock.
     */
    fun releaseAllLocked(): List<MutableState<*>> {
        val states = families.values.flatMap { it.releaseAllLocked() }
        families.clear()
        listenersLock.withLock { listeners = emptyList() }
        return states
    }

    /**
     * The failure text for declaring [name] again when it is already
     * declared as [existing] ("a state (…)" or "a keyed state family").
     */
    fun clashMessage(
        name: String,
        existing: String,
    ): String =
        "${registry.store.displayName} already declares '$name' as $existing, and it is declared again. A store's " +
            "states and keyed state families share one set of names, and a name is declared once: two " +
            "declarations of one name would silently share it, so the second one fails. Give one of them another " +
            "name — a subclass cannot redeclare a name its base class declares."

    /** Add [listener]; the returned handle removes it. */
    fun addListener(listener: KeyedMembershipListener): Disposable {
        listenersLock.withLock { listeners = listeners + listener }
        return Disposable { listenersLock.withLock { listeners = listeners - listener } }
    }

    /**
     * Tell the listeners that [decl]'s entry now lives as [state], unless it
     * was announced already: once per entry, on the first thread that returns
     * it, holding no lock of the store but the entry's announcement lock
     * ([KeyedEntry.announceLock]) — never after the entry's eviction was
     * announced ([announceEvicted]).
     */
    fun announceAdded(
        decl: StateDeclaration<*>,
        state: MutableState<*>,
    ) {
        val entry = decl.keyed ?: return
        entry.announceLock.withLock {
            if (entry.phase == KeyedEntry.Phase.NEW) {
                entry.phase = KeyedEntry.Phase.ADDED
                tell(state) { it.onEntryAdded(entry.family.name, entry.key, state) }
            }
        }
    }

    /**
     * Tell the listeners that [state]'s entry was evicted: in the evicting
     * commit's fanout, before its observers, once. An entry no thread has
     * returned yet — so never announced — is announced added first, under the
     * same lock, so a listener always hears an entry added before it hears it
     * evicted, and never added after.
     */
    fun announceEvicted(state: MutableState<*>) {
        val entry = state.declaration?.keyed ?: return
        entry.announceLock.withLock {
            when (entry.phase) {
                KeyedEntry.Phase.EVICTED -> return@withLock
                KeyedEntry.Phase.NEW -> tell(state) { it.onEntryAdded(entry.family.name, entry.key, state) }
                KeyedEntry.Phase.ADDED -> Unit
            }
            entry.phase = KeyedEntry.Phase.EVICTED
            tell(state) { it.onEntryEvicted(entry.family.name, entry.key, state) }
        }
    }

    /** Call [event] on each listener, reporting — not propagating — what one throws, so the others are told. */
    private inline fun tell(
        state: MutableState<*>,
        event: (KeyedMembershipListener) -> Unit,
    ) {
        for (listener in listenersLock.withLock { listeners }) {
            runCatching { event(listener) }.onFailure { failure ->
                runCatching { state.owningStore.internalReportUncaughtFailure(failure) }
            }
        }
    }
}

/** This store's [CreationHoldBack]. */
internal val Store<*>.creationHoldBack: CreationHoldBack get() = registry.keyed.creation

/**
 * Holds back the creation of every entry of one store's families — a new
 * entry's declaration, and the publishing of an entry whose initializer is
 * running — on every thread but the holder's, from [hold] to [release]: a
 * capture that listed again too often does this around listing the entries
 * and reading its cut, which runs no user code and waits only for write
 * brackets, so it can never wait for a thread it holds back
 * ([captureConsistent]). A held-back thread yields outside [registry]'s lock
 * ([KeyedFamily.stateFor], [KeyedFamily.publish]).
 */
internal class CreationHoldBack(
    private val registry: StateRegistry,
) {
    /** The threads holding creation back right now, once per hold; guarded by [registry]'s lock. */
    private val holders = ArrayList<Long>()

    /** Hold creation back for every other thread, until this thread's [release]. */
    fun hold() {
        registry.lock.withLock { holders += currentThreadId() }
    }

    /** End one [hold] of this thread. */
    fun release() {
        registry.lock.withLock { holders.remove(currentThreadId()) }
    }

    /** Whether a hold of another thread keeps this one from creating; the caller holds [registry]'s lock. */
    fun heldBackLocked(): Boolean {
        if (holders.isEmpty()) return false
        val me = currentThreadId()
        return holders.any { it != me }
    }
}

/**
 * The address of one keyed entry: its [family] and its [key]. Also where the
 * entry keeps the inbound subscriptions its `observeFrom` calls made, so an
 * eviction disposes them ([closeInbound]).
 */
internal class KeyedEntry(
    val family: KeyedFamily<*, *>,
    val key: Any,
) {
    private val lock = StoreLock()

    /** The open inbound subscriptions; `null` once [closeInbound] has run. */
    private var inbound: MutableList<Disposable>? = ArrayList()

    /**
     * Serializes the entry's two membership announcements
     * ([KeyedRegistry.announceAdded], [KeyedRegistry.announceEvicted]) and
     * guards [phase]; held while the listeners are told, so one entry's
     * callbacks never interleave across threads. Never [lock]: no listener
     * runs under the inbound lock.
     */
    val announceLock = StoreLock()

    /** How far the entry's announcements got; guarded by [announceLock]. */
    var phase: Phase = Phase.NEW

    /** An entry's announcements: none yet, added, then evicted. */
    enum class Phase { NEW, ADDED, EVICTED }

    /**
     * Keep [subscription] (an `observeFrom` of this entry) until the entry is
     * shut down, and return a handle that disposes it and stops keeping it.
     * An entry already shut down disposes it at once.
     */
    fun track(subscription: Disposable): Disposable {
        val kept = lock.withLock { inbound?.add(subscription) } == true
        if (!kept) {
            subscription.dispose()
            return subscription
        }
        return Disposable {
            lock.withLock { inbound?.remove(subscription) }
            subscription.dispose()
        }
    }

    /**
     * Dispose every inbound subscription kept, outside the lock, and keep no
     * more. Each is disposed even when an earlier one throws; the first
     * failure is rethrown afterwards, with the others suppressed.
     */
    fun closeInbound() {
        val open = lock.withLock { inbound.also { inbound = null } } ?: return
        val failures = open.mapNotNull { runCatching { it.dispose() }.exceptionOrNull() }
        val first = failures.firstOrNull() ?: return
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }
}

/**
 * [subscription], an `observeFrom` of this state, kept by the state's keyed
 * entry so its eviction disposes it ([KeyedEntry.track]); returned as it is
 * for any other state.
 */
internal fun MutableState<*>.trackInbound(subscription: Disposable): Disposable {
    val entry = declaration?.keyed ?: return subscription
    return entry.track(subscription)
}

/**
 * How middleware and logs name this state when it is an entry of a keyed
 * state family: `docs[*]`, after its family — never its key, which can be
 * data; `null` for any other state.
 */
internal fun State<*>.keyedEntryName(): String? {
    val decl = (this as? MutableState<*>)?.declaration
    return decl?.takeIf { it.keyed != null }?.name
}

/**
 * Where a keyed-state entry lives: the name of its [family] and its [key].
 * What [internalKeyedAddress] answers for an entry's state.
 *
 * Equal addresses name the same entry slot (the same family name and an
 * equal key), whichever entry — live or evicted — each was read from: an
 * address names a slot, not an entry. A slot holds a new entry after each
 * eviction, so to follow entries, track them by identity (the entry's
 * `State`), as [KeyedMembershipListener] explains. [toString] names the
 * family, never the key.
 */
@StoreInternalApi
class KeyedAddress internal constructor(
    val family: String,
    val key: Any,
) {
    override fun equals(other: Any?): Boolean = other is KeyedAddress && family == other.family && key == other.key

    override fun hashCode(): Int = family.hashCode() * HASH_MULTIPLIER + key.hashCode()

    override fun toString(): String = "KeyedAddress($family[*])"
}

/**
 * The address of [state] when it is an entry of one of this store's keyed
 * state families ([keyedState]): its family's name and its key. `null` for
 * any other state, and for a state of another store. Keeps answering for an
 * evicted entry's stale handle, and after the store is disposed: it reads the
 * state's declaration only.
 *
 * For issue #21, whose tree addresses a keyed entry by family and key.
 */
@StoreInternalApi
fun Store<*>.internalKeyedAddress(state: State<*>): KeyedAddress? {
    val entry = (state as? MutableState<*>)?.takeIf { it.owningStore === this }?.declaration?.keyed ?: return null
    return KeyedAddress(entry.family.name, entry.key)
}

/**
 * The keyed state family this store declares under [name], or `null` when
 * it declares none (a state of that name included).
 *
 * For `:holdfast-testing`, whose snapshot matcher compares families entry by
 * entry.
 *
 * @throws IllegalStateException if the store is disposed.
 */
@ExperimentalStoreApi
@StoreInternalApi
fun Store<*>.internalKeyedFamily(name: String): KeyedState<*, *>? {
    checkNotDisposed()
    return registry.keyed.family(name)
}

/**
 * Library machinery told of every keyed-state entry a store creates and
 * evicts ([internalObserveKeyedMembership]). Both members have a default.
 *
 * One entry's two callbacks are ordered — [onEntryAdded] always comes first,
 * each at most once, and never interleaved across threads — but the
 * callbacks of different entries of one key are not: an evicted entry's
 * [onEntryEvicted] can arrive after [onEntryAdded] of the key's next entry
 * (it always does when an observer of an earlier `atomic` participant, or
 * another thread, gets the key between the evicting commit's apply pass and
 * its fanout). So track entries by identity (`entry`), not by address: on
 * [onEntryEvicted], drop a key only while it still maps to that very `entry`
 * (`===`).
 */
@StoreInternalApi
interface KeyedMembershipListener {
    /**
     * [entry], the state of [family]'s entry for [key], was created: called
     * once, after its initializer ran, on the first thread to obtain the
     * entry (from a `get`, or a restore that created it) — not necessarily
     * the one that ran the initializer. An entry evicted before any thread
     * obtained it is announced here by the evicting thread, just before its
     * [onEntryEvicted]. Always before that entry's [onEntryEvicted]. Called
     * holding no lock the family took, but the entry's own announcement
     * lock (so a slow listener delays that entry's evicting commit), and
     * under any lock its caller holds — a `get` inside an action runs it
     * under that action's locks. Do not write to the store from it.
     */
    fun onEntryAdded(
        family: String,
        key: Any,
        entry: State<*>,
    ) {}

    /**
     * [entry], the state of [family]'s entry for [key], was evicted: called
     * once, after that entry's [onEntryAdded], on the committing thread, at
     * the start of the evicting transaction's fanout, after the entry's
     * observers and bridge were shut down and before any observer of the
     * commit runs. The store is still held then; do not write to it.
     */
    fun onEntryEvicted(
        family: String,
        key: Any,
        entry: State<*>,
    ) {}
}

/**
 * Tell [listener] of every keyed-state entry this store creates or evicts
 * from now on, until the returned handle is disposed (or the store is). A
 * throwing callback is reported through [Store.uncaughtObserverHandler]
 * (logged while none is set), and the other listeners are still told.
 *
 * For issue #21, whose tree keeps its view of keyed branches in step with the
 * store's entries.
 *
 * @throws IllegalStateException if the store is disposed.
 */
@StoreInternalApi
fun Store<*>.internalObserveKeyedMembership(listener: KeyedMembershipListener): Disposable {
    checkNotDisposed()
    return registry.keyed.addListener(listener)
}

private const val HASH_MULTIPLIER = 31
