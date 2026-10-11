package staff.android.costs.planning

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BatchPlannerTest {
    private val planner: BatchPlanning =
        if (System.getProperty("budget.solution") == "learner") LearnerBatchPlanner else ReferenceBatchPlanner
    private val a = TransferPartition("account-a", 7, "reports")
    private val b = TransferPartition("account-b", 2, "reports")
    private val budget = BatchBudget(2, 10, 2)
    private fun item(id: String, bytes: Long = 1, latest: Long = 150, earliest: Long = 0,
        partition: TransferPartition = a) = PendingTransfer(id, partition, earliest, latest, bytes)
    private val fixture get() = listOf(
        item("north", 6, 120), item("valve", 3, 125, partition = b), item("meter", 4, 130),
        item("roof", 5, 140), item("gate", 2, 160, 101), item("old", 1, 99),
    )
    private fun plan(input: List<PendingTransfer> = fixture, cap: BatchBudget = budget, now: Long = 100) =
        planner.plan(now, input, cap)

    @Test fun workedOpportunityPreservesAuthorityAndExplainsEveryUnscheduledItem() {
        assertEquals(OpportunityPlan(listOf(TransferBatch(a, listOf("north", "meter"), 10),
            TransferBatch(b, listOf("valve"), 3)),
            mapOf("gate" to UnscheduledReason.NotYetEligible, "old" to UnscheduledReason.Expired,
                "roof" to UnscheduledReason.Capacity), 13), plan())
    }
    @Test fun equalDeadlineUsesIdRatherThanInputOrder() {
        assertEquals(listOf("a", "b"), plan(listOf(item("b"), item("a"), item("c")),
            BatchBudget(2, 10, 1)).batches.single().transferIds)
    }
    @Test fun deadlinesPrecedeAlphabeticalId() {
        assertEquals(listOf("z", "a"), plan(listOf(item("a", latest = 130), item("z", latest = 110))).batches.single().transferIds)
    }
    @Test fun everyInputPermutationProducesTheSamePlan() {
        val expected = plan()
        repeat(20) { assertEquals(expected, plan(fixture.shuffled(Random(it)))) }
    }
    @Test fun notBeforeAndLatestStartAreBothInclusive() {
        val result = plan(listOf(item("exact", latest = 100, earliest = 100)))
        assertEquals(listOf("exact"), result.batches.single().transferIds)
        assertTrue(result.unscheduled.isEmpty())
    }
    @Test fun futureAndExpiredAreDifferentOutcomes() {
        assertEquals(mapOf("future" to UnscheduledReason.NotYetEligible, "past" to UnscheduledReason.Expired),
            plan(listOf(item("future", earliest = 101), item("past", latest = 99))).unscheduled)
    }
    @Test fun classificationPrecedenceDoesNotHideWindowStatusBehindSize() {
        assertEquals(mapOf("past" to UnscheduledReason.Expired, "future" to UnscheduledReason.NotYetEligible,
            "ready" to UnscheduledReason.Oversized),
            plan(listOf(item("past", 11, 99), item("future", 11, earliest = 101), item("ready", 11))).unscheduled)
    }
    @Test fun exactByteCapacityFitsButOneByteMoreNeedsAnotherBatch() {
        val result = plan(listOf(item("a", 6), item("b", 4), item("c", 1)))
        assertEquals(listOf(10L, 1L), result.batches.map { it.payloadBytes })
    }
    @Test fun itemCapacityIsIndependentOfBytes() {
        val result = plan(listOf(item("a", 0), item("b", 0), item("c", 0)), BatchBudget(2, 10, 2))
        assertEquals(listOf(listOf("a", "b"), listOf("c")), result.batches.map { it.transferIds })
        assertEquals(0L, result.totalPayloadBytes)
    }
    @Test fun aLaterItemCanJoinAnEarlierBatchAfterBatchBudgetIsFull() {
        assertEquals(listOf("north", "meter"), plan().batches.first().transferIds)
    }
    @Test fun firstFittingBatchWinsWhenSeveralCanAccept() {
        val result = plan(listOf(item("a", 6, 110), item("b", 6, 120), item("c", 3, 130)))
        assertEquals(listOf(listOf("a", "c"), listOf("b")), result.batches.map { it.transferIds })
    }
    @Test fun differentAccountsNeverShareABatch() {
        val result = plan(listOf(item("a", partition = a), item("b", partition = b)))
        assertEquals(2, result.batches.size)
    }
    @Test fun reauthorizationGenerationNeverSharesABatch() {
        val result = plan(listOf(item("a"), item("b", partition = a.copy(authorityGeneration = 8))))
        assertEquals(2, result.batches.size)
    }
    @Test fun differentEndpointIdentityNeverSharesABatch() {
        val result = plan(listOf(item("a"), item("b", partition = a.copy(endpoint = "photos"))))
        assertEquals(2, result.batches.size)
    }
    @Test fun endpointAndIdStringsAreExactNotNormalized() {
        val result = plan(listOf(item("a"), item(" a ", partition = a.copy(endpoint = "reports "))))
        assertEquals(2, result.batches.size)
        assertEquals(listOf(" a "), result.batches.first().transferIds)
    }
    @Test fun zeroBatchBudgetDefersEligibleTransfersWithoutChangingOtherReasons() {
        val result = plan(listOf(item("ready"), item("future", earliest = 101)), budget.copy(maxBatches = 0))
        assertTrue(result.batches.isEmpty())
        assertEquals(mapOf("ready" to UnscheduledReason.Capacity, "future" to UnscheduledReason.NotYetEligible), result.unscheduled)
        assertEquals(0L, result.totalPayloadBytes)
    }
    @Test fun firstFitIsExplicitlyNotGlobalMaximumAdmission() {
        val result = plan(listOf(item("a", 4, 110), item("b", 4, 120), item("c", 6, 130), item("d", 6, 140)))
        assertEquals(listOf(listOf("a", "b"), listOf("c")), result.batches.map { it.transferIds })
        assertEquals(mapOf("d" to UnscheduledReason.Capacity), result.unscheduled)
    }
    @Test fun advancingOneOpportunityChangesEligibilityButDoesNotAutomaticallyReschedule() {
        val input = listOf(item("gate", 2, 160, 101), item("due", 2, 100))
        assertEquals(UnscheduledReason.NotYetEligible, plan(input).unscheduled["gate"])
        val later = plan(input, now = 101)
        assertEquals(listOf("gate"), later.batches.single().transferIds)
        assertEquals(UnscheduledReason.Expired, later.unscheduled["due"])
    }
    @Test fun noTransfersProducesNoRequests() {
        assertEquals(OpportunityPlan(emptyList(), emptyMap(), 0), plan(emptyList()))
    }
    @Test fun invalidIdentityAndWindowAreRejectedBeforeClassification() {
        val invalid = listOf(item(""), item(" \t"), item("x", -1), item("x", earliest = -1),
            item("x", latest = 99, earliest = 100), item("x", partition = a.copy(accountId = " ")),
            item("x", partition = a.copy(authorityGeneration = -1)), item("x", partition = a.copy(endpoint = "")))
        invalid.forEach { bad -> assertThrows(IllegalArgumentException::class.java) { plan(listOf(bad)) } }
    }
    @Test fun invalidNowAndBudgetsAreRejectedEvenForEmptyInput() {
        assertThrows(IllegalArgumentException::class.java) { plan(emptyList(), now = -1) }
        listOf(BatchBudget(0, 10, 1), BatchBudget(1, 0, 1), BatchBudget(1, 10, -1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { plan(emptyList(), it) }
        }
    }
    @Test fun duplicateIdsAreRejectedAcrossDifferentPartitions() {
        assertThrows(IllegalArgumentException::class.java) { plan(listOf(item("same"), item("same", partition = b))) }
    }
    @Test fun boundedInputAccepts256AndRejects257() {
        assertEquals(256, plan((0..255).map { item("id-$it") }, BatchBudget(256, 256, 1)).batches.single().transferIds.size)
        assertThrows(IllegalArgumentException::class.java) { plan((0..256).map { item("id-$it") }) }
    }
    @Test fun byteAdmissionDoesNotWrapAtLongMaximum() {
        val result = plan(listOf(item("a", Long.MAX_VALUE, 110), item("b", 1, 120)), BatchBudget(2, Long.MAX_VALUE, 1))
        assertEquals(Long.MAX_VALUE, result.totalPayloadBytes)
        assertEquals(mapOf("b" to UnscheduledReason.Capacity), result.unscheduled)
    }
    @Test fun aggregatePayloadOverflowThrowsRatherThanPublishingAPartialPlan() {
        assertThrows(ArithmeticException::class.java) {
            plan(listOf(item("a", Long.MAX_VALUE, 110), item("b", 1, 120)), BatchBudget(1, Long.MAX_VALUE, 2))
        }
    }
    @Test fun everyInputAppearsExactlyOnceAndAllOutputBudgetsHold() {
        val input = (0 until 100).map { index -> item("id-$index", (index % 13).toLong(),
            latest = if (index % 7 == 0) 200 else (90 + index).toLong(),
            earliest = if (index % 7 == 0) 101 else 0,
            partition = if (index % 3 == 0) b else a) }
        val result = plan(input, BatchBudget(4, 10, 8))
        val selected = result.batches.flatMap { it.transferIds }
        assertEquals(input.size, selected.size + result.unscheduled.size)
        assertEquals(input.map { it.id }.toSet(), selected.toSet() + result.unscheduled.keys)
        assertEquals(selected.size, selected.toSet().size)
        assertTrue(result.batches.size <= 8)
        result.batches.forEach { batch ->
            assertTrue(batch.transferIds.size in 1..4)
            assertTrue(batch.payloadBytes in 0..10)
            val members = input.filter { it.id in batch.transferIds }
            assertTrue(members.all { it.partition == batch.partition && it.notBeforeMillis <= 100 && it.latestStartMillis >= 100 })
            assertEquals(members.sumOf { it.payloadBytes }, batch.payloadBytes)
        }
    }
    @Test fun resultOwnsCollectionsAndRejectsMutation() {
        val input = fixture.toMutableList()
        val result = plan(input)
        input.clear()
        assertEquals(2, result.batches.size)
        assertThrows(UnsupportedOperationException::class.java) { (result.batches as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.batches.first().transferIds as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.unscheduled as MutableMap).clear() }
    }
    @Test fun unscheduledOutputIsSortedByIdAndInputIsNotMutated() {
        val input = fixture.toMutableList()
        val before = input.toList()
        assertEquals(listOf("gate", "old", "roof"), plan(input).unscheduled.keys.toList())
        assertEquals(before, input)
    }
}
