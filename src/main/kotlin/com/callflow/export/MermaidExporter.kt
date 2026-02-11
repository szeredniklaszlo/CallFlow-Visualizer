package com.callflow.export

import com.callflow.core.model.CallGraph
import com.callflow.core.model.CallNode
import com.callflow.core.model.NodeType

/**
 * Exports CallGraph to Mermaid diagram format.
 * https://mermaid.js.org/syntax/flowchart.html
 */
class MermaidExporter {

    /**
     * Export the call graph as a Mermaid flowchart.
     */
    fun export(graph: CallGraph): String {
        val allNodes = graph.getAllNodes()
        val edges = collectEdges(graph.root, mutableSetOf())
        val nodesByType = allNodes.groupBy { it.type }

        return buildString {
            appendLine("```mermaid")
            appendLine("flowchart TB")
            appendLine()

            // Define all nodes first (without subgraphs for cleaner output)
            appendLine("    %% Node definitions")
            allNodes.forEach { node ->
                val nodeId = sanitizeId(node.id)
                val label = formatNodeLabel(node)
                appendLine("    $nodeId[\"$label\"]")
            }
            appendLine()

            // Style definitions
            appendLine("    %% Style definitions")
            NodeType.entries
                .filter { type -> nodesByType[type]?.isNotEmpty() == true }
                .forEach { type ->
                    appendLine("    classDef ${type.name.lowercase()} fill:${type.colorHex},stroke:#333,color:#000")
                }
            appendLine()

            // Apply styles to nodes
            appendLine("    %% Apply styles")
            nodesByType.forEach { (type, typeNodes) ->
                if (typeNodes.isNotEmpty()) {
                    val ids = typeNodes.joinToString(",") { sanitizeId(it.id) }
                    appendLine("    class $ids ${type.name.lowercase()}")
                }
            }
            appendLine()

            // Edges (call relationships)
            appendLine("    %% Call relationships")
            edges.distinct().forEach { edge ->
                appendLine("    $edge")
            }

            appendLine("```")
        }
    }

    /**
     * Export with subgraphs grouping nodes by layer type.
     * More organized but can be visually complex.
     */
    fun exportWithSubgraphs(graph: CallGraph): String {
        val allNodes = graph.getAllNodes()
        val edges = collectEdges(graph.root, mutableSetOf())
        val nodesByType = allNodes.groupBy { it.type }

        return buildString {
            appendLine("```mermaid")
            appendLine("flowchart TB")
            appendLine()

            // Subgraphs for different layers
            nodesByType.entries
                .filter { it.value.isNotEmpty() }
                .forEach { (type, typeNodes) ->
                    appendLine("    subgraph ${type.displayName}[\"${type.displayName} Layer\"]")
                    typeNodes.forEach { node ->
                        appendLine("        ${sanitizeId(node.id)}[\"${formatNodeLabel(node)}\"]")
                    }
                    appendLine("    end")
                    appendLine()
                }

            // Style definitions
            appendLine("    %% Styles")
            NodeType.entries
                .filter { type -> nodesByType[type]?.isNotEmpty() == true }
                .forEach { type ->
                    appendLine("    classDef ${type.name.lowercase()} fill:${type.colorHex},stroke:#333")
                }
            appendLine()

            // Apply styles
            nodesByType.forEach { (type, typeNodes) ->
                if (typeNodes.isNotEmpty()) {
                    val ids = typeNodes.joinToString(",") { sanitizeId(it.id) }
                    appendLine("    class $ids ${type.name.lowercase()}")
                }
            }
            appendLine()

            // Edges
            appendLine("    %% Call relationships")
            edges.distinct().forEach { edge ->
                appendLine("    $edge")
            }

            appendLine("```")
        }
    }

    /**
     * Export as a simpler sequence diagram (alternative view).
     */
    fun exportAsSequence(graph: CallGraph): String {
        return buildString {
            appendLine("```mermaid")
            appendLine("sequenceDiagram")

            val allNodes = graph.getAllNodes()
            val participants = allNodes.map { it.className }.distinct()

            // Declare participants
            participants.forEach { className ->
                appendLine("    participant ${sanitizeParticipant(className)}")
            }
            appendLine()

            // Generate sequence from root callees
            generateSequence(graph.root, this, mutableSetOf())

            appendLine("```")
        }
    }

