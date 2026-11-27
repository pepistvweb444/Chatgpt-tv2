package com.jarvis.logic

enum class CallPhase { INCOMING, AI, LIVE, ENDED }

enum class VideoPreview { HIDDEN, LIVE, UNAVAILABLE }

enum class CallButton { ANSWER, LEAVE_AI, MUTE, HANGUP, ANSWER_VIDEO, ANSWER_VOICE }

data class TranscriptLine(val speaker: String, val text: String, val timeLabel: String)

data class CallSnapshot(
    val active: Boolean,
    val phase: CallPhase,
    val video: Boolean,
    val channelLabel: String,
    val name: String,
    val number: String,
    val knownContact: Boolean,
    val sourceLabel: String,
    val ratingText: String? = null,
    val reviewText: String? = null,
    val address: String? = null,
    val initials: String = initialsOf(name),
    val aiConnected: Boolean = false,
    val aiStatus: String = "",
    val ageLabel: String = "",
    val lineLabel: String = "InitTPV",
    val transcript: List<TranscriptLine> = emptyList(),
    val videoPreview: VideoPreview = if (video) VideoPreview.UNAVAILABLE else VideoPreview.HIDDEN,
    val wantsUser: Boolean = false
)

data class LabeledButton(val action: CallButton, val label: String)

data class CallCardModel(
    val visible: Boolean,
    val border: String,
    val badge: String,
    val badgeTone: String,
    val heading: String,
    val meta: String,
    val name: String,
    val numberLine: String,
    val detailLine: String?,
    val address: String?,
    val aiLine: String,
    val banner: String?,
    val buttons: List<LabeledButton>,
    val transcript: List<TranscriptLine>,
    val askLine: String?,
    val videoNote: String?,
    val showVideoFrame: Boolean,
    val homeSubtitle: String,
    val bottomPrompt: String?,
    val bottomDetail: String?
)

fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() && it.any { ch -> ch.isLetter() } }
    if (parts.isEmpty()) return "?"
    if (parts.size == 1) return parts[0].take(1).uppercase()
    return (parts[0].take(1) + parts.last().take(1)).uppercase()
}

