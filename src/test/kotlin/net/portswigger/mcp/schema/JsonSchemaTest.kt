package net.portswigger.mcp.schema

import net.portswigger.mcp.tools.CaptureStart
import net.portswigger.mcp.tools.CaptureStorageCleanup
import net.portswigger.mcp.tools.GetProxyHttpHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonSchemaTest {
    @Test
    fun `capture callers only need to supply a session id`() {
        assertEquals(listOf("sessionId"), CaptureStart::class.asInputSchema().required)
    }

    @Test
    fun `defaults are optional without weakening required history arguments`() {
        assertEquals(emptyList<String>(), CaptureStorageCleanup::class.asInputSchema().required)
        assertEquals(listOf("count", "offset"), GetProxyHttpHistory::class.asInputSchema().required)
    }
}
