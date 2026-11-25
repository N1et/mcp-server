package net.portswigger.mcp.capture

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingUtilities

class CaptureSessionsPanelTest {
    @TempDir
    lateinit var captureDirectory: Path

    private var captureManager: CaptureManager? = null

    @AfterEach
    fun closeManager() {
        captureManager?.close()
    }

    @Test
    fun `split editors render and sorted selection loads only the selected payload`() {
        val api = mockk<MontoyaApi>(relaxed = true)
        val persistence = mockk<PersistedObject>(relaxed = true)
        every { persistence.getString(any()) } returns null
        every { api.persistence().extensionData() } returns persistence
        val requestEditor = api.userInterface().createHttpRequestEditor()
        val responseEditor = api.userInterface().createHttpResponseEditor()
        every { api.userInterface().createHttpRequestEditor() } returns requestEditor
        every { api.userInterface().createHttpResponseEditor() } returns responseEditor
        val requestComponent = JPanel()
        val responseComponent = JPanel()
        every { requestEditor.uiComponent() } returns requestComponent
        every { responseEditor.uiComponent() } returns responseComponent

        val manager = CaptureManager(api, captureDirectory).also {
            captureManager = it
            it.register()
        }
        manager.startCapture("ui-session")
        val selectedRequest = seed(manager, "/z-last")
        seed(manager, "/a-first")
        val originalRefs = manager.getEntryRefs("ui-session")
        val trackedManager = spyk(manager)
        val displayedRequest = mockk<HttpRequest>(relaxed = true)
        val displayedResponse = mockk<HttpResponse>(relaxed = true)
        mockkStatic(HttpRequest::class, HttpResponse::class)
        every { HttpRequest.httpRequest(any<String>()) } returns displayedRequest
        every { HttpResponse.httpResponse(any<String>()) } returns displayedResponse
        var panel: CaptureSessionsPanel? = null
        try {
            SwingUtilities.invokeAndWait {
                val capturePanel = CaptureSessionsPanel(trackedManager, api)
                panel = capturePanel
                val components = descendants(capturePanel).toList()
                val table = components.filterIsInstance<JTable>().single()
                val sessions = components.filterIsInstance<JList<*>>().single()
                val editors = components.filterIsInstance<JSplitPane>().single {
                    it.leftComponent === requestComponent && it.rightComponent === responseComponent
                }
                assertEquals(JSplitPane.HORIZONTAL_SPLIT, editors.orientation)
                assertEquals(0.5, editors.resizeWeight)
                assertEquals(0, table.rowCount)
                verify(exactly = 0) { trackedManager.getCaptureEntry(any(), any()) }
                verify(exactly = 0) { trackedManager.getCaptureEntry(any<CapturedEntryRef>()) }

                sessions.selectedIndex = 0
                assertEquals(2, table.rowCount)
                assertEquals("/z-last", table.model.getValueAt(0, 3))
                verify(exactly = 0) { trackedManager.getCaptureEntry(any(), any()) }
                verify(exactly = 0) { trackedManager.getCaptureEntry(any<CapturedEntryRef>()) }

                // Keep the table snapshot while a queued refresh follows a concurrent deletion.
                assertTrue(manager.deleteCaptureEntry("ui-session", 0))
                assertEquals("/a-first", manager.getEntryRefs("ui-session").single().path)

                table.rowSorter.sortKeys = listOf(RowSorter.SortKey(3, SortOrder.ASCENDING))
                table.setRowSelectionInterval(1, 1)
                verify(exactly = 1) { trackedManager.getCaptureEntry(originalRefs[0]) }
                verify(exactly = 0) { trackedManager.getCaptureEntry(any(), any()) }
                verify(exactly = 1) { HttpRequest.httpRequest(selectedRequest) }
                verify(exactly = 1) { requestEditor.setRequest(displayedRequest) }
                verify(exactly = 1) { responseEditor.setResponse(displayedResponse) }

                capturePanel.setSize(1000, 700)
                capturePanel.doLayout()
                val image = BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    capturePanel.printAll(graphics)
                } finally {
                    graphics.dispose()
                    image.flush()
                }
                assertSame(requestComponent, editors.leftComponent)
                assertSame(responseComponent, editors.rightComponent)

                every { trackedManager.getCaptureEntry(originalRefs[1]) } throws
                    IllegalStateException("synthetic-sensitive-payload")
                table.setRowSelectionInterval(0, 0)
                val errorLabel = components.filterIsInstance<JLabel>().single {
                    it.text.startsWith("Payload unavailable")
                }
                assertTrue(errorLabel.text.contains("Restore the external capture storage from backup"))
                assertFalse(errorLabel.text.contains("synthetic-sensitive-payload"))
                val actions = table.componentPopupMenu.components.filterIsInstance<JMenuItem>()
                actions.single { it.text == "Send to Repeater" }.doClick()
                actions.single { it.text == "Send to Intruder" }.doClick()
                verify(exactly = 0) { api.repeater().sendToRepeater(any<HttpRequest>(), any<String>()) }
                verify(exactly = 0) { api.intruder().sendToIntruder(any<HttpRequest>()) }
            }
        } finally {
            SwingUtilities.invokeAndWait { panel?.cleanup() }
            unmockkStatic(HttpRequest::class, HttpResponse::class)
        }
    }

    private fun seed(manager: CaptureManager, path: String): String {
        val requestText = "GET $path HTTP/1.1\r\nHost: example.test\r\n\r\n"
        val request = mockk<HttpRequest>(relaxed = true)
        every { request.toString() } returns requestText
        every { request.method() } returns "GET"
        every { request.path() } returns path
        every { request.url() } returns "https://example.test$path"
        every { request.httpService().host() } returns "example.test"
        every { request.httpService().port() } returns 443
        val response = mockk<HttpResponseReceived>(relaxed = true)
        every { response.initiatingRequest() } returns request
        every { response.toString() } returns "HTTP/1.1 200 OK\r\n\r\nsynthetic-ui-response"
        every { response.statusCode() } returns 200
        manager.captureResponse(response)
        return requestText
    }

    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) {
            component.components.forEach { yieldAll(descendants(it)) }
        }
    }
}
