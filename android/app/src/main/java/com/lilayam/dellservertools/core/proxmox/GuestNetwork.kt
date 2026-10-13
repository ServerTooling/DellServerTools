package com.lilayam.dellservertools.core.proxmox

import org.json.JSONArray
import org.json.JSONObject

/** One IP address of a guest, as reported by the guest itself or set statically in its config. */
data class GuestAddress(val iface: String, val address: String, val prefix: Int?) {
    val isIpv6: Boolean get() = ':' in address
    val cidr: String get() = if (prefix != null) "$address/$prefix" else address
}

/**
 * A guest's current IP addresses. The config only holds static addresses (`ip=dhcp` says nothing),
 * so the live ones come from the guest: `GET /nodes/{node}/lxc/{vmid}/interfaces` for containers and
 * the QEMU guest agent's `network-get-interfaces` for VMs.
 */
object GuestNetwork {

    /** `GET /nodes/{node}/lxc/{vmid}/interfaces`. */
    fun lxcInterfaces(data: JSONArray): List<GuestAddress> = clean(
        (0 until data.length()).mapNotNull { data.optJSONObject(it) }.flatMap { o ->
            val name = o.optString("name")
            val list = o.optJSONArray("ip-addresses")
            if (list != null) {
                addressList(name, list)
            } else {
                // Before PVE 8.1 there is only one inet and one inet6 string per interface.
                listOf("inet", "inet6").flatMap { key ->
                    o.optString(key).split(' ', ',').filter { it.isNotBlank() }.mapNotNull { parseCidr(name, it) }
                }
            }
        },
    )

    /** `GET /nodes/{node}/qemu/{vmid}/agent/network-get-interfaces`, whose data is `{"result": [...]}`. */
    fun agentInterfaces(data: JSONObject): List<GuestAddress> {
        val result = data.optJSONArray("result") ?: return emptyList()
        return clean(
            (0 until result.length()).mapNotNull { result.optJSONObject(it) }.flatMap { o ->
                addressList(o.optString("name"), o.optJSONArray("ip-addresses") ?: JSONArray())
            },
        )
    }

    /**
     * Static addresses from the config: `ip=`/`ip6=` of a container's `netN`, or of a VM's cloud-init
     * `ipconfigN`. `dhcp`, `auto` and `manual` are not addresses and are skipped.
     */
    fun staticAddresses(config: List<Pair<String, String>>): List<GuestAddress> = config.flatMap { (key, value) ->
        val index = Regex("(net|ipconfig)(\\d+)").matchEntire(key) ?: return@flatMap emptyList()
        val opts = options(value)
        val iface = opts["name"] ?: "net${index.groupValues[2]}"
        listOfNotNull(opts["ip"], opts["ip6"]).mapNotNull { parseCidr(iface, it) }
    }.sortedBy { it.isIpv6 }

    /** Whether a VM's `agent` option is on, e.g. `1`, `enabled=1,fstrim_cloned_disks=1` or `1,type=virtio`. */
    fun agentEnabled(config: List<Pair<String, String>>): Boolean {
        val value = config.firstOrNull { it.first == "agent" }?.second ?: return false
        val first = value.substringBefore(',')
        val enabled = if ('=' in first) options(value)["enabled"] else first
        return enabled == "1"
    }

    private fun options(value: String): Map<String, String> = value.split(',')
        .filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun addressList(iface: String, list: JSONArray): List<GuestAddress> =
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { a ->
            val address = a.optString("ip-address").ifEmpty { return@mapNotNull null }
            GuestAddress(iface, address, if (a.has("prefix")) a.optInt("prefix") else null)
        }

    private fun parseCidr(iface: String, text: String): GuestAddress? {
        val address = text.substringBefore('/')
        if (!address.contains('.') && !address.contains(':')) return null  // dhcp, auto, manual
        return GuestAddress(iface, address, text.substringAfter('/', "").toIntOrNull())
    }

    /**
     * Drops what nobody wants to connect to: loopback, IPv6 link-local, and the host-side bridges
     * Docker and libvirt create inside a guest (a `docker0` 172.17.0.1 would read as the guest's IP).
     */
    private fun clean(addresses: List<GuestAddress>): List<GuestAddress> = addresses
        .filterNot { a ->
            a.iface == "lo" || INTERNAL_PREFIXES.any { a.iface.startsWith(it) } ||
                a.address.startsWith("127.") || a.address == "::1" || a.address.lowercase().startsWith("fe80:")
        }
        .distinct()
        .sortedBy { it.isIpv6 }

    private val INTERNAL_PREFIXES = listOf("docker", "br-", "veth", "virbr", "cni", "flannel")
}
