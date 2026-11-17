package com.voxarium.remote.room

import com.voxarium.remote.data.room.ScribeQueue
import org.junit.Assert.*
import org.junit.Test

/** No sleeps, Android, editor, repository or network; time is an explicit monotonic input. */
class ScribeQueueTest {
    private fun queue(source: String = "A", dirty: Boolean = false, scribe: Boolean = true): ScribeQueue =
        ScribeQueue(source, dirtyAtJoin = dirty, isScribe = scribe).also { it.initialize(source, 0) }

    private fun send(q: ScribeQueue, id: Long = 1, now: Long = 500): ScribeQueue.Snapshot =
        requireNotNull(q.nextSnapshot(now)).also { q.sent(it, id) }

    @Test fun openAndRepeatedTicksNeverWrite() {
        val q = queue()
        for (now in listOf(0L, 500L, 3_000L, 10_000L)) assertNull(q.nextSnapshot(now, force = true))
        assertEquals(0L, q.view().generation)
        assertFalse(q.view().dirty)
        assertTrue(q.view().canLeave)
    }

    @Test fun normalizedInitialIsNotExactSourceAndDoesNotWrite() {
        val exact = "---\r\ntitle: Example\r\n---\r\n* item\r\n"
        val normalized = "---\r\ntitle: Example\r\n---\r\n- item\r\n"
        val q = ScribeQueue(exact, isScribe = true)
        q.initialize(normalized, 0)
        assertNull(q.nextSnapshot(100_000, force = true))
        assertEquals(exact, q.view().materializedMarkdown)
        assertEquals(normalized, q.view().normalizedInitialMarkdown)
        assertTrue(q.view().canLeave)
    }

    @Test fun editThenRevertToNormalizedInitialDoesNotRewriteSource() {
        val q = ScribeQueue("* item", isScribe = true)
        q.initialize("- item", 0)
        q.changed("- item edited", 1)
        q.changed("- item", 2)
        assertNull(q.nextSnapshot(502))
        assertEquals("* item", q.view().materializedMarkdown)
        assertFalse(q.view().dirty)
    }

    @Test fun tenCleanOpenCloseCyclesIncludingEmptyAndNoncanonicalDocuments() {
        for (source in listOf("", "* list\r\n", "| a |\n| - |\n| b |", "- [ ] task\n")) {
            repeat(10) {
                val q = ScribeQueue(source)
                q.setScribe(true)
                q.initialize(source.replace("* list", "- list"), 0)
                assertNull(q.nextSnapshot(3_000, true))
                assertTrue(q.view().canLeave)
            }
        }
    }

    @Test fun debounceHasExactBoundary() {
        val q = queue()
        q.changed("B", 20)
        assertNull(q.nextSnapshot(519))
        assertEquals("B", q.nextSnapshot(520)?.markdown)
        assertTrue(q.view().dirty)
        assertFalse(q.view().canLeave)
    }

    @Test fun subsequentChangeRestartsQuietDebounce() {
        val q = queue()
        q.changed("B", 0)
        q.changed("C", 450)
        assertNull(q.nextSnapshot(949))
        assertEquals("C", q.nextSnapshot(950)?.markdown)
    }

    @Test fun continuousTypingCannotPostponeBeyondThreeSeconds() {
        val q = queue()
        q.changed("first", 0)
        for (now in 400L..2_800L step 400) {
            q.changed("edit $now", now)
            assertNull(q.nextSnapshot(now))
        }
        assertNull(q.nextSnapshot(2_999))
        assertEquals("edit 2800", q.nextSnapshot(3_000)?.markdown)
    }

    @Test fun unchangedSnapshotsDoNotCreateGenerationOrRestartDebounce() {
        val q = queue()
        q.changed("A", 100)
        assertEquals(0L, q.view().generation)
        q.changed("B", 200)
        q.changed("B", 650)
        assertEquals(1L, q.view().generation)
        assertEquals("B", q.nextSnapshot(700)?.markdown)
    }

