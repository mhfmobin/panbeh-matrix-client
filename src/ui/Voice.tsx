import { useEffect, useRef, useState, useSyncExternalStore, type KeyboardEvent, type MouseEvent } from "react";
import type { MatrixEvent } from "matrix-js-sdk";
import { downsample, fmtDuration, num } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { audioDuration, errText, isVoice, senderName } from "./common.tsx";
import { client, mediaUrl } from "../matrix.ts";

const BARS = 40;
const SPEEDS = [1, 1.5, 2, 0.5];
// global like Telegram: the speed you picked last applies to every voice message
let speed = SPEEDS.includes(Number(localStorage.getItem("panbeh.voiceSpeed"))) ? Number(localStorage.getItem("panbeh.voiceSpeed")) : 1;

export type Track = { id: string; ev?: MatrixEvent; title?: string; load: () => Promise<string> | null; duration: number; waveform?: unknown };
type Snap = { track: Track | null; state: "idle" | "loading" | "playing" | "paused"; pos: number; dur: number };

/** One <audio> for the whole app, so playback survives scrolling and chat switches. */
const audio = new Audio();
audio.preload = "none";
let snap: Snap = { track: null, state: "idle", pos: 0, dur: 0 };
const subs = new Set<() => void>();
const set = (p: Partial<Snap>) => { snap = { ...snap, ...p }; subs.forEach((f) => f()); };
const subscribe = (f: () => void) => (subs.add(f), () => void subs.delete(f));
const usePlayer = () => useSyncExternalStore(subscribe, () => snap);

export function trackFor(ev: MatrixEvent): Track {
  const c = ev.getContent();
  return {
    id: ev.getId()!, ev, title: isVoice(c) ? undefined : c.body,
    load: () => mediaUrl(c as Parameters<typeof mediaUrl>[0]), duration: audioDuration(c),
    waveform: c["org.matrix.msc1767.audio"]?.waveform ?? c["m.audio_details"]?.waveform,
  };
}

export function stopPlayer() {
  audio.pause();
  set({ track: null, state: "idle", pos: 0, dur: 0 });
}

async function start(t: Track) {
  set({ track: t, state: "loading", pos: 0, dur: t.duration / 1000 });
  audio.pause(); // the previous one stops while this one loads
  try {
    const url = await t.load();
    if (!url) throw new Error("فایل صوتی در دسترس نیست");
    if (snap.track !== t) return; // user picked something else meanwhile
    audio.src = url;
    audio.playbackRate = speed;
    await audio.play();
  } catch (e) {
    if (snap.track === t) set({ state: "idle" });
    alert(errText(e));
  }
}

function playNext(ev: MatrixEvent) {
  const room = client.getRoom(ev.getRoomId());
  const root = ev.threadRootId;
  const evs = (root && root !== ev.getId() ? room?.getThread(root)?.liveTimeline.getEvents() : room?.getLiveTimeline().getEvents()) ?? [];
  const next = evs.slice(evs.findIndex((e) => e.getId() === ev.getId()) + 1).find((e) => e.getContent().msgtype === "m.audio" && !e.isRedacted());
  if (evs.some((e) => e.getId() === ev.getId()) && next) return void start(trackFor(next));
  set({ pos: 0, state: "paused" });
}

audio.onplay = () => set({ state: "playing" });
audio.onpause = () => snap.state !== "loading" && set({ state: "paused" });
audio.ontimeupdate = () => set({ pos: audio.currentTime });
audio.onloadedmetadata = () => Number.isFinite(audio.duration) && set({ dur: audio.duration }); // recorder webm reports Infinity
audio.onended = () => (snap.track?.ev ? playNext(snap.track.ev) : set({ pos: 0, state: "paused" }));

function toggle(t: Track) {
  if (snap.track?.id !== t.id) return void start(t);
  if (snap.state === "playing") audio.pause();
  else if (snap.state === "paused") audio.play().catch((e) => alert(errText(e)));
}

function cycleSpeed() {
  speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.length];
  localStorage.setItem("panbeh.voiceSpeed", String(speed));
  audio.playbackRate = speed;
  set({});
}

