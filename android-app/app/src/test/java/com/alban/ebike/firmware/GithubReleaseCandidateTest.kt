package com.alban.ebike.firmware

import org.junit.Assert.*
import org.junit.Test

class GithubReleaseCandidateTest {
    @Test fun newFirmwareIsFoundEvenWhenGithubListsOlderReleasesFirst() {
        fun release(i: Int, tag: String, date: String, draft: Boolean = false, preview: Boolean = false) =
            GithubReleaseCandidate(i, tag, draft, preview, date)
        val candidates = listOf(
            release(0, "esp32-hw-v1-v1.0.7", "2026-11-23T10:14:44Z"),
            release(1, "esp32-hw-v1-v1.0.6", "2026-11-23T09:17:02Z"),
            release(2, "esp32-hw-v1-v1.0.10", "2026-11-23T11:20:43Z"),
            release(3, "v0.4.33", "2026-11-23T12:00:00Z"),
            release(4, "esp32-hw-v1-v1.0.11", "2026-11-24T12:00:00Z", draft = true),
            release(5, "esp32-hw-v1-v1.0.12", "2026-11-25T12:00:00Z", preview = true))
        assertEquals(listOf(2, 0, 1), firmwareReleaseCandidates(candidates).map { it.index })
    }
}
