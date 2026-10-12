package com.telefarm.res

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The string resources, checked the way the resource compiler reads them.
 *
 * A resource file is not compiled by the Kotlin compiler, so a mistake in one shows up only when
 * the Android resource compiler runs - which is late, in a build that is otherwise fine, and the
 * message it gives is about "flattening XML" rather than about the apostrophe that caused it.
 *
 * Two strings that read perfectly well broke a build this way, because an apostrophe has to be
 * written `\'` in a string resource and neither of them had been. Both were English sentences
 * that nobody would think twice about: "the sender's name".
 *
 * This runs as an ordinary test, against the source files, so the same mistake is caught here
 * rather than in a build.
 */
class StringResourcesTest {

    /**
     * Where the resource files live.
     *
     * Gradle runs a unit test from the module directory, and a plain JVM run from the root of the
     * checkout, so both are looked for. The first that has the files wins, and finding neither is
     * itself a failure rather than an empty pass.
     */
    private val valuesDirs: List<File> by lazy {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        candidates.firstOrNull { it.isDirectory }?.listFiles { file ->
            file.isDirectory && file.name.startsWith("values")
        }?.toList().orEmpty()
    }

    private fun strings(): List<Pair<String, String>> {
        val factory = DocumentBuilderFactory.newInstance()
        val found = mutableListOf<Pair<String, String>>()
        for (dir in valuesDirs) {
            for (file in dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty()) {
                val root = factory.newDocumentBuilder().parse(file).documentElement
                val nodes = root.childNodes
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    val name = node.nodeName
                    // A bare <item> inside <plurals> or <string-array> is a string, but <item>
                    // is also how ids.xml declares a view id and dimens.xml a dimension. Those
                    // carry a type, and asking a translation for them is asking for a resource
                    // that has no words in it.
                    val type = node.attributes?.getNamedItem("type")?.nodeValue
                    if (name == "item" && type != null && type != "string" && type != "plurals") {
                        continue
                    }
                    if (name != "string" && name != "item") continue
                    val text = node.textContent ?: continue
                    val label = node.attributes?.getNamedItem("name")?.nodeValue
                        ?: node.attributes?.getNamedItem("quantity")?.nodeValue
                        ?: file.name
                    found += (dir.name + "/" + label) to text
                }
            }
        }
        return found
    }

    /**
     * Keys the default file marks `translatable="false"`.
     *
     * Those are glyphs and symbols rather than language - the B of a bold button, the ▨ of a
     * spoiler - and a translation of one would be a copy of it. Android falls back to the default
     * for them, which is the right answer, so asking a translation for them is the wrong question.
     */
    private fun untranslatable(): Set<String> {
        val factory = DocumentBuilderFactory.newInstance()
        val keys = mutableSetOf<String>()
        val file = valuesDirs.firstOrNull { it.name == "values" }?.listFiles { f -> f.name.endsWith(".xml") }
        for (each in file.orEmpty()) {
            val root = factory.newDocumentBuilder().parse(each).documentElement
            val nodes = root.childNodes
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.nodeName != "string") continue
                if (node.attributes?.getNamedItem("translatable")?.nodeValue == "false") {
                    node.attributes.getNamedItem("name")?.nodeValue?.let(keys::add)
                }
            }
        }
        return keys
    }

    @Test
    fun `the resource files are where the tests expect them`() {
        // A test that quietly reads nothing passes, which is the worst way for it to behave.
        assertTrue("no values directories found", valuesDirs.isNotEmpty())
        assertTrue("no strings found", strings().size > 100)
    }

    @Test
    fun `no string carries an unescaped apostrophe`() {
        // The resource compiler rejects a bare apostrophe and reports it as an invalid unicode
        // escape sequence, which does not point at the character that caused it.
        val offenders = strings().filter { (_, text) ->
            text.windowed(2).any { it[1] == '\'' && it[0] != '\\' } ||
                text.startsWith("'")
        }
        assertTrue(
            "escape these as \\' : " + offenders.joinToString { it.first },
            offenders.isEmpty()
        )
    }

    @Test
    fun `no string carries an unescaped backslash sequence the compiler would read as an escape`() {
        // \u must be followed by four hex digits, and \n, \t and friends are read as what they
        // say. A backslash that means a literal backslash has to be doubled.
        val allowed = setOf('n', 't', 'r', '\'', '"', '\\', '@', '?', 'u')
        val offenders = strings().filter { (_, text) ->
            var index = text.indexOf('\\')
            var bad = false
            while (index >= 0) {
                val next = text.getOrNull(index + 1)
                if (next == null || next !in allowed) {
                    bad = true
                    break
                }
                if (next == 'u') {
                    val hex = text.substring(index + 2, minOf(index + 6, text.length))
                    if (hex.length != 4 || hex.any { it !in "0123456789abcdefABCDEF" }) {
                        bad = true
                        break
                    }
                }
                index = text.indexOf('\\', index + 1)
            }
            bad
        }
        assertTrue(
            "check these backslashes: " + offenders.joinToString { it.first },
            offenders.isEmpty()
        )
    }

    @Test
    fun `every translation carries the same keys as the default`() {
        // A key missing from a translation is not an error to the compiler; it is a screen that
        // silently falls back to English for one person in a hundred, which is harder to notice.
        //
        // Only directories that actually hold strings count as translations. `values-night` is a
        // colour qualifier rather than a language, and asking it for a translation of everything
        // is asking the wrong file a question it was never meant to answer.
        val defaultKeys = stringsOf("values") - untranslatable()
        val translations = valuesDirs.filter { dir ->
            dir.name != "values" && dir.listFiles()?.any { it.name == "strings.xml" } == true
        }
        assertTrue("no translation directories found", translations.isNotEmpty())
        for (dir in translations) {
            val translated = stringsOf(dir.name)
            val missing = defaultKeys - translated
            assertTrue(
                dir.name + " is missing: " + missing.sorted().take(20),
                missing.isEmpty()
            )
        }
    }

    private fun stringsOf(dirName: String): Set<String> =
        strings().filter { (label, _) -> label.startsWith("$dirName/") }
            .map { (label, _) -> label.removePrefix("$dirName/") }
            .toSet()
}
