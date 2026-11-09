package app.notube

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Usage {
    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isYoutubeInstalled(ctx: Context): Boolean = Engine.YOUTUBE_PACKAGES.any {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
    }

    private val WANTED = setOf(
        EvType.RESUMED, EvType.PAUSED, EvType.STOPPED,
        EvType.SCREEN_OFF, EvType.KEYGUARD_SHOWN, EvType.SHUTDOWN,
    )

    /** 안드로이드 10 미만은 '중지' 신호가 없어서 '일시정지'를 대신 써요. */
    private val HAS_STOPPED = Build.VERSION.SDK_INT >= 29

    /** [from]~[to] 사이의 화면 전환 이벤트. 시스템은 대략 최근 며칠치만 보관해요. */
    fun events(ctx: Context, from: Long, to: Long): List<Ev> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val out = ArrayList<Ev>()
        val it = usm.queryEvents(from, to) ?: return out
        val e = UsageEvents.Event()
        while (it.hasNextEvent()) {
            it.getNextEvent(e)
            if (e.eventType !in WANTED) continue
            val type = when {
                e.eventType == EvType.PAUSED && !HAS_STOPPED -> EvType.STOPPED
                e.eventType == EvType.PAUSED -> continue // 보이는 채로 멈춘 것일 수 있어서 무시
                else -> e.eventType
            }
            out += Ev(e.timeStamp, e.packageName ?: "", type, e.className ?: "")
        }
        return out
    }
}

