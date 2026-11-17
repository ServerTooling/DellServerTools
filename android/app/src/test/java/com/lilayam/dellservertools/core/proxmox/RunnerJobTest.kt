package com.lilayam.dellservertools.core.proxmox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnerJobTest {
    private val prTags = listOf(
        "created-1794918146", "gh-runner", "mode-ephemeral", "owner-example-org", "pool-default", "pr-12",
        "repo-app", "runnerid-41", "branch-feature-login", "wf-ci", "job-unit-2-4", "run-1234567890",
    )

    @Test
    fun readsAPullRequestJob() {
        val job = RunnerJob.fromTags(prTags)!!
        assertEquals(
            RunnerJob("example-org", "app", "feature-login", "12", null, "ci", "unit-2-4", 1234567890),
            job,
        )
        assertEquals("example-org/app · PR #12 (feature-login)", job.source)
        assertEquals("ci › unit-2-4 · run 1234567890", job.what)
        assertEquals("https://github.com/example-org/app/actions/runs/1234567890", job.runUrl)
    }

    @Test
    fun readsBranchAndTagJobs() {
        val branch = RunnerJob.fromTags(listOf("gh-runner", "owner-example-org", "repo-app", "branch-main"))!!
        assertEquals("example-org/app · branch main", branch.source)
        assertNull(branch.what)
        assertNull(branch.runUrl)
        val tag = RunnerJob.fromTags(listOf("gh-runner", "repo-app", "tag-v1.2", "run-7"))!!
        assertEquals("app · tag v1.2", tag.source)
        assertEquals("run 7", tag.what)
        // Without the owner there is no URL to build.
        assertNull(tag.runUrl)
    }

    @Test
    fun onlyRunnersWithAJob() {
        assertNull(RunnerJob.fromTags(listOf("repo-app", "pr-1")))
        assertNull(RunnerJob.fromTags(listOf("gh-runner", "owner-example-org", "runnerid-41")))
        assertNull(RunnerJob.fromTags(emptyList()))
    }

    @Test
    fun jobTagsAreTheOnesPveRunnersSetsPerJob() {
        val job = prTags.filter(RunnerJob::isJobTag)
        assertEquals(listOf("pr-12", "repo-app", "branch-feature-login", "wf-ci", "job-unit-2-4", "run-1234567890"), job)
        assertFalse(RunnerJob.isJobTag("runnerid-41"))
        assertTrue(RunnerJob.isJobTag("run-1"))
    }
}
