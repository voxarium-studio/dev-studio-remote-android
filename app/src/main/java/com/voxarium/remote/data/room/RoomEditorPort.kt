package com.voxarium.remote.data.room

import com.voxarium.remote.data.documents.DocumentSend
import com.voxarium.remote.data.transport.ClientFrame

/** Native-only typed seam, not a registered WebView bridge. No credentials/URLs enter it.
 * Execute commands serially; deliver events on the coordinator's same ordered worker.
 * Every async reply MUST echo its immutable context/requestId, never the current selection.
 * Long values map to the VS-72 safe-integer JSON envelope, not lossy JS coercions. */
data class EditorContext(val documentGeneration: Long, val connectionGeneration: Long, val sid: String)
data class RoomSnapshot(val markdown: String, val generation: Long) {
    override fun toString() = "RoomSnapshot(redacted)"
}

sealed class EditorCommand(open val context: EditorContext, open val requestId: Long) {
    /** Allocate EMPTY working Y.Doc/clientId; build seed in a separate throwaway doc.
     * No binding, initial content, outgoing update or editable DOM yet. */
    data class Prepare(override val context: EditorContext, override val requestId: Long, val markdown: String) : EditorCommand(context,requestId)
    /** Apply ONLY the winning state to the empty doc, preflight and mount without initialContent.
     * Ready snapshot is the normalized CLEAN baseline BEFORE any queued updates are applied. */
    data class Initialize(override val context: EditorContext, override val requestId: Long, val markdown: String, val state: String, val readOnly: Boolean) : EditorCommand(context,requestId)
    /** Preflight before live apply, remote origin/no echo. Applied contains the resulting snapshot.
     * Do not emit Changed for this command: its snapshot is accounted for by Applied. */
    data class ApplyUpdate(override val context: EditorContext, override val requestId: Long, val update: String) : EditorCommand(context,requestId)
    data class Awareness(override val context: EditorContext, override val requestId: Long, val update: String) : EditorCommand(context,requestId)
    data class PeerLeft(override val context: EditorContext, override val requestId: Long, val clientId: Long) : EditorCommand(context,requestId)
    data class PublishAwareness(override val context: EditorContext, override val requestId: Long) : EditorCommand(context,requestId)
    /** Complete composition, freeze further input, drain earlier model events, then reply Flushed.
     * Also works after Initialize while the native connection is offline. Never a save ACK. */
    data class Flush(override val context: EditorContext, override val requestId: Long) : EditorCommand(context,requestId)
    /** Destroy binding/editor before Y.Doc. Disposed is required before any replacement Prepare. */
    data class Dispose(override val context: EditorContext, override val requestId: Long) : EditorCommand(context,requestId)
    final override fun toString() = "EditorCommand.${javaClass.simpleName}(redacted)"
}

sealed class EditorEvent(open val context: EditorContext) {
    data class Prepared(override val context: EditorContext, val requestId: Long, val clientId: Long, val seed: String) : EditorEvent(context)
    data class Ready(override val context: EditorContext, val requestId: Long, val snapshot: RoomSnapshot) : EditorEvent(context)
    data class Applied(override val context: EditorContext, val requestId: Long, val snapshot: RoomSnapshot) : EditorEvent(context)
    /** VS-72 onUpdate MUST be delivered before Changed for the same local transaction. */
    data class LocalUpdate(override val context: EditorContext, val update: String) : EditorEvent(context)
    data class Changed(override val context: EditorContext, val snapshot: RoomSnapshot) : EditorEvent(context)
    data class Awareness(override val context: EditorContext, val update: String) : EditorEvent(context)
    data class Flushed(override val context: EditorContext, val requestId: Long, val snapshot: RoomSnapshot) : EditorEvent(context)
    data class Disposed(override val context: EditorContext, val requestId: Long) : EditorEvent(context)
    /** Preserve last valid Markdown, block all writing; no automatic repair/retry. */
    data class Blocked(override val context: EditorContext, val snapshot: RoomSnapshot?, val raw: List<String> = emptyList()) : EditorEvent(context)
    final override fun toString() = "EditorEvent.${javaClass.simpleName}(redacted)"
}

fun interface RoomEditorPort { fun submit(command: EditorCommand) }

enum class RoomStatus {
    WAITING_SYNC, JOINING, SEEDING, MOUNTING, EDITING, ROOM_ONLY, SAVING, REMOTE_SAVED,
    CLOSING, CLOSED_CONFIRMED, CLOSED_RECOVERY_UNCONFIRMED, RECOVERY_UNCONFIRMED,
    MISSING, STALE, SCHEMA_BLOCKED, WRITE_GATE_CLOSED, RECONNECT_REQUIRED,
}

/** Effects must be submitted in returned order, without parallel frame dispatch. A send is NOT ACK.
 * Check coordinator.isCurrent immediately before network enqueue. Feed every send result back.
 * Local statuses refer only to Markdown snapshots, never state-vector durability. */
sealed class RoomEffect {
    data class Network(val generation: Long, val frame: ClientFrame, val context: EditorContext?, val documentSend: DocumentSend? = null, val serial: Long = 0) : RoomEffect()
    data class Editor(val command: EditorCommand) : RoomEffect()
    data class Status(val value: RoomStatus) : RoomEffect()
    final override fun toString() = "RoomEffect.${javaClass.simpleName}(redacted)"
}
