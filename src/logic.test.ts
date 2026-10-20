import { test } from "node:test";
import assert from "node:assert/strict";
import { applyFolderOrder, moveFolder, byListOrder, endpointOf, inArchive, isUnread, parseStats, fmtStats, AUTO_DL_DEFAULT, mergeAutoDl, shouldAutoDl, blurhashEncode, blurhashDecode, scheduleDays, fmtSpan, scheduleLabel, deriveDraftKey, sealDraft, openDraft } from "./logic.ts";
import { aliasLocalpart, buildRows, roomName, dayLabel, downsample, fmtDuration, inFolder, isUserId, formatMessage, parseGeoUri, spaceRooms, stamp, normalizeServer, normalize, matchRange, toEvict, hashPin, lockDelay, lastSeen, tallyPoll, fitSize, fitMenu, packImages, codeFrom, freeCode, cleanCode, padToRatio, isDarkMono, tintOf, toggleWrap, prefixLines, deleteBack, cachedSlice, hashtagsOf, soleHashtag, isEmojiOnly, emojiMotion, unreadSince, type Msg } from "./logic.ts";

const T = new Date("2026-09-30T12:00:00").getTime();
const m = (id: string, sender: string, min: number, kind: Msg["kind"] = "msg"): Msg => ({ id, sender, ts: T + min * 60_000, kind });

test("groups same-sender bursts, splits on gap, sender, notice and day", () => {
  const rows = buildRows([
    m("a", "@x", 0), m("b", "@x", 1), m("c", "@x", 2), // one group
    m("d", "@x", 10),                                  // gap > 5min
    m("e", "@y", 11),                                  // other sender
    m("f", "@y", 12, "notice"), m("g", "@y", 13),      // notice breaks group
    m("h", "@y", 13 + 24 * 60),                        // next day
  ], T);
  const flags = Object.fromEntries(rows.filter((r) => r.type === "msg").map((r) => [r.id, `${+r.first}${+r.last}`]));
  assert.deepEqual(flags, { a: "10", b: "00", c: "01", d: "11", e: "11", g: "11", h: "11" });
  assert.deepEqual(rows.filter((r) => r.type === "day").map((r) => r.type === "day" && r.label), ["امروز", "۹ مهر"]);
});

test("dayLabel compares Jalali years, not Gregorian", () => {
  const now = new Date("2026-10-01T12:00:00").getTime();
  assert.equal(dayLabel(new Date("2026-02-15T12:00:00").getTime(), now), "۲۶ بهمن ۱۴۰۴"); // same Gregorian year, previous Jalali year
  assert.equal(dayLabel(new Date("2026-04-15T12:00:00").getTime(), now), "۲۶ فروردین");
});

test("folders", () => {
  const dm = { id: "1", isDM: true, unread: 0, spaces: [] };
  const grp = { id: "2", isDM: false, unread: 3, spaces: ["!s"] };
  assert.deepEqual(["all", "unread", "dms", "groups", "!s"].map((f) => [inFolder(dm, f), inFolder(grp, f)]),
    [[true, true], [false, true], [true, false], [false, true], [false, true]]);
});

test("spaceRooms follows sub-spaces and survives cycles", () => {
  const tree: Record<string, string[]> = { s: ["a", "sub"], sub: ["b", "s"], a: [], b: [] };
  assert.deepEqual([...spaceRooms("s", (id) => tree[id] ?? [])].sort(), ["a", "b", "s", "sub"]);
});

test("normalizeServer", () => {
  assert.equal(normalizeServer(" chat.example.org:8448/ "), "https://chat.example.org:8448");
  assert.equal(normalizeServer("http://localhost:8008"), "http://localhost:8008");
});

test("user IDs and alias suggestions", () => {
  assert.ok(isUserId("@b:localhost"));
  assert.ok(isUserId(" @alice:matrix.org:8448 "));
  assert.ok(!isUserId("alice"));
  assert.ok(!isUserId("@alice"));
  assert.ok(!isUserId("#room:server"));
  assert.equal(aliasLocalpart("My Cool Group!"), "my-cool-group");
  assert.equal(aliasLocalpart("گروه دوستان"), "");
  assert.match(stamp(T, T), /^امروز، /);
});

test("voice helpers", () => {
  assert.equal(fmtDuration(65_400), "۱:۰۵");
  assert.equal(fmtDuration(0), "۰:۰۰");
  assert.deepEqual(downsample([1, 2, 3, 4], 2), [512, 1024]);
  assert.deepEqual(downsample([0, 5], 4), [0, 0, 1024, 1024]);
  assert.deepEqual(downsample([]), []);
});

test("parseGeoUri", () => {
  assert.deepEqual(parseGeoUri("geo:35.6892,51.389;u=20"), { lat: 35.6892, lon: 51.389, acc: 20 });
  assert.deepEqual(parseGeoUri("geo:-1.5,2,100;crs=wgs84;u=3.5"), { lat: -1.5, lon: 2, acc: 3.5 });
  assert.deepEqual(parseGeoUri("geo:1,2"), { lat: 1, lon: 2, acc: undefined });
  assert.equal(parseGeoUri("https://x"), null);
  assert.equal(parseGeoUri(undefined), null);
});

test("tallyPoll: latest vote wins, spoiled votes erase, max truncates", () => {
  const { voters, picks } = tallyPoll([
    { sender: "@a", ts: 1, answers: ["x"] },
    { sender: "@a", ts: 2, answers: ["y"] },      // changed vote
    { sender: "@b", ts: 1, answers: ["x"] },
    { sender: "@b", ts: 3, answers: ["nope"] },   // spoiled: b now has no vote
    { sender: "@c", ts: 1, answers: ["x", "y"] }, // single choice: only x counts
    { sender: "@d", ts: 1, answers: "x" },        // malformed
  ], ["x", "y"], 1);
  assert.deepEqual(Object.fromEntries(voters), { x: ["@c"], y: ["@a"] });
  assert.deepEqual([...picks.keys()].sort(), ["@a", "@c"]);
});

