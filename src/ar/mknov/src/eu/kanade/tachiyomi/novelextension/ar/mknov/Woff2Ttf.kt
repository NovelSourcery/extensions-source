package eu.kanade.tachiyomi.novelextension.ar.mknov

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Decoder for mknov.com's anti-scraping "Protected Font" woff2.
 *
 * The chapter page ships its (obfuscated) body text with a CSS @font-face pointing at a
 * per-request font `/fonts/protected-font-{N}.woff2`. Each body character maps through
 * that font's cmap to a glyph whose `post` table name is `uniXXXX` – the real Arabic
 * codepoint the author meant to display. `.empty` glyphs are word separators (spaces).
 *
 * The woff2 this server emits is non-standard: the compressed Brotli stream is the
 * tag-sorted payloads of the *untransformed* tables only (glyf/loca are transformed and
 * reconstructed by the encoder, so not present). There is no SFNT directory in the
 * stream. We therefore locate `cmap` and `post` by scanning:
 *
 *   - cmap: bytes parse as version=0, numTables in 1..8, first subtable format 4 or 12.
 *   - maxp: highest offset whose version is 0x00010000 and numGlyphs plausible; gives glyph count.
 *   - post: highest offset whose magic is 0x00020000 (format 2.0) and numGlyphs equals maxp's,
 *            i.e. the real `post` table (many 0x00020000 false positives exist in glyf data).
 *
 * The stream may itself be unaligned/quirk-offset → scan for the byte offset where Brotli
 * successfully decodes (usually the directory end + a small encoder-specific padding).
 */
object Woff2Decoder {

    data class DecodedFont(
        val cmap: Map<Int, Int>, // codepoint -> glyph id
        val post: Map<Int, String>, // glyph id -> glyph name
        val numGlyphs: Int,
    )

    /** Decompress the woff2 and extract a usable cmap/post mapping. */
    fun decode(woff2: ByteArray): DecodedFont {
        val sfnt = decompressStream(woff2)
        val numGlyphs = scanMaxpGlyphs(sfnt)
        val cmap = scanCmap(sfnt)
        val post = scanPost(sfnt, numGlyphs)
        return DecodedFont(cmap, post, numGlyphs)
    }

    private fun decompressStream(woff2: ByteArray): ByteArray {
        // The woff2 header's totalCompressedSize (offset 20) marks the exact end of the
        // brotli stream. Java's brotli decoder is strict about trailing bytes, so we must
        // not read past that boundary (a single extra byte makes it throw).
        val totalCompressed = if (woff2.size >= 24) getU32(woff2, 20) else woff2.size

        // Brotli stream starts somewhere after the header+directory; try each offset.
        for (off in 48 until minOf(woff2.size, 512)) {
            val out = ByteArrayOutputStream()
            try {
                // Slice exactly totalCompressed bytes so the decoder stops at stream end.
                val avail = minOf(off + totalCompressed, woff2.size) - off
                if (avail <= 0) continue
                val inStream = ByteArrayInputStream(woff2, off, avail)
                val brotli = org.brotli.dec.BrotliInputStream(inStream)
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = brotli.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                brotli.close()
                if (out.size() > 60_000) {
                    return out.toByteArray()
                }
            } catch (_: Throwable) {
                // try next offset
            }
        }
        throw IOException("No brotli stream found in woff2")
    }

    /** maxp: highest 0x00010000 with a plausible numGlyphs → glyph count. */
    private fun scanMaxpGlyphs(sfnt: ByteArray): Int {
        val n = sfnt.size
        var best = 0
        var i = 0
        while (i < n - 6) {
            if (getU32(sfnt, i) == 0x00010000) {
                val ng = getU16(sfnt, i + 4)
                if (ng in 100..4999) {
                    best = i
                }
            }
            i++
        }
        return if (best != 0) getU16(sfnt, best + 4) else 0
    }

    /** Scan for the cmap table; return codepoint → glyphId. Prefers format 12 over 4. */
    private fun scanCmap(sfnt: ByteArray): Map<Int, Int> {
        val n = sfnt.size
        var i = 0
        var bestBase = -1
        while (i < n - 260) {
            if (getU16(sfnt, i) == 0) {
                val numT = getU16(sfnt, i + 2)
                if (numT in 1..8) {
                    var ok = true
                    var hasFmt = false
                    for (j in 0 until numT) {
                        val off = getU32(sfnt, i + 4 + 8 * j + 4)
                        if (off < 0 || i + off + 4 > n - 2 * numT) {
                            ok = false
                            break
                        }
                        val f = getU16(sfnt, i + off)
                        if (f == 4 || f == 12) hasFmt = true
                    }
                    if (ok && hasFmt) {
                        bestBase = i
                        break
                    }
                }
            }
            i++
        }
        if (bestBase < 0) return emptyMap()
        val numT = getU16(sfnt, bestBase + 2)
        // prefer format 12, then 4
        var bestRank = 0
        var bestOff = -1
        for (j in 0 until numT) {
            val p = getU16(sfnt, bestBase + 4 + 8 * j)
            val e = getU16(sfnt, bestBase + 4 + 8 * j + 2)
            val off = getU32(sfnt, bestBase + 4 + 8 * j + 4)
            if (p == 0 || p == 3) {
                val f = getU16(sfnt, bestBase + off)
                val rank = if (f == 12) {
                    2
                } else if (f == 4) {
                    1
                } else {
                    0
                }
                if (rank > bestRank) {
                    bestRank = rank
                    bestOff = bestBase + off
                }
            }
        }
        if (bestOff < 0) return emptyMap()
        val fmt = getU16(sfnt, bestOff)
        return if (fmt == 12) parseFormat12(sfnt, bestOff) else parseFormat4(sfnt, bestOff)
    }

