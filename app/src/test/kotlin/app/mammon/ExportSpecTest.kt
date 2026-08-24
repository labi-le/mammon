package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExportSpecTest {

    @Test fun `plain host export`() {
        val s = ExportSpec.parse("192.0.2.1:/export/data")!!
        assertEquals("192.0.2.1", s.host)
        assertEquals("/export/data", s.export)
        assertEquals(2049, s.port)
        assertEquals("data", s.exportTail)
    }

    @Test fun `host with port`() {
        val s = ExportSpec.parse("nas.example.com:2049:/srv")!!
        assertEquals("nas.example.com", s.host)
        assertEquals(2049, s.port)
        assertEquals("/srv", s.export)
    }

    @Test fun `export with subpath is one path`() {
        val s = ExportSpec.parse("h:/export/sub/dir")!!
        assertEquals("/export/sub/dir", s.export)
        assertEquals("dir", s.exportTail)
    }

    @Test fun `trailing slash normalized`() {
        val s = ExportSpec.parse("h:/x/")!!
        assertEquals("/x", s.export)
    }

    @Test fun `root export`() {
        val s = ExportSpec.parse("h:/")!!
        assertEquals("/", s.export)
        assertEquals("h", s.exportTail)
    }

    @Test fun `rejects garbage`() {
        assertNull(ExportSpec.parse(""))
        assertNull(ExportSpec.parse("no-colon"))
        assertNull(ExportSpec.parse("host:noslash"))
        assertNull(ExportSpec.parse(":/x"))
        assertNull(ExportSpec.parse("host:.."))
        assertNull(ExportSpec.parse("host:/a/../b"))
        assertNull(ExportSpec.parse("host:99999:/x"))
        assertNull(ExportSpec.parse("host:0:/x"))
    }

    @Test fun `port boundary values`() {
        assertNotNull(ExportSpec.parse("h:1:/x"))
        assertNotNull(ExportSpec.parse("h:65535:/x"))
    }

    @Test fun `ipv6 host with brackets`() {
        val s = ExportSpec.parse("[2001:db8::1]:/x")!!
        assertEquals("[2001:db8::1]", s.host)
        assertEquals("/x", s.export)
    }

    @Test fun `prefs round trip format`() {
        // Prefs.spec() re-parses "host:port:export"; this pins that contract.
        val s = ExportSpec.parse("nas.example.com:12049:/media/docs")!!
        assertEquals("nas.example.com", s.host)
        assertEquals(12049, s.port)
        assertEquals("/media/docs", s.export)
    }
}
