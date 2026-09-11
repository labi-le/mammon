package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class NfsScannerTest {

    @Test fun `slash 24 expands to 254 usable hosts`() {
        val a = NfsScanner.addresses("192.168.1.77", 24)
        assertEquals(254, a.size)
        assertEquals("192.168.1.1", a.first())
        assertEquals("192.168.1.254", a.last())
    }

    @Test fun `baseIp mid-range keeps ascending order`() {
        val a = NfsScanner.addresses("10.0.0.130", 24)
        assertEquals("10.0.0.1", a.first())
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"), a.take(3))
    }

    @Test fun `slash 16 collapses to the baseIp's slash 24 `() {
        val a = NfsScanner.addresses("172.16.9.200", 16)
        assertEquals(254, a.size)
        assertTrue(a.all { it.startsWith("172.16.9.") })
    }

    @Test fun `slash 28 excludes network and broadcast only`() {
        val a = NfsScanner.addresses("192.168.4.5", 28)
        assertEquals((1..14).map { "192.168.4.$it" }, a)
    }

    @Test fun `slash 31 keeps both addresses per RFC 3021`() {
        assertEquals(listOf("10.9.9.4", "10.9.9.5"), NfsScanner.addresses("10.9.9.5", 31))
    }

    @Test fun `slash 32 yields its single address`() {
        assertEquals(listOf("10.1.2.3"), NfsScanner.addresses("10.1.2.3", 32))
    }

    @Test fun `malformed input yields empty`() {
        assertTrue(NfsScanner.addresses("not-an-ip", 24).isEmpty())
        assertTrue(NfsScanner.addresses("1.2.3", 24).isEmpty())
        assertTrue(NfsScanner.addresses("256.0.0.1", 24).isEmpty())
    }

    @Test fun `cap trims from the front of the block`() {
        assertEquals(
            (1..10).map { "192.168.1.$it" },
            NfsScanner.addresses("192.168.1.77", 24, cap = 10),
        )
    }


    // Loopback aliases (127/8) let two ports share one number: both listeners
    // answer, every other alias refuses, and the result must preserve input
    // order rather than sorting.
    @Test fun `scan keeps open hosts in input order and drops refused ones`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.2")).use { a ->
            ServerSocket(a.localPort, 50, InetAddress.getByName("127.0.0.3")).use { b ->
                assertEquals(a.localPort, b.localPort)
                assertEquals(
                    listOf("127.0.0.3", "127.0.0.2"),
                    NfsScanner.scan(
                        listOf("127.0.0.3", "127.0.0.9", "127.0.0.2", "127.0.0.1"),
                        port = a.localPort,
                    ),
                )
            }
        }
    }
}
