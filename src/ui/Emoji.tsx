import { useEffect, useMemo, useState } from "react";
import { EventType, type MatrixEvent, type Room } from "matrix-js-sdk";
// fetched as files, not bundled as JS: separate chunks, loaded the first time the panel opens
import enUrl from "emojibase-data/en/compact.json?url";
import faUrl from "cldr-annotations-modern/annotations/fa/annotations.json?url";
import { avatarUrl, client } from "../matrix.ts";
import { usePromise } from "../hooks.ts";
import { normalize, stickerPack, type Sticker, type StickerPack } from "../logic.ts";

type Emoji = { u: string; name: string; search: string };
type Raw = { unicode: string; label: string; tags?: string[]; group?: number; order?: number };

// emojibase group ids; 2 = skin-tone/hair components
// ponytail: no skin tones (base emoji only); add a tone picker from `skins` if people ask
const GROUPS: [number, string, string][] = [
  [0, "😀", "شکلک‌ها"], [1, "👋", "آدم‌ها"], [3, "🐱", "حیوانات و طبیعت"], [4, "🍔", "خوراکی‌ها"],
  [5, "✈️", "سفر و مکان‌ها"], [6, "⚽", "فعالیت‌ها"], [7, "💡", "اشیا"], [8, "❤️", "نمادها"], [9, "🏳️", "پرچم‌ها"],
];

const strip = (s: string) => s.replace(/️/g, ""); // CLDR keys drop the emoji variation selector
let data: Promise<Map<number, Emoji[]>> | null = null;

function loadEmoji() {
  data ??= Promise.all([fetch(enUrl).then((r) => r.json()), fetch(faUrl).then((r) => r.json())]).then(([en, fa]: [Raw[], any]) => { // eslint-disable-line @typescript-eslint/no-explicit-any
    const names = new Map<string, { default?: string[]; tts?: string[] }>();
    for (const [k, v] of Object.entries(fa.annotations.annotations)) names.set(strip(k), v as never);
    const groups = new Map<number, Emoji[]>();
    for (const e of en.sort((a, b) => (a.order ?? 0) - (b.order ?? 0))) {
      if (e.group == null || e.group === 2) continue; // no group = bare regional indicators
      const f = names.get(strip(e.unicode));
      const name = f?.tts?.[0] ?? e.label;
      if (!groups.has(e.group)) groups.set(e.group, []);
      groups.get(e.group)!.push({ u: e.unicode, name, search: normalize([name, ...(f?.default ?? []), e.label, ...(e.tags ?? [])].join(" ")) });
    }
    return groups;
  });
  data.catch(() => { data = null; }); // retry next time the panel opens
  return data;
}

const RECENT = "panbeh.recentEmoji";
function recent(): string[] {
  try { return JSON.parse(localStorage.getItem(RECENT) ?? "[]"); } catch { return []; }
}
function remember(u: string) {
  try { localStorage.setItem(RECENT, JSON.stringify([u, ...recent().filter((x) => x !== u)].slice(0, 24))); } catch { /* storage blocked */ }
}

type Props = { onEmoji: (e: string) => void; onClose: () => void; stickers?: { room: Room; onPick: (s: Sticker) => void } };

/** Emoji (+ optional stickers) popover; closes on outside click / Esc. Picking doesn't close it. */
export function EmojiPanel({ onEmoji, onClose, stickers }: Props) {
  const [tab, setTab] = useState<"emoji" | "sticker">("emoji");
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    addEventListener("keydown", k);
    return () => removeEventListener("keydown", k);
  }, [onClose]);
  return (
    <>
      <div className="menu-backdrop" onClick={onClose} />
      <div className="emoji-panel" role="dialog" aria-label="اموجی">
        {stickers && (
          <div className="segmented">
            <button className={tab === "emoji" ? "on" : ""} onClick={() => setTab("emoji")}>اموجی</button>
            <button className={tab === "sticker" ? "on" : ""} onClick={() => setTab("sticker")}>استیکر</button>
          </div>
        )}
        {tab === "emoji" || !stickers ? <EmojiGrid onPick={(u) => { remember(u); onEmoji(u); }} /> : <StickerGrid {...stickers} />}
      </div>
    </>
  );
}

