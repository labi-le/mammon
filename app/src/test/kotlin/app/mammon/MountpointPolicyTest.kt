package app.mammon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MountpointPolicy.isInStorageSurface] answers one question only: is this path inside
 * Android's own storage surface — tmpfs /storage, the MediaProvider FUSE view under
 * /storage/emulated, /sdcard (a symlink into it) and /data/media (its backing). Being
 * inside it is no longer a refusal: a subdirectory of the emulated tree is the product's
 * flagship mountpoint, and [EmulatedMount] routes it. What turns membership into a
 * verdict is [MountpointPolicy.refusalFor], which is what both entrances call.
 *
 * Matching is at path-component boundaries so a sibling top-level directory stays
 * mountable.
 */
class MountpointPolicyTest {

    private val insideStorageSurface = listOf(
        "/storage",
        "/storage/",
        "/storage/emulated/0/nfs",      // routable, hence NOT refused — see refusalFor
        "/storage//emulated/0/nfs",     // embedded double slash
        "//storage/emulated/0/nfs",     // leading double slash
        "/sdcard",
        "/sdcard/",
        "/sdcard/0",
        "/data/media",
        "/data/media/",
        "/data/media/0/anything",
    )

    private val outsideStorageSurface = listOf(
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

    @Test fun `the storage surface covers emulated storage and its subtrees`() {
        for (path in insideStorageSurface) {
            assertTrue("should be inside the surface: $path", MountpointPolicy.isInStorageSurface(path))
        }
    }

    @Test fun `real kernel paths and lookalikes stay outside the surface`() {
        for (path in outsideStorageSurface) {
            assertFalse("should be outside the surface: $path", MountpointPolicy.isInStorageSurface(path))
        }
    }

    @Test fun `normalize collapses slash runs and trims trailing slash`() {
        assertEquals("/storage/emulated/0", MountpointPolicy.normalize("//storage//emulated/0/"))
        assertEquals("/mnt/nas", MountpointPolicy.normalize("/mnt/nas/"))
        assertEquals("/", MountpointPolicy.normalize(""))
        assertEquals("/", MountpointPolicy.normalize("//"))
    }

    /** The composition RootMount.mount and the mountpoint field both apply: without it
     *  mounting /data/media/0/nfs would mkdir and mount below the FUSE daemon. */
    @Test fun `a path inside the storage surface that cannot be routed is still refused`() {
        for (path in listOf(
            "/storage",
            "/storage/0123-4567/nfs",
            "/storage/self/primary/nfs",
            "/data/media",
            "/data/media/0/nfs",
        )) {
            assertEquals(path, EmulatedMount.Refusal.NOT_EMULATED, MountpointPolicy.refusalFor(path))
        }
    }

    /**
     * err_bad_mountpoint_storage claims a physical volume under /storage or /data/media,
     * and "/storage/emulated/0/../nfs" is neither: it is inside emulated storage and
     * names a directory that exists. So the dot class is its own refusal, and the corpus
     * has to span both ways EmulatedMount reaches NOT_EMULATED — the subPath check, and
     * a dot in the media-id slot, which is only a non-numeric id to it.
     *
     * The module already drew this distinction (`fslib.sh:529-535`); running its
     * automount over this same corpus under mksh is what makes the two comparable.
     */
    @Test fun `a dot component in the storage surface is refused as itself`() {
        for (path in listOf(
            "/storage/emulated/0/./nfs",
            "/storage/emulated/0/../nfs",
            "/storage/emulated/0/nfs/..",
            "/sdcard/../../data/local/nfs",
            "/storage/emulated/./0/nfs",
            "/storage/emulated/../data/media/0/nfs",
            "/data/media/0/../../local/tmp",
            "//storage/emulated/0/../nfs/",
        )) {
            assertEquals(path, EmulatedMount.Refusal.HAS_DOT_COMPONENT, MountpointPolicy.refusalFor(path))
        }
    }

    /**
     * The dot refusal is a taxonomy split inside the storage surface, never a new rule
     * about dots: outside it a mountpoint is handed to root as saved, and the module
     * mounts these two verbatim as well. Only a relative spelling is refused, and for
     * being relative.
     */
    @Test fun `a dot component outside the storage surface is still mounted verbatim`() {
        assertNull(MountpointPolicy.refusalFor("/mnt/user/0/emulated/0/../nfs"))
        assertNull(MountpointPolicy.refusalFor("/mnt/nas/../foo"))
        assertEquals(EmulatedMount.Refusal.NOT_ABSOLUTE, MountpointPolicy.refusalFor("./nfs"))
    }

    /**
     * What the user is told to DO about it. The mountpoint field's own arm is one line of
     * `when` over the same enum (`MainActivity.mountpointFieldError`), which the compiler
     * keeps exhaustive; this entrance is the one a JVM test can execute, and it must name
     * the fault rather than hand back an exit code from a mount that never ran.
     */
    @Test fun `the mount entrance says what a dot component costs`() {
        val spawned = mutableListOf<String>()
        val result = RootMount.mount(
            "192.0.2.1",
            "/export",
            2049,
            "/storage/emulated/0/../nfs",
            RootMount.FuseLaunch("/data/app/base.apk", "/data/local/tmp/fuse.log", AuthIdentity.DEFAULT),
        ) { script ->
            spawned += script
            RootMount.SuResult(0, "", "")
        }

        assertFalse(result.message, result.ok)
        assertTrue(result.message, result.message.contains(". or .. step"))
        assertTrue(result.message, result.message.contains("/storage/emulated/0/../nfs"))
        assertEquals("a dot component is refused before any su spawn", emptyList<String>(), spawned)
    }

    /** NOT_EMULATED from EmulatedMount means "not routable through the emulated view",
     *  never "refused": a path with no relation to Android's storage is a plain
     *  mountpoint. */
    @Test fun `a path outside the storage surface earns no refusal`() {
        for (path in listOf("/mnt/nas", "/mnt/storage/nfs", "/data/mediafoo", "/storageroom")) {
            assertNull(path, MountpointPolicy.refusalFor(path))
        }
    }

    /** A routable emulated path is not refusable from the path alone — whether this
     *  device propagates is a mount-table question. */
    @Test fun `a routable emulated path is left to the device`() {
        assertNull(MountpointPolicy.refusalFor("/storage/emulated/0/nfs"))
        assertNull(MountpointPolicy.refusalFor("/sdcard/nfs"))
        assertNull(MountpointPolicy.refusalFor("//storage//emulated/0/nfs/"))
    }

    /** The app half of the parity FslibAutomountFuseFdTest pins for the module: a
     *  /mnt/user spelling whose user id is not numeric is not the master path, so it is
     *  neither routed nor refused — it is mounted where it was typed, and the module now
     *  agrees. Its glob spanned slashes and routed these to the derived master instead,
     *  which made one saved pref produce two different mounts. */
    @Test fun `a mnt user spelling with no numeric user id is not emulated and not refused`() {
        for (path in listOf(
            "/mnt/user/abc/emulated/0/nfs",
            "/mnt/user/x/y/emulated/0/nfs",
            "/mnt/user/abc/emulated",
        )) {
            assertEquals(path, EmulatedMount.Refusal.NOT_EMULATED, EmulatedMount.localRefusal(path))
            assertNull(path, MountpointPolicy.refusalFor(path))
        }
    }

    @Test fun `the path-shaped refusals pass through unchanged`() {
        assertEquals(EmulatedMount.Refusal.IS_TREE_ROOT, MountpointPolicy.refusalFor("/sdcard"))
        assertEquals(EmulatedMount.Refusal.IS_TREE_ROOT, MountpointPolicy.refusalFor("/storage/emulated/0"))
        assertEquals(EmulatedMount.Refusal.RESERVED_NAME, MountpointPolicy.refusalFor("/sdcard/Android"))
        assertEquals(
            EmulatedMount.Refusal.RESERVED_NAME,
            MountpointPolicy.refusalFor("/storage/emulated/0/Android/data"),
        )
    }
}
