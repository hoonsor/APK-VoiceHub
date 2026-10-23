package tw.hoonsor.voicehub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.hoonsor.voicehub.ai.ProviderPolicy
import tw.hoonsor.voicehub.ai.ProviderPolicy.Kind

class ProviderPolicyTest {
    @Test fun defaults() {
        // 預設值：MiniMax 國際版要提醒（只限去識別化文字），Groq 不提醒
        assertEquals(Kind.MINIMAX_INTL, ProviderPolicy.classify("https://api.minimax.io/v1"))
        assertEquals(Kind.OTHER, ProviderPolicy.classify("https://api.groq.com/openai/v1"))
        assertNull(ProviderPolicy.notice("https://api.groq.com/openai/v1"))
    }

    @Test fun mainland() {
        listOf(
            "https://api.minimaxi.com/v1",
            "https://api.deepseek.com",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://open.bigmodel.cn/api/paas/v4",
            "https://ark.cn-beijing.volces.com/api/v3",
            "https://api.moonshot.cn/v1",
            "https://api.siliconflow.cn/v1",
            "api.deepseek.com",                 // 沒寫 https:// 也要認得
            "https://some-llm.example.cn/v1",   // .cn 網域
        ).forEach { assertEquals(it, Kind.MAINLAND_CLOUD, ProviderPolicy.classify(it)) }
        assertTrue(ProviderPolicy.notice("https://api.deepseek.com")!!.danger)
    }

    @Test fun notFooledBySimilarNames() {
        // 只比對網域結尾，不比對字串包含
        assertEquals(Kind.OTHER, ProviderPolicy.classify("https://deepseek.com.example.org/v1"))
        assertEquals(Kind.OTHER, ProviderPolicy.classify("https://api.openai.com/v1?ref=deepseek.com"))
        assertEquals(Kind.MINIMAX_INTL, ProviderPolicy.classify("https://API.MiniMax.io/v1"))
    }

    @Test fun local() {
        // 本機離線執行的模型可以用（例如區網裡的 Ollama 跑 Qwen）
        assertEquals(Kind.LOCAL, ProviderPolicy.classify("http://192.168.1.20:11434/v1"))
        assertEquals(Kind.LOCAL, ProviderPolicy.classify("http://localhost:8080/v1"))
        assertEquals(Kind.LOCAL, ProviderPolicy.classify("http://172.20.0.5/v1"))
        assertNull(ProviderPolicy.notice("http://127.0.0.1:1234/v1"))
    }

    @Test fun blankIsSilent() {
        assertNull(ProviderPolicy.notice(""))
        assertNull(ProviderPolicy.notice("   "))
    }
}
