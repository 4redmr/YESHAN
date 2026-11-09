package app.notube

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

const val MIN = 60_000L
const val HOUR = 3_600_000L

/** 저금통 이자율: 자정부터 다음 자정까지 계속 들어 있던 시간에 붙어요. */
const val BANK_DAILY_RATE = 0.10

/**
 * 유튜브를 안 볼수록 "볼 수 있는 시간"이 쌓여요. rate = 안 본 1시간당 쌓이는 분.
 * 기본: 안 본 10분 → 볼 수 있는 1분. 오래 참을수록 빨라져요.
 */
data class Tier(val from: Long, val ratePerHour: Double, val label: String, val per10: String)

val TIERS = listOf(
    Tier(0, 6.0, "기본", "10분 참을 때마다 +1분"),
    Tier(3 * HOUR, 9.0, "1.5배", "10분 참을 때마다 +1.5분"),
    Tier(12 * HOUR, 12.0, "2배", "10분 참을 때마다 +2분"),
    Tier(24 * HOUR, 18.0, "3배", "10분 참을 때마다 +3분"),
)

/** 연속으로 [ms] 동안 유튜브를 안 봤을 때 쌓이는 시간(분). */
fun earnedFor(ms: Long): Double {
    var c = 0.0
    for (i in TIERS.indices) {
        val a = TIERS[i].from
        val b = if (i + 1 < TIERS.size) TIERS[i + 1].from else Long.MAX_VALUE
        if (ms <= a) break
        c += (min(ms, b) - a).toDouble() / HOUR * TIERS[i].ratePerHour
    }
    return c
}

fun tierIndex(ms: Long): Int = TIERS.indexOfLast { ms >= it.from }.coerceAtLeast(0)

data class StreakRec(val start: Long, val end: Long, val earned: Double)
data class WatchRec(val start: Long, val end: Long)

/** 저금통 기록. kind: in(넣음), out(깸), interest(이자). */
data class BankRec(val time: Long, val kind: String, val minutes: Double)

/** 눈에 띄어야 하는 일의 기록: 규칙 변경, 비상 탈출권, 보호 꺼짐, 활동 보너스 등. 지울 수 없어요. */
data class Note(val time: Long, val kind: String, val text: String)

/** 하루 뒤에 적용될 규칙 변경. [rule]이 null이면 [ruleId] 규칙 삭제. */
data class PendingRule(val at: Long, val ruleId: String, val rule: Rule?)

/** 지금 하고 있는 대체 활동 (기도, 운동 등). [watchedBase]: 시작할 때까지 그날 본 유튜브(ms), 짧게 연 것도 잡으려고. */
data class Activity(val name: String, val start: Long, val watchedBase: Long = 0)

/** 비상 탈출권: 10분 바로 열기, 대신 15분 빚. 하루 1번, 일주일(월~일)에 3번까지. */
const val PASS_MS = 10 * MIN
const val PASS_DEBT_MIN = 15.0
const val PASS_PER_WEEK = 3

/** 규칙을 느슨하게 바꾸면 이만큼 뒤에 적용돼요. 엄격하게 바꾸는 건 바로 적용. */
const val RULE_COOLDOWN_MS = 24 * HOUR

/** 대체 활동 보너스: 10분마다 +1분, 하루 30분까지. 활동 중 유튜브를 보면 보너스 없음. */
const val ACTIVITY_BONUS_PER_10MIN = 1.0
const val ACTIVITY_BONUS_DAILY_CAP = 30.0
val ACTIVITIES = listOf("기도", "운동", "말씀읽기", "독서", "공부")

/** 통계용 기록 보관 기간. */
const val STATS_KEEP_DAYS = 400L

/**
 * 앱의 모든 상태. 하나의 JSON으로 저장돼요.
 *
 * 볼 수 있는 시간 = 번 시간 − 본 시간.
 * 번 시간: 유튜브를 안 보는 연속 기록([streakStart]~)이 끝날 때 [totalEarned]로 적립.
 * 본 시간: 유튜브가 화면에 있던 시간 전부 ([spentMs] + 지금 보는 중인 시간).
 */
