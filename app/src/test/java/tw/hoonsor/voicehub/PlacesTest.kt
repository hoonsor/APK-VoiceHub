package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.hoonsor.voicehub.agent.OfflineParser
import tw.hoonsor.voicehub.agent.Places

/** 常用地點：設定解析、名稱比對、離線句型 */
class PlacesTest {
    private val places = Places.parse("家=住家地標\n公司＝某某大樓\n\n壞掉的一行\n=沒有名稱")

    @Test fun parseSkipsBadLines() {
        assertEquals(mapOf("家" to "住家地標", "公司" to "某某大樓"), places)
    }

    @Test fun homeVariantsResolve() {
        listOf("家", "回家", "我家", "家裡", "回到家", "導航回家").forEach {
            assertEquals(it, "住家地標", Places.resolve(it, places))
        }
        assertEquals("某某大樓", Places.resolve("去公司", places))
    }

    @Test fun otherDestinationsUntouched() {
        assertNull(Places.resolve("台北車站", places))
        assertNull(Places.resolve("家樂福", places))
        assertNull(Places.resolve("家", emptyMap()))
    }

    @Test fun shortcutOnlyForPlainPlaceCommands() {
        listOf("回家", "回家。", "導航回家", "帶我回家", "我要回家", "幫我導航回家吧", "去公司").forEach {
            assertEquals(it, true, Places.shortcut(it, places) != null)
        }
        listOf("開車回家", "回家路上買牛奶", "導航到台北車站", "家樂福").forEach {
            assertNull(it, Places.shortcut(it, places))
        }
        assertNull(Places.shortcut("回家", emptyMap()))
    }

    @Test fun offlineGoHome() {
        listOf("回家", "導航回家", "帶我回家").forEach {
            val c = OfflineParser.parse(it)!!
            assertEquals("map_navigate", c.action)
            assertEquals("家", c.params.getString("destination"))
        }
        assertEquals("台北車站", OfflineParser.parse("導航到台北車站")!!.params.getString("destination"))
    }
}
