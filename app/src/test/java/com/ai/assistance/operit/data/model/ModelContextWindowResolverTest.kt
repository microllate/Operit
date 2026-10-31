package com.ai.assistance.operit.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelContextWindowResolverTest {

    @Test
    fun `recognizes common model context windows`() {
        assertEquals(128f, resolveKnownModelContextLengthK("gpt-4o"))
        assertEquals(1024f, resolveKnownModelContextLengthK("gpt-4.1"))
        assertEquals(200f, resolveKnownModelContextLengthK("claude-3-7-sonnet"))
        assertEquals(1024f, resolveKnownModelContextLengthK("gemini-2.5-pro"))
        assertEquals(64f, resolveKnownModelContextLengthK("deepseek-chat"))
        assertEquals(1024f, resolveKnownModelContextLengthK("deepseek-flash"))
        assertEquals(1024f, resolveKnownModelContextLengthK("deepseek-v4-flash"))
        assertEquals(1024f, resolveKnownModelContextLengthK("deepseek-v4-flash-1017"))
        assertEquals(256f, resolveKnownModelContextLengthK("kimi-for-coding"))
    }

    @Test
    fun `uses the smallest capacity for a known fallback model list`() {
        assertEquals(64f, resolveKnownModelContextLengthK("gpt-4o,deepseek-chat"))
    }

    @Test
    fun `does not claim capacity for unknown models or mixed known and unknown lists`() {
        assertNull(resolveKnownModelContextLengthK("my-private-model"))
        assertNull(resolveKnownModelContextLengthK("gpt-4o,my-private-model"))
    }
}
