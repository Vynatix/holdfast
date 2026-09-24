@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlin.reflect.KProperty

// Keyed state families (issue #20, R7; plan decision D18).
//
// `val docs by keyedState<String, Doc> { id -> Doc(id) }` declares a FAMILY:
// one state per key, each created the first time the family is asked for its
// key and dropped again when it is evicted. The family is declared eagerly,
// like a state, under the property's name — families and states share the
// store's names — but its entries live in a registry of their own
// (KeyedRegistry.kt, KeyedFamily.kt), outside the declared states: no entry
// is in `properties` or `StoreSnapshot.stateNames`, and the passes over the
// declared states never see one. Each entry is an ordinary `MutableState`
// with a declaration of kind `Keyed`, so everything that works on a state —
// actions, rollback, frames, `effect`, `derived`, `derivedState`, bridges —
// works on an entry. Creating an entry is not transactional (it is the
// entry's first read); evicting one is (KeyedEviction.kt). Snapshots carry
// every live entry (KeyedSnapshot.kt, KeyedRestorePlanner.kt).

/**
 * A family of states of one type [T], one per key [K]: what
 * [keyedState] declares.
 *
 * ```
 * class DocsStore : Store<DocsStore>() {
 *     val docs by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { id -> "untitled $id" }
 * }
 *
 * store action { docs["a"] mutate "hello" }   // creates entry "a", then writes it
 * store.docs["a"].value                        // "hello" — the same State on every get
 * store.docs.evict("a")                        // drops entry "a" when its transaction commits
 * ```
 *
 * **Entries.** [get] returns the state of a key's entry, creating it the
 * first time — from the family's initializer, exactly as a declared state is
 * created at its first read: the initializer runs once, on the calling
 * thread, reads committed values only and may not write. While the entry is
 * live every [get] of its key returns the same [State] instance, so an entry
 * works everywhere a state does: `mutate`/`update` in an action (staged,
 * rolled back, joined to an `atomic` frame), `effect`, `bridge`,
 * `observeFrom`, `derived`, [derivedState]. Creating an entry is not a write:
 * it happens at once, even inside an action, and a rollback leaves the entry
 * live at its initial value, as it leaves a state materialized. [getOrNull],
 * [contains] and [entries] read which keys are live without creating any.
 *
 * **Eviction is transactional.** [evict] and [evictAll] stage evictions like
 * writes: inside an action (or frame) they commit, or roll back, with it, and
 * outside one they run as a one-shot action. The action's own reads see its
 * staged evictions — [contains], [getOrNull] and [entries] leave the entry
 * out — and the last operation on an entry wins: a [get] of the key, or a
 * write to the entry, after [evict] in the same transaction cancels the
 * eviction (the writes the eviction dropped stay dropped). Inside a
 * `suspendAction`/`suspendAtomic` body only a write cancels it: a [get] there
 * leaves the eviction staged, because nothing tells the body's thread from
 * another coroutine's on the same thread. So does a [get] from an `atomic`
 * frame body that does not enroll this store (unless the frame's policy
 * allows unenrolled writes): the eviction belongs to an enclosing action,
 * which commits whatever the frame does, so a cancel there would escape the
 * frame's rollback. An eviction
 * applies when the transaction commits, together with its writes, so a
 * [snapshot] never sees one without the other. From then on the evicted
 * entry's [State] is a stale handle: it keeps its last value for reads, but
 * writing it (`mutate`, `update`, `bridge`, `observeFrom`) throws
 * [IllegalStateException], its observers and bridge are shut down without a
 * last notification (an `effect` on it afterwards fires once, with its last
 * value, and never again), and the inbound subscriptions its `observeFrom`
 * calls made are disposed. Every other entry keeps its observers and bridges. The
 * next [get] of the key creates a new entry, a new [State], from the
 * initializer.
 *
 * An observer, bridge or event collector reacting to a commit of this store
 * cannot write into that commit any more (see [Store.mutate]); an [evict] or
 * [evictAll] from there is the one write that does not fail: it is deferred
 * until the commit has finished and the store is released, and then evicts
 * the entries that were live at the call (those not evicted meanwhile) in a
 * transaction of its own (id `Evict`). That transaction never waits for the
 * store: when the store is busy by then, it is handed to the store's holder,
 * which runs it once it releases the store.
 *
 * **Snapshots.** [snapshot] captures every live entry of every family, under
 * the family's name, and [restore] puts them back — creating the entries a
 * snapshot holds that are not live, never evicting one it does not hold.
 * [StoreSnapshot.keysOf] lists a snapshot's keys of a family, and
 * `snapshot[docs[key]]` reads one entry. A family declared with a `codec`
 * for its values and a `keyCodec` for its keys can be encoded: `encode()`
 * writes it as a JSON object under its name, one member per entry. [reset]
 * re-runs the initializer of every live entry and never evicts one.
 *
 * **Tags** apply to every entry of the family: [State.tags] of an entry is
 * the family's tags. A [StateTag.Secret] family's values are withheld as a
 * Secret state's are, but not its keys — keys are addresses, written by
 * `encode()`, shown by [StoreSnapshot.render] and listed by
 * [StoreSnapshot.keysOf], so do not use sensitive data as a key.
 * [StateTag.UserAuthored] and [StateTag.Remote] families take part in
 * snapshot scopes, `encode(includeRemote)` and sterile restores as states
 * with those tags do.
 *
 * **Names.** The library never writes a key into a message, a `toString` or
 * a middleware sample: an entry is named after its family, `Store.docs[*]`.
 * (A snapshot's `encode()`, `render()` and `keysOf` are where keys do
 * appear: they are the snapshot's content, not a message.)
 *
 * Experimental (issue #20, R7).
 */
