package tw.hoonsor.voicehub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.agent.AgentCatalog
import tw.hoonsor.voicehub.agent.AlarmRouting
import tw.hoonsor.voicehub.agent.BuiltinCatalog
import tw.hoonsor.voicehub.agent.OfflineParser
import tw.hoonsor.voicehub.ai.PlannedCall
import tw.hoonsor.voicehub.ai.Planner
import java.time.LocalDateTime

/** 鬧鐘／計時改送倬的鬧鐘、預設一次性（AlarmRouting） */
class AlarmRoutingTest {
    private val self = "tw.hoonsor.voicehub"
    private val builtin = BuiltinCatalog.catalog(self, "0")

    /** 倬的鬧鐘 v0.12.0 describe 的精簡版（只留路由會用到的參數） */
    private fun alarmApp(withAutoDelete: Boolean = true): AgentCatalog {
        val autoDelete = if (withAutoDelete) ""","auto_delete":{"type":"boolean"}""" else ""
        val json = """
            {"api":1,"app":"倬的鬧鐘","package":"com.hoonsor.alarm","version":"0.12.0","description":"鬧鐘與計時器",
             "actions":[
              {"name":"alarm_list","title":"列出鬧鐘","risk":"read","params":{"type":"object","properties":{}}},
              {"name":"alarm_create","title":"新增鬧鐘","risk":"reversible","params":{"type":"object","properties":{
                "hour":{"type":"integer"},"minute":{"type":"integer"},"memo":{"type":"string"},
                "days":{"type":"array","items":{"type":"integer"}}$autoDelete},"required":["hour"]}},
              {"name":"timer_start","title":"開始計時","risk":"reversible","params":{"type":"object","properties":{
                "seconds":{"type":"integer"},"label":{"type":"string"}$autoDelete},"required":["seconds"]}}
             ]}
        """.trimIndent()
        return AgentCatalog.fromDescribe(JSONObject(json), "com.hoonsor.alarm")
    }

    private fun withApp() = listOf(alarmApp(), builtin)

    /** 模擬離線模式：句型解析出系統動作，再經過路由 */
    private fun offline(text: String, catalogs: List<AgentCatalog> = withApp()): PlannedCall {
        val c = OfflineParser.parse(text)!!
        return AlarmRouting.adjust(PlannedCall(self, c.action, c.params), catalogs, text)
    }

    @Test fun alarmGoesToOwnAppAndIsOneTimeByDefault() {
        val c = offline("明天七點叫我")
        assertEquals("com.hoonsor.alarm", c.target)
        assertEquals("alarm_create", c.action)
        assertEquals(7, c.params.getInt("hour"))
        assertEquals(0, c.params.getInt("minute"))
        assertFalse(c.params.has("auto_delete"))
    }

    @Test fun fixedAlarmKeeps() {
        val c = offline("設一個固定的明天八點鬧鐘")
        assertEquals("alarm_create", c.action)
        assertEquals(8, c.params.getInt("hour"))
        assertEquals(false, c.params.getBoolean("auto_delete"))
    }

    @Test fun permanentTimerKeeps() {
        val c = offline("常駐的二十五分鐘計時器")
        assertEquals("com.hoonsor.alarm", c.target)
        assertEquals("timer_start", c.action)
        assertEquals(1500, c.params.getInt("seconds"))
        assertEquals(false, c.params.getBoolean("auto_delete"))
    }

    @Test fun timerIsOneTimeByDefault() {
        val c = offline("計時五分鐘")
        assertEquals("timer_start", c.action)
        assertFalse(c.params.has("auto_delete"))
    }

    @Test fun labelAndDaysAreMapped() {
        val call = PlannedCall(self, "alarm_set", JSONObject("""{"hour":6,"minute":30,"label":"運動","days":[1,3,5]}"""))
        val c = AlarmRouting.adjust(call, withApp(), "每週一三五六點半叫我運動")
        assertEquals("alarm_create", c.action)
        assertEquals("運動", c.params.getString("memo"))
        assertEquals(3, c.params.getJSONArray("days").length())
        assertFalse(c.params.has("label"))
    }

    @Test fun aiAutoDeleteIsCorrectedByUtterance() {
        // AI 自作主張帶 false，但倬沒說要留著 → 拿掉，交給 APP 預設一次性
        val wrong = PlannedCall("com.hoonsor.alarm", "alarm_create", JSONObject("""{"hour":7,"auto_delete":false}"""))
        assertFalse(AlarmRouting.adjust(wrong, withApp(), "明天七點叫我").params.has("auto_delete"))
        // 倬說要保留，AI 卻漏帶 → 補上 false
        val missed = PlannedCall("com.hoonsor.alarm", "timer_start", JSONObject("""{"seconds":600}"""))
        assertEquals(false, AlarmRouting.adjust(missed, withApp(), "計時十分鐘，響完不要刪").params.getBoolean("auto_delete"))
    }

    @Test fun oldAlarmAppWithoutAutoDeleteGetsNoParam() {
        val c = offline("設一個固定的明天八點鬧鐘", listOf(alarmApp(withAutoDelete = false), builtin))
        assertEquals("alarm_create", c.action)
        assertFalse(c.params.has("auto_delete"))
    }

    @Test fun staysBuiltinWithoutAlarmApp() {
        val c = offline("明天七點叫我", listOf(builtin))
        assertEquals(self, c.target)
        assertEquals("alarm_set", c.action)
    }

    @Test fun unrelatedCallsUntouched() {
        val call = PlannedCall(self, "flashlight_set", JSONObject().put("on", true))
        assertEquals(call, AlarmRouting.adjust(call, withApp(), "開手電筒"))
    }

    @Test fun keepWords() {
        assertTrue(AlarmRouting.wantsKeep("每天重複的七點鬧鐘"))
        assertTrue(AlarmRouting.wantsKeep("保留這個計時器"))
        assertTrue(AlarmRouting.wantsKeep("響完不要刪"))
        assertFalse(AlarmRouting.wantsKeep("明天七點叫我"))
        assertFalse(AlarmRouting.wantsKeep("不要固定，響一次就好"))
        assertFalse(AlarmRouting.wantsKeep("不用保留"))
    }

    @Test fun fallbackNote() {
        assertNotNull(AlarmRouting.fallbackNote("alarm_set", builtin = true, utterance = "明天七點叫我"))
        assertNotNull(AlarmRouting.fallbackNote("timer_set", builtin = true, utterance = "計時五分鐘"))
        assertNull(AlarmRouting.fallbackNote("alarm_set", builtin = true, utterance = "設一個固定的七點鬧鐘"))
        assertNull(AlarmRouting.fallbackNote("alarm_create", builtin = false, utterance = "明天七點叫我"))
        assertNull(AlarmRouting.fallbackNote("flashlight_set", builtin = true, utterance = "開手電筒"))
    }

    @Test fun promptTellsAiTheRule() {
        val prompt = Planner.systemPrompt(withApp(), LocalDateTime.of(2026, 10, 25, 9, 0))
        assertTrue(prompt.contains("auto_delete: false"))
        // 倬的 APP 排在系統動作前面
        assertTrue(prompt.indexOf("alarm_create") < prompt.indexOf("alarm_set"))
    }
}
