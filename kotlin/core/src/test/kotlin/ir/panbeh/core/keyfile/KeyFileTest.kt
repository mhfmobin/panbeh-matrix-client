package ir.panbeh.core.keyfile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyFileTest {
    private val element = "-----BEGIN MEGOLM SESSION DATA-----\nAXNhbHRzYWx0c2FsdHNhbHSIiIiIiIiIiIiIiIiIiIiIAAAACmIRUW2OjZ3L2l6j9h0lHlV3M2dx\ncissyYBxjsfsAndErh065A8=\n-----END MEGOLM SESSION DATA-----"

    @Test
    fun `Megolm key file - Element's test vector, round trip, wrong passphrase`() {
        assertEquals("plain", decryptKeyFile(element, "password"))
        val json = """[{"session_id":"s","room_id":"!r:hs","text":"${"سلام ".repeat(100)}"}]"""
        val file = encryptKeyFile(json, "رمز", 1000)
        assertEquals(json, decryptKeyFile(file, "رمز"))
        val e = assertFailsWith<KeyFileException.WrongPassphrase> { decryptKeyFile(file, "wrong") }
        assertTrue(e.message!!.contains("عبارت عبور"))
    }

    @Test
    fun `armor layout and line wrapping`() {
        val file = encryptKeyFile("x".repeat(500), "p", 10)
        val lines = file.split("\n")
        assertEquals("-----BEGIN MEGOLM SESSION DATA-----", lines.first())
        assertEquals(listOf("-----END MEGOLM SESSION DATA-----", ""), lines.takeLast(2))
        assertTrue(lines.subList(1, lines.size - 2).all { it.length <= 96 })
        assertEquals(96, lines[1].length)
    }

    @Test
    fun `bad inputs`() {
        assertFailsWith<KeyFileException.NotAKeyFile> { decryptKeyFile("hello", "p") }
        assertFailsWith<KeyFileException.UnsupportedVersion> {
            decryptKeyFile("-----BEGIN MEGOLM SESSION DATA-----\nAAAA\n-----END MEGOLM SESSION DATA-----", "p")
        }
    }
}
