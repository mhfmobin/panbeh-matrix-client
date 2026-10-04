import { useEffect, useMemo, useRef, useState } from "react";
import { EventType, type Room } from "matrix-js-sdk";
import { MatrixRTCSessionEvent } from "matrix-js-sdk/lib/matrixrtc/index.js";
import { ConnectionState, Track, type Participant } from "livekit-client";
import { allowCalls, client, isDirect } from "../matrix.ts";
import { answer, call, callLegacy, decline, flipCam, getCall, hangup, minimize, ourTransport, toggleCam, toggleMic, toggleScreen, toggleSpeaker, legacyCalls, useCall, type Active, type Legacy } from "../call.ts";
import { usePromise, useTick } from "../hooks.ts";
import { fmtDuration, num } from "../logic.ts";
import { isNative } from "../native.ts";
import { pushBack } from "../back.ts";
import { Icon, type IconName } from "../icons.tsx";
import { Avatar, errText, me, RoomAvatar } from "./common.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";

const run = (p: Promise<unknown> | void) => { p?.catch((e) => alertDialog(errText(e))); };
const canShare = !isNative && !!navigator.mediaDevices?.getDisplayMedia;

/** Starts a call, after making sure we may (members need power to send m.call.member) and ending another one. */
async function start(room: Room, video: boolean) {
  const { active } = getCall();
  if (active && active.room !== room && !(await confirmDialog("تماس فعلی پایان یابد؟", { ok: "پایان و تماس", danger: true }))) return;
  if (!room.currentState.maySendStateEvent(EventType.GroupCallMemberPrefix, me())) {
    if (!room.currentState.maySendStateEvent(EventType.RoomPowerLevels, me())) return alertDialog("مدیر گروه هنوز تماس را برای اعضا فعال نکرده است");
    if (!(await confirmDialog("تماس در این گروه فعال شود؟ اعضا می‌توانند به تماس بپیوندند."))) return;
    await allowCalls(room.roomId);
  }
  await call(room, video);
}

/** Voice and video buttons for the room header: MatrixRTC when the server has an SFU, else a legacy call in DMs. */
export function CallButtons({ room }: { room: Room }) {
  const found = usePromise(useMemo(() => ourTransport().then((sfu) => ({ sfu })), []));
  if (!found) return null;
  let go = (video: boolean) => start(room, video);
  if (!found.sfu) {
    if (!isDirect(room) || !legacyCalls()) return null;
    go = async (video) => {
      const { legacy, active } = getCall();
      if ((legacy && legacy.room !== room || active) && !(await confirmDialog("تماس فعلی پایان یابد؟", { ok: "پایان و تماس", danger: true }))) return;
      await callLegacy(room, video);
    };
  }
  return (
    <>
      <button className="icon-btn" onClick={() => run(go(false))} title="تماس صوتی" aria-label="تماس صوتی"><Icon name="phone" /></button>
      <button className="icon-btn" onClick={() => run(go(true))} title="تماس تصویری" aria-label="تماس تصویری"><Icon name="video" /></button>
    </>
  );
}

/** Thin bar under the header: back to a minimized call, or join the one going on in this room. */
export function CallBar({ room }: { room?: Room }) {
  const { active, legacy } = useCall();
  const session = room ? client.matrixRTC.getRoomSession(room) : undefined;
  useTick(session, [MatrixRTCSessionEvent.MembershipsChanged]);
  const now = useClock(!!(active?.since ?? legacy?.since));
  if (legacy?.min) return (
    <button className="call-bar" onClick={() => minimize(false)}>
      <Icon name="phone" size={18} /> <b>{legacy.room.name}</b>
      <span>{legacy.since ? fmtDuration(now - legacy.since) : "در حال اتصال…"}</span>
    </button>
  );
  if (active?.min) return (
    <button className="call-bar" onClick={() => minimize(false)}>
      <Icon name="phone" size={18} /> <b>{active.room.name}</b>
      <span>{active.since ? fmtDuration(now - active.since) : "در حال اتصال…"}</span>
    </button>
  );
  if (!room || active || legacy || !session?.memberships.length) return null;
  return (
    <button className="call-bar" onClick={() => run(start(room, false))}>
      <Icon name="phone" size={18} /> <b>تماس در جریان است</b>
      <span>{num(new Set(session.memberships.map((m) => m.userId)).size)} نفر · پیوستن</span>
    </button>
  );
}

