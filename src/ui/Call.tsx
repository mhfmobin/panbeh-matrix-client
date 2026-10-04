import { useEffect, useMemo, useRef, useState } from "react";
import { EventType, type Room } from "matrix-js-sdk";
import { MatrixRTCSessionEvent } from "matrix-js-sdk/lib/matrixrtc/index.js";
import { CallState } from "matrix-js-sdk/lib/webrtc/call.js";
import type { CallFeed } from "matrix-js-sdk/lib/webrtc/callFeed.js";
import { ConnectionState, Track, type VideoTrack } from "livekit-client";
import { allowCalls, client, isDirect } from "../matrix.ts";
import { answer, call, decline, flipCam, getCall, hangup, loadDevices, minimize, myMedia, ourTransport, protocolFor, setAudioRoute, setDevice, setNoiseSuppression, toggleCam, toggleMic, toggleScreen, toggleSpeaker, useCall, userOf, type Active, type Incoming } from "../call.ts";
import { usePromise, useTick } from "../hooks.ts";
import { fmtDuration, num } from "../logic.ts";
import { isNative, nativeAudioRoutes, type AudioRoute } from "../native.ts";
import { onPickSource, type ShareSource } from "../desktop.ts";
import { pushBack } from "../back.ts";
import { Icon, type IconName } from "../icons.tsx";
import { Avatar, errText, me, RoomAvatar } from "./common.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";

const run = (p: Promise<unknown> | void) => { p?.catch((e) => alertDialog(errText(e))); };
const canShare = !isNative && !!navigator.mediaDevices?.getDisplayMedia;

/**
 * Starts a call after ending another one. legacy: a peer-to-peer m.call.* call (DMs only); unset = call back the way they
 * last called, or legacy when our server has no SFU. MatrixRTC needs power to send m.call.member, which an admin can grant.
 */
async function start(room: Room, video: boolean, legacy?: boolean) {
  const { active } = getCall();
  if (active && active.room !== room && !(await confirmDialog("تماس فعلی پایان یابد؟", { ok: "پایان و تماس", danger: true }))) return;
  legacy ??= isDirect(room) && (protocolFor(room) === "legacy" || !(await ourTransport()));
  if (!legacy && !room.currentState.maySendStateEvent(EventType.GroupCallMemberPrefix, me())) {
    if (!room.currentState.maySendStateEvent(EventType.RoomPowerLevels, me())) return alertDialog("مدیر گروه هنوز تماس را برای اعضا فعال نکرده است");
    if (!(await confirmDialog("تماس در این گروه فعال شود؟ اعضا می‌توانند به تماس بپیوندند."))) return;
    await allowCalls(room.roomId);
  }
  await call(room, video, true, legacy);
}

/** Voice and video buttons for the room header. DMs always have them (legacy calls need no SFU); groups only with an SFU. */
export function CallButtons({ room }: { room: Room }) {
  const sfu = usePromise(ourTransport());
  const [menu, setMenu] = useState<{ x: number; y: number; video: boolean } | null>(null);
  const dm = isDirect(room);
  if (!sfu && !dm) return null;
  // right-click / long-press in a DM: pick the other kind of call by hand
  const pick = (video: boolean) => dm && sfu ? (e: React.MouseEvent) => { e.preventDefault(); setMenu({ x: e.clientX, y: e.clientY, video }); } : undefined;
  return (
    <>
      <button className="icon-btn" onClick={() => run(start(room, false))} onContextMenu={pick(false)} title="تماس صوتی" aria-label="تماس صوتی"><Icon name="phone" /></button>
      <button className="icon-btn" onClick={() => run(start(room, true))} onContextMenu={pick(true)} title="تماس تصویری" aria-label="تماس تصویری"><Icon name="video" /></button>
      {menu && (
        <div className="chat-menu-backdrop" onClick={() => setMenu(null)}>
          <div className="chat-menu" role="menu" style={{ left: Math.max(8, Math.min(menu.x - 260, innerWidth - 268)), top: menu.y }}>
            <button role="menuitem" onClick={() => run(start(room, menu.video, false))}>
              <Icon name={menu.video ? "video" : "phone"} /> تماس به روش جدید (Element X، پنبه)</button>
            <button role="menuitem" onClick={() => run(start(room, menu.video, true))}>
              <Icon name={menu.video ? "video" : "phone"} /> تماس به روش قدیمی (FluffyChat، Element قدیمی)</button>
          </div>
        </div>
      )}
    </>
  );
}

