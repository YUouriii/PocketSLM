package com.pocketllm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageSupportTest {

    @Test
    fun qwenFamilyIsMultilingual() {
        assertEquals(Catalog.LanguageSupport.MULTILINGUAL, Catalog.languageSupport("qwen2.5-1.5b"))
        assertEquals(Catalog.LanguageSupport.MULTILINGUAL, Catalog.languageSupport("qwen3.5-2b"))
        assertEquals(Catalog.LanguageSupport.MULTILINGUAL, Catalog.languageSupport("qwen2.5-coder-3b"))
    }

    @Test
    fun englishFirstFamiliesAreNotMultilingual() {
        assertEquals(Catalog.LanguageSupport.ENGLISH, Catalog.languageSupport("smollm2-1.7b"))
        assertEquals(Catalog.LanguageSupport.ENGLISH, Catalog.languageSupport("llama32-1b"))
    }

    @Test
    fun liquidModelsAreMultilingual() {
        // Liquid publishes Arabic among LFM2/LFM3 supported languages.
        assertEquals(Catalog.LanguageSupport.MULTILINGUAL, Catalog.languageSupport("lfm3-1.7b"))
        assertEquals(Catalog.LanguageSupport.MULTILINGUAL, Catalog.languageSupport("lfm2.5-1.2b"))
    }

    @Test
    fun communityUploadsStayUnknown() {
        assertEquals(Catalog.LanguageSupport.UNKNOWN, Catalog.languageSupport(null))
        assertEquals(Catalog.LanguageSupport.UNKNOWN, Catalog.languageSupport(""))
        assertEquals(Catalog.LanguageSupport.UNKNOWN, Catalog.languageSupport("user/model|file.gguf"))
    }

    @Test
    fun labelsAreReadable() {
        assertEquals("Multilingual", Catalog.languageLabel("qwen2.5-3b"))
        assertEquals("English-focused", Catalog.languageLabel("smollm2-1.7b"))
        assertEquals("Language support unknown", Catalog.languageLabel("user/model"))
    }
}
