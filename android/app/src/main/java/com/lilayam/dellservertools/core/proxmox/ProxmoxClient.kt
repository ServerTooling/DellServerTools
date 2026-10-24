package com.lilayam.dellservertools.core.proxmox

import com.lilayam.dellservertools.core.formatHostForUrl
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

class ProxmoxApiException(val httpStatus: Int, message: String) : IOException(message)

sealed interface ProxmoxCredentials {
    /** Username like `root@pam`. Gives a ticket, which also logs the in-app web UI in. */
    data class Password(val username: String, val password: String) : ProxmoxCredentials

    /** Token ID like `root@pam!phone` plus its secret. */
    data class ApiToken(val tokenId: String, val secret: String) : ProxmoxCredentials
}

data class ProxmoxTicket(val ticket: String, val csrfToken: String, val username: String)

/**
 * Minimal client for the Proxmox VE REST API (`/api2/json`).
 *
 * Every call blocks; run it off the main thread.
 */
class ProxmoxClient(
    host: String,
    port: Int,
    private val credentials: ProxmoxCredentials,
    pinnedFingerprint: String?,
) {
    val baseUrl: String = "https://${formatHostForUrl(host)}:$port"
    internal val tls = PinnedTls(pinnedFingerprint)

    @Volatile
    var ticket: ProxmoxTicket? = null
        private set

    /** Logs in (password auth) or checks the token, failing fast with a clear error. */
    fun login() {
        when (credentials) {
            is ProxmoxCredentials.Password -> {
                val data = rawRequest(
                    "POST",
                    "/access/ticket",
                    mapOf("username" to credentials.username, "password" to credentials.password),
                    authenticated = false,
                ) as? JSONObject ?: throw ProxmoxApiException(0, "Unexpected login response")
                if (data.has("NeedTFA") && data.optInt("NeedTFA") == 1 || data.optString("ticket").contains("!tfa!")) {
                    throw ProxmoxApiException(
                        0,
                        "This account uses two-factor authentication. Create an API token in Proxmox " +
                            "(Datacenter > Permissions > API Tokens) and enter it in the server settings.",
                    )
                }
                ticket = ProxmoxTicket(
                    ticket = data.getString("ticket"),
                    csrfToken = data.getString("CSRFPreventionToken"),
                    username = data.optString("username", credentials.username),
                )
            }
            is ProxmoxCredentials.ApiToken -> get("/version")
        }
    }

    /** The certificate that made the last request fail, if that was the reason. */
    val rejectedCertificate: UntrustedCertificateException? get() = tls.rejected

    fun version(): String = (get("/version") as JSONObject).let { "${it.optString("version")}-${it.optString("release")}" }

    fun resources(): List<ClusterResource> = ProxmoxJson.resources(get("/cluster/resources") as JSONArray)

    fun nodeStatus(node: String): NodeStatus = ProxmoxJson.nodeStatus(get("/nodes/${enc(node)}/status") as JSONObject)

    fun nodeCommand(node: String, command: String) {
        post("/nodes/${enc(node)}/status", mapOf("command" to command))
    }

    fun storage(node: String): List<StorageInfo> = ProxmoxJson.storage(get("/nodes/${enc(node)}/storage") as JSONArray)

    fun storageStatus(node: String, storage: String): StorageStatus =
        ProxmoxJson.storageStatus(get("/nodes/${enc(node)}/storage/${enc(storage)}/status") as JSONObject)

    fun storageConfig(storage: String): List<Pair<String, String>> =
        ProxmoxJson.storageConfig(get("/storage/${enc(storage)}") as JSONObject)

    fun storageContent(node: String, storage: String): List<StorageVolume> =
        ProxmoxJson.storageContent(get("/nodes/${enc(node)}/storage/${enc(storage)}/content") as JSONArray)

    fun zfsPool(node: String, pool: String): ZfsPool =
        ProxmoxJson.zfsPool(get("/nodes/${enc(node)}/disks/zfs/${enc(pool)}") as JSONObject)

    fun disks(node: String): List<PhysicalDisk> = ProxmoxJson.disks(get("/nodes/${enc(node)}/disks/list") as JSONArray)

    // ---- VM hardware

    /** USB devices plugged into the node, for passthrough. */
    fun usbDevices(node: String): List<UsbDevice> =
        VmHardware.usbDevices(get("/nodes/${enc(node)}/hardware/usb") as JSONArray)

    /** PCI devices of the node (bridges and the like left out by Proxmox's default filter), for passthrough. */
    fun pciDevices(node: String): List<PciDevice> =
        VmHardware.pciDevices(get("/nodes/${enc(node)}/hardware/pci") as JSONArray)

    /** Rebuilds a VM's cloud-init drive from its current cloud-init settings. */
    fun regenerateCloudInit(node: String, vmid: Int) {
        request("PUT", "${guestPath(node, GuestType.QEMU, vmid)}/cloudinit", emptyMap())
    }

    // ---- storage management

    /** Deletes one volume (ISO, template, backup, unused disk). Returns the task UPID when it runs as a task. */
    fun deleteVolume(node: String, storage: String, volid: String): String? =
        request("DELETE", "${storagePath(node, storage)}/content/${enc(volid)}", emptyMap()) as? String

    /** Sets a backup's notes and protection flag (a protected backup is never pruned or deleted). */
    fun updateVolume(node: String, storage: String, volid: String, notes: String, protected: Boolean) {
        request(
            "PUT",
            "${storagePath(node, storage)}/content/${enc(volid)}",
            mapOf("notes" to notes, "protected" to if (protected) "1" else "0"),
        )
    }

    /** File name and size of a URL, as the server sees it, before downloading. */
    fun queryUrlMetadata(node: String, url: String, verifyCertificates: Boolean = true): UrlMetadata =
        UrlMetadata.parse(
            request(
                "GET",
                "/nodes/${enc(node)}/query-url-metadata",
                mapOf("url" to url, "verify-certificates" to if (verifyCertificates) "1" else "0"),
            ) as? JSONObject,
        )

    /** Makes the server download an ISO or template itself. Returns the task UPID. */
    fun downloadUrl(node: String, storage: String, request: DownloadUrlRequest): String? =
        post("${storagePath(node, storage)}/download-url", request.toParams()) as? String

    /**
     * Uploads an ISO or template from the phone ([content] `iso` or `vztmpl`), streaming [input] of exactly [size]
     * bytes so a multi-GB ISO never sits in memory. Returns the UPID of the task that moves it into place.
     */
    fun upload(
        node: String,
        storage: String,
        content: String,
        filename: String,
        size: Long,
        input: InputStream,
        onProgress: (Long) -> Unit = {},
    ): String? {
        // Renew an expired ticket now: the body can't be sent twice, so the usual retry on 401 can't apply.
        get("/version")
        val boundary = "----dellservertools" + System.nanoTime()
        val head = buildString {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"content\"\r\n\r\n$content\r\n")
            // pveproxy wants the file as the last part, under the name "filename".
            append("--$boundary\r\nContent-Disposition: form-data; name=\"filename\"; filename=\"")
            append(filename.replace("\"", "").replace("\r", "").replace("\n", ""))
            append("\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = RequestBody("multipart/form-data; boundary=$boundary", head.size + size + tail.size) { out ->
            out.write(head)
            val buffer = ByteArray(256 * 1024)
            var sent = 0L
            var reported = 0L
            while (sent < size) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                if (n < 0) throw IOException("The file ended after $sent of $size bytes")
                out.write(buffer, 0, n)
                sent += n
                if (sent - reported >= PROGRESS_STEP || sent == size) {
                    reported = sent
                    onProgress(sent)
                }
            }
            out.write(tail)
        }
        return rawRequest("POST", "${storagePath(node, storage)}/upload", emptyMap(), authenticated = true, body = body) as? String
    }

    /** Which backups a retention policy would keep or remove; [vmid] limits it to one guest. */
    fun prunePreview(node: String, storage: String, options: PruneOptions, vmid: Int? = null): List<PruneEntry> =
        PruneEntry.parse(request("GET", "${storagePath(node, storage)}/prunebackups", pruneParams(options, vmid)) as JSONArray)

    /** Removes the backups [prunePreview] marks `remove`. Returns the task UPID. */
    fun pruneBackups(node: String, storage: String, options: PruneOptions, vmid: Int? = null): String? =
        request("DELETE", "${storagePath(node, storage)}/prunebackups", pruneParams(options, vmid)) as? String

    private fun pruneParams(options: PruneOptions, vmid: Int?): Map<String, String> {
        val p = linkedMapOf("prune-backups" to options.toParam())
        vmid?.let { p["vmid"] = it.toString() }
        return p
    }

    /** The storage.cfg entry as a map (`GET /storage/{id}`). */
    fun storageDefinition(storage: String): Map<String, String> =
        (get("/storage/${enc(storage)}") as JSONObject).let { o ->
            o.keys().asSequence().filter { it != "digest" }.associateWith { o.opt(it)?.toString().orEmpty() }
        }

    fun createStorage(form: StorageForm) {
        post("/storage", form.createParams())
    }

    fun updateStorage(form: StorageForm) {
        val params = form.updateParams()
        if (params.isNotEmpty()) request("PUT", "/storage/${enc(form.id)}", params)
    }

    /** Removes the storage definition only; the data on it stays where it is. */
    fun deleteStorage(storage: String) {
        request("DELETE", "/storage/${enc(storage)}", emptyMap())
    }

    // ---- backup jobs (Datacenter > Backup)

    fun backupJobs(): List<BackupJob> = BackupJob.parse(get("/cluster/backup") as JSONArray)

    fun createBackupJob(form: BackupJobForm) {
        post("/cluster/backup", form.createParams())
    }

    fun updateBackupJob(form: BackupJobForm) {
        val params = form.updateParams()
        if (params.isNotEmpty()) request("PUT", "/cluster/backup/${enc(form.id)}", params)
    }

    fun deleteBackupJob(id: String) {
        request("DELETE", "/cluster/backup/${enc(id)}", emptyMap())
    }

    /** Runs a job's backup now on [node], as the web UI's Run now does. Returns the task UPID. */
    fun runBackupJob(node: String, form: BackupJobForm): String? = post("/nodes/${enc(node)}/vzdump", form.runParams()) as? String

    // ---- node system: network, DNS, hosts, time, services, updates, repositories, journal

    fun networkInterfaces(node: String): List<NetworkInterface> = NetworkInterface.parse(get("/nodes/${enc(node)}/network") as JSONArray)

    fun createNetwork(node: String, form: NetworkForm) {
        post("/nodes/${enc(node)}/network", form.createParams())
    }

    fun updateNetwork(node: String, form: NetworkForm) {
        put("/nodes/${enc(node)}/network/${enc(form.iface)}", form.updateParams())
    }

    fun deleteNetwork(node: String, iface: String) {
        delete("/nodes/${enc(node)}/network/${enc(iface)}")
    }

    /** Applies the pending `/etc/network/interfaces.new` with ifreload. Returns the task UPID. */
    fun applyNetwork(node: String): String? = put("/nodes/${enc(node)}/network", emptyMap()) as? String

    /** Throws away the pending network changes. */
    fun revertNetwork(node: String) {
        delete("/nodes/${enc(node)}/network")
    }

    fun dns(node: String): DnsSettings = DnsSettings.parse(get("/nodes/${enc(node)}/dns") as JSONObject)

    fun setDns(node: String, dns: DnsSettings) {
        put("/nodes/${enc(node)}/dns", dns.params())
    }

    /** `/etc/hosts` and its digest, which the save sends back so a concurrent edit isn't overwritten. */
    fun hosts(node: String): Pair<String, String> =
        (get("/nodes/${enc(node)}/hosts") as JSONObject).let { it.optString("data") to it.optString("digest") }

    fun setHosts(node: String, data: String, digest: String) {
        post("/nodes/${enc(node)}/hosts", linkedMapOf("data" to data).apply { if (digest.isNotEmpty()) put("digest", digest) })
    }

    fun timezone(node: String): String = (get("/nodes/${enc(node)}/time") as JSONObject).optString("timezone")

    fun setTimezone(node: String, timezone: String) {
        put("/nodes/${enc(node)}/time", mapOf("timezone" to timezone))
    }

    fun services(node: String): List<NodeService> = NodeService.parse(get("/nodes/${enc(node)}/services") as JSONArray)

    /** [action] is start, stop, restart or reload. Returns the task UPID. */
    fun serviceAction(node: String, service: String, action: String): String? {
        require(action in setOf("start", "stop", "restart", "reload")) { "Unknown service action $action" }
        return post("/nodes/${enc(node)}/services/${enc(service)}/$action", emptyMap()) as? String
    }

    fun pendingUpdates(node: String): List<PackageUpdate> = PackageUpdate.parse(get("/nodes/${enc(node)}/apt/update") as JSONArray)

    /** `apt-get update`. Returns the task UPID. */
    fun refreshUpdates(node: String): String? = post("/nodes/${enc(node)}/apt/update", emptyMap()) as? String

    fun aptRepositories(node: String): AptRepositories = AptRepositories.parse(get("/nodes/${enc(node)}/apt/repositories") as JSONObject)

    fun setAptRepositoryEnabled(node: String, repo: AptRepository, enabled: Boolean, digest: String) {
        val p = linkedMapOf("path" to repo.path, "index" to repo.index.toString(), "enabled" to if (enabled) "1" else "0")
        if (digest.isNotEmpty()) p["digest"] = digest
        post("/nodes/${enc(node)}/apt/repositories", p)
    }

    /** Adds a standard repository (e.g. `no-subscription`) by its handle. */
    fun addStandardRepository(node: String, handle: String, digest: String) {
        put("/nodes/${enc(node)}/apt/repositories", linkedMapOf("handle" to handle).apply { if (digest.isNotEmpty()) put("digest", digest) })
    }

    fun journal(node: String, lines: Int = 500): List<String> =
        NodeSystemJson.journal(get("/nodes/${enc(node)}/journal?lastentries=$lines") as JSONArray)

    // ---- node disks: SMART, wipe, GPT, LVM / LVM-thin / directory / ZFS

    fun smart(node: String, disk: String): SmartData =
        SmartData.parse(get("/nodes/${enc(node)}/disks/smart?disk=${enc(disk)}") as JSONObject)

    /** Wipes the partition table and signatures of [disk]. Returns the task UPID. */
    fun wipeDisk(node: String, disk: String): String? = put("/nodes/${enc(node)}/disks/wipedisk", mapOf("disk" to disk)) as? String

    /** Returns the task UPID. */
    fun initGpt(node: String, disk: String): String? = post("/nodes/${enc(node)}/disks/initgpt", mapOf("disk" to disk)) as? String

    /** Everything built on the node's disks, from all four panels; a panel the node can't list is left out. */
    fun diskVolumes(node: String): List<DiskVolume> {
        val base = "/nodes/${enc(node)}/disks"
        return listOf(
            { DiskVolume.zfs(get("$base/zfs") as JSONArray) },
            { DiskVolume.lvm(get("$base/lvm") as JSONObject) },
            { DiskVolume.lvmThin(get("$base/lvmthin") as JSONArray) },
            { DiskVolume.directories(get("$base/directory") as JSONArray) },
        ).flatMap { runCatching(it).getOrDefault(emptyList()) }
    }

    /** Returns the task UPID. */
    fun createDiskVolume(node: String, form: DiskCreateForm): String? =
        post("/nodes/${enc(node)}/disks/${form.kind.path}", form.params()) as? String

    /** Destroys it, removes its storage entry and wipes its disks. Returns the task UPID. */
    fun destroyDiskVolume(node: String, volume: DiskVolume): String? = delete(volume.destroyPath(node, ::enc)) as? String

    // ---- node certificates: custom upload and ACME

    fun certificates(node: String): List<CertificateInfo> =
        CertificateInfo.list(get("/nodes/${enc(node)}/certificates/info") as JSONArray)

    /** Installs a custom certificate and restarts pveproxy; returns the new certificate's details. */
    fun uploadCertificate(node: String, form: CustomCertificateForm): CertificateInfo? =
        (post("/nodes/${enc(node)}/certificates/custom", form.params()) as? JSONObject)?.let(CertificateInfo::parse)

    /** Removes the custom certificate, so pveproxy serves Proxmox's own again. */
    fun deleteCustomCertificate(node: String) {
        delete("/nodes/${enc(node)}/certificates/custom?restart=1")
    }

    fun acmeSettings(node: String): AcmeSettings = AcmeSettings.parse(get("/nodes/${enc(node)}/config") as JSONObject)

    fun setNodeConfig(node: String, params: Map<String, String>) {
        put("/nodes/${enc(node)}/config", params)
    }

    fun acmeAccounts(): List<String> = AcmeJson.accounts(get("/cluster/acme/account") as JSONArray)

    fun acmeDirectories(): List<AcmeDirectory> = AcmeJson.directories(get("/cluster/acme/directories") as JSONArray)

    /** The URL of the directory's terms of service, which registering accepts. */
    fun acmeTermsOfService(directory: String): String = get("/cluster/acme/tos?directory=${enc(directory)}")?.toString().orEmpty()

    fun acmeDnsPlugins(): List<String> = AcmeJson.dnsPlugins(get("/cluster/acme/plugins") as JSONArray)

    /** Returns the task UPID. */
    fun registerAcmeAccount(form: AcmeAccountForm): String? = post("/cluster/acme/account", form.params()) as? String

    /** Orders a certificate for the node's ACME domains, or renews it ([renew]). Returns the task UPID. */
    fun orderAcmeCertificate(node: String, renew: Boolean): String? {
        val path = "/nodes/${enc(node)}/certificates/acme/certificate"
        return (if (renew) put(path, mapOf("force" to "1")) else post(path, emptyMap())) as? String
    }

    // ---- users, groups, API tokens, permissions, roles, pools, two-factor

    fun users(): List<PveUser> = PveUser.parse(get("/access/users?full=1") as JSONArray)

    fun createUser(form: UserForm) {
        post("/access/users", form.createParams())
    }

    fun updateUser(form: UserForm) {
        put("/access/users/${enc(form.userid)}", form.updateParams())
    }

    fun deleteUser(userid: String) {
        delete("/access/users/${enc(userid)}")
    }

    /**
     * Sets a `pve`-realm user's password. Proxmox asks for the signed-in user's own password as
     * [confirmation] when it changes someone else's or its own (PVE 8.1+); older versions ignore it.
     */
    fun setPassword(userid: String, password: String, confirmation: String) {
        put("/access/password", linkedMapOf("userid" to userid, "password" to password).apply {
            if (confirmation.isNotEmpty()) put("confirmation-password", confirmation)
        })
    }

    fun realms(): List<String> = AccessJson.realms(get("/access/domains") as JSONArray)

    fun groups(): List<PveGroup> = PveGroup.parse(get("/access/groups") as JSONArray)

    fun createGroup(groupid: String, comment: String) {
        post("/access/groups", linkedMapOf("groupid" to groupid).apply { if (comment.isNotBlank()) put("comment", comment.trim()) })
    }

    fun deleteGroup(groupid: String) {
        delete("/access/groups/${enc(groupid)}")
    }

    /** Creates a token; the secret in the answer is never shown again. */
    fun createToken(userid: String, tokenid: String, privsep: Boolean, expire: Long, comment: String): NewApiToken =
        AccessJson.newToken(
            post("/access/users/${enc(userid)}/token/${enc(tokenid)}", ApiToken.createParams(privsep, expire, comment)) as JSONObject,
        )

    fun deleteToken(userid: String, tokenid: String) {
        delete("/access/users/${enc(userid)}/token/${enc(tokenid)}")
    }

    fun acl(): List<AclEntry> = AclEntry.parse(get("/access/acl") as JSONArray)

    fun addAcl(entry: AclEntry) {
        put("/access/acl", entry.addParams())
    }

    fun removeAcl(entry: AclEntry) {
        put("/access/acl", entry.removeParams())
    }

    fun roles(): List<PveRole> = PveRole.parse(get("/access/roles") as JSONArray)

    fun createRole(roleid: String, privs: Collection<String>) {
        post("/access/roles", PveRole.createParams(roleid, privs))
    }

    /** Replaces the role's privileges with [privs]. */
    fun updateRole(roleid: String, privs: Collection<String>) {
        put("/access/roles/${enc(roleid)}", mapOf("privs" to privs.sorted().joinToString(",")))
    }

    fun deleteRole(roleid: String) {
        delete("/access/roles/${enc(roleid)}")
    }

    fun pools(): List<PvePool> = PvePool.list(get("/pools") as JSONArray)

    fun pool(poolid: String): PvePool = PvePool.detail(poolid, get("/pools/${enc(poolid)}") as JSONObject)

    fun createPool(poolid: String, comment: String) {
        post("/pools", linkedMapOf("poolid" to poolid).apply { if (comment.isNotBlank()) put("comment", comment.trim()) })
    }

    fun setPoolMembers(poolid: String, vmids: List<Int>, storages: List<String>, remove: Boolean) {
        put("/pools/${enc(poolid)}", PvePool.membersParams(vmids, storages, remove))
    }

    fun deletePool(poolid: String) {
        delete("/pools/${enc(poolid)}")
    }

    fun tfa(): List<TfaEntry> = TfaEntry.parse(get("/access/tfa") as JSONArray)

    /** Removes one second factor, e.g. for a user who lost their phone. */
    fun deleteTfa(userid: String, id: String) {
        delete("/access/tfa/${enc(userid)}/${enc(id)}")
    }

    // ---- firewall (datacenter, node, guest and security groups)

    fun firewallRules(scope: FirewallScope): List<FirewallRule> = FirewallRule.parse(get(scope.rulesPath) as JSONArray)

    fun addFirewallRule(scope: FirewallScope, form: FirewallRuleForm) {
        post(scope.rulesPath, form.createParams())
    }

    fun updateFirewallRule(scope: FirewallScope, pos: Int, params: Map<String, String>) {
        if (params.isNotEmpty()) put("${scope.rulesPath}/$pos", params)
    }

    /** Moves a rule to [to]; the rules in between shift by one, as with drag and drop in the web UI. */
    fun moveFirewallRule(scope: FirewallScope, pos: Int, to: Int) {
        put("${scope.rulesPath}/$pos", mapOf("moveto" to to.toString()))
    }

    fun deleteFirewallRule(scope: FirewallScope, pos: Int) {
        delete("${scope.rulesPath}/$pos")
    }

    fun firewallOptions(scope: FirewallScope): Map<String, String> = FirewallNames.options(get("${scope.path}/options") as JSONObject)

    fun setFirewallOptions(scope: FirewallScope, params: Map<String, String>) {
        put("${scope.path}/options", params)
    }

    fun firewallAliases(scope: FirewallScope): List<FirewallAlias> = FirewallAlias.parse(get("${scope.path}/aliases") as JSONArray)

    fun addFirewallAlias(scope: FirewallScope, alias: FirewallAlias) {
        post("${scope.path}/aliases", aliasParams(alias) + ("name" to alias.name))
    }

    fun updateFirewallAlias(scope: FirewallScope, alias: FirewallAlias) {
        put("${scope.path}/aliases/${enc(alias.name)}", aliasParams(alias))
    }

    fun deleteFirewallAlias(scope: FirewallScope, name: String) {
        delete("${scope.path}/aliases/${enc(name)}")
    }

    private fun aliasParams(alias: FirewallAlias): Map<String, String> =
        linkedMapOf("cidr" to alias.cidr.trim()).apply { if (alias.comment.isNotBlank()) put("comment", alias.comment.trim()) }

    fun ipSets(scope: FirewallScope): List<IpSet> = IpSet.parse(get("${scope.path}/ipset") as JSONArray)

    fun addIpSet(scope: FirewallScope, name: String, comment: String) {
        post("${scope.path}/ipset", linkedMapOf("name" to name).apply { if (comment.isNotBlank()) put("comment", comment.trim()) })
    }

    fun deleteIpSet(scope: FirewallScope, name: String) {
        delete("${scope.path}/ipset/${enc(name)}")
    }

    fun ipSetEntries(scope: FirewallScope, name: String): List<IpSetEntry> =
        IpSetEntry.parse(get("${scope.path}/ipset/${enc(name)}") as JSONArray)

    fun addIpSetEntry(scope: FirewallScope, name: String, entry: IpSetEntry) {
        val p = linkedMapOf("cidr" to entry.cidr.trim())
        if (entry.nomatch) p["nomatch"] = "1"
        if (entry.comment.isNotBlank()) p["comment"] = entry.comment.trim()
        post("${scope.path}/ipset/${enc(name)}", p)
    }

    fun deleteIpSetEntry(scope: FirewallScope, name: String, cidr: String) {
        delete("${scope.path}/ipset/${enc(name)}/${enc(cidr)}")
    }

    fun securityGroups(): List<SecurityGroup> = SecurityGroup.parse(get("/cluster/firewall/groups") as JSONArray)

    fun addSecurityGroup(name: String, comment: String) {
        post("/cluster/firewall/groups", linkedMapOf("group" to name).apply { if (comment.isNotBlank()) put("comment", comment.trim()) })
    }

    fun deleteSecurityGroup(name: String) {
        delete("/cluster/firewall/groups/${enc(name)}")
    }

    fun firewallMacros(): List<Pair<String, String>> = FirewallNames.macros(get("/cluster/firewall/macros") as JSONArray)

    fun firewallLog(scope: FirewallScope, limit: Int = 500): List<String> =
        FirewallNames.log(get("${scope.path}/log?limit=$limit") as JSONArray)

    /** Guests that no enabled job backs up. */
    fun notBackedUp(): List<UnprotectedGuest> = UnprotectedGuest.parse(get("/cluster/backup-info/not-backed-up") as JSONArray)

    /** ZFS pools, LVM volume groups and thin pools on the node, and NFS exports of a server, for the add form. */
    fun scanZfs(node: String): List<String> = scan(node, "zfs", emptyMap(), "pool")

    fun scanLvm(node: String): List<String> = scan(node, "lvm", emptyMap(), "vg")

    fun scanLvmThin(node: String, vg: String): List<String> = scan(node, "lvmthin", mapOf("vg" to vg), "lv")

    fun scanNfs(node: String, server: String): List<String> = scan(node, "nfs", mapOf("server" to server), "path")

    private fun scan(node: String, type: String, params: Map<String, String>, field: String): List<String> {
        val data = request("GET", "/nodes/${enc(node)}/scan/$type", params) as? JSONArray ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString(field)?.takeIf(String::isNotEmpty) }
    }

    fun guestStatus(node: String, type: GuestType, vmid: Int): GuestStatus =
        ProxmoxJson.guestStatus(get("${guestPath(node, type, vmid)}/status/current") as JSONObject)

    fun guestConfig(node: String, type: GuestType, vmid: Int): List<Pair<String, String>> =
        ProxmoxJson.config(get("${guestPath(node, type, vmid)}/config") as JSONObject)

    /** The guest's current addresses: LXC asks the container, QEMU asks the guest agent (fails when it isn't running). */
    fun guestAddresses(node: String, type: GuestType, vmid: Int): List<GuestAddress> = when (type) {
        GuestType.LXC -> GuestNetwork.lxcInterfaces(get("${guestPath(node, type, vmid)}/interfaces") as JSONArray)
        GuestType.QEMU ->
            GuestNetwork.agentInterfaces(get("${guestPath(node, type, vmid)}/agent/network-get-interfaces") as JSONObject)
    }

    /** start, stop, shutdown, reboot, suspend, resume, reset. Returns the task UPID. */
    fun guestAction(node: String, type: GuestType, vmid: Int, action: String): String? =
        post("${guestPath(node, type, vmid)}/status/$action", emptyMap()) as? String

    /** Saves a running VM's memory to disk and stops it; the next start resumes it. Returns the task UPID. */
    fun hibernate(node: String, vmid: Int): String? =
        post("${guestPath(node, GuestType.QEMU, vmid)}/status/suspend", mapOf("todisk" to "1")) as? String

    /** Moves a disk or volume to another storage. Returns the task UPID. */
    fun moveDisk(node: String, vmid: Int, request: MoveDiskRequest): String? =
        post("${guestPath(node, request.type, vmid)}/${request.path}", request.toParams()) as? String

    /** Turns a stopped guest into a template, for linked clones. Can't be undone. */
    fun convertToTemplate(node: String, type: GuestType, vmid: Int): String? =
        post("${guestPath(node, type, vmid)}/template", emptyMap()) as? String

    /** Asks a running task to stop. */
    fun stopTask(node: String, upid: String) {
        request("DELETE", "/nodes/${enc(node)}/tasks/${enc(upid)}", emptyMap())
    }

    /** Presses [key] (a [VmKeys] name, e.g. "ret") in a running QEMU VM. */
    fun sendKey(node: String, vmid: Int, key: String) {
        put("${guestPath(node, GuestType.QEMU, vmid)}/sendkey", VmKeys.params(key))
    }

    fun snapshots(node: String, type: GuestType, vmid: Int): List<Snapshot> =
        ProxmoxJson.snapshots(get("${guestPath(node, type, vmid)}/snapshot") as JSONArray)

    /** [withRam] (running VMs only) also saves the memory, so a rollback resumes the VM where it was. */
    fun createSnapshot(node: String, type: GuestType, vmid: Int, name: String, description: String, withRam: Boolean = false): String? {
        val params = mutableMapOf("snapname" to name)
        if (description.isNotBlank()) params["description"] = description
        if (withRam && type == GuestType.QEMU) params["vmstate"] = "1"
        return post("${guestPath(node, type, vmid)}/snapshot", params) as? String
    }

    fun rollbackSnapshot(node: String, type: GuestType, vmid: Int, name: String): String? =
        post("${guestPath(node, type, vmid)}/snapshot/${enc(name)}/rollback", emptyMap()) as? String

    fun deleteSnapshot(node: String, type: GuestType, vmid: Int, name: String): String? =
        request("DELETE", "${guestPath(node, type, vmid)}/snapshot/${enc(name)}", emptyMap()) as? String

    /** Starts a backup of one guest. Storage blank means the node's default backup storage. */
    fun backup(node: String, vmid: Int, storage: String?, mode: String = "snapshot"): String? {
        val params = mutableMapOf("vmid" to vmid.toString(), "mode" to mode, "compress" to "zstd")
        if (!storage.isNullOrBlank()) params["storage"] = storage
        return post("/nodes/${enc(node)}/vzdump", params) as? String
    }

    /** Backup archives on a storage, newest first. */
    fun backups(node: String, storage: String): List<StorageVolume> =
        ProxmoxJson.storageContent(get("/nodes/${enc(node)}/storage/${enc(storage)}/content?content=backup") as JSONArray)

    /** The guest config saved in a backup, as `key: value` lines. */
    fun backupConfig(node: String, volid: String): Map<String, String> =
        RestoreRequest.parseConfig(get("/nodes/${enc(node)}/vzdump/extractconfig?volume=${enc(volid)}") as? String ?: "")

    /** Restores a backup into a new or an existing guest. Returns the task UPID. */
    fun restoreGuest(node: String, request: RestoreRequest): String? =
        post("/nodes/${enc(node)}/${request.type.apiName}", request.toParams()) as? String

    // ---- container creation ----

    /** Next free VM/CT id in the cluster. */
    fun nextVmid(): Int = (get("/cluster/nextid") as? String)?.toIntOrNull() ?: 100

    /** Already-downloaded templates (content=vztmpl) across a storage, as volids. */
    fun templates(node: String, storage: String): List<TemplateFile> =
        ProxmoxJson.content(get("/nodes/${enc(node)}/storage/${enc(storage)}/content?content=vztmpl") as JSONArray)

    /** ISO images (content=iso) on a storage. */
    fun isoImages(node: String, storage: String): List<TemplateFile> =
        ProxmoxJson.content(get("/nodes/${enc(node)}/storage/${enc(storage)}/content?content=iso") as JSONArray)

    /** Creates a QEMU VM. Returns the task UPID. */
    fun createQemu(node: String, params: Map<String, String>): String? =
        post("/nodes/${enc(node)}/qemu", params) as? String

    /**
     * Changes config of an existing container or VM. VMs use the async POST, which the API recommends for
     * anything that allocates storage or hotplugs, and return its task UPID; containers only have a PUT.
     */
    fun updateConfig(node: String, type: GuestType, vmid: Int, params: Map<String, String>): String? {
        if (params.isEmpty()) return null
        val method = if (type == GuestType.QEMU) "POST" else "PUT"
        return request(method, "${guestPath(node, type, vmid)}/config", params) as? String
    }

    /** Grows a disk or mount point, e.g. `size=+8G`. Returns a task UPID when the server runs it as a task. */
    fun resizeDisk(node: String, type: GuestType, vmid: Int, disk: String, size: String): String? =
        request("PUT", "${guestPath(node, type, vmid)}/resize", mapOf("disk" to disk, "size" to size)) as? String

    /** Deletes a container or VM. Returns the task UPID. */
    fun deleteGuest(node: String, type: GuestType, vmid: Int, purge: Boolean, destroyUnreferenced: Boolean): String? {
        val params = mutableMapOf<String, String>()
        if (purge) params["purge"] = "1"
        if (destroyUnreferenced) params["destroy-unreferenced-disks"] = "1"
        return request("DELETE", guestPath(node, type, vmid), params) as? String
    }

    /** Network bridges on the node. */
    fun bridges(node: String): List<String> =
        ProxmoxJson.bridges(get("/nodes/${enc(node)}/network?type=bridge") as JSONArray)

    /** Downloadable appliance templates (the `pveam available` list). */
    fun availableTemplates(node: String): List<AplTemplate> =
        ProxmoxJson.aplinfo(get("/nodes/${enc(node)}/aplinfo") as JSONArray)

    /** Starts downloading a template into a storage. Returns the task UPID. */
    fun downloadTemplate(node: String, storage: String, template: String): String? =
        post("/nodes/${enc(node)}/aplinfo", mapOf("storage" to storage, "template" to template)) as? String

    /** Creates an LXC container. Returns the task UPID. */
    fun createLxc(node: String, params: Map<String, String>): String? =
        post("/nodes/${enc(node)}/lxc", params) as? String

    /** Clones a container or VM. Returns the task UPID. */
    fun cloneGuest(node: String, type: GuestType, vmid: Int, params: Map<String, String>): String? =
        post("${guestPath(node, type, vmid)}/clone", params) as? String

    fun tasks(node: String, limit: Int = 50): List<TaskSummary> =
        ProxmoxJson.tasks(get("/nodes/${enc(node)}/tasks?limit=$limit") as JSONArray)

    /** Recent tasks across the whole cluster (or the single node). */
    fun clusterTasks(): List<TaskSummary> = ProxmoxJson.tasks(get("/cluster/tasks") as JSONArray)

    fun taskStatus(node: String, upid: String): TaskStatus =
        ProxmoxJson.taskStatus(get("/nodes/${enc(node)}/tasks/${enc(upid)}/status") as JSONObject)

    fun taskLog(node: String, upid: String, limit: Int = 1000): List<String> =
        ProxmoxJson.taskLog(get("/nodes/${enc(node)}/tasks/${enc(upid)}/log?limit=$limit") as JSONArray)

    /** URL of the web UI, or of a noVNC / xterm.js console page inside it. */
    fun webUrl(mobile: Boolean): String = "$baseUrl/?mobile=${if (mobile) 1 else 0}"

    fun consoleUrl(node: String, type: GuestType?, vmid: Int?, name: String?): String {
        val params = when (type) {
            GuestType.QEMU -> "console=kvm&novnc=1&vmid=$vmid&vmname=${enc(name.orEmpty())}&node=${enc(node)}&resize=scale"
            GuestType.LXC -> "console=lxc&xtermjs=1&vmid=$vmid&vmname=${enc(name.orEmpty())}&node=${enc(node)}"
            null -> "console=shell&xtermjs=1&vmid=0&node=${enc(node)}&cmd="
        }
        return "$baseUrl/?$params"
    }

    /**
     * What pve-runners' API takes in its Authorization header: the same API token, or the login
     * ticket as Proxmox's own cookie (logging in first if needed). The service checks it with Proxmox.
     */
    fun runnersAuthorization(): String = when (credentials) {
        is ProxmoxCredentials.ApiToken -> "PVEAPIToken=${credentials.tokenId}=${credentials.secret}"
        is ProxmoxCredentials.Password -> {
            val t = ticket ?: run { login(); ticket!! }
            "PVEAuthCookie=${enc(t.ticket)}"
        }
    }

    /** Whether a 401 might be an expired ticket, so logging in again could help. */
    val canRenewLogin: Boolean get() = credentials is ProxmoxCredentials.Password

    fun get(path: String): Any? = request("GET", path, emptyMap())

    fun post(path: String, params: Map<String, String>): Any? = request("POST", path, params)

    fun put(path: String, params: Map<String, String>): Any? = request("PUT", path, params)

    fun delete(path: String): Any? = request("DELETE", path, emptyMap())

    private fun request(method: String, path: String, params: Map<String, String>): Any? {
        if (credentials is ProxmoxCredentials.Password && ticket == null) login()
        return try {
            rawRequest(method, path, params, authenticated = true)
        } catch (e: ProxmoxApiException) {
            // Tickets expire after two hours; log in again once.
            if (e.httpStatus == 401 && credentials is ProxmoxCredentials.Password) {
                login()
                rawRequest(method, path, params, authenticated = true)
            } else {
                throw e
            }
        }
    }

    /** A request body other than the usual form, sent with a fixed length so it is streamed, not buffered. */
    private class RequestBody(val contentType: String, val length: Long, val write: (OutputStream) -> Unit)

    private fun rawRequest(
        method: String,
        path: String,
        params: Map<String, String>,
        authenticated: Boolean,
        body: RequestBody? = null,
    ): Any? {
        val form = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val hasBody = method == "POST" || method == "PUT"
        val url = URL(
            "$baseUrl/api2/json$path" + if (!hasBody && form.isNotEmpty()) {
                (if (path.contains('?')) "&" else "?") + form
            } else {
                ""
            },
        )
        val conn = url.openConnection() as HttpsURLConnection
        conn.sslSocketFactory = tls.socketFactory
        conn.hostnameVerifier = tls.hostnameVerifier
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        // After an upload's last byte, pveproxy still copies the file before it answers.
        conn.readTimeout = if (body != null) 10 * 60_000 else 60_000
        conn.setRequestProperty("Accept", "application/json")
        if (authenticated) {
            when (credentials) {
                is ProxmoxCredentials.ApiToken ->
                    conn.setRequestProperty("Authorization", "PVEAPIToken=${credentials.tokenId}=${credentials.secret}")
                is ProxmoxCredentials.Password -> ticket?.let {
                    conn.setRequestProperty("Cookie", "PVEAuthCookie=${enc(it.ticket)}")
                    if (method != "GET") conn.setRequestProperty("CSRFPreventionToken", it.csrfToken)
                }
            }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.length)
                conn.setRequestProperty("Content-Type", body.contentType)
                conn.outputStream.use(body.write)
            } else if (hasBody) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            if (status !in 200..299) {
                throw ProxmoxApiException(status, errorMessage(status, conn.responseMessage, body))
            }
            if (body.isBlank()) return null
            return JSONObject(body).opt("data").takeUnless { it == JSONObject.NULL }
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(status: Int, reason: String?, body: String): String {
        val details = runCatching {
            val json = JSONObject(body)
            val errors = json.optJSONObject("errors")
            buildList {
                json.optString("message").takeIf { it.isNotBlank() }?.let { add(it.trim()) }
                errors?.keys()?.forEach { key -> add("$key: ${errors.optString(key).trim()}") }
            }.joinToString("; ")
        }.getOrNull().orEmpty()
        return when {
            status == 401 -> "Not authorized: ${details.ifBlank { reason.orEmpty() }}. Check username, realm and password."
            details.isNotBlank() -> "HTTP $status: $details"
            else -> "HTTP $status ${reason.orEmpty()}".trim()
        }
    }

    private fun storagePath(node: String, storage: String) = "/nodes/${enc(node)}/storage/${enc(storage)}"

    private companion object {
        const val PROGRESS_STEP = 4L * 1024 * 1024
    }

    private fun guestPath(node: String, type: GuestType, vmid: Int) = "/nodes/${enc(node)}/${type.apiName}/$vmid"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
