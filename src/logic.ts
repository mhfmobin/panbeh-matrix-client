// Pure helpers — no SDK imports so `node --test` can run them directly.
import { LOCALE, t } from "./i18n.ts";

/** album: "t:<id>" for Panbeh's tagged albums, "h" for media that may group by the heuristic (uncaptioned, close in time). */
export type Msg = { id: string; sender: string; ts: number; kind: "msg" | "notice"; album?: string };
export type Row =
  | { type: "day"; key: string; label: string }
  | { type: "notice"; key: string; id: string }
  | { type: "unread"; key: "unread" }
  | { type: "msg"; key: string; id: string; first: boolean; last: boolean }
  /** photos/videos shown as one grid; id is the last one's */
  | { type: "album"; key: string; id: string; ids: string[]; first: boolean; last: boolean };

// formatters built once: toLocale*String builds a new one per call
const fmt = (o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(LOCALE, o);
const NUM = new Intl.NumberFormat(LOCALE), TIME = fmt({ hour: LOCALE === "fa-IR" ? "2-digit" : "numeric", minute: "2-digit" }), YEAR = fmt({ year: "numeric" }),
  DAY_MONTH = fmt({ month: "long", day: "numeric" }), DAY_MONTH_YEAR = fmt({ month: "long", day: "numeric", year: "numeric" }),
  WEEKDAY = fmt({ weekday: "short" }), SHORT_DATE = fmt({ day: "numeric", month: "short" });
export const num = (n: number) => NUM.format(n);

const GROUP_GAP = 5 * 60_000;
const day = (ts: number) => new Date(ts).toDateString();
const groups = (a: Msg | undefined, b: Msg | undefined) =>
  !!a && !!b && a.kind === "msg" && b.kind === "msg" &&
  a.sender === b.sender && b.ts - a.ts < GROUP_GAP && day(a.ts) === day(b.ts);

const ALBUM_MAX = 10, ALBUM_GAP = 10_000;
/** `m` joins the album `prev` ends: same sender and day, and the same tag, or both untagged and seconds apart. */
const joinsAlbum = (prev: Msg, m: Msg, size: number) =>
  !!prev.album && prev.album === m.album && size < ALBUM_MAX && prev.sender === m.sender && day(prev.ts) === day(m.ts)
  && (prev.album !== "h" || m.ts - prev.ts <= ALBUM_GAP);

/** Timeline rows: day separators + messages flagged as first/last of a same-sender burst, consecutive album members
 *  as one row, plus an "unread" divider after `unreadAfter` (a Msg id) when anything follows it (it splits albums too). */
export function buildRows(msgs: Msg[], now = Date.now(), unreadAfter?: string): Row[] {
  const units: { m: Msg; ids: string[] }[] = []; // an album counts as its last message
  for (const m of msgs) {
    const u = units.at(-1);
    if (u && u.m.id !== unreadAfter && joinsAlbum(u.m, m, u.ids.length)) { u.m = m; u.ids.push(m.id); }
    else units.push({ m, ids: [m.id] });
  }
  const rows: Row[] = [];
  let prevDay = "";
  units.forEach(({ m, ids }, i) => {
    if (day(m.ts) !== prevDay) {
      prevDay = day(m.ts);
      rows.push({ type: "day", key: "day-" + prevDay, label: dayLabel(m.ts, now) });
    }
    const prev = units[i - 1]?.m, next = units[i + 1]?.m;
    // the divider splits a burst
    const first = !groups(prev, m) || prev?.id === unreadAfter, last = !groups(m, next) || m.id === unreadAfter;
    if (m.kind === "notice") rows.push({ type: "notice", key: m.id, id: m.id });
    else if (ids.length > 1) rows.push({ type: "album", key: ids[0], id: m.id, ids, first, last });
    else rows.push({ type: "msg", key: m.id, id: m.id, first, last });
    if (m.id === unreadAfter && i < units.length - 1) rows.push({ type: "unread", key: "unread" });
  });
  return rows;
}

export function dayLabel(ts: number, now = Date.now()) {
  const d = new Date(ts), n = new Date(now);
  const diff = Math.round((new Date(n.toDateString()).getTime() - new Date(d.toDateString()).getTime()) / 86_400_000);
  if (diff === 0) return t("day.today");
  if (diff === 1) return t("day.yesterday");
  // Jalali years roll over at Nowruz, not Jan 1, so compare them in that calendar
  return (YEAR.format(d) === YEAR.format(n) ? DAY_MONTH : DAY_MONTH_YEAR).format(d);
}

/** Chat-list timestamp: 14:05 today, "Mon" this week, "12 Mar" otherwise. */
export function listTime(ts: number, now = Date.now()) {
  if (!(ts > 0)) return "";
  const d = new Date(ts);
  if (day(ts) === day(now)) return TIME.format(d);
  if (now - ts < 6 * 86_400_000) return WEEKDAY.format(d);
  return SHORT_DATE.format(d);
}

export const clock = (ts: number) => TIME.format(ts);

export type RoomInfo = {
  id: string; isDM: boolean; unread: number; spaces: string[];
  marked?: boolean; archived?: boolean; muted?: boolean; // marked = "mark as unread"
};
export type Folder = { id: string; label: string };

export const BASE_FOLDERS: Folder[] = [
  { id: "all", label: t("folder.all") },
  { id: "unread", label: t("folder.unread") },
  { id: "dms", label: t("folder.dms") },
  { id: "groups", label: t("folder.groups") },
];

/** Folders in the user's saved order. "all" always leads; folders the order doesn't know (a newly joined space)
 *  follow in their default order, and ids that no longer exist are ignored. */
export function applyFolderOrder(folders: Folder[], order: string[]): Folder[] {
  const rank = new Map(order.map((id, i) => [id, i] as const));
  const rest = folders.filter((f) => f.id !== "all");
  const known = rest.filter((f) => rank.has(f.id)).sort((a, b) => rank.get(a.id)! - rank.get(b.id)!);
  return [...folders.filter((f) => f.id === "all"), ...known, ...rest.filter((f) => !rank.has(f.id))];
}

/** `ids` with the one at `from` moved to `to`. The first (the "all" tab) neither moves nor gets displaced. */
export function moveFolder(ids: string[], from: number, to: number): string[] {
  if (from < 1 || to < 1 || from >= ids.length || to >= ids.length || from === to) return ids;
  const next = ids.slice();
  next.splice(to, 0, ...next.splice(from, 1));
  return next;
}

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

/** Room names for the SDK's roomNameGenerator; null keeps an actual name. `inviter` names an invite with no members known. */
export function roomName(s: NameState, inviter?: string): string | null {
  if ("name" in s) return null;
  if ("names" in s && s.names.length) {
    const [a, b] = s.names, others = s.count - 1;
    const names = s.names.length === 1 && others <= 1 ? a
      : s.names.length === 2 && others <= 2 ? t("roomName.two", { a, b })
      : t("roomName.more", { a, count: others - 1 });
    return s.subtype === "Inviting" ? t("roomName.inviting", { names }) : names;
  }
  const old = "oldName" in s && s.oldName;
  return inviter ?? (old ? t("roomName.emptyWas", { old }) : t("roomName.empty"));
}

/** Suggested #address localpart from a room name; Latin letters/digits only (Farsi names give ""). */
export const aliasLocalpart = (name: string) =>
  name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");

/** Search-term folding: case, Arabic/Persian ي ك, and ZWNJ don't matter. */
export const normalize = (s: string) => s.toLowerCase().replace(/ي/g, "ی").replace(/ك/g, "ک").replace(/\u200c/g, "");

/** Where `term` matches `text` (both normalized), as [start, end) in the original text: normalizing drops ZWNJs, which shifts indices. */
export function matchRange(text: string, term: string): [number, number] | null {
  const t = normalize(term);
  if (!t) return null;
  let norm = "";
  const at: number[] = []; // index in `text` of each char of `norm`
  for (let i = 0; i < text.length; i++) {
    const n = normalize(text[i]);
    norm += n;
    for (let k = 0; k < n.length; k++) at.push(i);
  }
  const i = norm.indexOf(t);
  return i < 0 ? null : [at[i], at[i + t.length - 1] + 1];
}

export const stamp = (ts: number, now = Date.now()) => t("time.dayClock", { day: dayLabel(ts, now), time: clock(ts) });

/** Last-seen text from m.presence; null when the server told us nothing. */
export function lastSeen(online: boolean, activeTs: number | undefined, now = Date.now()) {
  if (online) return t("seen.online");
  if (!activeTs || activeTs <= 0) return null;
  const min = Math.floor((now - activeTs) / 60_000);
  if (min < 1) return t("seen.justNow");
  if (min < 60) return t("seen.minutes", { count: min });
  return t("seen.at", { day: day(activeTs) === day(now) ? t("day.today") : dayLabel(activeTs, now), time: clock(activeTs) });
}

/** "۱:۰۵" from milliseconds. */
export function fmtDuration(ms: number) {
  const s = Math.max(0, Math.round(ms / 1000));
  return `${num(Math.floor(s / 60))}:${num(s % 60).padStart(2, num(0))}`;
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

export const neshanUrl = (lat: number, lon: number) => `https://neshan.org/maps/@${lat},${lon},16z`;

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
/** `:code:` for any of `codes`, the code captured (longest first, so `:cat2:` isn't read as `:cat:`); null for none. */
export function emoteRe(codes: Iterable<string>): RegExp | null {
  const c = [...codes].sort((a, b) => b.length - a.length).map(escRe);
  return c.length ? new RegExp(`:(${c.join("|")}):`, "g") : null;
}

/** The composer's ⌫: a selection goes whole, else the custom emoji (":code:" of `codes`) or the one character
 *  (grapheme, so 👨‍👩‍👧 and 🇮🇷 go in one press) before the caret `a`. Returns the text and the new caret. */
export function deleteBack(text: string, a: number, b: number, codes: Iterable<string>): [string, number] {
  if (a !== b) return [text.slice(0, a) + text.slice(b), a];
  const before = text.slice(0, a), re = emoteRe(codes);
  const n = (re && before.match(new RegExp(`(?:${re.source})$`))?.[0].length) || ([...new Intl.Segmenter().segment(before)].at(-1)?.segment.length ?? 0);
  return [text.slice(0, a - n) + text.slice(a), a - n];
}

/** Markdown subset + "@Name" mentions as matrix.to links + ":code:" custom emoji (code → mxc).
 *  Everything that must not be re-parsed (code, links, mentions, emoji) is stashed behind \0N\0 placeholders until the end.
 *  null when nothing got formatted, so plain messages send no formatted_body. */
export function formatMessage(text: string, mentions: { name: string; id: string }[], emotes: ReadonlyMap<string, string> = new Map()): { html: string; ids: string[] } | null {
  const stash: string[] = [];
  const hold = (html: string) => `\0${stash.push(html) - 1}\0`;
  let t = text.replaceAll("\0", "");
  t = t.replace(/\n?```(?:([\w+-]*)\n)?([\s\S]*?)\n?```\n?/g, (_, lang, c) => `\n${hold(`<pre><code${lang ? ` class="language-${lang}"` : ""}>${esc(c)}</code></pre>`)}\n`); // a line of its own, out of any quote or list
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
  const re = emoteRe(emotes.keys());
  if (re) {
    t = t.replace(re,(m, code) => hold(`<img data-mx-emoticon src="${esc(emotes.get(code)!)}" alt="${esc(m)}" title="${esc(m)}" height="32">`));
  }

  t = esc(t);
  t = t.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g, (_, label, url) => hold(`<a href="${url}">${label}</a>`));
  // block lines: "> " quotes, "# " headings (up to ###), "- "/"* " and "1. " lists; runs of quotes or list items group
  const parts: { html: string; tag: string; attr?: string }[] = [];
  for (const line of t.split("\n")) {
    let m: RegExpExecArray | null, tag = "", html = line, attr;
    if ((m = /^\0(\d+)\0$/.exec(line)) && stash[+m[1]].startsWith("<pre>")) tag = "pre"; // already its own block
    else if (line.startsWith("&#62; ")) { tag = "blockquote"; html = line.slice(6); }
    else if ((m = /^(#{1,3}) +(.+)/.exec(line))) { tag = "h" + m[1].length; html = m[2]; }
    else if ((m = /^[-*] +(.+)/.exec(line))) { tag = "ul"; html = `<li>${m[1]}</li>`; }
    else if ((m = /^([0-9\u06f0-\u06f9]{1,9})\. +(.+)/.exec(line))) {
      tag = "ol"; html = `<li>${m[2]}</li>`;
      const n = Number(m[1].replace(/[\u06f0-\u06f9]/g, (d) => String(d.charCodeAt(0) - 0x6f0)));
      if (n !== 1) attr = ` start="${n}"`;
    }
    const last = parts[parts.length - 1];
    if (tag && last?.tag === tag && !tag.startsWith("h")) last.html += (tag === "blockquote" ? "<br>" : "") + html;
    else parts.push({ html, tag, attr });
  }
  let h = parts.map((p, i) => (p.tag && p.tag !== "pre" ? `<${p.tag}${p.attr ?? ""}>${p.html}</${p.tag}>` : p.html) + (i < parts.length - 1 && !p.tag && !parts[i + 1].tag ? "\n" : "")).join("");
  const wrap = (re: RegExp, open: string, close: string) => (h = h.replace(re, `${open}$1${close}`));
  wrap(/\*\*([^\s*](?:[^*\n]*?[^\s*])?)\*\*/g, "<strong>", "</strong>");
  wrap(/(?<![\p{L}\p{N}_])__([^\s_](?:[^_\n]*?[^\s_])?)__(?![\p{L}\p{N}_])/gu, "<u>", "</u>");
  wrap(/~~([^\s~](?:[^~\n]*?[^\s~])?)~~/g, "<del>", "</del>");
  wrap(/\|\|([^\s|](?:[^|\n]*?[^\s|])?)\|\|/g, "<span data-mx-spoiler>", "</span>");
  // opening marker not after a word char, closing not before one: snake_case and 2*3*4 stay literal
  h = h.replace(/(?<![\p{L}\p{N}_*])([*_])(?=\S)((?:(?!\1)[^\n])*?\S)\1(?![\p{L}\p{N}_*])/gu, "<em>$2</em>");
  h = h.replace(/\n/g, "<br>"); // before restoring, so <pre> keeps its newlines
  while (h.includes("\0")) h = h.replace(/\0(\d+)\0/g, (_, n) => stash[+n]);
  const clean = esc(text.replaceAll("\0", "")).replace(/\n/g, "<br>");
  return used.size || h !== clean ? { html: h, ids: [...used] } : null;
}

/** Formatting from the composer's bar: wraps the selection [a, b) in `mark`, or unwraps it when it's already marked
 *  (in a run of "*", odd = italic, two or more = bold). The selection drops its edge spaces first; multi-line code
 *  gets a ``` fence. The result keeps the same text selected. */
export function toggleWrap(text: string, a: number, b: number, mark: string) {
  while (a < b && /\s/.test(text[a])) a++;
  while (b > a && /\s/.test(text[b - 1])) b--;
  if (a === b) return null;
  const sel = text.slice(a, b);
  let o = mark, c = mark, on: boolean;
  if (mark === "`" && sel.includes("\n")) { o = "```\n"; c = "\n```"; on = text.slice(a - o.length, a) === o && text.startsWith(c, b); }
  else {
    let l = 0, r = 0;
    while (text[a - 1 - l] === mark[0]) l++;
    while (text[b + r] === mark[0]) r++;
    const n = Math.min(l, r);
    on = mark === "*" ? n % 2 === 1 : n >= mark.length;
  }
  return on
    ? { text: text.slice(0, a - o.length) + sel + text.slice(b + c.length), a: a - o.length, b: b - o.length }
    : { text: text.slice(0, a) + o + sel + c + text.slice(b), a: a + o.length, b: b + o.length };
}

/** "> " (`prefix`) added to every line the selection touches, or taken off when they all have it; the lines end up selected. */
export function prefixLines(text: string, a: number, b: number, prefix: string) {
  const s = text.lastIndexOf("\n", a - 1) + 1, e = text.indexOf("\n", Math.max(a, b - 1)), end = e < 0 ? text.length : e;
  const lines = text.slice(s, end).split("\n"), on = lines.every((l) => l.startsWith(prefix));
  const out = lines.map((l) => (on ? l.slice(prefix.length) : prefix + l)).join("\n");
  return { text: text.slice(0, s) + out + text.slice(end), a: s, b: s + out.length };
}

// ---------- custom emoji & stickers (MSC2545 image packs) ----------
export type Usage = "emoticon" | "sticker";
/** Whether a custom emoji takes the text color (so a black one turns white on a dark background): "auto" = when its pixels
 *  are dark and colorless. Set on an image or a whole pack under TINT, our own key (other clients ignore it). */
export type Tint = "auto" | "always" | "never";
export const TINT = "app.panbeh.tint";
export type PackImage = { url: string; body?: string; info?: Record<string, unknown>; usage?: Usage[]; [TINT]?: Tint };
export type PackContent = { pack?: { display_name?: string; avatar_url?: string; usage?: Usage[]; [TINT]?: Tint }; images?: Record<string, PackImage> };
/** The image's own setting, else its pack's, else "auto". */
export const tintOf = (p: PackContent, i: PackImage): Tint => i[TINT] ?? p.pack?.[TINT] ?? "auto";
/** "auto"'s test on RGBA pixels (not premultiplied): ≥95% of what's visible is gray, and it's dark on average,
 *  so a black glyph with gray shading counts and anything colored or light doesn't. */
export function isDarkMono(px: ArrayLike<number>) {
  let n = 0, gray = 0, lum = 0;
  for (let i = 0; i < px.length; i += 4) {
    const a = px[i + 3] / 255, r = px[i], g = px[i + 1], b = px[i + 2];
    if (a < 0.1) continue;
    n += a;
    if (Math.max(r, g, b) - Math.min(r, g, b) < 40) gray += a;
    lum += (a * (0.2126 * r + 0.7152 * g + 0.0722 * b)) / 255;
  }
  return n > 0 && gray / n >= 0.95 && lum / n < 0.4;
}
/** A pack's images usable as `use`: by the image's usage, else the pack's, else both (absent or empty = both, per MSC2545). */
export const packImages = (p: PackContent, use: Usage): [string, PackImage][] =>
  Object.entries(p.images ?? {}).filter(([, i]) => {
    if (typeof i?.url !== "string" || !i.url.startsWith("mxc://")) return false;
    const u = i.usage?.length ? i.usage : p.pack?.usage?.length ? p.pack.usage : null;
    return !u || u.includes(use);
  });
// shortcode characters: Latin or Persian/Arabic letters, digits, _ - and the Persian half-space (ZWNJ)
const CODE_CHAR = /(?=\p{L})[\p{sc=Latin}\p{sc=Arabic}]|\p{Nd}|[_\u200c-]/u;
/** A shortcode as typed: characters outside CODE_CHAR dropped (`sep` ""), or runs of them made one `sep`. */
export function cleanCode(s: string, sep = "") {
  const out = [...s].map((c) => (CODE_CHAR.test(c) ? c : sep)).join("");
  return (sep ? out.replace(new RegExp(`${escRe(sep)}{2,}`, "g"), sep) : out).slice(0, 32);
}
/** A shortcode from a file name: no extension, other characters as _. */
export const codeFrom = (name: string) => cleanCode(name.replace(/\.[^.]*$/, ""), "_").replace(/^_+|_+$/g, "") || "emoji";
/** How many images a pack may hold (Telegram's numbers). */
export const PACK_CAP: Record<Usage, number> = { emoticon: 200, sticker: 120 };
/** Emoji and stickers may be at most 2:1 either way: the size after padding the short side to fit. */
export function padToRatio(w: number, h: number, max = 2) {
  return w > h * max ? { w, h: Math.ceil(w / max) } : h > w * max ? { w: Math.ceil(h / max), h } : { w, h };
}
/** `code`, or code_2, code_3… when the pack already has it. */
export function freeCode(code: string, taken: Record<string, unknown>) {
  let c = code, n = 1;
  while (Object.hasOwn(taken, c)) c = `${code}_${++n}`;
  return c;
}

const TLDS = "com|org|net|edu|gov|int|io|ir|co|me|app|dev|info|biz|ai|tv|xyz|ly|gl|us|uk|de|fr|ru|tech|online|site|chat|link|page";
/** What counts as a web link in plain text: http(s)://…, www.…, or a bare domain with a well-known TLD (not in emails). */
export const LINK_SRC =
  "(?:https?:\\/\\/|www\\.)[^\\s<]+[^\\s<.,;:!?)\"'`]" +
  "|(?<![\\w@.\\/-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+(?:" + TLDS + ")(?![\\w@-])(?::\\d+)?(?:\\/[^\\s<]*[^\\s<.,;:!?)\"'`])?";
/** A #hashtag (needs the u flag): letters/digits/_ with at least one letter, ZWNJ only inside; not a#b, url/#frag, &#38;#1; or a #room:alias. */
export const HASHTAG_SRC =
  "(?<![\\p{L}\\p{N}\\p{M}_#&/])#(?=[\\p{N}_]*\\p{L})(?:[\\p{L}\\p{N}\\p{M}_]|\\u200c(?=[\\p{L}\\p{N}\\p{M}_]))+(?![\\p{L}\\p{N}\\p{M}_]|:[\\p{L}\\p{N}])";
/** The hashtags in text, without the "#", as written. */
export const hashtagsOf = (text: string) => [...text.matchAll(new RegExp(HASHTAG_SRC, "gu"))].map((m) => m[0].slice(1));
/** The tag when `term` is exactly one hashtag ("#tag"), normalized; else null. */
export const soleHashtag = (term: string) => {
  const t = term.trim(), tags = hashtagsOf(t);
  return tags.length === 1 && t === "#" + tags[0] ? normalize(tags[0]) : null;
};
/** href for a link found by LINK_SRC: scheme-less ones get https://. */
export const linkHref = (u: string) => (/^(?:https?:|matrix:)/i.test(u) ? u : "https://" + u);
/** Distinct web links in text (same pattern as the bubble's linkify), as hrefs. */
export const extractLinks = (text: string) => [...new Set((text.match(new RegExp(LINK_SRC, "gi")) ?? []).map(linkHref))];
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

/** How many of a thread's `replies` (oldest first) after the newest read one `count`; null if none is read and some
 *  aren't loaded (`complete` false): the read point is further back than we can see. */
export function unreadSince<T>(replies: T[], read: (e: T) => boolean, count: (e: T) => boolean, complete: boolean): number | null {
  let n = 0;
  for (let i = replies.length - 1; i >= 0; i--) {
    if (read(replies[i])) return n;
    if (count(replies[i])) n++;
  }
  return complete ? n : null;
}

/** An unmuted archived chat with new messages shows in the main list until it's read again. */
export const inArchive = (r: RoomInfo) => !!r.archived && !(r.unread > 0 && !r.muted);

/** Chat list order: invites, then pinned, then newest. */
export const byListOrder = (a: { invite: boolean; pinned?: boolean; ts: number }, b: typeof a) =>
  +b.invite - +a.invite || +!!b.pinned - +!!a.pinned || b.ts - a.ts;
/** A looping, silent video (mautrix's flags, also set by the Telegram bridge) or an animated gif image. */
export const isGif = (c: { msgtype?: string; info?: Record<string, unknown> }) =>
  !!c.info?.["fi.mau.gif"] || (c.msgtype === "m.image" && c.info?.mimetype === "image/gif");

export type Gif = { msgtype: string; body: string; info?: Record<string, unknown>; url?: string; file?: { url: string } };
const gifKey = (g: Gif) => g.url ?? g.file?.url;
// ponytail: capped at 100, account data tops out around 64KB
/** Saved gifs with `g` first: re-saving moves it up instead of duplicating. */
export const withGif = (list: Gif[], g: Gif) => [g, ...list.filter((x) => gifKey(x) !== gifKey(g))].slice(0, 100);
export const withoutGif = (list: Gif[], g: Gif) => list.filter((x) => gifKey(x) !== gifKey(g));
export const hasGif = (list: Gif[], g: Gif) => list.some((x) => gifKey(x) === gifKey(g));

// ---------- room admin ----------

/** "مدیر" / "ناظر" / "سطح ۲۵"; null for plain members. */
// Infinity = a room v12 creator, who outranks everyone
export const roleLabel = (level: number) => (level === Infinity ? t("role.owner") : level >= 100 ? t("role.admin") : level >= 50 ? t("role.mod") : level > 0 ? t("role.level", { level }) : null);

export type PowerLevels = {
  users?: Record<string, number>; users_default?: number; events?: Record<string, number>; events_default?: number; state_default?: number;
  invite?: number; kick?: number; ban?: number; redact?: number; notifications?: Record<string, number>;
};
const PL_DEFAULTS: Record<string, number> = { invite: 0, kick: 50, ban: 50, redact: 50, events_default: 0, state_default: 50 };

/** A power-level setting by key, with the spec's defaults: "kick", "notifications.room", "events.<state type>" (falls back
 *  to state_default). "events_default" means sending messages, so an m.room.message override wins over it. */
export function levelAt(pl: PowerLevels, key: string): number {
  if (key.startsWith("events.")) return pl.events?.[key.slice(7)] ?? pl.state_default ?? 50;
  if (key.startsWith("notifications.")) return pl.notifications?.[key.slice(14)] ?? 50;
  if (key === "events_default") return pl.events?.["m.room.message"] ?? pl.events_default ?? 0;
  return (pl as Record<string, number | undefined>)[key] ?? PL_DEFAULTS[key] ?? 50;
}

/** New m.room.power_levels content with one setting changed (keys as in levelAt). */
export function withLevel(pl: PowerLevels, key: string, level: number): PowerLevels {
  if (key.startsWith("events.")) return { ...pl, events: { ...pl.events, [key.slice(7)]: level } };
  if (key.startsWith("notifications.")) return { ...pl, notifications: { ...pl.notifications, [key.slice(14)]: level } };
  if (key === "events_default") {
    const { ["m.room.message"]: _, ...events } = pl.events ?? {};
    return { ...pl, events_default: level, events };
  }
  return { ...pl, [key]: level };
}

/** Select options for a level: the presets I may grant, plus the current value if it's a custom one. */
export function levelOptions(presets: [number, string][], mine: number, current: number): [number, string][] {
  const o = presets.filter(([l]) => l <= mine || l === current);
  if (!o.some(([l]) => l === current)) o.push([current, current === Infinity ? t("role.owner") : current > 0 ? t("role.level", { level: current }) : t("role.member")]);
  return o.sort((a, b) => a[0] - b[0]);
}

/** Room version features; unknown or non-numeric (experimental) versions count as unsupported. */
const versionAtLeast = (version: string | undefined, n: number) => /^\d+$/.test(version ?? "") && Number(version) >= n;
export const supportsKnock = (version: string | undefined) => versionAtLeast(version, 7);
export const supportsRestricted = (version: string | undefined) => versionAtLeast(version, 8);
export const supportsKnockRestricted = (version: string | undefined) => versionAtLeast(version, 10);

/** The server's default room version when the room should move to it: an older number, or a version the server marks
 *  unstable or doesn't know. Null when it's fine (or the server didn't say). */
export function upgradeTarget(version: string, rv?: { default: string; available: Record<string, string> }): string | null {
  if (!rv?.default || version === rv.default) return null;
  if (versionAtLeast(version, 0) && versionAtLeast(rv.default, 0)) return Number(version) < Number(rv.default) ? rv.default : null;
  return rv.available[version] !== "stable" ? rv.default : null;
}

export const JOIN_RULES: Record<string, string> = {
  public: t("join.public"), invite: t("join.invite"), knock: t("join.knock"),
  restricted: t("join.restricted"), knock_restricted: t("join.knockRestricted"),
};
export const HISTORY: Record<string, string> = { shared: t("history.shared"), invited: t("history.invited"), joined: t("history.joined"), world_readable: t("history.world") };

const ERRORS: Record<string, string> = {
  M_FORBIDDEN: t("err.forbidden"),
  M_ROOM_IN_USE: t("err.roomInUse"),
  M_EXCLUSIVE: t("err.exclusive"),
  M_UNSUPPORTED_ROOM_VERSION: t("err.roomVersion"),
  M_TOO_LARGE: t("err.tooLarge"),
  M_RESOURCE_LIMIT_EXCEEDED: t("err.resourceLimit"),
  M_NOT_FOUND: t("err.notFound"),
  M_UNKNOWN_TOKEN: t("err.unknownToken"),
  M_CANNOT_LEAVE_SERVER_NOTICE_ROOM: t("err.noticeRoom"),
  M_GUEST_ACCESS_FORBIDDEN: t("err.guest"),
  M_USER_IN_USE: t("err.userInUse"),
  M_INVALID_USERNAME: t("err.badUsername"),
  M_WEAK_PASSWORD: t("err.weakPassword"),
};

/** Our text for a Matrix error code; undefined = show the server's own message. */
export function errorText(errcode: string | undefined, data?: { retry_after_ms?: number }): string | undefined {
  if (errcode === "M_LIMIT_EXCEEDED") {
    const s = Math.ceil((data?.retry_after_ms ?? 0) / 1000);
    return s > 0 ? t("err.rateLimitIn", { count: s }) : t("err.rateLimit");
  }
  return errcode ? ERRORS[errcode] : undefined;
}

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

/** Direction of a message's text the way dir=auto picks it (first letter), but the same everywhere: WebViews disagree on text
 *  with no letters (numbers, emoji), and the time/ticks have to sit on the side the text leaves free. */
export function textDir(s: string): "rtl" | "ltr" {
  const letter = /\p{L}/u.exec(s)?.[0];
  return letter && /[\p{Script=Arabic}\p{Script=Hebrew}\p{Script=Syriac}\p{Script=Thaana}\p{Script=Nko}]/u.test(letter) ? "rtl" : "ltr";
}

type RingContent = { notification_type?: string; "m.mentions"?: { room?: boolean; user_ids?: string[] } };
/** An m.rtc.notification (MSC4075) that should ring `me` now: a "ring" (not a group "notification"), aimed at us or the room, before `until`. */
const aimedAt = (c: RingContent, me: string) => !!(c["m.mentions"]?.room || c["m.mentions"]?.user_ids?.includes(me));
export const isRing = (c: RingContent, until: number, me: string, now = Date.now()) =>
  c.notification_type === "ring" && aimedAt(c, me) && until > now; // NaN until = no
/** A group call that just started (a "notification", not a ring): worth a quiet notification. */
export const isGroupCallAlert = (c: RingContent, until: number, me: string, now = Date.now()) =>
  c.notification_type === "notification" && aimedAt(c, me) && until > now;

type Invite = { lifetime?: number; invitee?: string; offer?: { sdp?: string } };
/** A legacy 1:1 m.call.invite (old Element, FluffyChat, Nheko…) that should ring `me` now: unexpired and not aimed at someone else. */
export const isLegacyRing = (c: Invite, ts: number, me: string, now = Date.now()) =>
  ts + (c.lifetime ?? 0) > now && (!c.invitee || c.invitee === me);
export const isVideoOffer = (c: Invite) => /^m=video/m.test(c.offer?.sdp ?? "");

// ---------- how a call went (for its line in the timeline) ----------

export type CallEv = { id?: string; type: string; sender: string; ts: number; content: Record<string, any> };
/** ringing/ongoing: nothing to add yet. ended: answered, for `duration` ms. */
export type CallOutcome = { state: "ringing" | "ongoing" | "missed" | "declined" | "ended"; duration?: number };
const MEMBER = "org.matrix.msc3401.call.member";
const isEmpty = (c: object | undefined) => !c || !Object.keys(c).length;

/** A legacy m.call.invite, from the events after it in the room (oldest first). */
export function legacyOutcome(invite: CallEv, after: CallEv[], now = Date.now()): CallOutcome {
  const same = after.filter((e) => e.content?.call_id === invite.content.call_id);
  const answer = same.find((e) => e.type === "m.call.answer");
  const end = same.find((e) => e.type === "m.call.hangup" || e.type === "m.call.reject");
  if (answer) return end ? { state: "ended", duration: end.ts - answer.ts } : { state: "ongoing" };
  if (end?.type === "m.call.reject") return { state: "declined" };
  return end || invite.ts + (invite.content.lifetime ?? 0) <= now ? { state: "missed" } : { state: "ringing" };
}

/** A MatrixRTC ring (m.rtc.notification) ringing until `until`: answered once someone else's call membership shows up, over when one side leaves. */
export function rtcOutcome(ring: CallEv, until: number, after: CallEv[], now = Date.now()): CallOutcome {
  const members = after.filter((e) => e.type === MEMBER);
  const joined = members.find((e) => e.sender !== ring.sender && !isEmpty(e.content) && e.ts <= until);
  if (joined) {
    const left = members.find((e) => e.ts >= joined.ts && isEmpty(e.content)); // 1:1: whoever leaves first ends it
    return left ? { state: "ended", duration: left.ts - joined.ts } : { state: "ongoing" };
  }
  if (after.some((e) => e.type.endsWith("rtc.decline") && e.content?.["m.relates_to"]?.event_id === ring.id)) return { state: "declined" };
  const gaveUp = members.some((e) => e.sender === ring.sender && isEmpty(e.content));
  return gaveUp || until <= now ? { state: "missed" } : { state: "ringing" };
}

// ---------- call stats (the overlay on each feed) ----------

/** One feed's numbers; bytes/ts carry over to the next sample for the bitrate. loss in %, rtt in ms. */
export type Stats = { w?: number; h?: number; fps?: number; kbps?: number; loss?: number; rtt?: number; codec?: string; bytes: number; ts: number };

/**
 * A WebRTC stats report (its values) → one feed's numbers: received (in) or sent (out). Video wins over audio; simulcast layers add up.
 * prev: the last sample, for the bitrate.
 */
export function parseStats(entries: Iterable<Record<string, any>>, dir: "in" | "out", prev?: Stats): Stats {
  const all = [...entries], type = dir === "in" ? "inbound-rtp" : "outbound-rtp";
  const rtp = all.filter((e) => e.type === type);
  const kind = rtp.some((e) => e.kind === "video") ? "video" : "audio";
  const mine = rtp.filter((e) => e.kind === kind);
  const max = (k: string) => mine.reduce<number | undefined>((m, e) => typeof e[k] === "number" && e[k] > (m ?? -1) ? e[k] : m, undefined);
  const sum = (k: string) => mine.reduce((n, e) => n + (e[k] ?? 0), 0);
  const ts = mine[0]?.timestamp ?? Date.now(), bytes = sum(dir === "in" ? "bytesReceived" : "bytesSent");
  const s: Stats = { bytes, ts, w: max("frameWidth"), h: max("frameHeight"), fps: max("framesPerSecond") };
  if (prev && ts > prev.ts && bytes >= prev.bytes) s.kbps = Math.round((bytes - prev.bytes) * 8 / (ts - prev.ts));
  if (dir === "in") {
    const lost = sum("packetsLost"), got = sum("packetsReceived");
    if (lost + got > 0) s.loss = Math.round(1000 * lost / (lost + got)) / 10;
  } else {
    const remote = all.filter((e) => e.type === "remote-inbound-rtp" && e.kind === kind && typeof e.fractionLost === "number");
    if (remote.length) s.loss = Math.round(1000 * Math.max(...remote.map((e) => e.fractionLost))) / 10;
  }
  const pair = all.find((e) => e.type === "candidate-pair" && e.nominated && e.state === "succeeded" && typeof e.currentRoundTripTime === "number");
  if (pair) s.rtt = Math.round(pair.currentRoundTripTime * 1000);
  const codec = all.find((e) => e.type === "codec" && e.id === mine[0]?.codecId);
  if (codec?.mimeType) s.codec = String(codec.mimeType).split("/")[1];
  return s;
}

/** "1280×720 · 30fps · 1200kbps · 0.5% · 40ms · VP8" */
export const fmtStats = (s: Stats) => [s.w && s.h ? `${s.w}×${s.h}` : "", s.fps ? `${Math.round(s.fps)}fps` : "", s.kbps !== undefined ? `${s.kbps}kbps` : "",
  s.loss !== undefined ? `${s.loss}%` : "", s.rtt !== undefined ? `${s.rtt}ms` : "", s.codec ?? ""].filter(Boolean).join(" · ");

/** Endpoint name for the net log: /_matrix/client/v3/rooms/!x/send/… → rooms. */
export const endpointOf = (url: string) => new URL(url).pathname.split("/").slice(2).find((x) => !/^(client|media|v\d+|r0|unstable)$/.test(x)) ?? "?";

/** Oldest-first [key, size] entries: the keys to drop, oldest first, so the rest fit in `cap` bytes. */
export function toEvict(entries: [string, number][], cap: number): string[] {
  let total = entries.reduce((n, [, size]) => n + size, 0);
  const out: string[] = [];
  for (const [key, size] of entries) {
    if (total <= cap) break;
    out.push(key);
    total -= size;
  }
  return out;
}

/** The app lock's PIN check value: PBKDF2-SHA-256 over the PIN with a per-device salt, base64. Slow on purpose. */
export async function hashPin(pin: string, salt: string, iterations: number) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: new TextEncoder().encode(salt), iterations }, key, 256);
  return btoa(String.fromCharCode(...new Uint8Array(bits)));
}

/** Wait before the next PIN try after `fails` wrong ones: none for the first 5, then 30s doubling, at most an hour. */
export const lockDelay = (fails: number) => (fails < 5 ? 0 : Math.min(3_600_000, 30_000 * 2 ** (fails - 5)));

/** One axis of a menu opened at `v`: after it, or before it when it would cross `max`; 8px from the edges. */
export const fitMenu = (v: number, size: number, max: number) => Math.max(8, Math.min(v + size > max - 8 ? v - size : v, max - size - 8));

// ---------- media auto-download ----------

export type DlKind = "photos" | "videos" | "files" | "gifs" | "voice";
export type DlWho = { dm: boolean; group: boolean };
/** maxMB: 0 = no limit. voice: voice messages and round videos. */
export type DlRules = { photos: DlWho; videos: DlWho & { maxMB: number }; files: DlWho & { maxMB: number }; gifs: DlWho; voice: DlWho };
export type AutoDl = { mobile: DlRules; wifi: DlRules };
const EVERYWHERE: DlWho = { dm: true, group: true };
export const AUTO_DL_DEFAULT: AutoDl = {
  wifi: { photos: EVERYWHERE, videos: { ...EVERYWHERE, maxMB: 50 }, files: { ...EVERYWHERE, maxMB: 50 }, gifs: EVERYWHERE, voice: EVERYWHERE },
  mobile: { photos: EVERYWHERE, videos: { ...EVERYWHERE, maxMB: 10 }, files: { ...EVERYWHERE, maxMB: 1 }, gifs: EVERYWHERE, voice: EVERYWHERE },
};

/** Stored rules over the defaults, per network and kind, so kinds added later get their defaults. */
export function mergeAutoDl(stored: unknown): AutoDl {
  const s = (stored ?? {}) as Partial<Record<keyof AutoDl, Partial<Record<DlKind, object>>>>;
  const net = (n: keyof AutoDl) => Object.fromEntries((Object.keys(AUTO_DL_DEFAULT[n]) as DlKind[])
    .map((k) => [k, { ...AUTO_DL_DEFAULT[n][k], ...s[n]?.[k] }])) as DlRules;
  return { mobile: net("mobile"), wifi: net("wifi") };
}

/** Whether media of `kind` (and `size` bytes, if known) downloads by itself in this kind of chat. */
export function shouldAutoDl(rules: DlRules, kind: DlKind, isDM: boolean, size?: number) {
  const r = rules[kind];
  if (!(isDM ? r.dm : r.group)) return false;
  return !("maxMB" in r) || !r.maxMB || !size || size <= r.maxMB * 1024 * 1024;
}

// ---------- blurhash (MSC2448: info["xyz.amorgan.blurhash"]) ----------

const B83 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";
const enc83 = (n: number, len: number) => { let s = ""; for (let i = len - 1; i >= 0; i--) s += B83[Math.floor(n / 83 ** i) % 83]; return s; };
const dec83 = (s: string) => [...s].reduce((n, c) => n * 83 + B83.indexOf(c), 0);
const toLinear = (v: number) => { const x = v / 255; return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4; };
const toSrgb = (v: number) => { const x = Math.max(0, Math.min(1, v)); return Math.trunc((x <= 0.0031308 ? x * 12.92 : 1.055 * x ** (1 / 2.4) - 0.055) * 255 + 0.5); };
const signPow = (v: number, e: number) => Math.sign(v) * Math.abs(v) ** e;

/** RGBA pixels (keep them small, ~32px: the cost is pixels × components) → blurhash. */
export function blurhashEncode(px: Uint8ClampedArray, w: number, h: number, cx = 4, cy = 3) {
  const f: number[][] = [];
  for (let j = 0; j < cy; j++) for (let i = 0; i < cx; i++) {
    const norm = i || j ? 2 : 1, rgb = [0, 0, 0];
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const basis = norm * Math.cos((Math.PI * i * x) / w) * Math.cos((Math.PI * j * y) / h), o = 4 * (x + y * w);
      for (let k = 0; k < 3; k++) rgb[k] += basis * toLinear(px[o + k]);
    }
    f.push(rgb.map((v) => v / (w * h)));
  }
  const [dc, ...ac] = f;
  const q = ac.length ? Math.max(0, Math.min(82, Math.floor(Math.max(...ac.flat().map(Math.abs)) * 166 - 0.5))) : 0;
  const maxV = (q + 1) / 166;
  const quant = (v: number) => Math.max(0, Math.min(18, Math.floor(signPow(v / maxV, 0.5) * 9 + 9.5)));
  return enc83(cx - 1 + (cy - 1) * 9, 1) + enc83(q, 1) + enc83((toSrgb(dc[0]) << 16) + (toSrgb(dc[1]) << 8) + toSrgb(dc[2]), 4)
    + ac.map((c) => enc83(quant(c[0]) * 361 + quant(c[1]) * 19 + quant(c[2]), 2)).join("");
}

