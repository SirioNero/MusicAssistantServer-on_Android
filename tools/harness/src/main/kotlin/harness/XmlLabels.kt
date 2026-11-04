package harness

import it.xonos.ma.playback.MenuLabels
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Le scritte del menu lette dai veri strings.xml dell'app, con le regole dei plurali di ogni lingua. */
class XmlLabels(private val lang: String) : MenuLabels {
    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        // Le risorse dell'app: build.gradle.kts passa la cartella con -Dxonos.res.
        val res = System.getProperty("xonos.res") ?: "../../app/src/main/res"
        load(File("$res/values/strings.xml"))
        if (lang != "en") load(File("$res/values-$lang/strings.xml"))
    }

    private fun load(file: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = unescape(e.textContent)
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[e.getAttribute("name")] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun quantity(n: Int): String = when (lang) {
        "ru" -> when {
            n % 10 == 1 && n % 100 != 11 -> "one"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "few"
            else -> "many"
        }
        "zh" -> "other"
        "fr" -> if (n == 0 || n == 1) "one" else "other"
        else -> if (n == 1) "one" else "other"
    }

    private fun plural(name: String, n: Int): String {
        val forms = plurals.getValue(name)
        return (forms[quantity(n)] ?: forms.getValue("other")).replace("%d", n.toString())
    }

    private val categories = mapOf("radio" to "radios")

    override fun category(id: String) = strings.getValue("cat_$id")
    override fun kind(type: String) = strings.getValue("cat_${categories[type] ?: type}")
    override fun results(count: Int) = plural("results", count)
    override fun count(type: String, count: Int) = plural("count_$type", count)
    override val topTracks get() = strings.getValue("top_tracks")
    override val allTracks get() = strings.getValue("all_tracks")
    override fun row(itemId: String): String? = strings["row_$itemId"]
}
