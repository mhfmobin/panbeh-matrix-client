import { useEffect, useMemo, useState } from "react";
import { ClientEvent } from "matrix-js-sdk";
// fetched as files, not bundled as JS: separate chunks, loaded the first time the panel opens
import enUrl from "emojibase-data/en/compact.json?url";
import faUrl from "cldr-annotations-modern/annotations/fa/annotations.json?url";
import { client, mediaUrl, savedGifs, toggleGif } from "../matrix.ts";
import { usePromise, useTick } from "../hooks.ts";
import { normalize, type Gif } from "../logic.ts";
import { confirmDialog } from "./dialog.tsx";

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

type Props = { onEmoji: (e: string) => void; onClose: () => void; gifs?: { onPick: (g: Gif) => void } };

/** Emoji (+ optional saved gifs) popover; closes on outside click / Esc. Picking doesn't close it. */
export function EmojiPanel({ onEmoji, onClose, gifs }: Props) {
  const [tab, setTab] = useState<"emoji" | "gif">("emoji");
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    addEventListener("keydown", k);
    return () => removeEventListener("keydown", k);
  }, [onClose]);
  return (
    <>
      <div className="menu-backdrop" onClick={onClose} />
      <div className="emoji-panel" role="dialog" aria-label="اموجی">
        {gifs && (
          <div className="segmented">
            <button className={tab === "emoji" ? "on" : ""} onClick={() => setTab("emoji")}>اموجی</button>
            <button className={tab === "gif" ? "on" : ""} onClick={() => setTab("gif")}>گیف</button>
          </div>
        )}
        {tab === "emoji" || !gifs ? <EmojiGrid onPick={(u) => { remember(u); onEmoji(u); }} /> : <GifGrid {...gifs} />}
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

// ---------- saved gifs (account data, see matrix.ts) ----------

function GifGrid({ onPick }: { onPick: (g: Gif) => void }) {
  useTick(client, [ClientEvent.AccountData]);
  const gifs = savedGifs();
  if (!gifs.length) return <p className="muted">گیفی ندارید. ویدیو را با گزینه‌ی «ارسال به صورت گیف» بفرستید یا گیف دیگران را ذخیره کنید.</p>;
  const remove = (g: Gif) => confirmDialog("این گیف از گیف‌های شما حذف شود؟", { danger: true }).then((y) => { if (y) void toggleGif(g); });
  return (
    <div className="gif-grid">
      {gifs.map((g) => (
        <button key={g.url ?? g.file?.url} aria-label="گیف" onClick={() => onPick(g)}
          onContextMenu={(e) => { e.preventDefault(); void remove(g); }}><GifView c={g} /></button>
      ))}
    </div>
  );
}

/** A gif in a message or the gif tab: plays by itself, looped and muted. */
export function GifView({ c }: { c: Gif }) {
  const src = usePromise(mediaUrl(c as never)); // file carries the full IEncryptedFile; Gif only names its url
  if (!src) return <span className="shimmer" />;
  return c.msgtype === "m.image" ? <img src={src} alt="" draggable={false} /> : <video src={src} autoPlay loop muted playsInline disablePictureInPicture />;
}