    private fun parseFormat4(sfnt: ByteArray, base: Int): Map<Int, Int> {
        val segCountX2 = getU16(sfnt, base + 6)
        val segCount = segCountX2 / 2
        val endCodes = IntArray(segCount)
        for (i in 0 until segCount) endCodes[i] = getU16(sfnt, base + 14 + 2 * i)
        var p = base + 14 + 2 * segCount + 2
        val startCodes = IntArray(segCount)
        for (i in 0 until segCount) {
            startCodes[i] = getU16(sfnt, p)
            p += 2
        }
        val idDeltas = IntArray(segCount)
        for (i in 0 until segCount) {
            idDeltas[i] = getU16(sfnt, p)
            p += 2
        }
        val idRangeOffsetStart = p
        val result = HashMap<Int, Int>()
        for (i in 0 until segCount) {
            val start = startCodes[i]
            val end = endCodes[i]
            val delta = idDeltas[i]
            val roff = getU16(sfnt, p + 2 * i)
            for (c in start..end) {
                if (c == 0xFFFF) continue
                val gid: Int = if (roff == 0) {
                    (c + delta) and 0xFFFF
                } else {
                    val addr = idRangeOffsetStart + roff + 2 * (c - start)
                    if (addr + 2 > sfnt.size) 0 else getU16(sfnt, addr)
                }
                if (gid != 0) result[c] = gid
            }
        }
        return result
    }

    private fun parseFormat12(sfnt: ByteArray, base: Int): Map<Int, Int> {
        val nGroups = getU32(sfnt, base + 12)
        val result = HashMap<Int, Int>()
        var p = base + 16
        for (i in 0 until nGroups) {
            val startChar = getU32(sfnt, p)
            val endChar = getU32(sfnt, p + 4)
            val startGlyph = getU32(sfnt, p + 8)
            var gid = startGlyph
            for (c in startChar..endChar) {
                result[c] = gid
                gid++
            }
            p += 12
        }
        return result
    }

    /** post: highest 0x00020000 whose numGlyphs == glyphCount. */
    private fun scanPost(sfnt: ByteArray, numGlyphs: Int): Map<Int, String> {
        if (numGlyphs <= 0) return emptyMap()
        val n = sfnt.size
        var best = -1
        var i = 0
        while (i < n - 40) {
            if (getU32(sfnt, i) == 0x00020000) {
                val ng = getU16(sfnt, i + 32)
                if (ng == numGlyphs && i + 34 + 2 * ng <= n) {
                    best = i
                }
            }
            i++
        }
        if (best < 0) return emptyMap()
        return parsePost(sfnt, best)
    }

    private fun parsePost(sfnt: ByteArray, base: Int): Map<Int, String> {
        val numGlyphs = getU16(sfnt, base + 32)
        val idx = IntArray(numGlyphs)
        var p = base + 34
        for (g in 0 until numGlyphs) {
            idx[g] = getU16(sfnt, p)
            p += 2
        }
        val custom = ArrayList<String>()
        var needsCustom = false
        for (gi in idx) {
            if (gi >= 258) {
                needsCustom = true
                break
            }
        }
        if (needsCustom) {
            while (p < sfnt.size - 1) {
                val len = sfnt[p].toInt() and 0xFF
                p++
                if (p + len > sfnt.size) break
                val s = String(sfnt, p, len, Charsets.UTF_8)
                custom.add(s)
                p += len
            }
        }
        val out = HashMap<Int, String>(numGlyphs)
        var ci = 0
        for (g in 0 until numGlyphs) {
            val gi = idx[g]
            out[g] = if (gi < 258) {
                STANDARD_NAMES[gi]
            } else {
                if (ci < custom.size) custom[ci++] else "g$gi"
            }
        }
        return out
    }

    private fun getU16(b: ByteArray, i: Int): Int {
        if (i + 1 >= b.size) return 0
        return ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    }

    private fun getU32(b: ByteArray, i: Int): Int {
        if (i + 3 >= b.size) return 0
        return ((b[i].toInt() and 0xFF) shl 24) or
            ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or
            (b[i + 3].toInt() and 0xFF)
    }

