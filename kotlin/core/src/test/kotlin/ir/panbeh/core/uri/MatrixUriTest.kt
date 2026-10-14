package ir.panbeh.core.uri

import ir.panbeh.core.uri.Target.Action
import ir.panbeh.core.uri.Target.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatrixUriTest {
    @Test
    fun `matrix_to user, room, alias, event and via`() {
        assertEquals(Target(Kind.USER, "@a:x.org"), parseMatrixLink("https://matrix.to/#/@a:x.org"))
        assertEquals(Target(Kind.ROOM_ALIAS, "#r:x.org"), parseMatrixLink("https://matrix.to/#/%23r%3Ax.org"))
        assertEquals(
            Target(Kind.ROOM, "!id:x.org", "\$ev", listOf("a.org", "b.org")),
            parseMatrixLink("https://matrix.to/#/!id:x.org/\$ev?via=a.org&via=b.org"),
        )
        assertEquals("\$ev", parseMatrixLink("https://matrix.to/#/#r:x.org/\$ev")?.eventId)
    }

    @Test
    fun `matrix URIs`() {
        assertEquals(Target(Kind.USER, "@a:x.org", action = Action.CHAT), parseMatrixLink("matrix:u/a:x.org?action=chat"))
        assertEquals(Target(Kind.ROOM_ALIAS, "#r:x.org"), parseMatrixLink("matrix:r/r:x.org"))
        assertEquals(
            Target(Kind.ROOM, "!id:x.org", "\$ev", listOf("a.org"), Action.JOIN),
            parseMatrixLink("matrix:roomid/id:x.org/e/ev?via=a.org&action=join"),
        )
        assertEquals("\$ev", parseMatrixLink("matrix:r/r%3Ax.org/e/ev")?.eventId)
        assertEquals(Kind.USER, parseMatrixLink("MATRIX:u/a:x.org")?.kind)
    }

    @Test
    fun `rejects malformed and foreign links`() {
        for (s in listOf(
            "https://example.com/#/@a:x.org", "http://matrix.to/#/@a:x.org", "https://matrix.to/#/foo",
            "https://matrix.to/#/@a:x.org/\$ev", "https://matrix.to/#/!r:x/notevent", "https://matrix.to/#/!r:x/\$e/extra",
            "matrix:x/a:b", "matrix:u", "matrix:u/a:x/e/ev", "matrix:r/r:x/z/ev", "matrix:r/%E0%A4%A", "", "hello",
        )) assertNull(parseMatrixLink(s), s)
    }

    @Test
    fun `page hash form`() {
        assertEquals("\$ev", parseMatrixHash("#/!id:x.org/\$ev")?.eventId)
        assertNull(parseMatrixHash("#!id:x.org"))
    }

    @Test
    fun `matrixToLink round-trips through the parser, via picks the biggest servers`() {
        assertEquals(
            Target(Kind.ROOM, "!id:x.org", "\$ev/+=", listOf("a.org", "b.org")),
            parseMatrixLink(matrixToLink("!id:x.org", "\$ev/+=", listOf("a.org", "b.org"))),
        )
        assertEquals(Target(Kind.USER, "@a:x.org"), parseMatrixLink(matrixToLink("@a:x.org")))
        assertEquals(listOf("y.org", "x.org"), viaServers(listOf("@a:x.org", "@b:y.org", "@c:y.org")))
        assertEquals(listOf("y.org", "x.org"), viaServers(mapOf("x.org" to 1, "y.org" to 2)))
    }

    @Test
    fun `matrixToLink encoding matches encodeURIComponent and URLSearchParams`() {
        assertEquals("https://matrix.to/#/!id%3Ax.org/%24ev%2F%2B%3D?via=a.org&via=b+c", matrixToLink("!id:x.org", "\$ev/+=", listOf("a.org", "b c")))
    }
}
