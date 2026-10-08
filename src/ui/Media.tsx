import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type PointerEvent, type WheelEvent } from "react";
import { createPortal } from "react-dom";
import { Direction, Filter, type MatrixEvent, type Room } from "matrix-js-sdk";
import { avatarUrl, client, mediaUrl } from "../matrix.ts";
import { usePromise } from "../hooks.ts";
import { contentLinks, fmtDuration, mediaKind, num, stamp, type MediaKind } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { errText, previewText, senderName, Sheet, toast } from "./common.tsx";
import { saveFile } from "../native.ts";
import { FileRow } from "./Message.tsx";
import { AudioPlayer, stopPlayer, trackFor } from "./Voice.tsx";
import { requestJump } from "./Search.tsx";
import { growFrom, takeOrigin, useDismiss } from "./useDismiss.ts";
import { alertDialog } from "./dialog.tsx";

type Content = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

const isVisual = (e: MatrixEvent) => !e.isRedacted() && ["m.image", "m.video"].includes(e.getContent().msgtype ?? "");

/** Images/videos around `ev` in its loaded timeline (the thread's, for thread replies), oldest first. */
export function timelineMedia(room: Room, ev: MatrixEvent) {
  const root = ev.threadRootId;
  const evs = (root && root !== ev.getId() ? room.getThread(root)?.liveTimeline.getEvents() : room.getLiveTimeline().getEvents()) ?? [];
  const list = evs.filter(isVisual);
  return list.some((e) => e.getId() === ev.getId()) ? list : [ev]; // e.g. a sticker
}

/** Small preview: the sender's thumbnail if any, else a server-side one (encrypted media: the file itself). */
export const thumbFor = (c: Content) =>
  c.info?.thumbnail_file ? mediaUrl({ file: c.info.thumbnail_file })
  : c.info?.thumbnail_url ? mediaUrl({ url: c.info.thumbnail_url }, { w: 320, h: 320 })
  : c.msgtype === "m.video" ? null : mediaUrl(c, { w: 320, h: 320 });

const fileName = (c: Content) => c.filename ?? c.body ?? "file";
const caption = (c: Content) => (c.filename && c.body && c.body !== c.filename ? c.body : undefined);

type Zoom = { s: number; x: number; y: number };
const NO_ZOOM: Zoom = { s: 1, x: 0, y: 0 };
const clampScale = (s: number) => Math.min(6, Math.max(1, s));

