package com.voxarium.remote.ui.connection

import com.voxarium.remote.data.room.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One immutable binding lifetime. Main-thread delivery is ordered and never rebound to a profile.
 * A view can detach on rotation without destroying the model; retirement follows Disposed only. */
class EditorChannel internal constructor(
    private val main: Executor,
    private val receive: (EditorEvent) -> Unit,
    private val lost: (EditorContext?, Boolean) -> Unit,
) : RoomEditorPort {
    private val retired = AtomicBoolean()
    private val current = AtomicReference<EditorContext?>()
    private var port: RoomEditorPort? = null
    private val waiting = ArrayDeque<EditorCommand>()
    private var bytes = 0L
    private var onRetired: (() -> Unit)? = null
    private var onHalt: (() -> Unit)? = null
    private val halted=AtomicBoolean()
    val isRetired: Boolean get() = retired.get()

    override fun submit(command: EditorCommand) {
        current.set(command.context)
        main.execute {
            if (retired.get()) return@execute
            val target = port
            if (target != null) target.submit(command)
            else {
                val size = when (command) {
                    is EditorCommand.Prepare -> command.markdown.length.toLong()
                    is EditorCommand.Initialize -> command.markdown.length.toLong() + command.state.length
                    is EditorCommand.ApplyUpdate -> command.update.length.toLong()
                    is EditorCommand.Awareness -> command.update.length.toLong()
                    else -> 32L
                } * 2
                if (waiting.size >= 256 || bytes + size > 32L * 1024 * 1024) {
                    failure(); return@execute
                }
                waiting.add(command); bytes += size
            }
        }
    }

    /** Called on main. Same channel may reattach the SAME retained view after configuration change. */
    fun attach(target: RoomEditorPort) {
        if (retired.get()) return
        check(port == null || port === target)
        port = target
        while (waiting.isNotEmpty() && !retired.get()) target.submit(waiting.removeFirst())
        bytes = 0
    }

    fun event(event: EditorEvent) {
        if (!retired.get() && event.context == current.get()) receive(event)
    }
    fun failure(unsupported: Boolean = false) { if (!retired.get()) lost(current.get(),unsupported) }
    fun onRetired(action: () -> Unit) { if(retired.get()) action() else onRetired=action }
    fun onHalt(action: () -> Unit) { onHalt=action; if(halted.get()) action() }
    internal fun halt() { halted.set(true);main.execute { if(!retired.get()) onHalt?.invoke() } }
    internal fun retire() {
        if(!retired.compareAndSet(false,true)) return
        main.execute { waiting.clear(); bytes = 0; port = null; onRetired?.invoke();onRetired=null }
    }
    override fun toString() = "EditorChannel(redacted)"
}