    @Test fun forceBypassesOnlyTiming() {
        val q = queue(scribe = false)
        q.changed("B", 1)
        assertNull(q.nextSnapshot(1, true))
        q.setScribe(true)
        val snapshot = requireNotNull(q.nextSnapshot(1, true))
        q.sent(snapshot, 1)
        assertNull(q.nextSnapshot(10_000, true))
        assertFalse(q.view().canLeave)
    }

    @Test fun noSnapshotBeforeInitialStateEvenWithEarlyDirtyAndRole() {
        val q = ScribeQueue("A", dirtyAtJoin = true)
        q.setScribe(true)
        assertNull(q.nextSnapshot(0, true))
        assertFalse(q.view().canLeave)
        q.initialize("B", 1)
        assertEquals("B", q.nextSnapshot(501)?.markdown)
    }

    @Test fun dirtyAtJoinSurvivesEarlyScribeBeforeInitialize() {
        val q = ScribeQueue("old", dirtyAtJoin = true)
        q.setScribe(true)
        q.initialize("merged", 0)
        assertEquals(1L, q.view().generation)
        assertTrue(q.view().dirty)
        assertEquals("merged", q.nextSnapshot(500)?.markdown)
    }

    @Test fun dirtyAtJoinMayNotUseNormalizedInitialAsCleanBaseline() {
        val q = ScribeQueue("old", dirtyAtJoin = true, isScribe = true)
        q.initialize("already edited", 0)
        q.changed("new edit", 100)
        q.changed("already edited", 200)
        assertEquals("already edited", q.nextSnapshot(700)?.markdown)
        assertTrue(q.view().dirty)
    }

    @Test fun inheritedDebtAlreadyExactOnDiskNeedsNoPut() {
        val q = queue(dirty = true)
        assertNull(q.nextSnapshot(500))
        assertFalse(q.view().dirty)
    }

    @Test fun dirtyAtJoinWaitsForScribeAndKeepsOriginalDeadline() {
        val q = ScribeQueue("A", dirtyAtJoin = true)
        q.initialize("B", 0)
        assertNull(q.nextSnapshot(10_000))
        q.setScribe(true)
        assertEquals("B", q.nextSnapshot(10_000)?.markdown)
    }

    @Test fun earlyChangedSnapshotIsNotAbsorbedIntoInitialCleanBaseline() {
        val q = ScribeQueue("A")
        q.changed("B", 10)
        q.setScribe(true)
        q.initialize("A", 20)
        assertEquals("A", q.view().normalizedInitialMarkdown)
        assertEquals("B", q.view().snapshot?.markdown)
        assertEquals(1L, q.view().generation)
        assertEquals("B", q.nextSnapshot(510)?.markdown)
    }

    @Test fun remoteChangesAfterBaselineBeforeFirstRenderStillOweMaterialization() {
        val q = queue()
        q.changed("remote merged", 1)
        assertEquals("A", q.view().normalizedInitialMarkdown)
        assertEquals("remote merged", q.nextSnapshot(501)?.markdown)
    }

    @Test fun cleanRoleChangesCreateNoDebt() {
        val q = queue(scribe = false)
        repeat(5) { q.setScribe(true); q.setScribe(false) }
        q.setScribe(true)
        assertNull(q.nextSnapshot(3_000, true))
        assertEquals(0L, q.view().generation)
    }

    @Test fun roleLossAndHandoffPreserveExistingDebt() {
        val q = queue()
        q.changed("B", 0)
        q.setScribe(false)
        assertNull(q.nextSnapshot(1_000))
        assertTrue(q.view().dirty)
        q.setScribe(true)
        assertEquals("B", q.nextSnapshot(1_000)?.markdown)
    }

    @Test fun onlyOneImmutableSnapshotCanBeInFlight() {
        val q = queue()
        q.changed("B", 0)
        val first = send(q)
        q.changed("C", 600)
        assertEquals("B", first.markdown)
        assertEquals("B", q.view().inFlight?.snapshot?.markdown)
        assertNull(q.nextSnapshot(3_000, true))
        assertEquals("C", q.view().snapshot?.markdown)
    }

    @Test fun sendEnqueueAloneIsNeverDurability() {
        val q = queue()
        q.changed("B", 0)
        send(q)
        assertTrue(q.view().dirty)
        assertFalse(q.view().canLeave)
        assertEquals("A", q.view().materializedMarkdown)
    }

