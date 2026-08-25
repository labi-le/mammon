package app.mammon

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins version selection: v4.1 wins when the server answers it, v3 is reached only
 * after v4 fails, and a cancelled probe is never mistaken for a v4-only server.
 */
class NfsVersionSelectionTest {

    private class Fake : NfsSession {
        var closed = false
        override fun probeRoot(): NodeAttrs? = null
        override fun stat(docId: String): NodeAttrs? = null
        override fun list(docId: String): List<ChildEntry> = emptyList()
        override fun streamFor(docId: String): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun close() { closed = true }
    }

    private val spec = ExportSpec("192.0.2.1", "/export", 2049)

    @Test fun `v4 wins and v3 is never opened`() {
        val v4 = Fake()
        val picked = NfsSessions.select(spec, { v4 }, { fail("v3 must not be opened"); error("unreachable") })
        assertSame(v4, picked)
        assertFalse(v4.closed)
    }

    @Test fun `a v3 only server falls through to v3`() {
        val v3 = Fake()
        val picked = NfsSessions.select(
            spec,
            { throw IOException("rpc program mismatch") },
            { v3 },
        )
        assertSame(v3, picked)
    }

    @Test fun `a v4 stack that cannot load falls through to v3`() {
        val v3 = Fake()
        val picked = NfsSessions.select(
            spec,
            { throw NoClassDefFoundError("org/glassfish/grizzly/Grizzly") },
            { v3 },
        )
        assertSame(v3, picked)
    }

    @Test fun `the spec reaches both attempts unchanged`() {
        val seen = ArrayList<ExportSpec>()
        NfsSessions.select(
            spec,
            { seen += it; throw IOException("no v4") },
            { seen += it; Fake() },
        )
        assertEquals(listOf(spec, spec), seen)
    }

    @Test fun `both failing reports the v3 error and keeps the v4 one`() {
        val v4Failure = IOException("exchange_id refused")
        val v3Failure = IOException("portmap unreachable")
        try {
            NfsSessions.select(spec, { throw v4Failure }, { throw v3Failure })
            fail("expected the v3 failure")
        } catch (e: IOException) {
            assertSame(v3Failure, e)
            assertTrue(e.suppressed.contains(v4Failure))
        }
    }

    @Test fun `a cancelled v4 probe is not a protocol failure`() {
        try {
            NfsSessions.select(
                spec,
                { throw CancellationException("timed out") },
                { fail("v3 must not be opened"); error("unreachable") },
            )
            fail("expected the cancellation to propagate")
        } catch (e: CancellationException) {
            assertEquals("timed out", e.message)
        }
    }
}
