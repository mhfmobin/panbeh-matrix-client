import { useEffect, useMemo, useRef, useState } from "react";
import { EventType, type Room } from "matrix-js-sdk";
import { MatrixRTCSessionEvent } from "matrix-js-sdk/lib/matrixrtc/index.js";
import { CallState } from "matrix-js-sdk/lib/webrtc/call.js";
import type { CallFeed } from "matrix-js-sdk/lib/webrtc/callFeed.js";
import { ConnectionQuality, ConnectionState, Track, type VideoTrack } from "livekit-client";
import { allowCalls, client, isDirect } from "../matrix.ts";
import { answer, call, decline, flipCam, getCall, handOf, hangup, loadDevices, membershipOf, minimize, myMedia, ourTransport, react, reactionOf, REACTIONS, setAudioRoute, setDevice, setNoiseSuppression, toggleCam, toggleHand, toggleMic, toggleScreen, toggleSpeaker, useCall, type Active, type Incoming } from "../call.ts";
import { usePromise, useTick } from "../hooks.ts";
import { useExit } from "./useDismiss.ts";
import { fmtDuration, num } from "../logic.ts";
import { isNative, nativeImmersive, nativePip, type AudioRoute } from "../native.ts";
import { onPickSource, type ShareSource } from "../desktop.ts";
import { pushBack } from "../back.ts";
import { Icon, type IconName } from "../icons.tsx";
import { Avatar, errText, me, RoomAvatar, Select } from "./common.tsx";
import { loadPrefs } from "./Settings.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";
import { EmojiPanel } from "./Emoji.tsx";

const run = (p: Promise<unknown> | void) => { p?.catch((e) => alertDialog(errText(e))); };
const canShare = !isNative && !!navigator.mediaDevices?.getDisplayMedia;

/**
 * Starts a call after ending another one. legacy: a peer-to-peer m.call.* call (DMs only); unset = MatrixRTC, or legacy in a DM
 * when our server has no SFU and developer options are on. MatrixRTC needs power to send m.call.member, which an admin can grant.
 */
export async function start(room: Room, video: boolean, legacy?: boolean) {
  const { active } = getCall();
  if (active && active.room !== room && !(await confirmDialog("تماس فعلی پایان یابد؟", { ok: "پایان و تماس", danger: true }))) return;
  legacy ??= loadPrefs().dev && isDirect(room) && !(await ourTransport());
  if (!legacy && !room.currentState.maySendStateEvent(EventType.GroupCallMemberPrefix, me())) {
    if (!room.currentState.maySendStateEvent(EventType.RoomPowerLevels, me())) return alertDialog("مدیر گروه هنوز تماس را برای اعضا فعال نکرده است");
    if (!(await confirmDialog("تماس در این گروه فعال شود؟ اعضا می‌توانند به تماس بپیوندند."))) return;
    await allowCalls(room.roomId);
  }
  await call(room, video, true, legacy);
}

