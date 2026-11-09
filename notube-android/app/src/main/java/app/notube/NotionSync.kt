package app.notube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 노션 '📵 노튜브 기록' 데이터베이스에 하루 한 줄씩 올려요 (같은 날은 갱신).
 * 하루 퀘스트가 이 기록을 읽어서 퀘스트를 자동으로 완료해요.
 */
object NotionPayload {
    /** 하루치 속성. [withSnapshot]이면 지갑·저금통처럼 '지금' 값도 넣어요 (오늘 줄에만). */
    fun props(l: Ledger, day: LocalDate, now: Long, zone: ZoneId, withSnapshot: Boolean): JSONObject {
        val watched = l.watchedOn(day, now, zone) / MIN
        val charged = l.chargedOn(day, now, zone) / MIN
        val bonus = l.activityBonusByDay[day.toString()] ?: 0.0
        val earned = l.earnedOn(day, now, zone) - bonus
        val passes = l.passes.count { Ledger.dayOf(it, zone) == day }
        fun num(v: Number) = JSONObject().put("number", v)
        fun one(v: Double) = (v * 10).roundToInt() / 10.0
        val p = JSONObject()
            .put("날짜", JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", day.toString())))))
            .put("날", JSONObject().put("date", JSONObject().put("start", day.toString())))
            .put("유튜브", num(watched))
            .put("빠진 시간", num(charged))
            .put("자유 시청", num(max(0, watched - charged)))
            .put("번 시간", num(one(earned)))
            .put("활동 보너스", num(one(bonus)))
            .put("탈출권", num(passes))
            .put("최장 참기", num(max(l.best, l.currentStreak(now)) / MIN))
        if (withSnapshot) {
            p.put("지갑", num(one(l.remaining(now, zone))))
            p.put("저금통", num(one(l.bank)))
        }
        return p
    }

    /** 그날 줄을 찾는 조회 조건. */
    fun dayQuery(day: LocalDate): JSONObject = JSONObject().put("filter",
        JSONObject().put("property", "날짜").put("title", JSONObject().put("equals", day.toString()))).put("page_size", 1)
}

object NotionSync {
    /** 미리 만들어 둔 '📵 노튜브 기록' 데이터베이스. */
    const val DEFAULT_DB = "616845a84d2a46de8eb977dd424a1377"
    private const val PREFS = "notion" // 백업에서 빼요 (연결 키가 들어 있어서)
    private const val API = "https://api.notion.com/v1"
    private const val VERSION = "2022-06-28"
    private const val MIN_INTERVAL_MS = 30 * MIN

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context): String? = prefs(ctx).getString("token", null)?.takeIf { it.isNotBlank() }
    fun lastSync(ctx: Context): Long = prefs(ctx).getLong("last", 0)
    fun lastError(ctx: Context): String? = prefs(ctx).getString("err", null)

    fun setToken(ctx: Context, token: String?) {
        prefs(ctx).edit().apply {
            if (token.isNullOrBlank()) remove("token") else putString("token", token.trim())
            remove("err"); remove("pages")
        }.apply()
    }

    /** 15분마다 도는 작업에서 불러요. 30분에 한 번만 보내요. */
    fun syncIfDue(ctx: Context, l: Ledger) {
        if (token(ctx) == null || !l.started) return
        if (System.currentTimeMillis() - lastSync(ctx) < MIN_INTERVAL_MS) return
        sync(ctx, l)
    }

    /** 어제·오늘 줄을 올려요. 실패하면 이유를 돌려줘요 (성공이면 null). 네트워크를 쓰니 백그라운드에서 부르세요. */
    @Synchronized // 수동 '지금 보내기'와 백그라운드 작업이 겹쳐 같은 날 줄을 두 번 만들지 않게
    fun sync(ctx: Context, l: Ledger): String? {
        val token = token(ctx) ?: return "노션 연결 키가 없어요"
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = Ledger.dayOf(now, zone)
        val err = try {
            for (day in listOf(today.minusDays(1), today)) {
                if (day.isBefore(Ledger.dayOf(l.trackingSince, zone))) continue
                upsert(ctx, token, day, NotionPayload.props(l, day, now, zone, withSnapshot = day == today))
            }
            null
        } catch (e: NotionError) {
            e.message
        } catch (e: Exception) {
            "인터넷 연결을 확인해 주세요 (${e.javaClass.simpleName})"
        }
        prefs(ctx).edit().apply {
            if (err == null) { putLong("last", now); remove("err") } else putString("err", err)
        }.apply()
        return err
    }

    private class NotionError(val status: Int, val body: String, msg: String) : Exception(msg)

    /** 노션에서 그 줄이 지워졌거나(휴지통) 없어진 경우에만 true. 바쁨·서버 오류 때 새로 만들면 줄이 중복돼요. */
    private fun rowGone(e: NotionError) = e.status == 404 || (e.status == 400 && e.body.contains("archived"))

    private fun upsert(ctx: Context, token: String, day: LocalDate, props: JSONObject) {
        val cache = JSONObject(prefs(ctx).getString("pages", "{}") ?: "{}")
        var pageId = cache.optString(day.toString()).takeIf { it.isNotBlank() }
        if (pageId == null) {
            val res = call(token, "POST", "/databases/$DEFAULT_DB/query", NotionPayload.dayQuery(day))
            pageId = res.optJSONArray("results")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
        }
        if (pageId != null) {
            try {
                call(token, "PATCH", "/pages/$pageId", JSONObject().put("properties", props))
            } catch (e: NotionError) {
                if (!rowGone(e)) throw e
                // 노션에서 그 줄을 지웠으면 새로 만들어요
                cache.remove(day.toString()); pageId = null
            }
        }
        if (pageId == null) {
            val res = call(token, "POST", "/pages", JSONObject()
                .put("parent", JSONObject().put("database_id", DEFAULT_DB))
                .put("icon", JSONObject().put("type", "emoji").put("emoji", "📵"))
                .put("properties", props))
            pageId = res.getString("id")
        }
        cache.put(day.toString(), pageId)
        // 최근 10일치만 기억
        val keys = cache.keys().asSequence().toList().sortedDescending()
        keys.drop(10).forEach { cache.remove(it) }
        prefs(ctx).edit().putString("pages", cache.toString()).apply()
    }

    private fun call(token: String, method: String, path: String, body: JSONObject): JSONObject {
        val c = URL(API + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method // 안드로이드의 HttpURLConnection은 PATCH를 지원해요
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Notion-Version", VERSION)
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: "{}"
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optString("message") } catch (_: Exception) { "" }
                throw NotionError(code, text, when (code) {
                    401 -> "연결 키가 맞지 않아요. 다시 붙여넣어 주세요"
                    403, 404 -> "노션에서 '📵 노튜브 기록'에 연결(통합)을 추가해 주세요"
                    429 -> "노션이 잠시 바빠요. 조금 뒤에 다시 보낼게요"
                    else -> "노션 오류 $code ${msg.take(80)}"
                })
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
