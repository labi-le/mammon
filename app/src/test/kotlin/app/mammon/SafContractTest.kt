package app.mammon

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the SAF write surface makes without a server. Flags are advisory by
 * specification, so the exception and errno mappings are the load-bearing part: a client
 * that gets a clean return treats the mutation as done.
 */
class SafContractTest {

    @Test fun `a writable directory row offers create and delete but never write`() {
        val flags = SafFlags.forNode(isDirectory = true, writable = true)
        assertTrue(flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0)
        assertTrue(flags and Document.FLAG_SUPPORTS_DELETE != 0)
        assertEquals(0, flags and Document.FLAG_SUPPORTS_WRITE)
    }

    @Test fun `a writable file row offers write and delete but never directory create`() {
        val flags = SafFlags.forNode(isDirectory = false, writable = true)
        assertTrue(flags and Document.FLAG_SUPPORTS_WRITE != 0)
        assertTrue(flags and Document.FLAG_SUPPORTS_DELETE != 0)
        assertEquals(0, flags and Document.FLAG_DIR_SUPPORTS_CREATE)
    }

    /** No backend refuses writes wholesale any more; the flags still have to obey the gate. */
    @Test fun `a backend that implements no writes advertises nothing`() {
        assertEquals(0, SafFlags.forNode(isDirectory = true, writable = false))
        assertEquals(0, SafFlags.forNode(isDirectory = false, writable = false))
    }

    /** RENAME is absent from the backend; the flag would promise a throwing implementation. */
    @Test fun `no row ever offers rename`() {
        val everything = SafFlags.forNode(isDirectory = true, writable = true) or
            SafFlags.forNode(isDirectory = false, writable = true) or
            SafFlags.EXPORT_ROOT
        assertEquals(0, everything and Document.FLAG_SUPPORTS_RENAME)
    }

    @Test fun `the export root can be created in but not deleted`() {
        assertTrue(SafFlags.EXPORT_ROOT and Document.FLAG_DIR_SUPPORTS_CREATE != 0)
        assertEquals(0, SafFlags.EXPORT_ROOT and Document.FLAG_SUPPORTS_DELETE)
    }

    /** Without it the provider is filtered out of create pickers before any query runs. */
    @Test fun `the root row keeps is-child and gains create`() {
        assertTrue(SafFlags.ROOT and Root.FLAG_SUPPORTS_CREATE != 0)
        assertTrue(SafFlags.ROOT and Root.FLAG_SUPPORTS_IS_CHILD != 0)
    }

    @Test fun `a read-only open never truncates`() {
        assertEquals(SafOpenMode(read = true, write = false, truncate = false), SafOpenMode.parse("r"))
    }

    /** openOutputStream(uri) asks for bare "w" and writes a whole new content. */
    @Test fun `bare w truncates like wt`() {
        val truncating = SafOpenMode(read = false, write = true, truncate = true)
        assertEquals(truncating, SafOpenMode.parse("w"))
        assertEquals(truncating, SafOpenMode.parse("wt"))
    }

    /** rw is random access: truncating would destroy what the client opened to read. */
    @Test fun `rw does not truncate but rwt does`() {
        assertEquals(SafOpenMode(read = true, write = true, truncate = false), SafOpenMode.parse("rw"))
        assertEquals(SafOpenMode(read = true, write = true, truncate = true), SafOpenMode.parse("rwt"))
    }

    /** A proxy fd carries no O_APPEND, so an appending client would land at offset 0. */
    @Test fun `append is refused rather than approximated`() {
        assertNull(SafOpenMode.parse("wa"))
        assertNull(SafOpenMode.parse("rwa"))
    }

    @Test fun `modes this provider cannot honour are refused`() {
        assertNull(SafOpenMode.parse(""))
        assertNull(SafOpenMode.parse("t"))
        assertNull(SafOpenMode.parse("rt"))
        assertNull(SafOpenMode.parse("rwx"))
    }

    @Test fun `only a read-write open asks for read-write access bits`() {
        assertEquals(ParcelFileDescriptor.MODE_WRITE_ONLY, SafOpenMode.parse("w")!!.proxyMode)
        assertEquals(ParcelFileDescriptor.MODE_READ_WRITE, SafOpenMode.parse("rw")!!.proxyMode)
    }

    /**
     * DocumentsUI drops an UnsupportedOperationException from New folder and from SAVE
     * without showing the user anything, so a refusal it renders is the only useful one,
     * and FileNotFoundException is what `createDocument` declares.
     */
    @Test fun `every refusal reaches the client as the exception the write methods declare`() {
        assertTrue(NfsFailure.Unsupported("create").asSafException("m") is FileNotFoundException)
        assertTrue(NfsFailure.PermissionDenied("x").asSafException("m") is FileNotFoundException)
        assertTrue(NfsFailure.NotFound("x").asSafException("m") is FileNotFoundException)
        assertTrue(NfsFailure.AlreadyExists("x").asSafException("m") is FileNotFoundException)
        assertTrue(NfsFailure.DirectoryNotEmpty("x").asSafException("m") is FileNotFoundException)
        assertTrue(NfsFailure.OutOfSpace("x").asSafException("m") is FileNotFoundException)
        assertTrue(NfsFailure.Server("x").asSafException("m") is FileNotFoundException)
    }

