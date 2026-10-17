// Floating emoji effects: a reaction or a 🎉 inside a message spawns its emoji and lets it drift up the room
// (WhatsApp/Telegram style). Matrix has no effect primitives, so this only animates what this client shows.
// Live actions only (data.liveEvent): backfilled pages never replay old effects. Local echoes (ev.status) are
// skipped — the server's echo is what triggers the spawn, so you see exactly one burst per reaction.
// honk: the layer sits below menus (z 20) and sheets (z 40), so effects never cover the UI's chrome.
import { useEffect, useRef, useState, type CSSProperties } from "react";
import { EventType, MatrixEventEvent, RoomEvent, type IRoomTimelineData, type MatrixEvent, type Room } from "matrix-js-sdk";
import { client } from "../matrix.ts";
import { isEmojiOnly } from "../logic.ts";

type Floaty = { key: number; char: string; left: number; size: number; dur: number; sway: number };
type Piece = { key: number; x: number; dx: number; dur: number; delay: number; rot: number; color: string };

const COLORS = ["#f43f5e", "#fb923c", "#facc15", "#4ade80", "#38bdf8", "#a78bfa", "#f472b6", "#fbbf24"];
const CONFETTI_COOLDOWN = 3_600;

const reduceMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

/** Emoji-ish relation key: short, and at least one non-ASCII char (shortcodes and mxc:// URIs are out). */
const emojiOfRel = (rel: ReturnType<MatrixEvent["getRelation"]>) => {
  const key = rel?.rel_type === "m.annotation" && typeof rel.key === "string" ? rel.key : undefined;
  return key && key.length <= 8 && /[^ -~]/u.test(key) ? key : undefined;
};

export function LiveEmojis({ room }: { room: Room }) {
  const [floats, setFloats] = useState<Floaty[]>([]);
  const [pieces, setPieces] = useState<Piece[]>([]);
  const seq = useRef(0), seen = useRef(new Set<string>()), lastShow = useRef(0);

  const emit = (char: string) => {
    if (reduceMotion()) return;
    const key = ++seq.current;
    setFloats((f) => [...f.slice(-9), { key, char, left: 14 + Math.random() * 72, size: 44 + Math.random() * 26, dur: 3.8 + Math.random() * 1.6, sway: (Math.random() - 0.5) * 14 }]);
  };
  const confetti = () => {
    if (reduceMotion()) return;
    if (Date.now() - lastShow.current < CONFETTI_COOLDOWN) return;
    lastShow.current = Date.now();
    const batch: Piece[] = [];
    for (let i = 0; i < 56; i++) {
      batch.push({
        key: ++seq.current, x: Math.random() * 100, dx: (Math.random() - 0.5) * 24, dur: 2.4 + Math.random() * 1.4,
        delay: Math.random() * 0.5, rot: (Math.random() > 0.5 ? 1 : -1) * (360 + Math.random() * 720), color: COLORS[i % COLORS.length],
      });
    }
    setPieces(batch);
    // every piece also drops via its own animationend, but a hidden tab wouldn't fire those: clean up hard
    setTimeout(() => setPieces((a) => a.filter((p) => !batch.some((b) => b.key === p.key))), 5_000);
  };
  const route = (ev: MatrixEvent) => {
    const id = ev.getId();
    if (!id || ev.status != null || seen.current.has(id)) return;
    const char = emojiOfRel(ev.getRelation());
    if (char) { seen.current.add(id); emit(char); return; }
    const body = ev.getContent().body;
    if (typeof body !== "string" || !/[^ -~]/u.test(body)) return; // needs at least one emoji-ish char to matter
    if (body.includes("🎉")) { seen.current.add(id); confetti(); return; }
    // an emoji-only message floats its exact emojis; whitespace-split keeps ZWJ and tone-marker clusters whole
    if (isEmojiOnly(body)) {
      seen.current.add(id);
      [...new Set(body.split(/\s+/).filter((w) => w && /[^ -~]/u.test(w)))].slice(0, 4).forEach(emit);
    }
  };

  useEffect(() => {
    const onTimeline = (ev: MatrixEvent, room2: Room | undefined, toStart: boolean | undefined, _removed: boolean, data: IRoomTimelineData) => {
      if (toStart || !data?.liveEvent || !room2 || room2.roomId !== room.roomId) return;
      if (ev.getType() === EventType.RoomMessageEncrypted && !ev.isDecryptionFailure()) ev.once(MatrixEventEvent.Decrypted, route);
      else route(ev);
    };
    client.on(RoomEvent.Timeline, onTimeline);
    return () => { client.off(RoomEvent.Timeline, onTimeline); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [room]); // route closes over the refs only

  const cut = (key: number) => {
    setFloats((a) => a.filter((x) => x.key !== key));
    setPieces((a) => a.filter((x) => x.key !== key));
  };
  return (
    <div className="live-layer" aria-hidden>
      {floats.map((f) => (
        <span key={f.key} className="live-emoji" onAnimationEnd={() => cut(f.key)}
          style={{ left: f.left + "%", fontSize: f.size, animationDuration: f.dur + "s", "--sway": f.sway + "vw" } as CSSProperties}>
          {f.char}
        </span>
      ))}
      {pieces.map((p) => (
        <i key={p.key} className="live-confetti" onAnimationEnd={() => cut(p.key)}
          style={{ left: p.x + "%", background: p.color, animationDuration: p.dur + "s", animationDelay: p.delay + "s", "--dx": p.dx + "vw", "--rot": p.rot + "deg" } as CSSProperties} />
      ))}
    </div>
  );
}