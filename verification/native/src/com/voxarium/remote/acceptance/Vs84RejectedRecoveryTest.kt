package com.voxarium.remote.acceptance

import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.room.*
import com.voxarium.remote.data.transport.RemoteEndpoint
import com.voxarium.remote.documents.DocumentStorageTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Genuine fullstack Blocked diagnostics, then production Android AtomicFile recovery journal.
 * No synthesized rejected payloads. Cross-layer disk fidelity, not process-kill/power-loss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,35],shadows=[DocumentStorageTest.ErrnoAwareOs::class,DocumentStorageTest.PosixAtomicRename::class])
class Vs84RejectedRecoveryTest {
    @Test fun originalAndRealRejectedEditSurviveAtomicStoreRecreationWithoutReplay() {
        val input=File(requireNotNull(System.getenv("VS84_RECOVERY_INPUT")){"Supply genuine VS84 fullstack recovery.json"})
        val entries=JSONArray(input.readText())
        assertTrue("All five unsafe initial sources + winner/foreign/local/handoff/held ACK",entries.length()>=10)
        val root=File(System.getenv("VS78_NATIVE_TEMP"),"vs84-atomic-"+UUID.randomUUID())
        for(index in 0 until entries.length()){
            val e=entries.getJSONObject(index);val n=e.getJSONObject("namespace")
            val namespace=DocumentNamespace(n.getString("server"),n.getString("accountId"),n.getString("spaceId"))
            val rawArray=e.getJSONArray("raw");val raw=(0 until rawArray.length()).map{rawArray.getString(it)}
            val original=e.getString("source");val path=e.getString("path")
            val context=EditorContext(index.toLong()+1,1,"real-blocked-$index")
            RoomRecoveryStore(AtomicDocumentStorage(root)).persist(namespace,path,context,RoomSnapshot(original,0),raw)
            repeat(3){
                val storage=AtomicDocumentStorage(root)
                val recovered=RoomRecoveryStore(storage).list(namespace).single{it.path==path}
                assertEquals(original,recovered.snapshot!!.markdown);assertEquals(raw,recovered.raw)
                assertEquals(context,recovered.context)
                if(!path.startsWith("unsafe-source")){
                    assertTrue(recovered.raw.isNotEmpty())
                    assertTrue(e.getJSONObject("decoded").getString("merged").contains("REJECTED "))
                }
                val repository=DocumentsRepository(storage)
                val owner=repository.select(RemoteEndpoint.create(namespace.server,namespace.spaceId),namespace)
                assertTrue(repository.view().pending.isEmpty());assertTrue(repository.view().drafts.isEmpty())
                assertNull(repository.nextWrite(repository.commandScope(owner),path))
                assertTrue(RoomRecoveryStore(storage).list(namespace.copy(accountId="other-account")).isEmpty())
            }
        }
    }
}
