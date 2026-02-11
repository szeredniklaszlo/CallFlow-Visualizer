package com.callflow.core.analyzer

import com.callflow.core.model.CallNode
import com.callflow.core.model.RiskWarning
import com.callflow.core.model.TransactionPropagation
import com.intellij.openapi.diagnostic.Logger

/**
 * Post-processing analyzer that computes risk warnings and
 * finds the critical (highest-risk) path through a call graph.
 */
class CriticalPathAnalyzer {

    private val LOG = Logger.getInstance(CriticalPathAnalyzer::class.java)

    /**
     * Computes structured risk warnings from all nodes in the graph.
     */
    fun computeRiskWarnings(root: CallNode): List<RiskWarning> {
        val warnings = mutableListOf<RiskWarning>()
        val visited = mutableSetOf<String>()
        collectWarnings(root, warnings, visited)
        return warnings.sortedByDescending { it.severity }
    }

    private fun collectWarnings(node: CallNode, warnings: MutableList<RiskWarning>, visited: MutableSet<String>) {
        if (node.id in visited) return
        visited.add(node.id)

        val meta = node.metadata

        // Transaction-related warnings
        if (meta.isTransactional && meta.transactionPropagation == TransactionPropagation.REQUIRES_NEW) {
            warnings.add(createWarning(node, "REQUIRES_NEW_IN_TX"))
        }

        // External call flags (only dangerous when inside a transaction context)
        for (flag in meta.externalCallFlags) {
            if (meta.isTransactional || isInTransactionalAncestorChain(node)) {
                warnings.add(createWarning(node, flag))
            }
        }

        // Warning flags
        for (flag in meta.warningFlags) {
            warnings.add(createWarning(node, flag))
        }

        // Recurse into callees
        for (callee in node.callees) {
            collectWarnings(callee, warnings, visited)
        }
    }

    /**
     * Checks if any ancestor in the caller chain is transactional.
     */
    private fun isInTransactionalAncestorChain(node: CallNode): Boolean {
        val visited = mutableSetOf<String>()
        fun check(n: CallNode): Boolean {
            if (n.id in visited) return false
            visited.add(n.id)
            if (n.metadata.isTransactional) return true
            return n.callers.any { check(it) }
        }
        return node.callers.any { check(it) }
    }

    private fun createWarning(node: CallNode, flag: String): RiskWarning {
        return RiskWarning(
            nodeId = node.id,
            nodeDisplayName = node.displayName,
            flag = flag,
            severity = RiskWarning.RISK_SCORES[flag] ?: 3,
            title = RiskWarning.FLAG_TITLES[flag] ?: flag,
            description = RiskWarning.FLAG_DESCRIPTIONS[flag] ?: "Unknown risk pattern: $flag"
        )
    }

    /**
     * Finds the highest-risk path through the graph using DFS.
     * Returns a list of node IDs forming the critical path.
     */
    fun findCriticalPath(root: CallNode): List<String> {
        val bestPath = mutableListOf<String>()
        var bestScore = 0

        fun dfs(node: CallNode, currentPath: MutableList<String>, currentScore: Int, visited: MutableSet<String>) {
            if (node.id in visited) return
            visited.add(node.id)
            currentPath.add(node.id)

            val nodeScore = computeNodeRiskScore(node)
            val totalScore = currentScore + nodeScore

            if (node.callees.isEmpty() || node.callees.all { it.id in visited }) {
                // Leaf node or all callees visited — check if this is the best path
                if (totalScore > bestScore) {
                    bestScore = totalScore
                    bestPath.clear()
                    bestPath.addAll(currentPath)
                }
            } else {
                for (callee in node.callees) {
                    dfs(callee, currentPath, totalScore, visited)
                }
            }

            currentPath.removeAt(currentPath.lastIndex)
            visited.remove(node.id)
        }

        dfs(root, mutableListOf(), 0, mutableSetOf())

        LOG.info("Critical path found: ${bestPath.size} nodes, score=$bestScore")
        return bestPath
    }

    /**
     * Computes the risk score for a single node based on its flags.
     */
    private fun computeNodeRiskScore(node: CallNode): Int {
        var score = 0
        val meta = node.metadata

        if (meta.isTransactional && meta.transactionPropagation == TransactionPropagation.REQUIRES_NEW) {
            score += RiskWarning.RISK_SCORES["REQUIRES_NEW_IN_TX"] ?: 0
        }

        for (flag in meta.externalCallFlags) {
            score += RiskWarning.RISK_SCORES[flag] ?: 0
        }

        for (flag in meta.warningFlags) {
            score += RiskWarning.RISK_SCORES[flag] ?: 0
        }

        return score
    }
}
