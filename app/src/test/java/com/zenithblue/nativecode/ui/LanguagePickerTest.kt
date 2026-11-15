package com.zenithblue.nativecode.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePickerTest {
    private val res = File("src/main/res")
    private val configTags = Regex("""<locale android:name="([^"]+)"""")
        .findAll(File(res, "xml/locales_config.xml").readText()).map { it.groupValues[1] }.toList()
    private val tags = LanguagePicker.LANGS.map { it.tag }

    /** BCP-47 tag -> resource qualifier folder ("pt-BR" -> values-pt-rBR, "id" -> values-in, "en" -> values). */
    private fun folder(tag: String) = when (tag) {
        "en" -> "values"
        "id" -> "values-in"
        else -> "values-" + tag.replace(Regex("-([A-Z]{2})$"), "-r$1")
    }

    @Test fun everyLocalesConfigEntryHasAPickerOptionAndValuesFolder() {
        assertTrue(configTags.isNotEmpty())
        for (t in configTags) {
            assertTrue("no picker option for $t", t in tags)
            assertTrue("no ${folder(t)}/strings.xml for $t", File(res, folder(t) + "/strings.xml").isFile)
        }
    }

    @Test fun everyPickerOptionIsInLocalesConfig() {
        assertEquals(configTags.sorted(), tags.sorted())
        assertEquals("duplicate picker tags", tags.size, tags.toSet().size)
    }

    @Test fun matchMapsStoredTagsToOptions() {
        assertEquals("", LanguagePicker.match(""))
        assertEquals("de", LanguagePicker.match("de-DE"))
        assertEquals("pt-BR", LanguagePicker.match("pt-BR"))
        assertEquals("zh-CN", LanguagePicker.match("zh-CN"))
        assertEquals("ar", LanguagePicker.match("ar,en"))
        assertEquals("", LanguagePicker.match("xx")) // unsupported -> system default (English fallback)
    }
}
