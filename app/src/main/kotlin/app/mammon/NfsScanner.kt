package app.mammon

import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * NFS server discovery: expand the phone's current IPv4 subnet into candidates
 * and probe TCP :2049. Only [currentSubnet] touches Android, keeping the rest
 * JVM-testable.
 */
object NfsScanner {

    const val NFS_PORT = 2049

    /** LAN RTTs are sub-millisecond; the timeout only bounds dead hosts. */
    private const val TIMEOUT_MS = 150
    private const val MAX_HOSTS = 256
    private const val WORKERS = 64

    /**
     * Expands the /[prefixLength] network holding [baseIp] into ascending
     * candidates, capped at [cap]. Network and broadcast drop out wherever
     * derivable (prefix <= 30; /31 keeps both addresses per RFC 3021), and
     * anything wider than a /24 scans only the /24 holding [baseIp], so a /16
     * Wi-Fi costs 254 probes instead of 65 thousand. Malformed input yields an
     * empty list.
     */
    fun addresses(baseIp: String, prefixLength: Int, cap: Int = MAX_HOSTS): List<String> {
        val (network, prefix) = scanBlock(baseIp, prefixLength) ?: return emptyList()
        if (cap <= 0) return emptyList()
        val last = network or mask(prefix).inv()
        val first = if (prefix <= 30) network + 1 else network
        val end = if (prefix <= 30) last - 1 else last
        // Long math: first + cap - 1 overflows Int near the top of the range.
        return (first..minOf(end.toLong(), first.toLong() + cap - 1).toInt()).map(::dotted)
    }

    /**
     * Probes TCP [port] on every host, preserving input order; refusing and
     * unreachable hosts drop out. Blocks — call it off the main thread.
     */
    fun scan(
        hosts: List<String>,
        port: Int = NFS_PORT,
        timeoutMs: Int = TIMEOUT_MS,
    ): List<String> {
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(WORKERS, hosts.size)) { r ->
            Thread(r).apply { isDaemon = true }
        }
        try {
            val probes = hosts.map { h -> pool.submit(Callable { connects(h, port, timeoutMs) }) }
            return probes.withIndex().mapNotNull { (i, future) ->
                if (try { future.get() } catch (_: Exception) { false }) hosts[i] else null
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** [baseIp]'s scannable block as (network, prefix), clamped down to at most a /24. */
    private fun scanBlock(baseIp: String, prefixLength: Int): Pair<Int, Int>? {
        val o = baseIp.split('.').map { it.toIntOrNull() ?: return null }
        if (o.size != 4 || o.any { it !in 0..255 }) return null
        val prefix = maxOf(prefixLength.coerceIn(0, 32), 24)
        val base = (o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]
        return (base and mask(prefix)) to prefix
    }

    private fun mask(prefix: Int): Int = if (prefix == 0) 0 else -1 shl (32 - prefix)

    private fun connects(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    private fun dotted(ip: Int): String =
        "${ip ushr 24 and 0xff}.${ip ushr 16 and 0xff}.${ip ushr 8 and 0xff}.${ip and 0xff}"

    /**
     * The active network's IPv4 address and prefix length, or null when the
     * device has no local v4 link (mobile data, VPN-only, airplane mode).
     * Reading LinkProperties requires ACCESS_NETWORK_STATE.
     */
    fun currentSubnet(cm: ConnectivityManager): Pair<String, Int>? {
        val links = cm.getLinkProperties(cm.activeNetwork) ?: return null
        for (la in links.linkAddresses) {
            val addr = la.address
            val host = addr.hostAddress ?: continue
            return host to la.prefixLength
        }
        return null
    }
}
