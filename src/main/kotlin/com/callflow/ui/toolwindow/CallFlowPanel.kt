package com.callflow.ui.toolwindow

import com.callflow.core.analyzer.AnalysisConfig
import com.callflow.core.analyzer.CriticalPathAnalyzer
import com.callflow.core.analyzer.JavaCallAnalyzer
import com.callflow.core.model.CallGraph
import com.callflow.ui.graph.CallGraphPanel
import com.callflow.ui.warnings.WarningListPanel
import com.callflow.ui.tree.CallTreeView
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.psi.PsiMethod
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/**
 * Main panel for the CallFlow tool window.
 * Contains toolbar, tree view, and status bar.
 * Implements Disposable for proper resource cleanup.
 */
class CallFlowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : SimpleToolWindowPanel(true, true), Disposable {

    private val LOG = Logger.getInstance(CallFlowPanel::class.java)

    // UI Components
    private val treeView = CallTreeView(project)
    private val graphPanel = CallGraphPanel()
    private val tabbedPane = JBTabbedPane()
    private val statusLabel = JBLabel("Select a method and click 'Analyze Call Flow'")
    private val depthComboBox = ComboBox(arrayOf(1, 2, 3, 5, 7, 10))
    private val directionComboBox = ComboBox(arrayOf("Both", "Callers Only", "Callees Only"))
    private val hideEntitiesCheckbox = JCheckBox("Hide Entities", false)
    private val hideTestCodeCheckbox = JCheckBox("Hide Test Code", true)  // Default: hide test code
    private val warningPanel = WarningListPanel()
    private var graphSplitter: JBSplitter? = null

    // Toolbar controls
    private val showLegendCheckbox = JCheckBox("Legend", false)
    private val showWarningsCheckbox = JCheckBox("Warnings", true)

    // Badge visibility state
    private val badgeVisibility = mutableMapOf(
        "transactional" to true,
        "async" to true,
        "externalCall" to true,
        "warning" to true,
        "tableScan" to true,
        "cascade" to true,
        "earlyLock" to true,
        "httpMethod" to true
    )

    // State
    private var currentGraph: CallGraph? = null
    private var currentMethod: PsiMethod? = null
    private var exportButton: JButton? = null
    private var zoomInButton: JButton? = null
    private var zoomOutButton: JButton? = null
    private var fitButton: JButton? = null

    init {
        setupUI()
        // Initialize graph panel with default filter settings
        graphPanel.setHideTestCode(hideTestCodeCheckbox.isSelected)
        // Wire warning panel click to graph node highlighting
        warningPanel.onWarningClicked = { nodeId ->
            graphPanel.highlightNode(nodeId)
            // Switch to graph tab
            tabbedPane.selectedIndex = 0
        }
    }

    /**
     * Set the analysis direction programmatically.
     * Used by direction-specific actions.
     */
    fun setDirection(direction: CallGraph.AnalysisDirection) {
        directionComboBox.selectedIndex = when (direction) {
            CallGraph.AnalysisDirection.BIDIRECTIONAL -> 0
            CallGraph.AnalysisDirection.CALLERS_ONLY -> 1
            CallGraph.AnalysisDirection.CALLEES_ONLY -> 2
        }
    }

    private fun setupUI() {
        // Toolbar
        val toolbar = createToolbar()
        setToolbar(toolbar)

        // Tree view tab
        val treeScrollPane = JBScrollPane(treeView).apply {
            border = JBUI.Borders.empty()
        }

        // Graph view tab with zoom controls
        val graphContainer = JPanel(BorderLayout()).apply {
            // Zoom toolbar
            val zoomToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                zoomInButton = JButton(AllIcons.General.ZoomIn).apply {
                    toolTipText = "Zoom In (+)"
                    addActionListener { graphPanel.zoomIn() }
                }
                zoomOutButton = JButton(AllIcons.General.ZoomOut).apply {
                    toolTipText = "Zoom Out (-)"
                    addActionListener { graphPanel.zoomOut() }
                }
                fitButton = JButton("Fit").apply {
                    toolTipText = "Fit to View"
                    addActionListener { graphPanel.fitToView() }
                }
                val resetButton = JButton("100%").apply {
                    toolTipText = "Reset Zoom"
                    addActionListener { graphPanel.resetZoom() }
                }
                add(JBLabel("Zoom:"))
                add(zoomOutButton!!)
                add(zoomInButton!!)
                add(fitButton!!)
                add(resetButton)
            }

            // Graph + Warning panel in a splitter
            graphSplitter = JBSplitter(false, 0.75f).apply {
                firstComponent = graphPanel
                secondComponent = warningPanel
                dividerWidth = 3
            }

            add(zoomToolbar, BorderLayout.NORTH)
            add(graphSplitter!!, BorderLayout.CENTER)
        }

