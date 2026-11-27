package com.jarvis.mobile

import com.jarvis.logic.CallButton
import com.jarvis.logic.CallPhase
import com.jarvis.logic.CallSnapshot
import com.jarvis.logic.TranscriptLine
import com.jarvis.logic.VideoPreview
import com.jarvis.logic.initialsOf
import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.toCallSnapshot(now: Long = System.currentTimeMillis()): CallSnapshot {
    val rawPhase = optString("phase").ifBlank { optString("state") }
    val active = optBoolean("active", false)
    val phase = when (rawPhase) {
        "live" -> CallPhase.LIVE
        "ai" -> CallPhase.AI
        "ended", "rejected", "answered" -> if (rawPhase == "answered") CallPhase.LIVE else CallPhase.ENDED
        else -> if (active) CallPhase.INCOMING else CallPhase.ENDED
    }
    val video = optBoolean("video", false)
    val preview = when (optString("videoPreview")) {
        "live" -> VideoPreview.LIVE
        "unavailable" -> VideoPreview.UNAVAILABLE
        else -> if (video) VideoPreview.UNAVAILABLE else VideoPreview.HIDDEN
    }
    val started = optLong("time", now)
    val ageSec = ((now - started) / 1000L).coerceAtLeast(0L)
    val ageLabel = when (phase) {
        CallPhase.LIVE -> "%d:%02d".format(ageSec / 60, ageSec % 60)
        else -> "hace ${ageSec} s"
    }
    val lines = mutableListOf<TranscriptLine>()
    val transcript = optJSONArray("transcript") ?: JSONArray()
    for (i in 0 until transcript.length()) {
        val line = transcript.optJSONObject(i) ?: continue
        val text = line.optString("text")
        if (text.isBlank()) continue
        lines += TranscriptLine(
            speaker = line.optString("speaker").ifBlank { "IA PREMIUM" },
            text = text,
            timeLabel = line.optString("timeLabel").ifBlank { line.optString("at") }
        )
    }
    val name = optString("name")
    return CallSnapshot(
        active = active && phase != CallPhase.ENDED,
        phase = if (active) phase else CallPhase.ENDED,
        video = video,
        channelLabel = optString("channelLabel").ifBlank {
            if (video) "VIDEOLLAMADA ${optString("app").uppercase()}" else "LLAMADA ENTRANTE"
        },
        name = name,
        number = optString("number"),
        knownContact = optBoolean("knownContact", false),
        sourceLabel = optString("sourceLabel").ifBlank { optString("publicSource") },
        ratingText = optString("ratingText").ifBlank { null },
        reviewText = optString("reviewText").ifBlank { null },
        address = optString("address").ifBlank { null },
        initials = optString("initials").ifBlank { initialsOf(name) },
        aiConnected = optBoolean("aiConnected", false),
        aiStatus = optString("aiStatus"),
        ageLabel = optString("ageLabel").ifBlank { ageLabel },
        lineLabel = optString("lineLabel").ifBlank { "InitTPV" },
        transcript = lines,
        videoPreview = preview,
        wantsUser = optBoolean("wantsUser", false)
    )
}

fun CallButton.wireName(): String = when (this) {
    CallButton.ANSWER, CallButton.ANSWER_VOICE -> "answer"
    CallButton.ANSWER_VIDEO -> "answer_video"
    CallButton.LEAVE_AI -> "leave_ai"
    CallButton.MUTE -> "mute"
    CallButton.HANGUP -> "hangup"
}
