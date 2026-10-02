// Pure helpers — no SDK imports so `node --test` can run them directly.

export type Msg = { id: string; sender: string; ts: number; kind: "msg" | "notice" };
export type Row =
  | { type: "day"; key: string; label: string }
  | { type: "notice"; key: string; id: string }
  | { type: "unread"; key: "unread" }
  | { type: "msg"; key: string; id: string; first: boolean; last: boolean };

const LOCALE = "fa-IR"; // Jalali calendar + Persian digits
export const num = (n: number) => n.toLocaleString(LOCALE);

const GROUP_GAP = 5 * 60_000;
const day = (ts: number) => new Date(ts).toDateString();
const groups = (a: Msg | undefined, b: Msg | undefined) =>
  !!a && !!b && a.kind === "msg" && b.kind === "msg" &&
  a.sender === b.sender && b.ts - a.ts < GROUP_GAP && day(a.ts) === day(b.ts);

/** Timeline rows: day separators + messages flagged as first/last of a same-sender burst,
 *  plus an "unread" divider after `unreadAfter` (a Msg id) when anything follows it. */
export function buildRows(msgs: Msg[], now = Date.now(), unreadAfter?: string): Row[] {
  const rows: Row[] = [];
  let prevDay = "";
  msgs.forEach((m, i) => {
    if (day(m.ts) !== prevDay) {
      prevDay = day(m.ts);
      rows.push({ type: "day", key: "day-" + prevDay, label: dayLabel(m.ts, now) });
    }
    if (m.kind === "notice") rows.push({ type: "notice", key: m.id, id: m.id });
    // the divider splits a burst
    else rows.push({ type: "msg", key: m.id, id: m.id, first: !groups(msgs[i - 1], m) || msgs[i - 1].id === unreadAfter, last: !groups(m, msgs[i + 1]) || m.id === unreadAfter });
    if (m.id === unreadAfter && i < msgs.length - 1) rows.push({ type: "unread", key: "unread" });
  });
  return rows;
}

export function dayLabel(ts: number, now = Date.now()) {
  const d = new Date(ts), n = new Date(now);
  const diff = Math.round((new Date(n.toDateString()).getTime() - new Date(d.toDateString()).getTime()) / 86_400_000);
  if (diff === 0) return "امروز";
  if (diff === 1) return "دیروز";
  // Jalali years roll over at Nowruz, not Jan 1, so compare them in that calendar
  const year = (t: Date) => t.toLocaleDateString(LOCALE, { year: "numeric" });
  return d.toLocaleDateString(LOCALE, { month: "long", day: "numeric", year: year(d) === year(n) ? undefined : "numeric" });
}

/** Chat-list timestamp: 14:05 today, "Mon" this week, "12 Mar" otherwise. */
export function listTime(ts: number, now = Date.now()) {
  if (!(ts > 0)) return "";
  const d = new Date(ts);
  if (day(ts) === day(now)) return d.toLocaleTimeString(LOCALE, { hour: "2-digit", minute: "2-digit" });
  if (now - ts < 6 * 86_400_000) return d.toLocaleDateString(LOCALE, { weekday: "short" });
  return d.toLocaleDateString(LOCALE, { day: "numeric", month: "short" });
}

export const clock = (ts: number) => new Date(ts).toLocaleTimeString(LOCALE, { hour: "2-digit", minute: "2-digit" });

export type RoomInfo = {
  id: string; isDM: boolean; unread: number; spaces: string[];
  marked?: boolean; archived?: boolean; muted?: boolean; // marked = "mark as unread"
};
export type Folder = { id: string; label: string };

export const BASE_FOLDERS: Folder[] = [
  { id: "all", label: "همه" },
  { id: "unread", label: "خوانده‌نشده" },
  { id: "dms", label: "شخصی" },
  { id: "groups", label: "گروه‌ها" },
];

/** Archived chats live in the "archive" pseudo-folder and nowhere else. */
export function inFolder(r: RoomInfo, folder: string) {
  if ((folder === "archive") !== inArchive(r)) return false;
  switch (folder) {
    case "all": case "archive": return true;
    case "unread": return isUnread(r);
    case "dms": return r.isDM;
    case "groups": return !r.isDM;
    default: return r.spaces.includes(folder); // space id
  }
}

