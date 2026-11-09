package app.notube

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** 효과별 색. */
@Composable
fun effectColor(e: String): Color {
    val p = LocalPal.current
    return when (e) {
        Effect.FREE -> p.go
        Effect.NO_EARN -> p.muted
        Effect.BLOCK -> p.stop
        else -> p.coin
    }
}

/** 메인 화면의 '나만의 규칙' 카드. */
@Composable
fun MyRules(s: Ledger, now: Long) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    /** 규칙 변경 요청을 보내고, 하루 뒤 적용이면 알려줘요. */
    fun request(block: () -> Long?) = scope.launch {
        val at = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
        android.widget.Toast.makeText(ctx,
            if (at != null) "느슨하게 바꾸는 변경이라 ${fmtDate(at)}부터 적용돼요" else "바로 적용했어요",
            android.widget.Toast.LENGTH_LONG).show()
    }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var creating by remember { mutableStateOf(false) }
    val activeIds = remember(s, now / 30_000) { s.activeRules(now).map { it.id }.toSet() }

    Card {
        Text("나만의 규칙", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.ink)
        Text("요일과 시간대별로 계산 방식을 바꿔요. 규칙을 느슨하게 바꾸는 건(자유 시간 늘리기, 금지 규칙 끄기 등) 24시간 뒤에 적용되고, 엄격하게 바꾸는 건 바로 적용돼요. 모든 변경은 기록에 남아요.",
            fontSize = 13.sp, color = p.muted, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))

        if (s.currentRules().isEmpty()) {
            Text("아직 규칙이 없어요. 아래 추천 규칙을 눌러 바로 추가하거나 직접 만들어 보세요.",
                fontSize = 14.sp, color = p.muted, lineHeight = 20.sp)
        }
        s.currentRules().forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = p.line, modifier = Modifier.padding(vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (r.enabled) effectColor(r.effect) else p.line))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (r.enabled) p.ink else p.muted)
                        if (r.id in activeIds) {
                            Spacer(Modifier.width(6.dp))
                            Text("지금 적용 중", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = effectColor(r.effect),
                                modifier = Modifier.border(1.dp, effectColor(r.effect), RoundedCornerShape(50))
                                    .padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                    Text("${r.daysText()} · ${r.timeText()} · ${Effect.label(r.effect)}", fontSize = 13.sp, color = p.muted)
                    s.pendingRules.firstOrNull { it.ruleId == r.id }?.let { pr -> PendingLine(pr, scope) }
                }
                Switch(
                    checked = r.enabled,
                    onCheckedChange = { on -> request { Repo.putRule(r.copy(enabled = on)) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = effectColor(r.effect)),
                )
            }
        }

        // 아직 적용 전인 새 규칙
        s.pendingRules.filter { pr -> pr.rule != null && s.currentRules().none { it.id == pr.ruleId } }.forEach { pr ->
            val r = pr.rule!!
            HorizontalDivider(color = p.line, modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(p.line))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = p.muted)
                    Text("${r.daysText()} · ${r.timeText()} · ${Effect.label(r.effect)}", fontSize = 13.sp, color = p.muted)
                    PendingLine(pr, scope)
                }
            }
        }

        val missing = Rule.PRESETS.filter { pr -> s.currentRules().none { it.id == pr.id } && s.pendingRules.none { it.ruleId == pr.id } }
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("추천 규칙", fontSize = 12.sp, color = p.muted)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                missing.forEach { pr ->
                    OutlinedButton(
                        onClick = { request { Repo.putRule(pr) } },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("+ ${pr.name}", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${pr.daysText()} · ${pr.timeText()} · ${Effect.label(pr.effect)}", color = p.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { creating = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.bg),
        ) { Text("새 규칙 만들기", fontWeight = FontWeight.Bold) }
    }

    if (creating) RuleDialog(null, onDismiss = { creating = false }, onSave = { r ->
        creating = false
        request { Repo.putRule(r) }
    }, onDelete = null)
    editing?.let { r ->
        RuleDialog(r, onDismiss = { editing = null }, onSave = { nr ->
            editing = null
            request { Repo.putRule(nr) }
        }, onDelete = {
            editing = null
            request { Repo.removeRule(r.id) }
        })
    }
}