/** The incoming ring and the call screen; mounted once by the app shell. */
export function CallLayer() {
  const { active, legacy, incoming } = useCall();
  if (active && !active.min) return <CallScreen a={active} />;
  if (legacy && !legacy.min) return <LegacyScreen l={legacy} />;
  if (incoming && !active && !legacy) return <IncomingCall room={incoming.room} video={incoming.video} />;
  return null;
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
  const lp = a.lk.localParticipant;
  const others = [...a.lk.remoteParticipants.values()];
  const status = a.lk.state !== ConnectionState.Connected ? "در حال اتصال…"
    : !a.since ? (isDirect(a.room) ? "در حال زنگ زدن…" : "در انتظار دیگران…")
    : !others.length ? "تنها هستید" : fmtDuration(now - a.since);
  // tiles: everyone's camera (or avatar), plus any screen being shared
  const tiles: [Participant, Track.Source][] = [...others, lp].flatMap((p) =>
    [[p, Track.Source.Camera] as [Participant, Track.Source]].concat(p.isScreenShareEnabled ? [[p, Track.Source.ScreenShare]] : []));
  const [focus, setFocus] = useState<string | null>(null); // a feed blown up to most of the screen
  const key = ([p, src]: [Participant, Track.Source]) => p.identity + src;
  const main = tiles.find((t) => key(t) === focus); // gone (left, stopped sharing) = back to the grid
  const pip = !main && tiles.length === 2 && others.length === 1; // 1:1: the other side fills the screen, we're in the corner
  return (
    <div className="call-screen" role="dialog" aria-label="تماس">
      <header className="call-head">
        <button className="icon-btn" onClick={() => minimize(true)} title="کوچک کردن" aria-label="کوچک کردن"><Icon name="down" /></button>
        <div><b>{a.room.name}</b><span>{status}</span></div>
      </header>
      {main ? (
        <div className="call-grid focus">
          <Tile key={key(main)} a={a} p={main[0]} source={main[1]} focused onFocus={() => setFocus(null)} />
          <div className="call-strip">
            {tiles.filter((t) => t !== main).map((t) => <Tile key={key(t)} a={a} p={t[0]} source={t[1]} onFocus={() => setFocus(key(t))} />)}
          </div>
        </div>
      ) : (
        <div className={"call-grid" + (pip ? " pip" : "")} data-n={tiles.length}>
          {tiles.map((t) => <Tile key={key(t)} a={a} p={t[0]} source={t[1]} onFocus={() => setFocus(key(t))} />)}
        </div>
      )}
      <div className="call-controls">
        <CallBtn icon={lp.isMicrophoneEnabled ? "mic" : "micOff"} label="میکروفون" on={!lp.isMicrophoneEnabled} onClick={() => run(toggleMic())} />
        <CallBtn icon={lp.isCameraEnabled ? "video" : "videoOff"} label="دوربین" on={!lp.isCameraEnabled} onClick={() => run(toggleCam())} />
        {isNative && lp.isCameraEnabled && <CallBtn icon="flip" label="چرخش دوربین" onClick={() => run(flipCam())} />}
        {isNative && <CallBtn icon="speaker" label="بلندگو" on={a.speaker} onClick={toggleSpeaker} />}
        {canShare && <CallBtn icon="screen" label="اشتراک صفحه" on={lp.isScreenShareEnabled} onClick={() => run(toggleScreen())} />}
        <CallBtn icon="hangup" label="پایان" danger onClick={() => run(hangup())} />
      </div>
    </div>
  );
}