test("formatMessage", () => {
  const ali = { name: "Ali", id: "@ali:hs" }, aliR = { name: "Ali Reza", id: "@ar:hs" };
  assert.equal(formatMessage("hi\nthere", [ali]), null);
  assert.deepEqual(formatMessage("@Ali Reza & @Ali\n<b>", [ali, aliR]), {
    html: '<a href="https://matrix.to/#/@ar:hs">Ali Reza</a> &#38; <a href="https://matrix.to/#/@ali:hs">Ali</a><br>&#60;b&#62;',
    ids: ["@ar:hs", "@ali:hs"],
  });
});

test("formatMessage markdown", () => {
  const h = (s: string, m: { name: string; id: string }[] = []) => formatMessage(s, m)?.html ?? null;
  assert.equal(h("**b** *i* _j_ ~~s~~ ||x||"), "<strong>b</strong> <em>i</em> <em>j</em> <del>s</del> <span data-mx-spoiler>x</span>");
  assert.equal(h("snake_case_names and 2*3*4"), null);
  assert.equal(h("hi @ali_b:server _x_", [{ name: "ali_b:server", id: "@ali_b:server" }]),
    'hi <a href="https://matrix.to/#/@ali_b:server">ali_b:server</a> <em>x</em>');
  assert.equal(h("**hey @Ali**", [{ name: "Ali", id: "@ali:hs" }]), '<strong>hey <a href="https://matrix.to/#/@ali:hs">Ali</a></strong>');
  assert.equal(h("`*a* _b_` *c*"), "<code>*a* _b_</code> <em>c</em>");
  assert.equal(h("**<b>**"), "<strong>&#60;b&#62;</strong>");
  assert.equal(h("> a\n> *b*\nc"), "<blockquote>a<br><em>b</em></blockquote>c");
  assert.equal(h("x\n```js\nlet _a_ = 1;\n*z*\n```\ny"), 'x<pre><code class="language-js">let _a_ = 1;\n*z*</code></pre>y');
  assert.equal(h("[a_b](https://x.io/a_b_c?q=1&r=2)"), '<a href="https://x.io/a_b_c?q=1&#38;r=2">a_b</a>');
  assert.equal(h("[a](javascript:alert(1))"), null);
  assert.equal(h("```@Ali```", [{ name: "Ali", id: "@ali:hs" }]), "<pre><code>@Ali</code></pre>");
  assert.equal(h("> q\n```\nc\n```\n- a\n```b```"), "<blockquote>q</blockquote><pre><code>c</code></pre><ul><li>a</li></ul><pre><code>b</code></pre>");
  assert.equal(h("a ```b``` c"), "a <pre><code>b</code></pre> c");
  assert.equal(h("# تست"), "<h1>تست</h1>");
  assert.equal(h("a\n## **b**\nc"), "a<h2><strong>b</strong></h2>c");
  assert.equal(h("#tag and #### x"), null);
  assert.equal(h("x\n- a\n* *b*\ny"), "x<ul><li>a</li><li><em>b</em></li></ul>y");
  assert.equal(h("1. a\n2. b"), "<ol><li>a</li><li>b</li></ol>");
  assert.equal(h("۳. a\n۴. b"), '<ol start="3"><li>a</li><li>b</li></ol>');
  assert.equal(h("__u__ snake__case__x ***bi***"), "<u>u</u> snake__case__x <em><strong>bi</strong></em>");
});

test("hashtagsOf / soleHashtag", () => {
  assert.deepEqual(hashtagsOf("hi #tag, (#سلام) #می\u200cخواهم #tag_1."), ["tag", "سلام", "می\u200cخواهم", "tag_1"]);
  assert.deepEqual(hashtagsOf("#room:server.org x.com/#a a#b #123 # heading ##x &#38;"), []);
  assert.deepEqual(hashtagsOf("#a\u200c b"), ["a"]);
  assert.equal(soleHashtag(" #Tag "), "tag");
  assert.equal(soleHashtag("#tag x"), null);
  assert.equal(soleHashtag("tag"), null);
});

test("toggleWrap / prefixLines", () => {
  assert.deepEqual(toggleWrap("a word b", 2, 7, "**"), { text: "a **word** b", a: 4, b: 8 });
  assert.deepEqual(toggleWrap("a **word** b", 4, 8, "**"), { text: "a word b", a: 2, b: 6 });
  assert.deepEqual(toggleWrap("a **word** b", 4, 8, "*"), { text: "a ***word*** b", a: 5, b: 9 }); // italic over bold
  assert.deepEqual(toggleWrap("***w***", 3, 4, "*"), { text: "**w**", a: 2, b: 3 });
  assert.deepEqual(toggleWrap("***w***", 3, 4, "**"), { text: "*w*", a: 1, b: 2 });
  assert.deepEqual(toggleWrap("x word ", 1, 7, "~~"), { text: "x ~~word~~ ", a: 4, b: 8 }); // edge spaces left out
  assert.equal(toggleWrap("a  b", 1, 3, "**"), null);
  assert.deepEqual(toggleWrap("a\nb", 0, 3, "`"), { text: "```\na\nb\n```", a: 4, b: 7 });
  assert.deepEqual(toggleWrap("```\na\nb\n```", 4, 7, "`"), { text: "a\nb", a: 0, b: 3 });
  assert.deepEqual(prefixLines("x\nab\ncd\ny", 3, 6, "> "), { text: "x\n> ab\n> cd\ny", a: 2, b: 11 });
  assert.deepEqual(prefixLines("x\n> ab\n> cd\ny", 2, 11, "> "), { text: "x\nab\ncd\ny", a: 2, b: 7 });
});