/** blurhash → w×h RGBA pixels; null if it isn't a valid hash (it comes from other people's events). */
export function blurhashDecode(hash: string, w: number, h: number): Uint8ClampedArray | null {
  if (typeof hash !== "string" || hash.length < 6 || [...hash].some((c) => !B83.includes(c))) return null;
  const n = dec83(hash[0]), cy = Math.floor(n / 9) + 1, cx = (n % 9) + 1;
  if (hash.length !== 4 + 2 * cx * cy) return null;
  const maxV = (dec83(hash[1]) + 1) / 166, dc = dec83(hash.slice(2, 6));
  const colors = [[dc >> 16, (dc >> 8) & 255, dc & 255].map(toLinear)];
  for (let i = 1; i < cx * cy; i++) {
    const v = dec83(hash.slice(4 + 2 * i, 6 + 2 * i));
    colors.push([Math.floor(v / 361), Math.floor(v / 19) % 19, v % 19].map((d) => signPow((d - 9) / 9, 2) * maxV));
  }
  const px = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const rgb = [0, 0, 0];
    for (let j = 0; j < cy; j++) for (let i = 0; i < cx; i++) {
      const basis = Math.cos((Math.PI * x * i) / w) * Math.cos((Math.PI * y * j) / h), c = colors[i + j * cx];
      for (let k = 0; k < 3; k++) rgb[k] += c[k] * basis;
    }
    const o = 4 * (x + y * w);
    px.set([...rgb.map(toSrgb), 255], o);
  }
  return px;
}