/** A legacy 1:1 call: the other side's video fills the screen, ours sits in the corner. */
function LegacyScreen({ l }: { l: Legacy }) {
  useEffect(() => pushBack(() => { minimize(true); }), []);
  const now = useClock(!!l.since);
  const { mx } = l;
  const remote = mx.remoteUsermediaStream, local = mx.localUsermediaStream;
  const showRemote = !!remote?.getVideoTracks().some((t) => t.enabled && !t.muted);
  const camOn = !mx.isLocalVideoMuted() && !!local?.getVideoTracks().length;
  const micOn = !mx.isMicrophoneMuted();
  const peer = mx.getOpponentMember();
  const status = l.since ? fmtDuration(now - l.since) : mx.state === "ringing" || mx.state === "invite_sent" ? "در حال زنگ زدن…" : "در حال اتصال…";
  return (
    <div className="call-screen" role="dialog" aria-label="تماس">
      <header className="call-head">
        <button className="icon-btn" onClick={() => minimize(true)} title="کوچک کردن" aria-label="کوچک کردن"><Icon name="down" /></button>
        <div><b>{l.room.name}</b><span>{status}</span></div>
      </header>
      <div className="call-grid pip" data-n={2}>
        <StreamTile stream={remote} show={showRemote} name={peer?.name ?? l.room.name} id={peer?.userId ?? ""} mxc={peer?.getMxcAvatarUrl()} />
        <StreamTile stream={local} show={camOn} local name="شما" id={me()} mxc={l.room.getMember(me())?.getMxcAvatarUrl()} mic={micOn} />
      </div>
      <div className="call-controls">
        <CallBtn icon={micOn ? "mic" : "micOff"} label="میکروفون" on={!micOn} onClick={() => run(toggleMic())} />
        <CallBtn icon={camOn ? "video" : "videoOff"} label="دوربین" on={!camOn} onClick={() => run(toggleCam())} />
        {isNative && <CallBtn icon="speaker" label="بلندگو" on={l.speaker} onClick={toggleSpeaker} />}
        <CallBtn icon="hangup" label="پایان" danger onClick={() => run(hangup())} />
      </div>
    </div>
  );
}

function StreamTile({ stream, show, local, name, id, mxc, mic = true }: { stream?: MediaStream; show: boolean; local?: boolean; name: string; id: string; mxc?: string | null; mic?: boolean }) {
  const ref = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (el && show) el.srcObject = stream ?? null; // remote audio plays through the call-audio element, so the video stays muted
  }, [stream, show]);
  return (
    <div className={"call-tile" + (local ? " local" : "")}>
      {show ? <video ref={ref} autoPlay playsInline muted className={local ? "mirror" : ""} />
        : <Avatar mxc={mxc} name={name} id={id} size={88} />}
      <span className="call-name">{name}{!mic && <Icon name="micOff" size={14} />}</span>
    </div>
  );
}

function Tile({ a, p, source, focused, onFocus }: { a: Active; p: Participant; source: Track.Source; focused?: boolean; onFocus: () => void }) {
  const ref = useRef<HTMLVideoElement>(null);
  const pub = p.getTrackPublication(source);
  const track = pub && !pub.isMuted ? pub.videoTrack : undefined;
  useEffect(() => {
    const el = ref.current;
    if (!track || !el) return;
    track.attach(el);
    return () => { track.detach(el); };
  }, [track]);
  // LiveKit identities are "@user:server:DEVICE" (or hashed, for newer clients: ask the session)
  const userId = a.session.memberships.find((m) => m.rtcBackendIdentity === p.identity)?.userId ?? p.identity.slice(0, p.identity.lastIndexOf(":"));
  const m = a.room.getMember(userId);
  const mirror = p.isLocal && source === Track.Source.Camera && a.facing === "user";
  return (
    <div className={"call-tile" + (p.isLocal ? " local" : "") + (p.isSpeaking ? " speaking" : "") + (focused ? " main" : "")}>
      {track ? <video ref={ref} autoPlay playsInline muted className={(source === Track.Source.ScreenShare || focused ? "contain" : "") + (mirror ? " mirror" : "")} />
        : <Avatar mxc={m?.getMxcAvatarUrl()} name={m?.name ?? userId} id={userId} size={88} />}
      <span className="call-name">{p.isLocal ? "شما" : m?.name ?? userId}{!p.isMicrophoneEnabled && <Icon name="micOff" size={14} />}</span>
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
