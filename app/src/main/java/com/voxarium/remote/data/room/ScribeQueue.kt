package com.voxarium.remote.data.room

/**
 * Pure, in-memory materialization policy (remote-android/protocol.md §§4,7–8).
 * One instance per room incarnation; ALL calls belong to one serialized coordinator worker.
 * This is neither a durable journal nor a repository/editor/transport adapter.
 *
 * [sourceMarkdown] is the exact materialized file. [initialize] takes the codec-normalized
 * initial seed/state, BEFORE subsequent room updates. The two must not be conflated: codec
 * normalization, seed, role, selection and awareness alone never authorize a write.
 * [changed] is for a genuinely changed merged Markdown snapshot, local OR remote. It can be
 * called before initialize for buffered early edits; initialization never erases that debt.
 *
 * [nextSnapshot] is an effect proposal, not a network send. On the SAME ordered worker the
 * coordinator must first enqueue all Yjs updates represented by it, durably queue these exact
 * bytes in the repository, obtain its actual request ID and call [sent] BEFORE sending PUT.
 * No intervening editor/role event is allowed between proposal and reservation. If repository
 * nextWrite returns a verified exact no-op, call [materialized] instead. Repeated proposals
 * without reservation do not grant permission to send multiple PUTs.
 *
 * Only call [acknowledge] AFTER repository validation/durable application of the matching
 * owner, connection generation, path, ID and snapshot hash. An enqueue callback is NOT ACK.
 * On nack/error/reset/disconnect/send refusal call [stop], checkpoint/recover via the owning
 * coordinator and discard this queue; never replay a live attempt in a new incarnation.
 * Native coordinator owns schema/readiness/epoch fencing, checkpoints, flush timeout and leave.
 */