/** Full-screen gallery. `items` oldest first; in RTL "next" (newer) sits on the left. */
export function MediaViewer({ items, start, onClose, onJump }: { items: MatrixEvent[]; start: MatrixEvent; onClose: () => void; onJump?: (ev: MatrixEvent) => void }) {
  const [id, setId] = useState(start.getId());
  const i = Math.max(0, items.findIndex((e) => e.getId() === id));
  const ev = items[i] ?? start;
  const c: Content = ev.getContent();
  const video = c.msgtype === "m.video";
  const full = usePromise(mediaUrl(c)); // mediaUrl hands back the same cached promise per file
  const thumb = usePromise(thumbFor(c));

  const [zoom, setZoom] = useState(NO_ZOOM);
  const [closing, close] = useDismiss(onClose, 200);
  const dir = useRef(0); // which way the last prev/next went: the new item slides in from there
  const go = (d: number) => { const n = items[i + d]; if (n) { dir.current = d; setId(n.getId()); setZoom(NO_ZOOM); } };
  const goRef = useRef(go);
  goRef.current = go;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
      else if (e.key === "ArrowLeft") goRef.current(1);
      else if (e.key === "ArrowRight") goRef.current(-1);
      else return;
      e.preventDefault();
      e.stopImmediatePropagation(); // Esc here mustn't also close the chat
    };
    addEventListener("keydown", onKey, true);
    return () => removeEventListener("keydown", onKey, true);
  }, [close]);

  // zoom keeps the point under the cursor/fingers still: t' = p - c - (s'/s)(p - c - t), c = stage center
  const stage = useRef<HTMLDivElement>(null);
  const [from] = useState(takeOrigin);
  useLayoutEffect(() => { if (from && stage.current) growFrom(stage.current, from); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const zoomAt = (z: Zoom, s2: number, px: number, py: number, mx = px, my = py): Zoom => {
    const r = stage.current!.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    s2 = clampScale(s2);
    if (s2 === 1) return NO_ZOOM;
    return { s: s2, x: mx - cx - (s2 / z.s) * (px - cx - z.x), y: my - cy - (s2 / z.s) * (py - cy - z.y) };
  };
  const onWheel = (e: WheelEvent) => { if (!video) setZoom((z) => zoomAt(z, z.s * Math.exp(-e.deltaY * 0.002), e.clientX, e.clientY)); };

  // one pointer pans (when zoomed) or swipes; two pinch. Same formula: old midpoint → new midpoint, scaled by the distance ratio.
  const ptrs = useRef(new Map<number, { x: number; y: number }>());
  const down = useRef({ x: 0, y: 0, moved: false });
  const onPointerDown = (e: PointerEvent) => {
    if (video) return;
    ptrs.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    down.current = { x: e.clientX, y: e.clientY, moved: false };
  };
  const onPointerMove = (e: PointerEvent) => {
    const p = ptrs.current;
    if (!p.has(e.pointerId)) return;
    const before = [...p.values()];
    p.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const after = [...p.values()];
    if (Math.hypot(e.clientX - down.current.x, e.clientY - down.current.y) > 6) down.current.moved = true;
    const mid = (a: typeof after) => ({ x: (a[0].x + (a[1] ?? a[0]).x) / 2, y: (a[0].y + (a[1] ?? a[0]).y) / 2 });
    const dist = (a: typeof after) => (a[1] ? Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y) : 1);
    setZoom((z) => {
      if (after.length < 2 && z.s === 1) return z; // not zoomed: a drag is a swipe, handled on release
      const m0 = mid(before), m1 = mid(after);
      return zoomAt(z, z.s * (dist(after) / (dist(before) || 1)), m0.x, m0.y, m1.x, m1.y);
    });
  };
  const onPointerUp = (e: PointerEvent) => {
    const wasOne = ptrs.current.size === 1;
    ptrs.current.delete(e.pointerId);
    const dx = e.clientX - down.current.x;
    if (wasOne && zoom.s === 1 && Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(e.clientY - down.current.y)) go(dx > 0 ? 1 : -1); // RTL: dragging right brings in the newer one from the left
  };

  const download = async () => {
    try {
      const url = await mediaUrl(c);
      if (url && await saveFile(url, fileName(c))) toast("در پوشه‌ی دانلودها ذخیره شد");
    } catch (e) {
      alertDialog(errText(e));
    }
  };

  const src = full ?? thumb;
  return (
    <div className={"lightbox" + (closing ? " closing" : "") + (from ? " from-thumb" : "")} role="dialog" aria-label="نمایش رسانه">
      <header className="mv-bar">
        <button className="icon-btn" onClick={close} aria-label="بستن"><Icon name="close" /></button>
        <span className="mv-who">
          <b dir="auto">{senderName(ev)}</b>
          <small>{stamp(ev.getTs())}{items.length > 1 && ` · ${num(i + 1)} از ${num(items.length)}`}</small>
        </span>
        {onJump && <button className="icon-btn" onClick={() => { onClose(); onJump(ev); }} title="نمایش در گفتگو" aria-label="نمایش در گفتگو"><Icon name="thread" /></button>}
        <button className="icon-btn" onClick={download} title="دانلود" aria-label="دانلود"><Icon name="download" /></button>
      </header>
      <div className="mv-stage" ref={stage} onWheel={onWheel} onPointerDown={onPointerDown} onPointerMove={onPointerMove}
        onPointerUp={onPointerUp} onPointerCancel={onPointerUp} onPointerLeave={onPointerUp}
        onClick={(e) => { if (e.target === e.currentTarget && !down.current.moved) close(); }}
        onDoubleClick={(e) => !video && setZoom((z) => (z.s > 1 ? NO_ZOOM : zoomAt(z, 2.5, e.clientX, e.clientY)))}>
        {video ? (
          full ? <VideoPlayer key={ev.getId()} src={full} duration={(c.info?.duration ?? 0) / 1000} /> : <span className="spinner" />
        ) : src ? (
          <img key={dir.current ? ev.getId() : "first"} src={src} alt={c.body ?? ""} draggable={false} className={(zoom.s > 1 ? "zoomed" : "") + (dir.current ? " slide" : "")}
            style={{ transform: `translate(${zoom.x}px, ${zoom.y}px) scale(${zoom.s})`, "--dir": dir.current } as CSSProperties} />
        ) : <span className="spinner" />}
      </div>
      {i > 0 && <button className="icon-btn mv-nav mv-prev" onClick={() => go(-1)} aria-label="قبلی"><Icon name="back" size={28} /></button>}
      {i < items.length - 1 && <button className="icon-btn mv-nav mv-next" onClick={() => go(1)} aria-label="بعدی"><Icon name="chevron" size={28} /></button>}
      {caption(c) && <p className="mv-caption" dir="auto">{caption(c)}</p>}
    </div>
  );
}