@ExperimentalStoreApi
sealed interface KeyedState<K : Any, T : Any> {
    /**
     * The state of [key]'s entry, created from the family's initializer if
     * the key has no live entry. The same instance on every call while the
     * entry is live. Called in a transaction that staged the entry's
     * eviction, on its thread, it cancels that eviction (the last operation
     * wins) — except inside a `suspendAction`/`suspendAtomic` body, or an
     * `atomic` frame body that does not enroll this store (and whose policy
     * does not allow unenrolled writes), where it leaves the eviction staged
     * (and returns the entry that commit evicts); write to the entry to keep
     * it — for such a frame, after it, in the action that staged the
     * eviction.
     *
     * @throws IllegalStateException if the store is disposed, or for an
     *   initializer cycle; what the initializer throws propagates, and no
     *   entry is created.
     */
    operator fun get(key: K): State<T>

    /**
     * The state of [key]'s live entry, or `null` when it has none; never
     * creates one. On the thread running an action of this store, an entry
     * whose eviction the action staged has none.
     *
     * @throws IllegalStateException if the store is disposed.
     */
    fun getOrNull(key: K): State<T>?

    /**
     * Whether [key] has a live entry ([getOrNull] is not `null`).
     *
     * @throws IllegalStateException if the store is disposed.
     */
    operator fun contains(key: K): Boolean

    /**
     * Every live entry, by key, in the order the entries were created (a key
     * evicted and created again comes last): a copy, whose [State]s are live.
     * On the thread running an action of this store, the entries whose
     * eviction it staged are left out.
     *
     * @throws IllegalStateException if the store is disposed.
     */
    val entries: Map<K, State<T>>

    /**
     * Evict [key]'s entry, if it has a live one: staged in the transaction
     * of this store open on this thread, else as a one-shot action. The
     * transaction's pending write to the entry is dropped with it. Called
     * from that commit's fanout (an observer of this store), the eviction of
     * the entry live now is deferred until the commit has finished — run
     * then by a transaction of its own that never waits for the store; see
     * [KeyedState].
     *
     * @throws IllegalStateException if the store is disposed; from inside a
     *   state initializer, a schema migration or a derived state's compute;
     *   or when a `suspendAction`/`suspendAtomic` holds the store and its
     *   transaction has applied and is still committing, from a thread that
     *   is not part of that commit.
     * @throws UnenrolledStoreException inside an `atomic(...)` frame that does
     *   not enroll this store (unless its policy allows unenrolled writes).
     */
    fun evict(key: K)

