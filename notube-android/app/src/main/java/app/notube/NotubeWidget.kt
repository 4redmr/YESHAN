package app.notube

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * 홈 화면 위젯. 연속 기록 시간은 Chronometer라서 위젯을 갱신하지 않아도 초 단위로 올라가요.
 * 남은 시간과 진행 막대는 정산할 때마다(감시 서비스가 1분마다, 앱을 열 때) 갱신돼요.
 */
class NotubeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Repo.init(ctx)
        render(ctx, mgr, ids, Repo.state.value)
        // 최신 사용 기록으로 정산 (정산이 끝나면 Repo가 위젯을 다시 그려요)
        val pending = goAsync()
        thread {
            try { Repo.sync() } catch (_: Exception) { } finally { pending.finish() }
        }
    }

    companion object {
        fun updateAll(ctx: Context, l: Ledger) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NotubeWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids, l)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray, l: Ledger) {
            val views = build(ctx, l, Usage.hasPermission(ctx))
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        fun build(ctx: Context, l: Ledger, hasPerm: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_notube)
            val now = System.currentTimeMillis()
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            fun color(id: Int) = ContextCompat.getColor(ctx, id)

            if (!hasPerm || !l.started) {
                v.setTextViewText(R.id.w_state, "노튜브 저금통")
                v.setTextColor(R.id.w_state, color(R.color.w_ink))
                v.setTextViewText(R.id.w_balance, "")
                v.setViewVisibility(R.id.w_clock, View.GONE)
                v.setViewVisibility(R.id.w_watching, View.VISIBLE)
                v.setTextViewText(R.id.w_watching, "눌러서 시작하기")
                v.setTextColor(R.id.w_watching, color(R.color.w_coin))
                v.setTextViewText(R.id.w_next, "참은 만큼만 유튜브를 볼 수 있어요")
                v.setViewVisibility(R.id.w_progress, View.GONE)
                return v
            }

            val rem = l.remaining(now)
            val remMin = maxOf(0, kotlin.math.floor(rem).toInt())
            v.setTextViewText(R.id.w_balance, "${fmtMin(remMin)} 남음")
            v.setTextColor(R.id.w_balance, color(if (rem <= 0) R.color.w_stop else R.color.w_coin))
            val start = l.streakStart
            if (l.watchingNow || start == null) {
                v.setTextViewText(R.id.w_state, "유튜브 보는 중")
                v.setTextColor(R.id.w_state, color(R.color.w_stop))
                v.setChronometer(R.id.w_clock, SystemClock.elapsedRealtime(), null, false)
                v.setViewVisibility(R.id.w_clock, View.GONE)
                v.setViewVisibility(R.id.w_watching, View.VISIBLE)
                v.setTextViewText(R.id.w_watching, if (rem <= 0) "시간을 다 썼어요" else "1분에 1분씩 줄어요")
                v.setTextColor(R.id.w_watching, color(R.color.w_stop))
                v.setTextViewText(R.id.w_next, "끄는 순간 다시 쌓이기 시작해요")
                v.setViewVisibility(R.id.w_progress, View.GONE)
                return v
            }
            v.setTextViewText(R.id.w_state, "유튜브 안 본 지")
            v.setTextColor(R.id.w_state, color(R.color.w_go))
            v.setViewVisibility(R.id.w_clock, View.VISIBLE)
            v.setViewVisibility(R.id.w_watching, View.GONE)
            // Chronometer는 부팅 후 경과 시간 기준이라 변환해요
            v.setChronometer(R.id.w_clock, SystemClock.elapsedRealtime() - (now - start), null, true)
            val tier = TIERS[tierIndex(now - start)]
            val bankNote = if (l.bank >= 1) " · 저금통 ${fmtMin(l.bank.toInt())}" else ""
            v.setTextViewText(R.id.w_next, (if (rem <= 0) "지갑이 비었어요 · ${tier.per10}" else tier.per10) + bankNote)
            v.setViewVisibility(R.id.w_progress, View.VISIBLE)
            val frac = (rem - kotlin.math.floor(rem)).coerceIn(0.0, 1.0)
            v.setProgressBar(R.id.w_progress, 1000, (frac * 1000).toInt(), false)
            return v
        }
    }
}
