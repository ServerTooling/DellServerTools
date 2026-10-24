package com.lilayam.dellservertools.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.proxmox.AclEntry
import com.lilayam.dellservertools.core.proxmox.AclType
import com.lilayam.dellservertools.core.proxmox.ApiToken
import com.lilayam.dellservertools.core.proxmox.ExpiryDate
import com.lilayam.dellservertools.core.proxmox.NewApiToken
import com.lilayam.dellservertools.core.proxmox.PveGroup
import com.lilayam.dellservertools.core.proxmox.PvePool
import com.lilayam.dellservertools.core.proxmox.PveRole
import com.lilayam.dellservertools.core.proxmox.UserForm

private enum class PermTab(val label: String) {
    USERS("Users"), GROUPS("Groups"), TOKENS("API tokens"), ACL("Permissions"), ROLES("Roles"), POOLS("Pools"), TFA("Two-factor")
}

/** Datacenter > Permissions of the web UI, one tab at a time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    ForwardMessages(state.message, vms) { vm.clearMessage() }
    var selected by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }
    val reload: () -> Unit = { version++ }
    val busy = state.busy != null

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Permissions") },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
        )
        BusyBar(state.busy)
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
            PermTab.entries.forEachIndexed { i, tab ->
                Tab(
                    selected = selected == i,
                    onClick = { selected = i },
                    text = { Text(tab.label) },
                    modifier = Modifier.testTag("perm-tab-${tab.name.lowercase()}"),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            when (PermTab.entries[selected]) {
                PermTab.USERS -> UsersTab(vms, version, busy, reload)
                PermTab.GROUPS -> GroupsTab(vms, version, busy, reload)
                PermTab.TOKENS -> TokensTab(vms, version, busy, reload)
                PermTab.ACL -> AclTab(vms, version, busy, reload)
                PermTab.ROLES -> RolesTab(vms, version, busy, reload)
                PermTab.POOLS -> PoolsTab(vms, version, busy, reload)
                PermTab.TFA -> TfaTab(vms, version, busy, reload)
            }
        }
    }
}

@Composable
private fun SmallNote(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

/** Chips that toggle membership of each item in [selected]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiChips(items: List<Pair<String, String>>, selected: Collection<String>, tag: String, onToggle: (String, Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (value, label) ->
            val on = value in selected
            FilterChip(selected = on, onClick = { onToggle(value, !on) }, label = { Text(label) }, modifier = Modifier.testTag("$tag-$value"))
        }
    }
}

// ---------------------------------------------------------------- users

@Composable
private fun UsersTab(vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (users, error) = rememberLoaded(vms, version) { it.users() }
    val (groups, _) = rememberLoaded(vms, version) { it.groups() }
    val (realms, _) = rememberLoaded(vms, version) { it.realms() }
    var editing by remember { mutableStateOf<UserForm?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedButton(onClick = { editing = UserForm(userid = "@pve") }, enabled = !busy, modifier = Modifier.testTag("user-add")) {
                Text("Add user")
            }
        }
        loading(users, error)
        items(users.orEmpty(), key = { "user-${it.userid}" }) { u ->
            Card(Modifier.fillMaxWidth().clickable { editing = UserForm.from(u) }.testTag("user-${u.userid}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (u.enabled) Green else Amber)
                        Text("  ${u.userid}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (u.tokens.isNotEmpty()) Text("${u.tokens.size} token(s)", style = MaterialTheme.typography.labelSmall)
                    }
                    listOfNotNull(
                        "${u.firstname} ${u.lastname}".trim().ifEmpty { null },
                        u.email.ifEmpty { null },
                        u.groups.ifEmpty { null }?.joinToString(prefix = "groups "),
                        ExpiryDate.format(u.expire).ifEmpty { null }?.let { "expires $it" },
                        if (!u.enabled) "disabled" else null,
                    ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { SmallNote(it) }
                    if (u.comment.isNotBlank()) Text(u.comment, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    editing?.let { form ->
        UserDialog(
            form,
            groups.orEmpty().map { it.groupid },
            realms.orEmpty(),
            onDismiss = { editing = null },
            onDelete = { deleting = form.userid; editing = null },
            onPassword = { password = form.userid; editing = null },
        ) { saved ->
            vm.change(if (saved.isNew) "Add ${saved.userid}" else "Update ${saved.userid}", reload) { c ->
                if (saved.isNew) c.createUser(saved) else c.updateUser(saved)
            }
            editing = null
        }
    }
    deleting?.let { userid ->
        ConfirmDialog(
            title = "Remove $userid?",
            text = "Its API tokens and permissions go with it.",
            confirmLabel = "Remove",
            onConfirm = { vm.change("Remove $userid", reload) { it.deleteUser(userid) } },
            onDismiss = { deleting = null },
        )
    }
    password?.let { userid ->
        PasswordDialog(userid, onDismiss = { password = null }) { new, current ->
            vm.change("Change password of $userid") { it.setPassword(userid, new, current) }
            password = null
        }
    }
}

@Composable
private fun UserDialog(
    initial: UserForm,
    groups: List<String>,
    realms: List<String>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onPassword: () -> Unit,
    onSave: (UserForm) -> Unit,
) {
    var f by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(initial.userid.substringBefore('@')) }
    var realm by remember { mutableStateOf(initial.userid.substringAfterLast('@').ifEmpty { "pve" }) }
    var expiry by remember { mutableStateOf(ExpiryDate.format(initial.expire)) }
    val expire = ExpiryDate.parse(expiry)
    val form = if (f.isNew) f.copy(userid = "$name@$realm") else f
    val problem = form.validate() ?: if (expire == null) "Expiry: a date like 2027-12-31, or empty for never." else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (f.isNew) "Add user" else f.userid) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (f.isNew) {
                    RuleField("User name", name, "user-name") { name = it }
                    Text("Realm", style = MaterialTheme.typography.labelMedium)
                    ChipRow(realms.ifEmpty { listOf("pam", "pve") }.map { it to it }, realm, "user-realm") { realm = it }
                    if (realm == "pve") {
                        PasswordField("Password", f.password, "user-password") { f = f.copy(password = it) }
                    } else {
                        SmallNote("The $realm realm keeps its own passwords (PAM users need a Linux account on the host).")
                    }
                }
                CheckRow("Enabled", f.enabled, Modifier.testTag("user-enabled")) { f = f.copy(enabled = it) }
                RuleField("Expires (YYYY-MM-DD, empty = never)", expiry, "user-expire") { expiry = it }
                RuleField("First name", f.firstname, "user-first") { f = f.copy(firstname = it) }
                RuleField("Last name", f.lastname, "user-last") { f = f.copy(lastname = it) }
                RuleField("E-mail", f.email, "user-email") { f = f.copy(email = it) }
                RuleField("Comment", f.comment, "user-comment") { f = f.copy(comment = it) }
                if (groups.isNotEmpty()) {
                    Text("Groups", style = MaterialTheme.typography.labelMedium)
                    MultiChips(groups.map { it to it }, f.groups, "user-group") { g, on ->
                        f = f.copy(groups = if (on) f.groups + g else f.groups - g)
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!f.isNew) {
                    if (f.userid.endsWith("@pve")) {
                        TextButton(onClick = onPassword, modifier = Modifier.testTag("user-change-password")) { Text("Change password") }
                    }
                    if (f.userid != "root@pam") {
                        TextButton(onClick = onDelete, modifier = Modifier.testTag("user-delete")) {
                            Text("Remove user", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(form.copy(expire = expire ?: 0)) },
                enabled = problem == null,
                modifier = Modifier.testTag("user-save"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun PasswordDialog(userid: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    val problem = when {
        new.length < 8 -> "At least 8 characters."
        new != again -> "The two don't match."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password of $userid") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField("New password", new, "pw-new") { new = it }
                PasswordField("Repeat", again, "pw-again") { again = it }
                PasswordField("Your own current password", current, "pw-current") { current = it }
                SmallNote("Newer Proxmox versions ask for the password of the user signed in to the app.")
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(new, current) }, enabled = problem == null, modifier = Modifier.testTag("pw-save")) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- groups

@Composable
private fun GroupsTab(vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (groups, error) = rememberLoaded(vms, version) { it.groups() }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PveGroup?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedButton(onClick = { adding = true }, enabled = !busy, modifier = Modifier.testTag("group-add")) { Text("Add group") }
        }
        loading(groups, error)
        items(groups.orEmpty(), key = { "group-${it.groupid}" }) { g ->
            Card(Modifier.fillMaxWidth().testTag("group-${g.groupid}")) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(g.groupid, fontWeight = FontWeight.SemiBold)
                        SmallNote(g.users.joinToString().ifEmpty { "No members" })
                        if (g.comment.isNotBlank()) Text(g.comment, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { deleting = g }, enabled = !busy, modifier = Modifier.testTag("group-delete-${g.groupid}")) {
                        Text("Remove")
                    }
                }
            }
        }
        item { SmallNote("Add users to a group from the user's own form.") }
    }
    if (adding) {
        NameDialog("Add group", PveGroup.ID, "group", onDismiss = { adding = false }) { id, comment ->
            vm.change("Add group $id", reload) { it.createGroup(id, comment) }
            adding = false
        }
    }
    deleting?.let { g ->
        ConfirmDialog(
            title = "Remove group ${g.groupid}?",
            text = "Its members stay; the permissions given to the group go.",
            confirmLabel = "Remove",
            onConfirm = { vm.change("Remove group ${g.groupid}", reload) { it.deleteGroup(g.groupid) } },
            onDismiss = { deleting = null },
        )
    }
}

/** An ID plus an optional comment, for groups and pools. */
@Composable
private fun NameDialog(title: String, pattern: Regex, tag: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    val valid = pattern.matches(id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleField("ID", id, "$tag-id") { id = it }
                RuleField("Comment", comment, "$tag-comment") { comment = it }
                if (id.isNotEmpty() && !valid) {
                    Text("Letters, digits, '-', '_' and '.'.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(id, comment) }, enabled = valid, modifier = Modifier.testTag("$tag-save")) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- API tokens

@Composable
private fun TokensTab(vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (users, error) = rememberLoaded(vms, version) { it.users() }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ApiToken?>(null) }
    var created by remember { mutableStateOf<NewApiToken?>(null) }
    val tokens = users.orEmpty().flatMap { it.tokens }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedButton(onClick = { adding = true }, enabled = !busy && users != null, modifier = Modifier.testTag("token-add")) {
                Text("Add API token")
            }
        }
        loading(users, error)
        if (users != null && tokens.isEmpty()) item { SmallNote("No API tokens.") }
        items(tokens, key = { "token-${it.fullId}" }) { t ->
            Card(Modifier.fillMaxWidth().testTag("token-${t.fullId}")) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(t.fullId, fontWeight = FontWeight.SemiBold)
                        SmallNote(
                            listOfNotNull(
                                if (t.privsep) "own permissions only" else "all of the user's permissions",
                                ExpiryDate.format(t.expire).ifEmpty { null }?.let { "expires $it" },
                                t.comment.ifEmpty { null },
                            ).joinToString(" · "),
                        )
                    }
                    TextButton(onClick = { deleting = t }, enabled = !busy, modifier = Modifier.testTag("token-delete-${t.fullId}")) {
                        Text("Remove")
                    }
                }
            }
        }
    }
    if (adding) {
        TokenDialog(users.orEmpty().map { it.userid }, onDismiss = { adding = false }) { userid, tokenid, privsep, expire, comment ->
            // The answer arrives off the main thread; show it once the change is done.
            var answer: NewApiToken? = null
            vm.change("Add token $userid!$tokenid", { created = answer; reload() }) { c ->
                answer = c.createToken(userid, tokenid, privsep, expire, comment)
            }
            adding = false
        }
    }
    deleting?.let { t ->
        ConfirmDialog(
            title = "Remove ${t.fullId}?",
            text = "Anything using this token loses access at once.",
            confirmLabel = "Remove",
            onConfirm = { vm.change("Remove token ${t.fullId}", reload) { it.deleteToken(t.userid, t.tokenid) } },
            onDismiss = { deleting = null },
        )
    }
    created?.let { t -> TokenSecretDialog(t) { created = null } }
}

