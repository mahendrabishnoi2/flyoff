package io.github.mahendrabishnoi2.flyoff.airports

import java.text.Normalizer
import java.util.Locale

/**
 * Offline airport search for the origin and destination picker.
 *
 * Matching ignores case, accents and punctuation ("zurich" finds Zürich, "ohare"
 * finds O'Hare). Every word of the query must start a word of the airport's
 * city, name, IATA or ICAO code, or of its [Airport.keywords] ("new york",
 * "heath", "nyc"). Results are ranked:
 *
 * 1. the query is the IATA code;
 * 2. the query is the ICAO code;
 * 3. the city or the name starts with the query;
 * 4. a keyword starts with the query ("tokyo" finds Narita by its keyword "Tokyo");
 * 5. every query word starts a word of the city, name or codes;
 * 6. every query word starts a word of those or of the keywords.
 *
 * So a keyword match never outranks a city or name match: "new york" lists JFK
 * and LaGuardia (city New York) before Newark (keyword "New York City").
 *
 * Within a rank, larger airports ([AirportSize]) come first, then airports in
 * the order of the list. The bundled file lists bigger airports (longer runway)
 * first, which puts Heathrow ahead of Gatwick for "london".
 *
 * Everything that can be is normalised once, up front, so a search is a scan of
 * a few thousand short strings: cheap enough for every keystroke.
 */
class AirportSearch(airports: List<Airport>) {

    private class Entry(val airport: Airport, val order: Int) {
        val iata = airport.iata.lowercase(Locale.ROOT)
        val icao = airport.icao?.lowercase(Locale.ROOT).orEmpty()
        val city = normalize(airport.city)
        val name = normalize(airport.name)
        val words: Array<String> =
            (city.split(' ') + name.split(' ') + iata + icao).filter { it.isNotEmpty() }.distinct().toTypedArray()
        val keywords: Array<String> = airport.keywords.map(::normalize).filter { it.isNotEmpty() }.toTypedArray()
        val keywordWords: Array<String> =
            keywords.flatMap { it.split(' ') }.filter { it !in words }.distinct().toTypedArray()
    }

    private val entries = airports.mapIndexed { i, airport -> Entry(airport, i) }

    private val byCode = HashMap<String, Airport>(airports.size * 2).apply {
        for (airport in airports) putIfAbsent(airport.iata.uppercase(Locale.ROOT), airport)
    }

    private val byIcaoCode = HashMap<String, Airport>(airports.size * 2).apply {
        for (airport in airports) airport.icao?.let { putIfAbsent(it.uppercase(Locale.ROOT), airport) }
    }

    /** The airport with this IATA code, ignoring case and surrounding spaces. */
    fun byIata(code: String): Airport? = byCode[code.trim().uppercase(Locale.ROOT)]

    /** The airport with this ICAO code, ignoring case and surrounding spaces. */
    fun byIcao(code: String): Airport? = byIcaoCode[code.trim().uppercase(Locale.ROOT)]

    /**
     * The airport a route's code names: four characters are an ICAO code (EGLL), three an IATA
     * code (LHR). Null for anything else, or a code no bundled airport has.
     */
    fun byRouteCode(code: String): Airport? = when (code.trim().length) {
        4 -> byIcao(code)
        3 -> byIata(code)
        else -> null
    }

    /** Up to [limit] airports matching [query], best first. A blank query finds nothing. */
    fun search(query: String, limit: Int = 20): List<Airport> {
        val text = normalize(query)
        if (text.isEmpty() || limit <= 0) return emptyList()
        val tokens = text.split(' ')
        val code = text.replace(" ", "")

        val matches = ArrayList<Pair<Int, Entry>>()
        for (entry in entries) {
            val rank = rank(entry, text, code, tokens) ?: continue
            matches += rank to entry
        }
        return matches
            .sortedWith(compareBy({ it.first }, { it.second.airport.size.ordinal }, { it.second.order }))
            .take(limit)
            .map { it.second.airport }
    }

    private fun rank(entry: Entry, text: String, code: String, tokens: List<String>): Int? = when {
        code == entry.iata -> 0
        code == entry.icao -> 1
        entry.city.startsWith(text) || entry.name.startsWith(text) -> 2
        entry.keywords.any { it.startsWith(text) } -> 3
        tokens.all { token -> entry.words.any { it.startsWith(token) } } -> 4
        tokens.all { token -> entry.words.any { it.startsWith(token) } || entry.keywordWords.any { it.startsWith(token) } } -> 5
        else -> null
    }

    internal companion object {
        /** Letters that Unicode decomposition leaves alone but a person types in ASCII. */
        private val folds = mapOf(
            'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'đ' to "d", 'ð' to "d",
            'ł' to "l", 'þ' to "th", 'ı' to "i", 'ħ' to "h",
        )

        /** Apostrophes and okina join a word instead of splitting it: O'Hare is "ohare". */
        private const val JOINERS = "'`´ʹʻʼʽ‘’′"

        /** Lower case, no accents, only letters and digits, words separated by single spaces. */
        fun normalize(text: String): String {
            val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            val out = StringBuilder(decomposed.length)
            var gap = false
            for (c in decomposed) {
                when {
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() || c in JOINERS -> Unit
                    c.isLetterOrDigit() -> {
                        if (gap && out.isNotEmpty()) out.append(' ')
                        gap = false
                        out.append(folds[c] ?: c)
                    }
                    else -> gap = true
                }
            }
            return out.toString()
        }
    }
}