/** All rooms reachable from a space, following sub-spaces (cycle-safe). */
export function spaceRooms(spaceId: string, children: (id: string) => string[]): Set<string> {
  const seen = new Set<string>();
  const walk = (id: string) => children(id).forEach((c) => { if (!seen.has(c)) { seen.add(c); walk(c); } });
  walk(spaceId);
  return seen;
}

export function normalizeServer(input: string) {
  const s = input.trim().replace(/\/+$/, "");
  return /^https?:\/\//i.test(s) ? s : "https://" + s;
}

/** Full Matrix user ID, e.g. "@alice:example.org" (worth a direct profile lookup). */
export const isUserId = (s: string) => /^@[^\s:]+:[^\s:]+(:\d+)?$/.test(s.trim());

/** The SDK's RoomNameState, structurally (count includes us). */
type NameState = { name: string } | { names: string[]; count: number; subtype?: "Inviting" } | { oldName?: string };

/** Farsi room names for the SDK's roomNameGenerator; null keeps an actual name. `inviter` names an invite with no members known. */
export function roomName(s: NameState, inviter?: string): string | null {
  if ("name" in s) return null;
  if ("names" in s && s.names.length) {
    const [a, b] = s.names, others = s.count - 1;
    const names = s.names.length === 1 && others <= 1 ? a
      : s.names.length === 2 && others <= 2 ? `${a} و ${b}`
      : `${a} و ${num(others - 1)} نفر دیگر`;
    return s.subtype === "Inviting" ? `در حال دعوت از ${names}` : names;
  }
  const old = "oldName" in s && s.oldName;
  return inviter ?? (old ? `گفتگوی خالی (قبلاً ${old})` : "گفتگوی خالی");
}

/** Suggested #address localpart from a room name; Latin letters/digits only (Farsi names give ""). */
export const aliasLocalpart = (name: string) =>
  name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");

/** "امروز، ۱۴:۰۵" — day plus time, for read receipts. */
/** Search-term folding: case, Arabic/Persian ي ك, and ZWNJ don't matter. */
export const normalize = (s: string) => s.toLowerCase().replace(/ي/g, "ی").replace(/ك/g, "ک").replace(/\u200c/g, "");

export const stamp = (ts: number, now = Date.now()) => `${dayLabel(ts, now)}، ${clock(ts)}`;

/** Telegram-style last seen from m.presence; null when the server told us nothing. */
export function lastSeen(online: boolean, activeTs: number | undefined, now = Date.now()) {
  if (online) return "آنلاین";
  if (!activeTs || activeTs <= 0) return null;
  const min = Math.floor((now - activeTs) / 60_000);
  if (min < 1) return "چند لحظه پیش";
  if (min < 60) return `آخرین بازدید ${num(min)} دقیقه پیش`;
  return `آخرین بازدید ${day(activeTs) === day(now) ? "امروز" : dayLabel(activeTs, now)}، ${clock(activeTs)}`;
}

/** "۱:۰۵" from milliseconds. */
export function fmtDuration(ms: number) {
  const s = Math.max(0, Math.round(ms / 1000));
  return `${num(Math.floor(s / 60))}:${num(s % 60).padStart(2, "۰")}`;
}

/** `n` bars (peak of each slice), scaled 0..1024 like MSC1767 waveforms. Also rescales a received waveform for display. */
export function downsample(xs: number[], n = 40) {
  if (!xs.length) return [];
  const max = Math.max(...xs) || 1;
  return Array.from({ length: n }, (_, i) => {
    const a = Math.floor((i * xs.length) / n);
    const part = xs.slice(a, Math.max(a + 1, Math.floor(((i + 1) * xs.length) / n)));
    return Math.round((Math.max(...part) / max) * 1024);
  });
}

/** geo:lat,lon[,alt][;u=accuracy] → numbers; null if malformed. */
export function parseGeoUri(uri: unknown) {
  const m = /^geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)(?:,-?[\d.]+)?(?:;.*?\bu=(\d+(?:\.\d+)?))?/i.exec(typeof uri === "string" ? uri : "");
  return m ? { lat: +m[1], lon: +m[2], acc: m[3] ? +m[3] : undefined } : null;
}

export const osmUrl = (lat: number, lon: number) => `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lon}#map=16/${lat}/${lon}`;

export type Vote = { sender: string; ts: number; answers: unknown };

/** Poll results from response events: each voter's latest vote counts. Per MSC3381 a vote with an unknown
 *  or no answer is spoiled (and still replaces that voter's earlier vote); answers past `max` are dropped. */
