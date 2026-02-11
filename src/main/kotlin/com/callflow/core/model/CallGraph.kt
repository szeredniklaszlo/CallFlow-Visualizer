package com.callflow.core.model

/**
 * Represents the entire call graph with a root node and analysis metadata.
 */
data class CallGraph(
    /** The root node (the method being analyzed) */
    val root: CallNode,

    /** Maximum depth used for analysis */
    val maxDepth: Int,

    /** Analysis direction */
    val direction: AnalysisDirection,

    /** Total number of unique nodes in the graph */
    val nodeCount: Int,

    /** Analysis duration in milliseconds */
    val analysisTimeMs: Long = 0,

    /** Whether the analysis was truncated due to limits */
    val isTruncated: Boolean = false,

    /** Warning messages from analysis */
    val warnings: List<String> = emptyList(),

    /** Structured risk warnings detected across all nodes */
    val riskWarnings: List<RiskWarning> = emptyList(),

    /** Node IDs forming the highest-risk path through the graph */
    val criticalPath: List<String> = emptyList()
) {
    enum class AnalysisDirection {
        CALLERS_ONLY,    // Only upstream (who calls this)
        CALLEES_ONLY,    // Only downstream (what this calls)
        BIDIRECTIONAL    // Both directions
    }

    /**
     * Get all nodes in the graph as a flat list.
     */
    fun getAllNodes(): List<CallNode> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<CallNode>()

        fun traverse(node: CallNode) {
            if (node.id in visited) return
            visited.add(node.id)
            result.add(node)
            node.callers.forEach { traverse(it) }
            node.callees.forEach { traverse(it) }
        }

        traverse(root)
        return result
    }

    /**
     * Get nodes grouped by type.
     */
    fun getNodesByType(): Map<NodeType, List<CallNode>> {
        return getAllNodes().groupBy { it.type }
    }

    /**
     * Get nodes grouped by package.
     */
    fun getNodesByPackage(): Map<String, List<CallNode>> {
        return getAllNodes().groupBy { it.packageName }
    }

    /**
     * Find a node by its ID.
     */
    fun findNode(id: String): CallNode? {
        return getAllNodes().find { it.id == id }
    }

    /**
     * Get statistics about the graph.
     */
    fun getStatistics(): GraphStatistics {
        val allNodes = getAllNodes()
        return GraphStatistics(
            totalNodes = allNodes.size,
            maxDepthReached = allNodes.maxOfOrNull { it.depth } ?: 0,
            nodesByType = allNodes.groupingBy { it.type }.eachCount(),
            callerCount = root.totalCallerCount(),
            calleeCount = root.totalCalleeCount(),
            hasAsyncCalls = allNodes.any { it.metadata.isAsync },
            hasTransactions = allNodes.any { it.metadata.isTransactional },
            hasEvents = allNodes.any { it.metadata.eventClass != null }
        )
    }

    data class GraphStatistics(
        val totalNodes: Int,
        val maxDepthReached: Int,
        val nodesByType: Map<NodeType, Int>,
        val callerCount: Int,
        val calleeCount: Int,
        val hasAsyncCalls: Boolean,
        val hasTransactions: Boolean,
        val hasEvents: Boolean
    )
}
