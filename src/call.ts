import { useSyncExternalStore } from "react";
import { AutoDiscovery, EventType, MatrixEventEvent, RelationType, RoomEvent, type IRoomTimelineData, type MatrixEvent, type Room } from "matrix-js-sdk";
import { getCallNotificationExpiry, isLivekitTransportConfig, MatrixRTCSessionEvent, type IRTCNotificationContent, type LivekitTransportConfig, type MatrixRTCSession } from "matrix-js-sdk/lib/matrixrtc/index.js";
import { BaseKeyProvider, createKeyMaterialFromBuffer, createLocalTracks, Room as LkRoom, RoomEvent as LkEvent, Track, type LocalVideoTrack, type RemoteTrack } from "livekit-client";
import { avatarUrl, client, isDirect, isEncrypted, loadEvent } from "./matrix.ts";
import { isMuted, ringtone } from "./notify.ts";
import { isRing } from "./logic.ts";
import { isHeadless, isNative, nativeCallActive, nativeCancelCall, nativeShowCall, nativeSpeaker, toDataUrl, type CallAction } from "./native.ts";
import { isWindowVisible, showWindow } from "./desktop.ts";

/** MatrixRTC calls (what Element Call / Element X speak): membership and E2EE keys via the SDK, media via LiveKit. */

export type Active = {
  room: Room; session: MatrixRTCSession; lk: LkRoom; video: boolean;
  since?: number; // first time someone else was in the call
  min: boolean; speaker: boolean; facing: "user" | "environment";
};
export type Incoming = { room: Room; ev: MatrixEvent; video: boolean };
type Snap = { active?: Active; incoming?: Incoming; tick: number };

let snap: Snap = { tick: 0 };
const subs = new Set<() => void>();
const set = (p: Partial<Snap>) => { snap = { ...snap, ...p, tick: snap.tick + 1 }; subs.forEach((f) => f()); };
const patch = (p: Partial<Active>) => { if (snap.active) set({ active: { ...snap.active, ...p } }); };
const bump = () => set({});
const subscribe = (f: () => void) => (subs.add(f), () => void subs.delete(f));
export const useCall = () => useSyncExternalStore(subscribe, () => snap);
export const getCall = () => snap;

// ---------- finding the SFU ----------

let transport: Promise<LivekitTransportConfig | undefined> | undefined;
/** Our homeserver's LiveKit: MSC4143 /rtc/transports (newer Synapse), else .well-known rtc_foci (any server). */
export const ourTransport = () => transport ??= (async () => {
  const fromHs = await client.cachedRtcTransports.wait().catch(() => undefined);
  const list = fromHs?.length ? fromHs
    : (await AutoDiscovery.getRawClientConfig(client.getDomain()!).catch(() => undefined))?.["org.matrix.msc4143.rtc_foci"];
  return (Array.isArray(list) ? list : []).find(isLivekitTransportConfig);
})();

/** LiveKit JWT from lk-jwt-service. The legacy endpoint names us "@user:server:DEVICE", the identity our m.call.member keys are filed under. */
// ponytail: lk-jwt-service's /sfu/get only; move to /get_token (hashed identities) together with sticky m.rtc.member events
async function sfuToken(t: LivekitTransportConfig, roomId: string): Promise<{ url: string; jwt: string }> {
  const openid_token = await client.getOpenIdToken();
  const r = await fetch(t.livekit_service_url.replace(/\/+$/, "") + "/sfu/get", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ room: roomId, openid_token, device_id: client.getDeviceId() }),
  });
  if (!r.ok) throw new Error(`سرور تماس در دسترس نیست (${r.status})`);
  return r.json();
}

/** Per-participant media keys handed out by the SDK's MatrixRTC encryption manager. */
class Keys extends BaseKeyProvider {
  constructor() { super({ ratchetWindowSize: 0, keyringSize: 256 }); }
  async set(key: Uint8Array, index: number, identity: string) {
    this.onSetEncryptionKey(await createKeyMaterialFromBuffer(key.slice().buffer), identity, index);
  }
}