test("normalize", () => {
  assert.equal(normalize("Hello كتاب ي می\u200cروم"), "hello کتاب ی میروم");
  assert.equal(normalize("می\u200cروم"), normalize("ميروم"));
});

test("Farsi room names, invite falls back to the inviter", () => {
  assert.equal(roomName({ name: "Team" }), null);
  assert.equal(roomName({ names: ["علی"], count: 2 }), "علی");
  assert.equal(roomName({ names: ["علی", "سارا"], count: 3 }), "علی و سارا");
  assert.equal(roomName({ names: ["علی", "سارا"], count: 6 }), "علی و ۴ نفر دیگر");
  assert.equal(roomName({ names: ["علی"], count: 2, subtype: "Inviting" }), "در حال دعوت از علی");
  assert.equal(roomName({ names: [], count: 3 }), "گفتگوی خالی");
  assert.equal(roomName({ oldName: "علی" }), "گفتگوی خالی (قبلاً علی)");
  assert.equal(roomName({}, "@bob:hs"), "@bob:hs");
});

import { contentLinks, mediaKind } from "./logic.ts";

test("shared media: classify content and pull links out of the text, not the reply quote", () => {
  const msg = (c: Record<string, unknown>) => mediaKind("m.room.message", c);
  assert.deepEqual([msg({ msgtype: "m.image" }), msg({ msgtype: "m.video" }), msg({ msgtype: "m.file" }), msg({ msgtype: "m.audio" })], ["media", "media", "file", "audio"]);
  assert.equal(mediaKind("m.sticker", { url: "mxc://a/b" }), null);
  assert.equal(msg({ msgtype: "m.text", body: "سلام" }), null);
  assert.equal(msg({ msgtype: "m.text", body: "> <@a:x> see https://old.example\n\nthanks" }), null);
  assert.deepEqual(contentLinks({ body: "a https://x.org/p?q=1, and (http://y.io) again https://x.org/p?q=1." }), ["https://x.org/p?q=1", "http://y.io"]);
});

test("archive, marked unread and list order", () => {
  const base = { id: "1", isDM: true, unread: 0, spaces: [] };
  const arch = { ...base, archived: true };
  assert.deepEqual([inFolder(arch, "all"), inFolder(arch, "archive"), inFolder(arch, "dms")], [false, true, false]);
  assert.ok(!inArchive({ ...arch, unread: 2 }), "unmuted chat with new messages comes back");
  assert.ok(inArchive({ ...arch, unread: 2, muted: true }), "muted ones stay archived");
  assert.ok(!inFolder(base, "archive"));
  assert.ok(isUnread({ ...base, marked: true }) && inFolder({ ...base, marked: true }, "unread"));
  const rows = [{ id: "old", invite: false, ts: 1 }, { id: "pin", invite: false, pinned: true, ts: 0 }, { id: "new", invite: false, ts: 5 }, { id: "inv", invite: true, ts: 0 }];
  assert.deepEqual(rows.sort(byListOrder).map((r) => r.id), ["inv", "pin", "new", "old"]);
});

import { isGif, withGif, withoutGif, hasGif } from "./logic.ts";

test("gifs: mau flag or gif image; saving moves to the front without duplicates", () => {
  assert.ok(isGif({ msgtype: "m.video", info: { "fi.mau.gif": true } }));
  assert.ok(isGif({ msgtype: "m.image", info: { mimetype: "image/gif" } }));
  assert.ok(!isGif({ msgtype: "m.video", info: { mimetype: "video/mp4" } }));
  const a = { msgtype: "m.video", body: "a", url: "mxc://hs/a" }, b = { msgtype: "m.video", body: "b", file: { url: "mxc://hs/b" } };
  const list = withGif(withGif(withGif([], a), b), a);
  assert.deepEqual(list.map((g) => g.body), ["a", "b"]);
  assert.ok(hasGif(list, b) && !hasGif(withoutGif(list, b), b));
  assert.equal(Array.from({ length: 120 }, (_, i) => ({ ...a, url: `mxc://hs/${i}` })).reduce(withGif, [] as typeof list).length, 100);
});

test("unread divider goes after the last read message and splits its burst", () => {
  const msgs = [m("a", "@x", 0), m("b", "@x", 1), m("c", "@x", 2)];
  const rows = buildRows(msgs, T, "a");
  assert.deepEqual(rows.map((r) => (r.type === "msg" ? `${r.id}${+r.first}${+r.last}` : r.type)), ["day", "a11", "unread", "b10", "c01"]);
  assert.ok(!buildRows(msgs, T, "c").some((r) => r.type === "unread")); // nothing after it
  assert.ok(!buildRows(msgs, T).some((r) => r.type === "unread"));
});

import { errorText, levelAt, levelChanges, levelOptions, roleLabel, supportsKnock, supportsKnockRestricted, supportsRestricted, upgradeTarget, withLevel } from "./logic.ts";

