package com.roadmemory.app.core

import com.roadmemory.app.data.TripMode
import java.util.Calendar

/**
 * Picks "today's question" from the trip context. Questions are short, open,
 * and answerable by a child in one breath.
 */
class PromptEngine(private val random: kotlin.random.Random = kotlin.random.Random.Default) {

    data class Context(
        val mode: TripMode,
        val hourOfDay: Int,
        val dayOfWeek: Int,
        val rainy: Boolean,
        val hasRearPassenger: Boolean,
        val destinationVisitCount: Int,
        val destinationName: String?,
        /** Driver switched on "pet on board" (seat sensors cannot tell a dog from a child). */
        val petOnBoard: Boolean = false,
    )

    fun pick(ctx: Context, recentlyAsked: Collection<String> = emptyList()): String {
        val pool = buildList {
            if (ctx.rainy) addAll(RAIN)
            if (ctx.destinationVisitCount == 0 && ctx.destinationName != null) {
                add("${ctx.destinationName}에 처음 가 보네요. 어떤 곳일 것 같아요?")
            }
            when (ctx.mode) {
                TripMode.TRAVEL -> addAll(TRAVEL)
                TripMode.DAILY -> {
                    if (ctx.hourOfDay < 12) addAll(MORNING) else addAll(AFTERNOON)
                    if (ctx.dayOfWeek == Calendar.FRIDAY) addAll(FRIDAY)
                }
            }
            if (ctx.hasRearPassenger) addAll(KIDS)
            // A dog on board is the strongest signal: weight its questions double.
            if (ctx.petOnBoard) repeat(2) { addAll(PET) }
        }.filterNot { it in recentlyAsked }

        return if (pool.isEmpty()) FALLBACK else pool[random.nextInt(pool.size)]
    }

    companion object {
        const val FALLBACK = "오늘 제일 기억에 남는 일 하나만 말해 줄래요?"

        val MORNING = listOf(
            "오늘 제일 기대되는 시간은 뭐예요?",
            "오늘 누구랑 제일 먼저 인사하고 싶어요?",
            "어젯밤에 꾼 꿈 기억나요?",
        )
        val AFTERNOON = listOf(
            "오늘 제일 웃겼던 일은 뭐였어요?",
            "오늘 새로 알게 된 거 하나만 알려 줄래요?",
            "오늘 점심에 뭐 먹었어요? 맛있었어요?",
        )
        val FRIDAY = listOf("이번 주말에 꼭 하고 싶은 거 하나만 말해 볼까요?")
        val RAIN = listOf("비 오는 날 하면 제일 좋은 건 뭐예요?")
        val KIDS = listOf(
            "지금 창밖에서 제일 재미있게 생긴 걸 찾아볼래요?",
            "요즘 제일 친한 친구는 누구예요?",
        )
        val PET = listOf(
            "오늘 산책에서 우리 강아지가 제일 좋아한 건 뭐였어요?",
            "우리 강아지가 지금 창밖을 보면 무슨 생각을 할까요?",
            "처음 우리 강아지를 만난 날 기억나요?",
            "우리 강아지한테 오늘 하루를 한 단어로 붙여 준다면?",
        )
        val TRAVEL = listOf(
            "이번 여행에서 제일 하고 싶은 게 뭐예요?",
            "지금 보이는 풍경에 이름을 붙인다면?",
            "여기 오면 생각나는 노래가 있어요?",
            "오늘 여행을 한 단어로 말하면?",
        )

        fun contextNow(
            mode: TripMode,
            rainy: Boolean,
            hasRearPassenger: Boolean,
            destinationVisitCount: Int,
            destinationName: String?,
            petOnBoard: Boolean = false,
        ): Context {
            val c = Calendar.getInstance()
            return Context(
                mode = mode,
                hourOfDay = c.get(Calendar.HOUR_OF_DAY),
                dayOfWeek = c.get(Calendar.DAY_OF_WEEK),
                rainy = rainy,
                hasRearPassenger = hasRearPassenger,
                destinationVisitCount = destinationVisitCount,
                destinationName = destinationName,
                petOnBoard = petOnBoard,
            )
        }
    }
}
