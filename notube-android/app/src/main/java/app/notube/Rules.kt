package app.notube

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** 규칙의 효과. */
object Effect {
    /** 유튜브를 봐도 시간이 안 줄고 참기 기록도 안 끊김. */
    const val FREE = "free"
    /** 안 봐도 시간이 안 쌓임 (예: 잠자는 시간). */
    const val NO_EARN = "no_earn"
    /** 지갑에 시간이 있어도 유튜브 금지. */
    const val BLOCK = "block"
    /** 참으면 2배로 쌓임. */
    const val DOUBLE = "double"

    val ALL = listOf(FREE, NO_EARN, BLOCK, DOUBLE)

    fun label(e: String) = when (e) {
        FREE -> "자유 시간"
        NO_EARN -> "적립 쉬기"
        BLOCK -> "유튜브 금지"
        DOUBLE -> "2배 적립"
        else -> e
    }

    fun desc(e: String) = when (e) {
        FREE -> "유튜브를 봐도 시간이 안 줄고, 참기 기록도 안 끊겨요"
        NO_EARN -> "안 봐도 시간이 안 쌓여요"
        BLOCK -> "지갑에 시간이 있어도 유튜브가 막혀요"
        DOUBLE -> "참으면 두 배로 쌓여요"
        else -> ""
    }
}

/**
 * 나만의 규칙: [days] 요일의 [startMin]~[endMin] 동안 [effect]를 적용해요.
 * 시간은 자정부터 센 분(0~1439). 끝이 시작보다 이르면 다음 날까지 이어져요(예: 23:00~07:00).
 * 시작과 끝이 같으면 하루 종일. 밤을 넘기는 규칙은 시작하는 날의 요일을 따라요.
 */
data class Rule(
    val id: String,
    val name: String,
    val effect: String,
    /** DayOfWeek.value: 1=월 … 7=일 */
    val days: Set<Int>,
    val startMin: Int,
    val endMin: Int,
    val enabled: Boolean = true,
    /** 이 규칙이 적용되기 시작한 시각. 규칙을 바꿔도 그 전 시간까지 거슬러 계산되지 않게 해요. */
    val since: Long = 0,
    /** 이 규칙이 끝난 시각 (바뀌거나 지워지면 기록돼요). 지금 쓰는 규칙은 Long.MAX_VALUE. */
    val until: Long = Long.MAX_VALUE,
) {
    val current get() = until == Long.MAX_VALUE

    /** 시각 정보를 뺀 내용만 비교할 때. */
    fun sameContent(o: Rule?) = o != null && copy(since = 0, until = Long.MAX_VALUE) == o.copy(since = 0, until = Long.MAX_VALUE)

    val allDay get() = startMin == endMin

    fun timeText(): String = if (allDay) "하루 종일" else "${hhmm(startMin)}–${hhmm(endMin)}"

    fun daysText(): String = when (days) {
        (1..7).toSet() -> "매일"
        (1..5).toSet() -> "주중"
        setOf(6, 7) -> "주말"
        else -> days.sorted().joinToString("·") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.KOREAN) }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("e", effect)
        .put("d", JSONArray().apply { days.sorted().forEach { put(it) } })
        .put("s", startMin).put("t", endMin).put("on", enabled)
        .put("since", since).put("until", until)

    companion object {
        fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

        fun fromJson(o: JSONObject): Rule {
            val d = o.getJSONArray("d")
            return Rule(
                o.getString("id"), o.getString("n"), o.getString("e"),
                (0 until d.length()).map { d.getInt(it) }.toSet(),
                o.getInt("s"), o.getInt("t"), o.optBoolean("on", true),
                o.optLong("since", 0), o.optLong("until", Long.MAX_VALUE),
            )
        }

        /** 자주 쓰는 규칙. 버튼 하나로 추가할 수 있어요. */
        val PRESETS = listOf(
            Rule("p_sleep", "잠자는 시간", Effect.NO_EARN, (1..7).toSet(), 23 * 60, 7 * 60),
            Rule("p_weekend", "주말 자유 시간", Effect.FREE, setOf(6, 7), 0, 0),
            Rule("p_study", "공부·일하는 시간", Effect.BLOCK, (1..5).toSet(), 9 * 60, 18 * 60),
        )
    }
}

