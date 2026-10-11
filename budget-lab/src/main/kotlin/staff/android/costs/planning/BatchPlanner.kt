package staff.android.costs.planning

import java.util.Collections

data class TransferPartition(
    val accountId: String,
    val authorityGeneration: Long,
    val endpoint: String,
)
data class PendingTransfer(
    val id: String,
    val partition: TransferPartition,
    val notBeforeMillis: Long,
    val latestStartMillis: Long,
    val payloadBytes: Long,
)
data class BatchBudget(
    val maxItems: Int,
    val maxPayloadBytes: Long,
    val maxBatches: Int,
)
enum class UnscheduledReason { Expired, NotYetEligible, Oversized, Capacity }
data class TransferBatch(
    val partition: TransferPartition,
    val transferIds: List<String>,
    val payloadBytes: Long,
)
data class OpportunityPlan(
    val batches: List<TransferBatch>,
    val unscheduled: Map<String, UnscheduledReason>,
    val totalPayloadBytes: Long,
)
interface BatchPlanning {
    fun plan(nowMillis: Long, transfers: List<PendingTransfer>, budget: BatchBudget): OpportunityPlan
}

/** One supplied opportunity; latest-start windows, not guaranteed delivery or energy. */
object ReferenceBatchPlanner : BatchPlanning {
    override fun plan(nowMillis: Long, transfers: List<PendingTransfer>, budget: BatchBudget): OpportunityPlan {
        val input = transfers.toList()
        validate(nowMillis, input, budget)
        val pending = sortedMapOf<String, UnscheduledReason>()
        val eligible = input.filter { item ->
            val reason = when {
                item.latestStartMillis < nowMillis -> UnscheduledReason.Expired
                item.notBeforeMillis > nowMillis -> UnscheduledReason.NotYetEligible
                item.payloadBytes > budget.maxPayloadBytes -> UnscheduledReason.Oversized
                else -> null
            }
            if (reason != null) pending[item.id] = reason
            reason == null
        }.sortedWith(compareBy<PendingTransfer> { it.latestStartMillis }.thenBy { it.id })
        val batches = mutableListOf<BuildingBatch>()
        for (item in eligible) {
            val existing = batches.firstOrNull {
                it.partition == item.partition && it.ids.size < budget.maxItems &&
                    item.payloadBytes <= budget.maxPayloadBytes - it.bytes
            }
            val target = existing ?: if (batches.size < budget.maxBatches) {
                BuildingBatch(item.partition).also { batches.add(it) }
            } else null
            if (target == null) {
                pending[item.id] = UnscheduledReason.Capacity
            } else {
                target.ids.add(item.id)
                target.bytes = Math.addExact(target.bytes, item.payloadBytes)
            }
        }
        val frozen = batches.map {
            TransferBatch(it.partition, immutableList(it.ids), it.bytes)
        }
        val total = frozen.fold(0L) { sum, batch -> Math.addExact(sum, batch.payloadBytes) }
        return OpportunityPlan(immutableList(frozen), Collections.unmodifiableMap(pending), total)
    }

    private class BuildingBatch(val partition: TransferPartition) {
        val ids = mutableListOf<String>()
        var bytes = 0L
    }

    private fun validate(nowMillis: Long, input: List<PendingTransfer>, budget: BatchBudget) {
        require(nowMillis >= 0)
        require(budget.maxItems > 0 && budget.maxPayloadBytes > 0 && budget.maxBatches >= 0)
        require(input.size <= 256) { "At most 256 transfers per opportunity" }
        require(input.map { it.id }.toSet().size == input.size) { "Duplicate transfer ID" }
        require(input.all {
            it.id.isNotBlank() && it.partition.accountId.isNotBlank() &&
                it.partition.authorityGeneration >= 0 && it.partition.endpoint.isNotBlank() &&
                it.notBeforeMillis >= 0 && it.latestStartMillis >= it.notBeforeMillis && it.payloadBytes >= 0
        })
    }

    private fun <T> immutableList(values: List<T>): List<T> =
        Collections.unmodifiableList(values.toList())
}

/** Deliberately wrong: one request per item, with no admission or authority policy. */
object LearnerBatchPlanner : BatchPlanning {
    override fun plan(nowMillis: Long, transfers: List<PendingTransfer>, budget: BatchBudget): OpportunityPlan {
        val batches = transfers.map { TransferBatch(it.partition, listOf(it.id), it.payloadBytes) }
        return OpportunityPlan(batches, emptyMap(), transfers.sumOf { it.payloadBytes })
    }
}