// ---------- scheduled messages ----------

/** The days a message can be scheduled for: today, tomorrow, then weekday and date (Jalali), as each day's local midnight. */
export function scheduleDays(now = Date.now(), n = 31) {
  const d = new Date(now);
  return Array.from({ length: n }, (_, i) => {
    const day = new Date(d.getFullYear(), d.getMonth(), d.getDate() + i).getTime();
    return { ts: day, label: i === 0 ? t("day.today") : i === 1 ? t("day.tomorrow") : t("day.weekdayDate", { weekday: WEEKDAY.format(day), date: DAY_MONTH.format(day) }) };
  });
}

/** A length of time in its biggest whole unit: "۳۰ روز", "۵ ساعت", "۱۰ دقیقه". */
export function fmtSpan(ms: number) {
  const [n, unit] = ms >= 86_400_000 ? [ms / 86_400_000, "span.days" as const] : ms >= 3_600_000 ? [ms / 3_600_000, "span.hours" as const] : [ms / 60_000, "span.minutes" as const];
  return t(unit, { count: Math.floor(n) });
}

/** When a scheduled message goes out, for its list row: "امروز ساعت ۲۱:۰۰", "۱۹ مهر ساعت ۰۹:۳۰". */
export const scheduleLabel = (ts: number, now = Date.now()) => t("time.dayAt", { day: dayLabel(ts, now), time: TIME.format(ts) });