test("power level settings", () => {
  const pl = { kick: 100, events: { "m.room.name": 100, "m.room.message": 50 }, state_default: 75, users: { "@a": 100 } };
  assert.deepEqual(["kick", "ban", "invite", "redact", "notifications.room", "events.m.room.name", "events.m.room.topic", "events_default"].map((k) => levelAt(pl, k)),
    [100, 50, 0, 50, 50, 100, 75, 50]);
  assert.equal(levelAt({}, "events.m.room.topic"), 50);
  assert.deepEqual(withLevel(pl, "events.m.room.topic", 0).events, { "m.room.name": 100, "m.room.message": 50, "m.room.topic": 0 });
  assert.deepEqual(withLevel(pl, "notifications.room", 100).notifications, { room: 100 });
  const send = withLevel(pl, "events_default", 100); // drops the message override so the new level applies
  assert.deepEqual([send.events_default, send.events, send.users, send.kick], [100, { "m.room.name": 100 }, { "@a": 100 }, 100]);
  const roles: [number, string][] = [[0, "عضو"], [50, "ناظر"], [100, "مدیر"]];
  assert.deepEqual(levelOptions(roles, 50, 0), [[0, "عضو"], [50, "ناظر"]]);
  assert.deepEqual(levelOptions(roles, 100, 75).map(([l, t]) => `${l}:${t}`), ["0:عضو", "50:ناظر", "75:سطح ۷۵", "100:مدیر"]);
  assert.deepEqual(levelOptions(roles, 50, 100).map(([l]) => l), [0, 50, 100]); // current above mine stays shown
});

test("matrix error codes", () => {
  assert.equal(errorText("M_LIMIT_EXCEEDED", { retry_after_ms: 2500 }), "درخواست‌ها زیاد بود؛ ۳ ثانیه‌ی دیگر دوباره امتحان کنید.");
  assert.equal(errorText("M_FORBIDDEN"), "اجازه‌ی این کار را ندارید.");
  assert.equal(errorText("M_SOMETHING_NEW"), undefined); // the server's own text is shown
  assert.equal(errorText(undefined), undefined);
});

test("room admin helpers", () => {
  assert.deepEqual([Infinity, 100, 75, 50, 25, 0].map(roleLabel), ["مالک", "مدیر", "ناظر", "ناظر", "سطح ۲۵", null]);
  assert.deepEqual(["6", "7", "10", "org.example.v1", "", undefined].map(supportsKnock), [false, true, true, false, false, false]);
  assert.deepEqual(["7", "8", "9", "10", "12", "org.example.v1"].map(supportsRestricted), [false, true, true, true, true, false]);
  assert.deepEqual(["8", "9", "10", "11"].map(supportsKnockRestricted), [false, false, true, true]);
  const rv = { default: "10", available: { "9": "stable", "10": "stable", "11": "stable", "org.example.x": "unstable" } };
  assert.deepEqual(["9", "10", "11", "org.example.x", "org.gone"].map((v) => upgradeTarget(v, rv)), ["10", null, null, "10", "10"]);
  assert.equal(upgradeTarget("1", undefined), null);
  assert.deepEqual(levelChanges({ users: { "@a": 100, "@b": 50 } }, { users: { "@a": 100, "@c": 50 }, users_default: 0 }),
    [{ id: "@b", from: 50, to: 0 }, { id: "@c", from: 0, to: 50 }]);
});

import { decryptKeyFile, encryptKeyFile } from "./keyfile.ts";

test("Megolm key file: Element's test vector, round trip, wrong passphrase", async () => {
  const element = "-----BEGIN MEGOLM SESSION DATA-----\nAXNhbHRzYWx0c2FsdHNhbHSIiIiIiIiIiIiIiIiIiIiIAAAACmIRUW2OjZ3L2l6j9h0lHlV3M2dx\ncissyYBxjsfsAndErh065A8=\n-----END MEGOLM SESSION DATA-----";
  assert.equal(await decryptKeyFile(element, "password"), "plain");
  const json = JSON.stringify([{ session_id: "s", room_id: "!r:hs", text: "سلام ".repeat(100) }]);
  const file = await encryptKeyFile(json, "رمز", 1000);
  assert.equal(await decryptKeyFile(file, "رمز"), json);
  await assert.rejects(decryptKeyFile(file, "wrong"), /عبارت عبور/);
});

test("lastSeen buckets", () => {
  const ago = (min: number) => T - min * 60_000;
  assert.equal(lastSeen(true, undefined, T), "آنلاین");
  assert.equal(lastSeen(false, undefined, T), null);
  assert.equal(lastSeen(false, ago(0.2), T), "چند لحظه پیش");
  assert.equal(lastSeen(false, ago(5), T), "آخرین بازدید ۵ دقیقه پیش");
  assert.equal(lastSeen(false, ago(90), T), "آخرین بازدید امروز، ۱۰:۳۰");
  assert.equal(lastSeen(false, ago(24 * 60), T), "آخرین بازدید دیروز، ۱۲:۰۰");
});

test("fitSize downscales the long side and never upscales", () => {
  assert.deepEqual(fitSize(4000, 3000), { w: 1280, h: 960 });
  assert.deepEqual(fitSize(3000, 4000), { w: 960, h: 1280 });
  assert.deepEqual(fitSize(800, 600), { w: 800, h: 600 });
});

import { textDir } from "./logic.ts";
test("textDir: first letter decides, no letters is ltr", () => {
  assert.equal(textDir("سلام hello"), "rtl");
  assert.equal(textDir("hello سلام"), "ltr");
  assert.equal(textDir("123 سلام"), "rtl");
  assert.equal(textDir("۱۲۳"), "ltr");
  assert.equal(textDir("12:30 👍"), "ltr");
  assert.equal(textDir(""), "ltr");
});