// ---------- the call ----------

let cleanup: (() => void)[] = [];

/** Starts (ring = true) or joins the room's call. One call at a time. */
export async function call(room: Room, video: boolean, ring = true) {
  if (snap.active) {
    if (snap.active.room === room) return patch({ min: false });
    await hangup();
  }
  stopRinging();
  // mic (and camera) first: if they're refused, nobody gets rung for nothing
  const media = await createLocalTracks({ audio: true, video: video ? { facingMode: "user" } : false });
  const mine = await ourTransport();
  const session = client.matrixRTC.getRoomSession(room);
  // everyone meets on the SFU of whoever was in the call first
  const oldest = session.getOldestMembership();
  const theirs = oldest?.getTransport(oldest);
  const sfu = theirs && isLivekitTransportConfig(theirs) ? theirs : mine;
  if (!sfu) { media.forEach((t) => t.stop()); throw new Error("سرور شما از تماس پشتیبانی نمی‌کند"); }
  const e2ee = isEncrypted(room);
  const keys = new Keys();
  const lk = new LkRoom({
    adaptiveStream: true, dynacast: true,
    e2ee: e2ee ? { keyProvider: keys, worker: new Worker(new URL("livekit-client/e2ee-worker", import.meta.url), { type: "module" }) } : undefined,
  });
  set({ active: { room, session, lk, video, min: false, speaker: video, facing: "user" } });

  const onKey = (key: Uint8Array, index: number, _m: unknown, identity: string) => void keys.set(key, index, identity);
  const onMembers = () => {
    const a = snap.active;
    if (!a || !isDirect(room)) return;
    // 1:1: the call is over once the other side has left
    if (a.since && !session.memberships.some((m) => m.userId !== client.getUserId())) void hangup();
  };
  session.on(MatrixRTCSessionEvent.EncryptionKeyChanged, onKey);
  session.on(MatrixRTCSessionEvent.MembershipsChanged, onMembers);
  const onTrack = (t: RemoteTrack) => { // plays even with the call screen minimized
    if (t.kind === Track.Kind.Audio) document.body.append(Object.assign(t.attach(), { className: "call-audio" }));
  };
  const offTrack = (t: RemoteTrack) => t.detach().forEach((el) => el.remove());
  const onPeople = () => {
    if (!snap.active?.since && lk.remoteParticipants.size) patch({ since: Date.now() });
    else bump();
  };
  lk.on(LkEvent.TrackSubscribed, onTrack).on(LkEvent.TrackUnsubscribed, offTrack)
    .on(LkEvent.ParticipantConnected, onPeople).on(LkEvent.ParticipantDisconnected, bump)
    .on(LkEvent.TrackMuted, bump).on(LkEvent.TrackUnmuted, bump).on(LkEvent.ActiveSpeakersChanged, bump)
    .on(LkEvent.LocalTrackPublished, bump).on(LkEvent.LocalTrackUnpublished, bump)
    .on(LkEvent.ConnectionStateChanged, bump).on(LkEvent.Disconnected, () => void hangup())
    .on(LkEvent.AudioPlaybackStatusChanged, () => { if (!lk.canPlaybackAudio) void lk.startAudio(); });
  // 1:1 nobody picks up: give up when our ring would have expired anyway
  const noAnswer = ring && isDirect(room) ? setTimeout(() => { if (!snap.active?.since) void hangup(); }, 90_000) : undefined;
  const leaveOnUnload = () => void hangup();
  addEventListener("pagehide", leaveOnUnload);
  cleanup = [
    () => { session.off(MatrixRTCSessionEvent.EncryptionKeyChanged, onKey); session.off(MatrixRTCSessionEvent.MembershipsChanged, onMembers); },
    () => clearTimeout(noAnswer),
    () => removeEventListener("pagehide", leaveOnUnload),
  ];

  try {
    const { url, jwt } = await sfuToken(sfu, room.roomId);
    if (snap.active?.lk !== lk) return media.forEach((t) => t.stop()); // hung up meanwhile
    const [userId, deviceId] = [client.getSafeUserId(), client.getDeviceId()!];
    session.joinRTCSession({ userId, deviceId, memberId: `${userId}:${deviceId}` }, [{ ...(mine ?? sfu), livekit_alias: room.roomId }], undefined, {
      notificationType: ring ? (isDirect(room) ? "ring" : "notification") : undefined, // the SDK only sends it if we're the first one in
      callIntent: video ? "video" : "audio",
      manageMediaKeys: e2ee,
    });
    session.reemitEncryptionKeys();
    await lk.connect(url, jwt);
    if (e2ee) await lk.setE2EEEnabled(true);
    onPeople();
    for (const t of media) await lk.localParticipant.publishTrack(t);
    if (isNative) { nativeCallActive(true, video); nativeSpeaker(video); }
  } catch (e) {
    media.forEach((t) => t.stop()); // not published yet = not stopped by the disconnect
    await hangup();
    throw e;
  }
}

