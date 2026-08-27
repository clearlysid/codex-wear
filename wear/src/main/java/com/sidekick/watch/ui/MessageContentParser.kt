package com.sidekick.watch.ui

sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class Image(val url: String, val altText: String = "") : MessageSegment()
}

private val markdownImageRegex = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")
private val bareImageUrlRegex = Regex("""https?://\S+\.(?:png|jpg|jpeg|gif|webp|svg)(?:\?\S*)?""", RegexOption.IGNORE_CASE)

fun parseMessageContent(text: String): List<MessageSegment> {
    data class Match(val range: IntRange, val url: String, val alt: String)

    val matches = mutableListOf<Match>()

    for (m in markdownImageRegex.findAll(text)) {
        matches.add(Match(m.range, m.groupValues[2], m.groupValues[1]))
    }

    // Only add bare URLs that don't overlap with markdown images
    for (m in bareImageUrlRegex.findAll(text)) {
        if (matches.none { it.range.first <= m.range.first && it.range.last >= m.range.last }) {
            matches.add(Match(m.range, m.value, ""))
        }
    }

    if (matches.isEmpty()) return listOf(MessageSegment.Text(text))

    matches.sortBy { it.range.first }

    val segments = mutableListOf<MessageSegment>()
    var cursor = 0

    for (match in matches) {
        if (match.range.first > cursor) {
            val before = text.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) segments.add(MessageSegment.Text(before))
        }
        segments.add(MessageSegment.Image(match.url, match.alt))
        cursor = match.range.last + 1
    }

    if (cursor < text.length) {
        val after = text.substring(cursor).trim()
        if (after.isNotEmpty()) segments.add(MessageSegment.Text(after))
    }

    return segments
}