        // Tabbed pane
        tabbedPane.addTab("Graph View", AllIcons.Actions.DiagramDiff, graphContainer, "Visual call flow graph")
        tabbedPane.addTab("Tree View", AllIcons.Toolwindows.ToolWindowHierarchy, treeScrollPane, "Hierarchical tree view")
        setContent(tabbedPane)

        // Status bar
        val statusBar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1, 0, 0, 0)
            add(statusLabel)
        }
        add(statusBar, BorderLayout.SOUTH)
    }

    private fun createToolbar(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            // Direction selector
            add(JBLabel("Direction:"))
            directionComboBox.selectedIndex = 0
            add(directionComboBox)

            // Depth selector
            add(JBLabel("Depth:"))
            depthComboBox.selectedItem = 5
            add(depthComboBox)

            // Hide Entities checkbox
            hideEntitiesCheckbox.apply {
                toolTipText = "Hide Entity classes from the graph"
                addActionListener {
                    graphPanel.setHideEntities(isSelected)
                }
            }
            add(hideEntitiesCheckbox)

            // Hide Test Code checkbox
            hideTestCodeCheckbox.apply {
                toolTipText = "Hide test classes from the graph (classes ending with Test, in test packages, or with @Test annotation)"
                addActionListener {
                    graphPanel.setHideTestCode(isSelected)
                }
            }
            add(hideTestCodeCheckbox)

            // Show Legend checkbox
            showLegendCheckbox.apply {
                toolTipText = "Show/hide legend overlay on the graph"
                addActionListener {
                    graphPanel.setShowLegend(isSelected)
                }
            }
            add(showLegendCheckbox)

            // Show Warnings sidebar checkbox
            showWarningsCheckbox.apply {
                toolTipText = "Show/hide risk warnings sidebar"
                addActionListener {
                    warningPanel.isVisible = isSelected
                    graphSplitter?.let {
                        if (isSelected) {
                            it.proportion = 0.75f
                        } else {
                            it.proportion = 1.0f
                        }
                    }
                }
            }
            add(showWarningsCheckbox)

            // Badge filter dropdown
            val badgeFilterButton = JButton("Badges \u25BE").apply {
                toolTipText = "Filter which badges are shown on nodes"
                addActionListener {
                    val panel = JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        border = JBUI.Borders.empty(6)
                    }

                    val items = listOf(
                        "@Transactional" to "transactional",
                        "@Async" to "async",
                        "External Calls" to "externalCall",
                        "Warnings (FLUSH/EAGER)" to "warning",
                        "Risk: Table Scan" to "tableScan",
                        "Risk: Cascade" to "cascade",
                        "Risk: Early Lock" to "earlyLock",
                        "HTTP Method" to "httpMethod"
                    )

                    for ((label, key) in items) {
                        val cb = JBCheckBox(label, badgeVisibility[key] ?: true)
                        cb.addActionListener {
                            badgeVisibility[key] = cb.isSelected
                            graphPanel.setBadgeVisibility(badgeVisibility.toMap())
                        }
                        panel.add(cb)
                    }

                    JBPopupFactory.getInstance()
                        .createComponentPopupBuilder(panel, null)
                        .setRequestFocus(true)
                        .setFocusable(true)
                        .setResizable(false)
                        .setMovable(false)
                        .createPopup()
                        .showUnderneathOf(this)
                }
            }
            add(badgeFilterButton)

            // Refresh button
            val refreshButton = JButton("Refresh").apply {
                addActionListener { currentMethod?.let { analyzeMethod(it) } }
            }
            add(refreshButton)

            // Export button
            exportButton = JButton("Export").apply {
                addActionListener { showExportDialog() }
                isEnabled = false
            }
            add(exportButton!!)
        }
    }

    private fun createPlaceholderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            add(JBLabel("Graph view will be shown here (Phase 3)", SwingConstants.CENTER), BorderLayout.CENTER)
            border = JBUI.Borders.empty(20)
        }
    }

    /**
     * Public method to trigger analysis from external actions.
     */
    fun analyzeMethod(method: PsiMethod) {
        currentMethod = method
        val depth = depthComboBox.selectedItem as? Int ?: 5
        val directionIndex = directionComboBox.selectedIndex

        val direction = when (directionIndex) {
            0 -> CallGraph.AnalysisDirection.BIDIRECTIONAL
            1 -> CallGraph.AnalysisDirection.CALLERS_ONLY
            2 -> CallGraph.AnalysisDirection.CALLEES_ONLY
            else -> CallGraph.AnalysisDirection.BIDIRECTIONAL
        }

        statusLabel.text = "Analyzing ${method.containingClass?.name}.${method.name}()..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Analyzing Call Flow",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Searching for method references..."

                val analyzer = JavaCallAnalyzer(project, AnalysisConfig(maxDepth = depth))
                val graph = when (direction) {
                    CallGraph.AnalysisDirection.CALLERS_ONLY -> {
                        val root = analyzer.analyzeCallers(method, depth)
                        CallGraph(root, depth, direction, root.totalCallerCount() + 1)
                    }
                    CallGraph.AnalysisDirection.CALLEES_ONLY -> {
                        val root = analyzer.analyzeCallees(method, depth)
                        CallGraph(root, depth, direction, root.totalCalleeCount() + 1)
                    }
                    CallGraph.AnalysisDirection.BIDIRECTIONAL -> {
                        analyzer.analyzeBidirectional(method, depth)
                    }
                }

                // Post-processing: compute risk warnings and critical path (Feature 8)
                val criticalPathAnalyzer = CriticalPathAnalyzer()
                val riskWarnings = criticalPathAnalyzer.computeRiskWarnings(graph.root)
                val criticalPath = criticalPathAnalyzer.findCriticalPath(graph.root)
                val enrichedGraph = graph.copy(
                    riskWarnings = riskWarnings,
                    criticalPath = criticalPath
                )

                ApplicationManager.getApplication().invokeLater {
                    updateUI(enrichedGraph)
                }
            }

            override fun onThrowable(error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Analysis failed: ${error.message}"
                }
            }
        })
    }

    private fun updateUI(graph: CallGraph) {
        currentGraph = graph
        treeView.setRoot(graph.root)
        graphPanel.setRoot(graph.root)
        graphPanel.setCriticalPath(graph.criticalPath)
        warningPanel.setWarnings(graph.riskWarnings)
        exportButton?.isEnabled = true

        val stats = graph.getStatistics()
        val warningCount = graph.riskWarnings.size
        statusLabel.text = buildString {
            append("${graph.root.displayName} | ")
            append("${stats.totalNodes} nodes | ")
            append("Callers: ${stats.callerCount} | ")
            append("Callees: ${stats.calleeCount} | ")
            if (warningCount > 0) append("⚠ $warningCount warnings | ")
            append("${graph.analysisTimeMs}ms")
            if (graph.isTruncated) append(" (truncated)")
        }
    }

    private fun showExportDialog() {
        val graph = currentGraph
        if (graph == null) {
            JOptionPane.showMessageDialog(
                this,
                "No call graph available. Analyze a method first.",
                "Export Error",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val options = arrayOf("Mermaid", "PlantUML", "Cancel")
        val choice = JOptionPane.showOptionDialog(
            this,
            "Select export format:",
            "Export Call Graph",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]
        )

        when (choice) {
            0 -> exportAsMermaid(graph)
            1 -> exportAsPlantUML(graph)
        }
    }

    private fun exportAsMermaid(graph: CallGraph) {
        val exporter = com.callflow.export.MermaidExporter()
        val mermaid = exporter.export(graph)
        copyToClipboardAndNotify(mermaid, "Mermaid")
    }

    private fun exportAsPlantUML(graph: CallGraph) {
        val exporter = com.callflow.export.PlantUMLExporter()
        val plantuml = exporter.export(graph)
        copyToClipboardAndNotify(plantuml, "PlantUML")
    }

    private fun copyToClipboardAndNotify(content: String, format: String) {
        com.intellij.openapi.ide.CopyPasteManager.getInstance()
            .setContents(java.awt.datatransfer.StringSelection(content))

        JOptionPane.showMessageDialog(
            this,
            "$format diagram copied to clipboard!\n\nPreview:\n${content.take(300)}${if (content.length > 300) "..." else ""}",
            "Export Successful",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    /**
     * Get current graph for external access (e.g., export actions).
     */
    fun getCurrentGraph(): CallGraph? = currentGraph

    /**
     * Clear the current analysis and free resources.
     */
    fun clear() {
        currentGraph = null
        currentMethod = null
        treeView.setRoot(com.callflow.core.model.CallNode(
            id = "empty",
            methodName = "No analysis",
            className = "",
            packageName = "",
            type = com.callflow.core.model.NodeType.UNKNOWN
        ))
        exportButton?.isEnabled = false
        statusLabel.text = "Select a method and click 'Analyze Call Flow'"
        LOG.info("CallFlowPanel cleared")
    }

    /**
     * Dispose resources when the panel is destroyed.
     */
    override fun dispose() {
        LOG.info("Disposing CallFlowPanel")
        currentGraph = null
        currentMethod = null
    }
}
