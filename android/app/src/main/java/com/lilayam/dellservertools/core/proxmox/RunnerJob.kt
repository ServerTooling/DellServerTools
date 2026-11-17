package com.lilayam.dellservertools.core.proxmox

/**
 * The GitHub Actions job a pve-runners container is running, read back from the tags pve-runners puts on it
 * when the job starts. Tags are lowercased and stripped to Proxmox's tag characters, so the names here are
 * those cleaned forms ("feature-login", "unit-2-4"), not the exact GitHub names.
 */
data class RunnerJob(
    val owner: String?,
    val repo: String,
    val branch: String?,
    val pullRequest: String?,
    val tag: String?,
    val workflow: String?,
    val job: String?,
    val runId: Long?,
) {
    /** The run's page on GitHub; GitHub matches owner and repository names case-insensitively. */
    val runUrl: String?
        get() = if (owner != null && runId != null) "https://github.com/$owner/$repo/actions/runs/$runId" else null

    /** "owner/repo · PR #12 (feature-login)", "owner/repo · branch main" or "owner/repo · tag v1.2". */
    val source: String
        get() = buildString {
            append(if (owner != null) "$owner/$repo" else repo)
            when {
                pullRequest != null -> append(" · PR #$pullRequest").also { branch?.let { append(" ($it)") } }
                branch != null -> append(" · branch $branch")
                tag != null -> append(" · tag $tag")
            }
        }

    /** "ci › unit-2-4 · run 42", leaving out what isn't known; null when nothing is. */
    val what: String?
        get() = listOfNotNull(
            listOfNotNull(workflow, job).joinToString(" › ").ifEmpty { null },
            runId?.let { "run $it" },
        ).joinToString(" · ").ifEmpty { null }

    companion object {
        /** The tags pve-runners writes per job (owner-<org> is set when the runner is created). */
        private val JOB_PREFIXES = listOf("repo-", "branch-", "pr-", "tag-", "wf-", "job-", "run-")

        fun isJobTag(tag: String): Boolean = JOB_PREFIXES.any { tag.startsWith(it) }

        /** The job of a runner container, or null when it isn't one or has no job yet. */
        fun fromTags(tags: List<String>): RunnerJob? {
            if ("gh-runner" !in tags) return null
            fun value(prefix: String) = tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.ifEmpty { null }
            val repo = value("repo-") ?: return null
            return RunnerJob(
                owner = value("owner-"),
                repo = repo,
                branch = value("branch-"),
                pullRequest = value("pr-"),
                tag = value("tag-"),
                workflow = value("wf-"),
                job = value("job-"),
                runId = value("run-")?.toLongOrNull(),
            )
        }
    }
}