/** 대기 중인 변경 한 줄: 언제 적용되는지와 취소 버튼. */
@Composable
private fun PendingLine(pr: PendingRule, scope: kotlinx.coroutines.CoroutineScope) {
    val p = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (pr.rule == null) "삭제" else "변경") + " 대기 · ${fmtDate(pr.at)} 적용",
            fontSize = 12.sp, color = p.coin, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = { scope.launch(Dispatchers.IO) { Repo.cancelPendingRule(pr.ruleId) } },
            contentPadding = PaddingValues(horizontal = 6.dp)) { Text("취소", fontSize = 12.sp, color = p.muted) }
    }
}

/** 규칙 만들기·고치기 창. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleDialog(initial: Rule?, onDismiss: () -> Unit, onSave: (Rule) -> Unit, onDelete: (() -> Unit)?) {
    val p = LocalPal.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var effect by remember { mutableStateOf(initial?.effect ?: Effect.FREE) }
    var days by remember { mutableStateOf(initial?.days ?: (1..7).toSet()) }
    var allDay by remember { mutableStateOf(initial?.allDay ?: false) }
    var start by remember { mutableIntStateOf(if (initial?.allDay == false) initial.startMin else 20 * 60) }
    var end by remember { mutableIntStateOf(if (initial?.allDay == false) initial.endMin else 22 * 60) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end"
    val valid = name.isNotBlank() && days.isNotEmpty() && (allDay || start != end)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "새 규칙" else "규칙 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(name, { name = it.take(20) }, label = { Text("이름 (예: 저녁 휴식)") }, singleLine = true)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("효과", fontSize = 13.sp, color = p.muted)
                    Effect.ALL.forEach { e ->
                        val sel = e == effect
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .border(if (sel) 2.dp else 1.dp, if (sel) effectColor(e) else p.line, RoundedCornerShape(12.dp))
                                .clickable { effect = e }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(effectColor(e)))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(Effect.label(e), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = p.ink)
                                Text(Effect.desc(e), fontSize = 12.sp, color = p.muted)
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("요일", fontSize = 13.sp, color = p.muted, modifier = Modifier.weight(1f))
                        listOf("매일" to (1..7).toSet(), "주중" to (1..5).toSet(), "주말" to setOf(6, 7)).forEach { (label, set) ->
                            TextButton(onClick = { days = set }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(label, fontSize = 13.sp, color = if (days == set) p.ink else p.muted,
                                    fontWeight = if (days == set) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..7).forEach { d ->
                            val on = d in days
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                                    .background(if (on) p.ink else Color.Transparent)
                                    .border(1.dp, if (on) p.ink else p.line, CircleShape)
                                    .clickable { days = if (on) days - d else days + d },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                                    color = if (on) p.bg else p.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("시간", fontSize = 13.sp, color = p.muted, modifier = Modifier.weight(1f))
                        Text("하루 종일", fontSize = 13.sp, color = p.ink)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = allDay, onCheckedChange = { allDay = it })
                    }
                    if (!allDay) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { picking = "start" }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                                Text(Rule.hhmm(start), color = p.ink, fontSize = 16.sp)
                            }
                            Text("부터", color = p.muted, fontSize = 13.sp)
                            OutlinedButton(onClick = { picking = "end" }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                                Text(Rule.hhmm(end), color = p.ink, fontSize = 16.sp)
                            }
                            Text("까지", color = p.muted, fontSize = 13.sp)
                        }
                        if (end < start) Text("다음 날 ${Rule.hhmm(end)}까지 이어져요", fontSize = 12.sp, color = p.muted)
                        if (end == start) Text("시작과 끝이 같아요. 하루 종일이면 위 스위치를 켜 주세요.", fontSize = 12.sp, color = p.stop)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(Rule(
                    id = initial?.id ?: ("r" + System.currentTimeMillis().toString(36)),
                    name = name.trim(), effect = effect, days = days,
                    startMin = if (allDay) 0 else start, endMin = if (allDay) 0 else end,
                    enabled = initial?.enabled ?: true,
                ))
            }) { Text("저장", color = if (valid) p.ink else p.muted, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("삭제", color = p.stop) }
                TextButton(onClick = onDismiss) { Text("취소", color = p.muted) }
            }
        },
    )

    picking?.let { which ->
        val initialMin = if (which == "start") start else end
        val state = rememberTimePickerState(initialHour = initialMin / 60, initialMinute = initialMin % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (which == "start") "시작 시간" else "끝 시간") },
            text = { TimeInput(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    if (which == "start") start = m else end = m
                    picking = null
                }) { Text("확인", color = p.ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("취소", color = p.muted) } },
        )
    }
}