class ScribeQueue(
    sourceMarkdown: String,
    dirtyAtJoin: Boolean = false,
    isScribe: Boolean = false,
    private val debounceMillis: Long = 500,
    private val maxDelayMillis: Long = 3_000,
) {
    init {
        require(debounceMillis >= 0 && maxDelayMillis >= debounceMillis) { "Invalid scribe timing" }
    }

    /** Immutable bytes and queue-local generation; unrelated to repository draft generations. */
    class Snapshot internal constructor(val generation: Long, val markdown: String) {
        override fun toString() = "ScribeQueue.Snapshot(redacted)"
    }

    class InFlight internal constructor(val requestId: Long, val snapshot: Snapshot) {
        override fun toString() = "ScribeQueue.InFlight(redacted)"
    }

    class View internal constructor(
        val initialized: Boolean,
        val isScribe: Boolean,
        val stopped: Boolean,
        val generation: Long,
        val confirmedGeneration: Long,
        val normalizedInitialMarkdown: String?,
        val materializedMarkdown: String,
        val snapshot: Snapshot?,
        val inFlight: InFlight?,
    ) {
        val dirty: Boolean get() = generation > confirmedGeneration
        /** Non-scribe also needs exact materialization, not delivery of unacknowledged updates. */
        val canLeave: Boolean get() = initialized && !stopped && !dirty && inFlight == null
        override fun toString() = "ScribeQueue.View(redacted)"
    }

    private var initialized = false
    private var scribe = isScribe
    private var stopped = false
    private var generation = if (dirtyAtJoin) 1L else 0L
    private var confirmedGeneration = 0L
    private var normalizedInitial: String? = null
    private val inheritedDebt = dirtyAtJoin
    // Semantic initial no-op baseline; NOT a claim that these normalized bytes are on disk.
    // Dirty-at-join must never use it to erase the pre-existing room debt.
    private var cleanMarkdown: String? = null
    private var materialized = sourceMarkdown
    private var current: Snapshot? = null
    private var inFlight: InFlight? = null
    private var lastRequestId: Long? = null
    private var dirtySince: Long? = null
    private var lastChange: Long? = null
    private var lastNow: Long? = null

    /** Capture exactly once, from initial seed/state, not a later merged first render. */
    fun initialize(normalizedInitialMarkdown: String, nowMillis: Long) {
        active()
        check(!initialized) { "Scribe baseline already initialized" }
        time(nowMillis)
        normalizedInitial = normalizedInitialMarkdown
        if (!inheritedDebt) cleanMarkdown = normalizedInitialMarkdown
        if (current == null) current = Snapshot(generation, normalizedInitialMarkdown)
        initialized = true
        if (dirty()) {
            if (dirtySince == null) dirtySince = nowMillis
            if (lastChange == null) lastChange = nowMillis
        }
    }

    /** Role handoff may precede initialize; it never creates or clears materialization debt. */
    fun setScribe(isScribe: Boolean) {
        active()
        scribe = isScribe
    }

    /** Caller already established that this is real document work, not initial normalization. */
    fun changed(markdown: String, nowMillis: Long) {
        active()
        time(nowMillis)
        if (current?.markdown == markdown) return
        check(generation < Long.MAX_VALUE) { "Scribe generation exhausted" }
        generation++
        current = Snapshot(generation, markdown)
        if (dirtySince == null) dirtySince = nowMillis
        lastChange = nowMillis
    }

    /**
     * Call from injected monotonic ticks; force bypasses timing, NOT role/readiness/in-flight.
     * A verified byte-identical revert needs no PUT. Do not settle a revert while an older
     * PUT is outstanding: that older write could still change the file away from this text.
     */
    fun nextSnapshot(nowMillis: Long, force: Boolean = false): Snapshot? {
        time(nowMillis)
        if (stopped || !initialized || inFlight != null) return null
        val snapshot = current ?: return null
        if (!dirty()) return null
        if (snapshot.markdown == materialized || snapshot.markdown == cleanMarkdown) {
            confirmCurrent()
            return null
        }
        if (!scribe) return null
        val since = dirtySince ?: return null
        val last = lastChange ?: since
        return if (force || nowMillis - last >= debounceMillis || nowMillis - since >= maxDelayMillis) snapshot else null
    }

    /** Reserve a proposal using the repository's monotonically increasing actual request ID. */
    fun sent(snapshot: Snapshot, requestId: Long) {
        active()
        check(initialized && scribe && dirty() && inFlight == null && current === snapshot) { "Stale scribe proposal" }
        require(requestId >= 0 && (lastRequestId == null || requestId > lastRequestId!!)) { "Invalid scribe request ID" }
        inFlight = InFlight(requestId, snapshot)
        lastRequestId = requestId
    }

    /** Matching ACK confirms ONLY the sent generation, never any newer edits. */
    fun acknowledge(requestId: Long): Boolean {
        if (stopped) return false
        val pending = inFlight ?: return false
        if (pending.requestId != requestId) return false
        materialized = pending.snapshot.markdown
        cleanMarkdown = pending.snapshot.markdown
        confirmedGeneration = maxOf(confirmedGeneration, pending.snapshot.generation)
        inFlight = null
        if (!dirty()) clearTiming()
        return true
    }

    /**
     * After ordered repository application of a current external file version. Only an EXACT
     * current snapshot match confirms non-scribe durability (not normalization/hash guesses).
     * A matching change cannot complete an outstanding own request: only its validated ACK can.
     * Coordinator still handles external replacement/reset and revision ordering separately.
     */
    fun materialized(exactMarkdown: String): Boolean {
        if (stopped) return false
        if (exactMarkdown != materialized) cleanMarkdown = null
        materialized = exactMarkdown
        if (!initialized || current?.markdown != exactMarkdown || inFlight != null) return false
        cleanMarkdown = exactMarkdown
        confirmCurrent()
        return true
    }

    /** Terminal fence; retains current and in-flight immutable snapshots for recovery. */
    fun stop() { stopped = true }

    fun view(): View = View(
        initialized, scribe, stopped, generation, confirmedGeneration, normalizedInitial,
        materialized, current, inFlight,
    )

    private fun dirty() = generation > confirmedGeneration
    private fun confirmCurrent() { confirmedGeneration = generation; clearTiming() }
    private fun clearTiming() { dirtySince = null; lastChange = null }
    private fun active() { check(!stopped) { "Scribe queue is stopped" } }
    private fun time(nowMillis: Long) {
        require(nowMillis >= 0 && (lastNow == null || nowMillis >= lastNow!!)) { "Scribe clock must be monotonic" }
        lastNow = nowMillis
    }
}
