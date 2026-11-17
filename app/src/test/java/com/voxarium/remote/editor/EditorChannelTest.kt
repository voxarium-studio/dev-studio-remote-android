package com.voxarium.remote.editor

import com.voxarium.remote.data.room.*
import com.voxarium.remote.ui.connection.EditorChannel
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class EditorChannelTest {
    private class Main : Executor {
        val jobs=ArrayDeque<Runnable>()
        override fun execute(command:Runnable) {jobs.add(command)}
        fun drain() {while(jobs.isNotEmpty()) jobs.removeFirst().run()}
    }
    @Test fun retiredChannelCannotAttachOrDeliverAndCleanupRunsWithoutComposition() {
        val main=Main();var delivered=0;var cleaned=0
        val channel=EditorChannel(main,{}, {_,_->fail("unexpected failure")})
        channel.submit(EditorCommand.Prepare(EditorContext(1,1,"sid"),1,"source"))
        channel.retire()
        channel.onRetired {cleaned++}
        channel.attach(RoomEditorPort {delivered++});main.drain()
        assertTrue(channel.isRetired);assertEquals(0,delivered)
        assertTrue(cleaned>0)
    }
    @Test fun retiredChannelFencesAlreadyPostedCallbacks() {
        val main=Main();var events=0
        val channel=EditorChannel(main,{events++},{_,_->})
        val context=EditorContext(1,1,"sid")
        channel.submit(EditorCommand.Prepare(context,1,"source"));main.drain()
        channel.retire();channel.event(EditorEvent.Changed(context,RoomSnapshot("late",1)))
        main.drain();assertEquals(0,events)
    }
    @Test fun haltBeforeViewAttachmentIsRememberedWithoutDestroyingModel() {
        val main=Main();var halted=0;var retired=0
        val channel=EditorChannel(main,{}, {_,_->})
        channel.halt();main.drain();channel.onHalt {halted++};channel.onRetired {retired++}
        assertEquals(1,halted);assertEquals(0,retired);assertFalse(channel.isRetired)
    }
}