/** Thin bar under the header: back to a minimized call, or join the one going on in this room. */
export function CallBar({ room }: { room?: Room }) {
  const { active } = useCall();
  const session = room ? client.matrixRTC.getRoomSession(room) : undefined;
  useTick(session, [MatrixRTCSessionEvent.MembershipsChanged]);
  const now = useClock(!!active?.since);
  if (active?.min) return (
    <button className="call-bar" onClick={() => minimize(false)}>
      <Icon name="phone" size={18} /> <b>{active.room.name}</b>
      <span>{active.since ? fmtDuration(now - active.since) : "در حال اتصال…"}</span>
    </button>
  );
  if (!room || active || !session?.memberships.length) return null;
  return (
    <button className="call-bar" onClick={() => run(start(room, false, false))}>
      <Icon name="phone" size={18} /> <b>تماس در جریان است</b>
      <span>{num(new Set(session.memberships.map((m) => m.userId)).size)} نفر · پیوستن</span>
    </button>
  );
}

/** The incoming ring and the call screen; mounted once by the app shell. */
export function CallLayer() {
  const { active, incoming } = useCall();
  const [pick, setPick] = useState<{ sources: ShareSource[]; done: (id: string | null) => void } | null>(null);
  useEffect(() => onPickSource((sources) => new Promise((done) => setPick({ sources, done }))), []);
  if (incoming && !active) return <IncomingCall room={incoming.room} video={incoming.video} />;
  return <>
    {active && !active.min && <CallScreen a={active} />}
    {active && incoming && <Waiting i={incoming} />}
    {pick && <SourcePicker sources={pick.sources} onDone={(id) => { pick.done(id); setPick(null); }} />}
  </>;
}

/** Desktop (Windows/X11): which screen or window to share. */
function SourcePicker({ sources, onDone }: { sources: ShareSource[]; onDone: (id: string | null) => void }) {
  useEffect(() => pushBack(() => onDone(null)), [onDone]);
  const screens = sources.filter((s) => s.id.startsWith("screen:")), windows = sources.filter((s) => !s.id.startsWith("screen:"));
  const grid = (list: ShareSource[]) => (
    <div className="source-grid">
      {list.map((s) => (
        <button key={s.id} onClick={() => onDone(s.id)}><img src={s.thumb} alt="" /><span>{s.name}</span></button>
      ))}
    </div>
  );
  return (
    <div className="source-picker" role="dialog" aria-label="اشتراک صفحه" onClick={() => onDone(null)}>
      <div onClick={(e) => e.stopPropagation()}>
        <h3>چه چیزی به اشتراک گذاشته شود؟</h3>
        {screens.length > 0 && <><h4>صفحه‌نمایش</h4>{grid(screens)}</>}
        {windows.length > 0 && <><h4>پنجره</h4>{grid(windows)}</>}
        <button className="source-cancel" onClick={() => onDone(null)}>انصراف</button>
      </div>
    </div>
  );
}