/** Voice/audio player. The media is fetched on first play, so a chat full of voice notes downloads nothing up front. */
export function AudioPlayer({ track }: { track: Track }) {
  const s = usePlayer();
  const cur = s.track?.id === track.id;
  const state = cur ? s.state : "idle", pos = cur ? s.pos : 0, dur = cur ? s.dur : track.duration / 1000;
  const wave = Array.isArray(track.waveform) ? track.waveform.filter((n): n is number => typeof n === "number") : [];
  const bars = wave.length ? downsample(wave, BARS) : Array<number>(BARS).fill(320);
  const progress = dur ? pos / dur : 0;

  function seekTo(f: number) {
    if (!cur || !dur || state === "loading") return toggle(track);
    audio.currentTime = Math.min(Math.max(f, 0), 1) * dur;
    set({ pos: audio.currentTime });
  }

  const onWave = (e: MouseEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    seekTo((e.clientX - r.left) / r.width);
  };
  const onKey = (e: KeyboardEvent) => {
    if (e.key === "ArrowRight" || e.key === "ArrowLeft") { e.preventDefault(); seekTo((pos + (e.key === "ArrowRight" ? 5 : -5)) / dur); }
  };

  return (
    <div className="voice" dir="ltr">
      <button className="voice-play" onClick={() => toggle(track)} aria-label={state === "playing" ? "توقف" : "پخش"}>
        {state === "loading" ? <span className="spinner inline" /> : <Icon name={state === "playing" ? "pause" : "play"} size={22} />}
      </button>
      <div className="voice-body">
        {track.title && <b className="voice-title" dir="auto">{track.title}</b>}
        <div className="wave" onClick={onWave} onKeyDown={onKey} tabIndex={0} role="slider" aria-label="موقعیت پخش"
          aria-valuemin={0} aria-valuemax={Math.round(dur)} aria-valuenow={Math.round(pos)}>
          {bars.map((h, i) => <span key={i} className={i / BARS < progress ? "on" : ""} style={{ height: `${15 + (h / 1024) * 85}%` }} />)}
        </div>
        <div className="voice-meta">
          <span>{fmtDuration((state === "idle" ? dur : pos) * 1000)}</span>
          <button className="speed" onClick={cycleSpeed} aria-label="سرعت پخش">{num(speed)}×</button>
        </div>
      </div>
    </div>
  );
}

/** Bar pinned above the chat while something plays; click the text to jump to the message. */
export function NowPlaying({ onJump }: { onJump?: (id: string) => void }) {
  const { track, state, pos, dur } = usePlayer();
  const ev = track?.ev;
  if (!track || !ev) return null;
  const go = () => (ev.getRoomId() === location.hash.slice(1) && onJump ? onJump(ev.getId()!) : void (location.hash = ev.getRoomId()!));
  return (
    <div className="now-playing">
      <button className="icon-btn" onClick={() => toggle(track)} aria-label={state === "playing" ? "توقف" : "پخش"}>
        {state === "loading" ? <span className="spinner inline" /> : <Icon name={state === "playing" ? "pause" : "play"} size={22} />}
      </button>
      <button className="now-playing-text" onClick={go}>
        <b>{senderName(ev)}</b>
        <span>{track.title ?? "پیام صوتی"}</span>
      </button>
      <span className="now-playing-time">{fmtDuration(pos * 1000)}</span>
      <button className="speed" onClick={cycleSpeed} aria-label="سرعت پخش">{num(speed)}×</button>
      <button className="icon-btn" onClick={stopPlayer} aria-label="بستن"><Icon name="close" /></button>
      <i className="now-playing-line" style={{ width: dur ? `${Math.min(100, (pos / dur) * 100)}%` : 0 }} />
    </div>
  );
}

export type Voice = { file: File; extra: Record<string, unknown> };
type Recording = { blob: Blob; url: string; duration: number; waveform: number[] };