import { isRing } from "./logic.ts";
test("isRing: rings only for fresh rings aimed at us or the room", () => {
  const ring = { notification_type: "ring", "m.mentions": { user_ids: ["@me:x"] } };
  assert.equal(isRing(ring, 2000, "@me:x", 1000), true);
  assert.equal(isRing(ring, 1000, "@me:x", 1000), false); // expired
  assert.equal(isRing(ring, NaN, "@me:x", 1000), false); // bad lifetime
  assert.equal(isRing(ring, 2000, "@you:x", 1000), false); // someone else
  assert.equal(isRing({ notification_type: "ring", "m.mentions": { room: true } }, 2000, "@me:x", 1000), true);
  assert.equal(isRing({ notification_type: "notification", "m.mentions": { room: true } }, 2000, "@me:x", 1000), false);
  assert.equal(isRing({ notification_type: "ring" }, 2000, "@me:x", 1000), false);
});

import { isLegacyRing, isVideoOffer } from "./logic.ts";
test("isLegacyRing: fresh invites for us or anyone in the room", () => {
  assert.equal(isLegacyRing({ lifetime: 60000 }, 1000, "@me:x", 2000), true);
  assert.equal(isLegacyRing({ lifetime: 60000 }, 1000, "@me:x", 62000), false); // expired
  assert.equal(isLegacyRing({}, 1000, "@me:x", 1000), false); // no lifetime
  assert.equal(isLegacyRing({ lifetime: 60000, invitee: "@me:x" }, 1000, "@me:x", 2000), true);
  assert.equal(isLegacyRing({ lifetime: 60000, invitee: "@you:x" }, 1000, "@me:x", 2000), false);
  assert.equal(isVideoOffer({ offer: { sdp: "v=0\r\nm=audio 9 UDP\r\nm=video 9 UDP\r\n" } }), true);
  assert.equal(isVideoOffer({ offer: { sdp: "v=0\r\nm=audio 9 UDP\r\n" } }), false);
});

import { isGroupCallAlert, legacyOutcome, rtcOutcome } from "./logic.ts";
test("isGroupCallAlert: group call starts, not rings", () => {
  assert.equal(isGroupCallAlert({ notification_type: "notification", "m.mentions": { room: true } }, 2000, "@me:x", 1000), true);
  assert.equal(isGroupCallAlert({ notification_type: "ring", "m.mentions": { room: true } }, 2000, "@me:x", 1000), false);
  assert.equal(isGroupCallAlert({ notification_type: "notification", "m.mentions": { room: true } }, 1000, "@me:x", 1000), false);
});

test("legacyOutcome: answered with duration, declined, missed, still ringing", () => {
  const inv = { type: "m.call.invite", sender: "@a:x", ts: 1000, content: { call_id: "c1", lifetime: 60000 } };
  const ev = (type: string, ts: number, call_id = "c1") => ({ type, sender: "@b:x", ts, content: { call_id } });
  assert.deepEqual(legacyOutcome(inv, [ev("m.call.answer", 5000), ev("m.call.hangup", 197000)], 300000), { state: "ended", duration: 192000 });
  assert.deepEqual(legacyOutcome(inv, [ev("m.call.answer", 5000)], 300000), { state: "ongoing" });
  assert.deepEqual(legacyOutcome(inv, [ev("m.call.reject", 5000)], 300000), { state: "declined" });
  assert.deepEqual(legacyOutcome(inv, [ev("m.call.hangup", 5000)], 6000), { state: "missed" }); // caller gave up
  assert.deepEqual(legacyOutcome(inv, [], 300000), { state: "missed" }); // expired
  assert.deepEqual(legacyOutcome(inv, [ev("m.call.answer", 5000, "other")], 2000), { state: "ringing" }); // another call's events
});

test("rtcOutcome: from call memberships after the ring", () => {
  const ring = { id: "$r", type: "org.matrix.msc4075.rtc.notification", sender: "@a:x", ts: 1000, content: {} };
  const m = (sender: string, ts: number, on: boolean) => ({ type: "org.matrix.msc3401.call.member", sender, ts, content: on ? { application: "m.call" } : {} });
  assert.deepEqual(rtcOutcome(ring, 31000, [m("@b:x", 5000, true), m("@a:x", 65000, false)], 100000), { state: "ended", duration: 60000 });
  assert.deepEqual(rtcOutcome(ring, 31000, [m("@b:x", 5000, true)], 100000), { state: "ongoing" });
  assert.deepEqual(rtcOutcome(ring, 31000, [{ type: "org.matrix.msc4310.rtc.decline", sender: "@b:x", ts: 3000, content: { "m.relates_to": { event_id: "$r" } } }], 100000), { state: "declined" });
  assert.deepEqual(rtcOutcome(ring, 31000, [m("@a:x", 9000, false)], 10000), { state: "missed" }); // caller gave up
  assert.deepEqual(rtcOutcome(ring, 31000, [], 100000), { state: "missed" });
  assert.deepEqual(rtcOutcome(ring, 31000, [], 2000), { state: "ringing" });
  assert.deepEqual(rtcOutcome(ring, 31000, [m("@b:x", 40000, true)], 100000), { state: "missed" }); // joined after it stopped ringing
});

test("endpointOf names the endpoint for the net log", () => {
  assert.equal(endpointOf("https://hs.x/_matrix/client/v3/sync?since=s1&timeout=30000"), "sync");
  assert.equal(endpointOf("https://hs.x/_matrix/client/versions"), "versions");
  assert.equal(endpointOf("https://hs.x/_matrix/client/v3/rooms/!a:x/send/m.room.message/1"), "rooms");
  assert.equal(endpointOf("https://hs.x/_matrix/client/v1/media/thumbnail/x/y?width=96"), "thumbnail");
  assert.equal(endpointOf("https://hs.x/_matrix/client/unstable/org.matrix.msc3575/sync"), "org.matrix.msc3575");
});

