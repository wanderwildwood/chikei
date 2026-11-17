package com.kylecorry.trail_sense.tools.map.geocache

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads geocaches from a GPX file: a Geocaching.com Pocket Query or cache page download, an
 * Opencaching GPX, or what OpenSAK, GSAK or c:geo export. A Pocket Query zip (the caches file and
 * its "-wpts" file) is read whole. Which elements to read follows c:geo's GPXParser (Apache-2.0).
 *
 * Elements are matched by local name, so the groundspeak 1.0 and 1.0.1 namespaces both work.
 */
object GeocacheGpx {

    private const val MAX_ZIP_ENTRY = 64L * 1024 * 1024

    /** Whether [head] (the start of a GPX file) looks like it holds geocaches, not a track. */
    fun looksLikeCaches(head: String): Boolean {
        val lower = head.lowercase()
        return "groundspeak" in lower || "geocache|" in lower || "<oc:cache" in lower ||
                "opencaching" in lower && "<wpt" in lower
    }

    /** Every cache in [input], which may be a GPX file or a zip of them. Child waypoints are joined to their cache. */
    fun read(input: InputStream): List<Geocache> {
        val buffered = BufferedInputStream(input)
        buffered.mark(4)
        val magic = ByteArray(2)
        val count = buffered.read(magic)
        buffered.reset()
        val documents = if (count == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
            unzip(buffered)
        } else {
            listOf(buffered.readBytes())
        }
        val caches = mutableListOf<Geocache>()
        val children = mutableListOf<Pair<String?, CacheWaypoint>>()
        documents.forEach { parse(it, caches, children) }
        return join(caches, children)
    }

    private fun unzip(input: InputStream): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.lowercase().endsWith(".gpx")) continue
                val bytes = zip.readNBytesCapped(MAX_ZIP_ENTRY) ?: continue
                out.add(bytes)
            }
        }
        // The caches file before its waypoints, so every child finds its cache
        return out.sortedBy { if (looksLikeCaches(String(it, 0, minOf(it.size, 4096)))) 0 else 1 }
    }

    private fun InputStream.readNBytesCapped(max: Long): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > max) return null
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    private fun parse(bytes: ByteArray, caches: MutableList<Geocache>, children: MutableList<Pair<String?, CacheWaypoint>>) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // A GPX file has no business pulling in other files
            trySet { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            trySet { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            trySet { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val waypoints = document.documentElement.childElements("wpt")
        for (wpt in waypoints) {
            val lat = wpt.getAttribute("lat").toDoubleOrNull() ?: continue
            val lng = wpt.getAttribute("lon").toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
            val code = wpt.text("name")?.trim().orEmpty()
            val type = wpt.text("type")
            val sym = wpt.text("sym")
            val cache = wpt.descendant("cache")
            if (cache == null && type?.startsWith("Geocache", ignoreCase = true) != true) {
                // Not a cache: a parking spot or stage, from the "-wpts" file or an OpenSAK export
                val parent = wpt.descendant("Parent")?.textContent?.trim()
                children.add(
                    parent to CacheWaypoint(
                        code, wpt.text("desc") ?: wpt.text("urlname"), lat, lng,
                        type?.substringAfter("|")?.trim(), wpt.text("cmt")
                    )
                )
                continue
            }
            if (code.isEmpty()) continue
            val name = cache?.text("name") ?: wpt.text("urlname") ?: wpt.text("desc") ?: code
            val cacheType = cache?.text("type") ?: type?.substringAfter("|")
            val long = cache?.descendant("long_description")
            val short = cache?.descendant("short_description")
            val html = long?.getAttribute("html")?.equals("true", true) == true ||
                    short?.getAttribute("html")?.equals("true", true) == true
            caches.add(
                Geocache(
                    code = code.uppercase(),
                    source = GeocacheSources.GPX,
                    name = name.trim(),
                    lat = lat,
                    lng = lng,
                    kind = GeocacheKinds.of(cacheType),
                    typeName = cacheType?.trim(),
                    size = cache?.text("container")?.trim(),
                    difficulty = cache?.text("difficulty")?.toFloatOrNull(),
                    terrain = cache?.text("terrain")?.toFloatOrNull(),
                    owner = (cache?.text("placed_by") ?: cache?.text("owner"))?.trim(),
                    hint = cache?.text("encoded_hints")?.trim()?.ifEmpty { null },
                    summary = short?.textContent?.trim()?.ifEmpty { null },
                    description = long?.textContent?.trim()?.ifEmpty { null },
                    isHtml = html,
                    url = wpt.text("url")?.trim(),
                    placed = wpt.text("time")?.take(10),
                    available = cache?.getAttribute("available")?.equals("false", true) != true,
                    archived = cache?.getAttribute("archived")?.equals("true", true) == true,
                    foundOnSite = sym.equals("Geocache Found", ignoreCase = true) ||
                            wpt.descendant("UserFound")?.textContent?.isNotBlank() == true
                )
            )
        }
    }

    /** Child waypoints go with their cache: by GSAK's parent code, else by the code after its two-letter prefix. */
    private fun join(caches: List<Geocache>, children: List<Pair<String?, CacheWaypoint>>): List<Geocache> {
        if (children.isEmpty()) return caches.distinctBy { it.code }
        val byCode = caches.associateBy { it.code }
        val bySuffix = caches.associateBy { it.code.drop(2) }
        val grouped = mutableMapOf<String, MutableList<CacheWaypoint>>()
        for ((parent, child) in children) {
            val owner = parent?.uppercase()?.let { byCode[it] } ?: bySuffix[child.code.uppercase().drop(2)] ?: continue
            grouped.getOrPut(owner.code) { mutableListOf() }.add(child)
        }
        return caches.distinctBy { it.code }.map { cache ->
            val extra = grouped[cache.code] ?: return@map cache
            cache.copyWith(waypoints = (cache.waypoints.orEmpty() + extra).distinctBy { it.code })
        }
    }

    private inline fun DocumentBuilderFactory.trySet(block: DocumentBuilderFactory.() -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Not every parser knows every feature
        }
    }

    private fun Element.childElements(localName: String): List<Element> {
        val out = mutableListOf<Element>()
        var node = firstChild
        while (node != null) {
            if (node is Element && node.localOrName() == localName) out.add(node)
            node = node.nextSibling
        }
        return out
    }

    /** The first element below this one with [localName], at any depth. */
    private fun Element.descendant(localName: String): Element? {
        val list = getElementsByTagNameNS("*", localName)
        if (list.length > 0) return list.item(0) as Element
        // A parser that was not namespace aware
        val plain = getElementsByTagName(localName)
        return if (plain.length > 0) plain.item(0) as Element else null
    }

    /** The text of a direct child with [localName]. */
    private fun Element.text(localName: String): String? {
        var node = firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).localOrName() == localName) {
                return node.textContent?.takeIf { it.isNotBlank() }
            }
            node = node.nextSibling
        }
        return null
    }

    private fun Element.localOrName(): String = localName ?: nodeName.substringAfter(':')
}

/** A copy of this cache with other waypoints. */
fun Geocache.copyWith(waypoints: List<CacheWaypoint>?): Geocache = Geocache(
    code, source, name, lat, lng, kind, typeName, size, difficulty, terrain, owner, hint, summary,
    description, isHtml, url, placed, available, archived, foundOnSite, waypoints
)