function EmojiGrid({ onPick }: { onPick: (u: string) => void }) {
  const groups = usePromise(useMemo(loadEmoji, []));
  const [rec] = useState(recent);
  const [group, setGroup] = useState(rec.length ? -1 : 0);
  const [q, setQ] = useState("");
  const all = useMemo(() => [...(groups?.values() ?? [])].flat(), [groups]);
  const byU = useMemo(() => new Map(all.map((e) => [e.u, e])), [all]);
  const term = normalize(q.trim());
  const list = !groups ? [] : term ? all.filter((e) => e.search.includes(term)).slice(0, 240)
    : group === -1 ? rec.map((u) => byU.get(u) ?? { u, name: u, search: "" }) : groups.get(group) ?? [];
  const cats: [number, string, string][] = rec.length ? [[-1, "🕘", "اخیر"], ...GROUPS] : GROUPS;
  return (
    <>
      <input className="emoji-search" type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="جستجوی اموجی" aria-label="جستجوی اموجی" />
      {!term && (
        <nav className="emoji-cats" role="tablist">
          {cats.map(([g, icon, label]) => (
            <button key={g} role="tab" aria-selected={g === group} className={g === group ? "on" : ""} title={label} aria-label={label} onClick={() => setGroup(g)}>{icon}</button>
          ))}
        </nav>
      )}
      <div className="emoji-grid">
        {!groups ? <span className="spinner" />
          : !list.length ? <p className="muted">اموجی‌ای پیدا نشد</p>
          // mousedown default would move focus off the composer: keep its caret where the emoji goes
          : list.map((e) => <button key={e.u} title={e.name} aria-label={e.name} onMouseDown={(ev) => ev.preventDefault()} onClick={() => onPick(e.u)}>{e.u}</button>)}
      </div>
    </>
  );
}

// ---------- stickers (MSC2545 image packs) ----------

const ROOM_PACK = "im.ponies.room_emotes";

/** My packs, the room's packs, then packs from rooms I've enabled globally (im.ponies.emote_rooms), if loaded. */
export function stickerPacks(room: Room): StickerPack[] {
  const packs = [stickerPack("user", client.getAccountData("im.ponies.user_emotes" as never)?.getContent(), "استیکرهای من")];
  packs.push(...room.currentState.getStateEvents(ROOM_PACK).map((e) => stickerPack(`${room.roomId}/${e.getStateKey()}`, e.getContent(), room.name)));
  const refs: Record<string, Record<string, unknown>> = client.getAccountData("im.ponies.emote_rooms" as never)?.getContent()?.rooms ?? {};
  for (const [id, keys] of Object.entries(refs)) {
    const r = client.getRoom(id);
    if (!r || r === room) continue;
    for (const k of Object.keys(keys ?? {})) packs.push(stickerPack(`${id}/${k}`, r.currentState.getStateEvents(ROOM_PACK, k)?.getContent(), r.name));
  }
  return packs.filter((p): p is StickerPack => !!p);
}

export function sendSticker(room: Room, threadId: string | null, s: Sticker, replyTo?: MatrixEvent) {
  const content: Record<string, unknown> = { body: s.body, url: s.url, info: s.info ?? {} };
  if (replyTo) content["m.relates_to"] = { "m.in_reply_to": { event_id: replyTo.getId() } };
  return client.sendEvent(room.roomId, threadId, EventType.Sticker, content as never);
}

function StickerGrid({ room, onPick }: { room: Room; onPick: (s: Sticker) => void }) {
  const packs = stickerPacks(room);
  const [sel, setSel] = useState(0);
  if (!packs.length) return <p className="muted">هنوز بسته‌ی استیکری ندارید. بسته‌ها را می‌توانید در Cinny یا FluffyChat بسازید.</p>;
  const pack = packs[Math.min(sel, packs.length - 1)];
  return (
    <>
      {packs.length > 1 && (
        <nav className="emoji-cats" role="tablist">
          {packs.map((p, i) => (
            <button key={p.id} role="tab" aria-selected={p === pack} className={p === pack ? "on" : ""} title={p.name} aria-label={p.name} onClick={() => setSel(i)}>
              {p.avatar ? <Thumb url={p.avatar} /> : [...p.name][0]}
            </button>
          ))}
        </nav>
      )}
      <div className="sticker-grid">
        {pack.stickers.map((s) => <button key={s.shortcode} title={s.body} aria-label={s.body} onClick={() => onPick(s)}><Thumb url={s.url} /></button>)}
      </div>
    </>
  );
}

function Thumb({ url }: { url: string }) {
  const src = usePromise(avatarUrl(url, 128));
  return src ? <img src={src} alt="" loading="lazy" draggable={false} /> : <span className="shimmer" />;
}