test("links without a scheme: www. and bare domains are found, emails and file names are not", () => {
  assert.deepEqual(contentLinks({ body: "سلام www.example.com/a, و panbeh.ir. یا (example.org:8080/x?y=1) https://x.io" }),
    ["https://www.example.com/a", "https://panbeh.ir", "https://example.org:8080/x?y=1", "https://x.io"]);
  assert.deepEqual(contentLinks({ body: "mail me@example.com, see notes.txt and v1.2.3" }), []);
});

test("applyFolderOrder: all first, saved order, new spaces last, stale ids ignored", () => {
  const f = (id: string) => ({ id, label: id });
  const folders = ["all", "unread", "dms", "s1", "s2"].map(f);
  assert.deepEqual(applyFolderOrder(folders, []).map((x) => x.id), ["all", "unread", "dms", "s1", "s2"]);
  assert.deepEqual(applyFolderOrder(folders, ["s1", "gone", "dms"]).map((x) => x.id), ["all", "s1", "dms", "unread", "s2"]);
  assert.deepEqual(applyFolderOrder(folders, ["dms", "all"]).map((x) => x.id), ["all", "dms", "unread", "s1", "s2"]);
});

test("moveFolder: never touches the first tab", () => {
  const ids = ["all", "a", "b", "c"];
  assert.deepEqual(moveFolder(ids, 3, 1), ["all", "c", "a", "b"]);
  assert.deepEqual(moveFolder(ids, 1, 3), ["all", "b", "c", "a"]);
  assert.equal(moveFolder(ids, 0, 2), ids);
  assert.equal(moveFolder(ids, 2, 0), ids);
  assert.equal(moveFolder(ids, 2, 9), ids);
  assert.equal(moveFolder(ids, 2, 2), ids);
});

test("parseStats: video over audio, simulcast adds up, bitrate from the last sample", () => {
  const report = (bytes: number, ts: number) => [
    { type: "outbound-rtp", kind: "audio", bytesSent: 999999, timestamp: ts },
    { type: "outbound-rtp", kind: "video", bytesSent: bytes / 2, frameWidth: 640, frameHeight: 360, framesPerSecond: 15, codecId: "c1", timestamp: ts },
    { type: "outbound-rtp", kind: "video", bytesSent: bytes / 2, frameWidth: 1280, frameHeight: 720, framesPerSecond: 30, codecId: "c1", timestamp: ts },
    { type: "remote-inbound-rtp", kind: "video", fractionLost: 0.0125 },
    { type: "candidate-pair", nominated: true, state: "succeeded", currentRoundTripTime: 0.042 },
    { type: "codec", id: "c1", mimeType: "video/VP8" },
  ];
  const a = parseStats(report(100_000, 1000), "out");
  assert.equal(a.kbps, undefined);
  const b = parseStats(report(250_000, 2000), "out", a);
  assert.deepEqual([b.w, b.h, b.fps, b.kbps, b.loss, b.rtt, b.codec], [1280, 720, 30, 1200, 1.3, 42, "VP8"]);
  assert.equal(fmtStats(b), "1280×720 · 30fps · 1200kbps · 1.3% · 42ms · VP8");
  const audio = parseStats([{ type: "inbound-rtp", kind: "audio", bytesReceived: 10, packetsLost: 1, packetsReceived: 199, timestamp: 5 }], "in");
  assert.deepEqual([audio.w, audio.loss], [undefined, 0.5]);
});

test("matchRange: normalized match mapped back to the original text", () => {
  assert.deepEqual(matchRange("سلام علي", "علی"), [5, 8]); // Arabic yeh in the text
  assert.deepEqual(matchRange("می\u200cروم خانه", "میروم"), [0, 6]); // ZWNJ inside the match
  assert.deepEqual(matchRange("Hello World", "world"), [6, 11]);
  assert.equal(matchRange("abc", "x"), null);
  assert.equal(matchRange("abc", ""), null);
});

test("toEvict drops the oldest until the rest fit", () => {
  const e: [string, number][] = [["a", 40], ["b", 30], ["c", 20], ["d", 10]];
  assert.deepEqual(toEvict(e, 100), []);
  assert.deepEqual(toEvict(e, 60), ["a"]);
  assert.deepEqual(toEvict(e, 25), ["a", "b", "c"]);
  assert.deepEqual(toEvict(e, 0), ["a", "b", "c", "d"]);
});

test("hashPin: same PIN and salt agree, anything else doesn't", async () => {
  const a = await hashPin("1234", "salt", 1000);
  assert.equal(await hashPin("1234", "salt", 1000), a);
  assert.notEqual(await hashPin("1235", "salt", 1000), a);
  assert.notEqual(await hashPin("1234", "other", 1000), a);
});

test("lockDelay: free tries, then doubling up to an hour", () => {
  assert.equal(lockDelay(4), 0);
  assert.equal(lockDelay(5), 30_000);
  assert.equal(lockDelay(6), 60_000);
  assert.equal(lockDelay(30), 3_600_000);
});

test("fitMenu: after the click, flipped before it near the edge, kept on screen", () => {
  assert.equal(fitMenu(100, 200, 1000), 100);
  assert.equal(fitMenu(900, 200, 1000), 700); // last message: opens upward
  assert.equal(fitMenu(150, 200, 300), 8); // fits neither way: clamped
  assert.equal(fitMenu(5, 200, 1000), 8);
});

