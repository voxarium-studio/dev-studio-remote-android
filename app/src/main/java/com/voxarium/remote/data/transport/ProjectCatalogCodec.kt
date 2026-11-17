package com.voxarium.remote.data.transport

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

class CatalogValidationException : IllegalArgumentException("Invalid Remote catalog data")

/** Exact Remote v2 JSON, independent of the legacy file/room FrameCodec (§16.7). */
object ProjectCatalogCodec {
    const val PROTOCOL = 2L
    const val CATALOG_API = 2L
    const val MAX_INTEGER = 9_007_199_254_740_991L
    internal val requiredCapabilities = setOf("project_catalog", "explicit_project_create", "strict_connect")
    private val idPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    internal fun bad(): Nothing = throw CatalogValidationException()
    fun checkId(id: String): String = id.also { if (!idPattern.matches(it)) bad() }

    /** Explicit label normalisation; never apply to incoming historical labels or IDs. */
    fun normalizeDisplayName(raw: String): String {
        unicode(raw)
        if (raw.any { it.code < 32 || it.code in 127..159 || it in "\u2028\u2029\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069" }) bad()
        // Match Python str.strip(), not Java/Kotlin's larger whitespace definition.
        fun strip(c: Char) = c in " \u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u202f\u205f\u3000"
        val name = Normalizer.normalize(raw, Normalizer.Form.NFC).trim(::strip)
        if (name.codePointCount(0, name.length) !in 1..120 || name.toByteArray(Charsets.UTF_8).size > 480) bad()
        return name
    }
    fun encodeCreate(id: String, displayName: String): String = JSONObject()
        .put("id", checkId(id)).put("display_name", normalizeDisplayName(displayName)).toString()

    internal fun json(raw: String): JSONObject = try {
        StrictJson(raw).check()
        JSONObject(raw)
    } catch (_: Exception) { bad() }
    internal fun number(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> bad()
    }.also { if (it !in 0..MAX_INTEGER) bad() }
    internal fun string(value: Any?, maxBytes: Int = 8192): String = (value as? String ?: bad()).also {
        unicode(it)
        if (it.toByteArray(Charsets.UTF_8).size > maxBytes) bad()
    }
    internal fun unicode(value: String) {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c.isHighSurrogate()) {
                if (i == value.length || !value[i++].isLowSurrogate()) bad()
            } else if (c.isLowSurrogate()) bad()
        }
    }
    private fun objectValue(value: Any?): JSONObject = value as? JSONObject ?: bad()
    private fun envelope(raw: String): JSONObject = json(raw).also { if (number(it.opt("protocol")) != PROTOCOL) bad() }
    internal fun user(value: Any?, legacy: Boolean = false): RemoteUser {
        val obj = objectValue(value)
        val id = string(obj.opt("id"), 128)
        if (id.isEmpty() || id.any { it.code <= 32 || it.code == 127 }) bad()
        val name = string(obj.opt(if (legacy) "name" else "display_name"))
        val color = string(obj.opt("color"), 7)
        if (!Regex("#[0-9a-fA-F]{6}").matches(color)) bad()
        return RemoteUser(id, name, color)
    }
    internal fun decodeV2Me(raw: String, identity: ServerRequestIdentity): VerifiedServer {
        val obj = envelope(raw)
        val api = number(obj.opt("catalog_api"))
        if (api != CATALOG_API) bad()
        val array = obj.opt("capabilities") as? JSONArray ?: bad()
        if (array.length() > 64) bad()
        val capabilities = (0 until array.length()).map { string(array.opt(it), 128) }.toSet()
        if (!capabilities.containsAll(requiredCapabilities)) bad()
        return VerifiedServer(identity, user(obj.opt("user")), PROTOCOL, api, capabilities)
    }
    internal fun decodeLegacyMe(raw: String, identity: ServerRequestIdentity): VerifiedServer {
        val obj = json(raw)
        if (number(obj.opt("protocol")) != 1L) bad()
        return VerifiedServer(identity, user(obj.opt("user"), legacy = true), 1, null, emptySet())
    }
    private fun project(value: Any?): ProjectSummary {
        val obj = objectValue(value)
        val id = checkId(string(obj.opt("id"), 64))
        val epoch = string(obj.opt("storage_epoch"), 32)
        if (!Regex("[0-9a-f]{32}").matches(epoch)) bad()
        val revision = number(obj.opt("metadata_revision"))
        if (revision < 1) bad()
        val created = string(obj.opt("created_at"), 128)
        if (created.isEmpty() || created.any { it.code < 32 || it.code == 127 }) bad()
        // Historical labels are literal metadata, not routing. UI must render them as text.
        return ProjectSummary(id, string(obj.opt("display_name")), epoch, revision, created)
    }
    fun decodeProject(raw: String, expectedId: String? = null): ProjectSummary =
        project(envelope(raw).opt("project")).also { if (expectedId != null && it.id != checkId(expectedId)) bad() }
    fun decodeLegacyResolution(raw: String, exactName: String): ProjectSummary {
        val obj = envelope(raw)
        if (string(obj.opt("legacy_space_name"), 480) != exactName) bad()
        return project(obj.opt("project"))
    }
    fun decodePage(raw: String, limit: Int = 100, after: String? = null): ProjectPage {
        if (limit !in 1..200) bad()
        after?.let(::checkId)
        val obj = envelope(raw)
        val rows = obj.opt("projects") as? JSONArray ?: bad()
        if (rows.length() > limit || !obj.has("next_after")) bad()
        val projects = (0 until rows.length()).map { project(rows.opt(it)) }
        var previous = after ?: ""
        for (row in projects) {
            if (row.id <= previous) bad() // Reject duplicates, regressing pages and keyset loops.
            previous = row.id
        }
        val next = if (obj.isNull("next_after")) null else checkId(string(obj.opt("next_after"), 64))
        if (next != null && (projects.size != limit || next != projects.lastOrNull()?.id)) bad()
        return ProjectPage(projects, next)
    }
    /** Stable allowlist only. Raw message/request_id never escapes this codec. */
    internal fun errorCode(raw: String?): String? = try {
        val obj = json(raw ?: bad()).opt("error") as? JSONObject ?: bad()
        val code = string(obj.opt("code"), 64)
        if (code in setOf("unauthorised", "invalid_request", "invalid_id", "invalid_display_name",
                "project_exists", "project_not_found", "legacy_space_not_found", "payload_too_large")) code else null
    } catch (_: Exception) { null }
}

