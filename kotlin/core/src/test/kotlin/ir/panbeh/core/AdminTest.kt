package ir.panbeh.core

import ir.panbeh.core.admin.*
import kotlin.test.*

class AdminTest {
    private val inf = Double.POSITIVE_INFINITY

    @Test fun `power level settings`() {
        val pl = PowerLevels(kick = 100.0, events = mapOf("m.room.name" to 100.0, "m.room.message" to 50.0), stateDefault = 75.0, users = mapOf("@a" to 100.0))
        assertEquals(listOf(100.0, 50.0, 0.0, 50.0, 50.0, 100.0, 75.0, 50.0),
            listOf("kick", "ban", "invite", "redact", "notifications.room", "events.m.room.name", "events.m.room.topic", "events_default").map { levelAt(pl, it) })
        assertEquals(50.0, levelAt(PowerLevels(), "events.m.room.topic"))
        assertEquals(mapOf("m.room.name" to 100.0, "m.room.message" to 50.0, "m.room.topic" to 0.0), withLevel(pl, "events.m.room.topic", 0.0).events)
        assertEquals(mapOf("room" to 100.0), withLevel(pl, "notifications.room", 100.0).notifications)
        val send = withLevel(pl, "events_default", 100.0) // drops the message override so the new level applies
        assertEquals(listOf<Any?>(100.0, mapOf("m.room.name" to 100.0), mapOf("@a" to 100.0), 100.0), listOf(send.eventsDefault, send.events, send.users, send.kick))
        val roles = listOf(0.0 to "عضو", 50.0 to "ناظر", 100.0 to "مدیر")
        assertEquals(listOf(0.0 to "عضو", 50.0 to "ناظر"), levelOptions(roles, 50.0, 0.0))
        assertEquals(listOf("0:عضو", "50:ناظر", "75:سطح ۷۵", "100:مدیر"), levelOptions(roles, 100.0, 75.0).map { (l, t) -> "${l.toInt()}:$t" })
        assertEquals(listOf(0.0, 50.0, 100.0), levelOptions(roles, 50.0, 100.0).map { it.first }) // current above mine stays shown
    }

    @Test fun `matrix error codes`() {
        assertEquals("درخواست‌ها زیاد بود؛ ۳ ثانیه‌ی دیگر دوباره امتحان کنید.", errorText("M_LIMIT_EXCEEDED", 2500))
        assertEquals("اجازه‌ی این کار را ندارید.", errorText("M_FORBIDDEN"))
        assertNull(errorText("M_SOMETHING_NEW")) // the server's own text is shown
        assertNull(errorText(null))
    }

    @Test fun `room admin helpers`() {
        assertEquals(listOf("مالک", "مدیر", "ناظر", "ناظر", "سطح ۲۵", null), listOf(inf, 100.0, 75.0, 50.0, 25.0, 0.0).map(::roleLabel))
        assertEquals(listOf(false, true, true, false, false, false), listOf("6", "7", "10", "org.example.v1", "", null).map(::supportsKnock))
        assertEquals(listOf(false, true, true, true, true, false), listOf("7", "8", "9", "10", "12", "org.example.v1").map(::supportsRestricted))
        assertEquals(listOf(false, false, true, true), listOf("8", "9", "10", "11").map(::supportsKnockRestricted))
        val rv = RoomVersions("10", mapOf("9" to "stable", "10" to "stable", "11" to "stable", "org.example.x" to "unstable"))
        assertEquals(listOf("10", null, null, "10", "10"), listOf("9", "10", "11", "org.example.x", "org.gone").map { upgradeTarget(it, rv) })
        assertNull(upgradeTarget("1", null))
        assertEquals(
            listOf(LevelChange("@b", 50.0, 0.0), LevelChange("@c", 0.0, 50.0)),
            levelChanges(PowerLevels(users = mapOf("@a" to 100.0, "@b" to 50.0)), PowerLevels(users = mapOf("@a" to 100.0, "@c" to 50.0), usersDefault = 0.0)),
        )
    }
}
