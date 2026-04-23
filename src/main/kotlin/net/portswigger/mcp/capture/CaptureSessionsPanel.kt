package net.portswigger.mcp.capture

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import net.portswigger.mcp.config.Design
import java.awt.*
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class CaptureSessionsPanel(private val captureManager: CaptureManager, private val api: MontoyaApi) : JPanel(BorderLayout()) {

    private val sessionListModel = DefaultListModel<String>()
    private val sessionList = JList(sessionListModel)
    private val requestTableModel = RequestTableModel()
    private val requestTable = JTable(requestTableModel)
    private val statusLabel = JLabel("No session selected")
    private val refreshTimer: javax.swing.Timer

    private val requestEditor = api.userInterface().createHttpRequestEditor()
    private val responseEditor = api.userInterface().createHttpResponseEditor()

    private var suppressRefresh = false
    private var currentSessionId: String? = null
    private var currentSelectedRow: Int = -1

    init {
        buildUi()

        refreshTimer = javax.swing.Timer(2000) {
            if (!suppressRefresh) refreshSessions()
        }
        refreshTimer.start()
        refreshSessions()

        captureManager.addChangeListener { SwingUtilities.invokeLater { refreshSessions() } }
    }

    fun cleanup() {
        refreshTimer.stop()
    }

    private fun buildUi() {
        val topSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        topSplit.dividerLocation = 220
        topSplit.border = null

        topSplit.leftComponent = buildSessionListPanel()

        val rightPanel = JPanel(BorderLayout())
        rightPanel.border = null

        val rightSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        rightSplit.dividerLocation = 300
        rightSplit.border = null

        rightSplit.topComponent = buildRequestTablePanel()
        rightSplit.bottomComponent = buildRequestResponsePanel()

        rightPanel.add(rightSplit, BorderLayout.CENTER)

        topSplit.rightComponent = rightPanel

        add(topSplit, BorderLayout.CENTER)
        add(buildStatusBar(), BorderLayout.SOUTH)
    }

    private fun buildSessionListPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(0, 0, 0, 1)

        val header = JPanel(BorderLayout()).apply {
            border = EmptyBorder(Design.Spacing.SM, Design.Spacing.SM, Design.Spacing.SM, Design.Spacing.SM)
            add(Design.createSectionLabel("Capture Sessions"), BorderLayout.WEST)
        }

        sessionList.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SessionCellRenderer()
            addListSelectionListener {
                if (!it.valueIsAdjusting && !suppressRefresh) {
                    onSessionSelected()
                }
            }
        }

        // Right-click context menu for sessions
        val sessionPopup = JPopupMenu()
        sessionPopup.add(JMenuItem("New Capture Session").apply { addActionListener { newCaptureSession() } })
        sessionPopup.addSeparator()
        sessionPopup.add(JMenuItem("Resume Capture").apply { addActionListener { resumeSelectedSession() } })
        sessionPopup.add(JMenuItem("Stop Capture").apply { addActionListener { stopSelectedSession() } })
        sessionPopup.addSeparator()
        sessionPopup.add(JMenuItem("Delete Session").apply { addActionListener { deleteSelectedSession() } })
        sessionList.componentPopupMenu = sessionPopup

        val scrollPane = JScrollPane(sessionList).apply { border = null }

        // Session action buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        buttonPanel.add(JButton("New").apply {
            toolTipText = "Create new capture session"
            addActionListener { newCaptureSession() }
        })
        buttonPanel.add(JButton("Start").apply {
            toolTipText = "Resume capture on selected session"
            addActionListener { resumeSelectedSession() }
        })
        buttonPanel.add(JButton("Stop").apply {
            toolTipText = "Stop capture on selected session"
            addActionListener { stopSelectedSession() }
        })
        buttonPanel.add(JButton("Delete").apply {
            toolTipText = "Delete selected session"
            addActionListener { deleteSelectedSession() }
        })

        panel.add(header, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        return panel
    }

    // --- Session actions ---

    private fun newCaptureSession() {
        val name = JOptionPane.showInputDialog(this, "Session name:", "New Capture Session", JOptionPane.PLAIN_MESSAGE)
        if (name != null && name.isNotBlank()) {
            captureManager.startCapture(name.trim())
            refreshSessions()
        }
    }

    private fun stopSelectedSession() {
        val sessionId = sessionList.selectedValue ?: return
        val session = captureManager.getCapture(sessionId) ?: return
        if (!session.active) {
            JOptionPane.showMessageDialog(this, "Session '$sessionId' is already stopped.", "Stop", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        captureManager.stopCapture(sessionId)
        refreshSessions()
        onSessionSelected()
    }

    private fun resumeSelectedSession() {
        val sessionId = sessionList.selectedValue ?: return
        val session = captureManager.getCapture(sessionId) ?: return
        if (session.active) {
            JOptionPane.showMessageDialog(this, "Session '$sessionId' is already active.", "Resume", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        captureManager.startCapture(sessionId)
        refreshSessions()
        onSessionSelected()
    }

    private fun deleteSelectedSession() {
        val sessionId = sessionList.selectedValue ?: return
        val confirm = JOptionPane.showConfirmDialog(
            this,
            "Delete session '$sessionId' and all its captured data?",
            "Delete Capture Session",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirm == JOptionPane.YES_OPTION) {
            captureManager.deleteCapture(sessionId)
            currentSessionId = null
            requestTableModel.setEntries(emptyList())
            statusLabel.text = "No session selected"
            refreshSessions()
        }
    }

    // --- Request actions ---

    private fun getSelectedEntry(): CapturedEntry? {
        val row = requestTable.selectedRow
        if (row < 0) return null
        val modelRow = requestTable.convertRowIndexToModel(row)
        return requestTableModel.getEntry(modelRow)
    }

    private fun buildHttpRequest(entry: CapturedEntry): HttpRequest {
        return HttpRequest.httpRequest(
            burp.api.montoya.http.HttpService.httpService(entry.host, entry.port, entry.port == 443),
            entry.request
        )
    }

    private fun sendToRepeater() {
        val entry = getSelectedEntry() ?: run {
            JOptionPane.showMessageDialog(this, "Select a request first.", "Send to Repeater", JOptionPane.WARNING_MESSAGE)
            return
        }
        try {
            val request = buildHttpRequest(entry)
            val tabName = currentSessionId ?: "Capture"
            api.repeater().sendToRepeater(request, tabName)
            api.logging().logToOutput("Sent ${entry.method} ${entry.path} to Repeater")
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Error: ${e.message}", "Send to Repeater", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun sendToIntruder() {
        val entry = getSelectedEntry() ?: run {
            JOptionPane.showMessageDialog(this, "Select a request first.", "Send to Intruder", JOptionPane.WARNING_MESSAGE)
            return
        }
        try {
            val request = buildHttpRequest(entry)
            api.intruder().sendToIntruder(request)
            api.logging().logToOutput("Sent ${entry.method} ${entry.path} to Intruder")
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Error: ${e.message}", "Send to Intruder", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun deleteSelectedRequest() {
        val row = requestTable.selectedRow
        if (row < 0) return
        val modelRow = requestTable.convertRowIndexToModel(row)
        val sessionId = currentSessionId ?: return
        val session = captureManager.getCapture(sessionId) ?: return

        if (modelRow < session.entries.size) {
            session.entries.removeAt(modelRow)
            requestTableModel.setEntries(session.entries)
            val status = if (session.active) "ACTIVE" else "STOPPED"
            statusLabel.text = "Session: $sessionId | Status: $status | Entries: ${session.entries.size}"
            sessionList.repaint()
        }
    }

    // --- Table/panels ---

    private fun buildRequestTablePanel(): JComponent {
        requestTable.apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            rowHeight = 24
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            tableHeader.reorderingAllowed = false
            setDefaultRenderer(Any::class.java, RequestTableCellRenderer())
            autoCreateRowSorter = true

            selectionModel.addListSelectionListener {
                if (!it.valueIsAdjusting && selectedRow >= 0) {
                    onRequestSelected(requestTable.convertRowIndexToModel(selectedRow))
                }
            }
        }

        // Right-click context menu for requests
        val requestPopup = JPopupMenu()
        requestPopup.add(JMenuItem("Send to Repeater").apply { addActionListener { sendToRepeater() } })
        requestPopup.add(JMenuItem("Send to Intruder").apply { addActionListener { sendToIntruder() } })
        requestPopup.addSeparator()
        requestPopup.add(JMenuItem("Delete Request").apply { addActionListener { deleteSelectedRequest() } })
        requestTable.componentPopupMenu = requestPopup

        requestTable.columnModel.apply {
            getColumn(0).preferredWidth = 40   // #
            getColumn(0).cellRenderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.LEFT }
            getColumn(1).preferredWidth = 300  // Host
            getColumn(2).preferredWidth = 60   // Method
            getColumn(3).preferredWidth = 300  // URL
            getColumn(4).preferredWidth = 60   // Status
            getColumn(5).preferredWidth = 140  // Time
        }

        return JScrollPane(requestTable).apply {
            border = null
        }
    }

    private fun buildRequestResponsePanel(): JComponent {
        val tabbedPane = JTabbedPane()

        tabbedPane.addTab("Request", requestEditor.uiComponent())
        tabbedPane.addTab("Response", responseEditor.uiComponent())

        return tabbedPane
    }

    private fun buildStatusBar(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = EmptyBorder(4, Design.Spacing.SM, 4, Design.Spacing.SM)
            add(statusLabel, BorderLayout.WEST)
        }
    }

    // --- Refresh/selection ---

    private fun refreshSessions() {
        val sessions = captureManager.listSessions()
        val selectedValue = sessionList.selectedValue
        val newKeys = sessions.keys.sorted()

        val currentKeys = (0 until sessionListModel.size()).map { sessionListModel.getElementAt(it) }
        if (currentKeys == newKeys) {
            if (currentSessionId != null) {
                val session = captureManager.getCapture(currentSessionId!!)
                if (session != null) {
                    val status = if (session.active) "ACTIVE" else "STOPPED"
                    statusLabel.text = "Session: $currentSessionId | Status: $status | Entries: ${session.entries.size}"
                    // Live update table if session is active and has new entries
                    if (session.active && session.entries.size != requestTableModel.rowCount) {
                        val selectedRow = requestTable.selectedRow
                        requestTableModel.setEntries(session.entries)
                        if (selectedRow in 0 until requestTableModel.rowCount) {
                            requestTable.setRowSelectionInterval(selectedRow, selectedRow)
                        }
                    }
                }
            }
            sessionList.repaint()
            return
        }

        suppressRefresh = true
        sessionListModel.clear()
        newKeys.forEach { sessionListModel.addElement(it) }

        if (selectedValue != null && sessionListModel.contains(selectedValue)) {
            sessionList.setSelectedValue(selectedValue, false)
        }
        suppressRefresh = false
    }

    private fun onSessionSelected() {
        val sessionId = sessionList.selectedValue ?: return
        val session = captureManager.getCapture(sessionId) ?: return

        currentSessionId = sessionId
        currentSelectedRow = -1
        requestTableModel.setEntries(session.entries)

        val status = if (session.active) "ACTIVE" else "STOPPED"
        statusLabel.text = "Session: $sessionId | Status: $status | Entries: ${session.entries.size}"
    }

    private fun onRequestSelected(row: Int) {
        currentSelectedRow = row
        val entry = requestTableModel.getEntry(row) ?: return

        try {
            val httpRequest = HttpRequest.httpRequest(entry.request)
            requestEditor.setRequest(httpRequest)
        } catch (e: Exception) {
            // ignore parse errors
        }

        try {
            if (entry.response != null) {
                val httpResponse = HttpResponse.httpResponse(entry.response)
                responseEditor.setResponse(httpResponse)
            }
        } catch (e: Exception) {
            // ignore parse errors
        }
    }

    // --- Models/renderers ---

    inner class RequestTableModel : AbstractTableModel() {
        private val columns = arrayOf("#", "Host", "Method", "URL", "Status", "Time")
        private var entries = listOf<CapturedEntry>()
        private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

        fun setEntries(newEntries: List<CapturedEntry>) {
            entries = newEntries.toList()
            fireTableDataChanged()
        }

        fun getEntry(row: Int): CapturedEntry? = entries.getOrNull(row)

        override fun getRowCount() = entries.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                0 -> Integer::class.java  // #
                else -> String::class.java
            }
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries[rowIndex]
            return when (columnIndex) {
                0 -> rowIndex + 1
                1 -> entry.host
                2 -> entry.method
                3 -> entry.path
                4 -> entry.statusCode?.toString() ?: "-"
                5 -> dateFormat.format(Date(entry.timestampMs))
                else -> ""
            }
        }
    }

    inner class SessionCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            val sessionId = value as? String ?: return this
            val session = captureManager.getCapture(sessionId)

            if (session != null) {
                val status = if (session.active) "\u25CF" else "\u25CB"
                val count = session.entries.size
                text = "$status $sessionId ($count)"
            }

            border = EmptyBorder(4, 8, 4, 8)
            return this
        }
    }

    inner class RequestTableCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

            if (!isSelected) {
                foreground = table?.foreground
                if (column == 4) {
                    val statusCode = value?.toString()?.toIntOrNull()
                    if (statusCode != null) {
                        foreground = when {
                            statusCode in 200..299 -> Color(0x00C853)
                            statusCode in 300..399 -> Color(0x42A5F5)
                            statusCode in 400..499 -> Color(0xFF6D00)
                            statusCode >= 500 -> Color(0xFF1744)
                            else -> table?.foreground
                        }
                    }
                }
            }

            return this
        }
    }
}
