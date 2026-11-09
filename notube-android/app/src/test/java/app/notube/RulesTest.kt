package app.notube

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RulesTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val YT = "com.google.android.youtube"
    // 2026-11-13은 금요일
    private fun t(day: Int, h: Int, m: Int = 0) =
        LocalDate.of(2026, 11, 13).plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val sleep = Rule("s", "잠", Effect.NO_EARN, (1..7).toSet(), 23 * 60, 7 * 60)
    private val weekend = Rule("w", "주말", Effect.FREE, setOf(6, 7), 0, 0)
    private val study = Rule("b", "공부", Effect.BLOCK, (1..5).toSet(), 9 * 60, 18 * 60)
    private val dbl = Rule("d", "출근", Effect.DOUBLE, (1..5).toSet(), 8 * 60, 9 * 60)

    private fun ledger(start: Long, vararg rules: Rule) = Ledger().also {
        Engine.start(it, start); it.rules += rules
    }

    @Test fun overnightWindowSpansMidnight() {
        val w = RuleMath.windows(sleep, t(0, 20), t(1, 12), zone)
        assertEquals(listOf(t(0, 23) to t(1, 7)), w)
        // 전날 밤에 시작한 구간도 잡아요
        assertEquals(listOf(t(1, 6) to t(1, 7)), RuleMath.windows(sleep, t(1, 6), t(1, 12), zone))
    }

    @Test fun weekdaysOnly() {
        // 금 09~18은 공부, 토요일은 아님
        assertTrue(RuleMath.isActive(listOf(study), Effect.BLOCK, t(0, 10), zone))
        assertFalse(RuleMath.isActive(listOf(study), Effect.BLOCK, t(1, 10), zone))
        assertFalse(RuleMath.isActive(listOf(study), Effect.BLOCK, t(0, 19), zone))
    }

    @Test fun disabledRuleDoesNothing() {
        assertFalse(RuleMath.isActive(listOf(study.copy(enabled = false)), Effect.BLOCK, t(0, 10), zone))
    }

    @Test fun sleepDoesNotEarnButTierKeepsAging() {
        val l = ledger(t(0, 22), sleep)
        // 22~23시 1시간만 적립(6분), 23~07시는 쉬기, 07~08시는 이미 9시간째라 1.5배 → 9분
        assertEquals(6.0 + 9.0, l.liveEarned(t(1, 8), zone), 1e-6)
    }

    @Test fun doubleEarning() {
        val l = ledger(t(0, 8), dbl)
        assertEquals(12.0 + 6.0, l.liveEarned(t(0, 10), zone), 1e-6) // 8~9시 2배, 9~10시 기본
    }

    @Test fun freeTimeWatchingCostsNothingAndKeepsStreak() {
        val l = ledger(t(1, 9), weekend) // 토요일 아침부터
        l.totalEarned = 0.0
        Engine.process(l, listOf(
            Ev(t(1, 10), YT, EvType.RESUMED, "Watch"),
            Ev(t(1, 12), YT, EvType.STOPPED, "Watch"),
        ), t(1, 13), zone)
        assertEquals(t(1, 9), l.streakStart)      // 참기 기록 안 끊김
        assertEquals(0L, l.spentMs)               // 시간 안 빠짐
        assertEquals(2 * 60 * MIN, l.watchedByDay.values.sum()) // 본 시간 통계엔 남음
        assertEquals(18.0 + 9.0, l.remaining(t(1, 13), zone), 1e-6) // 4시간 참음(자유 시간 포함): 3시간 기본 + 4번째 시간 1.5배
    }

    @Test fun watchingAcrossFreeBoundaryChargesOnlyOutsidePart() {
        val l = ledger(t(0, 20), weekend) // 금요일 밤
        l.totalEarned = 100.0
        Engine.process(l, listOf(
            Ev(t(0, 23, 30), YT, EvType.RESUMED, "Watch"),
            Ev(t(1, 0, 30), YT, EvType.STOPPED, "Watch"), // 토요일 0시부터는 자유
        ), t(1, 1), zone)
        assertEquals(30 * MIN, l.spentMs)
    }

    @Test fun liveRemainingUsesRules() {
        val l = ledger(t(1, 9), weekend)
        l.totalEarned = 10.0
        Engine.process(l, listOf(Ev(t(1, 10), YT, EvType.RESUMED, "Watch")), t(1, 11), zone)
        // 토요일에 보는 중이라 안 줄어요
        assertEquals(10.0 + 6.0 + 6.0, l.remaining(t(1, 11), zone), 1e-6)
        assertTrue(l.freeNow(t(1, 11), zone))
    }

    @Test fun blockRuleIsReported() {
        val l = ledger(t(0, 8), study)
        assertEquals("b", l.blockRuleNow(t(0, 10), zone)?.id)
        assertNull(l.blockRuleNow(t(0, 19), zone))
        assertEquals(t(0, 18), RuleMath.activeUntil(study, t(0, 10), zone))
    }

    @Test fun rulesSurviveJson() {
        val l = ledger(t(0, 8), sleep, weekend, study, dbl)
        val back = Ledger.fromJson(l.toJson())
        assertEquals(listOf(sleep, weekend, study, dbl), back.rules)
    }

    @Test fun ruleTexts() {
        assertEquals("매일", sleep.daysText()); assertEquals("23:00–07:00", sleep.timeText())
        assertEquals("주말", weekend.daysText()); assertEquals("하루 종일", weekend.timeText())
        assertEquals("주중", study.daysText())
    }
}
