package app.notube

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 유튜브를 실시간으로 지켜보는 서비스.
 * 2~3초마다 어떤 앱이 화면에 있는지 확인하고, 유튜브 시간이 0이 되면 차단 화면을 띄워요.
 * 상단 알림에 남은 시간을 보여줘요 (안드로이드는 이런 서비스에 알림을 꼭 띄우게 해요).
 */
class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Repo.init(this)
        Notifs.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, Notifs.GUARD_ID, Notifs.guard(this, Repo.state.value), type)
        } catch (_: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!started) { started = true; scope.launch { loop() } }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        // 지금 보이는 유튜브 화면들. 저장된 상태에서 이어받아요(서비스가 재시작돼도 놓치지 않게).
        Repo.sync()
        val visible = Repo.state.value.ytVisible.toMutableSet()
        var lastSeen = Repo.state.value.lastEventTime
        var lastYt = visible.isNotEmpty()
        var lastSync = System.currentTimeMillis()
        var warned = Int.MAX_VALUE // 이번 시청에서 이미 경고한 남은 분
        var lastNotifMin = Int.MIN_VALUE
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            if (!Usage.hasPermission(this)) { Repo.checkProtection(); delay(10_000); continue }

            // 1) 유튜브가 화면에 보이는지 알아내기 (Engine과 같은 규칙: 사라질 때만 껐다고 봄)
            val from = if (lastSeen > 0) lastSeen + 1 else now - 30 * MIN
            for (e in Usage.events(this, from, now)) {
                Engine.applyVisibility(visible, e)
                lastSeen = maxOf(lastSeen, e.time)
            }

            // 1-2) 백그라운드 재생(소리만) 확인. 바뀌었으면 가짜 화면 '@media'를 열고 닫아요.
            val extra = ArrayList<Ev>()
            val playing = Media.youtubePlaying(this)
            if (playing != (Engine.MEDIA_CLS in Repo.state.value.ytVisible)) {
                val ev = Ev(now, Engine.YOUTUBE_PACKAGES.first(), if (playing) EvType.RESUMED else EvType.STOPPED, Engine.MEDIA_CLS)
                extra += ev
                Engine.applyVisibility(visible, ev)
            }
            val ytNow = visible.isNotEmpty()

            // 2) 유튜브를 켜거나 껐을 때, 아니면 1분마다 정산 (위젯도 이때 갱신돼요)
            if (extra.isNotEmpty() || ytNow != lastYt || now - lastSync >= 60_000) {
                Repo.sync(extra)
                Repo.checkProtection()
                lastSync = now
                if (ytNow && !lastYt) warned = Int.MAX_VALUE
                lastYt = ytNow
            }

            // 3) 남은 시간 확인
            val l = Repo.state.value
            val rem = l.remaining(now)
            val remMin = kotlin.math.floor(rem).toInt()
            if (remMin != lastNotifMin) {
                lastNotifMin = remMin
                NotificationManagerCompat.from(this).let {
                    try { it.notify(Notifs.GUARD_ID, Notifs.guard(this, l)) } catch (_: SecurityException) { }
                }
            }
            val free = l.freeNow(now)
            val banRule = l.blockRuleNow(now)
            // 금지 규칙은 자유 시간·탈출권보다 우선해요 (금지 시간 직전에 탈출권을 써서 우회하지 못하게)
            if (ytNow && banRule != null) {
                withContext(Dispatchers.Main) { Blocker.block(this@GuardService, banRule) }
            } else if (ytNow && !free) {
                when {
                    rem <= 0 -> withContext(Dispatchers.Main) { Blocker.block(this@GuardService) }
                    rem <= 1 && warned > 1 -> { warned = 1; Notifs.warn(this, "유튜브 시간이 1분 남았어요") }
                    rem <= 5 && warned > 5 -> { warned = 5; Notifs.warn(this, "유튜브 시간이 5분 남았어요") }
                }
            }
            delay(if (ytNow) 1_000 else 2_500)
        }
    }

    companion object {
        fun canRun(ctx: Context) = Usage.hasPermission(ctx)

        fun start(ctx: Context) {
            if (!canRun(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
            } catch (_: Exception) {
                // 백그라운드에서는 시작이 막힐 수 있어요. 앱을 열면 다시 시작돼요.
            }
        }
    }
}

