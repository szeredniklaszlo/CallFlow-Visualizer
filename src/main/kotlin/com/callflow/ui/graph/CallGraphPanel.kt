package com.callflow.ui.graph

import com.callflow.core.model.CallNode
import com.callflow.core.model.NodeType
import com.callflow.core.model.TransactionPropagation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import javax.swing.ToolTipManager
import java.awt.*
import java.awt.event.*
import java.awt.geom.*
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.min

/**
 * Visual graph panel that renders call flow as an interactive diagram.
 * Supports zoom, pan, node selection, and Entity hiding.
 */
class CallGraphPanel : JPanel() {

    private val LOG = Logger.getInstance(CallGraphPanel::class.java)

    private var rootNode: CallNode? = null
    private var graphNodes: MutableList<GraphNode> = mutableListOf()
    private var graphEdges: MutableList<GraphEdge> = mutableListOf()

    // View transformation
    private var scale: Double = 1.0
    private var offsetX: Double = 50.0
    private var offsetY: Double = 50.0

    // Interaction state
    private var dragStartPoint: Point? = null
    private var lastDragPoint: Point? = null
    private var selectedNode: GraphNode? = null
    private var hoveredNode: GraphNode? = null

    // Display options
    private var hideEntities: Boolean = false
    private var hideTestCode: Boolean = true  // Default: hide test code

    // Layout constants - Horizontal layout (left to right)
    private val nodeWidth = 200
    private val nodeHeight = 80
    private val horizontalGap = 220  // Horizontal spacing between depth levels (increased)
    private val verticalGap = 100    // Vertical spacing between nodes at same depth (increased)
    private val minVerticalGap = 40  // Minimum absolute gap between nodes to prevent overlap

    // Zoom limits
    private val minScale = 0.2
    private val maxScale = 3.0

