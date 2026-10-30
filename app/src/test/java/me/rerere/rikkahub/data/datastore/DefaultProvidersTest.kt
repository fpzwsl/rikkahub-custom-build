package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers should only include official providers`() {
        val expectedNames = setOf(
            "OpenAI",
            "Gemini",
            "DeepSeek",
            "月之暗面",
            "智谱AI开放平台",
            "xAI",
            "MiniMax",
        )

        assertEquals(expectedNames, DEFAULT_PROVIDERS.map { it.name }.toSet())
        assertTrue(DEFAULT_PROVIDERS.all { it.builtIn })
    }
}