/** 유튜브 시간이 0일 때 화면 전체를 덮는 차단 화면. */
object Blocker {
    private var view: View? = null

    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    @SuppressLint("SetTextI18n")
    fun block(ctx: Context, rule: Rule? = null) {
        Media.pauseYoutube(ctx) // 소리만 나오는 재생도 멈춤
        if (view != null) { goHome(ctx); return }
        if (!canOverlay(ctx)) { Notifs.warn(ctx, "유튜브 시간을 다 썼어요. 유튜브를 꺼 주세요."); return }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF10151F.toInt() else 0xFFE9EDF3.toInt()
        val ink = if (night) 0xFFE7ECF5.toInt() else 0xFF18223A.toInt()
        val muted = if (night) 0xFF98A3B8.toInt() else 0xFF5E6A80.toInt()
        val stop = if (night) 0xFFF07A6F.toInt() else 0xFFB9382F.toInt()
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

        val l = Repo.state.value
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true
        }
        val until = rule?.let { RuleMath.activeUntil(it, System.currentTimeMillis(), java.time.ZoneId.systemDefault()) }
        root.addView(TextView(ctx).apply {
            text = if (rule != null) "지금은 유튜브 금지 시간이에요" else "유튜브를 닫았어요"
            setTextColor(stop); textSize = 26f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(ctx).apply {
            text = if (rule != null) "'${rule.name}' 규칙 때문에 막았어요." +
                (until?.let { "\n${fmtDate(it).substringAfter(' ')}부터 다시 볼 수 있어요." } ?: "") +
                "\n\n규칙은 앱의 '나만의 규칙'에서 바꿀 수 있어요."
            else "지갑의 유튜브 시간을 다 썼어요.\n" +
                "유튜브를 안 보는 10분마다 1분이 다시 생겨요.\n\n" +
                (if (l.bank >= 1) "저금통에 ${fmtMin(l.bank.toInt())}이 있어요. 앱에서 깨면 볼 수 있어요."
                else "최장 기록 ${fmtDur(l.best)}에 도전해 보세요.")
            setTextColor(muted); textSize = 16f; gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(16), 0, dp(32))
        })
        root.addView(Button(ctx).apply {
            text = "확인"
            setTextColor(bg); textSize = 17f
            background = GradientDrawable().apply { setColor(ink); cornerRadius = dp(14).toFloat() }
            minWidth = dp(200); minHeight = dp(52)
            setOnClickListener { dismiss(ctx) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        try {
            wm.addView(root, lp)
            view = root
        } catch (_: Exception) { }
        goHome(ctx)
        closeYoutube(ctx)
    }

    /**
     * 홈으로 내보낸 뒤 유튜브를 종료해요. 안드로이드 13 이하에서만 가능해요.
     * (14부터는 다른 앱 종료가 막혀 있어서, 대신 열 때마다 바로 다시 막아요.)
     */
    private fun closeYoutube(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 34) return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Engine.YOUTUBE_PACKAGES.forEach { try { am.killBackgroundProcesses(it) } catch (_: Exception) { } }
        }, 1500)
    }

    private fun dismiss(ctx: Context) {
        val v = view ?: return
        view = null
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) { }
    }

    /** 유튜브를 화면에서 내려서 영상이 멈추게 해요. */
    private fun goHome(ctx: Context) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }
}

object Notifs {
    const val GUARD_ID = 1
    private const val GUARD = "guard"
    private const val ALERTS = "alerts"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(GUARD, "남은 유튜브 시간", NotificationManager.IMPORTANCE_LOW).apply {
            description = "상단에 남은 유튜브 시간을 보여줘요"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(ALERTS, "시간 경고", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "유튜브 시간이 얼마 안 남았을 때 알려줘요"
        })
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun guard(ctx: Context, l: Ledger): Notification {
        val now = System.currentTimeMillis()
        val rem = l.remainingMin(now)
        val title = (if (rem > 0) "유튜브 ${fmtMin(rem)} 볼 수 있어요" else "지갑이 비었어요") +
            (if (l.bank >= 1) " · 저금통 ${fmtMin(l.bank.toInt())}" else "")
        val active = l.activeRules(now)
        val text = when {
            !l.started -> "앱을 열어 시작하세요"
            active.any { it.effect == Effect.FREE } -> "자유 시간 · 유튜브를 봐도 시간이 안 줄어요"
            active.any { it.effect == Effect.BLOCK } -> "유튜브 금지 시간 · ${active.first { it.effect == Effect.BLOCK }.name}"
            l.watchingNow -> "유튜브 보는 중 · 1분 볼 때마다 1분씩 줄어요"
            else -> "안 보는 중 · ${TIERS[tierIndex(l.currentStreak(now))].per10}"
        }
        return NotificationCompat.Builder(ctx, GUARD)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    fun warn(ctx: Context, msg: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, ALERTS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(openApp(ctx))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(2, n) } catch (_: SecurityException) { }
    }
}

/** 휴대폰을 켜거나 앱을 업데이트하면 감시를 다시 시작해요. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Repo.init(ctx)
            GuardService.start(ctx)
        }
    }
}

fun fmtMin(m: Int): String = if (m < 60) "${m}분" else if (m % 60 == 0) "${m / 60}시간" else "${m / 60}시간 ${m % 60}분"
