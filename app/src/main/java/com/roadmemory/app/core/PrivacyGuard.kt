package com.roadmemory.app.core

/**
 * "듣지만 남기지 않는다" — listens, but keeps only what the family wants.
 *
 *  - Nothing is recorded without consent.
 *  - The microphone opens only right after a question, never in the background.
 *  - Voice commands can pause recording or delete the last memory.
 *  - Text that looks like phone numbers, card numbers or emails is masked before storing.
 */
object PrivacyGuard {

    enum class Command { PAUSE, RESUME, DELETE_LAST, NONE }

    fun detectCommand(text: String): Command {
        val t = text.replace(" ", "")
        return when {
            listOf("지워줘", "삭제해", "방금거지워").any { it in t } -> Command.DELETE_LAST
            listOf("기록하지마", "녹음하지마", "그만기록", "기록멈춰").any { it in t } -> Command.PAUSE
            listOf("다시기록", "기록다시", "기록시작").any { it in t } -> Command.RESUME
            else -> Command.NONE
        }
    }

    private val phone = Regex("""01[016789][-\s]?\d{3,4}[-\s]?\d{4}""")
    private val card = Regex("""\b\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}\b""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    fun mask(text: String): String = text
        .replace(card, "[카드번호]")
        .replace(phone, "[전화번호]")
        .replace(email, "[이메일]")

    /** Local summary used until the server summarizer is connected: first sentence, max 60 chars. */
    fun summarize(text: String): String {
        val clean = mask(text).trim()
        val first = clean.split('.', '?', '!', '\n').firstOrNull { it.isNotBlank() }?.trim() ?: clean
        return if (first.length <= 60) first else first.take(57) + "..."
    }
}