/** Records on mount; ■ stops into a preview, ✓ sends, trash discards. onDone() without a voice = cancelled. */
export function VoiceRecorder({ onDone }: { onDone: (v?: Voice) => void }) {
  const [rec, setRec] = useState<Recording | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const levels = useRef<number[]>([]);
  const stop = useRef<(keep: boolean) => void>(() => {});

  useEffect(() => {
    let live = true, keep = false, timer = 0, url = "";
    let stream: MediaStream | undefined, ctx: AudioContext | undefined, r: MediaRecorder | undefined;
    const release = () => { clearInterval(timer); stream?.getTracks().forEach((t) => t.stop()); ctx?.close().catch(() => {}); };
    stop.current = (k) => { keep = k; if (r?.state === "recording") r.stop(); release(); };
    (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      } catch {
        alert("دسترسی به میکروفون ممکن نشد. اجازه‌ی میکروفون را در مرورگر بدهید (فقط روی https یا localhost کار می‌کند).");
        return onDone();
      }
      if (!live) return release();
      const type = ["audio/ogg;codecs=opus", "audio/webm;codecs=opus"].find((t) => MediaRecorder.isTypeSupported(t));
      r = new MediaRecorder(stream, type ? { mimeType: type } : undefined);
      const chunks: Blob[] = [];
      const started = Date.now();
      r.ondataavailable = (e) => chunks.push(e.data);
      r.onstop = () => {
        if (!keep || !live) return;
        const blob = new Blob(chunks, { type: r!.mimeType.split(";")[0] });
        url = URL.createObjectURL(blob);
        setRec({ blob, url, duration: Date.now() - started, waveform: downsample(levels.current) });
      };
      ctx = new AudioContext();
      const an = ctx.createAnalyser();
      an.fftSize = 1024;
      ctx.createMediaStreamSource(stream).connect(an);
      const buf = new Uint8Array(an.fftSize);
      timer = window.setInterval(() => {
        an.getByteTimeDomainData(buf);
        let sum = 0;
        for (const v of buf) sum += ((v - 128) / 128) ** 2;
        levels.current.push(Math.sqrt(sum / buf.length));
        setElapsed(Date.now() - started);
      }, 100);
      r.start();
    })();
    return () => { live = false; stop.current(false); if (snap.track?.id === "preview:" + url) stopPlayer(); if (url) URL.revokeObjectURL(url); };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function send() {
    const type = rec!.blob.type || "audio/webm";
    const ext = type.includes("ogg") ? "ogg" : type.includes("mp4") ? "m4a" : "webm";
    const duration = Math.round(rec!.duration);
    onDone({
      file: new File([rec!.blob], `voice.${ext}`, { type }),
      extra: {
        msgtype: "m.audio", body: "پیام صوتی", info: { duration },
        // what Element reads to show it as a voice message with a waveform
        "org.matrix.msc1767.audio": { duration, waveform: rec!.waveform },
        "org.matrix.msc3245.voice": {},
      },
    });
  }

  const recent = levels.current.slice(-BARS);
  return (
    <div className="composer-row recorder">
      <button className="icon-btn" onClick={() => onDone()} title="حذف" aria-label="حذف"><Icon name="trash" /></button>
      {rec ? (
        <div className="recorder-preview"><AudioPlayer track={{ id: "preview:" + rec.url, load: () => Promise.resolve(rec.url), duration: rec.duration, waveform: rec.waveform }} /></div>
      ) : (
        <div className="recorder-live" dir="ltr">
          <span className="rec-dot" />
          <span className="rec-time">{fmtDuration(elapsed)}</span>
          <div className="wave live">{recent.map((v, i) => <span key={i} className="on" style={{ height: `${15 + Math.min(1, v * 4) * 85}%` }} />)}</div>
        </div>
      )}
      {rec ? (
        <button className="send-btn ready" onClick={send} aria-label="ارسال"><Icon name="send" /></button>
      ) : (
        <button className="send-btn ready rec-stop" onClick={() => stop.current(true)} aria-label="پایان ضبط"><Icon name="stop" /></button>
      )}
    </div>
  );
}
