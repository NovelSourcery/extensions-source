package eu.kanade.tachiyomi.novelextension.all.lunarxnovels

import android.util.Base64
import keiyoushi.utils.extractNextJs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

// Seed decoding and token minting are shared with the site's manga reader (see Lunar Manga in
// keiyoushi/extensions-source); only the chapter endpoint and session key differ for novels.

/**
 * The two seed strings every chapter token and session key is derived from.
 */
class LunarSeeds(val axis: String, val pitch: String)

/**
 * The seeds are not a field of their own: they arrive as the props of a reader component,
 * under names randomised per response. One prop holds a masked, reversed-base64 header that
 * names the props carrying the payload; the payload bytes are then chained through a small
 * byte program described by that same header.
 *
 * Several decoy prop sets are rendered alongside the real one, so [toSeeds] is also what
 * identifies it - a decoy either fails to produce a header or misses the magic prefix.
 */
@Serializable(with = LunarSeedPropsDto.Serializer::class)
class LunarSeedPropsDto(private val props: Map<String, String>) {

    fun toSeeds(): LunarSeeds? {
        for ((name, value) in props) {
            val header = parseHeader(name, value) ?: continue
            val bytes = header.decode(props) ?: continue

            if (bytes.size < HEADER_SIZE) continue
            if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1 || bytes[2] != MAGIC_2) return null

            val axisLength = bytes[3] shl 8 or bytes[4]
            val pitchLength = bytes[5] shl 8 or bytes[6]
            if (axisLength <= 0 || pitchLength <= 0 || HEADER_SIZE + axisLength + pitchLength > bytes.size) return null

            return LunarSeeds(
                axis = bytes.toLatin1(HEADER_SIZE, axisLength),
                pitch = bytes.toLatin1(HEADER_SIZE + axisLength, pitchLength),
            )
        }
        return null
    }

    /** The header prop is masked with a rolling key derived from its own name. */
    private fun parseHeader(name: String, value: String): SeedHeader? {
        val decoded = try {
            Base64.decode(value.reversed(), Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }

        var nameHash = 0
        for (char in name) nameHash = 31 * nameHash + char.code and 0xFF

        val unmasked = String(
            CharArray(decoded.size) { i ->
                ((decoded[i].toInt() and 0xFF) xor (nameHash + 37 * i and 0xFF)).toChar()
            },
        )

        val parts = unmasked.split("|")
        if (parts.size != HEADER_FIELDS || parts[0] != HEADER_VERSION) return null

        val seed = parts[1].toIntOrNull(16) ?: return null
        val multiplier = parts[2].toIntOrNull(16) ?: return null
        val increment = parts[3].toIntOrNull(16) ?: return null

        val programText = parts[4]
        if (programText.isEmpty() || programText.length % 3 != 0) return null

        val program = buildList {
            for (i in programText.indices step 3) {
                val op = programText.substring(i, i + 1).toIntOrNull(16) ?: return null
                val arg = programText.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                if (op > MAX_OPCODE) return null
                add(op to arg)
            }
        }

        val names = parts[5].split(".").filter { it.isNotEmpty() }
        if (names.isEmpty()) return null

        return SeedHeader(seed, multiplier, increment, program, names)
    }

    private class SeedHeader(
        private val seed: Int,
        private val multiplier: Int,
        private val increment: Int,
        private val program: List<Pair<Int, Int>>,
        private val names: List<String>,
    ) {
        /**
         * The named props concatenate into one hex string, each byte chained to the previous
         * one and to an LCG keyed by the header, then run backwards through the program.
         */
        fun decode(props: Map<String, String>): List<Int>? {
            val hex = names.joinToString("") { props[it].orEmpty() }
            if (hex.length < 2 || hex.length % 2 != 0) return null

            val out = ArrayList<Int>(hex.length / 2)
            var state = seed and 0xFF
            var previous = 0

            for (i in hex.indices step 2) {
                val current = hex.substring(i, i + 2).toIntOrNull(16) ?: return null
                state = state * multiplier + increment and 0xFF
                out.add(unprogram(current xor previous, i / 2, state))
                previous = current
            }
            return out
        }

        private fun unprogram(value: Int, index: Int, state: Int): Int {
            var n = value and 0xFF
            for ((op, arg) in program.asReversed()) {
                n = when (op) {
                    0 -> n xor arg
                    1 -> n - arg
                    2 -> {
                        val shift = (arg and 7).takeIf { it != 0 } ?: 1
                        (n and 0xFF) ushr shift or (n shl 8 - shift)
                    }
                    3 -> (n and 15) shl 4 or ((n and 0xFF) ushr 4)
                    4 -> n xor state
                    5 -> n xor (index * (1 or arg) + arg and 0xFF)
                    6 -> n.inv()
                    else -> arg - n
                } and 0xFF
            }
            return n
        }
    }

    private fun List<Int>.toLatin1(offset: Int, length: Int) = String(CharArray(length) { this[offset + it].toChar() })

    /** The prop names change per response, so the object can only be modelled as a map. */
    object Serializer : KSerializer<LunarSeedPropsDto> {
        private val delegate = MapSerializer(String.serializer(), String.serializer())

        override val descriptor: SerialDescriptor get() = delegate.descriptor

        override fun deserialize(decoder: Decoder) = LunarSeedPropsDto(delegate.deserialize(decoder))

        override fun serialize(encoder: Encoder, value: LunarSeedPropsDto) = delegate.serialize(encoder, value.props)
    }

    companion object {
        private const val HEADER_SIZE = 7
        private const val HEADER_FIELDS = 6
        private const val HEADER_VERSION = "3"
        private const val MAX_OPCODE = 7
        private const val MAGIC_0 = 167
        private const val MAGIC_1 = 62
        private const val MAGIC_2 = 145

        /**
         * Matches the flight element holding the seeds. Decoys share the shape, so the payload
         * has to be decoded to tell them apart.
         */
        fun matches(element: JsonElement): Boolean = element is JsonObject &&
            element.isNotEmpty() &&
            element.values.all { it is JsonPrimitive && it.isString } &&
            LunarSeedPropsDto(element.mapValues { (_, value) -> value.jsonPrimitive.content }).toSeeds() != null
    }
}