// ---------- synced drafts ----------
// In encrypted rooms a draft goes to account data sealed with AES-GCM, under a key HKDF-derived from the key-backup private key:
// every device of ours that holds the backup can read it, the server can't. The room id is bound in, so a draft can't be moved to another room.
const b64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
const unb64 = (s: string) => Uint8Array.from(atob(s), (ch) => ch.charCodeAt(0));
export async function deriveDraftKey(backupKey: Uint8Array<ArrayBuffer>): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey("raw", backupKey, "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey({ name: "HKDF", hash: "SHA-256", salt: new Uint8Array(32), info: new TextEncoder().encode("panbeh drafts") },
    base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}
export async function sealDraft(key: CryptoKey, roomId: string, obj: object): Promise<{ iv: string; ciphertext: string }> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: new TextEncoder().encode(roomId) }, key, new TextEncoder().encode(JSON.stringify(obj)));
  return { iv: b64(iv), ciphertext: b64(new Uint8Array(ct)) };
}
/** Throws on a wrong key, a wrong room or tampering. */
export async function openDraft<T>(key: CryptoKey, roomId: string, c: { iv: string; ciphertext: string }): Promise<T> {
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: unb64(c.iv), additionalData: new TextEncoder().encode(roomId) }, key, unb64(c.ciphertext));
  return JSON.parse(new TextDecoder().decode(pt));
}