/** Call waiting: someone else rings while we're in a call. */
function Waiting({ i }: { i: Incoming }) {
  return (
    <div className="call-waiting" role="alertdialog" aria-label="تماس ورودی">
      <RoomAvatar room={i.room} size={40} />
      <div><b>{i.room.name}</b><span>{i.video ? "تماس تصویری ورودی…" : "تماس صوتی ورودی…"}</span></div>
      <button className="call-btn end" onClick={decline} title="رد کردن" aria-label="رد کردن"><Icon name="hangup" size={22} /></button>
      <button className="call-btn ok" onClick={() => run(answer(false))} title="پایان تماس فعلی و پاسخ" aria-label="پایان تماس فعلی و پاسخ"><Icon name="phone" size={22} /></button>
    </div>
  );
}

function IncomingCall({ room, video }: { room: Room; video: boolean }) {
  useEffect(() => pushBack(() => { decline(); }), []);
  return (
    <div className="call-screen incoming" role="dialog" aria-label="تماس ورودی">
      <div className="call-who">
        <RoomAvatar room={room} size={112} />
        <h2>{room.name}</h2>
        <span>{video ? "تماس تصویری ورودی…" : "تماس صوتی ورودی…"}</span>
      </div>
      <div className="call-controls">
        <CallBtn icon="hangup" label="رد کردن" danger onClick={decline} />
        <CallBtn icon="phone" label="پاسخ" ok onClick={() => run(answer(false))} />
        {video && <CallBtn icon="video" label="پاسخ تصویری" ok onClick={() => run(answer(true))} />}
      </div>
    </div>
  );
}

function CallScreen({ a }: { a: Active }) {
  useEffect(() => pushBack(() => { minimize(true); }), []);
  const now = useClock(!!a.since);
  const { tiles, others } = tilesOf(a);
  const m = myMedia(a);
  const status = a.reconnecting ? "در حال اتصال دوباره…" : a.notice ? a.notice : a.kind === "legacy"
    ? (a.since ? fmtDuration(now - a.since) : a.mc.state === CallState.InviteSent ? "در حال زنگ زدن…" : "در حال اتصال…")
    : a.lk.state !== ConnectionState.Connected ? "در حال اتصال…"
    : !a.since ? (isDirect(a.room) ? "در حال زنگ زدن…" : "در انتظار دیگران…")
    : !others ? "تنها هستید" : fmtDuration(now - a.since);
  const [focus, setFocus] = useState<string | null>(null); // a feed blown up to most of the screen
  const main = tiles.find((t) => t.key === focus); // gone (left, stopped sharing) = back to the grid
  const pip = !main && tiles.length === 2 && others === 1; // 1:1: the other side fills the screen, we're in the corner
  const [panel, setPanel] = useState<"devices" | { routes: AudioRoute[]; current: number } | null>(null);
  // Android: with a headset around, the speaker button picks where audio goes; otherwise it just toggles the speaker
  const speakerBtn = async () => {
    const r = await nativeAudioRoutes();
    if (r.routes.some((x) => x.kind === "wired" || x.kind === "bluetooth")) setPanel(r);
    else toggleSpeaker();
  };
  const tile = (t: TileData, focused = false) =>
    <Tile key={t.key} room={a.room} t={t} mirror={t.local && !t.screen && a.facing === "user"} focused={focused} onFocus={() => setFocus(focused ? null : t.key)} />;
  return (
    <div className="call-screen" role="dialog" aria-label="تماس">
      <header className="call-head">
        <button className="icon-btn" onClick={() => minimize(true)} title="کوچک کردن" aria-label="کوچک کردن"><Icon name="down" /></button>
        <div><b>{a.room.name}</b><span>{status}</span></div>
      </header>
      {main ? (
        <div className="call-grid focus">
          {tile(main, true)}
          <div className="call-strip">{tiles.filter((t) => t !== main).map((t) => tile(t))}</div>
        </div>
      ) : (
        <div className={"call-grid" + (pip ? " pip" : "")} data-n={tiles.length}>
          {tiles.map((t) => tile(t))}
        </div>
      )}
      <div className="call-controls">
        <CallBtn icon={m.mic ? "mic" : "micOff"} label="میکروفون" on={!m.mic} onClick={() => run(toggleMic())} />
        <CallBtn icon={m.cam ? "video" : "videoOff"} label="دوربین" on={!m.cam} onClick={() => run(toggleCam())} />
        {isNative && m.cam && <CallBtn icon="flip" label="چرخش دوربین" onClick={() => run(flipCam())} />}
        {isNative && <CallBtn icon="speaker" label="بلندگو" on={a.speaker} onClick={() => run(speakerBtn())} />}
        {canShare && <CallBtn icon="screen" label="اشتراک صفحه" on={m.screen} onClick={() => run(toggleScreen())} />}
        {!isNative && <CallBtn icon="settings" label="میکروفون، دوربین و بلندگو" on={panel === "devices"} onClick={() => setPanel(panel ? null : "devices")} />}
        <CallBtn icon="hangup" label="پایان" danger onClick={() => run(hangup())} />
      </div>
      {panel && (
        <div className="call-panel-backdrop" onClick={() => setPanel(null)}>
          <div className="call-panel" onClick={(e) => e.stopPropagation()}>
            {panel === "devices" ? <Devices /> : <Routes {...panel} onPick={(r) => { setPanel(null); run(setAudioRoute(r.id, r.kind === "speaker")); }} />}
          </div>
        </div>
      )}
    </div>
  );
}