fun presentCall(snapshot: CallSnapshot): CallCardModel {
    if (!snapshot.active || snapshot.phase == CallPhase.ENDED) {
        return CallCardModel(
            visible = false, border = "lime", badge = "", badgeTone = "lime", heading = "", meta = "",
            name = "", numberLine = "", detailLine = null, address = null, aiLine = "", banner = null,
            buttons = emptyList(), transcript = emptyList(), askLine = null, videoNote = null,
            showVideoFrame = false, homeSubtitle = "", bottomPrompt = null, bottomDetail = null
        )
    }
    val live = !snapshot.video && (snapshot.phase == CallPhase.LIVE || snapshot.transcript.isNotEmpty())
    val known = snapshot.knownContact
    val border = if (known) "lime" else "orange"
    val badge = when {
        snapshot.video && !live -> "AHORA"
        live && !snapshot.video -> "EN CURSO"
        known -> "AHORA"
        else -> "DESCONOCIDO"
    }
    val badgeTone = when (badge) {
        "DESCONOCIDO" -> "orange"
        "EN CURSO" -> "lime"
        else -> "lime"
    }
    val heading = when {
        snapshot.video -> snapshot.channelLabel.ifBlank { "VIDEOLLAMADA WHATSAPP" }
        live -> "LLAMADA"
        else -> snapshot.channelLabel.ifBlank { "LLAMADA ENTRANTE" }
    }
    val meta = listOf(snapshot.lineLabel.ifBlank { "InitTPV" }, snapshot.ageLabel).filter { it.isNotBlank() }.joinToString(" · ")
    val detail = when {
        known && live -> "Contacto"
        !known && !snapshot.ratingText.isNullOrBlank() -> listOfNotNull(
            snapshot.ratingText?.let { "★ $it" },
            snapshot.reviewText,
            snapshot.sourceLabel.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        known -> snapshot.sourceLabel.ifBlank { "Contacto" }
        else -> snapshot.sourceLabel.ifBlank { null }
    }
    val banner = if (!known) {
        val via = snapshot.sourceLabel.ifBlank { "búsqueda pública" }
        "No está en tus contactos · Vía $via"
    } else null
    val aiLine = snapshot.aiStatus.ifBlank {
        if (snapshot.aiConnected) "IA Premium contestando..." else "IA Premium sin línea InitTPV"
    }
    val buttons = when {
        snapshot.video -> listOf(
            LabeledButton(CallButton.ANSWER_VIDEO, "Coger con vídeo"),
            LabeledButton(CallButton.ANSWER_VOICE, "Solo voz"),
            LabeledButton(CallButton.LEAVE_AI, "Dejar a la IA")
        )
        live -> listOf(
            LabeledButton(CallButton.ANSWER, "Coger la llamada"),
            LabeledButton(CallButton.MUTE, "Silenciar"),
            LabeledButton(CallButton.HANGUP, "Colgar")
        )
        else -> listOf(
            LabeledButton(CallButton.ANSWER, "Coger"),
            LabeledButton(CallButton.LEAVE_AI, "Dejar a la IA")
        )
    }
    val ask = when {
        snapshot.wantsUser && snapshot.name.isNotBlank() -> "${snapshot.name} quiere hablar contigo. ¿Coges la llamada?"
        live && snapshot.transcript.isNotEmpty() && snapshot.name.isNotBlank() -> "${snapshot.name} quiere hablar contigo. ¿Coges la llamada?"
        else -> null
    }
    val videoNote = when (snapshot.videoPreview) {
        VideoPreview.LIVE -> "Vista previa · no se envía tu cámara/micro"
        VideoPreview.UNAVAILABLE -> "La plataforma no puede mostrar el vídeo de quien llama antes de contestar. Tu cámara y tu micro siguen apagados."
        VideoPreview.HIDDEN -> null
    }
    val homeSubtitle = when {
        snapshot.video -> "Ha entrado una videollamada. La IA Premium ya la atiende, sin encender tu cámara ni tu micro."
        snapshot.aiConnected -> "Ha entrado una llamada. La IA Premium ya está contestando."
        else -> "Ha entrado una llamada. Conecta la línea InitTPV para que la IA Premium conteste."
    }
    val bottomPrompt = when {
        snapshot.video -> "¿Dejo que la IA siga con la videollamada?"
        else -> "¿Dejo que la IA siga con la llamada?"
    }
    val bottomDetail = if (snapshot.video) "Tu cámara y tu micro siguen apagados" else "Puedes cogerla en cualquier momento"
    return CallCardModel(
        visible = true,
        border = border,
        badge = badge,
        badgeTone = badgeTone,
        heading = heading,
        meta = meta,
        name = snapshot.name,
        numberLine = snapshot.number,
        detailLine = detail,
        address = snapshot.address?.takeIf { it.isNotBlank() },
        aiLine = aiLine,
        banner = banner,
        buttons = buttons,
        transcript = if (snapshot.video || live) snapshot.transcript else emptyList(),
        askLine = ask,
        videoNote = videoNote,
        showVideoFrame = snapshot.video && snapshot.videoPreview == VideoPreview.LIVE,
        homeSubtitle = homeSubtitle,
        bottomPrompt = bottomPrompt,
        bottomDetail = bottomDetail
    )
}

fun spokenPrompt(snapshot: CallSnapshot): String? {
    if (!snapshot.active || snapshot.phase == CallPhase.ENDED) return null
    val card = presentCall(snapshot)
    val lead = card.homeSubtitle
    val ask = card.bottomPrompt ?: return lead
    return "$lead $ask"
}

object CallFixtures {
    fun lauraIncoming() = CallSnapshot(
        active = true,
        phase = CallPhase.AI,
        video = false,
        channelLabel = "LLAMADA ENTRANTE",
        name = "Laura",
        number = "+34 629 14 82 07",
        knownContact = true,
        sourceLabel = "Contacto",
        aiConnected = true,
        aiStatus = "IA Premium contestando...",
        ageLabel = "hace 3 s",
        initials = "L"
    )

    fun iratiUnknown() = CallSnapshot(
        active = true,
        phase = CallPhase.AI,
        video = false,
        channelLabel = "LLAMADA ENTRANTE",
        name = "Distribuciones Irati",
        number = "+34 948 22 17 40",
        knownContact = false,
        sourceLabel = "Google Maps",
        ratingText = "4,3",
        reviewText = "46 opiniones",
        address = "Polígono Agustinos, nave 18, Pamplona",
        aiConnected = true,
        aiStatus = "IA Premium contestando...",
        ageLabel = "hace 6 s"
    )

    fun lauraLive() = lauraIncoming().copy(
        phase = CallPhase.LIVE,
        ageLabel = "0:42",
        wantsUser = true,
        transcript = listOf(
            TranscriptLine("IA PREMIUM", "Buenas noches, soy la asistente de Alberto. ¿Con quién hablo?", "20:17"),
            TranscriptLine("LAURA", "Hola, soy Laura. ¿Está Alberto? Quería confirmar lo de esta noche.", "20:17"),
            TranscriptLine("IA PREMIUM", "Sí, está disponible. ¿Te lo pongo ahora o prefieres que le deje el recado?", "20:18"),
            TranscriptLine("LAURA", "Si puede coger ahora, mejor. Es solo un momento.", "20:18")
        )
    )

    fun lauraVideoUnavailable() = lauraIncoming().copy(
        video = true,
        channelLabel = "VIDEOLLAMADA WHATSAPP",
        sourceLabel = "WhatsApp",
        ageLabel = "hace 4 s",
        videoPreview = VideoPreview.UNAVAILABLE,
        transcript = listOf(TranscriptLine("LAURA", "¿Estás ahí? Te llamaba un momento por vídeo.", "20:18"))
    )

    fun lauraVideoLive() = lauraVideoUnavailable().copy(videoPreview = VideoPreview.LIVE)
}
