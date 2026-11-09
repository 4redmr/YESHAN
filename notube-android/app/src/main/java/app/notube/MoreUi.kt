package app.notube

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val Mono = FontFamily.Monospace

// ---------------- 비상 탈출권 ----------------

/** 지갑 카드 안의 비상 탈출권 줄. 30초를 기다려야 쓸 수 있어요. */
@Composable
fun PassRow(s: Ledger, now: Long) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val until = s.passActiveUntil(now)
    val reason = remember(s, now / 10_000) { Engine.passBlockedReason(s, now) }
    val left = remember(s, now / 60_000) { Engine.passesLeftThisWeek(s, now) }

    if (until != null) {
        Text("비상 탈출권 사용 중 · ${fmtDate(until).substringAfter(' ')}까지 자유롭게 볼 수 있어요",
            fontSize = 13.sp, color = p.coin, fontWeight = FontWeight.Bold)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("비상 탈출권", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.ink)
            Text(reason ?: "10분 바로 열기 · 빚 ${PASS_DEBT_MIN.toInt()}분 · 이번 주 ${left}번 남음",
                fontSize = 12.sp, color = p.muted)
        }
        OutlinedButton(onClick = { open = true }, enabled = reason == null, shape = RoundedCornerShape(12.dp)) {
            Text("쓰기", color = if (reason == null) p.ink else p.muted)
        }
    }

    if (open) {
        var wait by remember { mutableIntStateOf(30) }
        LaunchedEffect(Unit) { while (wait > 0) { delay(1000); wait-- } }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("정말 지금 필요해요?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• 10분 동안 유튜브를 자유롭게 볼 수 있어요.")
                    Text("• 대신 지갑에서 ${PASS_DEBT_MIN.toInt()}분이 빚으로 바로 빠져요.")
                    Text("• 하루 1번, 일주일에 ${PASS_PER_WEEK}번까지예요. 쓴 기록은 지워지지 않아요.")
                    Spacer(Modifier.height(4.dp))
                    Text(if (wait > 0) "${wait}초 동안 한 번 더 생각해 보세요" else "그래도 필요하면 써도 돼요",
                        color = if (wait > 0) p.muted else p.ink, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(enabled = wait == 0, onClick = {
                    open = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Repo.usePass() }
                        Toast.makeText(ctx, if (ok) "10분 열렸어요" else "지금은 쓸 수 없어요", Toast.LENGTH_SHORT).show()
                    }
                }) { Text(if (wait > 0) "기다리는 중" else "탈출권 쓰기", color = if (wait == 0) p.stop else p.muted) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("참을래요", color = p.go, fontWeight = FontWeight.Bold) } },
        )
    }
}

// ---------------- 대체 활동 (습관 타이머 연동) ----------------

@Composable
fun ActivityCard(s: Ledger, now: Long) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val todayBonus = s.activityBonusByDay[Ledger.dayOf(now, zone).toString()] ?: 0.0
    Card {
        Text("유튜브 대신 하기", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.ink)
        Text("기도·운동·말씀읽기처럼 하고 싶던 걸 하는 동안엔 10분마다 +1분을 더 받아요. 하루 ${ACTIVITY_BONUS_DAILY_CAP.toInt()}분까지, 중간에 유튜브를 보면 보너스는 없어요.",
            fontSize = 13.sp, color = p.muted, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))
        val a = s.activity
        if (a != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${a.name} 하는 중", color = p.go, fontWeight = FontWeight.Bold)
                    Text(fmtClock(max(0, now - a.start)), fontFamily = Mono, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = p.ink)
                }
                Button(
                    onClick = {
                        scope.launch {
                            val b = withContext(Dispatchers.IO) { Repo.stopActivity() }
                            Toast.makeText(ctx, if (b > 0) "잘했어요! 보너스 +%.1f분".format(b) else "기록했어요", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.bg),
                ) { Text("끝내기", fontWeight = FontWeight.Bold) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ACTIVITIES.take(3).forEach { name -> ActivityChip(name, Modifier.weight(1f)) { scope.launch(Dispatchers.IO) { Repo.startActivity(name) } } }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ACTIVITIES.drop(3).forEach { name -> ActivityChip(name, Modifier.weight(1f)) { scope.launch(Dispatchers.IO) { Repo.startActivity(name) } } }
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("오늘 받은 활동 보너스 %.1f / %d분".format(todayBonus, ACTIVITY_BONUS_DAILY_CAP.toInt()), fontSize = 12.sp, color = p.muted)
    }
}

@Composable
private fun ActivityChip(name: String, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPal.current
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(name, color = p.ink, fontSize = 14.sp, maxLines = 1)
    }
}

