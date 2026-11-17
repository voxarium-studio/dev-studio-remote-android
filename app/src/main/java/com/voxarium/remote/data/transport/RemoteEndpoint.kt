package com.voxarium.remote.data.transport

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Android design §4 / dev spec §16.7: URL identity includes its reverse-proxy prefix. */
@ConsistentCopyVisibility
data class RemoteEndpoint private constructor(val serverUrl: String, val space: String = "") {
    init {
        val parsed = serverUrl.toHttpUrlOrNull()
        require(parsed != null && parsed.scheme == "https" && parsed.username.isEmpty() &&
            parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null &&
            parsed.toString().trimEnd('/') == serverUrl &&
            (space.isEmpty() || ID.matches(space))) { "Invalid Remote endpoint" }
    }

    fun healthUrl(): HttpUrl = route("healthz")
    /** Legacy HTTP discovery; never implies permission to open the legacy socket. */
    fun meUrl(): HttpUrl = route("api/v1/me")
    fun v2MeUrl(): HttpUrl = route("api/v2/me")
    fun projectsUrl(): HttpUrl = route("api/v2/projects")
    fun projectUrl(id: String): HttpUrl {
        require(ID.matches(id)) { "Invalid project ID" }
        return projectsUrl().newBuilder().addPathSegment(id).build()
    }
    fun legacyResolutionUrl(name: String): HttpUrl = route("api/v2/legacy-spaces/resolve")
        .newBuilder().addQueryParameter("name", name).build()
    fun v2SyncUrl(id: String): HttpUrl {
        require(ID.matches(id)) { "Invalid project ID" }
        return route("api/v2/sync").newBuilder().addQueryParameter("space", id).build()
    }
    // Compatibility only, until A5 replaces existing v1 session callers. URL-only values
    // cannot accidentally open a dummy/default legacy space.
    fun syncUrl(): HttpUrl {
        require(space.isNotEmpty()) { "Legacy space is required" }
        return route("api/v1/sync").newBuilder().addQueryParameter("space", space).build()
    }
    private fun route(path: String): HttpUrl = "$serverUrl/$path".toHttpUrlOrNull()!!
    override fun toString() = "RemoteEndpoint(redacted)"

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        /** URL-only factory. No space/project is invented or normalised. */
        fun create(url: String): RemoteEndpoint = RemoteEndpoint(canonicalUrl(url))
        /** Legacy adapter only: retain the old selector normalisation for existing callers. */
        fun create(url: String, space: String): RemoteEndpoint {
            val name = space.trim().lowercase(Locale.ROOT)
            require(ID.matches(name)) { "Invalid space name" }
            return RemoteEndpoint(canonicalUrl(url), name)
        }
        private fun canonicalUrl(url: String): String {
            var raw = url.trim()
            require(raw.isNotEmpty() && raw.none { it <= ' ' || it == '\\' }) { "Invalid server URL" }
            raw = when {
                raw.startsWith("wss://", true) -> "https://" + raw.substring(6)
                raw.contains("://") -> raw
                else -> "https://$raw"
            }
            val parsed = raw.toHttpUrlOrNull()
            require(parsed != null && parsed.scheme == "https" && parsed.host.isNotEmpty() &&
                parsed.username.isEmpty() && parsed.password.isEmpty() &&
                !raw.substringAfter("://").substringBefore('/').contains('@') &&
                parsed.query == null && parsed.fragment == null) { "Invalid server URL" }
            return parsed.toString().trimEnd('/')
        }
    }
}
