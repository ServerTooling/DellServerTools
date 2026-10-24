package com.lilayam.dellservertools.ui

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lilayam.dellservertools.core.proxmox.FirewallAlias
import com.lilayam.dellservertools.core.proxmox.FirewallNames
import com.lilayam.dellservertools.core.proxmox.FirewallRule
import com.lilayam.dellservertools.core.proxmox.FirewallRuleForm
import com.lilayam.dellservertools.core.proxmox.FirewallScope
import com.lilayam.dellservertools.core.proxmox.IpSet
import com.lilayam.dellservertools.core.proxmox.IpSetEntry
import com.lilayam.dellservertools.core.proxmox.SecurityGroup

private enum class FirewallTab(val label: String) { RULES("Rules"), OPTIONS("Options"), ALIASES("Aliases"), IPSETS("IP sets"), GROUPS("Security groups"), LOG("Log") }

/** The web UI's Firewall panel of one level (datacenter, node, guest or security group), one tab at a time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(screen: Screen.Firewall, vms: AppViewModels) {
    val vm = vms.proxmox
    val state by vm.state.collectAsStateWithLifecycle()
    ForwardMessages(state.message, vms) { vm.clearMessage() }
    val scope = screen.scope
    val tabs = FirewallTab.entries.filter {
        when (it) {
            FirewallTab.RULES -> true
            FirewallTab.OPTIONS -> scope.hasOptions
            FirewallTab.ALIASES -> scope.hasAliases
            FirewallTab.IPSETS -> scope.hasIpSets
            FirewallTab.GROUPS -> scope.isCluster
            FirewallTab.LOG -> scope.hasLog
        }
    }
    var selected by remember { mutableIntStateOf(0) }
    // Bumped after every change, so the open tab loads again.
    var version by remember { mutableIntStateOf(0) }
    val reload: () -> Unit = { version++ }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(scope.title) },
            navigationIcon = { IconButton(onClick = { vms.servers.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
        )
        BusyBar(state.busy)
        if (tabs.size > 1) {
            ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, tab ->
                    Tab(
                        selected = selected == i,
                        onClick = { selected = i },
                        text = { Text(tab.label) },
                        modifier = Modifier.testTag("fw-tab-${tab.name.lowercase()}"),
                    )
                }
            }
        }
        val busy = state.busy != null
        Column(Modifier.weight(1f)) {
            when (tabs[selected]) {
                FirewallTab.RULES -> RulesTab(scope, vms, version, busy, reload)
                FirewallTab.OPTIONS -> OptionsTab(scope, vms, version, busy, reload)
                FirewallTab.ALIASES -> AliasesTab(scope, vms, version, busy, reload)
                FirewallTab.IPSETS -> IpSetsTab(scope, vms, version, busy, reload)
                FirewallTab.GROUPS -> GroupsTab(screen.profileId, vms, version, busy, reload)
                FirewallTab.LOG -> LogTab(scope, vms, version)
            }
        }
    }
}

/** Loads [block] whenever [version] changes; null while loading, with the error text on failure. */
@Composable
private fun <T> rememberLoaded(vms: AppViewModels, version: Int, block: (com.lilayam.dellservertools.core.proxmox.ProxmoxClient) -> T): Pair<T?, String?> {
    var value by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) {
        vms.proxmox.fetch(block).onSuccess { value = it; error = null }.onFailure { error = it.message }
    }
    return value to error
}

private fun LazyListScope.loading(value: Any?, error: String?) {
    if (value == null) item { Text(error ?: "Loading…") }
}

// ---------------------------------------------------------------- rules

