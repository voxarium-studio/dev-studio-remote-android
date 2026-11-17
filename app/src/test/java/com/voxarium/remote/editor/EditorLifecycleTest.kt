package com.voxarium.remote.editor

import com.voxarium.remote.*
import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.room.*
import com.voxarium.remote.data.security.*
import com.voxarium.remote.data.transport.*
import com.voxarium.remote.ui.connection.*
import com.voxarium.remote.ui.SessionTestHttp
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

/** Native host/room lifecycle gate: real repository + coordinator + recovery, no server. */
class EditorLifecycleTest {
    private class Worker : AbstractExecutorService() {
        val jobs=ArrayDeque<Runnable>();var stopped=false
        override fun execute(command:Runnable) { check(!stopped);jobs.add(command) }
        fun drain() { while(jobs.isNotEmpty()) jobs.removeFirst().run() }
        override fun shutdown() { stopped=true }
        override fun shutdownNow()=jobs.toMutableList().also { jobs.clear();stopped=true }
        override fun isShutdown()=stopped
        override fun isTerminated()=stopped && jobs.isEmpty()
        override fun awaitTermination(timeout:Long,unit:TimeUnit)=isTerminated
    }
    private class Blob : BlobStorage {
        var bytes:ByteArray?=null
        override fun read()=bytes?.clone()
        override fun write(bytes:ByteArray) { this.bytes=bytes.clone() }
    }
    private class Disk : DocumentStorage {
        val c=mutableMapOf<String,ByteArray>();val d=mutableMapOf<String,ByteArray>()
        var fail=false
        override fun readCache(namespace:String)=c[namespace]?.clone()
        override fun readDrafts(namespace:String)=d[namespace]?.clone()
        override fun writeCache(namespace:String,bytes:ByteArray) { check(!fail);c[namespace]=bytes.clone() }
        override fun writeDrafts(namespace:String,bytes:ByteArray) { check(!fail);d[namespace]=bytes.clone() }
    }
    private class Wire(val observer:(ConnectionEvent)->Unit):SessionConnection {
        val sent=mutableListOf<ClientFrame>();var closed=false
        var result=SendResult.ENQUEUED
        override fun connect(endpoint:RemoteEndpoint,bearer:String,projectId:String,expectedAccountId:String,storageEpoch:String)=1L
        override fun close() { closed=true }
        override fun send(generation:Long,frame:ClientFrame,result:(SendResult)->Unit) { sent+=frame;result(this.result) }
        fun frame(frame:ServerFrame,g:Long=1)=observer(ConnectionEvent.Frame(g,frame))
    }
    private class Harness(val writes:Boolean=true) {
        val worker=Worker();val main=Worker();val disk=Disk()
        val profiles=ProfileStore(Blob())
        val identities=ServerProfileStore(Blob());val creates=CreateIntentStore(Blob())
        val http=SessionTestHttp().apply { account="account";projects=listOf(ProjectSummary("space","Project","a".repeat(32),1,"now")) }
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials=CredentialStore(Blob(),CredentialKeyProvider { key })
        val repo=DocumentsRepository(disk);val recovery=RoomRecoveryStore(disk)
        lateinit var wire:Wire;lateinit var state:RemoteUiState
        var now=0L
        val session=RemoteSession({SessionDependencies(profiles,credentials,repo,recovery,writes,{now},serverProfiles=identities,creates=creates,http=http) { cb -> Wire(cb).also { wire=it } }},worker,main) { state=it }
        lateinit var channel:EditorChannel
        lateinit var context:EditorContext
        var snapshot=RoomSnapshot("Base\n",0)
        var deferFlush=false
        var flush:EditorCommand.Flush?=null
        val commands=mutableListOf<EditorCommand>()
        fun drain() { repeat(30) { worker.drain();main.drain();if(worker.jobs.isEmpty() && main.jobs.isEmpty()) return };error("actor loop") }
        fun online() {
            val credential=credentials.create(Credential.fromToken("vxs_"+"a".repeat(40)))
            val p=SavedProfile.create("Work","https://example.invalid","game",credential,"profile")
            identities.upsert(ServerProfile.create(p.label,p.canonicalServerUrl,p.credentialId,p.id));drain();session.selectProfile(p.id);drain();session.verifyServer();drain();session.refreshProjects();drain();session.selectProject("space");drain();session.connect();drain()
            wire.observer(ConnectionEvent.State(1,ConnectionPhase.READY,null))
            wire.frame(ServerFrame.Hello(2,RemoteUser("account","Alice","#123456"),RemoteSpace("space","game","a".repeat(32)),1))
            wire.frame(ServerFrame.Change("note.md",1,documentHash("Base\n"),false,"Base\n",null))
            wire.frame(ServerFrame.Synced(1));drain()
        }
        fun mount() {
            session.openEditor("note.md",state.documentEpoch);drain()
            channel=state.editor!!
            channel.attach(RoomEditorPort { command ->
                commands+=command;context=command.context
                when(command) {
                    is EditorCommand.Prepare -> channel.event(EditorEvent.Prepared(context,command.requestId,42,"AQ=="))
                    is EditorCommand.Initialize -> channel.event(EditorEvent.Ready(context,command.requestId,snapshot))
                    is EditorCommand.ApplyUpdate -> channel.event(EditorEvent.Applied(context,command.requestId,snapshot))
                    is EditorCommand.Flush -> if(deferFlush) flush=command else channel.event(EditorEvent.Flushed(context,command.requestId,snapshot))
                    is EditorCommand.Dispose -> channel.event(EditorEvent.Disposed(context,command.requestId))
                    else -> Unit
                }
            });drain()
            val join=wire.sent.filterIsInstance<ClientFrame.Join>().last()
            wire.frame(ServerFrame.Joined(join.sid,"note.md","epoch",false,null,true,false,1));drain()
            wire.frame(ServerFrame.Seeded(join.sid,true,null));drain()
        }
        fun type(text:String) {
            snapshot=RoomSnapshot(text,snapshot.generation+1)
            channel.event(EditorEvent.LocalUpdate(context,"AQ=="));channel.event(EditorEvent.Changed(context,snapshot));drain()
        }
        fun tick() { now+=600;session.tickEditor();drain() }
        fun ack(put:ClientFrame.Put) { wire.frame(ServerFrame.Ack(FrameId.Integer(put.id),put.path,put.id+2,documentHash(put.content),false));drain() }
        fun finishFlush() { val c=flush!!;flush=null;channel.event(EditorEvent.Flushed(c.context,c.requestId,snapshot));drain() }
    }
    @Test fun tenCleanEditorBackCyclesDoNotWrite() {
        val h=Harness();h.online()
        repeat(10) { h.mount();h.session.closeDocument();h.drain();assertNull(h.state.editor) }
        assertTrue(h.wire.sent.none { it is ClientFrame.Put || it is ClientFrame.Delete || it is ClientFrame.Update })
        assertEquals(RoomStatus.CLOSED_CONFIRMED,h.state.roomStatus)
    }
    @Test fun backWaitsForFlushAndMatchingAckOfNewestGeneration() {
        val h=Harness();h.online();h.mount();h.type("First\n");h.tick()
        val first=h.wire.sent.filterIsInstance<ClientFrame.Put>().single()
        h.type("Newer\n");h.deferFlush=true;h.session.closeDocument();h.drain()
        assertNotNull(h.state.editor);assertEquals(RoomStatus.CLOSING,h.state.roomStatus)
        h.ack(first);assertNotNull(h.state.editor)
        h.finishFlush()
        val newer=h.wire.sent.filterIsInstance<ClientFrame.Put>().last();assertEquals("Newer\n",newer.content)
        assertNotNull(h.state.editor);h.ack(newer)
        assertNull(h.state.editor);assertEquals(RoomStatus.CLOSED_CONFIRMED,h.state.roomStatus)
        assertEquals("Newer\n",h.repo.view().documents["note.md"]?.content)
    }
    @Test fun backgroundAndRotationDoNotDestroyBeforeCompositionCheckpoint() {
        val h=Harness();h.online();h.mount();h.deferFlush=true;h.type("Before IME\n")
        h.session.backgroundEditor();h.drain()
        assertNotNull(h.state.editor);assertTrue(h.commands.last() is EditorCommand.Flush)
        // A pending composition is allowed to complete during the final flush.
        h.type("Before IME + emoji 😀\n");h.finishFlush()
        val put=h.wire.sent.filterIsInstance<ClientFrame.Put>().last();assertEquals(h.snapshot.markdown,put.content)
        h.ack(put);assertNull(h.state.editor)
        assertEquals(RoomStatus.CLOSED_CONFIRMED,h.state.roomStatus)
        h.session.foregroundEditor();h.drain()
        assertNotNull(h.state.editor)
    }
    @Test fun rendererLossKeepsNewestCheckpointAndNeverOverwritesOriginal() {
        val h=Harness();h.online();h.mount();h.type("Checkpoint\n")
        h.channel.failure();h.drain()
        assertNull(h.state.editor);assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED,h.state.roomStatus)
        h.session.loadRecovery();h.drain()
        assertEquals("Checkpoint\n",h.state.recovery.last().snapshot?.markdown)
        assertEquals("Base\n",h.repo.view().documents["note.md"]?.content)
        assertTrue(h.wire.sent.none { it is ClientFrame.Put })
        val n=h.repo.view().namespace!!
        assertEquals("Checkpoint\n",RoomRecoveryStore(h.disk).list(n).last().snapshot?.markdown)
    }
    @Test fun disconnectWaitsForCloseBarrierAndLateOldEventsCannotRebind() {
        val h=Harness();h.online();h.mount();h.type("Pending\n");h.deferFlush=true
        h.session.disconnect();h.drain();assertFalse(h.wire.closed)
        h.finishFlush();val put=h.wire.sent.filterIsInstance<ClientFrame.Put>().last();h.ack(put)
        assertTrue(h.wire.closed);assertNull(h.state.editor)
        h.channel.event(EditorEvent.Changed(h.context,RoomSnapshot("Stale\n",999)));h.drain()
        assertEquals("Pending\n",h.repo.view().documents["note.md"]?.content)
    }
    @Test fun writeGateRemainsReadOnlyEvenIfPortReportsLocalEdit() {
        val h=Harness(false);h.online();h.mount()
        assertTrue(h.commands.filterIsInstance<EditorCommand.Initialize>().single().readOnly)
        assertEquals(RoomStatus.WRITE_GATE_CLOSED,h.state.roomStatus)
        h.type("Illicit\n")
        assertTrue(h.wire.sent.none { it is ClientFrame.Put || it is ClientFrame.Update })
    }
    @Test fun shutdownTakesPriorityOverPendingBackgroundFlush() {
        val h=Harness();h.online();h.mount();h.type("Final\n");h.deferFlush=true
        h.session.backgroundEditor();h.drain();h.session.close();h.drain()
        assertFalse(h.wire.closed)
        h.finishFlush();val put=h.wire.sent.filterIsInstance<ClientFrame.Put>().last();h.ack(put)
        assertTrue(h.wire.closed);assertTrue(h.worker.isShutdown)
    }
    @Test fun nackTerminalRecoveryCannotBeUpgradedToSavedByBack() {
        val h=Harness();h.online();h.mount();h.type("Rejected\n");h.tick()
        val put=h.wire.sent.filterIsInstance<ClientFrame.Put>().last()
        h.wire.frame(ServerFrame.Nack(FrameId.Integer(put.id),put.path,
            DocumentVersion(9,documentHash("Other\n"),false,"Other\n")));h.drain()
        assertNull(h.state.editor);assertEquals(RoomStatus.RECOVERY_UNCONFIRMED,h.state.roomStatus)
        h.session.closeDocument();h.drain()
        assertEquals(RoomStatus.RECOVERY_UNCONFIRMED,h.state.roomStatus)
        assertEquals("Other\n",h.repo.view().documents["note.md"]?.content)
    }
    @Test fun recoveryHubIncludesDurableDraftsWithoutReplayingThem() {
        val h=Harness();h.online();h.mount();h.type("Local checkpoint\n")
        assertEquals("Local checkpoint\n",h.state.localDrafts.single().content)
        val previews=com.voxarium.remote.ui.editor.recoveryPreviews(h.state)
        assertTrue(previews.any { it.markdown == "Local checkpoint\n" })
        assertTrue(h.wire.sent.none { it is ClientFrame.Put })
    }
    @Test fun editorBacklogOverflowFlushesCompleteModelToRecoveryAndDisconnects() {
        val h=Harness();h.online();h.mount()
        repeat(200) { i ->
            h.snapshot=RoomSnapshot("Burst $i\n",i+1L)
            h.channel.event(EditorEvent.LocalUpdate(h.context,"AQ=="))
            h.channel.event(EditorEvent.Changed(h.context,h.snapshot))
        }
        h.drain();h.session.loadRecovery();h.drain()
        assertNull(h.state.editor);assertTrue(h.wire.closed)
        assertEquals("Burst 199\n",h.state.recovery.last().snapshot?.markdown)
        assertTrue(h.wire.sent.none { it is ClientFrame.Put })
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED,h.state.roomStatus)
    }
    @Test fun rendererDeathDuringOverflowRetainsNewestDeliveredGeneration() {
        val h=Harness();h.online();h.mount();h.deferFlush=true
        repeat(150) { i ->
            h.snapshot=RoomSnapshot("Last $i\n",i+1L)
            h.channel.event(EditorEvent.Changed(h.context,h.snapshot))
        }
        h.drain();h.channel.failure();h.drain();h.session.loadRecovery();h.drain()
        assertEquals("Last 149\n",h.state.recovery.last().snapshot?.markdown)
        assertEquals(RoomStatus.CLOSED_RECOVERY_UNCONFIRMED,h.state.roomStatus)
    }
    @Test fun backCannotReplaceRequiredTransportStopAfterSendRefusal() {
        val h=Harness();h.online();h.mount();h.deferFlush=true;h.wire.result=SendResult.NOT_READY
        h.type("Refused\n");h.session.closeDocument();h.drain()
        assertFalse(h.wire.closed)
        h.finishFlush();assertTrue(h.wire.closed)
        assertEquals(RemoteError.NETWORK,h.state.error)
        assertTrue(h.wire.sent.none { it is ClientFrame.Put })
    }
    @Test fun unsupportedWebviewExitsToNativeReaderWithoutUnsafeFallback() {
        val h=Harness();h.online();h.mount();h.channel.failure(unsupported=true);h.drain()
        assertNull(h.state.editor);assertEquals(RemoteError.EDITOR_UNSUPPORTED,h.state.error)
        assertEquals("Base\n",h.state.documents["note.md"]?.content)
        assertTrue(h.wire.sent.none { it is ClientFrame.Put || it is ClientFrame.Update })
    }
    @Test fun savedProfileCannotReplaceLiveRoomBeforeCheckpointAndAck() {
        val h=Harness();h.online();h.mount();h.type("Old profile edit\n");h.deferFlush=true
        h.session.saveServerProfile("Other","https://other.invalid","vxs_"+"b".repeat(40)) {}
        h.drain();assertEquals("Work",h.state.selectedServer?.name);assertFalse(h.wire.closed)
        assertNull(h.flush) // local Save itself is not a switch or a Flush
        val other=h.state.serverProfiles.single { it.name=="Other" }
        h.session.selectServerProfile(other.localProfileId);h.drain()
        assertEquals("Work",h.state.selectedServer?.name);assertFalse(h.wire.closed)
        h.finishFlush();h.ack(h.wire.sent.filterIsInstance<ClientFrame.Put>().last())
        assertEquals("Other",h.state.selectedServer?.name);assertTrue(h.wire.closed)
        assertNull(h.state.editor);assertTrue(h.state.recovery.isEmpty());assertTrue(h.state.localDrafts.isEmpty())
    }
    @Test fun fatalDiskFailureDuringPendingCloseImmediatelyHaltsButRetainsRenderer() {
        val h=Harness();h.online();h.mount();h.type("Keep me\n");h.deferFlush=true
        var halted=0;h.channel.onHalt {halted++}
        h.session.closeDocument();h.drain();h.disk.fail=true
        h.wire.frame(ServerFrame.Change("note.md",9,documentHash("External\n"),false,"External\n",null));h.drain()
        assertTrue(halted>0);assertEquals(RemoteError.STORAGE,h.state.error)
        assertNotNull(h.state.editor)
        assertTrue(h.commands.none {it is EditorCommand.Dispose})
        assertTrue(h.wire.sent.none {it is ClientFrame.Leave})
    }    @Test fun projectSwitchWaitsForNewestFlushAckAndNeverMovesOldDraftToNewPartition() {
        val h=Harness();h.online();h.mount();h.type("Old partition edit\n");h.deferFlush=true
        h.http.projects=h.http.projects+ProjectSummary("other","Same label","b".repeat(32),1,"now")
        h.session.refreshProjects();h.drain();val oldWire=h.wire;val oldChannel=h.channel;val oldContext=h.context
        h.session.selectProject("other");h.drain()
        assertEquals("space",h.state.selectedProject!!.projectId);assertFalse(oldWire.closed)
        h.finishFlush();val put=oldWire.sent.filterIsInstance<ClientFrame.Put>().last();h.ack(put)
        assertTrue(oldWire.closed);assertEquals("other",h.state.selectedProject!!.projectId)
        assertTrue(h.state.documents.isEmpty());assertTrue(h.state.localDrafts.isEmpty())
        oldChannel.event(EditorEvent.Changed(oldContext,RoomSnapshot("Late old edit\n",999)))
        oldWire.frame(ServerFrame.Ack(FrameId.Integer(put.id),put.path,999,documentHash(put.content),false));h.drain()
        assertTrue(h.state.documents.isEmpty());assertTrue(h.state.localDrafts.isEmpty())
        h.session.connect();h.drain()
        h.wire.frame(ServerFrame.Hello(2,RemoteUser("account","Alice","#123456"),RemoteSpace("other","Label","b".repeat(32)),0))
        h.wire.frame(ServerFrame.Synced(0));h.drain()
        assertTrue(h.wire.sent.all { it is ClientFrame.Sync });assertTrue(h.state.localDrafts.isEmpty())
    }
    @Test fun keyRotationWaitsForRecoveryBarrierAndDoesNotStrandOldAck() {
        val h=Harness();h.online();h.mount();h.type("Final old key edit\n");h.deferFlush=true
        val p=h.state.selectedServer!!;var saved=false
        h.session.replaceServerKey(p.localProfileId,"new-key") { saved=true };h.drain()
        assertFalse(saved);assertEquals(1L,h.identities.snapshot().profiles.single().credentialRevision)
        h.finishFlush();h.ack(h.wire.sent.filterIsInstance<ClientFrame.Put>().last())
        assertTrue(saved);assertTrue(h.wire.closed);assertNull(h.state.editor)
        assertEquals(2L,h.state.selectedServer!!.credentialRevision)
        assertTrue(h.state.documents.isEmpty());assertNull(h.state.selectedProject)
        assertEquals(com.voxarium.remote.VerificationStatus.UNVERIFIED,h.state.verificationStatus)
    }
    @Test fun failedFinalCheckpointBlocksSwitchAndRetainsOldRendererAndIdentity() {
        val h=Harness();h.online();h.mount();h.type("Must remain recoverable\n");h.deferFlush=true
        h.http.projects=h.http.projects+ProjectSummary("other","Other","b".repeat(32),1,"now")
        h.session.refreshProjects();h.drain();h.session.selectProject("other");h.drain()
        h.disk.fail=true;h.finishFlush()
        assertEquals("space",h.state.selectedProject!!.projectId);assertFalse(h.wire.closed)
        assertNotNull(h.state.editor);assertEquals(RemoteError.STORAGE,h.state.error)
        assertTrue(h.commands.none { it is EditorCommand.Dispose })
    }

}