export async function hangup() {
  const a = snap.active;
  if (!a) return;
  set({ active: undefined });
  cleanup.forEach((f) => f());
  cleanup = [];
  if (isNative) nativeCallActive(false, false);
  await a.lk.disconnect().catch(() => {});
  document.querySelectorAll("audio.call-audio").forEach((el) => el.remove()); // disconnecting doesn't unsubscribe them
  await a.session.leaveRoomSession(5000).catch(() => {});
}

export const minimize = (min: boolean) => patch({ min });

export async function toggleMic() {
  const p = snap.active?.lk.localParticipant;
  await p?.setMicrophoneEnabled(!p.isMicrophoneEnabled);
  bump();
}
export async function toggleCam() {
  const a = snap.active, p = a?.lk.localParticipant;
  await p?.setCameraEnabled(!p.isCameraEnabled, { facingMode: a!.facing });
  bump();
}
export async function flipCam() {
  const a = snap.active;
  if (!a) return;
  const facing = a.facing === "user" ? "environment" : "user";
  await (a.lk.localParticipant.getTrackPublication(Track.Source.Camera)?.videoTrack as LocalVideoTrack | undefined)?.restartTrack({ facingMode: facing });
  patch({ facing });
}
export async function toggleScreen() {
  const p = snap.active?.lk.localParticipant;
  await p?.setScreenShareEnabled(!p.isScreenShareEnabled, { audio: true });
  bump();
}
export function toggleSpeaker() {
  const a = snap.active;
  if (!a) return;
  nativeSpeaker(!a.speaker);
  patch({ speaker: !a.speaker });
}

// ---------- ringing ----------

let stopRing: (() => void)[] = [];

function stopRinging() {
  stopRing.forEach((f) => f());
  stopRing = [];
  if (isNative && snap.incoming && !snap.active) nativeCallActive(false, false); // a ring that woke the lock screen lets it sleep again
  if (snap.incoming) set({ incoming: undefined });
}

