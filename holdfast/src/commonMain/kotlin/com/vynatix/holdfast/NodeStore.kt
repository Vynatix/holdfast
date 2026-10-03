package com.vynatix.holdfast

/**
 * A non-recursive [Store] base for anonymous and inline child stores.
 *
 * `Store<Self>` is self-typed, and an anonymous object has no name to write
 * `object : Store<X>()` with; `NodeStore` closes the type parameter so an
 * inline child can be declared in place, typically behind a consumer
 * interface:
 *
 * ```
 * interface Draft {
 *     val text: State<String>
 *     fun edit(value: String)
 * }
 *
 * val draft: Draft = object : NodeStore(), Draft {
 *     override val text by state { "" }
 *     override fun edit(value: String) { text mutate value }
 * }
 * ```
 *
 * Inside the object, `mutate`, `update` and the rest of the [Store] DSL work
 * as in any subclass, with one difference: the receiver of its `action { }`
 * is the `NodeStore`, which declares none of the object's states, and the
 * `@StoreActionDsl` marker hides the object's own members behind it. A
 * method of the object that writes inside an action names the object
 * explicitly (`val self = this; action { self.text mutate v }`); a bare
 * `text mutate v` outside an action synthesizes its one-shot action as on
 * any store. From outside, write through the interface's own methods, or
 * cast to the store (`(draft as NodeStore) action { draft.text mutate "x" }`).
 * The store takes part in `snapshot()`/`restore()`/`reset()` like any other.
 *
 * Failure messages name the store by its class's simple name, which an
 * anonymous object does not have; such a store is named `Store` instead.
 */
@ExperimentalStoreApi
open class NodeStore : Store<NodeStore>()
