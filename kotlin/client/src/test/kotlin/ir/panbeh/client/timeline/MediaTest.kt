package ir.panbeh.client.timeline

import de.connect2x.trixnity.client.media
import de.connect2x.trixnity.core.model.events.m.room.EncryptedFile
import de.connect2x.trixnity.utils.toByteArray
import de.connect2x.trixnity.utils.toByteArrayFlow
import ir.panbeh.client.testServer
import ir.panbeh.core.timeline.Row
import ir.panbeh.core.util.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTest {
    private fun png(seed: Int) = ByteArray(200) { (it * 7 + seed).toByte() }
    private fun file(name: String, mime: String, bytes: ByteArray, info: MediaInfo = MediaInfo(mime, bytes.size.toLong())) = FileToSend(name, info) { bytes.toByteArrayFlow() }

    @Test
    fun `encrypted upload and download`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(prefix = "me")
        try {
            val q = UploadQueue(d.a, d.scope, d.sa)
            val tb = d.timelineB().also { it.open() }
            val bytes = ByteArray(5000) { (it % 251).toByte() }
            q.enqueue(d.roomId, file("notes.txt", "text/plain", bytes), caption = "my notes")
            val got = tb.item("file") { it.msgtype == "m.file" }
            assertEquals("my notes", got.content.string("body"))
            assertEquals("notes.txt", got.content.string("filename"))
            assertNull(got.content["url"])
            val enc = got.content["file"]!!.jsonObject
            assertTrue(enc.string("url")!!.startsWith("mxc://"))
            assertEquals("text/plain", enc.string("mimetype"))
            assertEquals(5000, (got.content["info"]!!.jsonObject["size"] as JsonPrimitive).content.toInt())
            val file = d.b.api.json.decodeFromJsonElement(EncryptedFile.serializer(), enc)
            val plain = d.b.media.getEncryptedMedia(file, maxSize = null).getOrThrow().toByteArray()!!
            assertContentEquals(bytes, plain)
            assertTrue(q.uploads.value.isEmpty())

            // a video with a poster: both encrypted
            val v = MediaInfo("video/mp4", 300, 320, 240, 4000, "LEHV6nWB2yk8pyo0adR*.7kCMdnj", Thumbnail(png(1), 320, 240))
            q.enqueue(d.roomId, file("clip.mp4", "video/mp4", png(2), v))
            val vid = tb.item("video") { it.msgtype == "m.video" }
            val info = vid.content["info"]!!.jsonObject
            assertNotNull(info["thumbnail_file"])
            assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", info.string(BLURHASH))
            assertEquals("320", (info["w"] as JsonPrimitive).content)
            assertEquals("4000", (info["duration"] as JsonPrimitive).content)
            val thumb = d.b.api.json.decodeFromJsonElement(EncryptedFile.serializer(), info["thumbnail_file"]!!.jsonObject)
            assertContentEquals(png(1), d.b.media.getEncryptedMedia(thumb, maxSize = null).getOrThrow().toByteArray()!!)

            // a voice message
            q.enqueue(d.roomId, file("voice.ogg", "audio/ogg", png(3)), extra = MediaExtras.voice(2500, List(40) { it * 25 }))
            val voice = tb.item("voice") { it.msgtype == "m.audio" }
            assertEquals("پیام صوتی", voice.content.string("body"))
            assertEquals(40, voice.content["org.matrix.msc1767.audio"]!!.jsonObject["waveform"]!!.jsonArray.size)
            assertNotNull(voice.content["org.matrix.msc3245.voice"])
            assertTrue(voice.preview.startsWith("🎤 پیام صوتی"))
        } finally { d.close() }
    }

    @Test
    fun `plain upload in an unencrypted room, album tags group into one row, reply goes on the first`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(encrypted = false, prefix = "al")
        try {
            val q = UploadQueue(d.a, d.scope, d.sa)
            val ta = d.timelineA().also { it.open() }
            val tb = d.timelineB().also { it.open() }
            d.sb.sendText(d.roomId, "look")
            val target = ta.item("target") { it.content.string("body") == "look" }
            val files = List(3) { file("p$it.png", "image/png", png(it), MediaInfo("image/png", 200, 100 + it, 50)) }
            q.sendFiles(d.roomId, files, caption = "three photos", replyTo = target.replyTarget())
            val items = eventually("album") { tb.state.value.items.filter { it.msgtype == "m.image" && it.eventId != null && it.sendState == null }.takeIf { it.size == 3 } }
            assertTrue(items.all { it.album?.startsWith("t:") == true && it.album == items[0].album })
            assertTrue(items.all { it.content.string("url")!!.startsWith("mxc://") && it.content["file"] == null })
            val ids = items.map { (it.content["app.panbeh.album"] as JsonObject).string("id") }.toSet()
            assertEquals(1, ids.size)
            assertEquals(listOf(0, 1, 2), items.map { ((it.content["app.panbeh.album"] as JsonObject)["i"] as JsonPrimitive).content.toInt() })
            // the reply on the first, the caption on the last
            assertEquals(target.eventId, items[0].replyTo)
            assertNull(items[1].replyTo)
            assertEquals("three photos", items[2].content.string("body"))
            assertEquals("p0.png", items[0].content.string("body"))
            val row = eventually("album row") { tb.state.value.rows.filterIsInstance<Row.Album>().firstOrNull() }
            assertEquals(3, row.ids.size)
            // edit the caption: the file stays, the body is the new caption
            val own = ta.item("own caption") { it.eventId == items[2].eventId }
            d.sa.edit(d.roomId, own, "new caption")
            val recaptioned = tb.item("recaptioned") { it.eventId == items[2].eventId && it.content.string("body") == "new caption" }
            assertTrue(recaptioned.edited)
            assertEquals(items[2].content.string("url"), recaptioned.content.string("url"))
            assertEquals("p2.png", recaptioned.content.string("filename"))
            assertEquals("m.image", recaptioned.msgtype)
            // delete the whole album
            d.sa.redact(d.roomId, items)
            eventually("album gone") { if (tb.state.value.items.none { it.msgtype == "m.image" }) true else null }
        } finally { d.close() }
    }

    @Test
    fun `failed uploads can be retried, running ones cancelled`() = runBlocking {
        testServer ?: return@runBlocking
        val d = Duo.create(encrypted = false, prefix = "up")
        try {
            val q = UploadQueue(d.a, d.scope, d.sa)
            val tb = d.timelineB().also { it.open() }
            var tries = 0
            val flaky = FileToSend("flaky.bin", MediaInfo("application/octet-stream", 10)) {
                if (tries++ == 0) flow { throw java.io.IOException("boom") } else ByteArray(10).toByteArrayFlow()
            }
            val id = q.enqueue(d.roomId, flaky)
            val failed = eventually("failed") { q.uploads.value.firstOrNull { it.id == id && it.phase == UploadPhase.FAILED } }
            assertNotNull(failed.error)
            q.retry(id)
            tb.item("retried") { it.content.string("body") == "flaky.bin" }
            assertTrue(q.uploads.value.isEmpty())

            // sequential: the second waits for the first; cancelling the first lets the second go
            val slow = FileToSend("slow.bin", MediaInfo("application/octet-stream", 10)) { flow { delay(60_000); emit(ByteArray(1)) } }
            val s1 = q.enqueue(d.roomId, slow)
            val s2 = q.enqueue(d.roomId, file("second.bin", "application/octet-stream", ByteArray(10)))
            eventually("first running") { q.uploads.value.firstOrNull { it.id == s1 && it.phase == UploadPhase.UPLOADING } }
            assertEquals(UploadPhase.QUEUED, q.uploads.value.first { it.id == s2 }.phase)
            q.cancel(s1)
            tb.item("second sent") { it.content.string("body") == "second.bin" }
            assertTrue(tb.state.value.items.none { it.content.string("body") == "slow.bin" })
        } finally { d.close() }
    }
}
