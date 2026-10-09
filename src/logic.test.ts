import { test } from "node:test";
import assert from "node:assert/strict";
import { applyFolderOrder, moveFolder, byListOrder, endpointOf, inArchive, isUnread, parseStats, fmtStats } from "./logic.ts";
import { aliasLocalpart, buildRows, roomName, dayLabel, downsample, fmtDuration, inFolder, isUserId, formatMessage, parseGeoUri, spaceRooms, stamp, normalizeServer, normalize, matchRange, toEvict, hashPin, lockDelay, lastSeen, tallyPoll, fitSize, type Msg } from "./logic.ts";

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
  assert.equal(h("x\n```js\nlet _a_ = 1;\n*z*\n```\ny"), "x<pre><code>let _a_ = 1;\n*z*</code></pre>y");
  assert.equal(h("[a_b](https://x.io/a_b_c?q=1&r=2)"), '<a href="https://x.io/a_b_c?q=1&#38;r=2">a_b</a>');
  assert.equal(h("[a](javascript:alert(1))"), null);
  assert.equal(h("```@Ali```", [{ name: "Ali", id: "@ali:hs" }]), "<pre><code>@Ali</code></pre>");
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

import { levelChanges, roleLabel, supportsKnock } from "./logic.ts";

test("room admin helpers", () => {
  assert.deepEqual([100, 75, 50, 25, 0].map(roleLabel), ["مدیر", "ناظر", "ناظر", "سطح ۲۵", null]);
  assert.deepEqual(["6", "7", "10", "org.example.v1", "", undefined].map(supportsKnock), [false, true, true, false, false, false]);
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
