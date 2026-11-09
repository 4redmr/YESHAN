package app.notube

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.init(this)
        Notifs.ensureChannels(this)
        SyncWorker.schedule(this)
        setContent { NotubeTheme { App() } }
    }
}

// ---------------- 색 ----------------

data class Pal(
    val bg: Color, val surface: Color, val ink: Color, val muted: Color, val line: Color,
    val coin: Color, val coinSoft: Color, val stop: Color, val stopSoft: Color, val go: Color,
)

private val Light = Pal(
    Color(0xFFE9EDF3), Color.White, Color(0xFF18223A), Color(0xFF5E6A80), Color(0xFFD3DAE6),
    Color(0xFFC98A0B), Color(0xFFF6E7C2), Color(0xFFB9382F), Color(0xFFF7DEDB), Color(0xFF2D7A58),
)
private val Dark = Pal(
    Color(0xFF10151F), Color(0xFF1A2130), Color(0xFFE7ECF5), Color(0xFF98A3B8), Color(0xFF2C3547),
    Color(0xFFF0B840), Color(0xFF3A3018), Color(0xFFF07A6F), Color(0xFF3B2120), Color(0xFF5FC795),
)
val LocalPal = staticCompositionLocalOf { Light }
private val Mono = FontFamily.Monospace

@Composable
fun NotubeTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme = (if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()).copy(
        primary = p.ink, onPrimary = p.bg, background = p.bg, surface = p.surface,
        onBackground = p.ink, onSurface = p.ink, outline = p.line, surfaceVariant = p.surface,
    )
    CompositionLocalProvider(LocalPal provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}

// ---------------- 포맷 ----------------

fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

fun fmtDur(ms: Long): String {
    val m = ms / MIN
    return when {
        m < 60 -> "${m}분"
        m < 48 * 60 -> "${m / 60}시간 ${m % 60}분"
        else -> "${m / 1440}일 ${(m % 1440) / 60}시간"
    }
}

fun fmtDate(t: Long): String {
    val d = java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
    return "%d/%d %02d:%02d".format(d.monthValue, d.dayOfMonth, d.hour, d.minute)
}

// ---------------- 권한 ----------------

data class Perms(val usage: Boolean, val overlay: Boolean, val notif: Boolean, val media: Boolean) {
    val ready get() = usage && overlay
    val all get() = ready && notif && media
}

fun readPerms(ctx: android.content.Context) = Perms(
    usage = Usage.hasPermission(ctx),
    overlay = Blocker.canOverlay(ctx),
    notif = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
    media = Media.hasPermission(ctx),
)

// ---------------- 화면 ----------------

