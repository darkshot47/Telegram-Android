package com.telefarm.core.delete

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deleting a message, and how far the deletion reaches. */
class DeletionTest {

    @Test
    fun `a message that can be deleted for everybody is`() {
        assertEquals(DeletionScope.EVERYONE, Deletion.scope(forAllUsers = true, forSelfOnly = true))
    }

    @Test
    fun `a message that can only be deleted for yourself is`() {
        assertEquals(DeletionScope.ONLY_ME, Deletion.scope(forAllUsers = false, forSelfOnly = true))
    }

    @Test
    fun `a message that cannot be deleted says so`() {
        assertNull(Deletion.scope(forAllUsers = false, forSelfOnly = false))
    }

    @Test
    fun `a message deletable for everybody but not for yourself alone is still deletable`() {
        assertEquals(DeletionScope.EVERYONE, Deletion.scope(forAllUsers = true, forSelfOnly = false))
    }

    @Test
    fun `both choices are offered when both are possible`() {
        assertEquals(
            listOf(DeletionScope.EVERYONE, DeletionScope.ONLY_ME),
            Deletion.choices(forAllUsers = true, forSelfOnly = true)
        )
    }

    @Test
    fun `one choice is offered when only one is possible, so nobody is asked a question with one answer`() {
        assertEquals(
            listOf(DeletionScope.ONLY_ME),
            Deletion.choices(forAllUsers = false, forSelfOnly = true)
        )
    }

    @Test
    fun `a message deletable for everybody can also be deleted for yourself`() {
        assertEquals(
            listOf(DeletionScope.EVERYONE, DeletionScope.ONLY_ME),
            Deletion.choices(forAllUsers = true, forSelfOnly = false)
        )
    }

    @Test
    fun `no choice is offered for a message that cannot be deleted`() {
        assertTrue(Deletion.choices(forAllUsers = false, forSelfOnly = false).isEmpty())
    }

    @Test
    fun `the widest choice comes first`() {
        val choices = Deletion.choices(forAllUsers = true, forSelfOnly = true)
        assertEquals(DeletionScope.EVERYONE, choices.first())
    }

    @Test
    fun `deleting for everybody changes what somebody else sees`() {
        assertTrue(Deletion.reachesOthers(DeletionScope.EVERYONE))
    }

    @Test
    fun `deleting for yourself does not`() {
        assertFalse(Deletion.reachesOthers(DeletionScope.ONLY_ME))
    }

    @Test
    fun `every offered choice is a scope that can be carried out`() {
        for (forAll in listOf(true, false)) {
            for (forSelf in listOf(true, false)) {
                val choices = Deletion.choices(forAll, forSelf)
                val scope = Deletion.scope(forAll, forSelf)
                if (scope == null) {
                    assertTrue(choices.isEmpty())
                } else {
                    assertTrue(scope in choices)
                }
            }
        }
    }

    @Test
    fun `there are exactly two scopes`() {
        assertEquals(2, DeletionScope.entries.size)
    }
}