test("auto-download rules: chat kind, size cap, no limit, merging stored rules", () => {
  const r = mergeAutoDl({ mobile: { files: { group: false } } });
  assert.equal(shouldAutoDl(r.mobile, "files", true, 500_000), true);
  assert.equal(shouldAutoDl(r.mobile, "files", true, 2_000_000), false); // over 1 MB
  assert.equal(shouldAutoDl(r.mobile, "files", false, 10), false); // groups turned off
  assert.equal(r.mobile.files.maxMB, 1); // the rest of the stored kind keeps its default
  assert.equal(shouldAutoDl(r.mobile, "videos", true), true); // size unknown
  assert.equal(shouldAutoDl({ ...r.wifi, videos: { dm: true, group: true, maxMB: 0 } }, "videos", false, 5e9), true);
  assert.deepEqual(mergeAutoDl(undefined), AUTO_DL_DEFAULT);
});

test("blurhash: average colour and left/right survive a round trip, bad hashes are refused", () => {
  const w = 32, h = 24, px = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < w * h; i++) px.set(i % w < w / 2 ? [220, 40, 40, 255] : [40, 40, 220, 255], i * 4);
  const hash = blurhashEncode(px, w, h);
  assert.equal(hash.length, 4 + 2 * 4 * 3);
  const out = blurhashDecode(hash, w, h)!;
  const at = (x: number, y: number, k: number) => out[4 * (x + y * w) + k];
  assert.ok(at(2, 12, 0) > at(2, 12, 2) + 80 && at(29, 12, 2) > at(29, 12, 0) + 80); // red left, blue right
  assert.equal(blurhashDecode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 4, 3)?.length, 48); // the reference hash
  assert.equal(blurhashDecode("LEHV6nWB2yk8", 4, 3), null);
  assert.equal(blurhashDecode("LEHV6nWB2yk8pyo0adR*.7kCMdn\"", 4, 3), null);
});

test("albums: tagged ones group, untagged only seconds apart, captions and the divider split them", () => {
  const at = (id: string, sec: number, album?: string, sender = "@x"): Msg => ({ id, sender, ts: T + sec * 1000, kind: "msg", album });
  const kinds = (rows: ReturnType<typeof buildRows>) => rows.filter((r) => r.type !== "day").map((r) => r.type === "album" ? r.ids.join("+") : r.type === "msg" ? r.id : r.type);
  assert.deepEqual(kinds(buildRows([at("a", 0, "t:1"), at("b", 40, "t:1"), at("c", 41, "t:2"), at("d", 42, "t:2")], T)), ["a+b", "c+d"]); // tags, however far apart
  assert.deepEqual(kinds(buildRows([at("a", 0, "h"), at("b", 3, "h"), at("c", 20, "h"), at("d", 21), at("e", 22, "h")], T)), ["a+b", "c", "d", "e"]); // gap, caption (no album key)
  assert.deepEqual(kinds(buildRows([at("a", 0, "h"), at("b", 1, "h", "@y")], T)), ["a", "b"]); // other sender
  assert.deepEqual(kinds(buildRows([at("a", 0, "h"), at("b", 1, "h"), at("c", 2, "h")], T, "a")), ["a", "unread", "b+c"]);
  assert.equal(buildRows(Array.from({ length: 12 }, (_, i) => at("m" + i, i, "h")), T).filter((r) => r.type === "album").length, 2); // 10 at most
  const rows = buildRows([at("a", 0, "t:1"), at("b", 1, "t:1"), at("c", 2)], T).filter((r) => r.type !== "day");
  assert.deepEqual(rows.map((r) => r.type !== "unread" && r.type !== "notice" && [r.key, r.id, r.first, r.last]), [["a", "b", true, false], ["c", "c", false, true]]);
});

test("schedule days, spans and labels", () => {
  const days = scheduleDays(new Date("2026-10-11T22:30:00").getTime(), 3);
  assert.deepEqual(days.map((d) => d.label.split("، ")[0]), ["امروز", "فردا", days[2].label.split("، ")[0]]);
  assert.equal(new Date(days[1].ts).getHours(), 0);
  assert.equal(new Date(days[1].ts).getDate(), 12);
  assert.equal(fmtSpan(30 * 86_400_000), "۳۰ روز");
  assert.equal(fmtSpan(5.5 * 3_600_000), "۵ ساعت");
  assert.equal(fmtSpan(600_000), "۱۰ دقیقه");
  assert.match(scheduleLabel(new Date("2026-10-11T21:00:00").getTime(), new Date("2026-10-11T08:00:00").getTime()), /^امروز ساعت ۲۱:۰۰$/);
});

test("custom emoji: :code: becomes an emoticon img, not in code, unknown codes stay text", () => {
  const emotes = new Map([["cat", "mxc://s/cat"], ["cat_big", "mxc://s/big"]]);
  const img = (code: string, url: string) => `<img data-mx-emoticon src="${url}" alt=":${code}:" title=":${code}:" height="32">`;
  assert.equal(formatMessage(":cat:", [], emotes)?.html, img("cat", "mxc://s/cat"));
  assert.equal(formatMessage("hi :cat_big: :dog:", [], emotes)?.html, `hi ${img("cat_big", "mxc://s/big")} :dog:`);
  assert.equal(formatMessage("`:cat:`", [], emotes)?.html, "<code>:cat:</code>");
  assert.equal(formatMessage(":dog: 10:30", [], emotes), null);
});