/** Voice and video buttons for the room header: with an SFU, or in a DM with developer options on (legacy calls need none). */
export function CallButtons({ room }: { room: Room }) {
  const sfu = usePromise(ourTransport());
  const [menu, setMenu] = useState<{ x: number; y: number; video: boolean } | null>(null);
  const [menuShown, menuClosing] = useExit(!!menu);
  const lastMenu = useRef(menu);
  if (menu) lastMenu.current = menu;
  const m = menu ?? lastMenu.current;
  const dm = isDirect(room), dev = loadPrefs().dev;
  if (!sfu && !(dm && dev)) return null;
  // developer options, right-click / long-press in a DM: pick a legacy call by hand
  const pick = (video: boolean) => dm && sfu && dev ? (e: React.MouseEvent) => { e.preventDefault(); setMenu({ x: e.clientX, y: e.clientY, video }); } : undefined;
  return (
    <>
      <button className="icon-btn" onClick={() => run(start(room, false))} onContextMenu={pick(false)} title="تماس صوتی" aria-label="تماس صوتی"><Icon name="phone" /></button>
      <button className="icon-btn" onClick={() => run(start(room, true))} onContextMenu={pick(true)} title="تماس تصویری" aria-label="تماس تصویری"><Icon name="video" /></button>
      {menuShown && m && (
        <div className={"chat-menu-backdrop msg-menu-backdrop" + (menuClosing ? " closing" : "")} onClick={() => setMenu(null)}>
          <div className="chat-menu msg-menu" role="menu" style={{ left: Math.max(8, Math.min(m.x - 260, innerWidth - 268)), top: m.y }}>
            <button role="menuitem" onClick={() => { setMenu(null); run(start(room, m.video, false)); }}>
              <Icon name={m.video ? "video" : "phone"} /> تماس</button>
            <button role="menuitem" onClick={() => { setMenu(null); run(start(room, m.video, true)); }}>
              <Icon name={m.video ? "video" : "phone"} /> تماس با روش قدیمی</button>
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
  const showing = !!active && !active.min;
  const [screenShown, screenClosing] = useExit(showing, 220);
  const lastActive = useRef(active);
  if (active) lastActive.current = active;
  useEffect(() => { if (showing || !active) void leavePip(); }, [showing, !active]); // back on the call screen, or it ended
  const [pick, setPick] = useState<{ sources: ShareSource[]; done: (id: string | null) => void } | null>(null);
  useEffect(() => onPickSource((sources) => new Promise((done) => setPick({ sources, done }))), []);
  if (incoming && !active) return <IncomingCall room={incoming.room} video={incoming.video} />;
  return <>
    {screenShown && lastActive.current && <CallScreen a={lastActive.current} closing={screenClosing} />}
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

function CallScreen({ a, closing }: { a: Active; closing?: boolean }) {
  useEffect(() => pushBack(() => { minimize(true); }), []);
  const hide = () => { minimize(true); if (!isNative) void enterPip(a); }; // the click is the user gesture PiP needs
  const now = useClock(!!a.since);
  const { tiles, others } = tilesOf(a);
  const m = myMedia(a);
  const status = a.held ? "در انتظار (تماس تلفنی)" : a.reconnecting ? "در حال اتصال دوباره…" : a.notice ? a.notice : a.kind === "legacy"
    ? (a.since ? fmtDuration(now - a.since) : a.mc.state === CallState.InviteSent ? "در حال زنگ زدن…" : "در حال اتصال…")
    : a.lk.state !== ConnectionState.Connected ? "در حال اتصال…"
    : !a.since ? (isDirect(a.room) ? "در حال زنگ زدن…" : "در انتظار دیگران…")
    : !others ? "تنها هستید" : fmtDuration(now - a.since);
  const [focus, setFocus] = useState<string | null>(null); // a feed blown up to most of the screen
  const main = tiles.find((t) => t.key === focus); // gone (left, stopped sharing) = back to the grid
  const pip = !main && tiles.length === 2 && others === 1; // 1:1: the other side fills the screen, we're in the corner
  // big groups: at most 9 tiles, us and whoever is sharing, speaking or has a hand up first; "+N" opens the list
  const rank = (t: TileData) => (t.local ? 8 : 0) + (t.screen ? 4 : 0) + (t.speaking ? 2 : 0) + (t.hand ? 1 : 0);
  const grid = tiles.length > MAX_TILES ? [...tiles].sort((x, y) => rank(y) - rank(x)).slice(0, MAX_TILES - 1) : tiles;
  const group = a.kind === "rtc" && !isDirect(a.room);
  const hasVideo = m.cam || tiles.some((t) => t.video && !t.local);
  useEffect(() => { // Android: leaving the app during a video call shrinks it to picture-in-picture
    if (!isNative || !hasVideo) return;
    nativePip(true);
    return () => nativePip(false);
  }, [hasVideo]);
  const [panel, setPanel] = useState<"devices" | "people" | "reactions" | "emoji" | "routes" | null>(null);
  const toggle = (p: "devices" | "people" | "reactions") => setPanel(panel === p ? null : p);
  // a video fills the screen (spotlit, or the other side of a 1:1): edge to edge, controls float over it and hide after a while, a tap brings them back
  const immersive = !!(main ?? (pip ? tiles.find((t) => !t.local) : undefined))?.video;
  const [bare, setBare] = useState(false);
  const [poke, setPoke] = useState(0);
  useEffect(() => {
    if (!immersive || bare || panel) return;
    const t = setTimeout(() => setBare(true), 4000);
    return () => clearTimeout(t);
  }, [immersive, bare, panel, poke]);
  useEffect(() => { if (!immersive) setBare(false); }, [immersive]);
  useEffect(() => {
    if (!isNative || !immersive) return;
    nativeImmersive(true);
    return () => nativeImmersive(false);
  }, [immersive]);
  const screen = useRef<HTMLDivElement>(null);
  useEffect(() => { // how tall the floating controls are, for what sits just above them
    const el = screen.current, bar = el?.querySelector<HTMLElement>(".call-controls");
    if (!immersive || !el || !bar) return;
    const ro = new ResizeObserver(() => el.style.setProperty("--controls-h", bar.offsetHeight + "px"));
    ro.observe(bar);
    return () => ro.disconnect();
  }, [immersive]);
  const onTap = (e: React.MouseEvent) => {
    if (!immersive) return;
    if (bare) setBare(false);
    else if ((e.target as Element).closest("button, .call-panel-backdrop, .react-picker")) setPoke((n) => n + 1); // using the controls keeps them up
    else setBare(true);
  };
  // Android: the audio button shows where call audio goes; with a headset around it opens the list, otherwise it toggles the speaker
  const routes = a.routes?.routes ?? [];
  const route = routes.find((r) => r.id === a.routes?.current)?.kind ?? (a.speaker ? "speaker" : "earpiece");
  const headset = routes.some((r) => r.kind === "wired" || r.kind === "bluetooth");
  const speakerBtn = () => headset ? setPanel(panel === "routes" ? null : "routes") : toggleSpeaker();
  const tile = (t: TileData, focused = false) =>
    <Tile key={t.key} room={a.room} t={t} mirror={t.local && !t.screen && a.facing === "user"} focused={focused} onFocus={() => setFocus(focused ? null : t.key)} />;
  return (
    <div className={"call-screen" + (closing ? " closing" : "") + (immersive ? " immersive" : "") + (bare ? " bare" : "")} role="dialog" aria-label="تماس" onClick={onTap} ref={screen}>
      <header className="call-head">
        <button className="icon-btn" onClick={hide} title="کوچک کردن" aria-label="کوچک کردن"><Icon name="down" /></button>
        <div><b>{a.room.name}</b><span>{status}</span></div>
      </header>
      {main ? (
        <div className="call-grid focus">
          {tile(main, true)}
          <div className="call-strip">{tiles.filter((t) => t !== main).map((t) => tile(t))}</div>
        </div>
      ) : (
        <div className={"call-grid" + (pip ? " pip" : "")} data-n={grid.length + (grid.length < tiles.length ? 1 : 0)}>
          {grid.map((t) => tile(t))}
          {grid.length < tiles.length && (
            <button className="call-tile call-more" onClick={() => setPanel("people")}>+{num(tiles.length - grid.length)}</button>
          )}
        </div>
      )}
      <div className="call-controls">
        <CallBtn icon={m.mic ? "mic" : "micOff"} label="میکروفون" on={!m.mic} onClick={() => run(toggleMic())} />
        <CallBtn icon={m.cam ? "video" : "videoOff"} label="دوربین" on={!m.cam} onClick={() => run(toggleCam())} />
        {isNative && m.cam && <CallBtn icon="flip" label="چرخش دوربین" onClick={() => run(flipCam())} />}
        {isNative && <CallBtn icon={ROUTE_ICONS[route]} label={headset ? "خروجی صدا: " + ROUTE_LABELS[route] : "بلندگو"} on={headset ? panel === "routes" || route !== "earpiece" : a.speaker} onClick={speakerBtn} />}
        {canShare && <CallBtn icon="screen" label="اشتراک صفحه" on={m.screen} onClick={() => run(toggleScreen())} />}
        {group && <CallBtn icon="hand" label="بالا بردن دست" on={handOf(membershipOf(a.session, a.lk.localParticipant.identity)?.eventId)} onClick={() => run(toggleHand())} />}
        {a.kind === "rtc" && <CallBtn icon="smile" label="واکنش" on={panel === "reactions"} onClick={() => toggle("reactions")} />}
        {group && <CallBtn icon="group" label="شرکت‌کنندگان" on={panel === "people"} onClick={() => toggle("people")} />}
        {!isNative && <CallBtn icon="settings" label="میکروفون، دوربین و بلندگو" on={panel === "devices"} onClick={() => toggle("devices")} />}
        <CallBtn icon="hangup" label="پایان" danger onClick={() => run(hangup())} />
      </div>
      {panel === "emoji" && (
        <div className="react-picker"><EmojiPanel onEmoji={(e) => { setPanel(null); run(react(e)); }} onClose={() => setPanel(null)} /></div>
      )}
      {panel && panel !== "emoji" && (
        <div className="call-panel-backdrop" onClick={() => setPanel(null)}>
          <div className="call-panel" onClick={(e) => e.stopPropagation()}>
            {panel === "devices" ? <Devices />
              : panel === "people" ? <People room={a.room} tiles={tiles} />
              : panel === "reactions" ? <div className="call-reactions">{REACTIONS.map(([emoji, name]) => (
                <button key={name} onClick={() => { setPanel(null); run(react(emoji, name)); }} aria-label={emoji}>{emoji}</button>
              ))}<button onClick={() => setPanel("emoji")} title="همه‌ی اموجی‌ها" aria-label="همه‌ی اموجی‌ها"><Icon name="plus" /></button></div>
              : <Routes routes={routes} current={a.routes?.current ?? -1} onPick={(r) => { setPanel(null); run(setAudioRoute(r.id)); }} />}
          </div>
        </div>
      )}
    </div>
  );
}

const MAX_TILES = 9;
const QUALITY: Partial<Record<string, string>> = { poor: "اتصال ضعیف", lost: "اتصال قطع شده" };

/** Everyone in a group call: name, hand, mic, connection. */
function People({ room, tiles }: { room: Room; tiles: TileData[] }) {
  const people = tiles.filter((t) => !t.screen).sort((x, y) => Number(!!y.hand) - Number(!!x.hand)); // hands first, like a queue
  return <div className="call-people">{people.map((t) => {
    const m = room.getMember(t.userId);
    return (
      <div key={t.key}>
        <Avatar mxc={m?.getMxcAvatarUrl()} name={m?.name ?? t.userId} id={t.userId} size={32} />
        <span>{t.local ? "شما" : m?.name ?? t.userId}{t.quality && QUALITY[t.quality] && <small>{QUALITY[t.quality]}</small>}</span>
        {t.hand && <span aria-label="دست بالا">🖐️</span>}
        {t.micOff && <Icon name="micOff" size={16} />}
      </div>
    );
  })}</div>;
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
          <Select value={d[kind] ?? ""} onChange={(v) => run(setDevice(kind, v))}
            options={[...(d[kind] ? [] : [["", "پیش‌فرض سیستم"] as [string, string]]), ...list.map((x, i): [string, string] => [x.deviceId, x.label || `${label} ${num(i + 1)}`])]} />
        </label>
      );
    })}
    <label className="switch-row"><span>حذف نویز</span>
      <input type="checkbox" role="switch" checked={d.noiseSuppression ?? true} onChange={(e) => run(setNoiseSuppression(e.target.checked))} /></label>
  </>;
}

