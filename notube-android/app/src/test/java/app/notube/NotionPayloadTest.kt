package app.notube

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class NotionPayloadTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val YT = "com.google.android.youtube"
    private fun t(day: Int, h: Int, m: Int = 0) =
        LocalDate.of(2026, 11, 16).plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun dailyRowMatchesNotionSchema() {
        val l = Ledger().also { Engine.start(it, t(0, 8)) }
        l.rules += Rule("f", "저녁 자유", Effect.FREE, (1..7).toSet(), 20 * 60, 22 * 60)
        Engine.process(l, listOf(Ev(t(0, 19, 30), YT, EvType.RESUMED, "W"), Ev(t(0, 20, 30), YT, EvType.STOPPED, "W")), t(0, 21), zone)
        Engine.usePass(l, t(0, 21), zone)
        val p = NotionPayload.props(l, LocalDate.of(2026, 11, 16), t(0, 21), zone, withSnapshot = true)
        assertEquals("2026-11-16", p.getJSONObject("날짜").getJSONArray("title").getJSONObject(0).getJSONObject("text").getString("content"))
        assertEquals("2026-11-16", p.getJSONObject("날").getJSONObject("date").getString("start"))
        assertEquals(60, p.getJSONObject("유튜브").getInt("number"))
        assertEquals(30, p.getJSONObject("빠진 시간").getInt("number"))
        assertEquals(30, p.getJSONObject("자유 시청").getInt("number"))
        assertEquals(1, p.getJSONObject("탈출권").getInt("number"))
        assertTrue(p.has("지갑") && p.has("저금통"))
        // 지난 날 줄에는 '지금' 값(지갑·저금통)을 넣지 않아요
        assertFalse(NotionPayload.props(l, LocalDate.of(2026, 11, 15), t(0, 21), zone, withSnapshot = false).has("지갑"))
    }

    @Test fun queryFindsRowByTitle() {
        val q = NotionPayload.dayQuery(LocalDate.of(2026, 11, 16))
        assertEquals("날짜", q.getJSONObject("filter").getString("property"))
        assertEquals("2026-11-16", q.getJSONObject("filter").getJSONObject("title").getString("equals"))
    }
}