test("image packs: usage falls back image → pack → both; shortcodes from file names, de-duplicated", () => {
  const p = { pack: { usage: ["sticker" as const] }, images: {
    a: { url: "mxc://s/a" }, b: { url: "mxc://s/b", usage: ["emoticon" as const] }, c: { url: "https://x/c" }, d: { url: "mxc://s/d", usage: [] },
  } };
  assert.deepEqual(packImages(p, "sticker").map(([k]) => k), ["a", "d"]);
  assert.deepEqual(packImages(p, "emoticon").map(([k]) => k), ["b"]);
  assert.deepEqual(packImages({ images: p.images }, "emoticon").map(([k]) => k), ["a", "b", "d"]);
  assert.equal(codeFrom("happy cat:1.png"), "happy_cat_1");
  assert.equal(codeFrom("  <:)> .png"), "emoji");
  assert.equal(codeFrom("خنده‌ی بلند!.gif"), "خنده‌ی_بلند");
  assert.equal(cleanCode("ha ha:<b>😂_x-۲"), "hahab_x-۲");
  assert.equal(cleanCode("x".repeat(40)).length, 32);
  assert.deepEqual(padToRatio(300, 100), { w: 300, h: 150 });
  assert.deepEqual(padToRatio(100, 250), { w: 125, h: 250 });
  assert.deepEqual(padToRatio(200, 100), { w: 200, h: 100 });
  assert.equal(freeCode("cat", { cat: 1, cat_2: 1 }), "cat_3");
  assert.equal(freeCode("constructor", {}), "constructor");
});

test("drafts: sealed per room, readable only with the same backup key and room", async () => {
  const key = await deriveDraftKey(new Uint8Array(32).fill(7));
  const sealed = await sealDraft(key, "!a:x", { body: "سلام", reply: "$e" });
  assert.deepEqual(await openDraft(key, "!a:x", sealed), { body: "سلام", reply: "$e" });
  await assert.rejects(openDraft(key, "!b:x", sealed)); // moved to another room
  await assert.rejects(openDraft(await deriveDraftKey(new Uint8Array(32).fill(8)), "!a:x", sealed)); // another account's backup
});

test("isDarkMono: dark gray art yes; colored, light or empty no", () => {
  const px = (...cols: number[][]) => cols.flat();
  const clear = [255, 0, 0, 0]; // fully transparent pixels don't count, whatever their color
  assert.equal(isDarkMono(px([0, 0, 0, 255], [0, 0, 0, 255], [120, 120, 120, 255], clear, clear)), true); // black + gray shading
  assert.equal(isDarkMono(px([0, 0, 0, 255], [230, 20, 20, 255])), false); // half red
  assert.equal(isDarkMono(px([240, 240, 240, 255], [200, 200, 200, 255])), false); // light gray
  assert.equal(isDarkMono(px(clear, clear)), false);
  assert.equal(tintOf({ pack: { "app.panbeh.tint": "never" } }, { url: "mxc://a/b" }), "never");
  assert.equal(tintOf({ pack: { "app.panbeh.tint": "never" } }, { url: "mxc://a/b", "app.panbeh.tint": "always" }), "always");
  assert.equal(tintOf({}, { url: "mxc://a/b" }), "auto");
});

test("deleteBack: selection, grapheme, custom emoji", () => {
  assert.deepEqual(deleteBack("hello", 1, 3, []), ["hlo", 1]);
  assert.deepEqual(deleteBack("a👨‍👩‍👧b", 9, 9, []), ["ab", 1]);
  assert.deepEqual(deleteBack("x🇮🇷", 5, 5, []), ["x", 1]);
  assert.deepEqual(deleteBack("hi :cat: there", 8, 8, ["cat"]), ["hi  there", 3]);
  assert.deepEqual(deleteBack("hi :dog:", 8, 8, ["cat"]), ["hi :dog", 7]);
  assert.deepEqual(deleteBack("abc", 0, 0, ["cat"]), ["abc", 0]);
});

test("cachedSlice pages the cache, then hands over to the server's token", () => {
  const r = (...ids: string[]) => ids.map((id) => ({ id }));
  assert.deepEqual(cachedSlice(r("c", "b", "a"), 2, "srv", "pb."), { chunk: r("c", "b"), end: "pb.b" }); // more cached
  assert.deepEqual(cachedSlice(r("b", "a"), 2, "srv", "pb."), { chunk: r("b", "a"), end: "srv" }); // the cache ends here
  assert.deepEqual(cachedSlice(r("a"), 2, null, "pb."), { chunk: r("a") }); // the start of the chat
  assert.deepEqual(cachedSlice([], 2, "srv", "pb."), { chunk: [], end: "srv" });
});

test("isEmojiOnly: up to 3 emoji, any sequence, nothing else", () => {
  for (const s of ["👍", "❤️", "👍🏽", "🇮🇷", "👨‍👩‍👧", "😂 😂 😂", "1️⃣", "👍\uFE0F", "👎\uFE0F👍\uFE0F"]) assert.ok(isEmojiOnly(s), s);
  for (const s of ["👍👍👍👍", "hi 👍", "1", "#", "", " "]) assert.ok(!isEmojiOnly(s), s);
});

test("emojiMotion: a motion class per emoji family, none otherwise", () => {
  assert.equal(emojiMotion("❤️"), "em-pulse");
  assert.equal(emojiMotion("👍🏽"), "em-pulse"); // tone marker doesn't break family matching
  assert.equal(emojiMotion("😂"), "em-shake");
  assert.equal(emojiMotion("🚀"), "");
  assert.equal(emojiMotion("hello"), "");
});

test("unreadSince: counted replies after the newest read one; null when the read point isn't loaded", () => {
  const rs = [{ read: true, n: true }, { read: false, n: true }, { read: false, n: false }, { read: false, n: true }];
  const run = (r: typeof rs, complete: boolean) => unreadSince(r, (e) => e.read, (e) => e.n, complete);
  assert.equal(run(rs, false), 2);
  assert.equal(run([...rs, { read: true, n: true }], false), 0); // read to the end
  assert.equal(run(rs.slice(1), false), null); // read point further back than what's loaded
  assert.equal(run(rs.slice(1), true), 2); // all loaded, none read
});
