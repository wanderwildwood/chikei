package com.kylecorry.trail_sense.tools.map.forage

/**
 * The edible plants Forage looks for in OpenStreetMap, by scientific name. An entry is a genus
 * ("Malus") or a species ("Prunus avium"); a species entry is for a genus with plants not worth
 * the picking among its edible ones, such as the flowering cherries. [names] are the common
 * names, in the same order, shown when a tree says only what it is in Latin.
 */
class ForageTaxa(scientific: List<String>, names: List<String>) {

    class Entry(val scientific: String, val name: String)

    private val entries: Map<String, Entry> =
        scientific.zip(names).associate { (s, n) -> s.lowercase() to Entry(s, n) }

    private val byName: Map<String, Entry> =
        entries.values.associateBy { it.name.lowercase() }

    /** Every genus with an entry, for the Overpass query. */
    val genera: List<String> = scientific.map { it.substringBefore(' ') }.distinct()

    /**
     * The entry for a scientific name as OSM or Falling Fruit writes it: "Prunus avium 'Stella'"
     * finds Prunus avium, "Malus" finds Malus, "Prunus" alone finds nothing. Fungi never match.
     */
    fun match(name: String?): Entry? {
        val words = clean(name) ?: return null
        if (Fungi.isFungus(words)) return null
        val parts = words.split(' ')
        val species = if (parts.size >= 2) "${parts[0]} ${parts[1]}".lowercase() else null
        return species?.let { entries[it] } ?: entries[parts[0].lowercase()]
    }

    /**
     * The entry for an OSM `produce` value: a common name ("apple", "apples", "cherry") or a
     * scientific one. Values are split on semicolons, the first that matches wins.
     */
    fun matchProduce(value: String?): Entry? {
        value ?: return null
        for (part in value.split(';')) {
            val word = part.trim().lowercase().replace('_', ' ')
            if (word.isEmpty()) continue
            val candidates = listOfNotNull(
                byName[word],
                byName[word.removeSuffix("s")],
                byName[word.removeSuffix("es")],
                if (word.endsWith("ies")) byName[word.removeSuffix("ies") + "y"] else null,
                match(part)
            )
            candidates.firstOrNull { !Fungi.isFungus(it.scientific) }?.let { return it }
        }
        return null
    }

    companion object {
        /** "Prunus avium 'Stella'" to "Prunus avium"; hybrids' "x" kept as a word. */
        fun clean(name: String?): String? {
            val words = name?.replace(Regex("['\"‘’].*$"), "")?.trim()
                ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            if (words.isNullOrEmpty()) return null
            return words.joinToString(" ")
        }

        fun genusOf(name: String?): String? =
            clean(name)?.substringBefore(' ')?.lowercase()?.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Mushrooms are kept off the map altogether. Topo does not say what is safe to eat, and with
 * fungi that is the whole question; the Mushroom Journal never says it either.
 *
 * Falling Fruit files most of its mushrooms under its Fungi kingdom, but not all (morels and
 * puffballs stand alone), so a type is also left out when its genus is one of these, or one
 * found under Fungi anywhere in Falling Fruit's own list.
 */
object Fungi {

    val GENERA: Set<String> = setOf(
        // Higher groups some entries are filed under
        "Fungi", "Basidiomycota", "Ascomycota", "Agaricomycetes", "Agaricales", "Boletales",
        "Polyporales", "Russulales", "Cantharellales", "Pezizales", "Lecanorales",
        // Gilled mushrooms
        "Agaricus", "Agrocybe", "Amanita", "Armillaria", "Calocybe", "Chlorophyllum", "Clitocybe",
        "Coprinellus", "Coprinopsis", "Coprinus", "Cortinarius", "Cyclocybe", "Entoloma",
        "Flammulina", "Galerina", "Gomphidius", "Gymnopus", "Hygrocybe", "Hygrophorus",
        "Hypholoma", "Inocybe", "Kuehneromyces", "Laccaria", "Lactarius", "Lactifluus",
        "Lentinula", "Lentinus", "Lepiota", "Lepista", "Leucoagaricus", "Macrolepiota",
        "Marasmius", "Mycena", "Panellus", "Parasola", "Paxillus", "Pholiota", "Pleurotus",
        "Pluteus", "Psathyrella", "Psilocybe", "Russula", "Stropharia", "Tricholoma",
        "Volvariella", "Volvopluteus",
        // Boletes
        "Boletus", "Butyriboletus", "Hortiboletus", "Imleria", "Leccinum", "Suillus",
        "Tylopilus", "Xerocomellus", "Xerocomus",
        // Chanterelles, tooth and coral fungi
        "Cantharellus", "Craterellus", "Clavaria", "Clavulina", "Artomyces", "Gomphus",
        "Hericium", "Hydnellum", "Hydnum", "Ramaria", "Sarcodon", "Sparassis",
        // Polypores and brackets
        "Albatrellus", "Cerioporus", "Fistulina", "Fomes", "Fomitopsis", "Ganoderma", "Grifola",
        "Inonotus", "Laetiporus", "Meripilus", "Piptoporus", "Polyporus", "Schizophyllum",
        "Trametes",
        // Puffballs, jelly fungi, cups, morels, truffles and the rest
        "Bovista", "Calvatia", "Lycoperdon", "Scleroderma", "Phallus", "Auricularia",
        "Pseudohydnum", "Tremella", "Gyromitra", "Helvella", "Morchella", "Peziza",
        "Sarcoscypha", "Tuber", "Verpa", "Hypomyces", "Ophiocordyceps", "Cordyceps", "Ustilago",
        // Lichens, which are fungi too
        "Cetraria", "Cladonia", "Umbilicaria", "Usnea"
    ).map { it.lowercase() }.toSet()

    private val COMMON = Regex(
        "\\b(mushrooms?|fung(us|i)|toadstools?|boletes?|puffballs?|morels?|truffles?|chanterelles?|lichens?)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Whether a scientific name belongs to a fungal genus or group. */
    fun isFungus(scientific: String?): Boolean {
        val genus = ForageTaxa.genusOf(scientific) ?: return false
        return genus.lowercase() in GENERA
    }

    /** A last check on common names, for entries with no scientific name at all. */
    fun soundsLikeFungus(commonName: String?): Boolean =
        commonName != null && COMMON.containsMatchIn(commonName)
}
