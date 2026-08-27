package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathCodecTest {

    @Test fun `root round trip`() {
        assertEquals("/", PathCodec.pathFor(PathCodec.ROOT_ID))
        assertEquals(PathCodec.ROOT_ID, PathCodec.docIdFor("/"))
    }

    @Test fun `nested round trip`() {
        assertEquals("/a/b/c.txt", PathCodec.pathFor("a/b/c.txt"))
        assertEquals("a/b/c.txt", PathCodec.docIdFor("/a/b/c.txt"))
    }

    @Test fun `rejects traversal`() {
        assertNull(PathCodec.pathFor(".."))
        assertNull(PathCodec.pathFor("a/../b"))
        assertNull(PathCodec.pathFor("../etc/passwd"))
        assertNull(PathCodec.docIdFor("/../x"))
    }

    @Test fun `rejects leading slash tricks and empties`() {
        assertNull(PathCodec.pathFor("/a"))
        assertNull(PathCodec.pathFor("a/"))
        assertNull(PathCodec.pathFor("a//b"))
        assertNull(PathCodec.pathFor(""))
        assertNull(PathCodec.docIdFor("//"))
        assertNull(PathCodec.docIdFor("."))
        assertNull(PathCodec.docIdFor("./a"))
        assertNull(PathCodec.pathFor("./a"))
        assertNull(PathCodec.pathFor("a/."))
    }

    /**
     * `componentOf` already refuses a NUL, but `deleteDocument` splits an id with
     * `nameOf` and never reaches it, so the codec's own gates have to.
     */
    @Test fun `rejects an embedded NUL`() {
        assertNull(PathCodec.pathFor("a\u0000b"))
        assertNull(PathCodec.pathFor("a/b\u0000c"))
        assertNull(PathCodec.parentOf("a/b\u0000c"))
        assertNull(PathCodec.docIdFor("/a\u0000b"))
    }

    @Test fun `name and child relations`() {
        assertEquals("c.txt", PathCodec.nameOf("a/b/c.txt"))
        assertEquals(PathCodec.ROOT_ID, PathCodec.nameOf(PathCodec.ROOT_ID))
        assertTrue(PathCodec.isChild(PathCodec.ROOT_ID, "a"))
        assertFalse(PathCodec.isChild(PathCodec.ROOT_ID, PathCodec.ROOT_ID))
        assertTrue(PathCodec.isChild("a", "a/b"))
        assertFalse(PathCodec.isChild("a", "ab"))
        // the classic confusion: a prefix that is not a path prefix
        assertFalse(PathCodec.isChild("a/b", "a/bc"))
        // ids that never resolve are not children of anything
        assertFalse(PathCodec.isChild("a", ".."))
    }

    @Test fun `parentOf walks up and stops at the export root`() {
        assertNull(PathCodec.parentOf(PathCodec.ROOT_ID))
        assertEquals(PathCodec.ROOT_ID, PathCodec.parentOf("a"))
        assertEquals("a", PathCodec.parentOf("a/b"))
        assertEquals("a/b", PathCodec.parentOf("a/b/c.txt"))
    }

    /**
     * deleteDocument splits an id into parent plus name, so an id this refuses is one
     * that never reaches NFS as a pair that only the backend would reject.
     */
    @Test fun `parentOf refuses an id that does not resolve`() {
        assertNull(PathCodec.parentOf(""))
        assertNull(PathCodec.parentOf("a/"))
        assertNull(PathCodec.parentOf("/a"))
        assertNull(PathCodec.parentOf("a//b"))
        assertNull(PathCodec.parentOf("a/../b"))
        assertNull(PathCodec.parentOf(".."))
    }

    /** A mutation takes a parent plus a name, so this is where a hostile name stops. */
    @Test fun `childDocId refuses anything that is not one component`() {
        assertNull(PathCodec.childDocId(PathCodec.ROOT_ID, "a/b"))
        assertNull(PathCodec.childDocId(PathCodec.ROOT_ID, ".."))
        assertNull(PathCodec.childDocId(PathCodec.ROOT_ID, ""))
        assertNull(PathCodec.childDocId(PathCodec.ROOT_ID, null))
        assertNull(PathCodec.childDocId("..", "x"))
    }

    @Test fun `childDocId builds ids under the root and under a directory`() {
        assertEquals("a", PathCodec.childDocId(PathCodec.ROOT_ID, "a"))
        assertEquals("a/b/c", PathCodec.childDocId("a/b", "c"))
    }
}