const DEVICE_KINDS: [MediaDeviceKind, string][] = [["audioinput", "میکروفون"], ["videoinput", "دوربین"], ["audiooutput", "بلندگو"]];
const canPickOutput = "setSinkId" in HTMLMediaElement.prototype;

/** Desktop/web: which mic, camera and speaker the call uses (remembered for later calls), and noise suppression. */
function Devices() {
  const devices = usePromise(useMemo(() => navigator.mediaDevices.enumerateDevices(), []));
  const d = loadDevices();
  return <>
    {DEVICE_KINDS.map(([kind, label]) => {
      const list = devices?.filter((x) => x.kind === kind && x.deviceId) ?? [];
      if (!list.length || (kind === "audiooutput" && !canPickOutput)) return null;
      return (
        <label key={kind}>{label}
          <select value={d[kind] ?? ""} onChange={(e) => run(setDevice(kind, e.target.value))}>
            {!d[kind] && <option value="" disabled>پیش‌فرض سیستم</option>}
            {list.map((x, i) => <option key={x.deviceId} value={x.deviceId}>{x.label || `${label} ${num(i + 1)}`}</option>)}
          </select>
        </label>
      );
    })}
    <label className="check">
      <input type="checkbox" checked={d.noiseSuppression ?? true} onChange={(e) => run(setNoiseSuppression(e.target.checked))} /> حذف نویز
    </label>
  </>;
}

const ROUTE_LABELS: Record<AudioRoute["kind"], string> = { earpiece: "گوشی", speaker: "بلندگو", wired: "هدفون", bluetooth: "بلوتوث" };

/** Android: where call audio goes. */
function Routes({ routes, current, onPick }: { routes: AudioRoute[]; current: number; onPick: (r: AudioRoute) => void }) {
  return <>{routes.map((r) => (
    <button key={r.id} className={r.id === current ? "on" : ""} onClick={() => onPick(r)} aria-pressed={r.id === current}>
      <Icon name={r.kind === "speaker" ? "speaker" : r.kind === "earpiece" ? "phone" : "headphones"} />
      {ROUTE_LABELS[r.kind]}{r.kind === "bluetooth" && r.name ? ` (${r.name})` : ""}
    </button>
  ))}</>;
}

/** One feed on the call screen: a camera or a shared screen, from LiveKit (track) or a legacy call (stream). */
type TileData = { key: string; userId: string; local: boolean; screen: boolean; video?: VideoTrack | MediaStream; micOff: boolean; speaking: boolean };

