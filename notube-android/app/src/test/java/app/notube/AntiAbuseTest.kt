package app.notube

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AntiAbuseTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val YT = "com.google.android.youtube"
    // 2026-11-16은 월요일
    private fun t(day: Int, h: Int, m: Int = 0) =
        LocalDate.of(2026, 11, 16).plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun fresh(start: Long = t(0, 8)) = Ledger().also { Engine.start(it, start) }
    /** 지금 쓰는 규칙 (적용 시각 정보는 빼고 비교). */
    private fun Ledger.cur() = currentRules().map { it.copy(since = 0) }
    private val freeEvening = Rule("f", "저녁 자유", Effect.FREE, (1..7).toSet(), 20 * 60, 22 * 60)
    private val study = Rule("b", "공부", Effect.BLOCK, (1..5).toSet(), 9 * 60, 18 * 60)

    // ---- 비상 탈출권 ----

    @Test fun passOpensTenMinutesAndAddsDebt() {
        val l = fresh(t(0, 19))
        assertTrue(Engine.usePass(l, t(0, 20, 0), zone).also { })
        assertEquals(PASS_DEBT_MIN, l.penalty, 1e-9)
        assertTrue(l.freeNow(t(0, 20, 5), zone))
        assertFalse(l.freeNow(t(0, 20, 11), zone))
        // 탈출권 10분 동안 본 건 안 빠지고, 그 뒤 5분은 빠져요
        Engine.process(l, listOf(Ev(t(0, 20, 0), YT, EvType.RESUMED, "W"), Ev(t(0, 20, 15), YT, EvType.STOPPED, "W")), t(0, 20, 30), zone)
        assertEquals(5 * MIN, l.spentMs)
    }

    @Test fun passOncePerDayAndThreePerWeek() {
        val l = fresh()
        assertTrue(Engine.usePass(l, t(0, 20), zone))
        assertNotNull(Engine.passBlockedReason(l, t(0, 21), zone))   // 같은 날 두 번 안 됨
        assertFalse(Engine.usePass(l, t(0, 21), zone))
        assertTrue(Engine.usePass(l, t(1, 20), zone))
        assertTrue(Engine.usePass(l, t(2, 20), zone))
        assertFalse(Engine.usePass(l, t(3, 20), zone))               // 주 3번 넘음
        assertEquals(0, Engine.passesLeftThisWeek(l, t(3, 20), zone))
        assertTrue(Engine.usePass(l, t(7, 20), zone))                // 다음 주 월요일엔 다시
        assertEquals(4 * PASS_DEBT_MIN, l.penalty, 1e-9)
    }

    @Test fun passNotAllowedDuringBlockRule() {
        val l = fresh(); l.rules += study
        assertNotNull(Engine.passBlockedReason(l, t(0, 10), zone))
        assertFalse(Engine.usePass(l, t(0, 10), zone))
        assertTrue(Engine.usePass(l, t(0, 19), zone))
    }

    @Test fun passIsLogged() {
        val l = fresh()
        Engine.usePass(l, t(0, 20), zone)
        assertEquals("pass", l.notes.first().kind)
    }

    // ---- 규칙 숙려 기간 ----

    @Test fun addingFreeRuleWaits24h() {
        val l = fresh()
        val at = Engine.requestRuleChange(l, freeEvening.id, freeEvening, t(0, 19), zone)
        assertEquals(t(1, 19), at)
        assertTrue(l.rules.isEmpty())                     // 아직 적용 안 됨
        assertFalse(l.freeNow(t(0, 21), zone))            // 오늘 저녁엔 자유 시간 아님
        Engine.process(l, emptyList(), t(1, 19, 1), zone)
        assertEquals(listOf(freeEvening), l.cur())        // 하루 뒤 적용
        assertTrue(l.freeNow(t(1, 21), zone))
        assertTrue(l.notes.any { it.text.contains("적용됨") })
    }

    @Test fun tighteningIsImmediate() {
        val l = fresh()
        assertNull(Engine.requestRuleChange(l, study.id, study, t(0, 8), zone))   // 금지 규칙 추가는 바로
        assertEquals(listOf(study), l.cur())
        l.rules += freeEvening
        assertNull(Engine.requestRuleChange(l, freeEvening.id, null, t(0, 8), zone)) // 자유 시간 삭제도 바로
        assertEquals(listOf(study), l.cur())
    }

    @Test fun removingOrDisablingBlockRuleWaits() {
        val l = fresh(); l.rules += study
        assertNotNull(Engine.requestRuleChange(l, study.id, study.copy(enabled = false), t(0, 8), zone))
        assertTrue(l.rules.first().enabled)               // 여전히 켜져 있음
        assertNotNull(Engine.requestRuleChange(l, study.id, null, t(0, 9), zone))
        assertEquals(1, l.pendingRules.size)              // 같은 규칙의 요청은 마지막 것만
        assertNull(l.pendingRules.first().rule)
        Engine.cancelPending(l, study.id, t(0, 10))
        assertTrue(l.pendingRules.isEmpty())
        assertEquals(listOf(study), l.cur())
    }

    @Test fun deleteAndReAddFreeRuleCannotSkipWait() {
        val l = fresh(); l.rules += freeEvening
        // 자유 시간 규칙을 지우고(바로) 다시 만들면 → 다시 24시간 대기
        Engine.requestRuleChange(l, freeEvening.id, null, t(0, 8), zone)
        assertNotNull(Engine.requestRuleChange(l, freeEvening.id, freeEvening, t(0, 8, 1), zone))
        assertTrue(l.cur().isEmpty())
    }

    @Test fun loosenessClassifier() {
        assertTrue(Engine.isLoosening(null, freeEvening))
        assertFalse(Engine.isLoosening(null, study))
        assertTrue(Engine.isLoosening(study, null))
        assertFalse(Engine.isLoosening(freeEvening, null))
        assertTrue(Engine.isLoosening(study, study.copy(endMin = 12 * 60)))
        assertFalse(Engine.isLoosening(study.copy(enabled = false), study))
        assertFalse(Engine.isLoosening(freeEvening, freeEvening.copy(enabled = false)))
        assertFalse(Engine.isLoosening(study, study))
    }

    // ---- 보호 꺼짐 ----

    @Test fun protectionOffIsRecordedAndUsageStillCharged() {
        val l = fresh(t(0, 8))
        Engine.protectionChanged(l, false, t(0, 10))
        Engine.protectionChanged(l, false, t(0, 11)) // 중복 기록 안 함
        // 꺼진 동안 본 유튜브: 다시 켜고 나서 사용 기록을 읽으면 계산됨
        Engine.protectionChanged(l, true, t(0, 13))
        Engine.process(l, listOf(Ev(t(0, 11), YT, EvType.RESUMED, "W"), Ev(t(0, 12), YT, EvType.STOPPED, "W")), t(0, 13), zone)
        assertEquals(60 * MIN, l.spentMs)
        assertEquals(3 * HOUR, l.protectionOffTotalMs)
        assertEquals(2, l.notes.count { it.kind == "protect" })
    }

    // ---- 대체 활동 ----

    @Test fun activityBonusAndDailyCap() {
        val l = fresh()
        Engine.startActivity(l, "기도", t(0, 9))
        assertEquals(3.0, Engine.stopActivity(l, t(0, 9, 30), zone), 1e-9)       // 30분 → +3분
        Engine.startActivity(l, "운동", t(0, 10))
        assertEquals(27.0, Engine.stopActivity(l, t(0, 15), zone), 1e-9)         // 5시간이어도 하루 30분까지
        Engine.startActivity(l, "독서", t(0, 16))
        assertEquals(0.0, Engine.stopActivity(l, t(0, 17), zone), 1e-9)
        assertEquals(30.0, l.activityBonusByDay["2026-11-16"]!!, 1e-9)
    }

    @Test fun watchingDuringActivityVoidsBonus() {
        val l = fresh()
        Engine.startActivity(l, "말씀읽기", t(0, 9))
        Engine.process(l, listOf(Ev(t(0, 9, 10), YT, EvType.RESUMED, "W"), Ev(t(0, 9, 12), YT, EvType.STOPPED, "W")), t(0, 9, 30), zone)
        assertEquals(0.0, Engine.stopActivity(l, t(0, 9, 40), zone), 1e-9)
        assertTrue(l.notes.first().text.contains("보너스 없음"))
    }

    // ---- 통계 ----

    @Test fun dailyStatsSplitFreeAndCharged() {
        val l = fresh(t(0, 8)); l.rules += freeEvening
        Engine.process(l, listOf(
            Ev(t(0, 19, 30), YT, EvType.RESUMED, "W"), Ev(t(0, 20, 30), YT, EvType.STOPPED, "W"), // 30분은 자유 시간
        ), t(0, 21), zone)
        assertEquals(60 * MIN, l.watchedOn(LocalDate.of(2026, 11, 16), t(0, 21), zone))
        assertEquals(30 * MIN, l.chargedOn(LocalDate.of(2026, 11, 16), t(0, 21), zone))
    }

    @Test fun earnedIsSplitAcrossDays() {
        val l = fresh(t(0, 22))
        Engine.process(l, listOf(Ev(t(1, 2), YT, EvType.RESUMED, "W"), Ev(t(1, 3), YT, EvType.STOPPED, "W")), t(1, 4), zone)
        assertEquals(12.0, l.earnedByDay["2026-11-16"]!!, 1e-6) // 22~24시 2시간
        assertEquals(6.0 + 9.0, l.earnedByDay["2026-11-17"]!!, 1e-6) // 0~1시 기본 + 1~2시는 4시간째라 1.5배
    }

    @Test fun newFieldsSurviveJson() {
        val l = fresh()
        Engine.usePass(l, t(0, 20), zone)
        Engine.requestRuleChange(l, freeEvening.id, freeEvening, t(0, 20), zone)
        Engine.protectionChanged(l, false, t(0, 21))
        Engine.startActivity(l, "기도", t(0, 22))
        val back = Ledger.fromJson(l.toJson())
        assertEquals(l.toJson(), back.toJson())
        assertEquals("기도", back.activity?.name)
        assertEquals(freeEvening, back.pendingRules.first().rule)
    }

    // ---- 코드 리뷰에서 나온 문제들 ----

    @Test fun ruleChangeNeverAppliesBackwards() {
        // 3일째 참는 중에 하루 종일 2배 규칙이 적용돼도, 그 전 시간은 2배가 안 돼요
        val allDayDouble = Rule("d", "2배", Effect.DOUBLE, (1..7).toSet(), 0, 0)
        val a = fresh(t(0, 0)); val b = fresh(t(0, 0))
        Engine.requestRuleChange(a, allDayDouble.id, allDayDouble, t(0, 12), zone) // t(1,12)부터 적용
        Engine.process(a, emptyList(), t(2, 0), zone)
        Engine.process(b, emptyList(), t(2, 0), zone)
        val plain = b.liveEarned(t(2, 0), zone)
        val withRule = a.liveEarned(t(2, 0), zone)
        // 규칙 적용 전 36시간은 같고, 뒤 12시간만 2배
        val last12 = RuleMath.earnedIn(t(0, 0), t(1, 12), t(2, 0), emptyList(), zone)
        assertEquals(plain + last12, withRule, 1e-6)
    }

    @Test fun deletingFreeRuleKeepsEarlierFreeTimeFree() {
        val l = fresh(t(0, 19)); l.rules += freeEvening
        Engine.process(l, listOf(Ev(t(0, 20, 0), YT, EvType.RESUMED, "W")), t(0, 20, 30), zone)
        Engine.requestRuleChange(l, freeEvening.id, null, t(0, 20, 30), zone) // 엄격하게: 바로 삭제
        Engine.process(l, listOf(Ev(t(0, 21, 0), YT, EvType.STOPPED, "W")), t(0, 21, 5), zone)
        assertEquals(30 * MIN, l.spentMs) // 20:00~20:30은 여전히 자유, 20:30~21:00만 빠짐
    }

    @Test fun shortPeekDuringActivityVoidsBonus() {
        val l = fresh()
        Engine.startActivity(l, "기도", t(0, 9), zone)
        Engine.process(l, listOf(
            Ev(t(0, 9, 10), YT, EvType.RESUMED, "W"), Ev(t(0, 9, 10) + 8_000, YT, EvType.STOPPED, "W"), // 8초만
        ), t(0, 9, 30), zone)
        assertEquals(0.0, Engine.stopActivity(l, t(0, 9, 40), zone), 1e-9)
    }

    @Test fun oldRetiredRulesArePruned() {
        val l = fresh(t(0, 8)); l.rules += freeEvening
        Engine.requestRuleChange(l, freeEvening.id, null, t(0, 9), zone)
        assertEquals(1, l.rules.size) // 옛 규칙이 계산용으로 남아 있음
        Engine.process(l, emptyList(), t(5, 9), zone)
        assertEquals(1, l.rules.size) // 참기 기록이 규칙이 끝나기 전에 시작돼서 아직 필요
        // 유튜브를 봐서 참기 기록이 새로 시작되면 더는 필요 없어서 정리돼요
        Engine.process(l, listOf(Ev(t(5, 10), YT, EvType.RESUMED, "W"), Ev(t(5, 11), YT, EvType.STOPPED, "W")), t(5, 12), zone)
        assertEquals(0, l.rules.size)
    }
}