class Ledger(
    var trackingSince: Long = 0,
    /** 이벤트를 어디까지 처리했는지 (마지막으로 처리한 이벤트 시각). */
    var lastEventTime: Long = 0,
    /** 현재 연속 기록 시작 시각. 유튜브를 보는 중이면 null. */
    var streakStart: Long? = null,
    /** 유튜브가 화면에 떠 있기 시작한 시각. */
    var ytOpenSince: Long? = null,
    /** 유튜브가 잠깐 내려간 시각. 짧은 끊김(회전, 화면 전환)은 같은 시청으로 이어 붙여요. */
    var ytPausedAt: Long? = null,
    /** 이번 시청 때문에 연속 기록이 이미 끝났는지. */
    var brokeThisSession: Boolean = false,
    /** 지금 화면에 보이는 유튜브 화면(액티비티)들. 비어 있으면 유튜브가 안 보이는 것. */
    val ytVisible: MutableSet<String> = mutableSetOf(),
    /** 이번 연속 기록 동안 봐준 짧은 시청 시간의 합. */
    var graceUsed: Long = 0,
    /** 지금까지 번 시간(분). 예전 버전의 시작 선물이 있으면 포함. */
    var totalEarned: Double = 0.0,
    /** 예전 버전에서 받은 시작 선물(분). '참아서 번 시간'에서 빼려고 기억해 둬요. */
    var bonus: Double = 0.0,
    /** 저금통에 든 시간(분, 이자 포함). 유튜브에는 못 써요. */
    var bank: Double = 0.0,
    /** 오늘 자정부터 지금까지 저금통에 계속 들어 있던 최소량. 다음 자정에 이만큼에 이자가 붙어요. */
    var bankHeld: Double = 0.0,
    /** 이자 계산 중인 날짜 (yyyy-MM-dd). */
    var bankDay: String = "",
    /** 지갑에서 저금통으로 옮긴 시간 합계 / 저금통에서 지갑으로 꺼낸 시간 합계. */
    var toBank: Double = 0.0,
    var fromBank: Double = 0.0,
    val bankLog: MutableList<BankRec> = mutableListOf(),
    /** 나만의 규칙 (요일·시간대별 자유 시간, 적립 쉬기, 유튜브 금지, 2배 적립). */
    val rules: MutableList<Rule> = mutableListOf(),
    /** 지금까지 유튜브를 본 시간(ms). 끝난 시청만. */
    var spentMs: Long = 0,
    var best: Long = 0,
    val streaks: MutableList<StreakRec> = mutableListOf(),
    val watches: MutableList<WatchRec> = mutableListOf(),
    /** yyyy-MM-dd -> 그날 유튜브를 본 시간(ms). */
    val watchedByDay: MutableMap<String, Long> = mutableMapOf(),
    /** yyyy-MM-dd -> 그날 지갑에서 실제로 빠진 시청 시간(ms). 자유 시간·탈출권 시간은 빠져요. */
    val chargedByDay: MutableMap<String, Long> = mutableMapOf(),
    /** yyyy-MM-dd -> 그날 참아서 번 시간(분). 끝난 참기 기록만. */
    val earnedByDay: MutableMap<String, Double> = mutableMapOf(),
    /** 비상 탈출권 빚 등 지갑에서 빼는 시간(분). */
    var penalty: Double = 0.0,
    /** 비상 탈출권을 쓴 시각들. */
    val passes: MutableList<Long> = mutableListOf(),
    val pendingRules: MutableList<PendingRule> = mutableListOf(),
    val notes: MutableList<Note> = mutableListOf(),
    /** 보호(사용 시간 접근, 다른 앱 위에 표시)가 꺼진 시각. 켜져 있으면 null. */
    var protectionOffSince: Long? = null,
    var protectionOffTotalMs: Long = 0,
    var activity: Activity? = null,
    /** yyyy-MM-dd -> 그날 받은 대체 활동 보너스(분). */
    val activityBonusByDay: MutableMap<String, Double> = mutableMapOf(),
) {
    val started get() = trackingSince > 0
    val watchingNow get() = ytOpenSince != null && ytPausedAt == null

    /** 지금 진행 중인 연속 기록에서 쌓이고 있는 시간(분). 규칙(적립 쉬기, 2배 적립)을 반영해요. */
    fun liveEarned(now: Long, zone: ZoneId = ZoneId.systemDefault()): Double =
        streakStart?.let { RuleMath.earnedIn(it, it, max(it, now), rules, zone) } ?: 0.0

    /** 비상 탈출권이 열려 있는 구간들. 이 시간엔 자유 시간처럼 안 빠지고 안 막혀요. */
    fun passWindows(): List<Pair<Long, Long>> = passes.map { it to it + PASS_MS }
    fun passActiveUntil(now: Long): Long? = passWindows().firstOrNull { now >= it.first && now < it.second }?.second

    /** 지금 보고 있는 시청 중 시간이 빠지는 부분(ms). 자유 시간·탈출권 시간은 빼요. */
    fun liveChargedMs(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        ytOpenSince?.let { RuleMath.chargedMs(it, ytPausedAt ?: now, rules, zone, passWindows()) } ?: 0

    /** 지금 쓰는 규칙 (바뀌거나 지워진 옛 규칙은 계산용으로만 남아 있어요). */
    fun currentRules(): List<Rule> = rules.filter { it.current }

    fun activeRules(now: Long, zone: ZoneId = ZoneId.systemDefault()) = RuleMath.activeAt(rules, now, zone)
    fun freeNow(now: Long, zone: ZoneId = ZoneId.systemDefault()) =
        RuleMath.isActive(rules, Effect.FREE, now, zone) || passActiveUntil(now) != null
    fun blockRuleNow(now: Long, zone: ZoneId = ZoneId.systemDefault()) =
        RuleMath.activeAt(rules, now, zone).firstOrNull { it.effect == Effect.BLOCK }

    /** 지금 보고 있는(아직 정산 안 된) 시청 시간(ms). */
    fun liveWatchMs(now: Long): Long = ytOpenSince?.let { max(0, (ytPausedAt ?: now) - it) } ?: 0

    /** 지갑: 지금 유튜브를 볼 수 있는 시간(분, 소수). 0 이하면 유튜브가 막혀요. */
    fun remaining(now: Long, zone: ZoneId = ZoneId.systemDefault()): Double =
        totalEarned + liveEarned(now, zone) - (spentMs + liveChargedMs(now, zone)).toDouble() / MIN - toBank + fromBank - penalty

    /** 참아서 번 시간(분). 예전 시작 선물과 저금통 이자는 빼요. */
    fun earnedByWaiting(now: Long, zone: ZoneId = ZoneId.systemDefault()): Double = max(0.0, totalEarned + liveEarned(now, zone) - bonus)

    /** 다음 자정에 붙을 이자(분). */
    fun pendingInterest(): Double = bankHeld * BANK_DAILY_RATE

    fun remainingMin(now: Long, zone: ZoneId = ZoneId.systemDefault()): Int = kotlin.math.floor(remaining(now, zone)).toInt()

    fun currentStreak(now: Long): Long = streakStart?.let { max(0, now - it) } ?: 0

    /** [day]에 지갑에서 빠진 시청 시간(ms). 지금 보는 중인 것도 포함. */
    fun chargedOn(day: LocalDate, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        var ms = chargedByDay[day.toString()] ?: 0
        val open = ytOpenSince
        if (open != null) {
            val d0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val d1 = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val a = max(open, d0); val b = min(ytPausedAt ?: now, d1)
            if (b > a) ms += RuleMath.chargedMs(a, b, rules, zone, passWindows())
        }
        return ms
    }

    /** [day]에 참아서 번 시간(분). 지금 진행 중인 기록 몫도 포함. */
    fun earnedOn(day: LocalDate, now: Long, zone: ZoneId = ZoneId.systemDefault()): Double {
        var m = earnedByDay[day.toString()] ?: 0.0
        val s = streakStart
        if (s != null) {
            val d0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val d1 = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val a = max(s, d0); val b = min(now, d1)
            if (b > a) m += RuleMath.earnedIn(s, a, b, rules, zone)
        }
        return m + (activityBonusByDay[day.toString()] ?: 0.0)
    }

    fun watchedOn(day: LocalDate, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        var ms = watchedByDay[day.toString()] ?: 0
        val open = ytOpenSince
        if (open != null) ms += overlap(open, ytPausedAt ?: now, day, zone)
        return ms
    }

    fun copy(): Ledger = fromJson(toJson())

    fun toJson(): String = JSONObject().apply {
        put("v", 4)
        put("bonus", bonus)
        put("bank", bank)
        put("bankHeld", bankHeld)
        put("bankDay", bankDay)
        put("toBank", toBank)
        put("fromBank", fromBank)
        put("rules", JSONArray().apply { rules.forEach { put(it.toJson()) } })
        put("bankLog", JSONArray().apply {
            bankLog.forEach { put(JSONObject().put("t", it.time).put("k", it.kind).put("m", it.minutes)) }
        })
        put("trackingSince", trackingSince)
        put("lastEventTime", lastEventTime)
        put("streakStart", streakStart ?: JSONObject.NULL)
        put("ytOpenSince", ytOpenSince ?: JSONObject.NULL)
        put("ytPausedAt", ytPausedAt ?: JSONObject.NULL)
        put("brokeThisSession", brokeThisSession)
        put("graceUsed", graceUsed)
        put("ytVisible", JSONArray().apply { ytVisible.forEach { put(it) } })
        put("totalEarned", totalEarned)
        put("spentMs", spentMs)
        put("best", best)
        put("streaks", JSONArray().apply {
            streaks.forEach { put(JSONObject().put("s", it.start).put("e", it.end).put("c", it.earned)) }
        })
        put("watches", JSONArray().apply {
            watches.forEach { put(JSONObject().put("s", it.start).put("e", it.end)) }
        })
        put("watched", JSONObject().apply { watchedByDay.forEach { (k, v) -> put(k, v) } })
        put("charged", JSONObject().apply { chargedByDay.forEach { (k, v) -> put(k, v) } })
        put("earned", JSONObject().apply { earnedByDay.forEach { (k, v) -> put(k, v) } })
        put("penalty", penalty)
        put("passes", JSONArray().apply { passes.forEach { put(it) } })
        put("pendingRules", JSONArray().apply {
            pendingRules.forEach { put(JSONObject().put("at", it.at).put("id", it.ruleId).put("r", it.rule?.toJson() ?: JSONObject.NULL)) }
        })
        put("notes", JSONArray().apply { notes.forEach { put(JSONObject().put("t", it.time).put("k", it.kind).put("x", it.text)) } })
        put("protOff", protectionOffSince ?: JSONObject.NULL)
        put("protOffTotal", protectionOffTotalMs)
        put("activity", activity?.let { JSONObject().put("n", it.name).put("s", it.start).put("w", it.watchedBase) } ?: JSONObject.NULL)
        put("actBonus", JSONObject().apply { activityBonusByDay.forEach { (k, v) -> put(k, v) } })
    }.toString()

    companion object {
        fun fromJson(s: String?): Ledger {
            if (s.isNullOrBlank()) return Ledger()
            val o = JSONObject(s)
            fun optLongOrNull(k: String) = if (o.isNull(k)) null else o.optLong(k)
            val l = Ledger(
                trackingSince = o.optLong("trackingSince"),
                lastEventTime = o.optLong("lastEventTime"),
                streakStart = optLongOrNull("streakStart"),
                ytOpenSince = optLongOrNull("ytOpenSince"),
                ytPausedAt = optLongOrNull("ytPausedAt"),
                brokeThisSession = o.optBoolean("brokeThisSession"),
                graceUsed = o.optLong("graceUsed"),
                totalEarned = o.optDouble("totalEarned", 0.0),
                spentMs = o.optLong("spentMs"),
                best = o.optLong("best"),
            )
            o.optJSONArray("streaks")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    l.streaks += StreakRec(it.getLong("s"), it.getLong("e"), it.getDouble("c"))
                }
            }
            o.optJSONArray("watches")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { l.watches += WatchRec(it.getLong("s"), it.getLong("e")) }
            }
            o.optJSONArray("ytVisible")?.let { a -> for (i in 0 until a.length()) l.ytVisible += a.getString(i) }
            o.optJSONObject("watched")?.let { w -> w.keys().forEach { k -> l.watchedByDay[k] = w.getLong(k) } }
            o.optJSONObject("charged")?.let { w -> w.keys().forEach { k -> l.chargedByDay[k] = w.getLong(k) } }
            o.optJSONObject("earned")?.let { w -> w.keys().forEach { k -> l.earnedByDay[k] = w.getDouble(k) } }
            o.optJSONObject("actBonus")?.let { w -> w.keys().forEach { k -> l.activityBonusByDay[k] = w.getDouble(k) } }
            l.penalty = o.optDouble("penalty", 0.0)
            o.optJSONArray("passes")?.let { a -> for (i in 0 until a.length()) l.passes += a.getLong(i) }
            o.optJSONArray("pendingRules")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    l.pendingRules += PendingRule(it.getLong("at"), it.getString("id"), if (it.isNull("r")) null else Rule.fromJson(it.getJSONObject("r")))
                }
            }
            o.optJSONArray("notes")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { l.notes += Note(it.getLong("t"), it.getString("k"), it.getString("x")) }
            }
            l.protectionOffSince = if (o.isNull("protOff")) null else o.optLong("protOff").takeIf { it > 0 }
            l.protectionOffTotalMs = o.optLong("protOffTotal")
            if (!o.isNull("activity")) o.optJSONObject("activity")?.let { l.activity = Activity(it.getString("n"), it.getLong("s"), it.optLong("w")) }
            // v1(보상 상점 버전)에서 넘어온 경우: 예전 코인을 분으로 그대로 옮기고, 시작 선물을 줘요.
            l.bonus = o.optDouble("bonus", 0.0)
            l.bank = o.optDouble("bank", 0.0)
            l.bankHeld = o.optDouble("bankHeld", 0.0)
            l.bankDay = o.optString("bankDay", "")
            l.toBank = o.optDouble("toBank", 0.0)
            l.fromBank = o.optDouble("fromBank", 0.0)
            o.optJSONArray("rules")?.let { a -> for (i in 0 until a.length()) l.rules += Rule.fromJson(a.getJSONObject(i)) }
            o.optJSONArray("bankLog")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    l.bankLog += BankRec(it.getLong("t"), it.getString("k"), it.getDouble("m"))
                }
            }
            // v2(시작 선물 30분이 있던 버전)에서 넘어온 경우: 받은 선물은 그대로 두되 '참아서 번 시간'에선 빼요.
            if (o.optInt("v", 1) == 2 && l.started) l.bonus = 30.0
            // v4 이전 데이터에는 날짜별 '빠진 시간'이 없어서 만들어 줘요
            val v = o.optInt("v", 1)
            if (v < 3) l.chargedByDay.putAll(l.watchedByDay) // 규칙이 없던 때라 본 시간 = 빠진 시간
            else if (v == 3) {
                // 규칙이 있던 때: 남아 있는 시청 기록에 규칙을 적용해 다시 계산
                val zone = ZoneId.systemDefault()
                for (w in l.watches) {
                    var d = dayOf(w.start, zone)
                    while (!d.isAfter(dayOf(w.end, zone))) {
                        val d0 = d.atStartOfDay(zone).toInstant().toEpochMilli()
                        val d1 = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        val a = max(w.start, d0); val b = min(w.end, d1)
                        if (b > a) l.chargedByDay[d.toString()] = (l.chargedByDay[d.toString()] ?: 0) + RuleMath.chargedMs(a, b, l.rules, zone)
                        d = d.plusDays(1)
                    }
                }
            }
            return l
        }

        /** [start, end) 구간 중 [day]에 해당하는 길이. */
        fun overlap(start: Long, end: Long, day: LocalDate, zone: ZoneId): Long {
            val d0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val d1 = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return max(0, min(end, d1) - max(start, d0))
        }

        fun dayOf(t: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    }
}
