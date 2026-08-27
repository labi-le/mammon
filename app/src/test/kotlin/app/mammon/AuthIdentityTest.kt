package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the AUTH_SYS identity's parsing boundaries. uid and gid are `unsigned int` on the
 * wire, so the accepted range outgrows [Int] in one direction and stops at zero in the
 * other, and RFC 5531 appendix A caps the supplementary list at 16 entries.
 */
class AuthIdentityTest {

    @Test fun `the full unsigned range parses and one past it does not`() {
        assertEquals(4_294_967_295L, AuthIdentity.idOrNull("4294967295"))
        assertNull(AuthIdentity.idOrNull("4294967296"))
        assertEquals(0L, AuthIdentity.idOrNull("0"))
    }

    @Test fun `a negative or non decimal id is refused rather than coerced`() {
        assertNull(AuthIdentity.idOrNull("-1"))
        assertNull(AuthIdentity.idOrNull("+7"))
        assertNull(AuthIdentity.idOrNull("1000x"))
        assertNull(AuthIdentity.idOrNull("1e3"))
        assertNull(AuthIdentity.idOrNull(""))
        assertNull(AuthIdentity.idOrNull("  "))
    }

    @Test fun `an empty supplementary list is a valid identity, not an error`() {
        assertEquals(emptyList<Long>(), AuthIdentity.auxOrNull(""))
        assertEquals(AuthIdentity(1000, 100, emptyList()), AuthIdentity.parse("1000:100:"))
    }

    @Test fun `the supplementary list stops at the protocol's sixteen`() {
        val sixteen = (1..16).joinToString(",")
        assertEquals(16, AuthIdentity.auxOrNull(sixteen)?.size)
        assertNull(AuthIdentity.auxOrNull((1..17).joinToString(",")))
    }

    @Test fun `one bad entry rejects the whole supplementary list`() {
        assertNull(AuthIdentity.auxOrNull("100,-5"))
        assertNull(AuthIdentity.auxOrNull("100,,237"))
    }

    @Test fun `parse round trips the form the daemon argv and the prefs both carry`() {
        val identity = AuthIdentity(1000, 237, listOf(237, 100))
        assertEquals("1000:237:237,100", identity.toString())
        assertEquals(identity, AuthIdentity.parse(identity.toString()))
        assertEquals(AuthIdentity.DEFAULT, AuthIdentity.parse(AuthIdentity.DEFAULT.toString()))
    }

    @Test fun `a shape that is not uid gid aux is refused`() {
        assertNull(AuthIdentity.parse("1000:237"))
        assertNull(AuthIdentity.parse("1000:237:100:5"))
        assertNull(AuthIdentity.parse(""))
        assertNull(AuthIdentity.parse("nobody:nogroup:"))
    }
}