@Composable
fun App() {
    val ctx = LocalContext.current
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    val s by Repo.state.collectAsState()
    var perms by remember { mutableStateOf(readPerms(ctx)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        perms = readPerms(ctx)
    }

    suspend fun sync() = withContext(Dispatchers.IO) { Repo.sync() }

    LifecycleResumeEffect(Unit) {
        perms = readPerms(ctx)
        scope.launch {
            sync()
            withContext(Dispatchers.IO) { Repo.checkProtection() }
            if (perms.usage) GuardService.start(ctx)
        }
        onPauseOrDispose { }
    }
    LaunchedEffect(perms.usage) {
        var tick = 0
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            if (perms.usage && ++tick % 20 == 0) sync()
        }
    }

    Scaffold(containerColor = p.bg) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { Text("노튜브 저금통", fontSize = 28.sp, fontWeight = FontWeight.Black, color = p.ink) }
            if (!perms.all) {
                item {
                    SetupCard(perms, onNotif = {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
            }
            if (perms.usage && s.started) {
                item { Bank(s, now) }
                item { ActivityCard(s, now) }
                item { PiggyBank(s, now) }
                item { StatsRow(s, now) }
                item { StatsCard(s, now) }
                item { MyRules(s, now) }
                item { GuardCard(s, perms) }
                item { NotionCard(s) }
                item { AddWidgetButton() }
                item { SectionTitle("기록") }
                item { History(s) }
            }
            item { Rules() }
            if (perms.usage && s.started) item { ResetButton() }
        }
    }
}

@Composable
fun ResetButton() {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    TextButton(onClick = { ask = true }, modifier = Modifier.fillMaxWidth()) {
        Text("처음부터 다시 시작", color = p.muted, fontSize = 14.sp)
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("처음부터 다시 시작할까요?") },
        text = { Text("지갑과 저금통의 시간, 기록이 모두 지워지고 0분부터 새로 시작해요. 되돌릴 수 없어요.") },
        confirmButton = {
            TextButton(onClick = {
                ask = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { Repo.reset() }
                    if (!ok) android.widget.Toast.makeText(ctx, "보호(사용 시간 접근)가 꺼져 있으면 다시 시작할 수 없어요. 먼저 권한을 켜 주세요", android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text("다시 시작", color = p.stop, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("취소", color = p.muted) } },
    )
}

@Composable
fun SectionTitle(t: String) {
    Text(t, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = LocalPal.current.muted)
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(20.dp)).padding(20.dp),
        content = content,
    )
}

@Composable
fun SetupCard(perms: Perms, onNotif: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPal.current
    val ytInstalled = remember { Usage.isYoutubeInstalled(ctx) }
    fun open(action: String) {
        try { ctx.startActivity(Intent(action, Uri.parse("package:${ctx.packageName}"))) } catch (_: Exception) {
            try { ctx.startActivity(Intent(action)) } catch (_: Exception) { }
        }
    }
    Card {
        Text("유튜브는 참은 만큼만 볼 수 있어요", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "유튜브를 안 본 10분마다 볼 수 있는 시간이 1분 생겨요. 다 쓰면 유튜브가 막혀요. '삭제 방지'를 켜 두면 충동적으로 지웠다 다시 깔 수 없어요.",
            color = p.muted, fontSize = 15.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(18.dp))
        Step(1, "사용 시간 접근", "유튜브가 화면에 켜졌는지 알기 위해 필요해요. 본 영상이나 내용은 읽지 않아요.",
            done = perms.usage, button = "허용하러 가기") { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        Spacer(Modifier.height(14.dp))
        Step(2, "다른 앱 위에 표시", "시간을 다 썼을 때 유튜브 위에 차단 화면을 띄우는 데 필요해요.",
            done = perms.overlay, button = "허용하러 가기") { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
        Spacer(Modifier.height(14.dp))
        Step(3, "알림 접근 (권장)", "화면을 닫고 소리만 듣는 백그라운드 재생도 잡아내요. 알림 내용은 읽지 않고, 유튜브가 재생 중인지만 확인해요.",
            done = perms.media, button = "허용하러 가기") {
            val detail = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    android.content.ComponentName(ctx, MediaWatcher::class.java).flattenToString()) else null
            try { ctx.startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {
                try { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
            }
        }
        Spacer(Modifier.height(14.dp))
        Step(4, "알림 (선택)", "5분, 1분 남았을 때 미리 알려드려요.",
            done = perms.notif, button = "알림 켜기", onClick = onNotif)
        Spacer(Modifier.height(14.dp))
        Text("설정 화면에서 '노튜브 저금통'을 찾아 허용한 뒤 뒤로 돌아오면 돼요.", color = p.muted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text("스위치가 회색이라 안 켜지면: 설정 → 애플리케이션 → 노튜브 저금통 → 오른쪽 위 ⋮ → 제한된 설정 허용",
            color = p.muted, fontSize = 13.sp, lineHeight = 19.sp)
        if (!ytInstalled) {
            Spacer(Modifier.height(8.dp))
            Text("이 휴대폰에서 유튜브 앱을 찾지 못했어요. 브라우저로 보는 유튜브는 감지할 수 없어요.", color = p.stop, fontSize = 13.sp)
        }
    }
}

@Composable
fun Step(n: Int, title: String, desc: String, done: Boolean, button: String, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (done) p.go else p.line),
            contentAlignment = Alignment.Center,
        ) { Text(if (done) "✓" else "$n", color = if (done) p.surface else p.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = p.ink, fontSize = 15.sp)
            Text(desc, color = p.muted, fontSize = 13.sp, lineHeight = 19.sp)
            if (!done) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onClick, shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.bg),
                ) { Text(button, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** 메인: 유튜브 볼 수 있는 시간. */
@Composable
fun Bank(s: Ledger, now: Long) {
    val p = LocalPal.current
    val rem = s.remaining(now)
    val remMin = max(0, floor(rem).toInt())
    val watching = s.watchingNow
    val out = rem <= 0
    val elapsed = s.currentStreak(now)
    val k = tierIndex(elapsed)
    val next = TIERS.getOrNull(k + 1)
    Card {
        val active = remember(s, now / 30_000) { s.activeRules(now) }
        Text("지갑 · 지금 유튜브 볼 수 있는 시간", fontSize = 14.sp, color = p.muted, fontWeight = FontWeight.Medium)
        if (active.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                active.take(2).forEach { r ->
                    Text("${r.name} · ${Effect.label(r.effect)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = effectColor(r.effect),
                        modifier = Modifier.border(1.dp, effectColor(r.effect), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val big = if (remMin < 60) "$remMin" else "${remMin / 60}:%02d".format(remMin % 60)
            Text(big, fontFamily = Mono, fontSize = 64.sp, fontWeight = FontWeight.SemiBold,
                color = if (out) p.stop else p.ink, lineHeight = 68.sp)
            Spacer(Modifier.width(6.dp))
            Text(if (remMin < 60) "분" else "시간:분", fontSize = 18.sp, color = p.muted, modifier = Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
        when {
            watching && s.freeNow(now) -> {
                Status("유튜브 보는 중 · 자유 시간", "지금은 봐도 시간이 안 줄어요", p.go)
            }
            watching -> {
                val bg = s.ytVisible == setOf(Engine.MEDIA_CLS)
                Status(if (bg) "유튜브 백그라운드 재생 중" else "유튜브 보는 중", "1분 볼 때마다 1분씩 줄어요", p.stop)
            }
            out -> {
                val fresh = s.spentMs == 0L && s.toBank == 0.0
                Status(if (fresh) "아직 모은 시간이 없어요" else "지갑이 비었어요",
                    "10분 참으면 1분이 생겨요 · 지금 ${fmtClock(elapsed)}째 참는 중", if (fresh) p.go else p.stop)
                if (s.bank >= 1) {
                    Spacer(Modifier.height(6.dp))
                    Text("저금통에 ${fmtMin(s.bank.toInt())}이 있어요. 깨면 볼 수 있어요.", fontSize = 13.sp, color = p.coin,
                        modifier = Modifier.padding(start = 18.dp))
                }
            }
            else -> {
                val noEarn = active.firstOrNull { it.effect == Effect.NO_EARN }
                val dbl = active.any { it.effect == Effect.DOUBLE }
                Status("유튜브 안 보는 중 · ${fmtClock(elapsed)}",
                    when {
                        noEarn != null -> "지금은 적립 쉬는 시간이에요 (${noEarn.name})"
                        dbl -> TIERS[k].per10 + " · 지금 2배"
                        else -> TIERS[k].per10
                    }, p.go)
                Spacer(Modifier.height(12.dp))
                // 다음 1분까지 진행
                val frac = (rem - floor(rem)).toFloat().coerceIn(0f, 1f)
                Text("다음 +1분까지", fontSize = 12.sp, color = p.muted)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(p.line)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(8.dp)).background(p.coin))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("${TIERS[k].label} 속도", filled = true)
                    Chip(if (next != null) "${fmtDur(next.from - elapsed)} 더 참으면 ${next.label}" else "최고 속도")
                }
            }
        }
        if (!s.freeNow(now) || s.passActiveUntil(now) != null) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = p.line)
            Spacer(Modifier.height(12.dp))
            PassRow(s, now)
        }
        if (s.penalty >= 1) {
            Spacer(Modifier.height(6.dp))
            Text("탈출권 빚 ${s.penalty.toInt()}분이 지갑에서 빠져 있어요", fontSize = 12.sp, color = p.muted)
        }
    }
}

/** 저금통: 지갑 시간을 모아 두면 매일 자정 이자가 붙어요. */
@Composable
fun PiggyBank(s: Ledger, now: Long) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallet = max(0, floor(s.remaining(now)).toInt())
    var askBreak by remember { mutableStateOf(false) }
    fun put(m: Int) = scope.launch {
        val ok = withContext(Dispatchers.IO) { Repo.deposit(m) }
        if (ok) android.widget.Toast.makeText(ctx, "저금통에 ${fmtMin(m)} 넣었어요", android.widget.Toast.LENGTH_SHORT).show()
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("저금통", fontSize = 14.sp, color = p.muted, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.1f".format(s.bank), fontFamily = Mono, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = p.coin)
                    Spacer(Modifier.width(4.dp))
                    Text("분", fontSize = 16.sp, color = p.muted, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            JarIcon(fill = (s.bank / 120.0).toFloat().coerceIn(0f, 1f))
        }
        Spacer(Modifier.height(6.dp))
        val pending = s.pendingInterest()
        Text(
            when {
                s.bank < 0.05 -> "지갑 시간을 넣어 두면 매일 자정 10% 이자가 붙어요. 하루를 꼬박 들어 있던 시간만 이자를 받아요."
                pending > 0 -> "오늘 자정에 이자 +%.1f분 · 이대로 7일 두면 약 %d분".format(pending, (s.bank * Math.pow(1 + BANK_DAILY_RATE, 7.0)).toInt())
                else -> "오늘 넣은 시간은 내일 자정부터 하루를 꼬박 지나면 이자가 붙어요."
            },
            fontSize = 13.sp, color = p.muted, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 30).forEach { m ->
                OutlinedButton(onClick = { put(m) }, enabled = wallet >= m, shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+${m}분", color = if (wallet >= m) p.ink else p.muted) }
            }
            OutlinedButton(onClick = { put(wallet) }, enabled = wallet >= 1, shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)) { Text("지갑 전부", color = if (wallet >= 1) p.ink else p.muted) }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { askBreak = true }, enabled = s.bank >= 0.05, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.coin, contentColor = Color(0xFF1B1404),
                disabledContainerColor = p.coinSoft, disabledContentColor = p.muted),
        ) { Text("저금통 깨서 꺼내기", fontWeight = FontWeight.Bold) }
    }
    if (askBreak) AlertDialog(
        onDismissRequest = { askBreak = false },
        title = { Text("저금통을 깰까요?") },
        text = { Text("%.1f분이 모두 지갑으로 옮겨져요. 오늘 자정 이자(+%.1f분)는 못 받아요.".format(s.bank, s.pendingInterest())) },
        confirmButton = {
            TextButton(onClick = {
                askBreak = false
                scope.launch(Dispatchers.IO) { Repo.breakBank() }
            }) { Text("깨기", color = p.coin, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { askBreak = false }) { Text("그대로 두기", color = p.muted) } },
    )
}

/** 저금통 그림: 채워진 만큼 금색이 차올라요(120분이면 가득). */
@Composable
fun JarIcon(fill: Float) {
    val p = LocalPal.current
    Canvas(Modifier.size(64.dp)) {
        val w = size.width; val h = size.height
        val body = androidx.compose.ui.geometry.RoundRect(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.96f, CornerRadius(w * 0.22f))
        val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(body) }
        drawPath(path, p.coinSoft)
        clipPath(path) {
            val top = h * 0.96f - (h * 0.78f) * fill
            drawRect(p.coin, topLeft = Offset(0f, top), size = Size(w, h))
        }
        drawPath(path, p.line, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        // 동전 넣는 구멍
        drawRoundRect(p.ink, topLeft = Offset(w * 0.36f, h * 0.06f), size = Size(w * 0.28f, h * 0.07f), cornerRadius = CornerRadius(h * 0.035f))
    }
}

@Composable
fun Status(title: String, sub: String, color: Color) {
    val p = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
    Text(sub, color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 18.dp))
}

@Composable
fun Chip(t: String, filled: Boolean = false) {
    val p = LocalPal.current
    Text(
        t, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (filled) p.ink else p.muted,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .then(if (filled) Modifier.background(p.coinSoft) else Modifier.border(1.dp, p.line, RoundedCornerShape(50)))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun StatsRow(s: Ledger, now: Long) {
    val p = LocalPal.current
    val best = max(s.best, s.currentStreak(now))
    val zone = ZoneId.systemDefault()
    val today = s.watchedOn(Ledger.dayOf(now, zone), now, zone) / MIN
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.line).padding(1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Stat("오늘 본 유튜브", "${today}분", if (today == 0L) p.go else p.ink, Modifier.weight(1f))
        Stat("최장 참기", if (best < HOUR) "${best / MIN}분" else "%.1f시간".format(best.toDouble() / HOUR), p.ink, Modifier.weight(1f))
        val waited = s.earnedByWaiting(now)
        Stat("참아서 번 시간", if (waited < 10) "%.1f분".format(waited) else fmtMin(waited.toInt()), p.coin, Modifier.weight(1f))
    }
}

@Composable
fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    val p = LocalPal.current
    Column(modifier.background(p.surface).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(label, fontSize = 12.sp, color = p.muted, maxLines = 1)
        Text(value, fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
fun AddWidgetButton() {
    val ctx = LocalContext.current
    val p = LocalPal.current
    val mgr = remember { android.appwidget.AppWidgetManager.getInstance(ctx) }
    val cn = remember { android.content.ComponentName(ctx, NotubeWidget::class.java) }
    val hasWidget = remember { mgr.getAppWidgetIds(cn).isNotEmpty() }
    if (hasWidget || !mgr.isRequestPinAppWidgetSupported) return
    OutlinedButton(
        onClick = { mgr.requestPinAppWidget(cn, null, null) },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
    ) { Text("홈 화면에 위젯 추가", color = p.ink) }
}

@Composable
fun History(s: Ledger) {
    val p = LocalPal.current
    data class Row3(val t: Long, val text: String, val amt: String, val plus: Boolean)
    val rows = remember(s) {
        (s.streaks.map { Row3(it.end, "${fmtDur(it.end - it.start)} 참음", "+%.1f분".format(it.earned), true) } +
            s.watches.map { Row3(it.end, "유튜브 ${fmtDur(max(MIN, it.end - it.start))} 봄", "−%.1f분".format((it.end - it.start).toDouble() / MIN), false) } +
            s.bankLog.map {
                when (it.kind) {
                    "in" -> Row3(it.time, "저금통에 넣음", "−%.0f분".format(it.minutes), false)
                    "out" -> Row3(it.time, "저금통 깸", "+%.1f분".format(it.minutes), true)
                    else -> Row3(it.time, "저금통 이자", "+%.1f분".format(it.minutes), true)
                }
            } +
            // 규칙 변경·탈출권·보호 꺼짐 등은 지울 수 없는 기록으로 함께 보여줘요
            s.notes.map { Row3(it.time, it.text, when (it.kind) { "pass" -> "탈출권"; "rule" -> "규칙"; "protect" -> "보호"; "admin" -> "삭제 방지"; "clock" -> "시계"; "activity" -> "활동"; else -> "알림" }, it.kind == "activity") })
            .sortedByDescending { it.t }.take(40)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(14.dp)),
    ) {
        if (rows.isEmpty()) {
            Text("유튜브를 보거나 참은 기록이 끝나면 여기에 남아요.",
                color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = p.line)
            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtDate(r.t), fontFamily = Mono, fontSize = 12.sp, color = p.muted)
                Spacer(Modifier.width(10.dp))
                Text(r.text, fontSize = 14.sp, color = p.ink, modifier = Modifier.weight(1f))
                Text(r.amt, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = if (r.plus) p.go else p.stop, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
fun Rules() {
    val p = LocalPal.current
    Column(
        Modifier.fillMaxWidth().border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("기본 규칙", fontWeight = FontWeight.Bold, color = p.ink, fontSize = 14.sp)
        listOf(
            "저금통에 하루 꼬박 둔 시간" to "매일 자정 +10%",
            "유튜브 1분 보면" to "−1분",
            "안 본 10분마다" to "+1분",
            "3시간 넘게 참으면" to "1.5배",
            "12시간 넘게 참으면" to "2배",
            "24시간 넘게 참으면" to "3배",
        ).forEach { (a, b) ->
            Row { Text(a, color = p.muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(b, color = p.muted, fontSize = 13.sp, fontFamily = Mono) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "볼 수 있는 시간이 0이 되면 유튜브 위에 차단 화면이 뜨고 재생이 멈춰요. 화면을 닫고 소리만 듣는 것도 보는 시간이에요. " +
                "유튜브를 1분 넘게 켜면 참기 기록이 처음부터 다시 시작돼요(1분 미만은 봐줘요). 잠자는 시간도 참은 시간이에요.",
            color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
        )
    }
}
