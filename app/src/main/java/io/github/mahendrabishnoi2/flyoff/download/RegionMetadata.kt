package io.github.mahendrabishnoi2.flyoff.download

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What Flyoff stores with each MapLibre offline region (as the region's `ByteArray` metadata),
 * so the storage screen and the clean-up job can tell which flight and chunk a region is.
 *
 * [encode] writes small versioned UTF-8 text: a `flyoff-region 1` header line, then one
 * `key=value` line per field (`landedAt` only once it is known). [decode] reads it back, or returns null for anything it can't
 * read with certainty.
 */
data class RegionMetadata(
    /** Origin airport's IATA code, such as "LHR". */
    val origin: String,
    /** Destination airport's IATA code. */
    val destination: String,
    val kind: DownloadKind,
    /** This region's position in its plan, from 0. */
    val chunkIndex: Int,
    /** How many chunks the plan has. */
    val chunkCount: Int,
    val minZoom: Int,
    val maxZoom: Int,
    /** When the download was created, in epoch milliseconds. */
    val createdAtMillis: Long,
    /**
     * When Flyoff saw the flight land, in epoch milliseconds, or null until then (see
     * [LandingRule]). The region is deleted [CorridorRetention.KEEP_AFTER_LANDING_MILLIS] later.
     */
    val landedAtMillis: Long? = null,
    /** The tile URL template the region was downloaded from, pinned to one OpenFreeMap build. */
    val tileUrlTemplate: String,
) {
    init {
        require(IATA.matches(origin)) { "origin \"$origin\" is not an IATA code" }
        require(IATA.matches(destination)) { "destination \"$destination\" is not an IATA code" }
        require(chunkCount >= 1 && chunkIndex in 0 until chunkCount) { "chunk $chunkIndex of $chunkCount" }
        require(minZoom in 0..TileId.MAX_ZOOM && maxZoom in minZoom..TileId.MAX_ZOOM) { "zooms $minZoom..$maxZoom" }
        require(tileUrlTemplate.isNotEmpty() && tileUrlTemplate.none { it == '\n' || it == '\r' }) {
            "tile URL template must be one non-empty line"
        }
    }

    /** This metadata as bytes for MapLibre. */
    fun encode(): ByteArray = buildString {
        append(HEADER).append('\n')
        for ((key, value) in fields()) append(key).append('=').append(value).append('\n')
    }.toByteArray(Charsets.UTF_8)

    private fun fields(): List<Pair<String, String>> = listOfNotNull(
        "origin" to origin,
        "destination" to destination,
        "kind" to kind.name,
        "chunkIndex" to chunkIndex.toString(),
        "chunkCount" to chunkCount.toString(),
        "minZoom" to minZoom.toString(),
        "maxZoom" to maxZoom.toString(),
        "createdAt" to createdAtMillis.toString(),
        landedAtMillis?.let { "landedAt" to it.toString() },
        "tileUrlTemplate" to tileUrlTemplate,
    )

    companion object {
        private const val HEADER = "flyoff-region 1"
        private val IATA = Regex("[A-Z0-9]{3}")

        /**
         * The metadata in [bytes], or null when they are not version-1 Flyoff metadata: not
         * UTF-8, another header, a missing, repeated or invalid field. Unknown keys are ignored.
         */
        fun decode(bytes: ByteArray): RegionMetadata? {
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                return null
            }
            val lines = text.split('\n')
            if (lines.first() != HEADER || lines.last() != "") return null
            val values = HashMap<String, String>()
            for (line in lines.subList(1, lines.size - 1)) {
                val equals = line.indexOf('=')
                if (equals <= 0) return null
                if (values.put(line.substring(0, equals), line.substring(equals + 1)) != null) return null
            }
            return try {
                RegionMetadata(
                    origin = values.getValue("origin"),
                    destination = values.getValue("destination"),
                    kind = DownloadKind.valueOf(values.getValue("kind")),
                    chunkIndex = values.getValue("chunkIndex").toInt(),
                    chunkCount = values.getValue("chunkCount").toInt(),
                    minZoom = values.getValue("minZoom").toInt(),
                    maxZoom = values.getValue("maxZoom").toInt(),
                    createdAtMillis = values.getValue("createdAt").toLong(),
                    landedAtMillis = values["landedAt"]?.toLong(),
                    tileUrlTemplate = values.getValue("tileUrlTemplate"),
                )
            } catch (e: NoSuchElementException) {
                null
            } catch (e: IllegalArgumentException) {
                // Includes NumberFormatException and an unknown DownloadKind name.
                null
            }
        }
    }
}