    @Test fun matchingAckConfirmsSentSnapshotNotNewerEdits() {
        val q = queue()
        q.changed("B", 0)
        val first = send(q, 42)
        q.changed("C", 600)
        assertTrue(q.acknowledge(42))
        assertEquals(first.generation, q.view().confirmedGeneration)
        assertEquals("B", q.view().materializedMarkdown)
        assertTrue(q.view().dirty)
        assertFalse(q.view().canLeave)
        val second = send(q, 43, 1_100)
        assertEquals("C", second.markdown)
        assertTrue(q.acknowledge(43))
        assertFalse(q.view().dirty)
        assertTrue(q.view().canLeave)
    }

    @Test fun ackMayNotClearNewerGenerationEvenIfItRevertedToSameSentText() {
        val q = queue()
        q.changed("B", 0)
        send(q)
        q.changed("C", 600)
        q.changed("B", 700)
        assertTrue(q.acknowledge(1))
        assertEquals(1L, q.view().confirmedGeneration)
        assertEquals(3L, q.view().generation)
        assertTrue(q.view().dirty)
        // A separate exact no-op decision can settle it; the ACK did not erase generation 3.
        assertNull(q.nextSnapshot(700, true))
        assertFalse(q.view().dirty)
    }

    @Test fun wrongDuplicateAndStaleRequestIdsCannotCompleteAnAttempt() {
        val q = queue()
        q.changed("B", 0)
        send(q, 10)
        assertFalse(q.acknowledge(9))
        assertNotNull(q.view().inFlight)
        assertTrue(q.acknowledge(10))
        assertFalse(q.acknowledge(10))
        q.changed("C", 1_000)
        send(q, 11, 1_500)
        assertFalse(q.acknowledge(10))
        assertEquals(11L, q.view().inFlight?.requestId)
        assertTrue(q.view().dirty)
    }

    @Test fun revertingToOldSourceWhilePutPendingStillNeedsSecondWrite() {
        val q = queue()
        q.changed("B", 0)
        send(q)
        q.changed("A", 600)
        assertNull(q.nextSnapshot(1_100, true))
        assertTrue(q.view().dirty)
        q.acknowledge(1)
        assertEquals("A", q.nextSnapshot(1_100)?.markdown)
    }

    @Test fun exactRevertWithNoInFlightNeedsNoPut() {
        val q = queue()
        q.changed("B", 0)
        q.changed("A", 100)
        assertNull(q.nextSnapshot(600))
        assertTrue(q.view().canLeave)
    }

    @Test fun nonScribeNeedsExactCurrentMaterializedText() {
        val q = queue(scribe = false)
        q.changed("B\r\n", 0)
        assertFalse(q.materialized("B\n"))
        assertFalse(q.materialized("A"))
        assertFalse(q.view().canLeave)
        assertNull(q.nextSnapshot(10_000, true))
        assertTrue(q.materialized("B\r\n"))
        assertTrue(q.view().canLeave)
    }

    @Test fun oldMaterializedSnapshotCannotConfirmNewerNonScribeEdits() {
        val q = queue(scribe = false)
        q.changed("B", 0)
        q.changed("C", 100)
        assertFalse(q.materialized("B"))
        assertEquals(0L, q.view().confirmedGeneration)
        assertTrue(q.view().dirty)
        assertTrue(q.materialized("C"))
        q.setScribe(true)
        assertNull(q.nextSnapshot(1_000, true))
    }

    @Test fun externalMatchingChangeCannotReplaceAckForOwnPending() {
        val q = queue()
        q.changed("B", 0)
        send(q)
        q.setScribe(false)
        assertFalse(q.materialized("B"))
        assertNotNull(q.view().inFlight)
        assertFalse(q.view().canLeave)
        assertTrue(q.acknowledge(1))
        assertTrue(q.view().canLeave)
    }

    @Test fun confirmedSaveStartsFreshDebounceWindowForNextBurst() {
        val q = queue()
        q.changed("B", 0)
        send(q)
        q.acknowledge(1)
        q.changed("C", 10_000)
        assertNull(q.nextSnapshot(10_499))
        assertEquals("C", q.nextSnapshot(10_500)?.markdown)
    }