@Composable
private fun TokenDialog(users: List<String>, onDismiss: () -> Unit, onCreate: (String, String, Boolean, Long, String) -> Unit) {
    var user by remember { mutableStateOf(users.firstOrNull { it == "root@pam" } ?: users.firstOrNull().orEmpty()) }
    var id by remember { mutableStateOf("") }
    var privsep by remember { mutableStateOf(true) }
    var expiry by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    val expire = ExpiryDate.parse(expiry)
    val problem = when {
        !ApiToken.ID.matches(id) -> "Token ID: a letter, then letters, digits, '-', '_' or '.'."
        expire == null -> "Expiry: a date like 2027-12-31, or empty for never."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add API token") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("User", style = MaterialTheme.typography.labelMedium)
                ChipRow(users.map { it to it }, user, "token-user") { user = it }
                RuleField("Token ID", id, "token-id") { id = it }
                CheckRow("Separate permissions (give the token its own)", privsep, Modifier.testTag("token-privsep")) { privsep = it }
                RuleField("Expires (YYYY-MM-DD, empty = never)", expiry, "token-expire") { expiry = it }
                RuleField("Comment", comment, "token-comment") { comment = it }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(user, id, privsep, expire ?: 0, comment) },
                enabled = problem == null && user.isNotEmpty(),
                modifier = Modifier.testTag("token-save"),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TokenSecretDialog(token: NewApiToken, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Token ${token.fullId}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Copy the secret now: Proxmox never shows it again.", color = MaterialTheme.colorScheme.error)
                Text(token.secret, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("token-secret"))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("API token secret", token.secret))
                },
                modifier = Modifier.testTag("token-copy"),
            ) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("token-done")) { Text("Done") } },
    )
}

