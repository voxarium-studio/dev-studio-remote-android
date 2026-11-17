package com.voxarium.remote.data.security

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Android identity design §3: canonicalizer v1, exact persisted URL (including proxy prefix).
 * These models contain no secrets or live transport/write grants. Factories validate every path.
 */
class ServerProfile private constructor(
    val localProfileId: String,
    val name: String,
    val canonicalUrl: String,
    val credentialId: String,
    val credentialRevision: Long,
    val verification: ServerVerification?,
    val canonicalizerVersion: Int,
) {
    override fun toString() = "ServerProfile([metadata redacted])"

    companion object {
        fun create(
            name: String, canonicalUrl: String, credentialId: String,
            localProfileId: String = UUID.randomUUID().toString(), credentialRevision: Long = 1,
            verification: ServerVerification? = null, canonicalizerVersion: Int = 1,
        ): ServerProfile {
            if (!validReference(localProfileId) || !validReference(credentialId) || credentialRevision < 1 ||
                name.isBlank() || name != name.trim() || name.length > 256 || name.any { it.code < 32 } ||
                canonicalizerVersion != 1 || !validCanonicalIdentityUrl(canonicalUrl)) invalid()
            if (verification != null && (verification.canonicalUrl != canonicalUrl ||
                verification.credentialId != credentialId || verification.credentialRevision != credentialRevision)) invalid()
            return ServerProfile(localProfileId, name, canonicalUrl, credentialId, credentialRevision,
                verification, canonicalizerVersion)
        }
    }
}

/** Historical HTTP observation only. Persistence/restart never makes this a live verification. */
class ServerVerification private constructor(
    val canonicalUrl: String, val credentialId: String, val credentialRevision: Long,
    val requestGeneration: Long, val accountId: String, val protocol: Int,
    val checkedAtEpochMillis: Long, val catalogContractVersion: Int?,
    val accountDisplayName: String, val accountColor: String,
) {
    override fun toString() = "ServerVerification([historical metadata redacted])"
    companion object {
        fun create(canonicalUrl: String, credentialId: String, credentialRevision: Long,
                   requestGeneration: Long, accountId: String, protocol: Int,
                   checkedAtEpochMillis: Long, accountDisplayName: String, accountColor: String,
                   catalogContractVersion: Int? = null): ServerVerification {
            if (!validCanonicalIdentityUrl(canonicalUrl) || !validReference(credentialId) ||
                credentialRevision < 1 || requestGeneration < 1 || !validIdentityValue(accountId) ||
                protocol !in 1..2 || checkedAtEpochMillis < 0 || accountDisplayName.isEmpty() ||
                accountDisplayName.length > 1024 || !Regex("#[0-9a-fA-F]{6}").matches(accountColor) ||
                (catalogContractVersion != null && (catalogContractVersion != 2 || protocol != 2))) invalid()
            return ServerVerification(canonicalUrl, credentialId, credentialRevision, requestGeneration,
                accountId, protocol, checkedAtEpochMillis, catalogContractVersion, accountDisplayName, accountColor)
        }
    }
}

/** A target is immutable. Selecting a different ID/account/epoch requires another binding ID.
 * Metadata alone does not activate it, open a socket or authorize reading/writing a partition.
 */
class ProjectBinding private constructor(
    val bindingId: String, val serverProfileId: String, val projectId: String,
    val expectedAccountId: String, val storageEpoch: String, val catalogContractVersion: Int,
    val legacyImportRecordId: String?,
    val canonicalUrl: String, val credentialId: String, val credentialRevision: Long,
) {
    override fun toString() = "ProjectBinding([identity redacted])"
    companion object {
        fun create(serverProfile: ServerProfile, projectId: String, expectedAccountId: String, storageEpoch: String,
                   bindingId: String = UUID.randomUUID().toString(), catalogContractVersion: Int = 2,
                   legacyImportRecordId: String? = null): ProjectBinding {
            if (!validReference(bindingId) || !validProjectIdentity(projectId) ||
                !validIdentityValue(expectedAccountId) || !validStorageEpoch(storageEpoch) || catalogContractVersion != 2 ||
                (legacyImportRecordId != null && !validReference(legacyImportRecordId))) invalid()
            return ProjectBinding(bindingId, serverProfile.localProfileId, projectId, expectedAccountId, storageEpoch,
                catalogContractVersion, legacyImportRecordId, serverProfile.canonicalUrl,
                serverProfile.credentialId, serverProfile.credentialRevision)
        }
    }
}

/** Exact v1 provenance; observedSpaceId is a candidate, never a selected v2 project ID. */
class LegacyImportRecord private constructor(
    val recordId: String, val localProfileId: String, val canonicalUrl: String,
    val credentialId: String, val spaceName: String,
    val lastAuthenticatedAccountId: String?, val observedSpaceId: String?,
) {
    override fun toString() = "LegacyImportRecord([provenance redacted])"
    companion object {
        fun create(profile: SavedProfile, lastAuthenticatedAccountId: String? = null,
                   observedSpaceId: String? = null): LegacyImportRecord {
            if ((lastAuthenticatedAccountId != null && !validIdentityValue(lastAuthenticatedAccountId)) ||
                (observedSpaceId != null && (!validProjectIdentity(observedSpaceId) || lastAuthenticatedAccountId == null))) invalid()
            // Profile ID is stable and unique, so retries cannot manufacture duplicate records.
            return LegacyImportRecord(profile.id, profile.id, profile.canonicalServerUrl, profile.credentialId,
                profile.spaceName, lastAuthenticatedAccountId, observedSpaceId)
        }
    }
}

internal fun validProjectIdentity(value: String) = Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(value)
internal fun validStorageEpoch(value: String) = Regex("[0-9a-f]{32}").matches(value)
internal fun validIdentityValue(value: String) = value.isNotBlank() && value.length <= 256 &&
    value.none { Character.isISOControl(it) || it.isSurrogate() }
internal fun validCanonicalIdentityUrl(value: String): Boolean {
    if (value.length > 16384 || value.any { it <= ' ' || it == '\\' }) return false
    val url = value.toHttpUrlOrNull() ?: return false
    return url.scheme == "https" && url.host.isNotEmpty() && url.username.isEmpty() && url.password.isEmpty() &&
        !value.substringAfter("://").substringBefore('/').contains('@') && url.query == null && url.fragment == null &&
        url.toString().trimEnd('/') == value
}