const SPEEDS = [1, 1.5, 2, 0.5];

/** Our controls over a <video>: tap toggles play; a bar with seek, time, speed, mute, fullscreen that hides while playing.
 *  `duration` (s) from the event covers files that report none (recorder webm). */
function VideoPlayer({ src, duration }: { src: string; duration: number }) {
  const v = useRef<HTMLVideoElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const [playing, setPlaying] = useState(false);
  const [pos, setPos] = useState(0);
  const [dur, setDur] = useState(duration);
  const [muted, setMuted] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [shown, setShown] = useState(true);
  const [full, setFull] = useState(false);
  const hideT = useRef(0);
  const poke = () => { setShown(true); clearTimeout(hideT.current); hideT.current = window.setTimeout(() => setShown(false), 2500); };
  const toggle = () => { const el = v.current!; if (el.paused) el.play().catch(() => {}); else el.pause(); };
  useEffect(() => {
    const onFs = () => setFull(!!document.fullscreenElement);
    const onKey = (e: KeyboardEvent) => { // space on a focused button already clicks it
      if (e.key !== " " || (e.target as HTMLElement).closest("button, input, textarea")) return;
      e.preventDefault();
      toggle();
    };
    document.addEventListener("fullscreenchange", onFs);
    addEventListener("keydown", onKey);
    return () => { clearTimeout(hideT.current); document.removeEventListener("fullscreenchange", onFs); removeEventListener("keydown", onKey); };
  }, []);
  const seek = (t: number) => { v.current!.currentTime = t; setPos(t); };
  const cycle = () => { const s = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.length]; v.current!.playbackRate = s; setSpeed(s); };
  return (
    <div ref={box} className={"vp" + (shown || !playing ? " shown" : "")} onPointerMove={poke}>
      <video ref={v} src={src} autoPlay playsInline onClick={toggle}
        onPlay={() => { stopPlayer(); setPlaying(true); poke(); }} onPause={() => setPlaying(false)}
        onTimeUpdate={(e) => setPos(e.currentTarget.currentTime)}
        onDurationChange={(e) => Number.isFinite(e.currentTarget.duration) && setDur(e.currentTarget.duration)} />
      {!playing && <button className="vp-big" onClick={toggle} aria-label="پخش"><Icon name="play" size={34} /></button>}
      <div className="vp-bar" dir="ltr">
        <button className="icon-btn" onClick={toggle} aria-label={playing ? "توقف" : "پخش"}><Icon name={playing ? "pause" : "play"} /></button>
        <span className="vp-time">{fmtDuration(pos * 1000)}</span>
        <input type="range" min={0} max={dur || 0} step="any" value={Math.min(pos, dur)} onChange={(e) => seek(+e.target.value)} aria-label="موقعیت پخش" />
        <span className="vp-time">{fmtDuration(dur * 1000)}</span>
        <button className="speed" onClick={cycle} aria-label="سرعت پخش">{num(speed)}×</button>
        <button className="icon-btn" onClick={() => { v.current!.muted = !muted; setMuted(!muted); }} aria-label={muted ? "صدا" : "بی‌صدا"}>
          <Icon name={muted ? "mute" : "speaker"} />
        </button>
        {document.fullscreenEnabled && (
          <button className="icon-btn" aria-label={full ? "خروج از تمام‌صفحه" : "تمام‌صفحه"}
            onClick={() => (full ? document.exitFullscreen() : box.current!.requestFullscreen()).catch(() => {})}>
            <Icon name={full ? "shrink" : "expand"} />
          </button>
        )}
      </div>
    </div>
  );
}