// ---------------------------------------------------------------- permissions (ACL)

@Composable
private fun AclTab(vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val (acl, error) = rememberLoaded(vms, version) { it.acl() }
    val (users, _) = rememberLoaded(vms, version) { it.users() }
    val (groups, _) = rememberLoaded(vms, version) { it.groups() }
    val (roles, _) = rememberLoaded(vms, version) { it.roles() }
    val (pools, _) = rememberLoaded(vms, version) { it.pools() }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AclEntry?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedButton(onClick = { adding = true }, enabled = !busy && roles != null, modifier = Modifier.testTag("acl-add")) {
                Text("Add permission")
            }
        }
        loading(acl, error)
        if (acl != null && acl.isEmpty()) item { SmallNote("No permissions given yet; root@pam can always do everything.") }
        itemsIndexed(acl.orEmpty(), key = { _, e -> "acl-${e.path}-${e.type}-${e.ugid}-${e.role}" }) { i, e ->
            Card(Modifier.fillMaxWidth().testTag("acl-$i")) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text("${e.path}  ·  ${e.role}", fontWeight = FontWeight.SemiBold)
                        SmallNote("${e.type.label} ${e.ugid}" + if (e.propagate) " · and below" else "")
                    }
                    TextButton(onClick = { removing = e }, enabled = !busy, modifier = Modifier.testTag("acl-remove-$i")) { Text("Remove") }
                }
            }
        }
    }
    if (adding) {
        val resources = state.resources
        AclDialog(
            paths = AclEntry.suggestedPaths(
                resources.filter { it.type == "node" }.map { it.node }.distinct(),
                resources.mapNotNull { r -> r.vmid.takeIf { r.guestType != null } },
                resources.mapNotNull { it.storage }.distinct().sorted(),
                pools.orEmpty().map { it.poolid },
            ),
            who = mapOf(
                AclType.USER to users.orEmpty().map { it.userid },
                AclType.GROUP to groups.orEmpty().map { it.groupid },
                AclType.TOKEN to users.orEmpty().flatMap { u -> u.tokens.map { it.fullId } },
            ),
            roles = roles.orEmpty().map { it.roleid },
            onDismiss = { adding = false },
        ) { entry ->
            vm.change("Give ${entry.ugid} ${entry.role} on ${entry.path}", reload) { it.addAcl(entry) }
            adding = false
        }
    }
    removing?.let { e ->
        ConfirmDialog(
            title = "Remove this permission?",
            text = "${e.type.label} ${e.ugid} loses ${e.role} on ${e.path}.",
            confirmLabel = "Remove",
            onConfirm = { vm.change("Remove ${e.role} of ${e.ugid} on ${e.path}", reload) { it.removeAcl(e) } },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun AclDialog(
    paths: List<String>,
    who: Map<AclType, List<String>>,
    roles: List<String>,
    onDismiss: () -> Unit,
    onSave: (AclEntry) -> Unit,
) {
    var e by remember { mutableStateOf(AclEntry("/", AclType.USER, "", "PVEAuditor")) }
    val problem = e.validate()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add permission") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleField("Path", e.path, "acl-path") { e = e.copy(path = it) }
                Dropdown("Or pick a path", paths.map { it to it }, e.path) { e = e.copy(path = it) }
                Text("For", style = MaterialTheme.typography.labelMedium)
                ChipRow(AclType.entries.map { it.param to it.label }, e.type.param, "acl-type") { p ->
                    e = e.copy(type = AclType.entries.first { it.param == p }, ugid = "")
                }
                val choices = who[e.type].orEmpty()
                if (choices.isEmpty()) SmallNote("None yet.") else ChipRow(choices.map { it to it }, e.ugid, "acl-who") { e = e.copy(ugid = it) }
                Text("Role", style = MaterialTheme.typography.labelMedium)
                ChipRow(roles.map { it to it }, e.role, "acl-role") { e = e.copy(role = it) }
                CheckRow("Also below this path", e.propagate, Modifier.testTag("acl-propagate")) { e = e.copy(propagate = it) }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(e) }, enabled = problem == null, modifier = Modifier.testTag("acl-save")) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- roles

@Composable
private fun RolesTab(vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (roles, error) = rememberLoaded(vms, version) { it.roles() }
    var editing by remember { mutableStateOf<PveRole?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val all = PveRole.allPrivileges(roles.orEmpty())
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedButton(
                onClick = { editing = PveRole("", emptyList(), special = false) },
                enabled = !busy && all.isNotEmpty(),
                modifier = Modifier.testTag("role-add"),
            ) { Text("Add role") }
        }
        loading(roles, error)
        items(roles.orEmpty(), key = { "role-${it.roleid}" }) { r ->
            Card(Modifier.fillMaxWidth().clickable(enabled = !r.special) { editing = r }.testTag("role-${r.roleid}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.roleid, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(if (r.special) "built-in" else "custom", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(r.privs.joinToString(", ").ifEmpty { "no privileges" }, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    editing?.let { role ->
        RoleDialog(
            role,
            all,
            onDismiss = { editing = null },
            onDelete = { deleting = role.roleid; editing = null },
        ) { id, privs ->
            vm.change(if (role.roleid.isEmpty()) "Add role $id" else "Update role $id", reload) { c ->
                if (role.roleid.isEmpty()) c.createRole(id, privs) else c.updateRole(id, privs)
            }
            editing = null
        }
    }
    deleting?.let { id ->
        ConfirmDialog(
            title = "Remove role $id?",
            text = "Permissions that use it are removed too.",
            confirmLabel = "Remove",
            onConfirm = { vm.change("Remove role $id", reload) { it.deleteRole(id) } },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RoleDialog(initial: PveRole, all: List<String>, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (String, Set<String>) -> Unit) {
    val isNew = initial.roleid.isEmpty()
    var id by remember { mutableStateOf(initial.roleid) }
    var privs by remember { mutableStateOf(initial.privs.toSet()) }
    val valid = PveRole.ID.matches(id) && privs.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add role" else "Role ${initial.roleid}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isNew) RuleField("Role ID", id, "role-id") { id = it }
                Text("Privileges", style = MaterialTheme.typography.labelMedium)
                MultiChips(all.map { it to it }, privs, "role-priv") { p, on -> privs = if (on) privs + p else privs - p }
                if (!isNew) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("role-delete")) {
                        Text("Remove role", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(id, privs) }, enabled = valid, modifier = Modifier.testTag("role-save")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- pools

@Composable
private fun PoolsTab(vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    val (pools, error) = rememberLoaded(vms, version) { it.pools() }
    var adding by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedButton(onClick = { adding = true }, enabled = !busy, modifier = Modifier.testTag("pool-add")) { Text("Add pool") }
        }
        loading(pools, error)
        if (pools != null && pools.isEmpty()) item { SmallNote("No pools. A pool groups guests and storages to give permissions on them together.") }
        items(pools.orEmpty(), key = { "pool-${it.poolid}" }) { p ->
            Card(Modifier.fillMaxWidth().clickable { opened = p.poolid }.testTag("pool-${p.poolid}")) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.poolid, fontWeight = FontWeight.SemiBold)
                    if (p.comment.isNotBlank()) SmallNote(p.comment)
                }
            }
        }
    }
    if (adding) {
        NameDialog("Add pool", PveGroup.ID, "pool", onDismiss = { adding = false }) { id, comment ->
            vm.change("Add pool $id", reload) { it.createPool(id, comment) }
            adding = false
        }
    }
    opened?.let { id ->
        PoolDialog(
            id,
            vms,
            guests = state.resources.filter { it.guestType != null && it.vmid != null }.sortedBy { it.vmid }
                .map { it.vmid!!.toString() to "${it.vmid} ${it.name}" },
            storages = state.resources.mapNotNull { it.storage }.distinct().sorted(),
            onDismiss = { opened = null },
            onDelete = { deleting = id; opened = null },
        ) { before, guests, storages ->
            vm.change("Update pool $id", reload) { c ->
                val addGuests = (guests - before.guests.toSet()).toList()
                val addStorages = (storages - before.storages.toSet()).toList()
                val dropGuests = (before.guests.toSet() - guests).toList()
                val dropStorages = (before.storages.toSet() - storages).toList()
                if (addGuests.isNotEmpty() || addStorages.isNotEmpty()) c.setPoolMembers(id, addGuests, addStorages, remove = false)
                if (dropGuests.isNotEmpty() || dropStorages.isNotEmpty()) c.setPoolMembers(id, dropGuests, dropStorages, remove = true)
            }
            opened = null
        }
    }
    deleting?.let { id ->
        ConfirmDialog(
            title = "Remove pool $id?",
            text = "Only an empty pool can be removed; the guests and storages themselves stay.",
            confirmLabel = "Remove",
            onConfirm = { vm.change("Remove pool $id", reload) { it.deletePool(id) } },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PoolDialog(
    poolid: String,
    vms: AppViewModels,
    guests: List<Pair<String, String>>,
    storages: List<String>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (PvePool, Set<Int>, Set<String>) -> Unit,
) {
    var pool by remember { mutableStateOf<PvePool?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var chosenGuests by remember { mutableStateOf(emptySet<String>()) }
    var chosenStorages by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(poolid) {
        vms.proxmox.fetch { it.pool(poolid) }.onSuccess { p ->
            pool = p
            chosenGuests = p.guests.map { it.toString() }.toSet()
            chosenStorages = p.storages.toSet()
        }.onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pool $poolid") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    pool != null -> {
                        Text("Guests", style = MaterialTheme.typography.labelMedium)
                        MultiChips(guests, chosenGuests, "pool-guest") { g, on -> chosenGuests = if (on) chosenGuests + g else chosenGuests - g }
                        Text("Storages", style = MaterialTheme.typography.labelMedium)
                        MultiChips(storages.map { it to it }, chosenStorages, "pool-storage") { s, on ->
                            chosenStorages = if (on) chosenStorages + s else chosenStorages - s
                        }
                        TextButton(onClick = onDelete, modifier = Modifier.testTag("pool-delete")) {
                            Text("Remove pool", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    else -> Text("Loading…")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { pool?.let { onSave(it, chosenGuests.map(String::toInt).toSet(), chosenStorages) } },
                enabled = pool != null,
                modifier = Modifier.testTag("pool-save"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- two-factor

@Composable
private fun TfaTab(vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (entries, error) = rememberLoaded(vms, version) { it.tfa() }
    var deleting by remember { mutableStateOf<Pair<String, String>?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SmallNote(
                "Second factors of each user. New ones (TOTP, WebAuthn, recovery keys) are set up by the user in the web UI, " +
                    "since that needs their authenticator; here you can remove one, e.g. after a lost phone.",
            )
        }
        loading(entries, error)
        if (entries != null && entries.isEmpty()) item { SmallNote("No user has a second factor.") }
        items(entries.orEmpty(), key = { "tfa-${it.userid}-${it.id}" }) { t ->
            Card(Modifier.fillMaxWidth().testTag("tfa-${t.userid}-${t.id}")) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text("${t.userid} · ${t.type}", fontWeight = FontWeight.SemiBold)
                        SmallNote(
                            listOfNotNull(
                                t.description.ifEmpty { null },
                                t.created.takeIf { it > 0 }?.let { "added ${formatStorageTime(it)}" },
                                if (!t.enabled) "disabled" else null,
                            ).joinToString(" · "),
                        )
                    }
                    TextButton(
                        onClick = { deleting = t.userid to t.id },
                        enabled = !busy,
                        modifier = Modifier.testTag("tfa-delete-${t.userid}-${t.id}"),
                    ) { Text("Remove") }
                }
            }
        }
    }
    deleting?.let { (userid, id) ->
        ConfirmDialog(
            title = "Remove this second factor?",
            text = "$userid can then sign in without it (or with their other factors).",
            confirmLabel = "Remove",
            onConfirm = { vm.change("Remove second factor of $userid", reload) { it.deleteTfa(userid, id) } },
            onDismiss = { deleting = null },
        )
    }
}
