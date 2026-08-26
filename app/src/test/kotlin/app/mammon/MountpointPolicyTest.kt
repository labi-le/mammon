package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mountpoint policy rejects only Android's emulated-storage surface — tmpfs
 * /storage, the MediaProvider FUSE mount under /storage/emulated, /sdcard (a symlink
 * into it) and /data/media (its backing) — never a real kernel path. Matching is at
 * path-component boundaries so a sibling top-level directory stays mountable.
 */
class MountpointPolicyTest {

    private val rejected = listOf(
        "/storage",
        "/storage/",
        "/storage/emulated/0/nfs",      // the field-bug path
        "/storage//emulated/0/nfs",     // embedded double slash
        "//storage/emulated/0/nfs",     // leading double slash
        "/sdcard",
        "/sdcard/",
        "/sdcard/0",
        "/data/media",
        "/data/media/",
        "/data/media/0/anything",
    )

    private val accepted = listOf(
        "/storageroom",                  // sibling, not the storage tree
        "/sdcardx",
        "/data/mediafoo",
        "/data/mediaBackup",
        "/mnt/nas",
        "/mnt/nas/",
        "/mnt/storage/nfs",              // "storage" as a component under /mnt
        "/mnt/sdcard-ish",
        "/",
        "",
        "/mnt/сетевое",                  // unicode path is untouched by the / splitter
        "//mnt/nas",                     // double slash outside the rejected trees
    )

    @Test fun `rejected set covers emulated storage and its subtrees`() {
        for (path in rejected) {
            assertTrue("should reject: $path", MountpointPolicy.isUnmountable(path))
        }
    }

    @Test fun `accepted set leaves real kernel paths and lookalikes mountable`() {
        for (path in accepted) {
            assertFalse("should accept: $path", MountpointPolicy.isUnmountable(path))
        }
    }

    @Test fun `normalize collapses slash runs and trims trailing slash`() {
        assertEquals("/storage/emulated/0", MountpointPolicy.normalize("//storage//emulated/0/"))
        assertEquals("/mnt/nas", MountpointPolicy.normalize("/mnt/nas/"))
        assertEquals("/", MountpointPolicy.normalize(""))
        assertEquals("/", MountpointPolicy.normalize("//"))
    }
}
