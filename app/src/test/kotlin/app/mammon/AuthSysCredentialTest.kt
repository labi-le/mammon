package app.mammon

import org.dcache.oncrpc4j.rpc.RpcAuthType
import org.dcache.oncrpc4j.xdr.Xdr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the AUTH_SYS credential on the wire. The supplementary group list is the whole
 * point: a root_squash export checks the file's group against it, so a credential that
 * can only carry one group cannot reach a tree owned by a second one.
 */
class AuthSysCredentialTest {

    private class Decoded(
        val flavor: Int,
        val length: Int,
        val machine: String,
        val uid: Int,
        val gid: Int,
        val auxGids: List<Int>,
        val size: Int,
    )

    private fun encode(identity: AuthIdentity, machine: String = "mammon"): Decoded {
        val bytes = Xdr(512).use { xdr ->
            xdr.beginEncoding()
            AuthSys(identity, machine).xdrEncode(xdr)
            xdr.endEncoding()
            xdr.getBytes()
        }
        return Xdr(bytes).use { xdr ->
            xdr.beginDecoding()
            val flavor = xdr.xdrDecodeInt()
            val length = xdr.xdrDecodeInt()
            xdr.xdrDecodeInt()
            val machineName = String(xdr.xdrDecodeDynamicOpaque())
            Decoded(
                flavor,
                length,
                machineName,
                xdr.xdrDecodeInt(),
                xdr.xdrDecodeInt(),
                xdr.xdrDecodeIntVector().toList(),
                bytes.size,
            )
        }
    }

    @Test fun `every supplementary group reaches the wire`() {
        val decoded = encode(AuthIdentity(1000, 237, listOf(237, 100)))
        assertEquals(RpcAuthType.UNIX, decoded.flavor)
        assertEquals("mammon", decoded.machine)
        assertEquals(1000, decoded.uid)
        assertEquals(237, decoded.gid)
        assertEquals(listOf(237, 100), decoded.auxGids)
    }

    @Test fun `an empty supplementary list encodes as a zero length vector`() {
        assertEquals(emptyList<Int>(), encode(AuthIdentity(1000, 100, emptyList())).auxGids)
    }

    @Test fun `sixteen groups all survive the encoding`() {
        val gids = (1..16).map { it.toLong() }
        assertEquals(gids.map(Long::toInt), encode(AuthIdentity(0, 0, gids)).auxGids)
    }

    /**
     * The declared body length is what the server uses to find the verifier, so it has to
     * grow with the list rather than describe a fixed one-element copy of the gid.
     */
    @Test fun `the declared length and the encoding grow one word per group`() {
        val one = encode(AuthIdentity(1000, 237, listOf(237)))
        val two = encode(AuthIdentity(1000, 237, listOf(237, 100)))
        val five = encode(AuthIdentity(1000, 237, listOf(237, 100, 1, 2, 3)))
        assertEquals(4, two.length - one.length)
        assertEquals(4, two.size - one.size)
        assertEquals(16, five.length - one.length)
        assertEquals(16, five.size - one.size)
    }

    /** uid and gid are unsigned on the wire, so the top of the range must ride the bits. */
    @Test fun `the unsigned top of the range keeps its bit pattern`() {
        val decoded = encode(AuthIdentity(AuthIdentity.MAX_ID, AuthIdentity.MAX_ID, listOf(65534)))
        assertEquals(-1, decoded.uid)
        assertEquals(-1, decoded.gid)
    }

    /** A machine name needing XDR padding must not shift the fields after it. */
    @Test fun `a padded machine name leaves the identity readable`() {
        val decoded = encode(AuthIdentity(1000, 237, listOf(237, 100)), "abcde")
        assertEquals("abcde", decoded.machine)
        assertEquals(1000, decoded.uid)
        assertEquals(listOf(237, 100), decoded.auxGids)
        assertTrue(decoded.length % 4 == 0)
    }
}
