package com.voxarium.remote.acceptance

import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.room.*
import com.voxarium.remote.data.transport.*
import com.voxarium.remote.documents.DocumentStorageTest
import com.voxarium.remote.ui.editor.bridge.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Real production AtomicDocumentStorage/RoomRecoveryStore; Windows POSIX rename shadows from
 * existing suite only adapt host fs behavior. Recreation destroys repository/store objects,
 * not an actual Android process kill (device/lifecycle coverage is explicitly not claimed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,35],shadows=[DocumentStorageTest.ErrnoAwareOs::class,DocumentStorageTest.PosixAtomicRename::class])
class DurableNativeAcceptanceTest {
    @Test fun diskRecoveryAndDraftRecreationAreIsolatedByServerAccountSpace() {
        val root=File(System.getenv("VS78_NATIVE_TEMP"),"durable-"+UUID.randomUUID())
        val endpoint=RemoteEndpoint.create("https://localhost","game")
        val namespace=DocumentNamespace(endpoint.serverUrl,"account-a","space-a")
        val context=EditorContext(7,9,"retired-session")
        val text="---\ntitle: recovery\n---\r\nРусский 🦊\r\n"
        run {
            val storage=AtomicDocumentStorage(root)
            val repository=DocumentsRepository(storage)
            val owner=repository.select(endpoint,namespace)
            repository.saveDraft(repository.commandScope(owner),"nested/note.md",text)
            RoomRecoveryStore(storage).persist(namespace,"nested/note.md",context,RoomSnapshot(text,4),listOf("AAA="))
        }
        repeat(3) {
            val storage=AtomicDocumentStorage(root)
            val repository=DocumentsRepository(storage)
            val owner=repository.select(endpoint,namespace)
            assertFalse(repository.view().synced)
            assertEquals(text,repository.view().drafts["nested/note.md"]!!.content)
            assertNull(repository.nextWrite(repository.commandScope(owner),"nested/note.md"))
            val recovery=RoomRecoveryStore(storage).list(namespace).single()
            assertEquals(text,recovery.snapshot!!.markdown)
            assertEquals(context,recovery.context)
            assertEquals(listOf("AAA="),recovery.raw)
            for(other in listOf(namespace.copy(accountId="account-b"),namespace.copy(spaceId="space-b"),namespace.copy(server="https://other.invalid"))) {
                assertTrue(RoomRecoveryStore(storage).list(other).isEmpty())
            }
        }
        assertTrue(root.walkTopDown().filter { it.isFile }.none { it.name.contains("nested") })
    }

    @Test fun admittedOriginStillRejectsStaleGenerationUnknownSchemaAndDuplicateReply() {
        val codec=RoomEditorCodec();val context=EditorContext(12,14,"current-room")
        codec.encode(EditorCommand.Prepare(context,1,"source"))
        val good="""{"version":1,"documentGeneration":12,"connectionGeneration":14,"sid":"current-room","requestId":1,"event":"prepared","payload":{"clientId":123,"seed":"AAA="}}"""
        fun reject(json:String=good,origin:String=SecureEditorView.ORIGIN,main:Boolean=true) {
            try { decodeEditorMessage(codec,json,origin,main,SecureEditorView.PAGE_URL);fail("Unsafe message admitted") }
            catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {}
        }
        reject(origin="https://appassets.androidplatform.net.evil")
        reject(main=false)
        reject(json=good.replace("\"connectionGeneration\":14","\"connectionGeneration\":13"))
        reject(json=good.replace("\"documentGeneration\":12","\"documentGeneration\":11"))
        reject(json=good.replace("\"version\":1","\"version\":999"))
        reject(json=good.replace("\"seed\":\"AAA=\"","\"seed\":\"AAA=\",\"token\":\"forbidden\""))
        assertTrue(decodeEditorMessage(codec,good,SecureEditorView.ORIGIN,true,SecureEditorView.PAGE_URL) is EditorEvent.Prepared)
        reject()
    }
}
