package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.dispatch.DispatchClient
import tw.hoonsor.voicehub.dispatch.DispatchOutcome
import tw.hoonsor.voicehub.dispatch.DispatchQueue
import tw.hoonsor.voicehub.dispatch.DispatchTrigger

class DispatchTriggerTest {
    @Test fun specExamples() {
        // voice-dispatch-api.md 的範例表
        assertEquals("幫鬧鐘 APP 加一個貪睡次數上限", DispatchTrigger.extract("派工，幫鬧鐘 APP 加一個貪睡次數上限"))
        assertEquals("做一個記帳工具可以拍收據", DispatchTrigger.extract("我有個點子，做一個記帳工具可以拍收據"))
        assertEquals("27 號專案跑一下收工", DispatchTrigger.extract("派工，27 號專案跑一下收工"))
    }

    @Test fun otherTriggers() {
        assertEquals("吸收新知網站加深色模式", DispatchTrigger.extract("幫我派工：吸收新知網站加深色模式"))
        assertEquals("照片 APP 要能批次改名", DispatchTrigger.extract("記一個開發需求 照片 APP 要能批次改名"))
        assertEquals("把鬧鐘的 bug 修一修", DispatchTrigger.extract("叫利姆路把鬧鐘的 bug 修一修"))
        assertEquals("加一個功能", DispatchTrigger.extract("請派工，加一個功能"))
    }

    @Test fun keepsOriginalWording() {
        // 不刪觸發詞以外的內容（包含標點、語助詞）
        assertEquals("嗯…那個，鬧鐘要可以設農曆，不急喔！", DispatchTrigger.extract("派工 嗯…那個，鬧鐘要可以設農曆，不急喔！"))
    }

    @Test fun triggerOnly() {
        assertEquals("", DispatchTrigger.extract("派工"))
        assertEquals("", DispatchTrigger.extract("幫我派工。"))
    }

    @Test fun notDispatch() {
        assertNull(DispatchTrigger.extract("打開 LINE"))
        assertNull(DispatchTrigger.extract("明天七點叫我"))
    }

    @Test fun statusQuery() {
        assertEquals(12, DispatchTrigger.statusQuery("第 12 號派工單進度如何"))
        assertEquals(12, DispatchTrigger.statusQuery("12號單做好了沒"))
        assertEquals(15, DispatchTrigger.statusQuery("十五號單的狀態"))
        assertNull(DispatchTrigger.statusQuery("派工，27 號專案跑一下收工"))
    }
}

class DispatchResponseTest {
    @Test fun created() {
        val body = """{"ticket":{"id":12,"project":"27-吸收新知網站","status":"new"},"guess":"27-吸收新知網站","message":"已送出第 12 號派工單，判斷是「吸收新知網站」，Claude 會在 15 分鐘內處理。"}"""
        val r = DispatchClient.parseSend(201, body) as DispatchOutcome.Sent
        assertEquals(12, r.ticketId)
        assertEquals("27-吸收新知網站", r.guess)
        // 直接唸伺服器的 message，不自己組句子
        assertEquals("已送出第 12 號派工單，判斷是「吸收新知網站」，Claude 會在 15 分鐘內處理。", r.speech())
    }

    @Test fun nullGuess() {
        val r = DispatchClient.parseSend(201, """{"ticket":{"id":3},"guess":null,"message":"ok"}""") as DispatchOutcome.Sent
        assertNull(r.guess)
    }

    @Test fun badRequest() {
        val r = DispatchClient.parseSend(400, """{"error":"text 不可為空白"}""")
        assertEquals("內容無法送出：text 不可為空白", r.speech())
    }

    @Test fun serverErrorIsQueued() {
        assertTrue(DispatchClient.parseSend(502, "Bad Gateway") is DispatchOutcome.Offline)
    }

    @Test fun offlineSpeech() {
        assertTrue(DispatchOutcome.Offline("x").speech().startsWith("派工網站連不上，請確認 Tailscale 有開。"))
    }

    @Test fun queueRoundTrip() {
        val items = listOf(DispatchQueue.Item("需求一", 1L), DispatchQueue.Item("需求二\n第二行", 2L))
        assertEquals(items, DispatchQueue.decode(DispatchQueue.encode(items)))
        assertTrue(DispatchQueue.decode("壞掉的").isEmpty())
    }
}
