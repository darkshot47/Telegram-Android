package com.telefarm.res

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The styles, checked for the one inheritance rule that fails a build quietly.
 *
 * A style name with a dot in it inherits from everything before the last dot. `Widget.Telefarm.Row`
 * therefore has `Widget.Telefarm` as its parent whether or not anybody meant it to, and if that
 * style was never written the resource linker fails with "resource style/Widget.Telefarm not
 * found" - naming a style that does not exist rather than the one that asked for it.
 *
 * The way out is `parent=""`, which says the style inherits from nothing, and every style in this
 * project says so except one, which broke the build.
 */
class ResourceStylesTest {

    private val valuesDirs: List<File> by lazy {
        listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory }
            ?.listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            ?.toList()
            .orEmpty()
    }

    /** Every style name declared anywhere, and the parent each one names. */
    private fun styles(): List<Style> {
        val factory = DocumentBuilderFactory.newInstance()
        val found = mutableListOf<Style>()
        for (dir in valuesDirs) {
            for (file in dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty()) {
                val root = factory.newDocumentBuilder().parse(file).documentElement
                val nodes = root.getElementsByTagName("style")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                    val parent = node.attributes.getNamedItem("parent")?.nodeValue
                    found += Style(name, parent, file.name, dir.name)
                }
            }
        }
        return found
    }

    private data class Style(val name: String, val parent: String?, val file: String, val dir: String)

    @Test
    fun `the styles are where the tests expect them`() {
        assertTrue("no values directories found", valuesDirs.isNotEmpty())
        assertTrue("no styles found", styles().isNotEmpty())
    }

    @Test
    fun `every dotted style either names a parent or has one that exists`() {
        val declared = styles().map { it.name }.toSet()
        val orphans = styles().filter { style ->
            val explicit = style.parent
            when {
                // An explicit parent is the author's own business; an empty one means "nothing".
                explicit != null -> false
                // No dot, so no implied parent.
                !style.name.contains('.') -> false
                // Base.<Name> is the Material convention for a parent that is meant to be
                // extended, and the part after Base. is what carries the inheritance.
                style.name.startsWith("Base.") -> false
                else -> style.name.substringBeforeLast('.') !in declared
            }
        }
        assertTrue(
            "these styles inherit from a style that does not exist; give them parent=\"\" : " +
                orphans.joinToString { "${it.name} (wants ${it.name.substringBeforeLast('.')}) in ${it.file}" },
            orphans.isEmpty()
        )
    }

    @Test
    fun `no two styles in the same configuration share a name`() {
        // The same name in two configurations is not a clash but the point: Theme.Telefarm is
        // declared for the day and again for the night, and the device picks one by the light in
        // the room. Two declarations inside one directory, though, is a name written twice.
        val duplicates = styles()
            .groupBy { it.dir to it.name }
            .filter { it.value.size > 1 }
            .keys
        assertTrue(
            "declared twice in the same directory: " +
                duplicates.map { (dir, name) -> "$dir/$name" }.sorted(),
            duplicates.isEmpty()
        )
    }
}