/** Everyone's camera (or avatar), plus any screen being shared; others = how many other people are in the call. */
function tilesOf(a: Active): { tiles: TileData[]; others: number } {
  if (a.kind === "legacy") {
    const mc = a.mc, them = mc.getOpponentMember()?.userId ?? mc.invitee ?? "";
    const feed = (f: CallFeed | undefined, local: boolean, screen: boolean): TileData[] => !f ? [] : [{
      key: (local ? "me" : "them") + (screen ? ":screen" : ""), userId: local ? me() : them, local, screen,
      video: !f.isVideoMuted() && f.stream.getVideoTracks().length ? f.stream : undefined, micOff: f.isAudioMuted(), speaking: false,
    }];
    const remote = feed(mc.remoteUsermediaFeed, false, false);
    return { others: 1, tiles: [ // while it rings, the other side is their avatar
      ...(remote.length ? remote : [{ key: "them", userId: them, local: false, screen: false, micOff: false, speaking: false }]),
      ...feed(mc.remoteScreensharingFeed, false, true), ...feed(mc.localUsermediaFeed, true, false), ...feed(mc.localScreensharingFeed, true, true)] };
  }
  const others = [...a.lk.remoteParticipants.values()];
  const tiles = [...others, a.lk.localParticipant].flatMap((p) => {
    const userId = userOf(a.session, p.identity);
    const video = (src: Track.Source) => { const pub = p.getTrackPublication(src); return pub && !pub.isMuted ? pub.videoTrack : undefined; };
    const t = { userId, local: p.isLocal, micOff: !p.isMicrophoneEnabled, speaking: p.isSpeaking };
    return [{ ...t, key: p.identity + Track.Source.Camera, screen: false, video: video(Track.Source.Camera) }]
      .concat(p.isScreenShareEnabled ? [{ ...t, key: p.identity + Track.Source.ScreenShare, screen: true, video: video(Track.Source.ScreenShare) }] : []);
  });
  return { tiles, others: others.length };
}

function Tile({ room, t, mirror, focused, onFocus }: { room: Room; t: TileData; mirror: boolean; focused: boolean; onFocus: () => void }) {
  const ref = useRef<HTMLVideoElement>(null);
  const { video } = t;
  useEffect(() => {
    const el = ref.current;
    if (!video || !el) return;
    if (video instanceof MediaStream) {
      el.srcObject = video;
      return () => { el.srcObject = null; };
    }
    video.attach(el);
    return () => { video.detach(el); };
  }, [video]);
  const m = room.getMember(t.userId);
  return (
    <div className={"call-tile" + (t.local ? " local" : "") + (t.speaking ? " speaking" : "") + (focused ? " main" : "")}>
      {video ? <video ref={ref} autoPlay playsInline muted className={(t.screen || focused ? "contain" : "") + (mirror ? " mirror" : "")} />
        : <Avatar mxc={m?.getMxcAvatarUrl()} name={m?.name ?? t.userId} id={t.userId} size={88} />}
      <span className="call-name">{t.local ? "شما" : m?.name ?? t.userId}{t.micOff && <Icon name="micOff" size={14} />}</span>
      <button className="call-focus" onClick={onFocus} title={focused ? "بازگشت به همه" : "بزرگ‌نمایی"} aria-label={focused ? "بازگشت به همه" : "بزرگ‌نمایی"}>
        <Icon name={focused ? "shrink" : "expand"} size={18} />
      </button>
    </div>
  );
}

function CallBtn({ icon, label, on, ok, danger, onClick }: { icon: IconName; label: string; on?: boolean; ok?: boolean; danger?: boolean; onClick: () => void }) {
  return (
    <button className={"call-btn" + (on ? " on" : "") + (ok ? " ok" : "") + (danger ? " end" : "")} onClick={onClick} title={label} aria-label={label} aria-pressed={on}>
      <Icon name={icon} size={26} />
    </button>
  );
}

/** Date.now(), re-rendering every second while on. */
function useClock(on: boolean) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    if (!on) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [on]);
  return now;
}
