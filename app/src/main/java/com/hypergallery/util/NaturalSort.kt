package com.hypergallery.util

import java.util.Locale

object NaturalSort {

    private val chunkRegex = Regex("(\\d+)|(\\D+)")

    /**
     * Generates a lexicographically comparable key that produces true natural alphanumeric ordering
     * directly inside SQLite ORDER BY (e.g. "Photo_2.jpg" -> "photo_0000000000002.jpg" < "Photo_10.jpg").
     */
    fun buildKey(input: String): String {
        val lower = input.lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length + 16)
        for (match in chunkRegex.findAll(lower)) {
            val digits = match.groups[1]?.value
            if (digits != null) {
                val trimmed = digits.trimStart('0').ifEmpty { "0" }
                val padLen = (16 - trimmed.length).coerceAtLeast(0)
                repeat(padLen) { sb.append('0') }
                sb.append(trimmed)
            } else {
                sb.append(match.value)
            }
        }
        return sb.toString()
    }

    val comparator: Comparator<String> = Comparator { a, b ->
        buildKey(a).compareTo(buildKey(b))
    }
}

object ByteFormatting {
    fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes o"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.FRANCE, "%.1f Ko", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.FRANCE, "%.1f Mo", mb)
        val gb = mb / 1024.0
        return String.format(Locale.FRANCE, "%.2f Go", gb)
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }
}
