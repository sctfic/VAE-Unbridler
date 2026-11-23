package com.alban.ebike.firmware

internal data class GithubReleaseCandidate(val index: Int, val tag: String,
    val draft: Boolean, val prerelease: Boolean, val publishedAt: String)

/** The list endpoint's order is not a promise of firmware publication order. */
internal fun firmwareReleaseCandidates(items: List<GithubReleaseCandidate>) = items
    .filter { !it.draft && !it.prerelease && it.tag.matches(Regex("esp32-hw-v1-v[0-9]+\\.[0-9]+\\.[0-9]+")) }
    .sortedByDescending { it.publishedAt }