/** 상태 저장소. 화면과 백그라운드 작업이 같이 써서 lock으로 순서를 지켜요. */
object Repo {
    private const val PREFS = "notube"
    private const val KEY = "ledger"
    private val lock = Any()
    private lateinit var app: Context
    private val _state = MutableStateFlow(Ledger())
    val state: StateFlow<Ledger> = _state.asStateFlow()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (::app.isInitialized) return
            app = ctx.applicationContext
            _state.value = load()
        }
    }

    /** 마지막으로 저장한 JSON. 같으면 다시 쓰지 않아요. */
    private var savedJson: String? = null

    private fun load(): Ledger {
        val raw = savedJson ?: app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return try {
            Ledger.fromJson(raw)
        } catch (_: Exception) {
            // 읽을 수 없는 기록은 지우지 않고 따로 보관해 둬요 (나중에 되살릴 수 있게)
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (raw != null && prefs.getString("ledger_broken", null) == null) prefs.edit().putString("ledger_broken", raw).commit()
            Ledger()
        }
    }

    /** 휴대폰의 '자동 날짜 및 시간'이 켜져 있는지. */
    private fun autoTimeOn(): Boolean = try {
        android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.AUTO_TIME, 1) == 1
    } catch (_: Exception) { true }

    /** 사용 기록을 다시 읽어 정산해요 (권한이 있을 때). 오래 비어 있던 구간은 적립하지 않아요. */
    private fun catchUp(l: Ledger, now: Long, extra: List<Ev> = emptyList()) {
        val trusted = autoTimeOn()
        if (trusted != l.clockTrusted) {
            l.clockTrusted = trusted
            Engine.note(l, now, "clock", if (trusted) "자동 날짜·시간 다시 켜짐" else "자동 날짜·시간 꺼짐 · 탈출권·규칙 적용·이자를 멈춰요")
        }
        val windowStart = now - EVENT_WINDOW_MS
        if (l.lastEventTime < windowStart - HOUR) Engine.markUnverified(l, l.lastEventTime, windowStart)
        val from = maxOf(l.lastEventTime - 1, windowStart)
        val up = android.os.SystemClock.elapsedRealtime()
        // 재부팅하면 가동 시간이 0부터라 알 수 없어요
        val since = if (l.lastSyncUptime in 1..up) up - l.lastSyncUptime else null
        // 시계가 뒤로 바뀌었으면 기록을 읽는 시작점도 새 시계 기준으로
        val readFrom = if (l.lastEventTime > now + 2 * MIN) (since?.let { now - it } ?: now) - 1 else from
        Engine.process(l, if (Usage.hasPermission(app)) Usage.events(app, maxOf(readFrom, windowStart), now) + extra else extra, now, sinceLastSyncMs = since)
        l.lastSyncUptime = up
    }

    /** 휴대폰이 사용 기록을 보관하는 기간 안에서만 읽어요. */
    private const val EVENT_WINDOW_MS = 7 * 24 * HOUR

    private fun save(l: Ledger) {
        val json = l.toJson()
        if (json != savedJson) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).commit()
            savedJson = json
        }
        // mutate는 매번 새로 읽은 객체를 쓰고 버리니, 복사하지 않고 그대로 화면에 넘겨요
        _state.value = l
        try { NotubeWidget.updateAll(app, l) } catch (_: Exception) { }
    }

    private fun mutate(block: (Ledger) -> Unit): Ledger = synchronized(lock) {
        val l = load()
        block(l)
        save(l)
        l
    }

    fun deposit(minutes: Int): Boolean {
        var ok = false
        mutate { ok = Engine.deposit(it, minutes, System.currentTimeMillis()) }
        return ok
    }

    fun breakBank(): Double {
        var m = 0.0
        mutate { m = Engine.breakBank(it, System.currentTimeMillis()) }
        return m
    }

    /**
     * 기록을 지우고 새로 시작해요. 악용을 막으려고 넘겨받는 것: 빚(마이너스 지갑), 탈출권 사용 횟수,
     * 규칙과 대기 중인 변경, 지울 수 없는 기록.
     */
    fun reset(): Boolean = synchronized(lock) {
        // 보호가 꺼져 있으면 그동안 본 유튜브를 확인할 수 없어서 다시 시작을 막아요 (빚을 지우는 데 쓰이지 않게)
        if (!Usage.hasPermission(app)) return false
        val old = load()
        val now = System.currentTimeMillis()
        if (old.started) catchUp(old, now) // 먼저 최신 사용 기록으로 정산
        val debt = maxOf(0.0, -old.remaining(now))
        val l = Ledger()
        l.rules += old.rules
        l.pendingRules += old.pendingRules
        l.passes += old.passes
        l.notes += old.notes
        l.penalty = debt
        l.protectionOffSince = old.protectionOffSince
        l.clockTrusted = old.clockTrusted
        // 통계와 오늘의 보너스 한도는 기록이 아니라 사실이라 그대로 (노션의 오늘 줄도 0으로 바뀌지 않게)
        l.watchedByDay.putAll(old.watchedByDay); l.chargedByDay.putAll(old.chargedByDay)
        l.earnedByDay.putAll(old.earnedByDay); l.activityBonusByDay.putAll(old.activityBonusByDay)
        Engine.start(l, now)
        Engine.note(l, now, "reset", "처음부터 다시 시작" + if (debt > 0) " · 빚 ${debt.toInt()}분은 그대로" else "")
        save(l)
        true
    }

    /** 규칙 추가·수정. 느슨하게 바꾸는 건 24시간 뒤 적용돼요. @return 대기 중이면 적용 시각 */
    fun putRule(r: Rule): Long? {
        var at: Long? = null
        mutate { l ->
            val now = System.currentTimeMillis()
            catchUp(l, now) // 바꾸기 전 규칙으로 지금까지를 먼저 정산
            at = Engine.requestRuleChange(l, r.id, r, now)
        }
        return at
    }

    fun removeRule(id: String): Long? {
        var at: Long? = null
        mutate { l ->
            val now = System.currentTimeMillis()
            catchUp(l, now)
            at = Engine.requestRuleChange(l, id, null, now)
        }
        return at
    }

    fun addNote(kind: String, text: String) = mutate { Engine.note(it, System.currentTimeMillis(), kind, text) }

    fun cancelPendingRule(id: String) = mutate { Engine.cancelPending(it, id, System.currentTimeMillis()) }

    fun usePass(): Boolean {
        var ok = false
        mutate { l ->
            val now = System.currentTimeMillis()
            catchUp(l, now)
            ok = Engine.usePass(l, now)
        }
        return ok
    }

    fun startActivity(name: String) = mutate { Engine.startActivity(it, name, System.currentTimeMillis()) }

    fun stopActivity(): Double {
        var b = 0.0
        mutate { l ->
            val now = System.currentTimeMillis()
            val verified = Usage.hasPermission(app)
            if (verified) catchUp(l, now) // 활동 중 유튜브를 봤는지 최신 기록으로 확인
            b = Engine.stopActivity(l, now, verified = verified)
        }
        return b
    }

    /** 필수 권한이 꺼지고 켜지는 걸 기록해요. 바뀌었을 때만 저장. */
    fun checkProtection() {
        val ok = Usage.hasPermission(app) && Blocker.canOverlay(app)
        val changed = synchronized(lock) {
            val cur = load()
            if (!cur.started || (cur.protectionOffSince == null) == ok) false
            else { Engine.protectionChanged(cur, ok, System.currentTimeMillis()); save(cur); true }
        }
        if (changed && !ok) Notifs.warn(app, "보호가 꺼졌어요. 다시 켜면 그동안 본 유튜브도 한꺼번에 계산돼요")
    }

    private fun sign(text: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec("notube-backup-v1:keep-it-honest".toByteArray(), "HmacSHA256"))
        return mac.doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 백업용 JSON. 고친 파일을 알아볼 수 있게 서명을 붙여요. */
    fun exportJson(): String {
        val ledger = load().toJson()
        val at = System.currentTimeMillis()
        return org.json.JSONObject()
            .put("app", "notube").put("exportedAt", at)
            .put("ledger", ledger).put("sig", sign("$at\n$ledger")).toString(2)
    }

    enum class ImportResult { OK, BAD_FILE, MODIFIED, TOO_OLD, NOT_RESTORE }

    /** 이보다 오래된 백업은 그 사이 사용 기록이 휴대폰에 남아 있지 않아서 못 불러와요. */
    private const val BACKUP_MAX_AGE_MS = 7 * 24 * HOUR

    /**
     * 백업 파일 불러오기. 앱을 지웠다 다시 깔았거나 휴대폰을 바꿨을 때 복원하는 용도예요. 악용을 막으려고:
     * - 서명이 안 맞으면(파일을 고쳤으면) 거절
     * - 7일 넘은 백업은 거절 (그 사이 기록을 확인할 수 없어서)
     * - 지금 설치보다 나중에 만든 백업은 거절 (저장 → 실컷 보고 → 되돌리기 방지)
     * - 백업 이후의 사용 기록을 다시 계산하고, 그 뒤에 쓴 탈출권 빚도 붙여요
     * - 백업 속 규칙은 지금 규칙에 '변경 요청'으로 들어가요 (느슨한 건 24시간 뒤)
     */
    fun importJson(text: String): ImportResult {
        val ledgerText: String
        val exportedAt: Long
        try {
            val o = org.json.JSONObject(text)
            ledgerText = o.getString("ledger")
            exportedAt = o.getLong("exportedAt")
            if (o.optString("sig") != sign("$exportedAt\n$ledgerText")) return ImportResult.MODIFIED
        } catch (_: Exception) { return ImportResult.BAD_FILE }
        val imported = try { Ledger.fromJson(ledgerText) } catch (_: Exception) { return ImportResult.BAD_FILE }
        if (!imported.started) return ImportResult.BAD_FILE
        val now = System.currentTimeMillis()
        // 나이는 백업 속 마지막 기록 시각으로 따져요 (그 뒤 기록을 휴대폰에서 다시 읽을 수 있어야 해서)
        if (now - imported.lastEventTime > BACKUP_MAX_AGE_MS || now - exportedAt > BACKUP_MAX_AGE_MS) return ImportResult.TOO_OLD
        synchronized(lock) {
            val cur = load()
            if (cur.started && cur.trackingSince < exportedAt) return ImportResult.NOT_RESTORE
            val curRules = cur.currentRules()
            val backupRules = imported.currentRules()
            // 백업 상태에서 다시 시작: 그 뒤 사용 기록을 다시 읽어 계산해요
            imported.activity = null
            imported.protectionOffSince = null
            imported.rules.clear(); imported.rules += curRules.map { it.copy(since = imported.lastEventTime) }
            imported.pendingRules.clear(); imported.pendingRules += cur.pendingRules
            for (p in cur.passes) if (p !in imported.passes) { imported.passes += p; imported.penalty += PASS_DEBT_MIN }
            imported.passes.sort()
            imported.notes.addAll(0, cur.notes.filter { it !in imported.notes })
            catchUp(imported, now)
            Engine.note(imported, now, "backup", "백업 파일에서 복원")
            for (r in backupRules) if (imported.currentRules().none { it.sameContent(r) }) Engine.requestRuleChange(imported, r.id, r, now)
            save(imported)
        }
        return ImportResult.OK
    }

    /** 사용 기록을 읽어 정산해요. 권한이 없으면 아무것도 안 해요. */
    fun sync(extra: List<Ev> = emptyList()): Ledger? {
        if (!Usage.hasPermission(app)) return null
        return mutate { l ->
            val now = System.currentTimeMillis()
            if (!l.started) {
                Engine.start(l, now)
                Engine.note(l, now, "start", "시작")
            }
            catchUp(l, now, extra)
        }
    }
}
