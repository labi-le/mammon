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
}
