package staff.android.costs.planning

fun main() {
    val accountA = TransferPartition("account-a", 7, "reports")
    val accountB = TransferPartition("account-b", 2, "reports")
    val transfers = listOf(
        PendingTransfer("north", accountA, 0, 120, 6),
        PendingTransfer("valve", accountB, 0, 125, 3),
        PendingTransfer("meter", accountA, 0, 130, 4),
        PendingTransfer("roof", accountA, 0, 140, 5),
        PendingTransfer("gate", accountA, 101, 160, 2),
        PendingTransfer("old", accountA, 0, 99, 1),
    )
    val plan = ReferenceBatchPlanner.plan(100, transfers, BatchBudget(2, 10, 2))
    plan.batches.forEachIndexed { index, batch -> println("batch ${index + 1}: ${batch.transferIds}, ${batch.payloadBytes} bytes") }
    println("unscheduled: ${plan.unscheduled}")
    println("total: ${plan.totalPayloadBytes} payload bytes; not wire bytes or energy")
}
