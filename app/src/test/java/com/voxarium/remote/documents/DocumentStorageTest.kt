package com.voxarium.remote.documents

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.AtomicFile
import com.voxarium.remote.data.documents.AtomicDocumentStorage
import com.voxarium.remote.data.documents.DocumentStorageException
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Real Android AtomicFile on both minSdk's legacy .bak and current Android's .new algorithm. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], shadows = [DocumentStorageTest.ErrnoAwareOs::class, DocumentStorageTest.PosixAtomicRename::class])
class DocumentStorageTest {
    @get:Rule
    val temporary = TemporaryFolder(
        File(System.getenv("VOXARIUM_DOCUMENTS_TEST_TEMP") ?: System.getProperty("java.io.tmpdir"), "documents-storage-tests").apply {
            check(isDirectory || mkdirs())
        },
    )

    private val root: File get() = File(temporary.root, "store")

    @Test fun missingSnapshotsDoNotCreateDirectoriesOrPretendToBeEmptyPayloads() {
        val storage = AtomicDocumentStorage(root)
        assertNull(storage.readCache(NAMESPACE))
        assertNull(storage.readDrafts(NAMESPACE))
        assertFalse(root.exists())
        storage.writeCache(NAMESPACE, byteArrayOf())
        storage.writeDrafts(NAMESPACE, byteArrayOf())
        assertArrayEquals(byteArrayOf(), AtomicDocumentStorage(root).readCache(NAMESPACE))
        assertArrayEquals(byteArrayOf(), AtomicDocumentStorage(root).readDrafts(NAMESPACE))
    }

