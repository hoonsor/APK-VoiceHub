package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.agent.ActionSpec
import tw.hoonsor.voicehub.agent.AgentCatalog
import tw.hoonsor.voicehub.agent.BuiltinCatalog
import tw.hoonsor.voicehub.agent.Risk
import tw.hoonsor.voicehub.agent.Schema
import tw.hoonsor.voicehub.manual.Manual
import java.io.File

/** 說明書的格式解析，以及「每個內建動作都要寫進說明書」的檢查 */
class ManualTest {
    private val doc: Manual.Document by lazy {
        val file = listOf(File("../docs/manual/MANUAL.md"), File("docs/manual/MANUAL.md")).first { it.exists() }
        Manual.parse(file.readText(Charsets.UTF_8))
    }

    @Test fun everyBuiltinActionIsDocumented() {
        val documented = doc.actionKeys.filter { it.pkg == Manual.SELF }.map { it.action }.toSet()
        val missing = BuiltinCatalog.actions.map { it.name }.filterNot { it in documented }
        assertTrue("說明書缺少內建動作：$missing（docs/manual/MANUAL.md 加一節並標 <!-- action: voicehub:動作名 -->）", missing.isEmpty())
    }

    @Test fun noStaleBuiltinTags() {
        val names = BuiltinCatalog.actions.map { it.name }.toSet()
        val stale = doc.actionKeys.filter { it.pkg == Manual.SELF && it.action !in names }
        assertTrue("說明書標了不存在的內建動作：$stale", stale.isEmpty())
    }

    @Test fun actionTagsAreUnique() {
        val all = doc.chapters.flatMap { c -> c.sections.mapNotNull { it.action } }
        val dup = all.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("同一個動作寫了兩節：$dup", dup.isEmpty())
    }

    @Test fun everyActionSectionHasExamples() {
        val empty = doc.chapters.flatMap { c -> c.sections.filter { it.action != null && it.blocks.none { b -> b is Manual.Example } } }.map { it.title }
        assertTrue("這些動作節沒有任何例句：$empty", empty.isEmpty())
    }

    @Test fun preambleAndCommentsAreSkipped() {
        assertEquals("快速上手", doc.chapters.first().title)
        assertFalse(doc.chapters.any { c -> c.sections.any { s -> s.searchText.contains("撰寫規則") } })
    }

    @Test fun parsesBlocks() {
        val d = Manual.parse(
            """
            書名不顯示

            # 第一章
            <!-- app: com.example.app -->
            章的介紹第一行
            接著同一段

            ## 設鬧鐘
            <!-- action: com.example.app:alarm_create -->
            - 「七點叫我」→ 新增 **07:00**
            - 「設一個鬧鐘」
            - 一般條列
            > 提示一
            > 提示二
            <!-- 一般註解
            多行也略過 -->
            | 表格 | 略過 |
            """.trimIndent(),
        )
        val ch = d.chapters.single()
        assertEquals("com.example.app", ch.app)
        assertEquals(listOf(Manual.Paragraph("章的介紹第一行 接著同一段")), ch.intro)
        val s = ch.sections.single()
        assertEquals(Manual.ActionKey("com.example.app", "alarm_create"), s.action)
        assertEquals(
            listOf(
                Manual.Example("七點叫我", "新增 **07:00**"),
                Manual.Example("設一個鬧鐘", ""),
                Manual.Bullet("一般條列"),
                Manual.Tip("提示一\n提示二"),
            ),
            s.blocks,
        )
    }

    @Test fun splitsMultiplePhrasesOnOneLine() {
        assertEquals(
            listOf(Manual.Example("下一首", "切歌"), Manual.Example("上一首", "切歌")),
            Manual.examples("「下一首」「上一首」→ 切歌"),
        )
        assertEquals(listOf(Manual.Example("暫停", "")), Manual.examples("「暫停」"))
        // 例句後面直接接文字＝在說明講法，不是可以點的指令
        assertEquals(null, Manual.examples("「七點半」「七點一刻」也聽得懂"))
        assertEquals(null, Manual.examples("一般條列"))
        // 真實說明書裡不能有黏在一起的例句
        val glued = doc.chapters.flatMap { c -> c.sections.flatMap { s -> s.blocks.filterIsInstance<Manual.Example>() } }.filter { "「" in it.phrase || "」" in it.phrase }
        assertTrue("例句黏在一起：$glued", glued.isEmpty())
    }

    @Test fun filterMatchesAllWords() {
        assertTrue(Manual.filter(doc, "").chapters.size == doc.chapters.size)
        val hit = Manual.filter(doc, "手電筒")
        assertTrue(hit.chapters.any { c -> c.sections.any { it.action?.action == "flashlight_set" } })
        assertTrue(Manual.filter(doc, "絕對不會出現的字串xyz").chapters.isEmpty())
    }

    private fun catalog(pkg: String, vararg actions: String, deprecated: Set<String> = emptySet()) = AgentCatalog(
        app = pkg, packageName = pkg, version = "1.0.0", description = "",
        actions = actions.map { ActionSpec(it, it, "", Risk.READ, Schema.obj(), listOf("例"), deprecated = it in deprecated) },
    )

    @Test fun resolverStatuses() {
        val r = Manual.Resolver(
            listOf(BuiltinCatalog.catalog("tw.hoonsor.voicehub.lab", "x"), catalog("com.photobox.sorter.lab", "photo_list", "old_one", deprecated = setOf("old_one"))),
        ) { it == "tw.hoonsor.ime" }
        // LAB 版的套件名視為同一個 APP
        assertEquals(Manual.Status.AVAILABLE, r.appStatus("com.photobox.sorter"))
        assertEquals(Manual.Status.AVAILABLE, r.actionStatus(Manual.ActionKey("com.photobox.sorter", "photo_list")))
        assertEquals(Manual.Status.MISSING_IN_VERSION, r.actionStatus(Manual.ActionKey("com.photobox.sorter", "album_list")))
        assertEquals(Manual.Status.MISSING_IN_VERSION, r.actionStatus(Manual.ActionKey("com.photobox.sorter", "old_one")))
        assertEquals(Manual.Status.INSTALLED_NO_API, r.actionStatus(Manual.ActionKey("tw.hoonsor.ime", "clipboard_list")))
        assertEquals(Manual.Status.NOT_INSTALLED, r.appStatus("com.hoonsor.screentranslate"))
        assertEquals(Manual.Status.AVAILABLE, r.actionStatus(Manual.ActionKey(Manual.SELF, "flashlight_set")))
    }

    @Test fun resolverListsUncovered() {
        val r = Manual.Resolver(listOf(catalog("com.new.app", "thing_list"), catalog("com.photobox.sorter", "photo_list"))) { false }
        val un = r.uncovered(doc)
        val newApp = un.firstOrNull { it.first.packageName == "com.new.app" }
        assertNotNull("說明書沒寫到的 APP 要列為未收錄", newApp)
        assertEquals(listOf("thing_list"), newApp!!.second)
        assertFalse("已收錄的動作不該出現", un.any { it.first.packageName == "com.photobox.sorter" })
    }
}