    /**
     * Evict every live entry, as [evict] evicts one: one staged eviction per
     * entry live at the call, in one transaction — deferred from a commit's
     * fanout, those same entries (never one created after the call).
     *
     * @throws IllegalStateException as [evict].
     * @throws UnenrolledStoreException as [evict].
     */
    fun evictAll()

    /**
     * `val docs by keyedState<K, T> { … }`: returns this family itself, so
     * the property's type is `KeyedState<K, T>`.
     */
    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): KeyedState<K, T> = this
}

/**
 * What [keyedState] returns: delegating a property to it
 * (`val docs by keyedState<String, Doc> { … }`) declares the family on its
 * store under the property's name, and the property then holds the
 * [KeyedState].
 *
 * Experimental (issue #20, R7).
 */
@ExperimentalStoreApi
class KeyedStateProvider<K : Any, T : Any> internal constructor(
    private val store: Store<*>,
    private val spec: KeyedFamilySpec<K, T>,
) {
    /**
     * Declare the family on the store under [property]'s name, running no
     * initializer. A second declaration of that name fails, as for a state,
     * unless the same declaration runs again (the same member property of
     * another object over the store, or a local delegated property): it binds
     * to the family declared first.
     *
     * @throws IllegalStateException if the store is disposed, or already
     *   declares a state or another family under the name.
     * @throws IllegalArgumentException for a refused tag combination (see
     *   [StateTag]); the message names the family.
     */
    operator fun provideDelegate(
        thisRef: Any?,
        property: KProperty<*>,
    ): KeyedState<K, T> {
        store.checkNotDisposed()
        validateTags("${store.displayName}.${property.name}", spec.tags)
        return store.registry.keyed.declare(KeyedFamily(store, property.name, spec, property, local = thisRef == null))
    }
}

/**
 * Declare a keyed state family of this store: one state of type [T] per key
 * of type [K], each created from [initialize] the first time its key is
 * needed. Delegate a property to it:
 *
 * ```
 * val docs by keyedState<String, Doc>(codec = docCodec, keyCodec = StringCodec) { id -> Doc(id) }
 * ```
 *
 * The parameters are those of `state(...)`, applied to every entry:
 * [transformer] and [distinct] as for a state; [codec] encodes an entry's raw
 * value and [keyCodec] its key, and a family without both is listed as
 * unencodable by `encode()`; [tags] (copied) are every entry's tags. See
 * [KeyedState] for how entries are created, evicted, snapshotted and reset.
 *
 * Experimental (issue #20, R7).
 *
 * @throws IllegalStateException if the store is disposed.
 */
@ExperimentalStoreApi
@Suppress("LongParameterList") // One family's policy, as the experimental state(...) overload has one state's.
fun <K : Any, T : Any> Store<*>.keyedState(
    transformer: Transformer<T>? = null,
    distinct: Boolean = false,
    codec: StateCodec<T>? = null,
    keyCodec: StateCodec<K>? = null,
    tags: Set<StateTag> = emptySet(),
    initialize: (K) -> T,
): KeyedStateProvider<K, T> {
    checkNotDisposed()
    return KeyedStateProvider(this, KeyedFamilySpec(transformer, distinct, codec, keyCodec, tags.toSet(), initialize))
}

/** A keyed state family's policy, as [keyedState] was given it: what every entry is created with. */
internal class KeyedFamilySpec<K : Any, T : Any>(
    val transformer: Transformer<T>?,
    val distinct: Boolean,
    val codec: StateCodec<T>?,
    val keyCodec: StateCodec<K>?,
    val tags: Set<StateTag>,
    val initialize: (K) -> T,
)