// ---------------- 지키기: 보호 상태, 삭제 방지, 백업 ----------------

@Composable
fun GuardCard(s: Ledger, perms: Perms) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var adminOn by remember { mutableStateOf(GuardAdmin.isActive(ctx)) }
    var importText by remember { mutableStateOf<String?>(null) }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        adminOn = GuardAdmin.isActive(ctx)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Repo.exportJson().toByteArray()) }; true } catch (_: Exception) { false }
            }
            Toast.makeText(ctx, if (ok) "백업 파일을 저장했어요" else "저장하지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importText = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } } catch (_: Exception) { null }
            }
            if (importText == null) Toast.makeText(ctx, "파일을 읽지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) { adminOn = GuardAdmin.isActive(ctx) }

    Card {
        Text("지키기", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.ink)
        Spacer(Modifier.height(10.dp))

        val off = s.protectionOffSince
        GuardLine(
            ok = perms.ready,
            title = if (perms.ready) "보호 켜짐" else "보호 꺼짐",
            desc = if (perms.ready) "권한을 꺼도, 다시 켜는 순간 그동안 본 유튜브가 사용 기록으로 한꺼번에 계산돼요." +
                (if (s.protectionOffTotalMs > 0) " 지금까지 꺼져 있던 시간: ${fmtDur(s.protectionOffTotalMs)}" else "")
            else "필수 권한이 꺼져서 지금은 막지 못해요. 다시 켜면 꺼져 있던 동안(${off?.let { fmtDur(System.currentTimeMillis() - it) } ?: "-"}) 본 유튜브도 계산돼요.",
        )
        Spacer(Modifier.height(12.dp))
        GuardLine(
            ok = adminOn,
            title = if (adminOn) "삭제 방지 켜짐" else "삭제 방지 꺼짐",
            desc = if (adminOn) "앱을 지우려면 먼저 설정에서 삭제 방지를 꺼야 해요. 끈 기록은 남아요."
            else "켜 두면 충동적으로 앱을 지우고 다시 까는 걸 막아줘요.",
        )
        if (!adminOn) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { adminLauncher.launch(GuardAdmin.enableIntent(ctx)) }, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()) { Text("삭제 방지 켜기", color = p.ink) }
        }
        Spacer(Modifier.height(12.dp))
        GuardLine(
            ok = true,
            title = "백업",
            desc = "구글 백업이 켜져 있으면 앱을 다시 깔아도 기록이 돌아와요. 휴대폰을 바꿀 때는 파일로 저장해 옮기세요. " +
                "불러오기는 다시 깐 뒤 7일 안의 백업만 되고, 백업 이후 본 유튜브는 다시 계산돼요.",
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("notube-backup-${LocalDate.now()}.json") }, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)) { Text("파일로 저장", color = p.ink) }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)) { Text("불러오기", color = p.ink) }
        }
    }

    importText?.let { text ->
        AlertDialog(
            onDismissRequest = { importText = null },
            title = { Text("백업을 불러올까요?") },
            text = { Text("백업 파일의 기록으로 복원하고, 백업 이후 본 유튜브와 쓴 탈출권은 다시 계산해요. 백업 속 규칙은 변경 요청으로 들어가요(느슨한 건 24시간 뒤).") },
            confirmButton = {
                TextButton(onClick = {
                    importText = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Repo.importJson(text).also { Repo.sync() } }
                        Toast.makeText(ctx, when (r) {
                            Repo.ImportResult.OK -> "불러왔어요"
                            Repo.ImportResult.MODIFIED -> "고친 흔적이 있는 백업 파일은 불러올 수 없어요"
                            Repo.ImportResult.BAD_FILE -> "노튜브 백업 파일이 아니에요"
                            Repo.ImportResult.TOO_OLD -> "7일이 지난 백업은 불러올 수 없어요 (그 사이 기록을 확인할 수 없어서요)"
                            Repo.ImportResult.NOT_RESTORE -> "백업은 앱을 다시 깔았거나 휴대폰을 바꿨을 때 복원하는 용도예요. 지금 기록보다 나중에 만든 백업만 불러올 수 없어요"
                        }, Toast.LENGTH_LONG).show()
                    }
                }) { Text("불러오기", color = p.stop, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { importText = null }) { Text("취소", color = p.muted) } },
        )
    }
}

@Composable
private fun GuardLine(ok: Boolean, title: String, desc: String) {
    val p = LocalPal.current
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(if (ok) p.go else p.stop))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (ok) p.ink else p.stop)
            Text(desc, fontSize = 13.sp, color = p.muted, lineHeight = 19.sp)
        }
    }
}