/** A room's or person's photo, full size. ponytail: no zoom/download; borrow MediaViewer's if wanted. */
export function PhotoViewer({ mxc, name, onClose }: { mxc: string; name: string; onClose: () => void }) {
  const full = usePromise(mediaUrl({ url: mxc }));
  const thumb = usePromise(avatarUrl(mxc, 96 * 2)); // the avatar's own cached size, shown until the original loads
  const [closing, close] = useDismiss(onClose, 200);
  const stage = useRef<HTMLDivElement>(null);
  const [from] = useState(takeOrigin);
  useLayoutEffect(() => { if (from && stage.current) growFrom(stage.current, from); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopImmediatePropagation(); // mustn't also close the sheet underneath
      close();
    };
    addEventListener("keydown", onKey, true);
    return () => removeEventListener("keydown", onKey, true);
  }, [close]);
  const src = full ?? thumb;
  return createPortal(
    <div className={"lightbox" + (closing ? " closing" : "") + (from ? " from-thumb" : "")} role="dialog" aria-label="نمایش عکس">
      <header className="mv-bar">
        <button className="icon-btn" onClick={close} aria-label="بستن"><Icon name="close" /></button>
        <span className="mv-who"><b dir="auto">{name}</b></span>
      </header>
      <div className="mv-stage" ref={stage} onClick={(e) => e.target === e.currentTarget && close()}>
        {src ? <img src={src} alt={name} draggable={false} style={{ cursor: "default" }} /> : <span className="spinner" />}
      </div>
    </div>,
    document.body,
  );
}

// ---------- shared media ----------

const TABS: Record<MediaKind, string> = { media: "عکس و ویدیو", file: "فایل", link: "پیوند", audio: "صدا" };
const AUTO_PAGES = 10; // pages fetched on their own per tab before asking for "more"

type Item = { ev: MatrixEvent; kind: MediaKind };
type Page = { items: Item[]; end: string | null };

let useFilter = true; // Conduit rejected some /messages filters; drop it after the first refusal

/** One page (newest first) of the room's history, independent of the live timeline. */
async function fetchPage(room: Room, from: string | null): Promise<Page> {
  let r;
  try {
    const f = new Filter(client.getUserId());
    f.setDefinition({ room: { timeline: { types: ["m.room.message", "m.room.encrypted"] } } });
    r = await client.createMessagesRequest(room.roomId, from, 100, Direction.Backward, useFilter ? f : undefined);
  } catch (e) {
    if (!useFilter) throw e;
    useFilter = false;
    return fetchPage(room, from);
  }
  const evs = r.chunk.map(client.getEventMapper({ decrypt: false }));
  await Promise.all(evs.map((e) => client.decryptEventIfNeeded(e).catch(() => {})));
  const items = evs.filter((e) => !e.isRedacted() && !e.isRelation("m.replace"))
    .map((ev) => ({ ev, kind: mediaKind(ev.getType(), ev.getContent()) }))
    .filter((x): x is Item => !!x.kind);
  return { items, end: r.chunk.length && r.end ? r.end : null };
}