object LunarCrypto {

    fun extractSeeds(document: Document): LunarSeeds = document.extractNextJs<LunarSeedPropsDto>(LunarSeedPropsDto::matches)?.toSeeds()
        ?: error("Failed to find payload seeds")

    /** A timestamped payload naming the chapter, stream-encrypted with a key blended from both seeds. */
    fun mint(seeds: LunarSeeds, slug: String, chapter: String): String {
        val key = blend(seeds.axis, seeds.pitch)
        val payload = "${(System.currentTimeMillis() / 1000).toString(16)}|${randomString(12)}|$slug|$chapter|${randomString(6)}"

        val offset = Random.nextInt(256)
        val out = ByteArray(payload.length + 1)
        out[0] = offset.toByte()
        payload.forEachIndexed { i, char ->
            out[i + 1] = (char.code xor key[(i + offset) % key.size] xor (offset + 83 * i and 0xFF)).toByte()
        }
        return Base64.encodeToString(out, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    private fun blend(axis: String, pitch: String): IntArray {
        val digest = sha256(axis + "\u0001" + pitch)
        return IntArray(maxOf(axis.length, pitch.length)) { i ->
            axis[i % axis.length].code xor
                pitch[i % pitch.length].code xor
                (digest[i % 32].toInt() and 0xFF) xor
                (83 * i + 29 and 0xFF) and 0xFF
        }
    }

    /** Novel chapter `session_data` is AES-CBC keyed by SHA-256 of the first seed, with a zero IV. */
    fun decryptSession(sessionData: String, seeds: LunarSeeds): String = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(sha256(seeds.axis), "AES"), IvParameterSpec(ByteArray(16)))
        String(doFinal(Base64.decode(sessionData, Base64.DEFAULT)), Charsets.UTF_8)
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    private fun randomString(length: Int) = String(CharArray(length) { ALPHABET[Random.nextInt(ALPHABET.length)] })

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
}