/** Rings for an m.rtc.notification if it's a ring meant for us, still fresh, and we're not in that call already. */
// ponytail: our own checks, not the SDK's parseCallNotificationContent: that one rejects rings in rooms without an m.rtc.slot, i.e. most of today's
function ring(room: Room, ev: MatrixEvent) {
  const c = ev.getContent<IRTCNotificationContent>(), me = client.getSafeUserId();
  const until = getCallNotificationExpiry(c, ev.getTs());
  if (!isRing(c, until, me) || ev.getSender() === me) return;
  if (snap.incoming || snap.active || isMuted(room) || client.isUserIgnored(ev.getSender()!)) return; // ponytail: busy = ignore, no call waiting
  const session = client.matrixRTC.getRoomSession(room);
  if (session.memberships.some((m) => m.userId === me)) return; // already in it (another device)
  const video = c["m.call.intent"] === "video";
  set({ incoming: { room, ev, video } });

  // stops when it expires, the caller gives up, or one of our devices answers
  const t = setTimeout(stopRinging, until - Date.now());
  const onMembers = () => {
    if (!session.memberships.length || session.memberships.some((m) => m.userId === me)) stopRinging();
  };
  session.on(MatrixRTCSessionEvent.MembershipsChanged, onMembers);
  stopRing = [() => clearTimeout(t), () => session.off(MatrixRTCSessionEvent.MembershipsChanged, onMembers)];

  if (isNative && (isHeadless || document.visibilityState === "hidden")) {
    void (async () => {
      const icon = await avatarUrl(room.getAvatarFallbackMember()?.getMxcAvatarUrl() ?? room.getMxcAvatarUrl(), 96)?.catch(() => undefined);
      if (snap.incoming?.ev !== ev) return;
      nativeShowCall({ roomId: room.roomId, eventId: ev.getId()!, caller: room.name, video, timeout: until - Date.now(), icon: icon && await toDataUrl(icon).catch(() => undefined) });
    })();
    stopRing.push(() => nativeCancelCall(room.roomId));
    return;
  }
  stopRing.push(ringtone());
  if (!isWindowVisible() && "Notification" in window && Notification.permission === "granted") {
    const n = new Notification(room.name, { body: video ? "تماس تصویری…" : "تماس صوتی…", tag: "call:" + room.roomId, requireInteraction: true, lang: "fa", dir: "rtl" });
    n.onclick = () => { showWindow(); n.close(); };
    stopRing.push(() => n.close());
  }
}

const sendDecline = (roomId: string, eventId: string) =>
  client.sendEvent(roomId, EventType.RTCDecline, { "m.relates_to": { rel_type: RelationType.Reference, event_id: eventId } } as never).catch(() => {});

export function decline() {
  const i = snap.incoming;
  if (!i) return;
  stopRinging();
  void sendDecline(i.room.roomId, i.ev.getId()!);
}

export async function answer(video: boolean) {
  const i = snap.incoming;
  if (i) await call(i.room, video, false);
}

function onEvent(ev: MatrixEvent, room: Room) {
  if (ev.getType() === EventType.RTCNotification) return ring(room, ev);
  if (ev.getType() !== EventType.RTCDecline) return;
  const of = ev.getContent()["m.relates_to"]?.event_id;
  // declined on another of our devices
  if (ev.getSender() === client.getUserId() && snap.incoming?.ev.getId() === of) stopRinging();
  // the other side of our 1:1 declined
  const a = snap.active;
  if (ev.getSender() !== client.getUserId() && a?.room === room && !a.since && isDirect(room)) void hangup();
}

/** Listens for rings and declines. Returns an unsubscribe. */
export function startCalls() {
  const onTimeline = (ev: MatrixEvent, room: Room | undefined, toStart: boolean | undefined, _removed: boolean, data: IRoomTimelineData) => {
    if (toStart || !data?.liveEvent || !room || !client.isInitialSyncComplete()) return;
    if (ev.getType() === EventType.RoomMessageEncrypted && !ev.isDecryptionFailure()) ev.once(MatrixEventEvent.Decrypted, () => onEvent(ev, room));
    else onEvent(ev, room);
  };
  client.on(RoomEvent.Timeline, onTimeline);
  return () => { client.off(RoomEvent.Timeline, onTimeline); };
}

/** Buttons on Android's call notification. */
export async function onNativeCall(a: CallAction) {
  const room = client.getRoom(a.roomId);
  if (!room) return;
  if (a.action === "decline") {
    if (snap.incoming?.ev.getId() === a.eventId) return decline();
    return void sendDecline(a.roomId, a.eventId);
  }
  if (a.action === "answer") return call(room, a.video, false);
  // tapped: the app was cold-started, so the ring never reached us live
  const ev = await loadEvent(room, a.eventId).catch(() => null);
  if (ev) ring(room, ev);
}
