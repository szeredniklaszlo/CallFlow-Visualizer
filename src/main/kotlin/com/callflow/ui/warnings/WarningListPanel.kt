package com.callflow.ui.warnings

import com.callflow.core.model.RiskWarning
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import javax.swing.*
import javax.swing.border.EmptyBorder
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * Collapsible side panel that displays risk warnings.
 * Each warning is clickable to highlight the corresponding node in the graph.
 */
class WarningListPanel : JPanel(BorderLayout()) {

    private val warningsList = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val scrollPane = JScrollPane(warningsList).apply {
        border = JBUI.Borders.empty()
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    /** Callback when a warning is clicked — passes the node ID */
    var onWarningClicked: ((String) -> Unit)? = null

    private val headerLabel = JLabel("⚠ Risk Warnings (0)").apply {
        font = Font("SansSerif", Font.BOLD, 12)
        border = EmptyBorder(6, 8, 6, 8)
    }

    init {
        background = JBColor.background()
        border = JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 0, 1, 0, 0)

        add(headerLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        minimumSize = Dimension(220, 100)
        preferredSize = Dimension(260, 400)
    }

    /**
     * Update the panel with a new list of risk warnings.
     */
    fun setWarnings(warnings: List<RiskWarning>) {
        warningsList.removeAll()
        headerLabel.text = "⚠ Risk Warnings (${warnings.size})"

        if (warnings.isEmpty()) {
            val emptyLabel = JLabel("✓ No risks detected").apply {
                font = Font("SansSerif", Font.ITALIC, 11)
                foreground = Color(0x4CAF50)
                border = EmptyBorder(12, 12, 12, 12)
            }
            warningsList.add(emptyLabel)
        } else {
            for (warning in warnings) {
                warningsList.add(createWarningItem(warning))
                warningsList.add(Box.createRigidArea(Dimension(0, 2)))
            }
        }

        warningsList.revalidate()
        warningsList.repaint()
    }

    private fun createWarningItem(warning: RiskWarning): JPanel {
        val item = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(getSeverityColor(warning.severity).brighter(), 0, 3, 0, 0),
                EmptyBorder(5, 8, 5, 6)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 60)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "<html><b>${warning.title}</b><br>${warning.description}</html>"
        }

        // Severity indicator + title
        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            background = JBColor.background()
        }
        val severityBadge = JLabel(getSeverityIcon(warning.severity)).apply {
            font = Font("SansSerif", Font.BOLD, 10)
            foreground = Color.WHITE
            isOpaque = true
            background = getSeverityColor(warning.severity)
            border = EmptyBorder(1, 4, 1, 4)
        }
        titlePanel.add(severityBadge)

        val titleLabel = JLabel(warning.title).apply {
            font = Font("SansSerif", Font.BOLD, 11)
            foreground = JBColor.foreground()
        }
        titlePanel.add(titleLabel)

        // Node name
        val nodeLabel = JLabel(warning.nodeDisplayName).apply {
            font = Font("SansSerif", Font.PLAIN, 10)
            foreground = JBColor.GRAY
            border = EmptyBorder(0, 8, 0, 0)
        }

        item.add(titlePanel, BorderLayout.NORTH)
        item.add(nodeLabel, BorderLayout.CENTER)

        // Click handler
        item.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onWarningClicked?.invoke(warning.nodeId)
            }

            override fun mouseEntered(e: MouseEvent) {
                item.background = JBColor.namedColor("EditorPane.selectionBackground", Color(0xE3F2FD))
                titlePanel.background = item.background
            }

            override fun mouseExited(e: MouseEvent) {
                item.background = JBColor.background()
                titlePanel.background = item.background
            }
        })

        return item
    }

    private fun getSeverityColor(severity: Int): Color {
        return when {
            severity >= 9 -> Color(0xB71C1C)   // Dark red
            severity >= 7 -> Color(0xD32F2F)   // Red
            severity >= 5 -> Color(0xE65100)   // Deep orange
            else -> Color(0xF9A825)             // Amber
        }
    }

    private fun getSeverityIcon(severity: Int): String {
        return when {
            severity >= 9 -> "☠ $severity"
            severity >= 7 -> "⚠ $severity"
            severity >= 5 -> "⚡ $severity"
            else -> "● $severity"
        }
    }
}