    /**
     * Collect all edges from the graph.
     */
    private fun collectEdges(node: CallNode, visited: MutableSet<String>): List<String> {
        if (node.id in visited) return emptyList()
        visited.add(node.id)

        val edges = mutableListOf<String>()
        val nodeId = sanitizeId(node.id)

        // Add edges to callees
        node.callees.forEach { callee ->
            val calleeId = sanitizeId(callee.id)
            val edgeProps = node.calleeEdgeProperties[callee.id]
            val edgeStyle = when {
                callee.metadata.isAsync -> "-.->|async|"
                callee.isCyclicRef -> "-.->|cycle|"
                edgeProps?.isInsideLoop == true -> "==>|loop|"
                else -> "-->"
            }
            edges.add("$nodeId $edgeStyle $calleeId")
            edges.addAll(collectEdges(callee, visited))
        }

        // Add edges from callers
        node.callers.forEach { caller ->
            val callerId = sanitizeId(caller.id)
            val edgeStyle = when {
                node.metadata.isAsync -> "-.->|async|"
                caller.isCyclicRef -> "-.->|cycle|"
                else -> "-->"
            }
            edges.add("$callerId $edgeStyle $nodeId")
            edges.addAll(collectEdges(caller, visited))
        }

        return edges
    }

    private fun generateSequence(
        node: CallNode,
        builder: StringBuilder,
        visited: MutableSet<String>,
        depth: Int = 0
    ) {
        if (depth > 5 || node.id in visited) return
        visited.add(node.id)

        node.callees.forEach { callee ->
            val fromClass = sanitizeParticipant(node.className)
            val toClass = sanitizeParticipant(callee.className)
            val method = callee.methodName

            val badges = callee.metadata.getBadges()
            val badgeText = if (badges.isNotEmpty()) " ${badges.joinToString(" ") { "[$it]" }}" else ""
            val edgeProps = node.calleeEdgeProperties[callee.id]
            val loopNote = if (edgeProps?.isInsideLoop == true) " [LOOP]" else ""

            builder.appendLine("    $fromClass->>+$toClass: $method()$badgeText$loopNote")

            generateSequence(callee, builder, visited, depth + 1)

            builder.appendLine("    $toClass-->>-$fromClass: return")
        }
    }

    private fun sanitizeId(id: String): String {
        return id.replace(Regex("[^a-zA-Z0-9]"), "_")
            .take(50)
    }

    private fun sanitizeParticipant(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9]"), "_")
    }

    private fun formatNodeLabel(node: CallNode): String {
        val badges = node.metadata.getBadges().joinToString(" ") { "[$it]" }
        val label = "${node.className}.${node.methodName}()"
        val fullLabel = if (badges.isNotEmpty()) "$label $badges" else label
        // Escape quotes for Mermaid
        return fullLabel.replace("\"", "'")
    }
}

/**
 * Exports CallGraph to PlantUML format.
 * http://plantuml.com/
 */
class PlantUMLExporter {

    fun export(graph: CallGraph): String {
        val visited = mutableSetOf<String>()
        val allNodes = graph.getAllNodes()

        return buildString {
            appendLine("@startuml")
            appendLine("!theme plain")
            appendLine("skinparam packageStyle rectangle")
            appendLine("skinparam classAttributeIconSize 0")
            appendLine()

            // Group by package
            val nodesByPackage = allNodes.groupBy { it.packageName }

            nodesByPackage.forEach { (pkg, nodes) ->
                val packageName = pkg.ifEmpty { "default" }
                appendLine("package \"$packageName\" {")
                nodes.distinctBy { it.className }.forEach { node ->
                    val stereotype = "<<${node.type.displayName}>>"
                    appendLine("    class ${sanitizeClassName(node.className)} $stereotype")
                }
                appendLine("}")
                appendLine()
            }

            // Relationships
            generateRelationships(graph.root, visited, this)

            appendLine("@enduml")
        }
    }

    private fun generateRelationships(
        node: CallNode,
        visited: MutableSet<String>,
        builder: StringBuilder
    ) {
        if (node.id in visited) return
        visited.add(node.id)

        node.callees.forEach { callee ->
            val arrow = if (callee.metadata.isAsync) "..>" else "-->"
            val fromClass = sanitizeClassName(node.className)
            val toClass = sanitizeClassName(callee.className)
            builder.appendLine("$fromClass $arrow $toClass : ${callee.methodName}()")
            generateRelationships(callee, visited, builder)
        }

        node.callers.forEach { caller ->
            if (caller.id !in visited) {
                val arrow = if (node.metadata.isAsync) "..>" else "-->"
                val fromClass = sanitizeClassName(caller.className)
                val toClass = sanitizeClassName(node.className)
                builder.appendLine("$fromClass $arrow $toClass : ${node.methodName}()")
                generateRelationships(caller, visited, builder)
            }
        }
    }

    private fun sanitizeClassName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9]"), "_")
    }
}
