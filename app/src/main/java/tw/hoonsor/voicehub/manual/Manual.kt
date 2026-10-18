package tw.hoonsor.voicehub.manual

import tw.hoonsor.voicehub.agent.AgentCatalog

/**
 * 使用說明書：唯一來源是 repo 的 docs/manual/MANUAL.md（打包成 assets/MANUAL.md）。
 * 純邏輯，JVM 測試會檢查每個內建動作都有收錄（ManualTest）。
 *
 * 格式（刻意只支援這幾種，GitHub 上看也正常）：
 * - `# 標題`：章；下一行 `<!-- app: 套件名 -->` 表示這章在介紹某個 APP
 * - `## 標題`：節；下一行 `<!-- action: 套件名:動作名 -->` 表示這節在介紹某個動作（內建動作用 `voicehub`）
 * - `- 「例句」` 或 `- 「例句」→ 說明`：可以點的語音例句
 * - `- 文字`：一般條列；`> 文字`：提示框；其餘連續的行是段落
 * - 行內只支援 `**粗體**` 與 `` `程式字` ``；其他 HTML 註解會被忽略
 */
object Manual {
    /** 內建動作在說明書裡的套件代號 */
    const val SELF = "voicehub"

    sealed interface Block
    data class Paragraph(val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Tip(val text: String) : Block
    data class Example(val phrase: String, val note: String) : Block

    data class Section(val title: String, val action: ActionKey?, val blocks: List<Block>) {
        /** 搜尋用：標題、內文、例句全部串起來 */
        val searchText: String by lazy {
            buildString {
                append(title).append('\n')
                blocks.forEach {
                    when (it) {
                        is Paragraph -> append(it.text)
                        is Bullet -> append(it.text)
                        is Tip -> append(it.text)
                        is Example -> append(it.phrase).append(' ').append(it.note)
                    }
                    append('\n')
                }
            }
        }
    }

    data class Chapter(val title: String, val app: String?, val intro: List<Block>, val sections: List<Section>)

    data class ActionKey(val pkg: String, val action: String) {
        override fun toString() = "$pkg:$action"
    }

    data class Document(val chapters: List<Chapter>) {
        val actionKeys: Set<ActionKey> get() = chapters.flatMap { c -> c.sections.mapNotNull { it.action } }.toSet()
        val appPackages: Set<String> get() = chapters.mapNotNull { it.app }.toSet()
    }

    private val actionTag = Regex("""^<!--\s*action:\s*([A-Za-z0-9_.]+):([a-z0-9_]+)\s*-->$""")
    private val appTag = Regex("""^<!--\s*app:\s*([A-Za-z0-9_.]+)\s*-->$""")
    /** 開頭連續的「例句」「例句」，後面可接 → 說明 */
    private val examplePrefix = Regex("""^(?:「[^「」]+」\s*)+""")
    private val phrase = Regex("""「([^「」]+)」""")
    private val noteArrow = Regex("""^(?:→|->|—)\s*""")

    /** 一行可以寫好幾句例句，每句拆成一個可以點的按鈕；不是例句回 null */
    fun examples(body: String): List<Example>? {
        val head = examplePrefix.find(body) ?: return null
        val rest = body.substring(head.range.last + 1).trim()
        // 例句後面接的不是說明箭頭（例如「…」這種講法），就當一般條列
        if (rest.isNotEmpty() && !noteArrow.containsMatchIn(rest)) return null
        val note = rest.replace(noteArrow, "").trim()
        return phrase.findAll(head.value).map { Example(it.groupValues[1].trim(), note) }.toList()
    }

    fun parse(markdown: String): Document {
        val chapters = mutableListOf<Chapter>()
        var chTitle: String? = null
        var chApp: String? = null
        var intro = mutableListOf<Block>()
        var sections = mutableListOf<Section>()
        var secTitle: String? = null
        var secAction: ActionKey? = null
        var blocks = mutableListOf<Block>()
        val para = StringBuilder()
        var inComment = false

        fun flushPara() {
            if (para.isNotBlank()) blocks += Paragraph(para.toString().trim())
            para.clear()
        }
        fun flushSection() {
            flushPara()
            val t = secTitle
            if (t != null) sections += Section(t, secAction, blocks) else intro.addAll(blocks)
            blocks = mutableListOf()
            secTitle = null
            secAction = null
        }
        fun flushChapter() {
            flushSection()
            chTitle?.let { chapters += Chapter(it, chApp, intro, sections) }
            intro = mutableListOf()
            sections = mutableListOf()
            chApp = null
        }

        for (raw in markdown.lines()) {
            val line = raw.trim()
            if (inComment) {
                if ("-->" in line) inComment = false
                continue
            }
            when {
                line.startsWith("<!--") -> {
                    flushPara()
                    actionTag.find(line)?.let { secAction = ActionKey(it.groupValues[1], it.groupValues[2]) }
                    appTag.find(line)?.let { if (secTitle == null) chApp = it.groupValues[1] }
                    if ("-->" !in line) inComment = true
                }
                line.startsWith("## ") -> { flushSection(); secTitle = line.removePrefix("## ").trim() }
                line.startsWith("# ") -> { flushChapter(); chTitle = line.removePrefix("# ").trim() }
                // 書名、更新日期等第一章之前的內容不放進說明書畫面
                chTitle == null -> Unit
                line.isEmpty() -> flushPara()
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flushPara()
                    val body = line.substring(2).trim()
                    blocks += examples(body) ?: listOf(Bullet(body))
                }
                line.startsWith(">") -> {
                    flushPara()
                    val body = line.removePrefix(">").trim()
                    // 連續的引言行併成同一個提示框
                    val last = blocks.lastOrNull()
                    if (last is Tip && body.isNotEmpty()) blocks[blocks.lastIndex] = Tip(last.text + "\n" + body)
                    else if (body.isNotEmpty()) blocks += Tip(body)
                }
                line.startsWith("|") || line.startsWith("---") -> Unit // 表格與分隔線不支援，直接略過
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(line) }
            }
        }
        flushChapter()
        return Document(chapters)
    }

    /** LAB 版的套件名多了 .lab，對照說明書時視為同一個 APP */
    fun basePackage(pkg: String) = pkg.removeSuffix(".lab")

    enum class Status {
        /** 已安裝而且這個動作可以用 */
        AVAILABLE,
        /** APP 有開放語音控制，但手機上這一版沒有這個動作 */
        MISSING_IN_VERSION,
        /** APP 已安裝，但還沒開放語音控制（規劃中） */
        INSTALLED_NO_API,
        /** 手機上沒有這個 APP */
        NOT_INSTALLED,
    }

    /**
     * 依手機上實際偵測到的 APP 判斷說明書每一節的狀態。
     * [catalogs] 來自 ToolRegistry（describe 的結果，內建動作也在裡面）；[installed] 判斷套件是否有安裝。
     */
    class Resolver(
        catalogs: List<AgentCatalog>,
        private val installed: (String) -> Boolean,
    ) {
        private val byBase: Map<String, AgentCatalog> = catalogs.associateBy { if (it.builtin) SELF else basePackage(it.packageName) }

        fun catalogFor(pkg: String): AgentCatalog? = byBase[if (pkg == SELF) SELF else basePackage(pkg)]

        fun appStatus(pkg: String): Status = when {
            pkg == SELF -> Status.AVAILABLE
            catalogFor(pkg) != null -> Status.AVAILABLE
            installed(pkg) || installed("$pkg.lab") -> Status.INSTALLED_NO_API
            else -> Status.NOT_INSTALLED
        }

        fun actionStatus(key: ActionKey): Status {
            val c = catalogFor(key.pkg) ?: return appStatus(key.pkg)
            val a = c.action(key.action)
            return if (a != null && !a.deprecated) Status.AVAILABLE else Status.MISSING_IN_VERSION
        }

        /** 手機上偵測得到、但說明書還沒收錄的 APP 與動作（說明書畫面最後會自動列出來） */
        fun uncovered(doc: Document): List<Pair<AgentCatalog, List<String>>> {
            val keys = doc.actionKeys
            return byBase.entries.mapNotNull { (base, c) ->
                val missing = c.actions.filter { !it.deprecated && ActionKey(base, it.name) !in keys }.map { it.name }
                if (missing.isEmpty()) null else c to missing
            }
        }
    }

    /** 依關鍵字篩選：空白分隔的每個詞都要出現（不分大小寫） */
    fun filter(doc: Document, query: String): Document {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return doc
        return Document(
            doc.chapters.mapNotNull { ch ->
                val hitTitle = words.all { ch.title.lowercase().contains(it) }
                val secs = if (hitTitle) ch.sections else ch.sections.filter { s -> words.all { s.searchText.lowercase().contains(it) } }
                if (secs.isEmpty() && !hitTitle) null else ch.copy(intro = if (hitTitle) ch.intro else emptyList(), sections = secs)
            },
        )
    }
}
