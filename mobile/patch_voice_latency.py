from pathlib import Path

# Main hands-free capture: close utterances faster and update text immediately.
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
s = s.replace('if (now - silenceSince > 1050L)', 'if (now - silenceSince > 620L)')
s = s.replace('voiceHandler.postDelayed(this, 140L)', 'voiceHandler.postDelayed(this, 90L)')
s = s.replace('voiceHandler.postDelayed(monitor, 350L)', 'voiceHandler.postDelayed(monitor, 220L)')
s = s.replace('if (now - startedAt > 18000L)', 'if (now - startedAt > 14000L)')
s = s.replace('voiceHandler.postDelayed({ if (handsFreeMode && !voiceRecording) startOpenAiVoiceCapture() }, 350L)', 'voiceHandler.postDelayed({ if (handsFreeMode && !voiceRecording) startOpenAiVoiceCapture() }, 180L)')
s = s.replace('if (handsFreeMode) voiceHandler.postDelayed({ if (!voiceRecording) startOpenAiVoiceCapture() }, 500L)', 'if (handsFreeMode) voiceHandler.postDelayed({ if (!voiceRecording) startOpenAiVoiceCapture() }, 250L)')
p.write_text(s)

# Background TTS: smaller first chunk so Jarvis starts speaking sooner, and normal Spanish pace.
p = Path('mobile/src/main/java/com/jarvis/mobile/MobileSpeechService.kt')
s = p.read_text()
s = s.replace('val limit = minOf(320, rest.length)', 'val limit = minOf(if (out.isEmpty()) 135 else 250, rest.length)')
s = s.replace('filter { it >= 70 }', 'filter { it >= if (out.isEmpty()) 45 else 65 }')
s = s.replace('.put("speed", 0.94)', '.put("speed", 1.06)')
s = s.replace('readTimeout = 30000', 'readTimeout = 22000')
p.write_text(s)

print('Low-latency voice patch applied')
