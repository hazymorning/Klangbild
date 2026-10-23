package io.github.hazymorning.klangbild

import assertk.assertThat
import assertk.assertions.isEmpty
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every text in layers/text/strings is read somewhere in the app's code, as lint checks for
 * Android's own resources: a string or a plural that nothing uses anymore goes.
 */
class TextsUsedTest {
    @Test
    fun `every text is used`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(System.getProperty("klangbild.strings")!!, "values/strings.xml"))
        fun names(tag: String) = doc.documentElement.getElementsByTagName(tag).let { list -> (0 until list.length).map { (list.item(it) as Element).getAttribute("name") } }
        val code = code().joinToString("\n") { it.readText() }
        val unused = names("string").filterNot { Regex("""\bStrings\.${camel(it)}\b""").containsMatchIn(code) } +
            names("plurals").filterNot { Regex("""\bPlurals\.${camel(it)}\b""").containsMatchIn(code) }
        assertThat(unused, name = "texts nothing uses").isEmpty()
    }

    /** The app's own code and every layer's, without the helpers of the tests. */
    private fun code(): List<File> {
        val layers = File(System.getProperty("klangbild.layers")!!).listFiles { f -> f.isDirectory && !f.name.endsWith("-testing") }!!
            .flatMap { File(it, "src").listFiles { f -> f.name.endsWith("Main") }.orEmpty().toList() }
        return (layers + File(System.getProperty("klangbild.appCode")!!)).flatMap { dir -> dir.walk().filter { it.extension == "kt" }.toList() }
    }

    /** A name in strings.xml as the generated code has it: set_up is setUp. */
    private fun camel(name: String) = name.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercaseChar) }.joinToString("")
}