    @Test fun roundTripAndRestartKeepCacheAndDraftsPhysicallyIndependent() {
        val storage = AtomicDocumentStorage(root)
        val cache = byteArrayOf(0, 127, -128, -1)
        val drafts = "draft\u0000 $SECRET".toByteArray(Charsets.UTF_8)
        storage.writeCache(NAMESPACE, cache)
        storage.writeDrafts(NAMESPACE, drafts)
        val files = directory(root, NAMESPACE).listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("cache.bin", "drafts.bin"), files)
        val reopened = AtomicDocumentStorage(root)
        assertArrayEquals(cache, reopened.readCache(NAMESPACE))
        assertArrayEquals(drafts, reopened.readDrafts(NAMESPACE))
        reopened.writeCache(NAMESPACE, "new cache".toByteArray())
        assertArrayEquals(drafts, storage.readDrafts(NAMESPACE))
        reopened.writeDrafts(NAMESPACE, "new drafts".toByteArray())
        assertArrayEquals("new cache".toByteArray(), storage.readCache(NAMESPACE))
    }

    @Test fun returnedAndSubmittedArraysDoNotAliasStoredBytes() {
        val storage = AtomicDocumentStorage(root)
        val bytes = byteArrayOf(1, 2, 3)
        storage.writeDrafts(NAMESPACE, bytes)
        bytes[0] = 9
        val read = storage.readDrafts(NAMESPACE)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), read)
        read[1] = 8
        assertArrayEquals(byteArrayOf(1, 2, 3), storage.readDrafts(NAMESPACE))
    }

    @Test fun corruptCacheIsDisposableButEveryCorruptDraftSurvivesReadAndWriteAttempts() {
        val storage = AtomicDocumentStorage(root)
        storage.writeCache(NAMESPACE, SECRET.toByteArray())
        storage.writeDrafts(NAMESPACE, SECRET.toByteArray())
        val valid = cacheFile().readBytes()
        val damaged = listOf(
            byteArrayOf(),
            valid.copyOf(4),
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0),
            valid.clone().apply { this[0] = 0 },
            valid.clone().apply { this[7] = 99 },
            valid.clone().apply { ByteBuffer.wrap(this).putInt(8, -1) },
            valid.clone().apply { ByteBuffer.wrap(this).putInt(8, Int.MAX_VALUE) },
            valid.clone().apply { this[12] = (this[12].toInt() xor 1).toByte() },
            valid.clone().apply { this[lastIndex] = (last().toInt() xor 1).toByte() },
        )
        for (bytes in damaged) {
            cacheFile().writeBytes(bytes)
            draftsFile().writeBytes(bytes)
            val reopened = AtomicDocumentStorage(root)
            assertNull(reopened.readCache(NAMESPACE))
            assertArrayEquals(bytes, cacheFile().readBytes())
            assertSafeFailure(DocumentStorageException.Reason.CORRUPT) {
                reopened.readDrafts(NAMESPACE)
            }
            assertSafeFailure(DocumentStorageException.Reason.CORRUPT) {
                reopened.writeDrafts(NAMESPACE, byteArrayOf())
            }
            assertArrayEquals(bytes, draftsFile().readBytes())
            assertFalse(File(draftsFile().path + ".bak").exists())
            assertFalse(File(draftsFile().path + ".new").exists())
            reopened.writeCache(NAMESPACE, byteArrayOf(4, 5))
            assertArrayEquals(byteArrayOf(4, 5), reopened.readCache(NAMESPACE))
            assertArrayEquals(bytes, draftsFile().readBytes())
        }
    }

    @Test fun legacyBackupRecoversOverPartialOrEvenApparentlyCompleteBaseAfterRestart() {
        val storage = AtomicDocumentStorage(root)
        val original = "committed draft".toByteArray()
        storage.writeDrafts(NAMESPACE, original)
        val envelope = draftsFile().readBytes()
        val backup = File(draftsFile().path + ".bak")
        for (uncommitted in listOf(byteArrayOf(1, 2), envelope)) {
            assertTrue(draftsFile().renameTo(backup))
            draftsFile().writeBytes(uncommitted)
            assertArrayEquals(original, AtomicDocumentStorage(root).readDrafts(NAMESPACE))
            assertArrayEquals(envelope, draftsFile().readBytes())
            assertFalse(backup.exists())
        }
        assertTrue(draftsFile().renameTo(backup))
        assertArrayEquals(original, AtomicDocumentStorage(root).readDrafts(NAMESPACE))
        assertFalse(backup.exists())
    }

    @Test fun corruptCommittedBackupIsNotReplacedWithUncommittedBaseOrNewDraft() {
        val storage = AtomicDocumentStorage(root)
        storage.writeDrafts(NAMESPACE, "valid but uncommitted".toByteArray())
        val corrupt = SECRET.toByteArray()
        val backup = File(draftsFile().path + ".bak").apply { writeBytes(corrupt) }
        assertSafeFailure(DocumentStorageException.Reason.CORRUPT) {
            AtomicDocumentStorage(root).writeDrafts(NAMESPACE, "replacement".toByteArray())
        }
        assertFalse(backup.exists())
        assertArrayEquals(corrupt, draftsFile().readBytes())
    }

    @Test fun interruptedActualAtomicWriteRecoversPreviousSnapshotAndCanCommitAgain() {
        val storage = AtomicDocumentStorage(root)
        val previous = "previous committed".toByteArray()
        storage.writeDrafts(NAMESPACE, previous)
        storage.writeCache(NAMESPACE, "independent cache".toByteArray())
        val atomic = AtomicFile(draftsFile())
        val stream = atomic.startWrite()
        stream.write("partial uncommitted $SECRET".toByteArray())
        stream.flush()
        stream.fd.sync()
        // Deliberate crash simulation: the process loses its fd without finishWrite/failWrite.
        stream.close()
        assertTrue(File(draftsFile().path + ".bak").exists() ||
            File(draftsFile().path + ".new").exists())
        val reopened = AtomicDocumentStorage(root)
        assertArrayEquals(previous, reopened.readDrafts(NAMESPACE))
        assertArrayEquals("independent cache".toByteArray(), reopened.readCache(NAMESPACE))
        assertFalse(File(draftsFile().path + ".bak").exists())
        assertFalse(File(draftsFile().path + ".new").exists())
        reopened.writeDrafts(NAMESPACE, "next committed".toByteArray())
        assertArrayEquals("next committed".toByteArray(), storage.readDrafts(NAMESPACE))
    }

    @Test fun explicitAtomicRollbackRestoresCommittedDraftAndDoesNotAffectCache() {
        val storage = AtomicDocumentStorage(root)
        storage.writeDrafts(NAMESPACE, SECRET.toByteArray())
        storage.writeCache(NAMESPACE, byteArrayOf(3))
        val atomic = AtomicFile(draftsFile())
        val stream = atomic.startWrite()
        try {
            stream.write(byteArrayOf(1, 2, 3))
            stream.fd.sync()
        } finally {
            atomic.failWrite(stream)
        }
        assertArrayEquals(SECRET.toByteArray(), AtomicDocumentStorage(root).readDrafts(NAMESPACE))
        assertArrayEquals(byteArrayOf(3), storage.readCache(NAMESPACE))
        assertFalse(File(draftsFile().path + ".bak").exists())
        assertFalse(File(draftsFile().path + ".new").exists())
    }

    @Test fun interruptedFirstWriteNeverReturnsPartialPayloadAsDurableDraft() {
        val base = draftsFile().apply { parentFile!!.mkdirs() }
        val stream = AtomicFile(base).startWrite()
        stream.write(SECRET.toByteArray())
        stream.fd.sync()
        stream.close() // Simulated process death, intentionally no commit/rollback.
        if (Build.VERSION.SDK_INT == 23) {
            // Legacy first write has no .bak: damage must be surfaced, not silently erased.
            assertSafeFailure(DocumentStorageException.Reason.CORRUPT) {
                AtomicDocumentStorage(root).writeDrafts(NAMESPACE, byteArrayOf())
            }
            assertArrayEquals(SECRET.toByteArray(), base.readBytes())
        } else {
            // Modern .new is an uncommitted transaction, never a committed snapshot.
            assertNull(AtomicDocumentStorage(root).readDrafts(NAMESPACE))
            assertFalse(base.exists())
            AtomicDocumentStorage(root).writeDrafts(NAMESPACE, byteArrayOf(9))
            assertArrayEquals(byteArrayOf(9), AtomicDocumentStorage(root).readDrafts(NAMESPACE))
        }
    }

    @Test @Config(sdk = [35])
    fun staleNewFileIsDiscardedRatherThanMistakenForCommittedData() {
        val storage = AtomicDocumentStorage(root)
        storage.writeDrafts(NAMESPACE, "committed".toByteArray())
        val newFile = File(draftsFile().path + ".new")
        newFile.writeBytes(SECRET.toByteArray())
        assertArrayEquals("committed".toByteArray(), AtomicDocumentStorage(root).readDrafts(NAMESPACE))
        assertFalse(newFile.exists())
    }

    @Test @Config(sdk = [35])
    fun modernAtomicFileUsesPosixAtomicReplacementOnWindowsToo() {
        val base = File(temporary.root, "rename-test")
        base.writeBytes(byteArrayOf(1))
        val atomic = AtomicFile(base)
        PosixAtomicRename.moveCalls = 0
        val stream = atomic.startWrite()
        stream.write(byteArrayOf(2))
        stream.fd.sync()
        atomic.finishWrite(stream)
        assertEquals(1, PosixAtomicRename.moveCalls)
        assertArrayEquals(byteArrayOf(2), base.readBytes())
        assertFalse(File(base.path + ".new").exists())
    }

    @Test @Config(sdk = [35])
    fun loggedAtomicRenameFailureIsSurfacedAndKeepsPreviousCacheAndDrafts() {
        val storage = AtomicDocumentStorage(root)
        val original = SECRET.toByteArray()
        storage.writeCache(NAMESPACE, original)
        storage.writeDrafts(NAMESPACE, original)
        try {
            PosixAtomicRename.rejectNextMove = true
            assertSafeFailure(DocumentStorageException.Reason.STORAGE) {
                storage.writeCache(NAMESPACE, byteArrayOf(1))
            }
            assertArrayEquals(original, AtomicDocumentStorage(root).readCache(NAMESPACE))
            assertFalse(File(cacheFile().path + ".new").exists())
            PosixAtomicRename.rejectNextMove = true
            assertSafeFailure(DocumentStorageException.Reason.STORAGE) {
                storage.writeDrafts(NAMESPACE, byteArrayOf(2))
            }
            assertArrayEquals(original, AtomicDocumentStorage(root).readDrafts(NAMESPACE))
            assertFalse(File(draftsFile().path + ".new").exists())
        } finally {
            PosixAtomicRename.rejectNextMove = false
        }
    }

    @Test fun namespaceAccountServerAndSpaceAreIsolatedAndNeverBecomePaths() {
        val namespaces = listOf(
            "https://one/account-a/space-1",
            "https://one/account-b/space-1",
            "https://two/account-a/space-1",
            "https://one/account-a/space-2",
            "../../$SECRET", "", "/", "\\", "a\u0000b", "\uD800", "\uD801", "?",
        )
        val storage = AtomicDocumentStorage(root)
        namespaces.forEachIndexed { index, namespace ->
            storage.writeCache(namespace, byteArrayOf(index.toByte()))
            storage.writeDrafts(namespace, byteArrayOf((index + 1).toByte()))
        }
        val reopened = AtomicDocumentStorage(root)
        namespaces.forEachIndexed { index, namespace ->
            assertArrayEquals(byteArrayOf(index.toByte()), reopened.readCache(namespace))
            assertArrayEquals(byteArrayOf((index + 1).toByte()), reopened.readDrafts(namespace))
        }
        assertEquals(namespaces.size, root.listFiles()!!.size)
        assertTrue(root.listFiles()!!.all { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) })
        assertEquals(listOf("store"), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun actualIoFailuresAreNeverReportedAsCacheMissesOrCorruptData() {
        root.writeText(SECRET) // ENOTDIR, not a missing snapshot.
        val storage = AtomicDocumentStorage(root)
        assertSafeFailure(DocumentStorageException.Reason.STORAGE) { storage.readCache(NAMESPACE) }
        assertSafeFailure(DocumentStorageException.Reason.STORAGE) { storage.readDrafts(NAMESPACE) }
        assertSafeFailure(DocumentStorageException.Reason.STORAGE) {
            storage.writeCache(NAMESPACE, byteArrayOf())
        }
        assertSafeFailure(DocumentStorageException.Reason.STORAGE) {
            storage.writeDrafts(NAMESPACE, byteArrayOf())
        }
        assertEquals(SECRET, root.readText())
    }

    @Test fun directoriesAtSnapshotOrRecoveryPathsAreIoFailuresAndAreNotDeleted() {
        for (name in listOf("cache.bin", "cache.bin.bak", "cache.bin.new")) {
            val namespace = "directory-$name"
            val path = File(directory(root, namespace), name)
            assertTrue(path.mkdirs())
            val storage = AtomicDocumentStorage(root)
            assertSafeFailure(DocumentStorageException.Reason.STORAGE) { storage.readCache(namespace) }
            assertSafeFailure(DocumentStorageException.Reason.STORAGE) {
                storage.writeCache(namespace, byteArrayOf())
            }
            assertTrue(path.isDirectory)
        }
    }

    @Test @Config(sdk = [35])
    fun exactPayloadLimitIsAcceptedButOversizedWritesPreserveCommittedSnapshots() {
        val storage = AtomicDocumentStorage(root)
        assertEquals(64 * 1024 * 1024, AtomicDocumentStorage.MAX_PAYLOAD_BYTES)
        storage.writeDrafts(NAMESPACE, byteArrayOf(1, 2))
        val tooLarge = ByteArray(AtomicDocumentStorage.MAX_PAYLOAD_BYTES + 1)
        assertSafeFailure(DocumentStorageException.Reason.TOO_LARGE) {
            storage.writeDrafts(NAMESPACE, tooLarge)
        }
        assertSafeFailure(DocumentStorageException.Reason.TOO_LARGE) {
            storage.writeCache(NAMESPACE, tooLarge)
        }
        assertArrayEquals(byteArrayOf(1, 2), storage.readDrafts(NAMESPACE))
        assertNull(storage.readCache(NAMESPACE))
        val atLimit = ByteArray(AtomicDocumentStorage.MAX_PAYLOAD_BYTES).apply {
            this[0] = 7
            this[lastIndex] = 8
        }
        storage.writeCache(NAMESPACE, atLimit)
        assertArrayEquals(atLimit, AtomicDocumentStorage(root).readCache(NAMESPACE))
    }

    @Test fun instancesWithCanonicalRootAliasesSerializeConcurrentTransactions() {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (0..3).map { writer ->
                pool.submit(Callable {
                    val alias = if (writer % 2 == 0) root else File(root, "../store/.")
                    val storage = AtomicDocumentStorage(alias)
                    start.await()
                    repeat(12) {
                        val bytes = ByteArray(4096) { writer.toByte() }
                        storage.writeDrafts(NAMESPACE, bytes)
                        val observed = storage.readDrafts(NAMESPACE)!!
                        assertEquals(4096, observed.size)
                        assertTrue(observed.all { it == observed[0] })
                    }
                })
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            assertEquals(4096, AtomicDocumentStorage(root).readDrafts(NAMESPACE)!!.size)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun openUsesOnlyProvidedContextsNoBackupDocumentsDirectory() {
        val noBackup = File(temporary.root, "no-backup")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = noBackup
        }
        val storage = AtomicDocumentStorage.open(context)
        storage.writeDrafts(NAMESPACE, SECRET.toByteArray())
        assertArrayEquals(SECRET.toByteArray(), AtomicDocumentStorage.open(context).readDrafts(NAMESPACE))
        assertTrue(File(directory(File(noBackup, "documents.v1"), NAMESPACE), "drafts.bin").isFile)
        assertEquals(listOf("no-backup"), temporary.root.listFiles()!!.map { it.name })
    }

    private fun cacheFile() = File(directory(root, NAMESPACE), "cache.bin")
    private fun draftsFile() = File(directory(root, NAMESPACE), "drafts.bin")

    private fun assertSafeFailure(reason: DocumentStorageException.Reason, block: () -> Unit) {
        try {
            block()
            fail("Expected storage failure")
        } catch (error: DocumentStorageException) {
            assertEquals(reason, error.reason)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.stackTraceToString().contains(SECRET))
            assertFalse(error.message!!.contains(NAMESPACE))
        }
    }

    private fun directory(root: File, namespace: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
        for (char in namespace) {
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
        return File(root, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    /**
     * Robolectric 4.16.1's ShadowPosix/ShadowLinux.stat reports mode=0 for nonexistent paths,
     * instead of the real Android ENOENT/ENOTDIR contract. Adapt this OS primitive only;
     * AtomicFile's recovery/transaction algorithm and filesystem/streams remain real.
     */
    @Implements(Os::class)
    class ErrnoAwareOs {
        companion object {
            @Implementation @JvmStatic
            fun stat(path: String): StructStat {
                try {
                    // On Windows, probing a descendant of a plain file otherwise looks missing.
                    var ancestor = File(path).parentFile
                    while (ancestor != null) {
                        try {
                            val attrs = Files.readAttributes(ancestor.toPath(), BasicFileAttributes::class.java)
                            if (!attrs.isDirectory) throw ErrnoException("stat", OsConstants.ENOTDIR)
                        } catch (_: NoSuchFileException) {
                            // A missing directory is legitimate; check its existing ancestors too.
                        }
                        ancestor = ancestor.parentFile
                    }
                    val attrs = Files.readAttributes(File(path).toPath(), BasicFileAttributes::class.java)
                    val mode = when {
                        attrs.isRegularFile -> OsConstants.S_IFREG
                        attrs.isDirectory -> OsConstants.S_IFDIR
                        else -> 0
                    }
                    return StructStat(0, 0, mode, 1, 0, 0, 0, attrs.size(), 0, 0, 0, 0, 0)
                } catch (_: NoSuchFileException) {
                    throw ErrnoException("stat", OsConstants.ENOENT)
                } catch (_: NotDirectoryException) {
                    throw ErrnoException("stat", OsConstants.ENOTDIR)
                } catch (_: AccessDeniedException) {
                    throw ErrnoException("stat", OsConstants.EACCES)
                } catch (_: IOException) {
                    throw ErrnoException("stat", OsConstants.EIO)
                }
            }
        }
    }

    /**
     * Modern AtomicFile's private rename primitive assumes Linux File.renameTo atomically
     * replaces an existing target. Windows File.renameTo instead returns false in that case.
     * Substitute the matching host atomic-move primitive, NOT a delete-then-rename and NOT a
     * fake AtomicFile transaction. The .bak/.new algorithm, fsync and streams still run as-is.
     * Android's helper logs rename failures instead of throwing, which is preserved here too.
     */
    @Implements(value = AtomicFile::class, minSdk = 30)
    class PosixAtomicRename {
        companion object {
            var moveCalls = 0
            var rejectNextMove = false

            @Implementation @JvmStatic
            fun rename(source: File, target: File) {
                moveCalls++
                if (rejectNextMove) {
                    rejectNextMove = false
                    android.util.Log.e("AtomicFile", "Injected atomic rename failure")
                    return
                }
                if (target.isDirectory) target.delete()
                try {
                    Files.move(source.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: IOException) {
                    // Same log-only failure semantics as Android's private rename helper.
                    android.util.Log.e("AtomicFile", "Failed to atomically replace test file")
                }
            }
        }
    }

    private companion object {
        const val NAMESPACE = "https://example.invalid/account/space-id"
        const val SECRET = "private-document-content-never-in-exceptions"
    }
}