/** Photos/videos, files, links and audio of a chat, newest first. `onJump` closes whatever hosts us. */
export function SharedMedia({ room, onClose, onJump }: { room: Room; onClose: () => void; onJump: () => void }) {
  const [tab, setTab] = useState<MediaKind>("media");
  const [items, setItems] = useState<Item[]>([]);
  const [token, setToken] = useState<string | null | undefined>(undefined); // undefined = first page, null = start of history
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [viewing, setViewing] = useState<MatrixEvent | null>(null);
  const [autoLeft, setAutoLeft] = useState(AUTO_PAGES);
  const [seen, setSeen] = useState(true); // the bottom sentinel is on screen
  const sentinel = useRef<HTMLDivElement>(null);

  const more = async () => {
    if (busy || token === null) return;
    setBusy(true);
    setError("");
    try {
      const p = await fetchPage(room, token ?? null);
      setItems((x) => [...x, ...p.items]);
      setToken(p.end);
    } catch (e) {
      setError(errText(e));
    }
    setBusy(false);
    setAutoLeft((n) => n - 1);
  };

  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver(([e]) => setSeen(e.isIntersecting));
    io.observe(el);
    return () => io.disconnect();
  }, []);
  // keep paging while the list doesn't reach the bottom; capped so a chat without media doesn't walk its whole history
  useEffect(() => { if (seen && !busy && !error && token !== null && autoLeft > 0) more(); }, [seen, busy, token, autoLeft, error]); // eslint-disable-line react-hooks/exhaustive-deps

  const shown = items.filter((x) => x.kind === tab).map((x) => x.ev);
  const jump = (ev: MatrixEvent) => { requestJump(room.roomId, ev.getId()!); onJump(); };
  const jumpBtn = (ev: MatrixEvent) => (
    <button className="icon-btn" onClick={() => jump(ev)} title="نمایش در گفتگو" aria-label="نمایش در گفتگو"><Icon name="thread" size={18} /></button>
  );
  const meta = (ev: MatrixEvent) => <small className="sm-meta" dir="auto">{senderName(ev)} · {stamp(ev.getTs())}</small>;

  return (
    <Sheet title="رسانه‌ها، فایل‌ها و پیوندها" onClose={onClose}>
      <div className="segmented sm-tabs">
        {(Object.keys(TABS) as MediaKind[]).map((k) => (
          <button key={k} className={tab === k ? "on" : ""} onClick={() => { setTab(k); setAutoLeft(AUTO_PAGES); }}>{TABS[k]}</button>
        ))}
      </div>
      {tab === "media" ? (
        <div className="sm-grid">
          {shown.map((ev) => <Thumb key={ev.getId()} ev={ev} onClick={() => setViewing(ev)} />)}
        </div>
      ) : (
        <div className="sm-list">
          {shown.map((ev) => {
            const c = ev.getContent();
            return (
              <div key={ev.getId()} className="sm-row">
                {tab === "file" ? <FileRow c={c} />
                  : tab === "audio" ? <AudioPlayer track={trackFor(ev)} />
                  : (
                    <span className="sm-links">
                      {contentLinks(c).map((u) => <a key={u} href={u} target="_blank" rel="noreferrer noopener" dir="ltr"><Icon name="link" size={16} /> {u}</a>)}
                      <span className="muted" dir="auto">{previewText(ev)}</span>
                    </span>
                  )}
                <span className="sm-row-foot">{meta(ev)}{jumpBtn(ev)}</span>
              </div>
            );
          })}
        </div>
      )}
      <div ref={sentinel} className="sm-foot">
        {busy ? <span className="spinner inline" />
          : error ? <><p className="error">{error}</p><button onClick={more}>تلاش دوباره</button></>
          : token === null ? (!shown.length && <p className="muted">چیزی پیدا نشد</p>)
          : autoLeft <= 0 && <button onClick={() => setAutoLeft(AUTO_PAGES)}>جستجوی پیام‌های قدیمی‌تر</button>}
      </div>
      {viewing && <MediaViewer items={[...shown].reverse()} start={viewing} onClose={() => setViewing(null)} onJump={jump} />}
    </Sheet>
  );
}

function Thumb({ ev, onClick }: { ev: MatrixEvent; onClick: () => void }) {
  const c = ev.getContent();
  const url = usePromise(thumbFor(c));
  return (
    <button className="sm-thumb" onClick={onClick} aria-label={c.body}>
      {url ? <img src={url} alt="" loading="lazy" /> : c.msgtype !== "m.video" && <span className="shimmer" />}
      {c.msgtype === "m.video" && <span className="sm-play"><Icon name="play" size={22} /></span>}
    </button>
  );
}
