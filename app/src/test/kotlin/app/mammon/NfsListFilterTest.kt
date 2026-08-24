package app.mammon

import com.emc.ecs.nfsclient.nfs.NfsType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the pure half of the READDIRPLUS listing: entry filtering and path assembly. */
class NfsListFilterTest {
    @Test fun `keeps directories and regular files with assembled paths`() {
        // Root children assemble as "/name" (parent path "/" already ends in a slash).
        assertEquals("/dir", NfsAccess.listableChild("/", "dir", NfsType.NFS_DIR))
        assertEquals(
            "/a/b/file.txt",
            NfsAccess.listableChild("/a/b", "file.txt", NfsType.NFS_REG),
        )
    }

    @Test fun `drops dot entries and non listable types`() {
        assertNull(NfsAccess.listableChild("/", ".", NfsType.NFS_DIR))
        assertNull(NfsAccess.listableChild("/", "..", NfsType.NFS_DIR))
        assertNull(NfsAccess.listableChild("/x", "fifo", NfsType.NFS_FIFO))
        assertNull(NfsAccess.listableChild("/x", "sock", NfsType.NFS_SOCK))
        // Symlinks would need a per-child lookup to resolve; they are not listed.
        assertNull(NfsAccess.listableChild("/x", "link", NfsType.NFS_LNK))
    }

    @Test fun `drops blank names and null types`() {
        assertNull(NfsAccess.listableChild("/x", "", NfsType.NFS_REG))
        assertNull(NfsAccess.listableChild("/x", null, NfsType.NFS_REG))
        assertNull(NfsAccess.listableChild("/x", "name", null))
    }
}
