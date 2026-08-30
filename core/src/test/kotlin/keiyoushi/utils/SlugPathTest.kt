package keiyoushi.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SlugPathTest {

    @Test
    fun `prefix only`() {
        val path = SlugPath("/novel/")
        assertEquals("some-slug", path.slug("/novel/some-slug"))
        assertEquals("/novel/some-slug", path.resolve("some-slug"))
    }

    @Test
    fun `prefix and suffix`() {
        val path = SlugPath("/", ".html")
        assertEquals("some-slug", path.slug("/some-slug.html"))
        assertEquals("/some-slug.html", path.resolve("some-slug"))
    }

    @Test
    fun `legacy full path values resolve unchanged`() {
        val path = SlugPath("/novel/")
        assertEquals("/novel/legacy-slug", path.resolve("/novel/legacy-slug"))
        // even a legacy value that no longer matches this source's current prefix must survive
        assertEquals("/old-prefix/legacy-slug", path.resolve("/old-prefix/legacy-slug"))
    }

    @Test
    fun `legacy absolute url values resolve unchanged`() {
        val path = SlugPath("/novel/")
        assertEquals("https://example.com/novel/legacy-slug", path.resolve("https://example.com/novel/legacy-slug"))
        assertEquals("http://example.com/novel/legacy-slug", path.resolve("http://example.com/novel/legacy-slug"))
    }

    @Test
    fun `absolute builds full url for a new-style slug`() {
        val path = SlugPath("/novel/")
        assertEquals("https://example.com/novel/some-slug", path.absolute("https://example.com", "some-slug"))
    }

    @Test
    fun `absolute does not double up a legacy absolute url`() {
        val path = SlugPath("/novel/")
        assertEquals(
            "https://example.com/novel/legacy-slug",
            path.absolute("https://example.com", "https://example.com/novel/legacy-slug"),
        )
    }
}
