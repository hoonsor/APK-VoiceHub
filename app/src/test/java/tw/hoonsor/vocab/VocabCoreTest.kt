package tw.hoonsor.vocab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 跑中心的共用案例：resources/vocab/replace-cases.json 是 contract/replace-cases.json 的複本。 */
class VocabCoreTest {
    private val contract = JSONObject(
        javaClass.getResourceAsStream("/vocab/replace-cases.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() },
    )

    private val terms: List<VocabTerm> = contract.getJSONArray("terms").let { arr ->
        List(arr.length()) { i ->
            val t = arr.getJSONObject(i)
            val aliases = t.getJSONArray("aliases")
            VocabTerm(t.getString("term"), "other", false, List(aliases.length()) { n ->
                aliases.getJSONObject(n).let { VocabAlias(it.getString("text"), it.getBoolean("replace")) }
            })
        }
    }

    @Test fun replaceCases() {
        val cases = contract.getJSONArray("cases")
        assert(cases.length() > 10)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val r = VocabCore.applyReplacements(c.getString("input"), terms)
            val hits = c.getJSONArray("hits")
            assertEquals(c.getString("name"), c.getString("output"), r.text)
            assertEquals(c.getString("name"), List(hits.length()) { hits.getString(it).lowercase() }, r.hits.map { it.lowercase() })
        }
    }

    @Test fun mergeKeepsBothSides() {
        assertEquals(listOf("a", "b", "x", "y"), VocabCore.mergeForPrompt(listOf("a", "b", "c"), listOf("x", "y", "z"), 4))
    }

    @Test fun mergeFillsFromCenterWhenLocalIsShort() {
        assertEquals(listOf("a", "b", "x", "c"), VocabCore.mergeForPrompt(listOf("a", "b", "c", "d"), listOf("x"), 4))
    }

    @Test fun mergeRemovesDuplicates() {
        assertEquals(listOf("a", "b"), VocabCore.mergeForPrompt(listOf("a"), listOf("a", "b"), 4))
    }
}
