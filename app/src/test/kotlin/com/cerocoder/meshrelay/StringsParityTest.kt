package com.cerocoder.meshrelay

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A missing Spanish key does not fail the build - Android silently falls back to
 * English, and the gap is only found by someone using the app in Spanish. This
 * test is the only thing that notices.
 */
class StringsParityTest {

    private fun keys(path: String, tag: String): Set<String> {
        val file = File(path)
        assertTrue("missing resource file: $path", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length)
            .map { nodes.item(it).attributes.getNamedItem("name").nodeValue }
            .toSet()
    }

    private val english = "src/main/res/values/strings.xml"
    private val spanish = "src/main/res/values-es/strings.xml"

    @Test
    fun `both locales define the same strings`() {
        assertEquals(emptySet<String>(), keys(english, "string") - keys(spanish, "string"))
        assertEquals(emptySet<String>(), keys(spanish, "string") - keys(english, "string"))
    }

    @Test
    fun `both locales define the same plurals`() {
        assertEquals(emptySet<String>(), keys(english, "plurals") - keys(spanish, "plurals"))
        assertEquals(emptySet<String>(), keys(spanish, "plurals") - keys(english, "plurals"))
    }

    @Test
    fun `format placeholders match between locales`() {
        // A translated string that drops a %1$s crashes at format time, in Spanish
        // only, on the one screen nobody tested in Spanish.
        val placeholder = Regex("""%\d+\$[sd]""")
        fun values(path: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate {
                nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
            }
        }
        val en = values(english)
        val es = values(spanish)
        for ((key, text) in en) {
            assertEquals(
                "placeholder mismatch in $key",
                placeholder.findAll(text).map { it.value }.toSet(),
                placeholder.findAll(es.getValue(key)).map { it.value }.toSet(),
            )
        }
    }
}
