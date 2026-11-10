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

    @Test fun aiNeedsDispatchWord() {
        // 2026-11-10 誤判：待辦事項被 AI 送成派工單
        assertTrue(!DispatchTrigger.aiMayDispatch("待辦事項新增APP自動備份"))
        assertTrue(!DispatchTrigger.aiMayDispatch("幫我做一個記帳 APP"))
        assertTrue(DispatchTrigger.aiMayDispatch("開一張派工單，APP 要自動備份"))
        assertTrue(DispatchTrigger.aiMayDispatch("這個幫我 派 工 一下"))
        assertNull(DispatchTrigger.parse("待辦事項新增APP自動備份"))
    }

    @Test fun notDispatch() {
        assertNull(DispatchTrigger.extract("打開 LINE"))
        assertNull(DispatchTrigger.extract("明天七點叫我"))
    }

    @Test fun projectPlanning() {
        // 規格範例：加前綴、原話不改
        assertEquals("【專案規劃】我想做一個出國旅遊規劃的 APP", DispatchTrigger.extract("專案規劃，我想做一個出國旅遊規劃的 APP"))
        assertEquals("【專案規劃】記錄喝水的網頁", DispatchTrigger.extract("規劃一個新專案：記錄喝水的網頁"))
        assertEquals("【專案規劃】記錄喝水的網頁", DispatchTrigger.extract("請規劃專案 記錄喝水的網頁"))
        val r = DispatchTrigger.parse("規劃新專案，記帳")!!
        assertTrue(r.planning)
        assertEquals("記帳", r.text)
        // 一般派工不受影響
        assertEquals(false, DispatchTrigger.parse("派工，記帳")!!.planning)
    }

    @Test fun projectPlanningTriggerOnly() {
        // 只講觸發詞：回空字串，下一句再補前綴
        assertEquals("", DispatchTrigger.extract("專案規劃"))
        val r = DispatchTrigger.parse("專案規劃。")!!
        assertTrue(r.planning)
        assertEquals("【專案規劃】旅遊 APP", r.copy(text = "旅遊 APP").payload)
    }

    @Test fun statusQuery() {
        assertEquals(12, DispatchTrigger.statusQuery("第 12 號派工單進度如何"))
        assertEquals(12, DispatchTrigger.statusQuery("12號單做好了沒"))
        assertEquals(15, DispatchTrigger.statusQuery("十五號單的狀態"))
        assertNull(DispatchTrigger.statusQuery("派工，27 號專案跑一下收工"))
    }

    @Test fun listQuery() {
        for (s in listOf("我的派工單有哪些", "派工單狀態", "所有派工單的進度", "還有哪些派工單沒做完", "列出派工單")) {
            assertTrue(s, DispatchTrigger.listQuery(s))
        }
        assertTrue(!DispatchTrigger.listQuery("第 12 號派工單進度如何"))
        assertTrue(!DispatchTrigger.listQuery("派工，幫鬧鐘 APP 加一個貪睡次數上限"))
        assertTrue(!DispatchTrigger.listQuery("派工，派工單網站要加一個清單頁可以列出每張單的狀態"))
    }

    @Test fun summarize() {
        val arr = org.json.JSONArray()
        fun t(id: Int, status: String, title: String) = arr.put(org.json.JSONObject().put("id", id).put("status", status).put("title", title))
        t(3, "approved", "已核准的")
        t(5, "awaiting_approval", "核准五")
        t(9, "needs_info", "補充九")
        t(7, "needs_info", "補充七")
        val s = DispatchClient.summarize(arr)
        assertTrue(s, s.startsWith("未完成的派工單有 4 張：需要你補充說明 2 張、等你核准 1 張、已核准 1 張。"))
        assertTrue(s, s.contains("第 9 號（需要你補充說明）補充九；第 7 號（需要你補充說明）補充七；第 5 號（等你核准）核准五"))
        assertEquals("目前沒有未完成的派工單。", DispatchClient.summarize(org.json.JSONArray()))
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
