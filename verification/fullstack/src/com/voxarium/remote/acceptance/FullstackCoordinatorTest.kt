package com.voxarium.remote.acceptance

import com.voxarium.remote.data.transport.*
import com.voxarium.remote.data.documents.*
import com.voxarium.remote.data.room.*
import com.voxarium.remote.ui.editor.bridge.RoomEditorCodec
import okhttp3.tls.HandshakeCertificates
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.voxarium.remote.documents.DocumentStorageTest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Production native coordinator, repository, transport AND browser RoomRuntime in one vertical.
 * The only host responsibilities are ordered effect execution and command/event HTTP forwarding.
 * No manual queueRoomSnapshot/confirmRoomReady/save calls; no fabricated EditorEvent or ACK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[DocumentStorageTest.ErrnoAwareOs::class,DocumentStorageTest.PosixAtomicRename::class])
class FullstackCoordinatorTest {
    private val config=JSONObject(File(requireNotNull(System.getenv("VS78_NATIVE_CONFIG"))).readText())
    private val run=File(config.getString("run"))
    private val browser=JSONObject(File(run,"browser-service.json").readText()).getString("url")
    private val cert=File(config.getString("cert")).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    private val tls=HandshakeCertificates.Builder().addTrustedCertificate(cert).build()
    private val client=RemoteConnection.defaultClient().newBuilder().sslSocketFactory(tls.sslSocketFactory(),tls.trustManager).build()
    private val space="fullstack-"+UUID.randomUUID()
    private val endpoint=RemoteEndpoint.create(config.getString("url"),space)
    private val jobs=LinkedBlockingQueue<()->Unit>()
    private val sends=mutableListOf<ClientFrame>()
    private val statuses=mutableListOf<RoomStatus>()
    private val nativeEvents=JSONArray()
    private val observedEditorEvents=mutableListOf<Pair<String,EditorEvent>>()
    private val observedFrames=mutableListOf<ServerFrame>()
    private val observedConnectionEvents=mutableListOf<ConnectionEvent>()
    private val rows=JSONArray()
    private val actor=Thread.currentThread()
    private val storageRoot=File(run,"documents")
    private val storage=AtomicDocumentStorage(storageRoot)
    private val repo=DocumentsRepository(storage)
    private val owner=repo.select(endpoint)
    private val recover=RoomRecoveryStore(storage)
    // Deliberately test-only policy exercise. BuildConfig/APK gate remains untouched.
    private val co=RoomCoordinator(repo,owner,recover,writesEnabled=true,nowMillis={System.nanoTime()/1_000_000})
    private val codec=RoomEditorCodec()
    private val connection=RemoteConnection(client,ConnectionTiming(5000,5000,100,300,{0})) { e-> jobs.offer {
        observedConnectionEvents+=e
        if(e is ConnectionEvent.Frame)observedFrames+=e.frame
        effect(co.onConnectionEvent(e))
    } }
    private val controlEvents=LinkedBlockingQueue<ConnectionEvent>()
    private val control=RemoteConnection(client,ConnectionTiming(5000,5000,100,300,{0})) { e->controlEvents.offer(e) }
    private var controlGeneration=0L
    private var id=0L
    private fun http(route:String,payload:String="{}"):String {
        val c=URL(browser+route).openConnection() as HttpURLConnection
        c.requestMethod="POST";c.doOutput=true;c.connectTimeout=10000;c.readTimeout=20000;c.setRequestProperty("Content-Type","application/json")
        c.outputStream.use{it.write(payload.toByteArray(Charsets.UTF_8))}
        val code=c.responseCode;val text=(if(code==200)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};c.disconnect()
        check(code==200){text};return text
    }
    private fun action(op:String,vararg fields:Pair<String,Any>):String {val o=JSONObject().put("op",op);fields.forEach{o.put(it.first,it.second)};return http("/action",o.toString())}
    private fun snapshot()=JSONObject(action("snapshot"))
    private fun effect(effects:List<RoomEffect>){
        check(Thread.currentThread()===actor)
        for(e in effects)when(e){
            is RoomEffect.Status->statuses+=e.value
            is RoomEffect.Editor->http("/command",codec.encode(e.command))
            is RoomEffect.Network->{
                check(co.isCurrent(e)){"Unexpected stale network effect"};sends+=e.frame
                connection.send(e.generation,e.frame){r->jobs.offer{effect(co.onSendResult(e,r))}}
            }
        }
    }
    private fun pump(){
        repeat(100){val job=jobs.poll()?:return@repeat;job()}
        val events=JSONArray(http("/events"))
        for(i in 0 until events.length()){
            val raw=events.getJSONObject(i);nativeEvents.put(raw)
            val event=codec.decode(raw.toString())
            observedEditorEvents+=raw.toString() to event
            effect(co.onEditorEvent(event))
        }
        effect(co.tick())
    }
    private fun until(message:String,timeout:Long=15000,condition:()->Boolean){
        val deadline=System.nanoTime()+timeout*1_000_000
        while(System.nanoTime()<deadline){pump();if(condition())return;Thread.sleep(20)}
        error("Timeout $message, statuses=$statuses sends="+sends.map{it.javaClass.simpleName})
    }
    private inline fun <reified T:ServerFrame> controlFrame():T{
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<deadline){val e=controlEvents.poll(50,TimeUnit.MILLISECONDS);if(e is ConnectionEvent.Frame && e.frame is T)return e.frame as T}
        error("Control missing ${T::class.java.simpleName}")
    }
    private fun controlSend(f:ClientFrame){val q=LinkedBlockingQueue<SendResult>();control.send(controlGeneration,f){q.offer(it)};assertEquals(SendResult.ENQUEUED,q.poll(5,TimeUnit.SECONDS))}
    private fun put(path:String,content:String,rev:Long=0):ServerFrame.Ack {controlSend(ClientFrame.Put(++id,path,content,rev));return controlFrame()}
    private fun open(path:String){val n=statuses.size;effect(co.open(path));until("native ready"){statuses.drop(n).contains(RoomStatus.EDITING)}}
    private fun close(){val n=statuses.size;effect(co.close());until("confirmed native flush/dispose/leave"){!co.hasActiveEditor};assertTrue(statuses.drop(n).contains(RoomStatus.CLOSED_CONFIRMED))}
    private fun studio(path:String,source:String)=action("openStudio","path" to path,"source" to source,"space" to space)
    private fun writeCount()=sends.count{it is ClientFrame.Put||it is ClientFrame.Delete}
    private fun row(name:String,detail:JSONObject){rows.put(JSONObject().put("name",name).put("status","PASS").put("detail",detail))}
    private fun settle(ms:Long=700){val end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);while(System.nanoTime()<end){pump();Thread.sleep(20)}}
    private fun blockedSince(start:Int)=observedEditorEvents.drop(start).any{it.second is EditorEvent.Blocked}
    private fun recovery(path:String,original:String,rejected:Boolean):JSONObject {
        val namespace=repo.view().namespace!!
        // New storage/recovery objects reread disk bytes; never just inspect the in-memory event.
        val entries=RoomRecoveryStore(AtomicDocumentStorage(storageRoot)).list(namespace).filter{it.path==path}
        val entry=entries.last{it.snapshot?.markdown==original && (!rejected||it.raw.isNotEmpty())}
        val raw=JSONArray(entry.raw)
        val decoded=JSONObject(action("decodeRecovery","raw" to raw))
        if(rejected)assertTrue("Raw recovery must retain exact rejected colored word + space",decoded.getString("merged").contains("REJECTED "))
        val result=JSONObject().put("path",path).put("source",original).put("snapshot",entry.snapshot!!.markdown).put("raw",raw).put("decoded",decoded)
            .put("namespace",JSONObject().put("server",namespace.server).put("accountId",namespace.accountId).put("spaceId",namespace.spaceId))
        val file=File(run,"recovery.json");val array=if(file.exists())JSONArray(file.readText())else JSONArray();array.put(result);file.writeText(array.toString(2))
        return result
    }

    private fun failClosedScenarios(){
        val unsafe=listOf("A [nested [emphasis]{.blue} span]{.gray}.\n","[a ]{.red}[b]{.blue} tail\n","head [ a]{.red}\n","[a\t]{.red} next\n","[a\rb]{.red}\n")
        for((index,source) in unsafe.withIndex()){
            val path="unsafe-source-$index.md";val baseline=put(path,source)
            until("unsafe source sync"){repo.view().documents[path]?.rev==baseline.rev}
            val start=sends.size;val eventStart=observedEditorEvents.size;effect(co.open(path))
            until("source refused before join"){blockedSince(eventStart)&&!co.hasActiveEditor}
            assertTrue(sends.drop(start).none{it is ClientFrame.Join||it is ClientFrame.Seed||it is ClientFrame.Update||it is ClientFrame.Put||it is ClientFrame.Delete})
            assertEquals(source,repo.view().documents[path]!!.content);assertEquals(baseline.rev,repo.view().documents[path]!!.rev)
            assertEquals(documentHash(source),repo.view().documents[path]!!.hash)
            assertTrue(observedEditorEvents.drop(eventStart).none{it.second is EditorEvent.Ready||it.second is EditorEvent.Changed||it.second is EditorEvent.Flushed})
            recovery(path,source,false);effect(co.close());settle(80)
        }
        row("unsafe initial nested/boundary/tab/bare-CR source blocked before real Join/Seed",JSONObject().put("cases",unsafe.size).put("zeroNativePublication",true))

        val safe="a b.\n"
        for(mode in listOf("winner","foreign","local","handoff")){
            val path="unsafe-$mode.md";val baseline=put(path,safe)
            until("safe source sync"){repo.view().documents[path]?.rev==baseline.rev}
            if(mode=="local"){open(path);studio(path,safe)} else studio(path,safe)
            if(mode=="winner"){action("unsafeStudio");Thread.sleep(100)} else if(mode!="local")open(path)
            val start=sends.size;val eventStart=observedEditorEvents.size
            if(mode=="winner")effect(co.open(path))
            else action(if(mode=="local"||mode=="handoff")"unsafeNative" else "unsafeStudio")
            if(mode=="handoff")action("closeStudio") // Real server scribe event races blocked handling.
            until("$mode unsafe block/disposal"){blockedSince(eventStart)&&!co.hasActiveEditor}
            if(mode=="handoff"){
                val sid=observedEditorEvents.drop(eventStart).first{it.second is EditorEvent.Blocked}.second.context.sid
                until("actual handoff frame captured"){observedConnectionEvents.any{it is ConnectionEvent.Frame&&it.frame is ServerFrame.Scribe&&it.frame.sid==sid}}
                val actual=observedConnectionEvents.last{it is ConnectionEvent.Frame&&it.frame is ServerFrame.Scribe&&it.frame.sid==sid}
                // Replay the EXACT late native callback after disposal, not a manufactured Scribe.
                effect(co.onConnectionEvent(actual))
            }
            val prohibited=sends.drop(start).filter{it is ClientFrame.Update||it is ClientFrame.Put||it is ClientFrame.Delete}
            assertTrue("No rejected update or materialization for $mode",prohibited.isEmpty())
            assertTrue(observedEditorEvents.drop(eventStart).none{it.second is EditorEvent.Changed||it.second is EditorEvent.Flushed})
            if(mode=="winner")assertTrue(observedEditorEvents.drop(eventStart).none{it.second is EditorEvent.Ready})
            recovery(path,safe,true)
            action("closeStudio");effect(co.close());settle()
            assertTrue(sends.drop(start).none{it is ClientFrame.Put||it is ClientFrame.Delete||it is ClientFrame.Update})
            assertEquals(safe,repo.view().documents[path]!!.content);assertEquals(baseline.rev,repo.view().documents[path]!!.rev);assertEquals(documentHash(safe),repo.view().documents[path]!!.hash)
            row("unsafe $mode rejected through real transport/coordinator; durable raw retained",JSONObject().put("originalRev",baseline.rev).put("noNativeUpdatePutDelete",true).put("handoffRace",mode=="handoff").put("actualLateHandoffCallbackReplayed",mode=="handoff"))
        }

        val path="unsafe-late-ack.md";val baseline=put(path,safe);until("late ACK source"){repo.view().documents[path]?.rev==baseline.rev};open(path);studio(path,safe)
        File(run,"hold-ack").writeText("hold");File(run,"ack-held").delete()
        action("editNative","text" to "SAFE ");until("actual safe committed ACK held"){File(run,"ack-held").exists()}
        val committed=repo.view().pending[path]!!.content!!;assertTrue(committed.contains("SAFE "))
        val start=sends.size;val eventStart=observedEditorEvents.size
        action("unsafeNative");until("unsafe blocked while real ACK withheld"){blockedSince(eventStart)&&!co.hasActiveEditor}
        recovery(path,committed,true)
        File(run,"hold-ack").delete();action("closeStudio");effect(co.close());settle(1200)
        assertTrue(sends.drop(start).none{it is ClientFrame.Put||it is ClientFrame.Delete||it is ClientFrame.Update})
        assertTrue(observedEditorEvents.drop(eventStart).none{it.second is EditorEvent.Changed||it.second is EditorEvent.Flushed})
        assertEquals(committed,repo.view().documents[path]!!.content);assertFalse(repo.view().documents[path]!!.content!!.contains("REJECTED"))
        row("unsafe local block fences late real committed ACK and close",JSONObject().put("safeCommitted",committed).put("noRejectedPublication",true))

        for((name,source) in mapOf("ordinary" to "Plain Русский 🦊 é CJK漢字.\n","safe-color" to "[one two]{.red} [three four]{.blue}\n")){
            val path="cycle-$name.md";val baseline=put(path,source);until("cycle initial sync"){repo.view().documents[path]?.rev==baseline.rev}
            var persisted=source;var expected=if(name=="ordinary")"Plain Русский 🦊 é CJK漢字." else "one two three four"
            repeat(3){cycle->
                open(path);studio(path,persisted)
                var visible=JSONObject(action("visible"));assertEquals(expected,visible.getString("native"));assertEquals(expected,visible.getString("studio"))
                val oldRev=repo.view().documents[path]!!.rev
                action("editNative","text" to "X");expected="X"+expected
                if(name=="safe-color")action("safeColorNative")
                until("real cycle edit materialization ACK"){repo.view().documents[path]!!.rev>oldRev&&repo.view().pending[path]==null&&snapshot().getString("studio")==repo.view().documents[path]!!.content}
                close();action("closeStudio");settle(80)
                persisted=repo.view().documents[path]!!.content!!
                row("$name real ACK/last-leave/reseed cycle $cycle",JSONObject().put("markdown",persisted).put("expectedVisible",expected).put("rev",repo.view().documents[path]!!.rev))
            }
            open(path);studio(path,persisted);val visible=JSONObject(action("visible"));assertEquals(expected,visible.getString("native"));assertEquals(expected,visible.getString("studio"));close();action("closeStudio")
        }
    }

    @Test fun productionCoordinatorThroughTypedBrowserSeamAndRealTlsRemote(){
        var failure:Throwable?=null
        try{
            assertFalse("This harness must not arm the APK write gate",com.voxarium.remote.BuildConfig.EDITOR_WRITES_ENABLED)
            controlGeneration=control.connect(endpoint,config.getString("bob"));controlFrame<ServerFrame.Hello>();controlSend(ClientFrame.Sync(0));controlFrame<ServerFrame.Synced>()
            connection.connect(endpoint,config.getString("alice"));until("repository sync"){repo.view().synced}
            val corpus=linkedMapOf("empty" to "","table" to "| A | B |\n| :--- | ---: |\n| 1 | 2 |\n","list" to "- [x] task\n- plain\n","normalization" to "#### Heading\n\n*one*","envelope" to "---\r\ny: \"raw\" # keep\r\n---\r\n\r\nText without final newline")
            val initial=corpus.mapValues{(name,source)->put("clean-$name.md",source)}
            until("all clean fixtures mirrored"){initial.all{(n,a)->repo.view().documents["clean-$n.md"]?.rev==a.rev}}
            val before=writeCount();val localBefore=nativeEvents.length()
            repeat(2){for((name,source) in corpus){
                val path="clean-$name.md";open(path);studio(path,source)
                val pair=snapshot();assertEquals(pair.getJSONObject("native").getString("markdown"),pair.getString("studio"))
                close();action("closeStudio")
                assertEquals(initial[name]!!.rev,repo.view().documents[path]!!.rev);assertEquals(documentHash(source),repo.view().documents[path]!!.hash)
            }}
            assertEquals(before,writeCount())
            assertTrue((localBefore until nativeEvents.length()).none{nativeEvents.getJSONObject(it).getString("event") in setOf("changed","localUpdate","blocked")})
            controlSend(ClientFrame.Sync(0));val finalChanges=mutableMapOf<String,ServerFrame.Change>()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);var synced=false
            while(System.nanoTime()<deadline&&!synced){val e=controlEvents.poll(50,TimeUnit.MILLISECONDS);if(e is ConnectionEvent.Frame){if(e.frame is ServerFrame.Change)finalChanges[e.frame.path]=e.frame;if(e.frame is ServerFrame.Synced)synced=true}}
            assertTrue(synced);for((name,source)in corpus){val remote=finalChanges["clean-$name.md"]!!;assertEquals(source,remote.content);assertEquals(initial[name]!!.rev,remote.rev);assertEquals(documentHash(source),remote.hash)}
            row("10 production native open/flush/close with reference Studio",JSONObject().put("opens",10).put("zeroPutDelete",true).put("zeroLocalUpdates",true).put("unchangedRemoteRevHashContent",true).put("fixtures",JSONArray(corpus.keys)))

            val path="live.md";val source="One.\n\nTwo.\n";val original=put(path,source)
            until("live source"){repo.view().documents[path]?.rev==original.rev};open(path);studio(path,source)
            val start=sends.size;action("editNative","text" to "NATIVE ")
            until("real local update and acknowledged native live scribe put"){repo.view().documents[path]?.content?.contains("NATIVE ")==true&&repo.view().pending[path]==null}
            val localFrames=sends.drop(start);assertTrue(localFrames.any{it is ClientFrame.Update});val firstPut=localFrames.filterIsInstance<ClientFrame.Put>().single();assertNotNull(firstPut.live)
            until("Studio received native edit"){snapshot().getString("studio").contains("NATIVE ")}
            row("real local editor change → native Update → live PUT/ACK",JSONObject().put("rev",repo.view().documents[path]!!.rev).put("liveSid",firstPut.live).put("studioConverged",true))

            File(run,"hold-ack").writeText("hold");File(run,"ack-held").delete()
            action("editNative","text" to "FIRST ");until("real server ACK held"){File(run,"ack-held").exists()}
            val oldPending=repo.view().pending[path]!!.content!!;assertTrue(oldPending.contains("FIRST "))
            action("editNative","text" to "NEWER ");until("newer browser generation checkpoint"){repo.view().drafts[path]?.content?.contains("NEWER ")==true}
            assertEquals(oldPending,repo.view().pending[path]!!.content)
            File(run,"hold-ack").delete()
            until("newer typing eventually acknowledged"){repo.view().documents[path]?.content?.contains("NEWER ")==true&&repo.view().pending[path]==null}
            assertTrue(repo.view().documents[path]!!.content!!.contains("FIRST "));until("Studio newer convergence"){snapshot().getString("studio")==repo.view().documents[path]!!.content}
            row("typing during actual held ACK preserves newer generation",JSONObject().put("olderSnapshot",oldPending).put("final",repo.view().documents[path]!!.content).put("rev",repo.view().documents[path]!!.rev))
            close();action("closeStudio")

            val hand="handoff.md";val h=put(hand,source);until("handoff source"){repo.view().documents[hand]?.rev==h.rev}
            studio(hand,source);open(hand) // Studio is the real server's original scribe.
            val count=writeCount();action("editStudio","text" to "FOREIGN ")
            until("foreign update merged by production ApplyUpdate"){snapshot().getJSONObject("native").getString("markdown").contains("FOREIGN ")}
            val pause=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(800);while(System.nanoTime()<pause){pump();Thread.sleep(20)}
            assertEquals(count,writeCount());assertEquals(source,repo.view().documents[hand]!!.content)
            action("closeStudio");until("server scribe handoff materializes foreign debt"){repo.view().documents[hand]?.content?.contains("FOREIGN ")==true&&repo.view().pending[hand]==null}
            close();row("foreign Studio update + real scribe handoff",JSONObject().put("noNonScribePut",true).put("materialized",repo.view().documents[hand]!!.content).put("rev",repo.view().documents[hand]!!.rev))

            val dirty="dirty-at-join.md";val dirtyBase=put(dirty,source);until("dirty-at-join source"){repo.view().documents[dirty]?.rev==dirtyBase.rev}
            studio(dirty,source);action("editStudio","text" to "WINNING DEBT ");Thread.sleep(100)
            val dirtyWrites=writeCount();open(dirty)
            val dirtyJoined=observedFrames.filterIsInstance<ServerFrame.Joined>().last{it.path==dirty}
            assertTrue(dirtyJoined.seeded);assertTrue(dirtyJoined.dirty);assertFalse(dirtyJoined.scribe)
            assertTrue(snapshot().getJSONObject("native").getString("markdown").contains("WINNING DEBT "))
            settle();assertEquals(dirtyWrites,writeCount());assertEquals(source,repo.view().documents[dirty]!!.content)
            action("closeStudio");until("first-render dirty debt materialized by actual handoff"){repo.view().documents[dirty]?.content?.contains("WINNING DEBT ")==true&&repo.view().pending[dirty]==null}
            val dirtyPersisted=repo.view().documents[dirty]!!.content!!;close();open(dirty);studio(dirty,dirtyPersisted)
            assertEquals(dirtyPersisted,snapshot().getJSONObject("native").getString("markdown"));assertEquals(dirtyPersisted,snapshot().getString("studio"));close();action("closeStudio")
            row("safe foreign seed winner dirty-at-join first-render debt survives real handoff and reseed",JSONObject().put("joinedDirty",true).put("noNonScribePut",true).put("persisted",dirtyPersisted))

            val resetPath="reset.md";val resetBase=put(resetPath,source)
            until("reset source"){repo.view().documents[resetPath]?.rev==resetBase.rev}
            studio(resetPath,source);open(resetPath) // Native is non-scribe: merged debt must stay unsaved.
            val oldJoined=observedFrames.filterIsInstance<ServerFrame.Joined>().last{it.path==resetPath}
            assertFalse(oldJoined.scribe)
            val writesBeforeReset=writeCount()
            action("editStudio","text" to "RESET FOREIGN ")
            until("reset foreign update actually applied"){snapshot().getJSONObject("native").getString("markdown").contains("RESET FOREIGN ")}
            action("editNative","text" to "RESET LOCAL ")
            until("reset local generation checkpointed"){repo.view().drafts[resetPath]?.content?.contains("RESET LOCAL ")==true}
            val oldCallback=observedEditorEvents.last{it.second is EditorEvent.Changed && it.second.context.sid==oldJoined.sid}
            val resetMerged=snapshot().getJSONObject("native").getString("markdown")
            assertTrue(resetMerged.contains("RESET LOCAL "));assertTrue(resetMerged.contains("RESET FOREIGN "))
            until("both editors merged before ordinary external PUT"){snapshot().getString("studio")==resetMerged}
            assertEquals(source,repo.view().documents[resetPath]!!.content)
            assertEquals(writesBeforeReset,writeCount())
            // Ordinary protocol PUT: no live sid. Real Remote replaces and resets the existing room.
            val external="# External replacement\n\nExact replacement.\n"
            val resetEventStart=observedEditorEvents.size
            val replaced=put(resetPath,external,resetBase.rev)
            until("replacement room rejoined and mounted"){observedEditorEvents.drop(resetEventStart).any{
                it.second is EditorEvent.Ready && it.second.context.sid!=oldJoined.sid
            }}
            val newJoined=observedFrames.filterIsInstance<ServerFrame.Joined>().last{it.path==resetPath}
            assertNotEquals(oldJoined.sid,newJoined.sid);assertNotEquals(oldJoined.epoch,newJoined.epoch)
            assertTrue(observedFrames.filterIsInstance<ServerFrame.Reset>().any{it.path==resetPath&&it.sid==oldJoined.sid})
            val recoveredReset=recover.list(repo.view().namespace!!).filter{it.path==resetPath}
            assertTrue(recoveredReset.any{it.context.sid==oldJoined.sid&&it.snapshot?.markdown==resetMerged})
            assertEquals(external,snapshot().getJSONObject("native").getString("markdown"))
            assertEquals(external,repo.view().documents[resetPath]!!.content)
            assertEquals(replaced.rev,repo.view().documents[resetPath]!!.rev)
            assertEquals(documentHash(external),repo.view().documents[resetPath]!!.hash)
            // Replay the EXACT real old-session callback, not a fabricated snapshot/event.
            // Codec rejects stale context; coordinator independently fences the already-decoded event.
            var rejected=false
            try{codec.decode(oldCallback.first)}catch(_:IllegalArgumentException){rejected=true}
            assertTrue("Old real callback must be rejected by codec",rejected)
            assertTrue(co.onEditorEvent(oldCallback.second).isEmpty())
            assertEquals(external,snapshot().getJSONObject("native").getString("markdown"))
            val settled=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(700)
            while(System.nanoTime()<settled){pump();Thread.sleep(20)}
            assertEquals(writesBeforeReset,writeCount())
            close();action("closeStudio")
            assertEquals(writesBeforeReset,writeCount())
            controlSend(ClientFrame.Sync(0))
            var remoteReset:ServerFrame.Change?=null;var resetSynced=false
            val resetDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(System.nanoTime()<resetDeadline&&!resetSynced){val e=controlEvents.poll(50,TimeUnit.MILLISECONDS);if(e is ConnectionEvent.Frame){
                if(e.frame is ServerFrame.Change&&e.frame.path==resetPath)remoteReset=e.frame
                if(e.frame is ServerFrame.Synced)resetSynced=true
            }}
            assertTrue(resetSynced);assertNotNull(remoteReset)
            assertEquals(external,remoteReset!!.content);assertEquals(replaced.rev,remoteReset.rev)
            assertEquals(documentHash(external),remoteReset.hash);assertFalse(remoteReset.deleted)
            row("ordinary external PUT resets dirty merged room without stale overwrite",JSONObject()
                .put("mergedRecovery",resetMerged).put("externalContent",external).put("externalRev",replaced.rev)
                .put("externalHash",remoteReset.hash).put("oldSid",oldJoined.sid).put("newSid",newJoined.sid)
                .put("oldEpoch",oldJoined.epoch).put("newEpoch",newJoined.epoch)
                .put("actualResetReceived",true).put("replayedActualOldCallbackRejected",true)
                .put("noNativePutDelete",true).put("recoveryEntries",recoveredReset.size))

            failClosedScenarios()

            val dis="disconnect.md";val d=put(dis,source);until("disconnect source"){repo.view().documents[dis]?.rev==d.rev};studio(dis,source);open(dis)
            action("editStudio","text" to "MERGED FOREIGN ");until("merged foreign snapshot before disconnect"){snapshot().getJSONObject("native").getString("markdown").contains("MERGED FOREIGN ")}
            action("editNative","text" to "LOCAL RECOVERY ");until("local checkpoint before disconnect"){repo.view().drafts[dis]?.content?.contains("LOCAL RECOVERY ")==true}
            val merged=snapshot().getJSONObject("native").getString("markdown");assertTrue(merged.contains("MERGED FOREIGN "))
            connection.disconnect();until("offline browser flush and disposal"){!co.hasActiveEditor}
            val entries=recover.list(repo.view().namespace!!);assertTrue(entries.any{it.path==dis&&it.snapshot?.markdown==merged})
            assertEquals(source,repo.view().documents[dis]!!.content);assertEquals(d.rev,repo.view().documents[dis]!!.rev)
            action("closeStudio");row("actual disconnect preserves merged local + foreign recovery",JSONObject().put("snapshot",merged).put("noOriginalOverwrite",true).put("recoveryEntries",entries.size))
        }catch(t:Throwable){failure=t;throw t}finally{
            File(run,"hold-ack").delete()
            File(run,"native-events.json").writeText(nativeEvents.toString(2));File(run,"native-sends.json").writeText(JSONArray(sends.map{JSONObject(FrameCodec.encode(it))}).toString(2))
            File(run,"fullstack-results.json").writeText(JSONObject().put("rows",rows).put("failure",failure?.stackTraceToString()?:JSONObject.NULL).put("apkGateEnabled",com.voxarium.remote.BuildConfig.EDITOR_WRITES_ENABLED).put("statuses",JSONArray(statuses.map{it.name})).toString(2))
            connection.close();control.close();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
        }
    }
}
