package app.notube

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class EngineTest {
    private val YT = "com.google.android.youtube"
    private val HOME = "com.android.launcher"
    private val zone = ZoneId.of("Asia/Seoul")
    private val t0 = 1_780_000_000_000L
    private fun at(min: Double) = t0 + (min * MIN).toLong()
    /** 지갑에 30분이 있는 상태로 시작 (예전 시작 선물을 받은 지금 사용자의 상태와 같음). */
    private fun fresh() = Ledger().also { Engine.start(it, t0); it.totalEarned = 30.0; it.bonus = 30.0 }
    private fun watch(fromMin: Double, toMin: Double) = listOf(
        Ev(at(fromMin), YT, EvType.RESUMED), Ev(at(toMin), YT, EvType.STOPPED), Ev(at(toMin) + 10, HOME, EvType.RESUMED),
    )

    @Test fun earningTiers() {
        assertEquals(1.0, earnedFor(10 * MIN), 1e-9)
        assertEquals(18.0, earnedFor(3 * HOUR), 1e-9)
        // 3h*6 + 9h*9 + 12h*12 + 1h*18
        assertEquals(18 + 81 + 144 + 18.0, earnedFor(25 * HOUR), 1e-9)
    }

    @Test fun newStartBeginsAtZero() {
        val l = Ledger().also { Engine.start(it, t0) }
        assertEquals(0.0, l.remaining(t0), 1e-9)
        // 바로 유튜브를 켜면 지갑이 마이너스 → 막힘
        Engine.process(l, listOf(Ev(at(1.0), YT, EvType.RESUMED, "Watch")), at(1.5), zone)
        assertTrue(l.remaining(at(1.5)) <= 0)
    }

    @Test fun notWatchingEarnsTime() {
        val l = fresh()
        Engine.process(l, emptyList(), at(120.0), zone)
        assertEquals(t0, l.streakStart)
        assertEquals(30 + 12.0, l.remaining(at(120.0)), 1e-6)
    }

    @Test fun watchingSpendsMinuteForMinute() {
        val l = fresh()
        Engine.process(l, watch(60.0, 75.0), at(90.0), zone)
        // 시작 30 + 60분 참음 6 − 15분 시청 + 끄고 15분 참음 1.5
        assertEquals(30 + 6 - 15 + 1.5, l.remaining(at(90.0)), 1e-6)
        assertEquals(at(75.0), l.streakStart)
        assertEquals(1, l.streaks.size)
        assertEquals(1, l.watches.size)
        assertEquals(15 * MIN, l.spentMs)
    }

    @Test fun liveWatchingCountsBeforeItEnds() {
        val l = fresh()
        Engine.process(l, listOf(Ev(at(20.0), YT, EvType.RESUMED)), at(25.0), zone)
        assertTrue(l.watchingNow)
        assertNull(l.streakStart)
        // 30 + 20분 참음 2 − 지금까지 본 5분
        assertEquals(27.0, l.remaining(at(25.0)), 1e-6)
        // 시간이 흐르면 계속 줄어듦 → 52분 지점에서 0
        assertEquals(0.0, l.remaining(at(52.0)), 1e-6)
        assertTrue(l.remaining(at(53.0)) < 0)
        // 다음 정산에서 끈 게 보이면 이중으로 빠지지 않음
        Engine.process(l, listOf(Ev(at(40.0), YT, EvType.STOPPED), Ev(at(40.01), HOME, EvType.RESUMED)), at(40.5), zone)
        assertEquals(at(40.0), l.streakStart)
        assertEquals(30 + 2 - 20 + 0.05, l.remaining(at(40.5)), 1e-6)
    }

    @Test fun shortPeekCostsButKeepsStreak() {
        val l = fresh()
        Engine.process(l, watch(30.0, 30.5), at(60.0), zone)
        assertEquals(t0, l.streakStart)            // 참기 기록은 유지
        assertEquals(30_000L, l.graceUsed)
        assertEquals(30 + 6 - 0.5, l.remaining(at(60.0)), 1e-6) // 본 30초는 빠짐
    }

    @Test fun manyShortPeeksBreakStreak() {
        val l = fresh()
        val ev = (0 until 5).flatMap { i -> val s = 10.0 + i * 5; watch(s, s + 0.75) }
        Engine.process(l, ev, at(60.0), zone)
        // 45초 × 4 = 3분 → 4번째(25분)에 참기 기록 종료
        assertEquals(1, l.streaks.size)
        assertEquals(2.5, l.streaks[0].earned, 1e-6)
    }

    @Test fun rotationGlitchMergesIntoOneSession() {
        val l = fresh()
        val ev = listOf(
            Ev(at(10.0), YT, EvType.RESUMED), Ev(at(10.0) + 40_000, YT, EvType.STOPPED),
            Ev(at(10.0) + 42_000, YT, EvType.RESUMED), Ev(at(10.0) + 82_000, YT, EvType.STOPPED),
            Ev(at(10.0) + 83_000, HOME, EvType.RESUMED),
        )
        Engine.process(l, ev, at(30.0), zone)
        assertEquals(1, l.streaks.size)
        assertEquals(at(10.0) + 82_000, l.streakStart)
        assertEquals(82_000L, l.spentMs) // 끊긴 2초도 시청으로 봄
    }

    @Test fun screenOffEndsSession() {
        val l = fresh()
        Engine.process(l, listOf(Ev(at(5.0), YT, EvType.RESUMED), Ev(at(15.0), "android", EvType.SCREEN_OFF)), at(600.0), zone)
        assertEquals(at(15.0), l.streakStart)
        assertFalse(l.watchingNow)
        assertEquals(10 * MIN, l.spentMs)
    }

    @Test fun reprocessingSameEventsIsIdempotent() {
        val l = fresh()
        Engine.process(l, watch(60.0, 75.0), at(90.0), zone)
        val r1 = l.remaining(at(95.0))
        Engine.process(l, watch(60.0, 75.0), at(95.0), zone)
        assertEquals(r1, l.remaining(at(95.0)), 1e-9)
        assertEquals(1, l.streaks.size)
    }

    @Test fun jsonRoundTrip() {
        val l = fresh()
        Engine.process(l, watch(60.0, 75.0) + listOf(Ev(at(100.0), YT, EvType.RESUMED)), at(110.0), zone)
        val back = Ledger.fromJson(l.toJson())
        assertEquals(l.toJson(), back.toJson())
        assertEquals(l.remaining(at(110.0)), back.remaining(at(110.0)), 1e-9)
    }

    @Test fun migratesV1Coins() {
        val v1 = """{"v":1,"trackingSince":$t0,"lastEventTime":$t0,"streakStart":$t0,"totalEarned":40.0,"totalSpent":30,"rewards":[]}"""
        val l = Ledger.fromJson(v1)
        assertEquals(40.0, l.totalEarned, 1e-9) // 예전 코인 그대로, 선물 없음
        assertEquals(0.0, l.bonus, 1e-9)
    }

    @Test fun migratesV2KeepsGiftButExcludesItFromEarned() {
        val v2 = """{"v":2,"trackingSince":$t0,"lastEventTime":$t0,"streakStart":$t0,"totalEarned":31.0,"spentMs":60000}"""
        val l = Ledger.fromJson(v2)
        assertEquals(30.0, l.bonus, 1e-9)
        assertEquals(1.0, l.earnedByWaiting(t0), 1e-9)
        assertEquals(30.0, l.remaining(t0), 1e-9)
        assertEquals(60_000L, l.spentMs)
    }

    @Test fun watchedSplitsAcrossMidnight() {
        val l = Ledger()
        val midnight = java.time.LocalDate.of(2026, 11, 7).atStartOfDay(zone).toInstant().toEpochMilli()
        Engine.start(l, midnight - 2 * HOUR)
        Engine.process(l, listOf(Ev(midnight - 10 * MIN, YT, EvType.RESUMED), Ev(midnight + 20 * MIN, YT, EvType.STOPPED),
            Ev(midnight + 20 * MIN + 1, HOME, EvType.RESUMED)), midnight + HOUR, zone)
        assertEquals(10 * MIN, l.watchedByDay["2026-11-06"])
        assertEquals(20 * MIN, l.watchedByDay["2026-11-07"])
    }

    // ---- 캡처로 받은 실제 상황들 ----

    @Test fun popupStaysWatchingWhenAnotherAppIsInFront() {
        val l = fresh()
        // 유튜브를 팝업으로 띄움 → 뒤의 노튜브 앱을 누름: 노튜브가 앞으로 오지만 유튜브는 보이는 채로 남음(중지 신호 없음)
        Engine.process(l, listOf(
            Ev(at(10.0), YT, EvType.RESUMED, "Watch"),
            Ev(at(10.5), "app.notube", EvType.RESUMED, "MainActivity"),
        ), at(20.0), zone)
        assertTrue(l.watchingNow)
        assertNull(l.streakStart)
        assertEquals(30 + 1 - 10.0, l.remaining(at(20.0)), 1e-6)
        // 팝업을 닫아야 비로소 끝
        Engine.process(l, listOf(Ev(at(25.0), YT, EvType.STOPPED, "Watch")), at(40.0), zone)
        assertFalse(l.watchingNow)
        assertEquals(at(25.0), l.streakStart)
        assertEquals(15 * MIN, l.spentMs)
    }

    @Test fun switchingThroughRecentsKeepsWatching() {
        val l = fresh()
        // 유튜브 → 최근 앱 화면(런처) → 다시 유튜브. 런처의 신호가 유튜브보다 늦게 찍혀도 영향 없음
        Engine.process(l, listOf(
            Ev(at(10.0), YT, EvType.RESUMED, "Shell"),
            Ev(at(12.0), YT, EvType.STOPPED, "Shell"),
            Ev(at(12.0) + 1, HOME, EvType.RESUMED, "Recents"),
            Ev(at(12.0) + 3_000, YT, EvType.RESUMED, "Shell"),
            Ev(at(12.0) + 3_100, HOME, EvType.RESUMED, "Recents"), // 늦게 도착한 런처 신호
            Ev(at(12.0) + 3_200, HOME, EvType.STOPPED, "Recents"),
        ), at(30.0), zone)
        assertTrue(l.watchingNow)
        // 3초 끊김은 한 번의 시청으로 이어 붙음
        assertEquals(at(10.0), l.ytOpenSince)
        assertEquals(30 + 1 - 20.0, l.remaining(at(30.0)), 1e-6)
    }

    @Test fun youtubeScreenChangesInsideAppKeepWatching() {
        val l = fresh()
        // 유튜브 안에서 홈 목록 → 영상 화면으로 이동: 새 화면이 먼저 보이고, 이전 화면이 나중에 중지됨
        Engine.process(l, listOf(
            Ev(at(10.0), YT, EvType.RESUMED, "Home"),
            Ev(at(15.0), YT, EvType.RESUMED, "Watch"),
            Ev(at(15.0) + 500, YT, EvType.STOPPED, "Home"),
        ), at(20.0), zone)
        assertTrue(l.watchingNow)
        assertEquals(setOf("Watch"), l.ytVisible)
        Engine.process(l, listOf(Ev(at(22.0), YT, EvType.STOPPED, "Watch")), at(30.0), zone)
        assertFalse(l.watchingNow)
        assertEquals(12 * MIN, l.spentMs)
    }

    @Test fun screenOffClearsVisibleYoutube() {
        val l = fresh()
        Engine.process(l, listOf(Ev(at(5.0), YT, EvType.RESUMED, "Watch"), Ev(at(8.0), "android", EvType.SCREEN_OFF)), at(60.0), zone)
        assertTrue(l.ytVisible.isEmpty())
        assertFalse(l.watchingNow)
    }

    // ---- 백그라운드 재생 ----
    private val M = Engine.MEDIA_CLS

    @Test fun backgroundPlaybackCountsAsWatching() {
        val l = fresh()
        // 유튜브에서 재생 → 홈으로 나감(화면은 사라짐) 그런데 소리는 계속 재생
        Engine.process(l, listOf(
            Ev(at(10.0), YT, EvType.RESUMED, "Watch"),
            Ev(at(10.0) + 1_000, YT, EvType.RESUMED, M),
            Ev(at(12.0), YT, EvType.STOPPED, "Watch"),
        ), at(20.0), zone)
        assertTrue(l.watchingNow)
        assertEquals(setOf(M), l.ytVisible)
        assertEquals(30 + 1 - 10.0, l.remaining(at(20.0)), 1e-6)
        // 재생을 멈춰야 끝
        Engine.process(l, listOf(Ev(at(25.0), YT, EvType.STOPPED, M)), at(40.0), zone)
        assertFalse(l.watchingNow)
        assertEquals(at(25.0), l.streakStart)
    }

    @Test fun listeningWithScreenOffStillCounts() {
        val l = fresh()
        Engine.process(l, listOf(
            Ev(at(10.0), YT, EvType.RESUMED, "Watch"),
            Ev(at(10.0) + 1_000, YT, EvType.RESUMED, M),
            Ev(at(15.0), "android", EvType.SCREEN_OFF),
        ), at(30.0), zone)
        assertTrue(l.watchingNow)            // 화면은 꺼졌지만 소리는 재생 중
        assertEquals(setOf(M), l.ytVisible)
        assertEquals(20 * MIN, l.liveWatchMs(at(30.0)))
    }

    @Test fun screenOffWithoutPlaybackEndsSession() {
        val l = fresh()
        Engine.process(l, listOf(Ev(at(10.0), YT, EvType.RESUMED, "Watch"), Ev(at(15.0), "android", EvType.SCREEN_OFF)), at(30.0), zone)
        assertFalse(l.watchingNow)
        assertEquals(at(15.0), l.streakStart)
    }

    @Test fun earnedByWaitingExcludesStartBonus() {
        val l = fresh()
        assertEquals(0.0, l.earnedByWaiting(t0), 1e-9)
        assertEquals(1.6, l.earnedByWaiting(at(16.0)), 1e-6)
        assertEquals(30 + 1.6, l.remaining(at(16.0)), 1e-6)
    }

    // ---- 저금통 ----
    private fun dayAt(d: Int, h: Int, m: Int = 0) =
        java.time.LocalDate.of(2026, 11, 10).plusDays(d.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun depositMovesTimeOutOfWallet() {
        val l = Ledger(); Engine.start(l, dayAt(0, 9)); l.totalEarned = 50.0
        Engine.process(l, emptyList(), dayAt(0, 9), zone)
        assertFalse(Engine.deposit(l, 51, dayAt(0, 9), zone))   // 지갑보다 많이는 못 넣음
        assertTrue(Engine.deposit(l, 40, dayAt(0, 9), zone))
        assertEquals(10.0, l.remaining(dayAt(0, 9)), 1e-9)
        assertEquals(40.0, l.bank, 1e-9)
    }

    @Test fun interestNeedsAFullDay() {
        val l = Ledger(); Engine.start(l, dayAt(0, 9)); l.totalEarned = 100.0
        Engine.process(l, emptyList(), dayAt(0, 9), zone)
        Engine.deposit(l, 60, dayAt(0, 10), zone)
        // 첫 자정: 넣은 날은 하루를 꼬박 채우지 않았으니 이자 없음
        Engine.settleInterest(l, dayAt(1, 8), zone)
        assertEquals(60.0, l.bank, 1e-9)
        assertEquals(6.0, l.pendingInterest(), 1e-9)
        // 둘째 자정: 6분 이자
        Engine.settleInterest(l, dayAt(2, 8), zone)
        assertEquals(66.0, l.bank, 1e-9)
        // 며칠 밀려도 하루씩 복리로
        Engine.settleInterest(l, dayAt(4, 8), zone)
        assertEquals(66 * 1.1 * 1.1, l.bank, 1e-6)
        assertEquals(3, l.bankLog.count { it.kind == "interest" })
    }

    @Test fun depositTodayDoesNotEarnTonight() {
        val l = Ledger(); Engine.start(l, dayAt(0, 9)); l.totalEarned = 100.0
        Engine.process(l, emptyList(), dayAt(0, 9), zone)
        Engine.deposit(l, 50, dayAt(0, 10), zone)
        Engine.settleInterest(l, dayAt(1, 1), zone)      // 50분은 이제 하루를 시작
        Engine.deposit(l, 30, dayAt(1, 23), zone)        // 밤 11시에 30분 추가
        Engine.settleInterest(l, dayAt(2, 1), zone)
        assertEquals(80 + 5.0, l.bank, 1e-9)             // 이자는 하루 꼬박 있던 50분에만
    }

    @Test fun breakingBankReturnsEverythingAndForfeitsTonight() {
        val l = Ledger(); Engine.start(l, dayAt(0, 9)); l.totalEarned = 100.0
        Engine.process(l, emptyList(), dayAt(0, 9), zone)
        Engine.deposit(l, 60, dayAt(0, 10), zone)
        Engine.settleInterest(l, dayAt(2, 8), zone)      // 66분
        val back = Engine.breakBank(l, dayAt(2, 9), zone)
        assertEquals(66.0, back, 1e-9)
        assertEquals(0.0, l.bank, 1e-9)
        assertEquals(0.0, l.pendingInterest(), 1e-9)
        assertEquals(40 + 66.0, l.remaining(dayAt(2, 9)) - l.liveEarned(dayAt(2, 9)), 1e-6)
        // 이자는 '참아서 번 시간'에 섞이지 않음
        assertEquals(100.0, l.earnedByWaiting(dayAt(2, 9)) - l.liveEarned(dayAt(2, 9)), 1e-6)
    }

    @Test fun bankSurvivesJsonRoundTrip() {
        val l = Ledger(); Engine.start(l, dayAt(0, 9)); l.totalEarned = 100.0
        Engine.process(l, emptyList(), dayAt(0, 9), zone)
        Engine.deposit(l, 60, dayAt(0, 10), zone)
        Engine.settleInterest(l, dayAt(2, 8), zone)
        val back = Ledger.fromJson(l.toJson())
        assertEquals(l.toJson(), back.toJson())
    }
}
