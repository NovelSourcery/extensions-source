package eu.kanade.tachiyomi.novelextension.en.novelshaven

import keiyoushi.utils.jsonInstance
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

/**
 * React Flight rows of an App Router page. Rows are split across several `self.__next_f.push`
 * scripts on this site, so the stream has to be joined before it can be parsed.
 */
class Flight(private val models: Map<String, JsonElement>, private val texts: Map<String, String>) {

    fun find(predicate: (JsonObject) -> Boolean): JsonObject? = models.values.firstNotNullOfOrNull { it.find(predicate) }

    /** Resolves a `$<id>` / `$<id>:<path>` reference; other values are returned unchanged. */
    fun resolve(value: JsonElement): JsonElement {
        val ref = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.length > 1 && it[0] == '$' && it[1] != '$' }
            ?: return value
        val segments = ref.substring(1).split(':')
        if (segments.size == 1) texts[segments[0]]?.let { return JsonPrimitive(it) }
        var current = models[segments[0]] ?: return value
        for (segment in segments.drop(1)) {
            current = resolve(current).child(segment) ?: return value
        }
        return resolve(current)
    }

    private fun JsonElement.child(segment: String): JsonElement? = when (this) {
        is JsonObject -> this[segment]
        is JsonArray -> {
            // React elements travel as ["$", type, key, props] tuples.
            val isElement = size >= 4 && (this[0] as? JsonPrimitive)?.contentOrNull == "$"
            val index = if (isElement) ELEMENT_FIELDS.indexOf(segment).takeIf { it >= 0 } else null
            getOrNull(index ?: segment.toIntOrNull() ?: -1)
        }
        else -> null
    }

    private fun JsonElement.find(predicate: (JsonObject) -> Boolean): JsonObject? = when (this) {
        is JsonObject -> if (predicate(this)) this else values.firstNotNullOfOrNull { it.find(predicate) }
        is JsonArray -> firstNotNullOfOrNull { it.find(predicate) }
        else -> null
    }

    companion object {
        private val ELEMENT_FIELDS = listOf("$", "type", "key", "props")
        private val PUSH_REGEX = Regex("""^\s*self\.__next_f\.push\((\[.*])\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL)
        private val ROW_ID_REGEX = Regex("""[0-9a-f]+""")

        fun parse(document: Document): Flight {
            val stream = document.select("script:not([src])").joinToString("") { script ->
                val push = PUSH_REGEX.find(script.data())?.groupValues?.get(1) ?: return@joinToString ""
                val chunk = jsonInstance.parseToJsonElement(push).jsonArray
                if (chunk[0].jsonPrimitive.contentOrNull == "1") chunk[1].jsonPrimitive.content else ""
            }
            return parseStream(stream)
        }

        private fun parseStream(stream: String): Flight {
            val models = mutableMapOf<String, JsonElement>()
            val texts = mutableMapOf<String, String>()
            var pos = 0
            while (pos < stream.length) {
                val colon = stream.indexOf(':', pos).takeIf { it != -1 } ?: break
                val id = stream.substring(pos, colon)
                pos = colon + 1
                // Hint rows (`:HL[...]`) carry no id.
                if (!ROW_ID_REGEX.matches(id)) {
                    pos = stream.indexOf('\n', pos).let { if (it == -1) stream.length else it + 1 }
                    continue
                }
                if (stream.getOrNull(pos) == 'T') {
                    // Text row: T<hex UTF-8 byte length>,<raw text> with no trailing newline.
                    val comma = stream.indexOf(',', pos)
                    val byteLength = stream.substring(pos + 1, comma).toInt(16)
                    pos = comma + 1
                    val start = pos
                    var bytes = 0
                    while (pos < stream.length && bytes < byteLength) {
                        val char = stream[pos]
                        bytes += when {
                            char.code < 0x80 -> 1
                            char.code < 0x800 -> 2
                            Character.isHighSurrogate(char) -> 4.also { pos++ }
                            else -> 3
                        }
                        pos++
                    }
                    texts[id] = stream.substring(start, pos)
                } else {
                    val end = stream.indexOf('\n', pos).let { if (it == -1) stream.length else it }
                    val row = stream.substring(pos, end)
                    // Module (I), hint (HL) and similar tagged rows aren't JSON models.
                    if (row.firstOrNull() in JSON_STARTS) models[id] = jsonInstance.parseToJsonElement(row)
                    pos = end + 1
                }
            }
            return Flight(models, texts)
        }

        private val JSON_STARTS = setOf('[', '{', '"')
    }
}
