package tw.hoonsor.vocab

import java.util.Locale

/**
 * 詞彙替換核心（語音個人詞庫中心 clients/kotlin，複製到 APP 使用，不要在 APP 內改）。
 * 行為以中心的 contract/replace-cases.json 為準；TypeScript 版在 src/lib/vocab-core。
 */
data class VocabAlias(val text: String, val replace: Boolean)

data class VocabTerm(val term: String, val category: String, val pinned: Boolean, val aliases: List<VocabAlias>)

data class ReplaceResult(val text: String, val hits: List<String>)

object VocabCore {
    private const val MIN_REPLACE_LENGTH = 2

    private class Pattern(val needle: String, val output: String?, val alias: String) {
        val length = needle.length
        val ascii = alias.all { it.code in 0x20..0x7e }
    }

    private fun isAsciiWord(c: Char?) = c != null && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9')

    private fun patterns(terms: List<VocabTerm>): List<Pattern> {
        val list = ArrayList<Pattern>()
        for (t in terms) {
            list += Pattern(t.term.lowercase(Locale.ROOT), null, t.term)
            for (a in t.aliases) {
                if (a.replace && a.text.length >= MIN_REPLACE_LENGTH) {
                    list += Pattern(a.text.lowercase(Locale.ROOT), t.term, a.text)
                }
            }
        }
        // 長的優先；一樣長時正確寫法優先
        return list.sortedWith(compareBy<Pattern> { -it.length }.thenBy { if (it.output == null) 0 else 1 })
    }

    /** 由左到右掃一遍；替換後的文字不再掃描，所以不會連鎖 */
    fun applyReplacements(text: String, terms: List<VocabTerm>): ReplaceResult {
        if (text.isEmpty()) return ReplaceResult(text, emptyList())
        val all = patterns(terms)
        val lower = text.lowercase(Locale.ROOT)
        val hits = ArrayList<String>()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val hit = all.firstOrNull { p ->
                lower.startsWith(p.needle, i) &&
                    (!p.ascii || (!isAsciiWord(text.getOrNull(i - 1)) && !isAsciiWord(text.getOrNull(i + p.length))))
            }
            if (hit == null) {
                out.append(text[i])
                i += 1
                continue
            }
            if (hit.output == null) {
                out.append(text, i, i + hit.length)
            } else {
                out.append(hit.output)
                hits += hit.alias
            }
            i += hit.length
        }
        return ReplaceResult(out.toString(), hits)
    }

    /**
     * 中心的詞和本機的詞合併成提示清單：中心已排好序，先給中心一半名額，其餘給本機，還有空位再由中心補滿。
     * 兩邊都不會被另一邊完全擠掉。
     */
    fun mergeForPrompt(center: List<String>, local: List<String>, limit: Int): List<String> {
        val half = (limit + 1) / 2
        return (center.take(half) + local + center.drop(half)).distinct().take(limit)
    }
}