// ---------------- 통계: 일·주·월 ----------------

private data class Bucket(val label: String, val days: List<LocalDate>, val current: Boolean)

@Composable
fun StatsCard(s: Ledger, now: Long) {
    val p = LocalPal.current
    val zone = ZoneId.systemDefault()
    var mode by remember { mutableStateOf("day") }
    val today = Ledger.dayOf(now, zone)
    val buckets = remember(mode, today) { bucketsFor(mode, today) }
    // 1분마다만 다시 계산 (매초 다시 그릴 필요 없음)
    val tick = now / 60_000
    val watched = remember(s, tick, mode) { buckets.map { b -> b.days.sumOf { s.watchedOn(it, now, zone) } / MIN } }
    val charged = remember(s, tick, mode) { buckets.map { b -> b.days.sumOf { s.chargedOn(it, now, zone) } / MIN } }
    val earned = remember(s, tick, mode) { buckets.map { b -> b.days.sumOf { s.earnedOn(it, now, zone) } } }
    val cur = buckets.lastIndex
    val prev = cur - 1
    val unit = when (mode) { "day" -> "오늘"; "week" -> "이번 주"; else -> "이번 달" }
    val prevUnit = when (mode) { "day" -> "어제"; "week" -> "지난주"; else -> "지난달" }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("유튜브 통계", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.ink, modifier = Modifier.weight(1f))
            listOf("day" to "일", "week" to "주", "month" to "월").forEach { (k, label) ->
                val sel = mode == k
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (sel) p.bg else p.muted,
                    modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50))
                        .background(if (sel) p.ink else p.surface).border(1.dp, if (sel) p.ink else p.line, RoundedCornerShape(50))
                        .clickable { mode = k }.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$unit 유튜브 ", fontSize = 14.sp, color = p.muted)
            Text(fmtMin(watched[cur].toInt()), fontFamily = Mono, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                color = if (watched[cur] == 0L) p.go else p.ink)
        }
        if (prev >= 0) {
            val a = watched[cur]; val b = watched[prev]
            val msg = when {
                b == 0L && a == 0L -> "$prevUnit 도 0분이었어요. 대단해요!"
                b == 0L -> "$prevUnit 은 0분이었어요"
                else -> {
                    val pct = ((a - b).toDouble() / b * 100).roundToInt()
                    if (pct <= 0) "${prevUnit}보다 ${-pct}% 줄었어요" else "${prevUnit}보다 ${pct}% 늘었어요"
                }
            }
            Text(msg, fontSize = 13.sp, color = if (a <= b) p.go else p.stop)
        }
        Spacer(Modifier.height(14.dp))
        BarChart(buckets, watched)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.line).padding(1.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            val days = buckets[cur].days.count { !it.isAfter(today) }.coerceAtLeast(1)
            MiniStat("하루 평균", fmtMin((watched[cur] / days).toInt()), Modifier.weight(1f))
            MiniStat("참아서 번 시간", fmtMin(earned[cur].toInt()), Modifier.weight(1f))
            MiniStat("자유 시간 시청", fmtMin(max(0, watched[cur] - charged[cur]).toInt()), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text("자유 시간 시청: 자유 시간 규칙이나 비상 탈출권으로 본 시간이에요. 지갑에서 안 빠졌지만 기록에는 남아요.",
            fontSize = 11.sp, color = p.muted, lineHeight = 16.sp)
    }
}

private fun bucketsFor(mode: String, today: LocalDate): List<Bucket> = when (mode) {
    "day" -> (13 downTo 0).map { i ->
        val d = today.minusDays(i.toLong())
        Bucket(if (i == 0) "오늘" else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN), listOf(d), i == 0)
    }
    "week" -> {
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        (11 downTo 0).map { i ->
            val start = thisMonday.minusWeeks(i.toLong())
            Bucket(if (i == 0) "이번주" else "${start.monthValue}/${start.dayOfMonth}", (0..6).map { start.plusDays(it.toLong()) }.filter { !it.isAfter(today) }, i == 0)
        }
    }
    else -> (11 downTo 0).map { i ->
        val first = today.withDayOfMonth(1).minusMonths(i.toLong())
        Bucket("${first.monthValue}월", (0 until first.lengthOfMonth()).map { first.plusDays(it.toLong()) }.filter { !it.isAfter(today) }, i == 0)
    }
}

