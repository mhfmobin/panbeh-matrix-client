import { useEffect, useRef, useState } from "react";
import { EventType, type Room } from "matrix-js-sdk";
import { MatrixRTCSessionEvent } from "matrix-js-sdk/lib/matrixrtc/index.js";
import { CallState } from "matrix-js-sdk/lib/webrtc/call.js";
import type { CallFeed } from "matrix-js-sdk/lib/webrtc/callFeed.js";
import { ConnectionState, Track, type VideoTrack } from "livekit-client";
import { allowCalls, client, isDirect } from "../matrix.ts";
import { answer, call, decline, flipCam, getCall, hangup, minimize, myMedia, ourTransport, protocolFor, toggleCam, toggleMic, toggleScreen, toggleSpeaker, useCall, type Active } from "../call.ts";
import { usePromise, useTick } from "../hooks.ts";
import { fmtDuration, num } from "../logic.ts";
import { isNative } from "../native.ts";
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
  if (active && !active.min) return <CallScreen a={active} />;
  if (incoming && !active) return <IncomingCall room={incoming.room} video={incoming.video} />;
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
  const { tiles, others } = tilesOf(a);
  const m = myMedia(a);
  const status = a.kind === "legacy"
    ? (a.since ? fmtDuration(now - a.since) : a.mc.state === CallState.InviteSent ? "در حال زنگ زدن…" : "در حال اتصال…")
    : a.lk.state !== ConnectionState.Connected ? "در حال اتصال…"
    : !a.since ? (isDirect(a.room) ? "در حال زنگ زدن…" : "در انتظار دیگران…")
    : !others ? "تنها هستید" : fmtDuration(now - a.since);
  const [focus, setFocus] = useState<string | null>(null); // a feed blown up to most of the screen
  const main = tiles.find((t) => t.key === focus); // gone (left, stopped sharing) = back to the grid
  const pip = !main && tiles.length === 2 && others === 1; // 1:1: the other side fills the screen, we're in the corner
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
        {isNative && <CallBtn icon="speaker" label="بلندگو" on={a.speaker} onClick={toggleSpeaker} />}
        {canShare && <CallBtn icon="screen" label="اشتراک صفحه" on={m.screen} onClick={() => run(toggleScreen())} />}
        <CallBtn icon="hangup" label="پایان" danger onClick={() => run(hangup())} />
      </div>
    </div>
  );
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
    // LiveKit identities are "@user:server:DEVICE" (or hashed, for newer clients: ask the session)
    const userId = a.session.memberships.find((m) => m.rtcBackendIdentity === p.identity)?.userId ?? p.identity.slice(0, p.identity.lastIndexOf(":"));
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