export function tallyPoll(votes: Vote[], answerIds: string[], max: number) {
  const latest = new Map<string, Vote>();
  for (const v of votes) if (!((latest.get(v.sender)?.ts ?? -Infinity) > v.ts)) latest.set(v.sender, v);
  const voters = new Map<string, string[]>(answerIds.map((id) => [id, []]));
  const picks = new Map<string, string[]>();
  for (const [sender, v] of latest) {
    const a = [...new Set(Array.isArray(v.answers) ? v.answers.slice(0, Math.max(1, max)) : [])];
    if (!a.length || a.some((id) => !voters.has(id))) continue;
    picks.set(sender, a);
    a.forEach((id) => voters.get(id)!.push(sender));
  }
  return { voters, picks };
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
export const escRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** Telegram-style Markdown subset + "@Name" mentions as matrix.to links. Everything that must not be
 *  re-parsed (code, links, mentions) is stashed behind \0N\0 placeholders until the end.
 *  null when nothing got formatted, so plain messages send no formatted_body. */
export function formatMessage(text: string, mentions: { name: string; id: string }[]): { html: string; ids: string[] } | null {
  const stash: string[] = [];
  const hold = (html: string) => `\0${stash.push(html) - 1}\0`;
  let t = text.replaceAll("\0", "");
  t = t.replace(/\n?```(?:[\w+-]*\n)?([\s\S]*?)\n?```\n?/g, (_, c) => hold(`<pre><code>${esc(c)}</code></pre>`));
  t = t.replace(/`([^`\n]+)`/g, (_, c) => hold(`<code>${esc(c)}</code>`));

  const byName = new Map(mentions.map((m) => ["@" + m.name, m.id]));
  const used = new Set<string>();
  if (byName.size) {
    // longest first so "@Ali Reza" isn't cut short by "@Ali"
    const re = new RegExp([...byName.keys()].sort((a, b) => b.length - a.length).map(escRe).join("|"), "g");
    t = t.replace(re, (m) => {
      const id = byName.get(m)!;
      used.add(id);
      return hold(`<a href="https://matrix.to/#/${esc(id)}">${esc(m.slice(1))}</a>`);
    });
  }

  t = esc(t);
  t = t.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, (_, label, url) => hold(`<a href="${url}">${label}</a>`));
  const parts: { html: string; quote: boolean }[] = [];
  for (const line of t.split("\n")) {
    const q = line.startsWith("&#62; ");
    const last = parts[parts.length - 1];
    if (q && last?.quote) last.html += "<br>" + line.slice(6);
    else parts.push({ html: q ? line.slice(6) : line, quote: q });
  }
  let h = parts.map((p, i) => (p.quote ? `<blockquote>${p.html}</blockquote>` : p.html) + (i < parts.length - 1 && !p.quote && !parts[i + 1].quote ? "\n" : "")).join("");
  const wrap = (re: RegExp, open: string, close: string) => (h = h.replace(re, `${open}$1${close}`));
  wrap(/\*\*([^\s*](?:[^*\n]*?[^\s*])?)\*\*/g, "<strong>", "</strong>");
  wrap(/~~([^\s~](?:[^~\n]*?[^\s~])?)~~/g, "<del>", "</del>");
  wrap(/\|\|([^\s|](?:[^|\n]*?[^\s|])?)\|\|/g, "<span data-mx-spoiler>", "</span>");
  // opening marker not after a word char, closing not before one: snake_case and 2*3*4 stay literal
  h = h.replace(/(?<![\p{L}\p{N}_*])([*_])(?=\S)((?:(?!\1)[^\n])*?\S)\1(?![\p{L}\p{N}_*])/gu, "<em>$2</em>");
  h = h.replace(/\n/g, "<br>"); // before restoring, so <pre> keeps its newlines
  while (h.includes("\0")) h = h.replace(/\0(\d+)\0/g, (_, n) => stash[+n]);
  const clean = esc(text.replaceAll("\0", "")).replace(/\n/g, "<br>");
  return used.size || h !== clean ? { html: h, ids: [...used] } : null;
}

/** Distinct http(s) links in text (same pattern as the bubble's linkify). */
export const extractLinks = (text: string) => [...new Set(text.match(/https?:\/\/[^\s<]+[^\s<.,;:!?)"']/g) ?? [])];
/** Links in a message's own text, not in the quoted reply fallback. */
export const contentLinks = (c: Record<string, unknown>) => extractLinks(String(c.body ?? "").replace(/^(> .*\n)+\n?/, ""));

export type MediaKind = "media" | "file" | "link" | "audio";
/** Shared-media tab for an event; null = not listed (stickers, polls, plain text…). */
export function mediaKind(type: string, c: Record<string, unknown>): MediaKind | null {
  if (type !== "m.room.message") return null;
  switch (c.msgtype) {
    case "m.image": case "m.video": return "media";
    case "m.file": return "file";
    case "m.audio": return "audio";
    case "m.text": case "m.notice": case "m.emote": return contentLinks(c).length ? "link" : null;
  }
  return null;
}
export const isUnread = (r: RoomInfo) => r.unread > 0 || !!r.marked;

/** Telegram-style: an unmuted archived chat with new messages shows in the main list until it's read again. */
export const inArchive = (r: RoomInfo) => !!r.archived && !(r.unread > 0 && !r.muted);

/** Chat list order: invites, then pinned, then newest. */
export const byListOrder = (a: { invite: boolean; pinned?: boolean; ts: number }, b: typeof a) =>
  +b.invite - +a.invite || +!!b.pinned - +!!a.pinned || b.ts - a.ts;
export type Sticker = { shortcode: string; url: string; body: string; info?: Record<string, unknown> };
export type StickerPack = { id: string; name: string; avatar?: string; stickers: Sticker[] };

/** MSC2545 image pack → its stickers: images whose usage (own, else the pack's) includes "sticker" or is unset/empty.
 *  null if none are usable. */
export function stickerPack(id: string, content: unknown, fallbackName: string): StickerPack | null {
  const c = (content ?? {}) as Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
  const use = (u: unknown) => (Array.isArray(u) && u.length ? u : null);
  const packUsage = use(c.pack?.usage);
  const stickers: Sticker[] = Object.entries(c.images && typeof c.images === "object" ? c.images : {}).flatMap(([shortcode, img]: [string, any]) => { // eslint-disable-line @typescript-eslint/no-explicit-any
    const usage = use(img?.usage) ?? packUsage;
    if (typeof img?.url !== "string" || !img.url.startsWith("mxc://") || (usage && !usage.includes("sticker"))) return [];
    return [{ shortcode, url: img.url, body: typeof img.body === "string" && img.body ? img.body : shortcode, ...(img.info && typeof img.info === "object" && { info: img.info }) }];
  });
  if (!stickers.length) return null;
  const name = typeof c.pack?.display_name === "string" && c.pack.display_name ? c.pack.display_name : fallbackName;
  return { id, name, avatar: typeof c.pack?.avatar_url === "string" ? c.pack.avatar_url : undefined, stickers };
}

// ---------- room admin ----------

/** "مدیر" / "ناظر" / "سطح ۲۵"; null for plain members. */
export const roleLabel = (level: number) => (level >= 100 ? "مدیر" : level >= 50 ? "ناظر" : level > 0 ? `سطح ${num(level)}` : null);

/** Knocking needs room version 7+; unknown or non-numeric (experimental) versions count as unsupported. */
export const supportsKnock = (version: string | undefined) => /^\d+$/.test(version ?? "") && Number(version) >= 7;

export const JOIN_RULES: Record<string, string> = { public: "عمومی؛ هر کسی می‌تواند بپیوندد", invite: "خصوصی؛ فقط با دعوت", knock: "با درخواست عضویت", restricted: "اعضای فضا" };
export const HISTORY: Record<string, string> = { shared: "اعضا، از ابتدا", invited: "اعضا، از زمان دعوت", joined: "اعضا، از زمان پیوستن", world_readable: "همه، حتی بدون عضویت" };

type Levels = { users?: Record<string, number>; users_default?: number };
/** Users whose power level differs between two m.room.power_levels contents (missing = users_default). */
export function levelChanges(prev: Levels, next: Levels) {
  const at = (c: Levels, id: string) => c.users?.[id] ?? c.users_default ?? 0;
  const ids = new Set([...Object.keys(prev.users ?? {}), ...Object.keys(next.users ?? {})]);
  return [...ids].filter((id) => at(prev, id) !== at(next, id)).map((id) => ({ id, from: at(prev, id), to: at(next, id) }));
}

/** Scale (w, h) so the longer side is at most `max`; never upscales. */
export function fitSize(w: number, h: number, max = 1280) {
  const k = Math.min(1, max / Math.max(w, h));
  return { w: Math.round(w * k), h: Math.round(h * k) };
}
