package app.notube

import java.time.ZoneId
import kotlin.math.max

/**
 * 휴대폰 사용 기록에서 꺼낸 이벤트 하나. type 값은 android.app.usage.UsageEvents.Event 상수와 같아요.
 * [cls]는 화면(액티비티) 이름. 유튜브 안에서도 화면이 여러 개라 하나씩 구분해요.
 */
data class Ev(val time: Long, val pkg: String, val type: Int, val cls: String = "")

object EvType {
    const val RESUMED = 1          // ACTIVITY_RESUMED (= MOVE_TO_FOREGROUND)
    const val PAUSED = 2           // ACTIVITY_PAUSED (= MOVE_TO_BACKGROUND)
    const val STOPPED = 23         // ACTIVITY_STOPPED
    const val SCREEN_OFF = 16      // SCREEN_NON_INTERACTIVE
    const val KEYGUARD_SHOWN = 17
    const val SHUTDOWN = 26        // DEVICE_SHUTDOWN
}

/**
 * 사용 기록 이벤트를 읽어 [Ledger]를 갱신하는 순수 로직.
 * 안드로이드 API를 쓰지 않아서 테스트로 바로 검증할 수 있어요.
 */
object Engine {
    val YOUTUBE_PACKAGES = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "app.revanced.android.youtube",
        "app.rvx.android.youtube",
    )

    /** 이보다 짧게 연 건 봐줘요 (알림 눌러 잘못 들어간 경우 등). */
    const val GRACE_MS = 60_000L

    /** 1분 미만으로 잠깐 연 것도 한 연속 기록 안에서 합쳐서 이만큼 넘으면 기록이 끝나요. */
    const val GRACE_BUDGET_MS = 3 * 60_000L

    /** 유튜브가 이 시간 안에 다시 올라오면 같은 시청으로 이어 붙여요 (화면 회전, 내부 화면 전환). */
    const val MERGE_GAP_MS = 10_000L

    /** 백그라운드 재생(소리만 나오는 재생)을 나타내는 가짜 화면 이름. 감시 서비스가 넣어줘요. */
    const val MEDIA_CLS = "@media"

    /**
     * 핵심 규칙: 유튜브 화면이 "보이거나" 소리가 "재생 중"이면 시청으로 봐요. Engine과 감시 서비스가 같이 써요.
     * - 다른 앱이 앞으로 와도(팝업·분할 화면) 유튜브가 보이면 계속 시청
     * - 일시정지(PAUSED)는 무시: 팝업에서 다른 앱을 누르거나 작은 창(PIP)일 때도 오는 신호라서
     * - 화면이 실제로 사라질 때(STOPPED)만 껐다고 봐요
     * - 화면을 끄면 보이던 화면은 사라지지만, 백그라운드 재생은 계속 시청으로 남아요
     */
    fun applyVisibility(visible: MutableSet<String>, e: Ev) {
        val isYt = e.pkg in YOUTUBE_PACKAGES
        when (e.type) {
            EvType.RESUMED -> if (isYt) visible += e.cls
            EvType.STOPPED -> if (isYt) visible -= e.cls
            EvType.SCREEN_OFF, EvType.KEYGUARD_SHOWN -> visible.retainAll { it == MEDIA_CLS }
            EvType.SHUTDOWN -> visible.clear()
        }
    }

    /** 처음 시작. 지금부터 연속 기록이 시작돼요. */
    fun start(l: Ledger, now: Long) {
        if (l.started) return
        l.trackingSince = now
        l.lastEventTime = now
        l.streakStart = now
    }

    fun process(l: Ledger, events: List<Ev>, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        if (!l.started) return
        for (e in events.sortedBy { it.time }) {
            // 이 이벤트 전에 적용될 규칙 변경이 있으면 먼저 적용 (규칙마다 시작 시각이 있어서 거슬러 적용되지 않아요)
            applyPendingRules(l, e.time, zone)
            if (e.time <= l.lastEventTime) continue
            // 잠깐 내려갔던 유튜브가 돌아오지 않은 채 시간이 지났으면 그 시점에 시청 종료
            finalizeIfGapPassed(l, e.time, zone)
            val isYt = e.pkg in YOUTUBE_PACKAGES
            val wasVisible = l.ytVisible.isNotEmpty()
            applyVisibility(l.ytVisible, e)
            if (isYt && e.type == EvType.RESUMED) {
                if (l.ytOpenSince == null) {
                    l.ytOpenSince = e.time
                    l.brokeThisSession = false
                }
                l.ytPausedAt = null
            } else if (wasVisible && l.ytVisible.isEmpty()) {
                pauseYt(l, e.time)
                // 화면이 꺼졌으면 이어 붙일 일이 없으니 바로 마무리
                if (e.type == EvType.SCREEN_OFF || e.type == EvType.KEYGUARD_SHOWN || e.type == EvType.SHUTDOWN) finalizeYt(l, zone)
            }
            checkGrace(l, e.time, zone)
            l.lastEventTime = e.time
        }
        applyPendingRules(l, now, zone)
        finalizeIfGapPassed(l, now, zone)
        checkGrace(l, now, zone)
        trimWatched(l, now, zone)
        settleInterest(l, now, zone)
        pruneRules(l, now)
    }

    // ----- 저금통 -----

    /** 자정이 지났으면 그날 계속 들어 있던 시간에 이자를 붙여요. 여러 날 밀렸으면 하루씩 차례로. */
    fun settleInterest(l: Ledger, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val today = Ledger.dayOf(now, zone)
        if (l.bankDay.isEmpty()) { l.bankDay = today.toString(); l.bankHeld = l.bank; return }
        var day = java.time.LocalDate.parse(l.bankDay)
        while (day.isBefore(today)) {
            day = day.plusDays(1)
            val interest = l.bankHeld * BANK_DAILY_RATE
            if (interest > 0) {
                l.bank += interest
                addBankLog(l, BankRec(day.atStartOfDay(zone).toInstant().toEpochMilli(), "interest", interest))
            }
            l.bankHeld = l.bank
        }
        l.bankDay = today.toString()
    }

    /** 지갑에서 저금통으로 [minutes]분 넣기. 지갑에 있는 만큼만 넣을 수 있어요. 넣은 시간은 다음 자정부터 하루를 꼬박 지나야 이자가 붙어요. */
    fun deposit(l: Ledger, minutes: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        settleInterest(l, now, zone)
        if (minutes < 1 || minutes > kotlin.math.floor(l.remaining(now, zone))) return false
        l.bank += minutes
        l.toBank += minutes
        addBankLog(l, BankRec(now, "in", minutes.toDouble()))
        return true
    }

    /** 저금통을 깨서 이자까지 전부 지갑으로. 오늘 밤 이자는 못 받아요. */
    fun breakBank(l: Ledger, now: Long, zone: ZoneId = ZoneId.systemDefault()): Double {
        settleInterest(l, now, zone)
        val m = l.bank
        if (m <= 0) return 0.0
        l.fromBank += m
        l.bank = 0.0
        l.bankHeld = 0.0
        addBankLog(l, BankRec(now, "out", m))
        return m
    }

    private fun addBankLog(l: Ledger, r: BankRec) {
        l.bankLog.add(0, r)
        while (l.bankLog.size > 200) l.bankLog.removeAt(l.bankLog.size - 1)
    }

    private fun pauseYt(l: Ledger, t: Long) {
        if (l.ytOpenSince != null && l.ytPausedAt == null) l.ytPausedAt = t
    }

    private fun finalizeIfGapPassed(l: Ledger, t: Long, zone: ZoneId) {
        val p = l.ytPausedAt ?: return
        if (t - p >= MERGE_GAP_MS) finalizeYt(l, zone)
    }

    /** 유튜브 시청이 1분을 넘기면 그 시청이 시작된 시점에 연속 기록을 끝내요. */
    private fun checkGrace(l: Ledger, t: Long, zone: ZoneId) {
        val open = l.ytOpenSince ?: return
        if (l.brokeThisSession) return
        val end = l.ytPausedAt ?: t
        // 자유 시간에 본 건 빼고 따져요
        if (exceedsGrace(l, RuleMath.chargedMs(open, end, l.rules, zone, l.passWindows()))) {
            breakStreak(l, open, zone)
            l.brokeThisSession = true
        }
    }

    /** 시청 하나를 마무리: 시청 시간 기록, 필요하면 연속 기록 종료, 새 연속 기록 시작. */
    private fun finalizeYt(l: Ledger, zone: ZoneId) {
        val open = l.ytOpenSince ?: return
        val close = l.ytPausedAt ?: return
        addWatched(l, open, close, zone)
        val dur = close - open
        val charged = RuleMath.chargedMs(open, close, l.rules, zone, l.passWindows())
        l.spentMs += charged
        if (dur >= 10_000) {
            l.watches.add(0, WatchRec(open, close))
            while (l.watches.size > 200) l.watches.removeAt(l.watches.size - 1)
        }
        if (!l.brokeThisSession && exceedsGrace(l, charged)) {
            breakStreak(l, open, zone)
            l.brokeThisSession = true
        } else if (!l.brokeThisSession) {
            l.graceUsed += charged
        }
        if (l.brokeThisSession) l.streakStart = close
        l.ytOpenSince = null
        l.ytPausedAt = null
        l.brokeThisSession = false
    }

    private fun exceedsGrace(l: Ledger, dur: Long) =
        dur >= GRACE_MS || l.graceUsed + dur >= GRACE_BUDGET_MS

    private fun breakStreak(l: Ledger, at: Long, zone: ZoneId) {
        val s = l.streakStart ?: return
        val dur = max(0, at - s)
        val c = RuleMath.earnedIn(s, s, max(s, at), l.rules, zone)
        l.totalEarned += c
        // 통계용: 날짜별로 나눠 기록
        var day = Ledger.dayOf(s, zone)
        while (!day.isAfter(Ledger.dayOf(at, zone))) {
            val d0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val d1 = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val a = max(s, d0); val b = kotlin.math.min(at, d1)
            if (b > a) {
                val k = day.toString()
                l.earnedByDay[k] = (l.earnedByDay[k] ?: 0.0) + RuleMath.earnedIn(s, a, b, l.rules, zone)
            }
            day = day.plusDays(1)
        }
        l.best = max(l.best, dur)
        if (dur >= MIN) {
            l.streaks.add(0, StreakRec(s, at, Math.round(c * 10) / 10.0))
            while (l.streaks.size > 200) l.streaks.removeAt(l.streaks.size - 1)
        }
        l.streakStart = null
        l.graceUsed = 0
    }

    private fun addWatched(l: Ledger, start: Long, end: Long, zone: ZoneId) {
        if (end <= start) return
        var day = Ledger.dayOf(start, zone)
        val last = Ledger.dayOf(end, zone)
        while (!day.isAfter(last)) {
            val ms = Ledger.overlap(start, end, day, zone)
            if (ms > 0) {
                val k = day.toString()
                l.watchedByDay[k] = (l.watchedByDay[k] ?: 0) + ms
                val d0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val a = max(start, d0); val b = a + ms
                l.chargedByDay[k] = (l.chargedByDay[k] ?: 0) + RuleMath.chargedMs(a, b, l.rules, zone, l.passWindows())
            }
            day = day.plusDays(1)
        }
    }

    private fun trimWatched(l: Ledger, now: Long, zone: ZoneId) {
        val cutoff = Ledger.dayOf(now, zone).minusDays(STATS_KEEP_DAYS).toString()
        l.watchedByDay.keys.removeAll { it < cutoff }
        l.chargedByDay.keys.removeAll { it < cutoff }
        l.earnedByDay.keys.removeAll { it < cutoff }
        l.activityBonusByDay.keys.removeAll { it < cutoff }
        while (l.passes.size > 60) l.passes.removeAt(0)
    }

    // ----- 기록 (지울 수 없는 메모) -----

    fun note(l: Ledger, now: Long, kind: String, text: String) {
        l.notes.add(0, Note(now, kind, text))
        while (l.notes.size > 150) l.notes.removeAt(l.notes.size - 1)
    }

    private fun hhmmOf(t: Long, zone: ZoneId): String {
        val d = java.time.Instant.ofEpochMilli(t).atZone(zone)
        return "%d/%d %02d:%02d".format(d.monthValue, d.dayOfMonth, d.hour, d.minute)
    }

    // ----- 비상 탈출권 -----

    /** 지금 탈출권을 쓸 수 있는지. 못 쓰면 이유를 돌려줘요. */
    fun passBlockedReason(l: Ledger, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val today = Ledger.dayOf(now, zone)
        val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val days = l.passes.map { Ledger.dayOf(it, zone) }
        return when {
            l.passActiveUntil(now) != null -> "이미 탈출권이 열려 있어요"
            l.blockRuleNow(now, zone) != null -> "'${l.blockRuleNow(now, zone)!!.name}' 규칙 시간에는 쓸 수 없어요"
            days.any { it == today } -> "오늘은 이미 썼어요. 내일 다시 쓸 수 있어요"
            days.count { !it.isBefore(weekStart) } >= PASS_PER_WEEK -> "이번 주 ${PASS_PER_WEEK}번을 다 썼어요. 월요일에 다시 생겨요"
            else -> null
        }
    }

    fun passesLeftThisWeek(l: Ledger, now: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val today = Ledger.dayOf(now, zone)
        val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return (PASS_PER_WEEK - l.passes.count { !Ledger.dayOf(it, zone).isBefore(weekStart) }).coerceAtLeast(0)
    }

    /** 탈출권 사용: 10분 동안 자유롭게 볼 수 있고, 대신 15분이 빚으로 바로 빠져요. */
    fun usePass(l: Ledger, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (passBlockedReason(l, now, zone) != null) return false
        l.passes += now
        l.penalty += PASS_DEBT_MIN
        note(l, now, "pass", "비상 탈출권 사용 · 10분 열기, 빚 ${PASS_DEBT_MIN.toInt()}분")
        return true
    }

    // ----- 규칙 변경 (느슨하게 바꾸는 건 하루 뒤) -----

    private val LOOSE = setOf(Effect.FREE, Effect.DOUBLE)
    private val STRICT = setOf(Effect.BLOCK, Effect.NO_EARN)

    /** 이 변경이 규칙을 느슨하게 만드는지 (자유 시간 늘리기, 금지 시간 줄이기 등). */
    fun isLoosening(old: Rule?, new: Rule?): Boolean {
        if (old == null && new == null) return false
        if (old != null && old.sameContent(new)) return false
        val newLoose = new != null && new.enabled && new.effect in LOOSE
        val oldStrict = old != null && old.enabled && old.effect in STRICT
        return newLoose || oldStrict
    }

    /**
     * 규칙 추가·수정([new]) 또는 삭제([new] = null) 요청.
     * 엄격하게 바꾸는 건 바로 적용, 느슨하게 바꾸는 건 24시간 뒤 적용. 모두 기록에 남아요.
     * @return 바로 적용됐으면 null, 대기 중이면 적용 시각.
     */
    fun requestRuleChange(l: Ledger, ruleId: String, new: Rule?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val old = l.rules.firstOrNull { it.id == ruleId && it.current }
        val name = (new ?: old)?.name ?: "규칙"
        l.pendingRules.removeAll { it.ruleId == ruleId } // 같은 규칙의 이전 대기 요청은 새 요청으로 바꿔요
        if (!isLoosening(old, new)) {
            applyRule(l, ruleId, new, now)
            note(l, now, "rule", describe(old, new, name) + " · 바로 적용")
            return null
        }
        val at = now + RULE_COOLDOWN_MS
        l.pendingRules += PendingRule(at, ruleId, new)
        note(l, now, "rule", describe(old, new, name) + " 요청 · ${hhmmOf(at, zone)}부터 적용")
        return at
    }

    /** 대기 중인 변경 취소 (취소는 원래대로 두는 거라 바로 돼요). */
    fun cancelPending(l: Ledger, ruleId: String, now: Long) {
        val p = l.pendingRules.firstOrNull { it.ruleId == ruleId } ?: return
        l.pendingRules.remove(p)
        note(l, now, "rule", "'${p.rule?.name ?: l.rules.firstOrNull { it.id == ruleId && it.current }?.name ?: "규칙"}' 변경 요청 취소")
    }

    fun applyPendingRules(l: Ledger, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val due = l.pendingRules.filter { it.at <= now }.sortedBy { it.at }
        for (p in due) {
            val old = l.rules.firstOrNull { it.id == p.ruleId && it.current }
            applyRule(l, p.ruleId, p.rule, p.at)
            note(l, p.at, "rule", describe(old, p.rule, (p.rule ?: old)?.name ?: "규칙") + " · 적용됨")
        }
        l.pendingRules.removeAll(due)
    }

    /**
     * 규칙을 [at] 시각부터 바꿔요. 옛 규칙은 지우지 않고 [at]에서 끝난 걸로 남겨 둬서,
     * 그 전 시간은 옛 규칙으로, 그 뒤는 새 규칙으로 계산돼요 (거슬러 적용되지 않음).
     */
    private fun applyRule(l: Ledger, ruleId: String, new: Rule?, at: Long) {
        val i = l.rules.indexOfFirst { it.id == ruleId && it.current }
        if (i >= 0) l.rules[i] = l.rules[i].copy(until = at)
        if (new != null) l.rules += new.copy(since = at, until = Long.MAX_VALUE)
    }

    /** 끝난 옛 규칙 중 더는 계산에 안 쓰이는 것 정리. */
    private fun pruneRules(l: Ledger, now: Long) {
        val oldest = listOfNotNull(l.streakStart, l.ytOpenSince, l.activity?.start, now - 2 * 24 * HOUR).min()
        l.rules.removeAll { !it.current && it.until < oldest }
    }

    private fun describe(old: Rule?, new: Rule?, name: String): String = when {
        old == null && new != null -> "'$name' 규칙 추가 (${new.daysText()} ${new.timeText()} ${Effect.label(new.effect)})"
        new == null -> "'$name' 규칙 삭제"
        old != null && old.enabled != new.enabled && old.copy(enabled = new.enabled) == new ->
            "'$name' 규칙 " + if (new.enabled) "켬" else "끔"
        else -> "'$name' 규칙 수정 (${new.daysText()} ${new.timeText()} ${Effect.label(new.effect)})"
    }

    // ----- 보호 상태 -----

    /** 보호(필수 권한)가 꺼지고 켜지는 걸 기록해요. 꺼진 동안 본 유튜브도 다시 켜면 사용 기록으로 계산돼요. */
    fun protectionChanged(l: Ledger, ok: Boolean, now: Long) {
        val off = l.protectionOffSince
        if (!ok && off == null) {
            l.protectionOffSince = now
            note(l, now, "protect", "보호 꺼짐 (필수 권한이 꺼졌어요)")
        } else if (ok && off != null) {
            val d = now - off
            l.protectionOffTotalMs += d
            l.protectionOffSince = null
            note(l, now, "protect", "보호 다시 켜짐 · ${d / HOUR}시간 ${(d / MIN) % 60}분 꺼져 있었어요")
        }
    }

    // ----- 대체 활동 (습관 타이머 연동) -----

    fun startActivity(l: Ledger, name: String, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        if (l.activity != null) return
        l.activity = Activity(name, now, l.watchedOn(Ledger.dayOf(now, zone), now, zone))
    }

    /** 활동을 끝내고 보너스를 줘요. 활동 중 유튜브를 봤으면 보너스 없음. @return 받은 보너스(분) */
    fun stopActivity(l: Ledger, now: Long, zone: ZoneId = ZoneId.systemDefault()): Double {
        val a = l.activity ?: return 0.0
        l.activity = null
        val dur = max(0, now - a.start)
        // 활동 중에 본 유튜브: 날짜별 시청 합계가 늘었는지로 봐요 (10초 미만으로 잠깐 연 것도 잡혀요)
        var watched = -a.watchedBase
        var d = Ledger.dayOf(a.start, zone)
        while (!d.isAfter(Ledger.dayOf(now, zone))) { watched += l.watchedOn(d, now, zone); d = d.plusDays(1) }
        val key = Ledger.dayOf(now, zone).toString()
        val already = l.activityBonusByDay[key] ?: 0.0
        val mins = dur / MIN
        if (watched > 0) {
            note(l, now, "activity", "${a.name} ${mins}분 · 중간에 유튜브를 봐서 보너스 없음")
            return 0.0
        }
        val bonus = kotlin.math.min(dur.toDouble() / (10 * MIN) * ACTIVITY_BONUS_PER_10MIN, ACTIVITY_BONUS_DAILY_CAP - already).coerceAtLeast(0.0)
        if (bonus > 0) {
            l.totalEarned += bonus
            l.activityBonusByDay[key] = already + bonus
        }
        note(l, now, "activity", "${a.name} ${mins}분 · 보너스 +%.1f분".format(bonus) + if (already + bonus >= ACTIVITY_BONUS_DAILY_CAP) " (오늘 한도)" else "")
        return bonus
    }
}