@Composable
private fun BarChart(buckets: List<Bucket>, values: List<Long>) {
    val p = LocalPal.current
    val maxV = max(30L, values.maxOrNull() ?: 0L)
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        buckets.forEachIndexed { i, b ->
            val v = values[i]
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (v > 0 && (buckets.size <= 12 || i % 2 == 1 || b.current)) compact(v) else "", fontSize = 9.sp, fontFamily = Mono, color = p.muted, maxLines = 1)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Canvas(Modifier.fillMaxSize()) {
                        val h = if (v == 0L) 3.dp.toPx() else max(3.dp.toPx(), size.height * (v.toFloat() / maxV))
                        drawRoundRect(
                            if (v == 0L) p.go else if (b.current) p.stop else p.stop.copy(alpha = 0.45f),
                            topLeft = Offset(size.width * 0.12f, size.height - h),
                            size = Size(size.width * 0.76f, h), cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(b.label, fontSize = 9.sp, color = if (b.current) p.ink else p.muted, maxLines = 1,
                    fontWeight = if (b.current) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private fun compact(min: Long) = if (min < 60) "$min" else "%.1fh".format(min / 60.0)

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    val p = LocalPal.current
    Column(modifier.background(p.surface).padding(horizontal = 10.dp, vertical = 10.dp)) {
        Text(label, fontSize = 11.sp, color = p.muted, maxLines = 1)
        Text(value, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1)
    }
}

// ---------------- 노션 연동 ----------------

@Composable
fun NotionCard(s: Ledger) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(NotionSync.token(ctx) != null) }
    var last by remember { mutableLongStateOf(NotionSync.lastSync(ctx)) }
    var err by remember { mutableStateOf(NotionSync.lastError(ctx)) }
    var setup by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    fun send() = scope.launch {
        sending = true
        val e = withContext(Dispatchers.IO) { NotionSync.sync(ctx, Repo.state.value) }
        sending = false
        last = NotionSync.lastSync(ctx); err = e
        Toast.makeText(ctx, e ?: "노션에 보냈어요", Toast.LENGTH_SHORT).show()
    }

    Card {
        Text("노션 연동", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.ink)
        Text("하루 기록을 노션 '📵 노튜브 기록'에 자동으로 올려요(30분마다). 하루 퀘스트가 이 기록을 읽어서 '유튜브 0분인 날' 같은 퀘스트를 자동으로 완료해요.",
            fontSize = 13.sp, color = p.muted, lineHeight = 19.sp)
        Spacer(Modifier.height(10.dp))
        GuardLine(
            ok = connected && err == null,
            title = when { !connected -> "연결 안 됨"; err != null -> "보내기 실패"; else -> "연결됨" },
            desc = when {
                !connected -> "노션 연결 키를 한 번만 넣으면 돼요."
                err != null -> err!!
                last > 0 -> "마지막으로 보낸 시각 ${fmtDate(last)}"
                else -> "아직 보낸 적이 없어요."
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { setup = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Text(if (connected) "연결 설정" else "노션 연결하기", color = p.ink)
            }
            if (connected) OutlinedButton(onClick = { send() }, enabled = !sending, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Text(if (sending) "보내는 중…" else "지금 보내기", color = p.ink)
            }
        }
    }

    if (setup) {
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { setup = false },
            title = { Text("노션 연결하기") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("1. 컴퓨터나 휴대폰 브라우저에서 notion.so/profile/integrations 를 열고 '새 API 통합'을 만들어요 (이름: 노튜브, 유형: 내부).", fontSize = 14.sp)
                    Text("2. 만든 통합의 '내부 통합 시크릿'을 복사해요 (ntn_ 으로 시작해요).", fontSize = 14.sp)
                    Text("3. 노션에서 '회장님과의 동행 → 📵 노튜브 기록'을 열고, 오른쪽 위 ⋯ → 연결 → '노튜브'를 추가해요.", fontSize = 14.sp)
                    Text("4. 복사한 시크릿을 아래에 붙여넣어요.", fontSize = 14.sp)
                    OutlinedTextField(token, { token = it.trim() }, label = { Text("내부 통합 시크릿") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Text("이 키는 이 휴대폰 안에만 저장되고, 백업 파일과 구글 백업에도 들어가지 않아요.", fontSize = 12.sp, color = p.muted)
                }
            },
            confirmButton = {
                TextButton(enabled = token.length > 10, onClick = {
                    NotionSync.setToken(ctx, token)
                    connected = true; setup = false
                    send()
                }) { Text("연결하고 보내기", color = p.ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    if (connected) TextButton(onClick = {
                        NotionSync.setToken(ctx, null); connected = false; err = null; setup = false
                    }) { Text("연결 끊기", color = p.stop) }
                    TextButton(onClick = { setup = false }) { Text("취소", color = p.muted) }
                }
            },
        )
    }
}
