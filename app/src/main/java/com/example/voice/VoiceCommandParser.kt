package com.example.voice

import java.util.Locale

sealed class VoiceCommand {
    data class PlaySearch(val query: String) : VoiceCommand()
    data class SearchOnly(val query: String) : VoiceCommand()
    object Pause : VoiceCommand()
    object Resume : VoiceCommand()
    object Next : VoiceCommand()
    object Previous : VoiceCommand()
    object Shuffle : VoiceCommand()
    object Repeat : VoiceCommand()
    object OpenLyrics : VoiceCommand()
    object OpenDownloads : VoiceCommand()
    object GoBack : VoiceCommand()
}

object VoiceCommandParser {

    fun parse(spokenText: String): VoiceCommand {
        val trimmed = spokenText.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) {
            return VoiceCommand.SearchOnly("")
        }

        return when {
            // Pause / Stop
            trimmed == "pause" || trimmed == "pause music" || trimmed == "stop" || trimmed == "stop music" ||
            trimmed == "ruko" || trimmed == "gaana roko" -> {
                VoiceCommand.Pause
            }

            // Resume / Play current
            trimmed == "resume" || trimmed == "resume music" || trimmed == "play" || trimmed == "play music" ||
            trimmed == "chalao" || trimmed == "bajaao" -> {
                VoiceCommand.Resume
            }

            // Next
            trimmed.contains("next song") || trimmed.contains("play the next") || trimmed == "next" ||
            trimmed.contains("agla gaana") || trimmed == "agla" -> {
                VoiceCommand.Next
            }

            // Previous
            trimmed.contains("previous song") || trimmed.contains("play the previous") || trimmed == "previous" ||
            trimmed.contains("pichla gaana") || trimmed == "pichla" -> {
                VoiceCommand.Previous
            }

            // Shuffle
            trimmed.contains("shuffle") || trimmed.contains("randomize") -> {
                VoiceCommand.Shuffle
            }

            // Repeat
            trimmed.contains("repeat this song") || trimmed.contains("repeat one") || trimmed.contains("loop this") ||
            trimmed == "repeat" -> {
                VoiceCommand.Repeat
            }

            // Lyrics
            trimmed.contains("open lyrics") || trimmed.contains("show lyrics") || trimmed == "lyrics" ||
            trimmed.contains("bol dikhao") -> {
                VoiceCommand.OpenLyrics
            }

            // Downloads
            trimmed.contains("play my downloads") || trimmed.contains("open downloads") || trimmed.contains("downloaded songs") ||
            trimmed == "downloads" -> {
                VoiceCommand.OpenDownloads
            }

            // Go back
            trimmed == "go back" || trimmed == "back" || trimmed == "wapas" -> {
                VoiceCommand.GoBack
            }

            // Explicit Search: "search <query>"
            trimmed.startsWith("search ") || trimmed.startsWith("find ") || trimmed.startsWith("khojo ") -> {
                val query = trimmed.replaceFirst(Regex("^(search|find|khojo)\\s+"), "").trim()
                VoiceCommand.SearchOnly(query)
            }

            // "play <song title>"
            trimmed.startsWith("play ") || trimmed.startsWith("bajaao ") || trimmed.startsWith("suno ") -> {
                val query = trimmed.replaceFirst(Regex("^(play|bajaao|suno)\\s+"), "").trim()
                VoiceCommand.PlaySearch(query)
            }

            // Default: Search for whatever was spoken
            else -> {
                VoiceCommand.PlaySearch(trimmed)
            }
        }
    }
}