/** 규칙을 시간 계산에 적용하는 순수 로직. */
object RuleMath {
    /** [rule]이 [from, to) 안에서 켜져 있는 구간들. */
    fun windows(rule: Rule, fromIn: Long, toIn: Long, zone: ZoneId): List<Pair<Long, Long>> {
        // 규칙이 실제로 적용되던 기간 안에서만
        val from = max(fromIn, rule.since)
        val to = min(toIn, rule.until)
        if (!rule.enabled || to <= from || rule.days.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Long, Long>>()
        var d = Ledger.dayOf(from, zone).minusDays(1) // 전날 밤에 시작한 구간까지
        val last = Ledger.dayOf(to, zone)
        while (!d.isAfter(last)) {
            if (d.dayOfWeek.value in rule.days) {
                val startDt = d.atTime(LocalTime.of(rule.startMin / 60, rule.startMin % 60)).atZone(zone)
                val endDt = when {
                    rule.allDay -> startDt.plusDays(1)
                    rule.endMin > rule.startMin -> d.atTime(LocalTime.of(rule.endMin / 60, rule.endMin % 60)).atZone(zone)
                    else -> d.plusDays(1).atTime(LocalTime.of(rule.endMin / 60, rule.endMin % 60)).atZone(zone)
                }
                val a = max(from, startDt.toInstant().toEpochMilli())
                val b = min(to, endDt.toInstant().toEpochMilli())
                if (a < b) out += a to b
            }
            d = d.plusDays(1)
        }
        return out
    }

    private fun windowsFor(rules: List<Rule>, effect: String, from: Long, to: Long, zone: ZoneId) =
        rules.filter { it.effect == effect }.flatMap { windows(it, from, to, zone) }

    private fun inAny(ws: List<Pair<Long, Long>>, t: Long) = ws.any { t >= it.first && t < it.second }

    /** 지금 켜져 있는 규칙들. */
    fun activeAt(rules: List<Rule>, t: Long, zone: ZoneId): List<Rule> =
        rules.filter { r -> windows(r, t, t + 1, zone).isNotEmpty() }

    /** 지금 켜져 있는 [rule] 구간이 끝나는 시각. */
    fun activeUntil(rule: Rule, t: Long, zone: ZoneId): Long? =
        windows(rule, t, t + 3 * 24 * HOUR, zone).firstOrNull { it.first <= t }?.second

    /**
     * [streakStart]에 시작한 참기 기록에서 [from, to) 동안 쌓이는 시간(분).
     * 오래 참을수록 빨라지는 배수(TIERS)와 규칙(적립 쉬기 0배, 2배 적립)을 함께 적용해요.
     */
    fun earnedIn(streakStart: Long, from: Long, to: Long, rules: List<Rule>, zone: ZoneId): Double {
        if (to <= from) return 0.0
        val noEarn = windowsFor(rules, Effect.NO_EARN, from, to, zone)
        val double = windowsFor(rules, Effect.DOUBLE, from, to, zone)
        if (noEarn.isEmpty() && double.isEmpty()) return earnedFor(to - streakStart) - earnedFor(from - streakStart)
        val cuts = sortedSetOf(from, to)
        TIERS.forEach { val c = streakStart + it.from; if (c in (from + 1) until to) cuts += c }
        (noEarn + double).forEach { (a, b) -> cuts += a; cuts += b }
        var sum = 0.0
        val pts = cuts.toList()
        for (i in 0 until pts.size - 1) {
            val x = pts[i]; val y = pts[i + 1]
            if (y <= x) continue
            val mid = x + (y - x) / 2
            val mult = when {
                inAny(noEarn, mid) -> 0.0
                inAny(double, mid) -> 2.0
                else -> 1.0
            }
            if (mult == 0.0) continue
            sum += (y - x).toDouble() / HOUR * TIERS[tierIndex(mid - streakStart)].ratePerHour * mult
        }
        return sum
    }

    /** [from, to) 시청 중 시간이 빠지는 부분(ms). 자유 시간과 [extraFree](비상 탈출권 등) 구간은 빼요. */
    fun chargedMs(from: Long, to: Long, rules: List<Rule>, zone: ZoneId, extraFree: List<Pair<Long, Long>> = emptyList()): Long {
        if (to <= from) return 0
        val clipped = extraFree.mapNotNull { (a, b) -> val x = max(a, from); val y = min(b, to); if (x < y) x to y else null }
        val free = (windowsFor(rules, Effect.FREE, from, to, zone) + clipped).sortedBy { it.first }
        if (free.isEmpty()) return to - from
        // 겹치는 자유 시간 구간을 합쳐서 길이를 빼요
        var freeMs = 0L
        var curA = free[0].first; var curB = free[0].second
        for ((a, b) in free.drop(1)) {
            if (a <= curB) curB = max(curB, b) else { freeMs += curB - curA; curA = a; curB = b }
        }
        freeMs += curB - curA
        return max(0, (to - from) - freeMs)
    }

    fun isActive(rules: List<Rule>, effect: String, t: Long, zone: ZoneId) =
        windowsFor(rules, effect, t, t + 1, zone).isNotEmpty()
}