    @Test fun `the mapped exception carries the readable message, not the wire text`() {
        assertEquals("nope", NfsFailure.PermissionDenied("/a/b").asSafException("nope").message)
    }

    /** ENOSPC and EACCES must not arrive as EIO: a writer acts on them differently. */
    @Test fun `each failure reaches the writer as its own errno`() {
        assertEquals(SafErrno.ACCESS, NfsFailure.PermissionDenied("x").safErrno())
        assertEquals(SafErrno.NOENT, NfsFailure.NotFound("x").safErrno())
        assertEquals(SafErrno.EXISTS, NfsFailure.AlreadyExists("x").safErrno())
        assertEquals(SafErrno.NOTEMPTY, NfsFailure.DirectoryNotEmpty("x").safErrno())
        assertEquals(SafErrno.NOSPC, NfsFailure.OutOfSpace("x").safErrno())
        assertEquals(SafErrno.NOSYS, NfsFailure.Unsupported("write").safErrno())
        assertEquals(SafErrno.IO, NfsFailure.Server("x").safErrno())
    }

    /**
     * `openProxyFileDescriptor` needs a mount the framework makes per app, and a kernel
     * that refuses it fails every write on the device rather than anything about this
     * export. It arrives as IllegalStateException, which `openDocument` does not declare,
     * so without this arm a client's `openOutputStream` sees an undeclared RuntimeException
     * carrying a bare "Failed to mount" instead of a file error it can render.
     */
    @Test fun `a device that cannot give this app a proxy descriptor reads as a file error`() {
        assertEquals(
            ProxyOpenOutcome.NO_PROXY_FD,
            IllegalStateException("Failed to mount").proxyOpenOutcome(),
        )
    }

    /** The documented failure of that call, and the one already carrying a usable message. */
    @Test fun `an io failure from the open still reads as a file error`() {
        assertEquals(
            ProxyOpenOutcome.FILE_ERROR,
            IOException("Failed to mount proxy bridge").proxyOpenOutcome(),
        )
        assertEquals(ProxyOpenOutcome.FILE_ERROR, FileNotFoundException("gone").proxyOpenOutcome())
    }

    /**
     * Translating these would hide a bug behind a file error: only the two cases above are
     * the framework refusing the descriptor, and everything else is ours to answer for.
     */
    @Test fun `any other failure keeps its own type`() {
        assertEquals(ProxyOpenOutcome.RETHROW, IllegalArgumentException("bad mode").proxyOpenOutcome())
        assertEquals(ProxyOpenOutcome.RETHROW, NullPointerException().proxyOpenOutcome())
        assertEquals(ProxyOpenOutcome.RETHROW, OutOfMemoryError().proxyOpenOutcome())
    }

    /** The row's MIME type is re-derived from the name, so the extension has to agree. */
    @Test fun `a create appends the extension its mime type implies`() {
        assertEquals("notes.txt", SafNaming.createdName("text/plain", "notes"))
        assertEquals("notes.txt", SafNaming.createdName("text/plain", "notes.txt"))
        assertEquals("shot.jpeg", SafNaming.createdName("image/jpeg", "shot.jpeg"))
        assertEquals("shot.jpg", SafNaming.createdName("image/jpeg", "shot"))
    }

    @Test fun `a mime type the table does not know leaves the name alone`() {
        assertEquals("blob", SafNaming.createdName(SafNaming.OCTET_STREAM, "blob"))
        assertEquals("thing.dat", SafNaming.createdName("application/x-nonesuch", "thing.dat"))
    }

    /**
     * The framework reads both create extras out of a Bundle unchecked, and a directory has
     * no extension to own — neither may turn into a name the caller did not ask for.
     */
    @Test fun `a null type and a directory type leave the name alone`() {
        assertEquals("notes", SafNaming.createdName(null, "notes"))
        assertEquals("photos", SafNaming.createdName(Document.MIME_TYPE_DIR, "photos"))
        assertEquals("photos.txt", SafNaming.createdName(Document.MIME_TYPE_DIR, "photos.txt"))
    }

    /** One table, inverted: a second hand-kept map is what drifts. */
    @Test fun `the extension comes back out of the table that reads it`() {
        assertEquals("image/jpeg", SafNaming.mimeFor(SafNaming.createdName("image/jpeg", "x")))
        assertEquals("text/html", SafNaming.mimeFor(SafNaming.createdName("text/html", "x")))
    }

    @Test fun `de-duplication keeps the extension last`() {
        assertEquals("notes (2).txt", SafNaming.retryName("notes.txt", 2))
        assertEquals("notes (3)", SafNaming.retryName("notes", 3))
        assertEquals(".hidden (2)", SafNaming.retryName(".hidden", 2))
    }
}
