package app.notube

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 다른 AI 검토(second opinion)에서 나온 지적에 대한 회귀 테스트. */
class ReviewFixesTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val YT = "com.google.android.youtube"
    // 2026-11-16은 월요일
    private fun t(day: Int, h: Int, m: Int = 0) =
        LocalDate.of(2026, 11, 16).plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun fresh(start: Long = t(0, 8)) = Ledger().also { Engine.start(it, start) }
    private val study = Rule("b", "공부", Effect.BLOCK, (1..5).toSet(), 9 * 60, 18 * 60)
    private val freeEvening = Rule("f", "저녁 자유", Effect.FREE, (1..7).toSet(), 20 * 60, 22 * 60)

    // ---- 2. 시계 바꾸기 ----

    @Test fun untrustedClockBlocksPassRulesAndInterest() {
        val l = fresh(); l.clockTrusted = false
        assertNotNull(Engine.passBlockedReason(l, t(0, 20), zone))
        assertFalse(Engine.usePass(l, t(0, 20), zone))
        assertEquals(Engine.REFUSED_CLOCK, Engine.requestRuleChange(l, freeEvening.id, freeEvening, t(0, 20), zone))
        // 엄격하게 바꾸는 건 시계와 상관없이 바로 돼요
        assertNull(Engine.requestRuleChange(l, study.id, study, t(0, 20), zone))
    }

    @Test fun pendingRuleWaitsWhileClockUntrusted() {
        val l = fresh()
        Engine.requestRuleChange(l, freeEvening.id, freeEvening, t(0, 8), zone)
        l.clockTrusted = false
        Engine.process(l, emptyList(), t(3, 8), zone) // 시계를 앞당긴 상황
        assertTrue(l.currentRules().isEmpty())
        l.clockTrusted = true
        Engine.process(l, emptyList(), t(3, 8), zone)
        assertEquals(1, l.currentRules().size)
    }

    @Test fun interestCannotBeCollectedTwiceByMovingClockBack() {
        val l = Ledger(); Engine.start(l, t(0, 9)); l.totalEarned = 100.0
        Engine.process(l, emptyList(), t(0, 9), zone)
        Engine.deposit(l, 60, t(0, 10), zone)
        Engine.settleInterest(l, t(2, 8), zone)  // 66분
        Engine.settleInterest(l, t(1, 8), zone)  // 시계를 하루 되돌림 → 아무 일 없음
        Engine.settleInterest(l, t(2, 9), zone)  // 다시 앞으로 → 이미 받은 날이라 그대로
        assertEquals(66.0, l.bank, 1e-9)
    }

    @Test fun clockMovedBackDoesNotDropViewing() {
        val l = fresh(t(0, 8))
        Engine.process(l, emptyList(), t(2, 8), zone)
        l.lastEventTime = t(2, 8) // 미래 시각까지 처리된 상태에서
        // 시계를 하루 되돌린 뒤 본 유튜브도 계산돼요
        // 지난 정산 뒤 실제로 2시간이 흘렀다는 걸 휴대폰 가동 시간으로 알아요 → 새 시계 8:30 이후 기록부터 읽어요
        Engine.process(l, listOf(Ev(t(1, 9), YT, EvType.RESUMED, "W"), Ev(t(1, 10), YT, EvType.STOPPED, "W")), t(1, 10, 30), zone,
            sinceLastSyncMs = 2 * HOUR)
        assertEquals(60 * MIN, l.spentMs)
        assertTrue(l.notes.any { it.kind == "clock" })
    }

    // ---- 3. 확인할 수 없는 긴 공백 ----

    @Test fun unverifiableGapEarnsNothing() {
        val l = fresh(t(0, 8))
        Engine.markUnverified(l, t(0, 8), t(10, 8), zone)
        assertEquals(0.0, l.totalEarned, 1e-9)            // 확인 못 한 10일은 적립 안 함
        assertEquals(t(10, 8), l.streakStart)             // 참기 기록은 그 뒤부터 새로
        assertEquals(6.0, l.liveEarned(t(10, 9), zone), 1e-9)
    }

    @Test fun unverifiableGapKeepsEarningsBeforeIt() {
        val l = fresh(t(0, 8))
        Engine.markUnverified(l, t(0, 10), t(9, 10), zone)
        assertEquals(12.0, l.totalEarned, 1e-9)           // 공백 전 2시간 몫은 그대로
    }

    @Test fun openSessionEndsAtStartOfGap() {
        val l = fresh(t(0, 8))
        Engine.process(l, listOf(Ev(t(0, 9), YT, EvType.RESUMED, "W")), t(0, 9, 30), zone)
        Engine.markUnverified(l, t(0, 10), t(9, 10), zone)
        assertFalse(l.watchingNow)
        assertEquals(60 * MIN, l.spentMs)
        assertEquals(t(9, 10), l.streakStart)
    }

    // ---- 7. 대체 활동 확인 ----

    @Test fun activityWithoutVerificationGetsNoBonus() {
        val l = fresh()
        Engine.startActivity(l, "기도", t(0, 9), zone)
        assertEquals(0.0, Engine.stopActivity(l, t(0, 10), zone, verified = false), 1e-9)
        Engine.startActivity(l, "기도", t(0, 11), zone)
        Engine.protectionChanged(l, false, t(0, 11, 10))
        Engine.protectionChanged(l, true, t(0, 11, 20))  // 중간에 권한을 껐다 켬
        assertEquals(0.0, Engine.stopActivity(l, t(0, 12), zone), 1e-9)
    }

    // ---- 8. 엄격한 변경은 바로 ----

    @Test fun extendingBlockRuleIsImmediate() {
        val l = fresh(); l.rules += study
        val wider = study.copy(days = (1..6).toSet(), endMin = 20 * 60) // 토요일 추가, 20시까지
        assertFalse(Engine.isLoosening(study, wider))
        assertNull(Engine.requestRuleChange(l, study.id, wider, t(0, 8), zone))
        assertEquals(setOf(1, 2, 3, 4, 5, 6), l.currentRules().first().days)
    }

    @Test fun shrinkingFreeRuleIsImmediateButGrowingWaits() {
        val narrower = freeEvening.copy(startMin = 21 * 60)
        assertFalse(Engine.isLoosening(freeEvening, narrower))
        assertTrue(Engine.isLoosening(narrower, freeEvening))
        assertTrue(Engine.isLoosening(study, study.copy(effect = Effect.FREE)))     // 금지 → 자유
        assertFalse(Engine.isLoosening(freeEvening, freeEvening.copy(effect = Effect.BLOCK))) // 자유 → 금지
    }

    @Test fun overnightCoverageWrapsSundayToMonday() {
        val sunNight = Rule("s", "일요일 밤", Effect.BLOCK, setOf(7), 23 * 60, 1 * 60)
        val cov = RuleMath.weekCoverage(sunNight)
        assertTrue(cov[6 * 1440 + 23 * 60])  // 일 23:00
        assertTrue(cov[0 * 1440 + 30])       // 월 00:30
        assertFalse(cov[0 * 1440 + 90])      // 월 01:30
    }

    // ---- 11. 중요한 기록은 남기기 ----

    @Test fun importantNotesSurviveManyActivityNotes() {
        val l = fresh()
        Engine.usePass(l, t(0, 20), zone)
        repeat(400) { Engine.note(l, t(1, 8) + it, "activity", "기도 10분") }
        assertTrue(l.notes.any { it.kind == "pass" })
        assertTrue(l.notes.size <= 300)
    }

    @Test fun clockFlagSurvivesJson() {
        val l = fresh(); l.clockTrusted = false
        assertFalse(Ledger.fromJson(l.toJson()).clockTrusted)
    }
}
