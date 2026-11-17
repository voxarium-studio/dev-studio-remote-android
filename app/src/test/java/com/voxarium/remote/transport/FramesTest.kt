package com.voxarium.remote.transport

import com.voxarium.remote.data.transport.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FramesTest {
    private val hash="a".repeat(64)
    private fun decode(s:String)=FrameCodec.decode(s)
    private fun invalid(s:String) { assertThrows(FrameValidationException::class.java) { decode(s) } }

    @Test fun mePreservesVersionAndUsesStrictValidation() {
        val user="""{"id":"u","name":"private name","color":"#fff"}"""
        val me=FrameCodec.decodeMe("""{"protocol":2,"user":$user}""")
        assertEquals(2L,me.protocol)
        assertEquals("u",me.user.id)
        assertFalse(me.toString().contains("private name"))
        listOf(
            """{'protocol':1,'user':$user}""",
            """{"protocol":1,"protocol":2,"user":$user}""",
            """{"protocol":1,"user":$user,}""",
            """{"protocol":1,"user":$user} trailing""",
            """{"protocol":"1","user":$user}""",
            """{"protocol":1.0,"user":$user}""",
            """{"protocol":true,"user":$user}""",
            """{"protocol":-1,"user":$user}""",
            """{"protocol":9007199254740992,"user":$user}""",
            """{"protocol":null,"user":$user}""",
            """{"protocol":1,"user":null}""",
            """{"protocol":1,"user":{"id":"u","name":null,"color":"c"}}""",
            """{"protocol":1,"user":{"id":"u","name":"n"}}""",
            """{"protocol":1,"user":{"id":"","name":"n","color":"c"}}""",
            " ".repeat(FrameCodec.MAX_ME_BYTES + 1),
            "[]"
        ).forEach { assertThrows(FrameValidationException::class.java) { FrameCodec.decodeMe(it) } }
    }

    @Test fun helloPreservesOtherProtocolVersion() {
        val f=decode("""{"t":"hello","protocol":2,"user":{"id":"u","name":"Alice","color":"#fff"},"space":{"id":"s","name":"game"},"seq":42}""") as ServerFrame.Hello
        assertEquals(2L,f.protocol);assertEquals(42L,f.seq);assertEquals("u",f.user.id);assertEquals("s",f.space.id)
        assertEquals(1,FrameCodec.PROTOCOL_VERSION)
    }
    @Test fun nullDocumentFieldsAndCorrelationIds() {
        val f=decode("""{"t":"change","path":"a.md","rev":3,"hash":null,"deleted":true,"content":null,"by":null}""") as ServerFrame.Change
        assertNull(f.content);assertNull(f.hash);assertNull(f.by)
        val ack=decode("""{"t":"ack","id":"legacy","path":"a.md","rev":0,"hash":null,"deleted":true}""") as ServerFrame.Ack
        assertEquals(FrameId.Text("legacy"),ack.id)
        val nack=decode("""{"t":"nack","id":7,"path":"a.md","current":null}""") as ServerFrame.Nack
        assertEquals(FrameId.Integer(7),nack.id);assertNull(nack.current)
        val current=decode("""{"t":"nack","id":null,"path":"a.md","current":{"rev":2,"hash":"$hash","deleted":false,"content":"text"}}""") as ServerFrame.Nack
        assertEquals(FrameId.Null,current.id);assertEquals("text",current.current!!.content)
    }
    @Test fun roomFramesCoverRawRemoteContract() {
        val frames=listOf(
            """{"t":"join_failed","sid":"s","path":"a.md","reason":"stale"}""",
            """{"t":"joined","sid":"s","path":"a.md","epoch":"e","seeded":false,"state":null,"scribe":true,"dirty":false,"rev":2}""",
            """{"t":"joined","sid":"s","path":"a.md","epoch":"e","seeded":true,"state":"AA==","scribe":false,"dirty":true,"rev":2}""",
            """{"t":"seeded","sid":"s","ok":true,"state":null}""",
            """{"t":"seeded","sid":"s","ok":false,"state":"AA=="}""",
            """{"t":"update","sid":"s","update":"AA=="}""",
            """{"t":"awareness","sid":"s","update":""}""",
            """{"t":"peer_joined","sid":"s"}""",
            """{"t":"peer_left","sid":"s","client_id":4294967295}""",
            """{"t":"scribe","sid":"s"}""",
            """{"t":"reset","sid":"s","path":"a.md","reason":"deleted"}"""
        )
        frames.forEach { assertNotNull(decode(it)) }
        assertEquals(4294967295L,(decode(frames[8]) as ServerFrame.PeerLeft).clientId)
    }
    @Test fun presenceAndErrorCorrelation() {
        val p=decode("""{"t":"presence","peers":[{"user":{"id":"u","name":"n","color":"c"},"connections":2,"docs":["a.md"]}]}""") as ServerFrame.Presence
        assertEquals(2L,p.peers.single().connections)
        val e=decode("""{"t":"error","message":"private server details","of":"put","id":5,"sid":"s","path":"a.md"}""") as ServerFrame.Error
        assertEquals(FrameId.Integer(5),e.id);assertEquals(FrameId.Text("s"),e.sid)
        assertFalse(e.toString().contains("private"))
    }
    @Test fun strictTypesNumbersAndJson() {
        listOf("{}","[]","null","{'t':'synced','seq':1}","{t:\"synced\",seq:1}","""{"t":"synced","seq":1,}""","""{"t":"synced","seq":1} trailing""","""{"t":"synced","seq":1,"seq":2}""","""{"t":"synced","seq":"1"}""","""{"t":"synced","seq":true}""","""{"t":"synced","seq":1.0}""","""{"t":"synced","seq":-1}""","""{"t":"synced","seq":9007199254740992}""","""{"t":"synced","seq":null}""","""{"t":"unknown"}""").forEach(::invalid)
        assertEquals(FrameCodec.MAX_INTEGER,(decode("""{"t":"synced","seq":9007199254740991}""") as ServerFrame.Synced).seq)
    }
    @Test fun malformedRoomAndDocumentData() {
        listOf(
            """{"t":"update","sid":"s","update":"a"}""",
            """{"t":"update","sid":"s","update":"AA==\n"}""",
            """{"t":"update","sid":"","update":"AA=="}""",
            """{"t":"peer_left","sid":"s","client_id":true}""",
            """{"t":"seeded","sid":"s","ok":true,"state":"AA=="}""",
            """{"t":"seeded","sid":"s","ok":false,"state":null}""",
            """{"t":"change","path":"a.md","rev":3,"hash":null,"deleted":false,"content":null,"by":null}""",
            """{"t":"change","path":"../a.md","rev":3,"hash":null,"deleted":true,"content":null,"by":null}""",
            """{"t":"nack","id":{},"path":"a.md","current":null}"""
        ).forEach(::invalid)
    }
    @Test fun outboundFramesValidateAndSerialize() {
        val p=JSONObject(FrameCodec.encode(ClientFrame.Put(1," folder/a.MD ","private text",0,"session")))
        assertEquals("folder/a.MD",p.getString("path"));assertEquals(1,p.getInt("id"));assertEquals("session",p.getString("live"))
        listOf<ClientFrame>(ClientFrame.Sync(0),ClientFrame.Delete(2,"a.md",0),ClientFrame.Join("s","a.md",4294967295L,1),ClientFrame.Seed("s","AA=="),ClientFrame.Update("s","AA=="),ClientFrame.Awareness("s",""),ClientFrame.Leave("s")).forEach { assertNotNull(JSONObject(FrameCodec.encode(it))) }
        listOf<ClientFrame>(ClientFrame.Sync(-1),ClientFrame.Put(-1,"a.md","x",0),ClientFrame.Delete(1,"a/../x.md",0),ClientFrame.Join("s","a.md",FrameCodec.MAX_INTEGER+1,0),ClientFrame.Leave("s".repeat(65)),ClientFrame.Update("s","@@=="),ClientFrame.Put(1,"a.md","é".repeat(500001),0)).forEach {
            assertThrows(FrameValidationException::class.java) {FrameCodec.encode(it)}
        }
        assertFalse(ClientFrame.Put(1,"a.md","private text",0).toString().contains("private text"))
    }
    @Test fun payloadLimitsWithoutRecursiveBase64Regex() {
        val legal="A".repeat(4*((FrameCodec.MAX_UPDATE_BYTES+2)/3)-1)+"="
        assertNotNull(FrameCodec.encode(ClientFrame.Update("s",legal)))
        assertThrows(FrameValidationException::class.java) {FrameCodec.encode(ClientFrame.Update("s","A".repeat(4*((FrameCodec.MAX_UPDATE_BYTES+2)/3))))}
        assertNotNull(FrameCodec.encode(ClientFrame.Put(1,"a.md","é".repeat(500000),0)))
    }
    @Test fun v2HelloUsesExactIdAndEpochAndDisplayNames() {
        val wire="""{"t":"hello","protocol":2,"user":{"id":"u","display_name":"Alice","color":"#123456"},"space":{"id":"project-a","display_name":"Same label","storage_epoch":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},"seq":42}"""
        val frame=FrameCodec.decodeV2(wire) as ServerFrame.Hello
        assertEquals("project-a",frame.space.id)
        assertEquals("Same label",frame.space.displayName)
        assertEquals("a".repeat(32),frame.space.storageEpoch)
        assertEquals("Alice",frame.user.displayName)
        for(invalid in listOf(wire.replace("project-a"," Project-a "),wire.replace("a".repeat(32),"A".repeat(32)),
            wire.replace("display_name","name"),wire.replace("42","42.0"),wire.replace("42","-1")))
            assertThrows(FrameValidationException::class.java) { FrameCodec.decodeV2(invalid) }
        assertThrows(FrameValidationException::class.java) { FrameCodec.decode(wire) }
    }
    @Test fun v2ChangeAndPresenceUseSameUserAdapter() {
        val user="""{"id":"u","display_name":"Alice","color":"#123456"}"""
        val me=FrameCodec.decodeMeV2("""{"protocol":2,"user":$user}""")
        val change=FrameCodec.decodeV2("""{"t":"change","path":"a.md","rev":1,"hash":"$hash","deleted":false,"content":"text","by":$user}""") as ServerFrame.Change
        val presence=FrameCodec.decodeV2("""{"t":"presence","peers":[{"user":$user,"connections":1,"docs":["a.md"]}]}""") as ServerFrame.Presence
        assertEquals(me.user,change.by)
        assertEquals(me.user,presence.peers.single().user)
        assertThrows(FrameValidationException::class.java) { FrameCodec.decodeMeV2("""{"protocol":2,"user":${user.replace("display_name","name")}}""") }
    }
    @Test fun v2CodecDoesNotNormalizeIdentityOrRejectHistoricalLabels() {
        val id="a".repeat(32)
        val base="""{"t":"hello","protocol":2,"user":{"id":"alice","display_name":"Alice","color":"#123456"},"space":{"id":"$id","display_name":"Legacy\nlabel","storage_epoch":"$id"},"seq":0}"""
        assertEquals("Legacy\nlabel",(FrameCodec.decodeV2(base) as ServerFrame.Hello).space.name)
        for(idText in listOf(" A","A","a ","a/b","a%2fb"))
            assertThrows(FrameValidationException::class.java) { FrameCodec.decodeV2(base.replace("\"id\":\"$id\"","\"id\":\"$idText\"")) }
        assertThrows(FrameValidationException::class.java) { FrameCodec.decodeV2(base.replace("\"storage_epoch\":\"$id\"","\"storage_epoch\":\"uuid-with-dashes\"")) }
    }

}
