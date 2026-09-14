package app.mammon

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [HandleCache]'s time bound is a correctness property rather than a tuning knob: a
 * filehandle survives a foreign rename, so nothing on the wire can tell this session that
 * an entry stopped denoting the path it is keyed under.
 */
class Nfs3HandleCacheTest {

    private var nanos = 0L
    private val cache = HandleCache { nanos }
    private val fh = byteArrayOf(1, 2, 3)

    @Test fun `a binding is handed out up to its bound and not past it`() {
        cache.remember("/a/b", fh)
        nanos += HandleCache.TTL_NANOS
        assertArrayEquals("a binding inside the window is what the cache is for", fh, cache["/a/b"])
        nanos += 1
        assertNull("a binding past its bound may denote another object", cache["/a/b"])
    }

    @Test fun `resolving a path again restarts its bound`() {
        cache.remember("/a/b", fh)
        nanos += HandleCache.TTL_NANOS + 1
        cache.remember("/a/b", fh)
        nanos += HandleCache.TTL_NANOS
        assertArrayEquals(fh, cache["/a/b"])
    }

    /** Written in seconds rather than in [HandleCache.TTL_NANOS], so that raising the
     *  bound fails here instead of moving the other cases with it. */
    @Test fun `a binding is gone six seconds on`() {
        cache.remember("/a/b", fh)
        nanos += TimeUnit.SECONDS.toNanos(6)
        assertNull("a handle may denote another object seconds after it was resolved", cache["/a/b"])
    }

    /** The sweep is keyed on the separator, so `/a/bc` is not under `/a/b`. It runs on
     *  every successful removal, and a bare `startsWith` would drop live bindings whose
     *  only relation to the removed name is a shared prefix. */
    @Test fun `forgetting a tree spares a name that merely shares its prefix`() {
        cache.remember("/a/b", fh)
        cache.remember("/a/bc", fh)
        cache.remember("/a/b/c", fh)

        cache.forgetTree("/a/b")

        assertNull("the removed name itself must be gone", cache["/a/b"])
        assertNull("a name under the removed one must be gone", cache["/a/b/c"])
        assertArrayEquals("a name that merely shares a prefix must survive", fh, cache["/a/bc"])
    }

    /** The rig measured the unbounded-budget version against a 300-child directory: the
     *  listing cost its 3 prefix LOOKUPs again straight afterwards, because 3 prefixes
     *  plus a whole cap of children is more inserts than the cap holds and the prefixes
     *  were the eldest entries in it. Harvesting the budget exactly, rather than the 300
     *  the rig used, keeps the inserts here equal to the cap whatever the cap is, so a
     *  budget that ignores the prefixes overflows by the prefix count at any cap. */
    @Test fun `a listing-sized harvest spares the prefixes that listing resolved`() {
        val prefixes = listOf("/cprobe", "/cprobe/l1", "/cprobe/l1/l2")
        prefixes.forEach { cache.remember(it, fh) }

        val harvested = HandleCache.harvestBudget(prefixes.size)
        for (i in 0 until harvested) cache.remember("/cprobe/l1/l2/child$i", fh)

        prefixes.forEach {
            assertArrayEquals("$it made the listing cheap and must outlive it", fh, cache[it])
        }
        assertArrayEquals(
            "the harvest keeps its head too, so nothing was evicted at all",
            fh,
            cache["/cprobe/l1/l2/child0"],
        )
    }
}