const ROUTE_LABELS: Record<AudioRoute["kind"], string> = { earpiece: "گوشی", speaker: "بلندگو", wired: "هدفون", bluetooth: "بلوتوث" };
const ROUTE_ICONS: Record<AudioRoute["kind"], IconName> = { earpiece: "phone", speaker: "speaker", wired: "headphones", bluetooth: "bluetooth" };

/** Android: where call audio goes. */
function Routes({ routes, current, onPick }: { routes: AudioRoute[]; current: number; onPick: (r: AudioRoute) => void }) {
  return <>{routes.map((r) => (
    <button key={r.id} className={r.id === current ? "on" : ""} onClick={() => onPick(r)} aria-pressed={r.id === current}>
      <Icon name={ROUTE_ICONS[r.kind]} />
      {r.kind === "bluetooth" && r.name ? r.name : ROUTE_LABELS[r.kind]}
      {r.id === current && <Icon name="check" size={18} />}
    </button>
  ))}</>;
}

/** Desktop/web: the other side's video (a shared screen first) in a floating window while the call is minimized. */
async function enterPip(a: Active) {
  if (!document.pictureInPictureEnabled) return;
  const remote = tilesOf(a).tiles.filter((t) => t.video && !t.local).sort((x, y) => Number(y.screen) - Number(x.screen))[0]?.video;
  if (!remote) return;
  const v = Object.assign(document.createElement("video"), { muted: true, playsInline: true });
  v.srcObject = remote instanceof MediaStream ? remote : new MediaStream([remote.mediaStreamTrack]);
  try {
    await v.play();
    await v.requestPictureInPicture();
  } catch { /* refused: the minimized bar is enough */ }
}
const leavePip = () => document.pictureInPictureElement ? document.exitPictureInPicture().catch(() => {}) : undefined;