/** org.json on Android is permissive. Preflight RFC 8259 syntax + depth before parsing. */
private class StrictJson(private val raw: String) {
    private var i = 0
    fun check() { value(0); white(); if (i != raw.length) fail() }
    private fun fail(): Nothing = throw CatalogValidationException()
    private fun white() { while (i < raw.length && raw[i] in " \t\r\n") i++ }
    private fun take(c: Char): Boolean { white(); return if (i < raw.length && raw[i] == c) { i++; true } else false }
    private fun need(c: Char) { if (!take(c)) fail() }
    private fun value(depth: Int) {
        if (depth > 16) fail()
        white()
        when (raw.getOrNull(i)) {
            '{' -> {
                i++
                val keys = mutableSetOf<String>()
                if (!take('}')) {
                    do {
                        val key = org.json.JSONTokener(string()).nextValue() as? String ?: fail()
                        if (!keys.add(key)) fail()
                        need(':'); value(depth + 1)
                    } while (take(','))
                    need('}')
                }
            }
            '[' -> { i++; if (!take(']')) { do { value(depth + 1) } while (take(',')); need(']') } }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val start = i
                if (raw.getOrNull(i) == '-') i++
                if (raw.getOrNull(i) == '0') i++ else {
                    if (raw.getOrNull(i) !in '1'..'9') fail()
                    while (raw.getOrNull(i) in '0'..'9') i++
                }
                if (raw.getOrNull(i) == '.') { i++; digits() }
                if (raw.getOrNull(i) == 'e' || raw.getOrNull(i) == 'E') {
                    i++; if (raw.getOrNull(i) == '+' || raw.getOrNull(i) == '-') i++; digits()
                }
                if (i == start) fail()
            }
        }
    }
    private fun digits() { if (raw.getOrNull(i) !in '0'..'9') fail(); while (raw.getOrNull(i) in '0'..'9') i++ }
    private fun literal(text: String) { if (!raw.startsWith(text, i)) fail(); i += text.length }
    private fun string(): String {
        white(); val start = i
        need('"')
        while (i < raw.length) {
            val c = raw[i++]
            if (c == '"') return raw.substring(start, i)
            if (c.code < 32) fail()
            if (c == '\\') {
                val escaped = raw.getOrNull(i++) ?: fail()
                if (escaped == 'u') repeat(4) { if (raw.getOrNull(i++)?.let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } != true) fail() }
                else if (escaped !in "\"\\/bfnrt") fail()
            }
        }
        fail()
    }
}

