package com.spacecorps.oam.game

import com.spacecorps.oam.stringValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Holds an NPC's memory and the changes memory tools make during a turn.
 * Changes are staged and only committed when the turn succeeds, so a
 * blocked or cancelled turn leaves memory untouched.
 */
internal class MemoryStore(initial: NPCMemory) {
    private val lock = Any()
    private var current: NPCMemory = initial
    private val pendingFacts = ArrayList<String>()
    private var pendingDelta = 0L

    var memory: NPCMemory
        get() = synchronized(lock) { current }
        set(value) = synchronized(lock) { current = value }

    fun update(transform: (NPCMemory) -> NPCMemory): NPCMemory = synchronized(lock) {
        current = transform(current)
        current
    }

    /** Stages a fact. Returns false if it is already known or staged. */
    fun stageFact(fact: String): Boolean = synchronized(lock) {
        var probe = current
        for (staged in pendingFacts) probe = probe.remembering(staged)
        if (probe.remembering(fact) === probe) return@synchronized false
        pendingFacts += fact.trim()
        true
    }

    /** Stages a relationship change and returns the resulting value. */
    fun stageRelationship(delta: Int): Int = synchronized(lock) {
        pendingDelta += delta
        current.adjustingRelationship(pendingDelta.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()).relationship
    }

    /** Applies staged changes and returns the new memory. */
    fun commit(maxFacts: Int): NPCMemory = synchronized(lock) {
        var memory = current
        for (fact in pendingFacts) memory = memory.remembering(fact, limit = maxFacts)
        current = memory.adjustingRelationship(pendingDelta.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
        pendingFacts.clear()
        pendingDelta = 0
        current
    }

    fun discardPending() {
        synchronized(lock) {
            pendingFacts.clear()
            pendingDelta = 0
        }
    }
}

/** Turns partial replies (structured output, or a tagged text reply) into emotion and line events. */
internal class LineTracker(private val speaker: String) {
    var shown: String = ""
        private set
    private var sentEmotion: Emotion? = null

    fun consume(partial: JsonElement, emit: (DialogueEvent) -> Unit) {
        val reply = partial as? JsonObject ?: return
        // The emotion is complete once the model has moved on to the line.
        if (sentEmotion == null && reply["line"] != null) {
            reply["emotion"]?.stringValue?.let(Emotion::matching)?.let { emotion ->
                sentEmotion = emotion
                emit(DialogueEvent.Emotion(emotion))
            }
        }
        val raw = reply["line"]?.stringValue ?: return
        val visible = TextCleanup.streamingLine(raw, speaker) ?: return
        show(visible, emit)
    }

    /** Emits whatever makes the displayed text equal the final line. */
    fun finish(line: String, emotion: Emotion, emit: (DialogueEvent) -> Unit) {
        if (sentEmotion != emotion) {
            sentEmotion = emotion
            emit(DialogueEvent.Emotion(emotion))
        }
        show(line, emit)
    }

    private fun show(text: String, emit: (DialogueEvent) -> Unit) {
        if (text == shown) return
        if (text.startsWith(shown)) emit(DialogueEvent.LineDelta(text.substring(shown.length))) else emit(DialogueEvent.LineReset(text))
        shown = text
    }
}