    @Test fun roleLossDuringPendingDoesNotDiscardAttemptOrNewDebt() {
        val q = queue()
        q.changed("B", 0)
        send(q)
        q.setScribe(false)
        q.changed("C", 600)
        assertTrue(q.acknowledge(1))
        assertNull(q.nextSnapshot(5_000, true))
        assertTrue(q.view().dirty)
        assertTrue(q.materialized("C"))
        assertTrue(q.view().canLeave)
    }

    @Test fun stopRetainsRecoverySnapshotsAndCannotRetryUnknownAttempt() {
        val q = queue()
        q.changed("B", 0)
        val pending = send(q)
        q.changed("C", 600)
        q.stop()
        q.stop()
        assertSame(pending, q.view().inFlight?.snapshot)
        assertEquals("C", q.view().snapshot?.markdown)
        assertFalse(q.acknowledge(1))
        assertFalse(q.materialized("C"))
        assertNull(q.nextSnapshot(3_000, true))
        assertFalse(q.view().canLeave)
        assertThrows(IllegalStateException::class.java) { q.setScribe(true) }
    }

    @Test fun previousViewsAreImmutableAndRedacted() {
        val q = queue(source = "private markdown")
        val before = q.view()
        q.changed("private edit", 0)
        send(q)
        assertEquals("private markdown", before.snapshot?.markdown)
        assertFalse(before.dirty)
        for (value in listOf(q.view(), q.view().snapshot, q.view().inFlight)) {
            assertFalse(value.toString().contains("private"))
        }
    }

    @Test fun staleProposalsAndReusedIdsFailClosed() {
        val q = queue()
        q.changed("B", 0)
        val stale = requireNotNull(q.nextSnapshot(500))
        q.changed("C", 600)
        assertThrows(IllegalStateException::class.java) { q.sent(stale, 1) }
        send(q, 1, 1_100)
        q.acknowledge(1)
        q.changed("D", 1_200)
        val latest = requireNotNull(q.nextSnapshot(1_700))
        assertThrows(IllegalArgumentException::class.java) { q.sent(latest, 1) }
        assertNull(q.view().inFlight)
    }

    @Test fun roleLossInvalidatesAProposalBeforeReservation() {
        val q = queue()
        q.changed("B", 0)
        val snapshot = requireNotNull(q.nextSnapshot(500))
        q.setScribe(false)
        assertThrows(IllegalStateException::class.java) { q.sent(snapshot, 1) }
    }

    @Test fun initializedBaselineCannotBeReplacedByLaterRender() {
        val q = queue()
        q.changed("B", 0)
        assertThrows(IllegalStateException::class.java) { q.initialize("B", 1) }
        assertEquals("A", q.view().normalizedInitialMarkdown)
        assertTrue(q.view().dirty)
    }

    @Test fun clockRejectsBackwardsTimeAndDurationsCannotBeInvalid() {
        val q = queue()
        q.changed("B", 100)
        assertThrows(IllegalArgumentException::class.java) { q.nextSnapshot(99) }
        assertThrows(IllegalArgumentException::class.java) { q.changed("C", -1) }
        assertThrows(IllegalArgumentException::class.java) { ScribeQueue("A", debounceMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { ScribeQueue("A", maxDelayMillis = 499) }
    }

    @Test fun timeNearLongLimitDoesNotOverflowDeadlineCalculation() {
        val q = queue()
        q.changed("B", Long.MAX_VALUE - 500)
        assertNull(q.nextSnapshot(Long.MAX_VALUE - 1))
        assertEquals("B", q.nextSnapshot(Long.MAX_VALUE)?.markdown)
    }

    @Test fun injectedDebounceAndMaxDelayAreIndependentOfWallClock() {
        val q = ScribeQueue("A", isScribe = true, debounceMillis = 10, maxDelayMillis = 20)
        q.initialize("A", 0)
        q.changed("B", 5)
        q.changed("C", 14)
        q.changed("D", 23)
        assertNull(q.nextSnapshot(24))
        assertEquals("D", q.nextSnapshot(25)?.markdown)
    }
}