/** One feed on the call screen: a camera or a shared screen, from LiveKit (track) or a legacy call (stream). */
type TileData = {
  key: string; userId: string; local: boolean; screen: boolean; video?: VideoTrack | MediaStream; micOff: boolean; speaking: boolean;
  hand?: boolean; reaction?: string; quality?: ConnectionQuality; // group calls
};

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
    const m = membershipOf(a.session, p.identity);
    const userId = m?.userId ?? p.identity.slice(0, p.identity.lastIndexOf(":"));
    const video = (src: Track.Source) => { const pub = p.getTrackPublication(src); return pub && !pub.isMuted ? pub.videoTrack : undefined; };
    const t = { userId, local: p.isLocal, micOff: !p.isMicrophoneEnabled, speaking: p.isSpeaking, hand: handOf(m?.eventId), reaction: reactionOf(m?.eventId), quality: p.connectionQuality };
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
      <span className="call-name">
        {(t.quality === ConnectionQuality.Poor || t.quality === ConnectionQuality.Lost) && <i className={"call-quality " + t.quality} title={QUALITY[t.quality]} />}
        {t.local ? "شما" : m?.name ?? t.userId}{t.micOff && <Icon name="micOff" size={14} />}
      </span>
      {t.hand && !t.screen && <span className="call-hand" aria-label="دست بالا">🖐️</span>}
      {t.reaction && !t.screen && <span key={t.reaction} className="call-reaction" aria-hidden>{t.reaction}</span>}
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