    init {
        background = JBColor.background()
        isDoubleBuffered = true

        // Enable tooltips
        ToolTipManager.sharedInstance().registerComponent(this)
        ToolTipManager.sharedInstance().initialDelay = 300

        // Mouse wheel for zoom
        addMouseWheelListener { e ->
            val oldScale = scale
            val zoomFactor = if (e.wheelRotation < 0) 1.1 else 0.9
            scale = (scale * zoomFactor).coerceIn(minScale, maxScale)

            // Zoom towards mouse position
            val mouseX = e.x
            val mouseY = e.y
            offsetX = mouseX - (mouseX - offsetX) * (scale / oldScale)
            offsetY = mouseY - (mouseY - offsetY) * (scale / oldScale)

            repaint()
        }

        // Mouse drag for panning
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    val clickedNode = findNodeAt(e.point)
                    if (clickedNode != null) {
                        selectedNode = clickedNode
                        // Navigate to source on single click
                        clickedNode.callNode.navigate(true)
                    } else {
                        dragStartPoint = e.point
                        lastDragPoint = e.point
                    }
                    repaint()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                dragStartPoint = null
                lastDragPoint = null
                cursor = Cursor.getDefaultCursor()
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (dragStartPoint != null && lastDragPoint != null) {
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    val dx = e.x - lastDragPoint!!.x
                    val dy = e.y - lastDragPoint!!.y
                    offsetX += dx
                    offsetY += dy
                    lastDragPoint = e.point
                    repaint()
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                val node = findNodeAt(e.point)
                if (node != hoveredNode) {
                    hoveredNode = node
                    cursor = if (node != null) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                    repaint()
                }
            }
        })
    }

    /**
     * Set the root call node and rebuild the graph layout.
     */
    fun setRoot(callNode: CallNode) {
        this.rootNode = callNode
        buildGraphLayout()

        // Fit the entire graph in view on initial load
        SwingUtilities.invokeLater {
            fitToView()
            repaint()
        }
    }

    /**
     * Reset zoom to 100%.
     */
    fun resetZoom() {
        scale = 1.0
        centerOnRoot()
        repaint()
    }

    /**
     * Zoom in by 20%.
     */
    fun zoomIn() {
        val centerX = width / 2.0
        val centerY = height / 2.0
        val oldScale = scale
        scale = (scale * 1.2).coerceAtMost(maxScale)
        offsetX = centerX - (centerX - offsetX) * (scale / oldScale)
        offsetY = centerY - (centerY - offsetY) * (scale / oldScale)
        repaint()
    }

    /**
     * Zoom out by 20%.
     */
    fun zoomOut() {
        val centerX = width / 2.0
        val centerY = height / 2.0
        val oldScale = scale
        scale = (scale / 1.2).coerceAtLeast(minScale)
        offsetX = centerX - (centerX - offsetX) * (scale / oldScale)
        offsetY = centerY - (centerY - offsetY) * (scale / oldScale)
        repaint()
    }

    /**
     * Fit the entire graph in view.
     */
    fun fitToView() {
        if (graphNodes.isEmpty()) return

        val minX = graphNodes.minOf { it.x }
        val maxX = graphNodes.maxOf { it.x + nodeWidth }
        val minY = graphNodes.minOf { it.y }
        val maxY = graphNodes.maxOf { it.y + nodeHeight }

        val graphWidth = maxX - minX + 100
        val graphHeight = maxY - minY + 100

        val scaleX = width.toDouble() / graphWidth
        val scaleY = height.toDouble() / graphHeight
        scale = min(scaleX, scaleY).coerceIn(minScale, maxScale)

        offsetX = (width - graphWidth * scale) / 2 - minX * scale + 50
        offsetY = (height - graphHeight * scale) / 2 - minY * scale + 50

        repaint()
    }

    private fun centerOnRoot() {
        val rootGraphNode = graphNodes.firstOrNull() ?: return
        offsetX = width / 2.0 - (rootGraphNode.x + nodeWidth / 2) * scale
        offsetY = 80.0
    }

    private fun buildGraphLayout() {
        graphNodes.clear()
        graphEdges.clear()

        val root = rootNode ?: return

        // Map to track unique nodes by their ID (prevents duplicates)
        val nodeMap = mutableMapOf<String, GraphNode>()
        val edgeSet = mutableSetOf<String>()

        // Collect unique nodes by depth level
        val callersByDepth = mutableMapOf<Int, MutableSet<String>>()
        val calleesByDepth = mutableMapOf<Int, MutableSet<String>>()
        val allNodes = mutableMapOf<String, CallNode>()

        // First pass: collect all unique nodes
        collectUniqueNodes(root.callers, callersByDepth, allNodes, 1, isCallers = true)
        collectUniqueNodes(root.callees, calleesByDepth, allNodes, 1, isCallers = false)

        // Calculate max nodes per level
        val maxCallerLevel = callersByDepth.values.maxOfOrNull { it.size } ?: 0
        val maxCalleeLevel = calleesByDepth.values.maxOfOrNull { it.size } ?: 0
        val maxLevel = max(maxCallerLevel, maxCalleeLevel)
        val totalHeight = max(1, maxLevel) * (nodeHeight + verticalGap)

        // Create root node
        val rootY = totalHeight / 2 - nodeHeight / 2
        val rootGraphNode = GraphNode(root, 0, rootY)
        graphNodes.add(rootGraphNode)
        nodeMap[root.id] = rootGraphNode

        // Layout caller nodes (LEFT)
        callersByDepth.keys.sorted().forEach { depth ->
            val nodeIds = callersByDepth[depth]!!.toList()
            layoutNodesAtDepth(nodeIds, allNodes, nodeMap, depth, isCallers = true, totalHeight)
        }

        // Layout callee nodes (RIGHT)
        calleesByDepth.keys.sorted().forEach { depth ->
            val nodeIds = calleesByDepth[depth]!!.toList()
            layoutNodesAtDepth(nodeIds, allNodes, nodeMap, depth, isCallers = false, totalHeight)
        }

        // Build edges
        buildAllEdges(root, nodeMap, edgeSet, isCallers = true)
        buildAllEdges(root, nodeMap, edgeSet, isCallers = false)
    }

    /**
     * Collect unique nodes recursively.
     * Filters out Entity nodes when hideEntities is true.
     * Filters out test code when hideTestCode is true.
     */
    private fun collectUniqueNodes(
        nodes: List<CallNode>,
        byDepth: MutableMap<Int, MutableSet<String>>,
        allNodes: MutableMap<String, CallNode>,
        depth: Int,
        isCallers: Boolean
    ) {
        if (depth > 5) return

        nodes.forEach { node ->
            // Skip Entity nodes if hideEntities is enabled
            if (hideEntities && node.type == NodeType.ENTITY) {
                return@forEach
            }

            // Skip test code if hideTestCode is enabled
            if (hideTestCode && isTestCode(node)) {
                return@forEach
            }

            val nodeId = node.id
            if (nodeId !in allNodes) {
                allNodes[nodeId] = node
                byDepth.getOrPut(depth) { mutableSetOf() }.add(nodeId)
            }

            if (!node.isCyclicRef) {
                val children = if (isCallers) node.callers else node.callees
                collectUniqueNodes(children, byDepth, allNodes, depth + 1, isCallers)
            }
        }
    }

    /**
     * Layout nodes at a specific depth level with dynamic spacing.
     * Ensures minimum gap between nodes to prevent overlap.
     */
    private fun layoutNodesAtDepth(
        nodeIds: List<String>,
        allNodes: Map<String, CallNode>,
        nodeMap: MutableMap<String, GraphNode>,
        depth: Int,
        isCallers: Boolean,
        totalHeight: Int
    ) {
        val count = nodeIds.size
        if (count == 0) return

        // Calculate actual gap: use larger of verticalGap or minVerticalGap based on node count
        val actualGap = if (count > 5) {
            max(verticalGap, minVerticalGap + (count - 5) * 10)  // Increase gap for many nodes
        } else {
            verticalGap
        }

        val levelHeight = count * (nodeHeight + actualGap) - actualGap
        var y = totalHeight / 2 - levelHeight / 2

        val x = if (isCallers) {
            -(nodeWidth + horizontalGap) * depth
        } else {
            (nodeWidth + horizontalGap) * depth
        }

        nodeIds.forEach { nodeId ->
            if (nodeId !in nodeMap) {
                val callNode = allNodes[nodeId]!!
                val graphNode = GraphNode(callNode, x, y)
                graphNodes.add(graphNode)
                nodeMap[nodeId] = graphNode
            }
            y += nodeHeight + actualGap
        }
    }

    /**
     * Build edges recursively.
     */
    private fun buildAllEdges(
        node: CallNode,
        nodeMap: Map<String, GraphNode>,
        edgeSet: MutableSet<String>,
        isCallers: Boolean
    ) {
        val graphNode = nodeMap[node.id] ?: return
        val children = if (isCallers) node.callers else node.callees

        children.forEach { child ->
            val childGraphNode = nodeMap[child.id] ?: return@forEach

            val edgeKey = if (isCallers) {
                "${child.id}->${node.id}"
            } else {
                "${node.id}->${child.id}"
            }

            if (edgeKey !in edgeSet) {
                edgeSet.add(edgeKey)

                val edge = if (isCallers) {
                    GraphEdge(childGraphNode, graphNode)
                } else {
                    GraphEdge(graphNode, childGraphNode)
                }
                graphEdges.add(edge)
            }

            if (!child.isCyclicRef) {
                buildAllEdges(child, nodeMap, edgeSet, isCallers)
            }
        }
    }

    private fun findNodeAt(point: Point): GraphNode? {
        val worldX = (point.x - offsetX) / scale
        val worldY = (point.y - offsetY) / scale

        return graphNodes.find { node ->
            worldX >= node.x && worldX <= node.x + nodeWidth &&
            worldY >= node.y && worldY <= node.y + nodeHeight
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D

        // Enable anti-aliasing
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Apply transformation
        val originalTransform = g2.transform
        g2.translate(offsetX, offsetY)
        g2.scale(scale, scale)

        // Draw edges (behind nodes)
        graphEdges.forEach { edge ->
            drawEdge(g2, edge)
        }

        // Draw nodes
        graphNodes.forEach { node ->
            drawNode(g2, node)
        }

        // Restore transform
        g2.transform = originalTransform

        // Draw zoom indicator
        drawZoomIndicator(g2)

        // Draw instructions if no graph
        if (graphNodes.isEmpty()) {
            drawPlaceholder(g2)
        }
    }

    private fun drawEdge(g2: Graphics2D, edge: GraphEdge) {
        // Horizontal layout: edges go from right side of 'from' node to left side of 'to' node
        val fromX = edge.from.x + nodeWidth
        val fromY = edge.from.y + nodeHeight / 2
        val toX = edge.to.x
        val toY = edge.to.y + nodeHeight / 2

        // Check if this edge represents a call inside a loop
        val isLoopEdge = edge.from.callNode.calleeEdgeProperties[edge.to.callNode.id]?.isInsideLoop == true

        // Draw curved line — thicker and darker for loop edges
        g2.color = if (isLoopEdge) Color(0xE65100) else JBColor.GRAY  // Dark orange vs gray
        g2.stroke = if (isLoopEdge) {
            BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        } else {
            BasicStroke(2f)
        }

        val path = Path2D.Double()
        path.moveTo(fromX.toDouble(), fromY.toDouble())

        val midX = (fromX + toX) / 2.0
        path.curveTo(
            midX, fromY.toDouble(),
            midX, toY.toDouble(),
            toX.toDouble(), toY.toDouble()
        )
        g2.draw(path)

        // Draw loop indicator label
        if (isLoopEdge) {
            val labelX = midX.toInt() - 15
            val labelY = ((fromY + toY) / 2) - 8
            g2.color = Color(0xE65100)
            g2.font = Font("SansSerif", Font.BOLD, 10)
            g2.drawString("⟳ loop", labelX, labelY)
        }

        // Draw arrow head (pointing right)
        g2.color = if (isLoopEdge) Color(0xE65100) else JBColor.GRAY
        drawArrowHead(g2, toX, toY)
    }

    private fun drawArrowHead(g2: Graphics2D, x: Int, y: Int) {
        val arrowSize = 10
        val path = Path2D.Double()
        // Arrow pointing right
        path.moveTo(x.toDouble(), y.toDouble())
        path.lineTo((x - arrowSize * 1.5).toDouble(), (y - arrowSize).toDouble())
        path.lineTo((x - arrowSize * 1.5).toDouble(), (y + arrowSize).toDouble())
        path.closePath()
        g2.fill(path)
    }

    private fun drawNode(g2: Graphics2D, node: GraphNode) {
        val callNode = node.callNode
        val x = node.x
        val y = node.y
        val metadata = callNode.metadata

        // Check if this is the root/starting node
        val isRootNode = callNode == rootNode

        // Determine if this node has a dangerous TX + external call combo
        val hasDangerCombo = metadata.isTransactional && metadata.externalCallFlags.isNotEmpty()

        // Node background color based on type
        val bgColor = if (isRootNode) {
            Color(0xFFEBEE)
        } else {
            getBackgroundColor(callNode.type)
        }
        val isSelected = node == selectedNode
        val isHovered = node == hoveredNode

        // Draw shadow
        g2.color = if (isRootNode) Color(200, 0, 0, 40) else Color(0, 0, 0, 30)
        g2.fillRoundRect(x + 3, y + 3, nodeWidth, nodeHeight, 10, 10)

        // Draw node background
        g2.color = if (isSelected) bgColor.brighter() else bgColor
        g2.fillRoundRect(x, y, nodeWidth, nodeHeight, 10, 10)

        // Draw border — red dashed for danger combo, red solid for root, default otherwise
        when {
            hasDangerCombo -> {
                g2.color = Color(0xD32F2F) // Red
                g2.stroke = BasicStroke(
                    3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    10f, floatArrayOf(8f, 6f), 0f
                )
            }
            isRootNode -> {
                g2.color = Color(0xE53935)
                g2.stroke = BasicStroke(4f)
            }
            isSelected || isHovered -> {
                g2.color = JBColor.namedColor("Focus.borderColor", JBColor.BLUE)
                g2.stroke = BasicStroke(3f)
            }
            else -> {
                g2.color = bgColor.darker()
                g2.stroke = BasicStroke(1.5f)
            }
        }
        g2.drawRoundRect(x, y, nodeWidth, nodeHeight, 10, 10)

        // Draw type badge (top-left)
        val badgeColor = getBadgeColor(callNode.type)
        g2.color = badgeColor
        g2.fillRoundRect(x + 5, y + 5, 80, 18, 6, 6)
        g2.color = Color.WHITE
        g2.font = Font("SansSerif", Font.BOLD, 10)
        g2.drawString(callNode.type.displayName, x + 10, y + 17)

        // Draw class name
        g2.color = JBColor.foreground()
        g2.font = Font("SansSerif", Font.BOLD, 13)
        val className = truncateText(g2, callNode.className, nodeWidth - 15)
        g2.drawString(className, x + 8, y + 37)

        // Draw method name
        g2.color = JBColor.GRAY
        g2.font = Font("SansSerif", Font.PLAIN, 11)
        val methodName = truncateText(g2, ".${callNode.methodName}()", nodeWidth - 15)
        g2.drawString(methodName, x + 8, y + 50)

        // Draw annotation/risk badges row (bottom area)
        drawAnnotationBadges(g2, x, y, metadata)

        // Draw cycle indicator
        if (callNode.isCyclicRef) {
            g2.color = JBColor.ORANGE
            g2.font = Font("SansSerif", Font.BOLD, 12)
            g2.drawString("\u21BB", x + nodeWidth - 20, y + 17)
        }

        // Draw START indicator for root node
        if (isRootNode) {
            g2.color = Color(0xE53935)
            g2.font = Font("SansSerif", Font.BOLD, 10)
            g2.drawString("★ START", x + nodeWidth - 65, y + 17)
        }
    }

    /**
     * Draw colored annotation/risk badges in a row at the bottom of a node.
     */
    private fun drawAnnotationBadges(g2: Graphics2D, x: Int, y: Int, metadata: com.callflow.core.model.NodeMetadata) {
        val badges = mutableListOf<Pair<String, Color>>()

        // Transaction badges
        if (metadata.isTransactional) {
            when {
                metadata.isReadOnlyTx -> badges.add("@Tx(RO)" to Color(0x4CAF50))  // Green
                metadata.transactionPropagation == TransactionPropagation.REQUIRES_NEW ->
                    badges.add("!TX(NEW)!" to Color(0xD32F2F))  // Bright red
                else -> badges.add("@Tx" to Color(0x1976D2))  // Blue
            }
        }

        // Async badge
        if (metadata.isAsync) badges.add("@Async" to Color(0x7B1FA2))  // Purple

        // External call badges
        if ("MQ_SEND" in metadata.externalCallFlags) badges.add("MQ Send" to Color(0xE65100))  // Deep orange
        if ("HTTP_CALL" in metadata.externalCallFlags) badges.add("HTTP Call" to Color(0x6A1B9A))  // Deep purple

        // Warning badges
        if ("FLUSH" in metadata.warningFlags) badges.add("FLUSH!" to Color(0xF9A825))  // Amber
        if ("EAGER_FETCH" in metadata.warningFlags) badges.add("EAGER!" to Color(0xF9A825))  // Amber

        // HTTP method badge
        metadata.httpMethod?.let { badges.add(it to Color(0x00838F))  }

        if (badges.isEmpty()) return

        // Draw badges in a row at y + 56
        var badgeX = x + 5
        val badgeY = y + 58
        g2.font = Font("SansSerif", Font.BOLD, 9)
        val fm = g2.fontMetrics

        for ((text, color) in badges) {
            val textWidth = fm.stringWidth(text)
            val badgeWidth = textWidth + 8

            // Don't overflow the node
            if (badgeX + badgeWidth > x + nodeWidth - 5) break

            g2.color = color
            g2.fillRoundRect(badgeX, badgeY, badgeWidth, 16, 4, 4)
            g2.color = Color.WHITE
            g2.drawString(text, badgeX + 4, badgeY + 12)

            badgeX += badgeWidth + 3
        }
    }

    /**
     * Returns context-sensitive tooltip text based on hovered node.
     */
    override fun getToolTipText(event: MouseEvent): String? {
        val node = findNodeAt(event.point) ?: return null
        val metadata = node.callNode.metadata
        val tips = mutableListOf<String>()

        if (metadata.isTransactional) {
            when {
                metadata.isReadOnlyTx ->
                    tips.add("Read-only transaction: no write locks acquired. Safe for reads.")
                metadata.transactionPropagation == TransactionPropagation.REQUIRES_NEW ->
                    tips.add("⚠️ REQUIRES_NEW: Opens a new transaction. Can cause deadlocks if the calling transaction holds locks on the same data.")
                else ->
                    tips.add("Standard transaction (REQUIRED propagation).")
            }
        }

        if (metadata.isTransactional && metadata.externalCallFlags.isNotEmpty()) {
            tips.add("🔴 DANGER: Holding DB locks during external I/O. This blocks other transactions and risks distributed deadlocks.")
        }

        if ("MQ_SEND" in metadata.externalCallFlags) {
            tips.add("Sends messages to a message queue (Kafka/RabbitMQ).")
        }
        if ("HTTP_CALL" in metadata.externalCallFlags) {
            tips.add("Makes HTTP calls to external services (REST/Feign).")
        }
        if ("FLUSH" in metadata.warningFlags) {
            tips.add("⚠️ saveAndFlush() acquires locks immediately instead of deferring to TX commit.")
        }
        if ("EAGER_FETCH" in metadata.warningFlags) {
            tips.add("⚠️ EAGER fetch on @...ToMany can cause hidden N+1 queries and acquire more locks than expected.")
        }

        return if (tips.isNotEmpty()) {
            "<html>${tips.joinToString("<br>")}</html>"
        } else {
            "${node.callNode.displayName} (${node.callNode.type.displayName})"
        }
    }

    private fun truncateText(g2: Graphics2D, text: String, maxWidth: Int): String {
        val fm = g2.fontMetrics
        if (fm.stringWidth(text) <= maxWidth) return text

        var truncated = text
        while (truncated.isNotEmpty() && fm.stringWidth("$truncated...") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated..."
    }

    private fun getBackgroundColor(type: NodeType): Color {
        return when (type) {
            NodeType.CONTROLLER -> Color(0xE3F2FD) // Light blue
            NodeType.SERVICE -> Color(0xE8F5E9) // Light green
            NodeType.REPOSITORY -> Color(0xFFF3E0) // Light orange
            NodeType.ENTITY -> Color(0xF3E5F5) // Light purple
            NodeType.INTERFACE -> Color(0xE0F7FA) // Light cyan
            NodeType.IMPLEMENTATION -> Color(0xFCE4EC) // Light pink
            NodeType.EVENT_PUBLISHER -> Color(0xFFFDE7) // Light yellow
            NodeType.EVENT_LISTENER -> Color(0xFFF8E1) // Light amber
            NodeType.COMPONENT -> Color(0xE8EAF6) // Light indigo
            NodeType.CONFIGURATION -> Color(0xEFEBE9) // Light brown
            NodeType.EXTERNAL -> Color(0xECEFF1) // Light blue grey
            NodeType.UNKNOWN -> Color(0xFAFAFA) // Light grey
        }
    }

    private fun getBadgeColor(type: NodeType): Color {
        return try {
            Color.decode(type.colorHex)
        } catch (e: Exception) {
            Color.GRAY
        }
    }

    private fun drawZoomIndicator(g2: Graphics2D) {
        g2.color = JBColor.foreground()
        g2.font = Font("SansSerif", Font.PLAIN, 11)
        val zoomText = "Zoom: ${(scale * 100).toInt()}%"
        g2.drawString(zoomText, 10, height - 10)

        // Draw controls hint
        g2.color = JBColor.GRAY
        g2.font = Font("SansSerif", Font.PLAIN, 10)
        g2.drawString("Scroll: Zoom | Drag: Pan | Click: Navigate", 10, height - 25)
    }

    private fun drawPlaceholder(g2: Graphics2D) {
        g2.color = JBColor.GRAY
        g2.font = Font("SansSerif", Font.PLAIN, 14)
        val message = "No call graph. Right-click a method and select 'Analyze Call Flow'"
        val fm = g2.fontMetrics
        val x = (width - fm.stringWidth(message)) / 2
        val y = height / 2
        g2.drawString(message, x, y)
    }

    /**
     * Internal class representing a visual node in the graph.
     */
    data class GraphNode(
        val callNode: CallNode,
        var x: Int,
        var y: Int
    )

    /**
     * Internal class representing an edge between two nodes.
     */
    data class GraphEdge(
        val from: GraphNode,
        val to: GraphNode
    )

    /**
     * Set whether to hide Entity nodes from the graph.
     */
    fun setHideEntities(hide: Boolean) {
        if (this.hideEntities != hide) {
            this.hideEntities = hide
            buildGraphLayout()
            repaint()
        }
    }

    /**
     * Set whether to hide test code from the graph.
     */
    fun setHideTestCode(hide: Boolean) {
        if (this.hideTestCode != hide) {
            this.hideTestCode = hide
            buildGraphLayout()
            repaint()
        }
    }

    /**
     * Check if a node represents test code.
     * Priority: file path > class name > package name > annotations
     */
    private fun isTestCode(node: CallNode): Boolean {
        // 1. Check file path first (most reliable)
        val filePath = node.metadata.filePath?.lowercase()
        if (filePath != null) {
            // Check for common test directory patterns
            if (filePath.contains("/test/") ||
                filePath.contains("/tests/") ||
                filePath.contains("/src/test/") ||
                filePath.contains("/src/tests/") ||
                filePath.contains("\\test\\") ||
                filePath.contains("\\tests\\")) {
                return true
            }
        }

        // 2. Check class name patterns
        val className = node.className.lowercase()
        if (className.endsWith("test") ||
            className.endsWith("tests") ||
            className.startsWith("test")) {
            return true
        }

        // 3. Check package name patterns
        val packageName = node.packageName.lowercase()
        if (packageName.contains(".test.") ||
            packageName.contains(".tests.") ||
            packageName.endsWith(".test") ||
            packageName.endsWith(".tests")) {
            return true
        }

        // 4. Check annotations (@Test, @TestInstance, etc.)
        val annotations = node.metadata.annotations.map { it.lowercase() }
        return annotations.any { it.contains("test") }
    }
}
