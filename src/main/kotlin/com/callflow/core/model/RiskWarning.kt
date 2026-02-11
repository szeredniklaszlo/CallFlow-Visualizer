package com.callflow.core.model

/**
 * Structured warning produced by risk analysis.
 * Used in the Warning List Panel and for critical path computation.
 */
data class RiskWarning(
    /** ID of the node that has this warning */
    val nodeId: String,

    /** Display name of the node for UI */
    val nodeDisplayName: String,

    /** Warning flag key (e.g., "TABLE_SCAN_RISK") */
    val flag: String,

    /** Severity score 1-10 (higher = more dangerous) */
    val severity: Int,

    /** Short title for list display */
    val title: String,

    /** Full explanation for tooltip/detail view */
    val description: String
) {
    companion object {
        /** Risk score mapping for each flag */
        val RISK_SCORES: Map<String, Int> = mapOf(
            "TABLE_SCAN_RISK" to 10,
            "REQUIRES_NEW_IN_TX" to 9,
            "CASCADE_OPERATION" to 8,
            "HTTP_CALL" to 8,
            "MQ_SEND" to 7,
            "EARLY_INSERT_LOCK" to 7,
            "FLUSH" to 6,
            "EAGER_FETCH" to 5
        )

        /** Human-readable titles for each flag */
        val FLAG_TITLES: Map<String, String> = mapOf(
            "TABLE_SCAN_RISK" to "☠ Table Scan Risk",
            "REQUIRES_NEW_IN_TX" to "⚠ REQUIRES_NEW in TX Chain",
            "CASCADE_OPERATION" to "⚠ Cascade Operation",
            "HTTP_CALL" to "⚠ HTTP Call in TX",
            "MQ_SEND" to "⚠ MQ Send in TX",
            "EARLY_INSERT_LOCK" to "⚡ Early INSERT Lock",
            "FLUSH" to "⚠ Immediate Flush",
            "EAGER_FETCH" to "⚠ Eager Fetch"
        )

        /** Detailed descriptions for each flag */
        val FLAG_DESCRIPTIONS: Map<String, String> = mapOf(
            "TABLE_SCAN_RISK" to "This query's WHERE clause likely uses a non-indexed column, which can cause a full table scan and lock the entire table, leading to severe deadlocks.",
            "CASCADE_OPERATION" to "This operation triggers cascade actions on related entities, potentially causing many 'hidden' database operations and locks.",
            "EARLY_INSERT_LOCK" to "This entity uses IDENTITY ID generation. The save() call triggers an immediate INSERT and acquires a DB lock, not at the end of the transaction.",
            "HTTP_CALL" to "HTTP call to external service while holding DB transaction locks. Blocks other transactions and risks distributed deadlocks.",
            "MQ_SEND" to "Message queue send while holding DB transaction locks. If the MQ is slow, the transaction is held open longer.",
            "FLUSH" to "saveAndFlush() acquires locks immediately instead of deferring to TX commit.",
            "EAGER_FETCH" to "EAGER fetch on @...ToMany can cause hidden N+1 queries and acquire more locks than expected.",
            "REQUIRES_NEW_IN_TX" to "REQUIRES_NEW opens a new transaction. Can cause deadlocks if the calling transaction holds locks on the same data."
        )
    }
}
