package com.voxarium.remote.acceptance

import com.voxarium.remote.data.transport.*
import com.voxarium.remote.data.documents.*
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual production RemoteConnection/FrameCodec/DocumentsRepository over HTTPS/WSS to Python Remote.
 * No fake network port. Explicit repository snapshot calls test its policy, NOT editor interop.
 * Test-only exact certificate trust keeps normal hostname verification enabled.
 */
class NativeRemoteAcceptanceTest {
    private val config = JSONObject(File(requireNotNull(System.getenv("VS78_NATIVE_CONFIG")) { "Run verification/native/run.py" }).readText())
    private val run = File(config.getString("run"))
    private val cert = File(config.getString("cert")).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    private val tls = HandshakeCertificates.Builder().addTrustedCertificate(cert).build()
    private val client = RemoteConnection.defaultClient().newBuilder().sslSocketFactory(tls.sslSocketFactory(),tls.trustManager).build()
    private val peers = mutableListOf<Peer>()
    private val otherClients = mutableListOf<OkHttpClient>()

    /** Real disk boundary; production AtomicFile behavior is separately covered by Robolectric suites. */
    private class Disk(private val root: File): DocumentStorage {
        init { root.mkdirs() }
        private fun file(n: String,k: String) = File(root,documentHash(n)+"-"+k)
        override fun readCache(namespace: String) = file(namespace,"cache").takeIf { it.exists() }?.readBytes()
        override fun readDrafts(namespace: String) = file(namespace,"draft").takeIf { it.exists() }?.readBytes()
        override fun writeCache(namespace: String,bytes: ByteArray) { file(namespace,"cache").writeBytes(bytes) }
        override fun writeDrafts(namespace: String,bytes: ByteArray) { file(namespace,"draft").writeBytes(bytes) }
    }
    private inner class Peer(val endpoint: RemoteEndpoint, val storage: DocumentStorage?=null, val http: OkHttpClient=client) {
        val queue=LinkedBlockingQueue<ConnectionEvent>()
        val history=CopyOnWriteArrayList<ConnectionEvent>()
        val repo=storage?.let { DocumentsRepository(it) }
        val owner=repo?.select(endpoint)
        val c=RemoteConnection(http,ConnectionTiming(5000,5000,100,300,{0})) { event ->
            repo?.onEvent(owner!!,event)?.forEach { effect ->
                cSend(effect)
            }
            history.add(event);queue.offer(event)
        }
        var generation=0L
        private fun cSend(effect: DocumentSend) { c.send(effect.connectionGeneration,effect.frame) { result -> repo!!.onSendResult(owner!!,effect.connectionGeneration,(effect.frame as? ClientFrame.Put)?.id,result) } }
        fun start(key: String="alice"): Peer { generation=c.connect(endpoint,config.getString(key));return this }
        fun event(timeout: Long=10,predicate:(ConnectionEvent)->Boolean): ConnectionEvent {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout)
            while(System.nanoTime()<deadline) {
                val e=queue.poll(50,TimeUnit.MILLISECONDS) ?: continue
                if(predicate(e)) return e
            }
            error("Expected event missing: "+history.joinToString { if(it is ConnectionEvent.Frame) it.frame.javaClass.simpleName else it.toString() })
        }
        inline fun <reified T: ServerFrame> frame(noinline match:(T)->Boolean={true}): T = (event { it is ConnectionEvent.Frame && it.frame is T && match(it.frame) } as ConnectionEvent.Frame).frame as T
        fun send(f: ClientFrame,g:Long=generation):SendResult {
            val q=LinkedBlockingQueue<SendResult>();c.send(g,f) { q.offer(it) }
            return q.poll(5,TimeUnit.SECONDS) ?: error("No send-result")
        }
        fun write(e:DocumentSend) { assertTrue(repo!!.isCurrent(e));assertEquals(SendResult.ENQUEUED,send(e.frame,e.connectionGeneration)) }
        fun ready():ServerFrame.Hello=frame<ServerFrame.Hello>()
        fun sync(since:Long=0):ServerFrame.Synced { assertEquals(SendResult.ENQUEUED,send(ClientFrame.Sync(since)));return frame() }
    }
    private fun peer(space:String=UUID.randomUUID().toString(),storage:DocumentStorage?=null,prefix:String="",http:OkHttpClient=client):Peer = Peer(RemoteEndpoint.create(config.getString("url")+prefix,space),storage,http).also { peers+=it }
    private fun ack(p:Peer,id:Long)=p.frame<ServerFrame.Ack> { it.id==FrameId.Integer(id) }
    private fun awaitFile(name:String) { val d=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!File(run,name).exists()&&System.nanoTime()<d) Thread.sleep(20);assertTrue("Missing $name",File(run,name).exists()) }
    @After fun cleanup() {
        File(run,"hold-ack").delete();File(run,"ack-held").delete()
        peers.forEach { it.c.close() }
        (otherClients+client).forEach { it.dispatcher.executorService.shutdown();it.connectionPool.evictAll() }
    }

    @Test fun realRemoteFullIncrementalTombstonesCasAndIdempotence() {
        val space=UUID.randomUUID().toString();val a=peer(space).start();val b=peer(space).start("bob")
        a.ready();b.ready();a.sync();b.sync()
        assertEquals(SendResult.ENQUEUED,a.send(ClientFrame.Put(1,"a.md","exact\r\n",0)))
        val first=ack(a,1);assertEquals(documentHash("exact\r\n"),first.hash)
        val change=b.frame<ServerFrame.Change>();assertEquals("exact\r\n",change.content)
        b.send(ClientFrame.Put(2,"a.md","stale",0));val nack=b.frame<ServerFrame.Nack>();assertEquals(first.rev,nack.current!!.rev)
        b.send(ClientFrame.Put(3,"a.md","exact\r\n",0));assertEquals(first.rev,ack(b,3).rev)
        a.send(ClientFrame.Delete(4,"a.md",first.rev));val deleted=ack(a,4);assertTrue(deleted.deleted)
        val tomb=b.frame<ServerFrame.Change>();assertTrue(tomb.deleted);assertNull(tomb.content)
        a.send(ClientFrame.Put(5,"a.md","must not restore",0));assertTrue(a.frame<ServerFrame.Nack>().current!!.deleted)
        val c=peer(space,Disk(File(run,"mirror-"+space))).start();c.ready();c.frame<ServerFrame.Synced>()
        assertTrue(c.repo!!.view().documents["a.md"]!!.deleted)
        assertEquals(deleted.rev,c.repo.view().cursor)
        a.send(ClientFrame.Put(6,"next.md","next",0));val next=ack(a,6)
        c.frame<ServerFrame.Change> { it.path=="next.md" }
        c.c.disconnect();c.event { it is ConnectionEvent.State&&it.phase==ConnectionPhase.STOPPED }
        val replay=peer(space,c.storage).start();replay.ready();replay.frame<ServerFrame.Synced>()
        assertEquals(next.rev,replay.repo!!.view().documents["next.md"]!!.rev)
        assertTrue(replay.repo.view().documents["a.md"]!!.deleted)
    }

    @Test fun realAckHeldNewerTypingSurvivesAndRestartKeepsDraft() {
        val p=peer(storage=Disk(File(run,"typing-"+UUID.randomUUID()))).start();p.ready();p.frame<ServerFrame.Synced>()
        val r=p.repo!!;val scope=r.commandScope(p.owner!!)
        r.queueCreate(scope,"typing.md","first");val sent=r.nextWrite(scope,"typing.md")!!
        File(run,"hold-ack").writeText("hold")
        p.write(sent);awaitFile("ack-held")
        r.saveDraft(scope,"typing.md","first + newer 🦊 русский")
        assertEquals("first",r.view().pending["typing.md"]!!.content)
        File(run,"hold-ack").delete();ack(p,(sent.frame as ClientFrame.Put).id)
        assertEquals("first",r.view().documents["typing.md"]!!.content)
        assertEquals("first + newer 🦊 русский",r.view().drafts["typing.md"]!!.content)
        val ns=r.view().namespace!!;p.c.disconnect();p.event { it is ConnectionEvent.State&&it.phase==ConnectionPhase.STOPPED }
        val recreated=DocumentsRepository(p.storage!!);val owner=recreated.select(p.endpoint,ns)
        assertEquals("first + newer 🦊 русский",recreated.view().drafts["typing.md"]!!.content)
        assertNull(recreated.nextWrite(recreated.commandScope(owner),"typing.md"))
        val hidden=DocumentsRepository(p.storage);hidden.select(p.endpoint,ns.copy(accountId="another-account"))
        assertTrue(hidden.view().documents.isEmpty());assertTrue(hidden.view().drafts.isEmpty())
    }

    @Test fun realLostAckResolvesBySyncWithoutReplayingOldWrite() {
        val space=UUID.randomUUID().toString();val disk=Disk(File(run,"lost-ack-"+space))
        val p=peer(space,disk).start();p.ready();p.frame<ServerFrame.Synced>()
        val r=p.repo!!;val scope=r.commandScope(p.owner!!)
        r.queueCreate(scope,"lost.md","server committed");val effect=r.nextWrite(scope,"lost.md")!!
        File(run,"hold-ack").writeText("hold");p.write(effect);awaitFile("ack-held")
        r.saveDraft(scope,"lost.md","new local while ack lost")
        p.c.disconnect();p.event { it is ConnectionEvent.State&&it.phase==ConnectionPhase.STOPPED }
        assertTrue(r.view().pending.containsKey("lost.md"))
        File(run,"hold-ack").delete()
        val fresh=peer(space,disk).start();fresh.ready();fresh.frame<ServerFrame.Synced>()
        val v=fresh.repo!!.view()
        assertEquals("server committed",v.documents["lost.md"]!!.content)
        assertTrue(v.pending.isEmpty())
        assertTrue(v.recoveries.any { it.content=="new local while ack lost" })
        val sent=File(run,"wire.jsonl").readLines().map { JSONObject(it) }.count { it.optString("direction")=="in" && it.optString("t")=="put" && it.optString("path")=="lost.md" }
        assertEquals("Reconnect must not replay the old put",1,sent)
    }

    @Test fun realRoomExternalResetInvalidatesEpochGrantAndDisconnectFencesSends() {
        val space=UUID.randomUUID().toString();val p=peer(space,Disk(File(run,"epoch-"+space))).start();val b=peer(space).start("bob")
        p.ready();p.frame<ServerFrame.Synced>();b.ready();b.sync()
        b.send(ClientFrame.Put(1,"epoch.md","source",0));val initial=ack(b,1);p.frame<ServerFrame.Change>()
        p.send(ClientFrame.Join("old-sid","epoch.md",123,initial.rev));val old=p.frame<ServerFrame.Joined>()
        assertFalse(old.seeded);p.send(ClientFrame.Seed("old-sid","AAA="));assertTrue(p.frame<ServerFrame.Seeded>().ok)
        val r=p.repo!!;val grant=r.confirmRoomReady(p.owner!!,p.generation,"old-sid",old.epoch)
        r.queueRoomSnapshot(grant,"unsaved room snapshot")
        b.send(ClientFrame.Put(2,"epoch.md","external",initial.rev));val replaced=ack(b,2)
        p.frame<ServerFrame.Reset>();assertEquals("external",r.view().documents["epoch.md"]!!.content)
        try { r.queueRoomSnapshot(grant,"stale overwrite");fail("Old grant must be fenced") } catch(_:DocumentRepositoryException) {}
        assertTrue(r.view().recoveries.any { it.content=="unsaved room snapshot" })
        p.send(ClientFrame.Join("new-sid","epoch.md",124,replaced.rev));val fresh=p.frame<ServerFrame.Joined>()
        assertNotEquals(old.epoch,fresh.epoch)
        p.c.disconnect();p.event { it is ConnectionEvent.State&&it.phase==ConnectionPhase.STOPPED }
        assertEquals(SendResult.STALE_GENERATION,p.send(ClientFrame.Update("old-sid","AAA=")))
        val next=p.c.connect(p.endpoint,config.getString("alice"));val hello=p.ready();assertTrue(next>p.generation)
        assertEquals(hello.space.id,r.view().namespace!!.spaceId)
        assertEquals(SendResult.STALE_GENERATION,p.send(ClientFrame.Put(3,"epoch.md","stale",replaced.rev),p.generation))
    }

    @Test fun realMeAndHelloUnknownProtocolStopBeforeSyncOrWrite() {
        for(prefix in listOf("/future-me","/future-hello")) {
            val p=peer(prefix=prefix).start()
            p.event { it is ConnectionEvent.State&&it.phase==ConnectionPhase.STOPPED&&it.problem==ConnectionProblem.PROTOCOL_MISMATCH }
            assertNotEquals(SendResult.ENQUEUED,p.send(ClientFrame.Sync(0)))
            assertNotEquals(SendResult.ENQUEUED,p.send(ClientFrame.Put(1,"a.md","no",0)))
            assertTrue(p.history.none { it is ConnectionEvent.Frame })
        }
    }

    @Test fun realRemoteRevokedActiveKeyIsTerminalAndDiskDraftSurvives() {
        val p=peer(storage=Disk(File(run,"revoked-drafts"))).start("revoked");p.ready();p.frame<ServerFrame.Synced>()
        val r=p.repo!!;r.saveDraft(r.commandScope(p.owner!!),"local.md","keep revoked draft")
        File(run,"revoke").writeText("revoke disposable user only")
        // A new /me request makes wrapper apply the admin Store.revoke_user; actual 30s watcher closes socket.
        val trigger=peer().start("revoked")
        trigger.event { it is ConnectionEvent.State&&it.phase==ConnectionPhase.STOPPED&&it.problem==ConnectionProblem.HTTP_UNAUTHORIZED }
        p.event(40) { it is ConnectionEvent.State&&it.phase==ConnectionPhase.STOPPED&&it.problem==ConnectionProblem.KEY_REJECTED }
        assertEquals("keep revoked draft",r.view().drafts["local.md"]!!.content)
        assertNotEquals(SendResult.ENQUEUED,p.send(ClientFrame.Sync(0)))
        assertFalse(p.history.toString().contains(config.getString("revoked")))
    }

    @Test fun actualRemoteCertificateRejectedByProductionTrustAndHostnameVerifier() {
        val untrusted=RemoteConnection.defaultClient();otherClients+=untrusted
        val a=peer(http=untrusted).start();a.event { it is ConnectionEvent.State&&it.phase==ConnectionPhase.STOPPED&&it.problem==ConnectionProblem.TLS }
        assertTrue(a.history.none { it is ConnectionEvent.Frame })
        val b=Peer(RemoteEndpoint.create(config.getString("url").replace("localhost","127.0.0.1"),UUID.randomUUID().toString()),null,client).also { peers+=it }.start()
        b.event { it is ConnectionEvent.State&&it.phase==ConnectionPhase.STOPPED&&it.problem==ConnectionProblem.TLS }
        assertTrue(b.history.none { it is ConnectionEvent.Frame })
    }
}