/** One page of cached history (historyCache.ts) from `older`: what's kept before the page's start, newest first, one past
 *  the page if there is more. Then the next page starts at a cache token; at the cache's end, at the server's token
 *  where the cached run stops (none: the start of the chat). */
export function cachedSlice<T extends { id: string }>(older: T[], n: number, back: string | null, prefix: string): { chunk: T[]; end?: string } {
  const chunk = older.slice(0, n);
  if (older.length > n) return { chunk, end: prefix + chunk.at(-1)!.id };
  return back ? { chunk, end: back } : { chunk };
}

/** Up to 3 emoji and nothing else (spaces allowed): shown big, without a bubble. A stray U+FE0F after one is allowed:
 *  emojibase (our picker) writes 👍️ ⭐️ ✨️ and ~150 more that way, which isn't their RGI form. */
let EMOJI_ONLY: RegExp;
try { EMOJI_ONLY = new RegExp("^\\s*(?:\\p{RGI_Emoji}\\uFE0F?\\s*){1,3}$", "v"); }
catch { EMOJI_ONLY = /^(?:\p{Extended_Pictographic}\uFE0F?){1,3}$/u; } // an old WebView without the v flag
export const isEmojiOnly = (s: string) => EMOJI_ONLY.test(s);

/** An "em-*" class so a lone emoji or reaction key gets a small bit of infinite motion (see styles.css), "" = none. */
const MOTIONS: [RegExp, string][] = [
  [/❤|❣|💕|💞|💓|💗|💖|💘|💝|🧡|💛|💚|💙|💜|🖤|🤍|🤎|💔/, "em-pulse"],
  [/😂|🤣/, "em-shake"],
  [/✨|💫|⭐|🌟|✴|💥|🎆|🎇/, "em-twinkle"],
  [/🔥/, "em-flicker"],
  [/👋|🖐|✋/, "em-wave"],
  [/🎉|🎊/, "em-bounce"],
  [/😴|💤/, "em-bob"],
];
export const emojiMotion = (s: string) => MOTIONS.find(([re]) => re.test(s))?.[1] ?? "";