@Composable
private fun RulesTab(scope: FirewallScope, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (rules, error) = rememberLoaded(vms, version) { it.firewallRules(scope) }
    // For the rule dialog, loaded here rather than in it: a dialog's own LaunchedEffect never delivered its result
    // under Robolectric. A security group can't reference another group.
    val (groups) = rememberLoaded(vms, version) { if (scope.group == null) it.securityGroups() else emptyList() }
    val (macros) = rememberLoaded(vms, 0) { it.firewallMacros() }
    var editing by remember { mutableStateOf<FirewallRuleForm?>(null) }
    var deleting by remember { mutableStateOf<FirewallRule?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loading(rules, error)
        if (rules?.isEmpty() == true) item { Text("No rules. Traffic follows the policy in Options.") }
        val list = rules.orEmpty()
        items(list, key = { "rule-${it.pos}" }) { rule ->
            Card(Modifier.fillMaxWidth().clickable { editing = FirewallRuleForm.fromRule(rule) }.testTag("fw-rule-${rule.pos}")) {
                Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${rule.pos}  ${rule.type.uppercase()}  ${rule.action}",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            color = if (rule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on ->
                                vm.change("${if (on) "Enable" else "Disable"} rule ${rule.pos}", reload) { c ->
                                    c.updateFirewallRule(scope, rule.pos, mapOf("enable" to if (on) "1" else "0"))
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("fw-rule-enable-${rule.pos}"),
                        )
                    }
                    Text(if (rule.type == "group") "Security group" else rule.summary, style = MaterialTheme.typography.bodySmall)
                    if (rule.comment.isNotBlank()) Text(rule.comment, style = MaterialTheme.typography.labelSmall)
                    Row {
                        IconButton(
                            onClick = { vm.change("Move rule ${rule.pos} up", reload) { it.moveFirewallRule(scope, rule.pos, rule.pos - 1) } },
                            enabled = !busy && rule.pos > 0,
                            modifier = Modifier.testTag("fw-rule-up-${rule.pos}"),
                        ) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                        IconButton(
                            // Proxmox moves a rule to before position `moveto`, so one down is pos + 2.
                            onClick = { vm.change("Move rule ${rule.pos} down", reload) { it.moveFirewallRule(scope, rule.pos, rule.pos + 2) } },
                            enabled = !busy && rule.pos < list.size - 1,
                            modifier = Modifier.testTag("fw-rule-down-${rule.pos}"),
                        ) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                        IconButton(
                            onClick = { deleting = rule },
                            enabled = !busy,
                            modifier = Modifier.testTag("fw-rule-delete-${rule.pos}"),
                        ) { Icon(Icons.Filled.Delete, "Delete rule", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { editing = FirewallRuleForm() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("fw-rule-add"),
            ) { Text("Add rule") }
        }
    }
    editing?.let { form ->
        RuleDialog(form, scope, groups.orEmpty(), macros.orEmpty(), onDismiss = { editing = null }) { saved ->
            val current = saved.current
            if (current == null) {
                vm.change("Add firewall rule", reload) { it.addFirewallRule(scope, saved) }
            } else {
                vm.change("Update rule ${current.pos}", reload) { it.updateFirewallRule(scope, current.pos, saved.updateParams()) }
            }
            editing = null
        }
    }
    deleting?.let { rule ->
        ConfirmDialog(
            title = "Delete rule ${rule.pos}?",
            text = "${rule.type.uppercase()} ${rule.action} ${rule.summary}",
            confirmLabel = "Delete",
            onConfirm = { vm.change("Delete rule ${rule.pos}", reload) { it.deleteFirewallRule(scope, rule.pos) } },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(
    initial: FirewallRuleForm,
    scope: FirewallScope,
    groups: List<SecurityGroup>,
    macros: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (FirewallRuleForm) -> Unit,
) {
    var f by remember { mutableStateOf(initial) }
    val problem = f.validate()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.current == null) "Add rule" else "Rule ${initial.current.pos}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow((if (scope.group == null) FirewallRuleForm.TYPES else listOf("in", "out")).map { it to it.uppercase() }, f.type, "fw-type") {
                    f = f.copy(type = it, action = if (it == "group") groups.firstOrNull()?.name.orEmpty() else "ACCEPT")
                }
                if (f.isGroup) {
                    if (groups.isEmpty()) Text("No security groups yet. Add one on the datacenter firewall.")
                    ChipRow(groups.map { it.name to it.name }, f.action, "fw-group") { f = f.copy(action = it) }
                } else {
                    ChipRow(FirewallRuleForm.ACTIONS.map { it to it }, f.action, "fw-action") { f = f.copy(action = it) }
                    Dropdown("Macro (optional)", listOf("" to "None") + macros.map { (m, d) -> m to "$m · $d" }, f.macro) {
                        f = f.copy(macro = it)
                    }
                    if (f.macro.isEmpty()) {
                        Text("Protocol", style = MaterialTheme.typography.labelLarge)
                        ChipRow(FirewallRuleForm.PROTOCOLS.map { it to it.ifEmpty { "any" } }, f.proto, "fw-proto") { f = f.copy(proto = it) }
                        RuleField("Destination port", f.dport, "fw-dport") { f = f.copy(dport = it) }
                        RuleField("Source port", f.sport, "fw-sport") { f = f.copy(sport = it) }
                    }
                    RuleField("Source (IP, CIDR, alias, +ipset)", f.source, "fw-source") { f = f.copy(source = it) }
                    RuleField("Destination", f.dest, "fw-dest") { f = f.copy(dest = it) }
                    Dropdown("Log level", listOf("" to "Default", "nolog" to "nolog", "info" to "info", "warning" to "warning", "err" to "err"), f.log) {
                        f = f.copy(log = it)
                    }
                }
                RuleField("Interface (optional, e.g. net0)", f.iface, "fw-iface") { f = f.copy(iface = it) }
                RuleField("Comment", f.comment, "fw-comment") { f = f.copy(comment = it) }
                CheckRow("Enabled", f.enabled, Modifier.testTag("fw-enabled")) { f = f.copy(enabled = it) }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(f) }, enabled = problem == null, modifier = Modifier.testTag("fw-rule-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(items: List<Pair<String, String>>, selected: String, tag: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.testTag("$tag-${value.ifEmpty { "any" }}"),
            )
        }
    }
}

@Composable
private fun RuleField(label: String, value: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

// ---------------------------------------------------------------- options

@Composable
private fun OptionsTab(scope: FirewallScope, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val (options, error) = rememberLoaded(vms, version) { it.firewallOptions(scope) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loading(options, error)
        val current = options ?: return@LazyColumn
        if (scope.isCluster && current["enable"] != "1") {
            item {
                Text(
                    "The firewall is off for the whole datacenter: rules on nodes and guests don't apply until it is on.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(scope.options, key = { it.key }) { option ->
            val value = option.value(current)
            fun set(v: String) = vms.proxmox.change("Set ${option.label}", reload) { it.setFirewallOptions(scope, option.params(v)) }
            if (option.isSwitch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(option.label, modifier = Modifier.weight(1f))
                    Switch(
                        checked = value == "1",
                        onCheckedChange = { set(if (it) "1" else "0") },
                        enabled = !busy,
                        modifier = Modifier.testTag("fw-opt-${option.key}"),
                    )
                }
            } else {
                Dropdown(option.label, option.choices.map { it to it }, value) { if (it != value) set(it) }
            }
        }
    }
}

// ---------------------------------------------------------------- aliases

@Composable
private fun AliasesTab(scope: FirewallScope, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (aliases, error) = rememberLoaded(vms, version) { it.firewallAliases(scope) }
    var editing by remember { mutableStateOf<FirewallAlias?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FirewallAlias?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loading(aliases, error)
        if (aliases?.isEmpty() == true) item { Text("No aliases. An alias names an address for use in rules.") }
        items(aliases.orEmpty(), key = { "alias-${it.name}" }) { alias ->
            Card(Modifier.fillMaxWidth().clickable { isNew = false; editing = alias }.testTag("fw-alias-${alias.name}")) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(alias.name, fontWeight = FontWeight.SemiBold)
                        Text(alias.cidr, style = MaterialTheme.typography.bodySmall)
                        if (alias.comment.isNotBlank()) Text(alias.comment, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { deleting = alias }, enabled = !busy) {
                        Icon(Icons.Filled.Delete, "Delete alias", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { isNew = true; editing = FirewallAlias("", "", "") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("fw-alias-add"),
            ) { Text("Add alias") }
        }
    }
    editing?.let { alias ->
        var name by remember(alias) { mutableStateOf(alias.name) }
        var cidr by remember(alias) { mutableStateOf(alias.cidr) }
        var comment by remember(alias) { mutableStateOf(alias.comment) }
        val problem = (if (isNew) FirewallNames.nameError(name) else null) ?: FirewallNames.cidrError(cidr)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) "Add alias" else "Alias ${alias.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isNew) RuleField("Name", name, "fw-alias-name") { name = it }
                    RuleField("IP or CIDR", cidr, "fw-alias-cidr") { cidr = it }
                    RuleField("Comment", comment, "fw-alias-comment") { comment = it }
                    if (name.isNotEmpty() || cidr.isNotEmpty()) problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val saved = FirewallAlias(name, cidr, comment)
                        if (isNew) {
                            vm.change("Add alias $name", reload) { it.addFirewallAlias(scope, saved) }
                        } else {
                            vm.change("Update alias $name", reload) { it.updateFirewallAlias(scope, saved) }
                        }
                        editing = null
                    },
                    enabled = problem == null,
                    modifier = Modifier.testTag("fw-alias-save"),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { alias ->
        ConfirmDialog(
            title = "Delete alias ${alias.name}?",
            text = "Rules that use it must be changed first, or the server refuses.",
            confirmLabel = "Delete",
            onConfirm = { vm.change("Delete alias ${alias.name}", reload) { it.deleteFirewallAlias(scope, alias.name) } },
            onDismiss = { deleting = null },
        )
    }
}

// ---------------------------------------------------------------- IP sets

@Composable
private fun IpSetsTab(scope: FirewallScope, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (sets, error) = rememberLoaded(vms, version) { it.ipSets(scope) }
    var open by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<IpSetEntry>>(emptyList()) }
    var adding by remember { mutableStateOf<String?>(null) } // set name, "" for a new set
    var deleting by remember { mutableStateOf<Pair<String, String?>?>(null) } // set, entry
    LaunchedEffect(open, version) {
        val name = open ?: return@LaunchedEffect
        vm.fetch { it.ipSetEntries(scope, name) }.onSuccess { entries = it }
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loading(sets, error)
        if (sets?.isEmpty() == true) item { Text("No IP sets. An IP set groups addresses for use in rules as +name.") }
        items(sets.orEmpty(), key = { "ipset-${it.name}" }) { set: IpSet ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (open == set.name) null else set.name }.testTag("fw-ipset-${set.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(set.name, fontWeight = FontWeight.SemiBold)
                            if (set.comment.isNotBlank()) Text(set.comment, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { deleting = set.name to null }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, "Delete IP set", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (open == set.name) {
                        entries.forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (if (e.nomatch) "! " else "") + e.cidr + if (e.comment.isNotBlank()) "  · ${e.comment}" else "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { deleting = set.name to e.cidr }, enabled = !busy) {
                                    Icon(Icons.Filled.Delete, "Remove ${e.cidr}", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if (entries.isEmpty()) Text("Empty.", style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            onClick = { adding = set.name },
                            enabled = !busy,
                            modifier = Modifier.testTag("fw-ipset-add-entry-${set.name}"),
                        ) { Text("Add address") }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { adding = "" }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("fw-ipset-add")) {
                Text("Add IP set")
            }
        }
    }
    adding?.let { setName ->
        val newSet = setName.isEmpty()
        var value by remember(setName) { mutableStateOf("") }
        var comment by remember(setName) { mutableStateOf("") }
        var nomatch by remember(setName) { mutableStateOf(false) }
        val problem = if (newSet) FirewallNames.nameError(value) else FirewallNames.cidrError(value)
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(if (newSet) "Add IP set" else "Add to $setName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleField(if (newSet) "Name" else "IP or CIDR", value, "fw-ipset-value") { value = it }
                    RuleField("Comment", comment, "fw-ipset-comment") { comment = it }
                    if (!newSet) CheckRow("No match (exclude this address)", nomatch) { nomatch = it }
                    if (value.isNotEmpty()) problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newSet) {
                            vm.change("Add IP set $value", reload) { it.addIpSet(scope, value, comment) }
                        } else {
                            vm.change("Add $value to $setName", reload) { it.addIpSetEntry(scope, setName, IpSetEntry(value, nomatch, comment)) }
                        }
                        adding = null
                    },
                    enabled = problem == null,
                    modifier = Modifier.testTag("fw-ipset-save"),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { (set, cidr) ->
        ConfirmDialog(
            title = if (cidr == null) "Delete IP set $set?" else "Remove $cidr from $set?",
            text = if (cidr == null) "Only an empty IP set that no rule uses can be deleted." else "Rules using +$set no longer match it.",
            confirmLabel = if (cidr == null) "Delete" else "Remove",
            onConfirm = {
                if (cidr == null) {
                    vm.change("Delete IP set $set", reload) { it.deleteIpSet(scope, set) }
                } else {
                    vm.change("Remove $cidr", reload) { it.deleteIpSetEntry(scope, set, cidr) }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

// ---------------------------------------------------------------- security groups

@Composable
private fun GroupsTab(profileId: String, vms: AppViewModels, version: Int, busy: Boolean, reload: () -> Unit) {
    val vm = vms.proxmox
    val (groups, error) = rememberLoaded(vms, version) { it.securityGroups() }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loading(groups, error)
        if (groups?.isEmpty() == true) {
            item { Text("No security groups. A group is a set of rules that any guest or node can use in one rule.") }
        }
        items(groups.orEmpty(), key = { "group-${it.name}" }) { g ->
            Card(
                Modifier.fillMaxWidth()
                    .clickable { vms.servers.navigate(Screen.Firewall(profileId, FirewallScope(group = g.name))) }
                    .testTag("fw-group-open-${g.name}"),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(g.name, fontWeight = FontWeight.SemiBold)
                        if (g.comment.isNotBlank()) Text(g.comment, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { deleting = g.name }, enabled = !busy) {
                        Icon(Icons.Filled.Delete, "Delete group", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { adding = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("fw-group-add")) {
                Text("Add security group")
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var comment by remember { mutableStateOf("") }
        val problem = FirewallNames.nameError(name)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add security group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleField("Name", name, "fw-group-name") { name = it }
                    RuleField("Comment", comment, "fw-group-comment") { comment = it }
                    if (name.isNotEmpty()) problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.change("Add security group $name", reload) { it.addSecurityGroup(name, comment) }
                        adding = false
                    },
                    enabled = problem == null,
                    modifier = Modifier.testTag("fw-group-save"),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { name ->
        ConfirmDialog(
            title = "Delete security group $name?",
            text = "Only a group with no rules that nothing uses can be deleted.",
            confirmLabel = "Delete",
            onConfirm = { vm.change("Delete security group $name", reload) { it.deleteSecurityGroup(name) } },
            onDismiss = { deleting = null },
        )
    }
}

// ---------------------------------------------------------------- log

@Composable
private fun LogTab(scope: FirewallScope, vms: AppViewModels, version: Int) {
    val (lines, error) = rememberLoaded(vms, version) { it.firewallLog(scope) }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        loading(lines, error)
        if (lines?.isEmpty() == true) item { Text("Nothing logged. Set a log level on a rule or in Options.") }
        items(lines.orEmpty()) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}
