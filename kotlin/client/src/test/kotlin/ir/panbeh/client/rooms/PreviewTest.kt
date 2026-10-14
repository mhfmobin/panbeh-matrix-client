package ir.panbeh.client.rooms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewTest {
    private fun p(json: String, type: String = "m.room.message", sender: String = "علی") = previewText(type, Json.parseToJsonElement(json).jsonObject, sender)

    @Test
    fun `text strips the reply fallback`() {
        assertEquals("جواب", p("""{"msgtype":"m.text","body":"> <@a:x> سلام\n> بابا\n\nجواب"}"""))
        assertEquals("hi", p("""{"msgtype":"m.text","body":"hi"}"""))
    }

    @Test
    fun `media kinds`() {
        assertEquals("🖼 عکس", p("""{"msgtype":"m.image","body":"a.jpg"}"""))
        assertEquals("🖼 سلام", p("""{"msgtype":"m.image","body":"سلام","filename":"a.jpg"}"""))
        assertEquals("🖼 عکس", p("""{"msgtype":"m.image","body":"a.jpg","filename":"a.jpg"}"""))
        assertEquals("🎬 ویدیو", p("""{"msgtype":"m.video","body":"v.mp4"}"""))
        assertEquals("🎞 گیف", p("""{"msgtype":"m.video","body":"v.mp4","info":{"fi.mau.gif":true}}"""))
        assertEquals("📹 پیام ویدیویی ۰:۰۷", p("""{"msgtype":"m.video","body":"v","app.panbeh.video_note":true,"info":{"duration":7000}}"""))
        assertEquals("🎵 صدا", p("""{"msgtype":"m.audio","body":"a.mp3"}"""))
        assertEquals("🎤 پیام صوتی ۰:۱۲", p("""{"msgtype":"m.audio","body":"v","org.matrix.msc3245.voice":{},"info":{"duration":12000}}"""))
        assertEquals("🎤 پیام صوتی ۰:۰۳", p("""{"msgtype":"m.audio","body":"v","m.voice":{},"org.matrix.msc1767.audio":{"duration":3000}}"""))
        assertEquals("📍 موقعیت مکانی", p("""{"msgtype":"m.location","body":"x","geo_uri":"geo:1,2"}"""))
        assertEquals("📎 report.pdf", p("""{"msgtype":"m.file","body":"report.pdf","filename":"report.pdf"}"""))
        assertEquals("📎 شرح", p("""{"msgtype":"m.file","body":"شرح","filename":"report.pdf"}"""))
        assertEquals("* علی می‌رقصد", p("""{"msgtype":"m.emote","body":"می‌رقصد"}"""))
        assertEquals("🔐 درخواست تأیید هویت", p("""{"msgtype":"m.key.verification.request","body":"x"}"""))
    }

    @Test
    fun `stickers, polls and calls`() {
        assertEquals("استیکر", p("""{"body":"x"}""", "m.sticker"))
        assertEquals("📊 غذا؟", p("""{"org.matrix.msc3381.poll.start":{"question":{"org.matrix.msc1767.text":"غذا؟","body":"غذا؟"}}}""", "org.matrix.msc3381.poll.start"))
        assertEquals("📊 نظرسنجی", p("""{}""", "org.matrix.msc3381.poll.start"))
        assertEquals("📞 تماس تصویری", callPreview(Json.parseToJsonElement("""{"notification_type":"ring","m.call.intent":"video"}""").jsonObject))
        assertEquals("📞 تماس صوتی گروهی", callPreview(Json.parseToJsonElement("""{"notification_type":"notification","m.call.intent":"audio"}""").jsonObject))
    }
}