    // Standard Macintosh order glyph names (post table format 1/2/3). Index 0-based.
    private val STANDARD_NAMES: List<String> = listOf(
        ".notdef", ".null", "nonmarkingreturn", "space", "exclam", "quotedbl", "numbersign",
        "dollar", "percent", "ampersand", "quotesingle", "parenleft", "parenright", "asterisk",
        "plus", "comma", "hyphen", "period", "slash", "zero", "one", "two", "three", "four",
        "five", "six", "seven", "eight", "nine", "colon", "semicolon", "less", "equal",
        "greater", "question", "at", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K",
        "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "bracketleft",
        "backslash", "bracketright", "asciicircum", "underscore", "grave", "a", "b", "c", "d",
        "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u",
        "v", "w", "x", "y", "z", "braceleft", "bar", "braceright", "asciitilde", "Adieresis",
        "Aring", "Ccedilla", "Eacute", "Ntilde", "Odieresis", "Udieresis", "aacute", "agrave",
        "acircumflex", "adieresis", "atilde", "aring", "ccedilla", "eacute", "egrave",
        "ecircumflex", "edieresis", "iacute", "igrave", "icircumflex", "idieresis", "ntilde",
        "oacute", "ograve", "ocircumflex", "odieresis", "otilde", "uacute", "ugrave",
        "ucircumflex", "udieresis", "dagger", "degree", "cent", "sterling", "section", "bullet",
        "paragraph", "germandbls", "registered", "copyright", "trademark", "acute", "dieresis",
        "notequal", "AE", "Oslash", "infinity", "plusminus", "lessequal", "greaterequal",
        "yen", "mu", "partialdiff", "summation", "product", "pi", "integral", "ordfeminine",
        "ordmasculine", "Omega", "ae", "oslash", "questiondown", "exclamdown", "logicalnot",
        "radical", "florin", "approxequal", "Delta", "guillemotleft", "guillemotright",
        "ellipsis", "nonbreakingspace", "Agrave", "Atilde", "Otilde", "OE", "oe", "endash",
        "emdash", "quotedblleft", "quotedblright", "quoteleft", "quoteright", "divide",
        "lozenge", "ydieresis", "Ydieresis", "fraction", "currency", "guilsinglleft",
        "guilsinglright", "fi", "fl", "daggerdbl", "periodcentered", "quotesinglbase",
        "quotedblbase", "perthousand", "Acircumflex", "Ecircumflex", "Aacute", "Edieresis",
        "Egrave", "Iacute", "Icircumflex", "Idieresis", "Igrave", "Oacute", "Ocircumflex",
        "apple", "Ograve", "Uacute", "Ucircumflex", "Ugrave", "dotlessi", "circumflex",
        "tilde", "macron", "breve", "dotaccent", "ring", "cedilla", "hungarumlaut", "ogonek",
        "caron", "Lslash", "lslash", "Scaron", "scaron", "Zcaron", "zcaron", "brokenbar",
        "Eth", "eth", "Yacute", "yacute", "Thorn", "thorn", "minus", "multiply", "onesuperior",
        "twosuperior", "threesuperior", "onehalf", "onequarter", "threequarters", "franc",
        "Gbreve", "gbreve", "Idotaccent", "Scedilla", "scedilla", "Cacute", "cacute", "Ccaron",
        "ccaron", "dcroat",
    )
}

/** Resolve a decoded glyph name into the real character. Returns null for meaningless names. */
fun decodeGlyphName(name: String): Char? {
    if (name == "space" || name == "space*") return ' '
    if (name == "nonmarkingreturn") return '\n'
    if (name == ".empty") return ' '
    if (name.startsWith("uni")) {
        val hex = name.substring(3, minOf(9, name.length)).takeWhile { it in "0123456789abcdefABCDEF" }
        if (hex.isNotEmpty()) {
            val cp = hex.toIntOrNull(16) ?: return null
            if (cp in 1..0x10FFFF && !Character.isSurrogate(cp.toChar())) {
                return cp.toChar()
            }
        }
        return null
    }
    return when (name) {
        "exclam" -> '!'
        "quotedbl" -> '"'
        "numbersign" -> '#'
        "dollar" -> '$'
        "percent" -> '%'
        "ampersand" -> '&'
        "quotesingle" -> '\''
        "parenleft" -> '('
        "parenright" -> ')'
        "asterisk" -> '*'
        "plus" -> '+'
        "comma" -> ','
        "hyphen" -> '-'
        "period" -> '.'
        "slash" -> '/'
        "zero" -> '0'
        "one" -> '1'
        "two" -> '2'
        "three" -> '3'
        "four" -> '4'
        "five" -> '5'
        "six" -> '6'
        "seven" -> '7'
        "eight" -> '8'
        "nine" -> '9'
        "colon" -> ':'
        "semicolon" -> ';'
        "less" -> '<'
        "equal" -> '='
        "greater" -> '>'
        "question" -> '?'
        "at" -> '@'
        "bracketleft" -> '['
        "backslash" -> '\\'
        "bracketright" -> ']'
        "asciicircum" -> '^'
        "underscore" -> '_'
        "grave" -> '`'
        "braceleft" -> '{'
        "bar" -> '|'
        "braceright" -> '}'
        "asciitilde" -> '~'
        "guillemotleft" -> '«'
        "guillemotright" -> '»'
        "ellipsis" -> '…'
        "emdash" -> '—'
        "endash" -> '–'
        "bullet" -> '•'
        else -> null
    }
}
