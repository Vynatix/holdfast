@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachment
import com.vynatix.holdfast.internalAttachments
import com.vynatix.holdfast.observerCount
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ContractStore : Store<ContractStore>() {
    val n by state { 0 }
    val label by state { "init" }
}

private class ContractParent(
    child: ContractStore,
) : Store<ContractParent>() {
    val leaf by store { child }
}

private class OrderMiddleware(
    private val tag: String,
    private val log: MutableList<String>,
) : Middleware<ContractStore>() {
    override fun onTransactionStarted(context: MiddlewareContext<ContractStore>) {
        log += "$tag.started"
    }

    override fun onTransactionCompleted(context: MiddlewareContext<ContractStore>) {
        log += "$tag.completed"
    }
}

private class VetoMiddleware : Middleware<ContractStore>() {
    override fun onTransactionCompleted(context: MiddlewareContext<ContractStore>) {
        error("vetoed")
    }
}

/**
 * T2: a store attached to a tree behaves exactly like an unattached one. The
 * same case set runs against a plain store and one declared as a child of a
 * parent store (`store { }`), materialized.
 */
internal abstract class AttachedStoreContractCases {
    protected abstract fun newStore(): ContractStoreHandle

    class ContractStoreHandle(
        val store: ContractStore,
        val parent: Store<*>?,
    )

    @Test
    fun nestedSavepointsMergeIntoTheParentAndRollBackWithIt() {
        val s = newStore().store
        s action {
            n mutate 1
            action { n mutate 2 }
            assertEquals(2, n.value)
        }
        assertEquals(2, s.n.value)
        val r =
            s action {
                n mutate 10
                action { n mutate 11 }
                error("outer fails")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals(2, s.n.value)
    }

    @Test
    fun rollbackNeverTouchesState() {
        val s = newStore().store
        var fired = 0
        s.n effect { fired++ }
        val baseline = fired
        val r =
            s action {
                n mutate 99
                error("no")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals(0, s.n.value)
        assertEquals(baseline, fired)
    }

    @Test
    fun rawSnapshotRoundTrips() {
        val s = newStore().store
        s action {
            n mutate 7
            label mutate "seven"
        }
        val snap = s.snapshot()
        s action { n mutate 8 }
        s.restore(snap)
        assertEquals(7, s.n.value)
        assertEquals("seven", s.label.value)
    }

    @Test
    fun middlewareOrderIsLastRegisteredOutermost() {
        val s = newStore().store
        val log = ArrayList<String>()
        s.middlewares(OrderMiddleware("A", log), OrderMiddleware("B", log))
        s action { n mutate 1 }
        assertEquals(listOf("B.started", "A.started", "A.completed", "B.completed"), log)
    }

    @Test
    fun atomicCommitsAndAVetoRollsBack() {
        val a = newStore().store
        val b = newStore().store
        val ok =
            atomic(a, b) {
                a { n mutate 1 }
                b { n mutate 2 }
            }
        assertIs<TransactionResult.Success<*>>(ok)
        assertEquals(1, a.n.value)
        assertEquals(2, b.n.value)
        b.middlewares(VetoMiddleware())
        val vetoed =
            atomic(a, b) {
                a { n mutate 10 }
                b { n mutate 20 }
            }
        assertIs<TransactionResult.Error>(vetoed)
        assertEquals(1, a.n.value)
        assertEquals(2, b.n.value)
    }

    @Test
    fun effectFiresOnCommit() {
        val s = newStore().store
        val seen = ArrayList<Int>()
        s.n effect { seen += this }
        s action { n mutate 3 }
        assertEquals(listOf(0, 3), seen)
    }

    @Test
    fun disposeIsIdempotent() {
        val s = newStore().store
        s.dispose()
        s.dispose()
        assertTrue(s.isDisposed)
    }

    @Test
    fun attachmentInstallsNoObserversAndNoMiddleware() {
        val handle = newStore()
        val s = handle.store
        assertEquals(0, s.n.observerCount)
        assertTrue(s.snapshotMiddleware().isEmpty())
        // The tree keeps one attachment on a child for the child's whole life: its node, never an observer.
        val expectedAttachments = if (handle.parent == null) 0 else 1
        assertEquals(expectedAttachments, s.internalAttachments().size)
    }

    @Test
    fun aDisposedParentLeavesItsChildUsable() {
        val handle = newStore()
        handle.parent?.dispose()
        val s = handle.store
        s action { n mutate 5 }
        assertEquals(5, s.n.value)
        assertTrue(!s.isDisposed)
        assertNull(s.internalAttachment(treeMembershipKey)?.parentEdge?.value, "released: it has no parent")
        val expectedAttachments = if (handle.parent == null) 0 else 1
        assertEquals(expectedAttachments, s.internalAttachments().size)
    }
}

internal class UnattachedStoreContractTest : AttachedStoreContractCases() {
    override fun newStore() = ContractStoreHandle(ContractStore(), parent = null)
}

internal class AttachedStoreContractTest : AttachedStoreContractCases() {
    override fun newStore(): ContractStoreHandle {
        val child = ContractStore()
        val parent = ContractParent(child)
        check(parent.leaf === child)
        return ContractStoreHandle(child, parent)
    }
}
