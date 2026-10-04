import { useSyncExternalStore } from "react";
import { AutoDiscovery, CallEvent, CallFeedEvent, createNewMatrixCall, EventType, MatrixEventEvent, RelationType, RoomEvent, type IRoomTimelineData, type MatrixCall, type MatrixEvent, type Room } from "matrix-js-sdk";
import { CallErrorCode, CallState } from "matrix-js-sdk/lib/webrtc/call.js";
import { CallEventHandlerEvent } from "matrix-js-sdk/lib/webrtc/callEventHandler.js";
import type { CallFeed } from "matrix-js-sdk/lib/webrtc/callFeed.js";
import type { MCallInviteNegotiate } from "matrix-js-sdk/lib/webrtc/callEventTypes.js";
import { getCallNotificationExpiry, isLivekitTransportConfig, MatrixRTCSessionEvent, type IRTCNotificationContent, type LivekitTransportConfig, type MatrixRTCSession } from "matrix-js-sdk/lib/matrixrtc/index.js";
import { BaseKeyProvider, createKeyMaterialFromBuffer, createLocalTracks, Room as LkRoom, RoomEvent as LkEvent, Track, type LocalVideoTrack, type RemoteTrack } from "livekit-client";
import { avatarUrl, client, isDirect, isEncrypted, loadEvent } from "./matrix.ts";
import { callNotice, isMuted, ringtone } from "./notify.ts";
import { senderName } from "./ui/common.tsx";
import { isGroupCallAlert, isLegacyRing, isRing, isVideoOffer, pickProtocol } from "./logic.ts";
import { isHeadless, isNative, nativeCallActive, nativeCancelCall, nativeShowCall, nativeSpeaker, toDataUrl, type CallAction } from "./native.ts";
import { isWindowVisible, showWindow } from "./desktop.ts";

/**
 * Two kinds of call:
 * - MatrixRTC (what Element Call / Element X speak): membership and E2EE keys via the SDK, media via LiveKit. Groups and our own DMs.
 * - legacy 1:1 m.call.* (old Element, FluffyChat, Nheko…): the SDK's peer-to-peer MatrixCall. Answered, and used to call those people back.
 */

type Common = {
  room: Room; video: boolean;
  since?: number; // first time someone else was in the call
  min: boolean; speaker: boolean; facing: "user" | "environment";
};
export type Active = Common & ({ kind: "rtc"; session: MatrixRTCSession; lk: LkRoom } | { kind: "legacy"; mc: MatrixCall });
/** ev: the m.rtc.notification or m.call.invite. mc: the SDK's legacy call (not in Android's headless page, which leaves VoIP off). */
export type Incoming = { room: Room; ev: MatrixEvent; video: boolean; mc?: MatrixCall };
type Snap = { active?: Active; incoming?: Incoming; tick: number };

let snap: Snap = { tick: 0 };
const subs = new Set<() => void>();
const set = (p: Partial<Snap>) => { snap = { ...snap, ...p, tick: snap.tick + 1 }; subs.forEach((f) => f()); };
const patch = (p: Partial<Common>) => { if (snap.active) set({ active: { ...snap.active, ...p } }); };
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

/** In a DM, call back the way the other side last called us; groups are always MatrixRTC. */
export const protocolFor = (room: Room) => !isDirect(room) ? "rtc"
  : pickProtocol(room.getLiveTimeline().getEvents().map((e) => ({ type: e.getType(), sender: e.getSender()! })), client.getSafeUserId());

/** Starts (ring = true) or joins the room's call. One call at a time. */
export async function call(room: Room, video: boolean, ring = true, legacy = false) {
  if (snap.active) {
    if (snap.active.room === room) return patch({ min: false });
    await hangup();
  }
  stopRinging();
  if (legacy) return placeLegacy(room, video);
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
  set({ active: { kind: "rtc", room, session, lk, video, min: false, speaker: video, facing: "user" } });

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
  cleanup = [
    () => { session.off(MatrixRTCSessionEvent.EncryptionKeyChanged, onKey); session.off(MatrixRTCSessionEvent.MembershipsChanged, onMembers); },
    () => clearTimeout(noAnswer),
    leaveOnUnload(),
  ];

  try {
    const { url, jwt } = await sfuToken(sfu, room.roomId);
    const now = snap.active;
    if (now?.kind !== "rtc" || now.lk !== lk) return media.forEach((t) => t.stop()); // hung up meanwhile
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

function leaveOnUnload() {
  const f = () => void hangup();
  addEventListener("pagehide", f);
  return () => removeEventListener("pagehide", f);
}

export async function hangup() {
  const a = snap.active;
  if (!a) return;
  set({ active: undefined });
  cleanup.forEach((f) => f());
  cleanup = [];
  if (isNative) nativeCallActive(false, false);
  if (a.kind === "legacy") {
    if (!a.mc.callHasEnded()) a.mc.hangup(CallErrorCode.UserHangup, false);
    document.querySelectorAll("audio.call-audio").forEach((el) => el.remove());
    return;
  }
  await a.lk.disconnect().catch(() => {});
  document.querySelectorAll("audio.call-audio").forEach((el) => el.remove()); // disconnecting doesn't unsubscribe them
  await a.session.leaveRoomSession(5000).catch(() => {});
}

export const minimize = (min: boolean) => patch({ min });

/** What we're sending, for the control buttons. */
export function myMedia(a: Active) {
  if (a.kind === "legacy") return { mic: !a.mc.isMicrophoneMuted(), cam: !a.mc.isLocalVideoMuted() && a.mc.hasLocalUserMediaVideoTrack, screen: a.mc.isScreensharing() };
  const p = a.lk.localParticipant;
  return { mic: p.isMicrophoneEnabled, cam: p.isCameraEnabled, screen: p.isScreenShareEnabled };
}

export async function toggleMic() {
  const a = snap.active;
  if (a?.kind === "legacy") await a.mc.setMicrophoneMuted(!a.mc.isMicrophoneMuted());
  else if (a) await a.lk.localParticipant.setMicrophoneEnabled(!a.lk.localParticipant.isMicrophoneEnabled);
  bump();
}
export async function toggleCam() {
  const a = snap.active;
  if (a?.kind === "legacy") await a.mc.setLocalVideoMuted(myMedia(a).cam); // unmuting a voice call upgrades it to video
  else if (a) await a.lk.localParticipant.setCameraEnabled(!a.lk.localParticipant.isCameraEnabled, { facingMode: a.facing });
  bump();
}
export async function flipCam() {
  const a = snap.active;
  if (!a) return;
  const facing = a.facing === "user" ? "environment" : "user";
  if (a.kind === "legacy") { // the SDK picks cameras by device id: take the next one
    const cams = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === "videoinput");
    const now = a.mc.localUsermediaStream?.getVideoTracks()[0]?.getSettings().deviceId;
    const next = cams[(cams.findIndex((d) => d.deviceId === now) + 1) % cams.length];
    if (next) await client.getMediaHandler().setVideoInput(next.deviceId);
  } else {
    await (a.lk.localParticipant.getTrackPublication(Track.Source.Camera)?.videoTrack as LocalVideoTrack | undefined)?.restartTrack({ facingMode: facing });
  }
  patch({ facing });
}
export async function toggleScreen() {
  const a = snap.active;
  if (a?.kind === "legacy") await a.mc.setScreensharingEnabled(!a.mc.isScreensharing());
  else if (a) await a.lk.localParticipant.setScreenShareEnabled(!a.lk.localParticipant.isScreenShareEnabled, { audio: true });
  bump();
}
export function toggleSpeaker() {
  const a = snap.active;
  if (!a) return;
  nativeSpeaker(!a.speaker);
  patch({ speaker: !a.speaker });
}

// ---------- legacy 1:1 calls ----------

/** Rejects with the reason a legacy call failed to start (no mic, no TURN…), or resolves once it's under way. */
async function legacyStart(mc: MatrixCall, go: () => Promise<void>) {
  let err: unknown;
  const onErr = (e: { err?: unknown }) => { err = e.err ?? e; };
  mc.on(CallEvent.Error, onErr as never);
  try { await go(); } finally { mc.off(CallEvent.Error, onErr as never); }
  if (err) throw err; // getUserMedia's NotAllowedError etc., which errText understands
}

/** Puts a legacy call on screen and follows it until it ends. */
function showLegacy(room: Room, mc: MatrixCall, video: boolean) {
  set({ active: { kind: "legacy", room, mc, video, min: false, speaker: video, facing: "user" } });
  const audio = Object.assign(document.createElement("audio"), { className: "call-audio", autoplay: true });
  document.body.append(audio); // plays even with the call screen minimized
  const seen = new WeakSet<CallFeed>();
  const onFeeds = () => {
    for (const f of mc.getFeeds()) if (!seen.has(f)) { seen.add(f); f.on(CallFeedEvent.MuteStateChanged, bump); f.on(CallFeedEvent.NewStream, onFeeds); }
    if (audio.srcObject !== (mc.remoteUsermediaStream ?? null)) audio.srcObject = mc.remoteUsermediaStream ?? null;
    bump();
  };
  const onState = (s: CallState) => {
    if (s === CallState.Connected && !snap.active?.since) patch({ since: Date.now() });
    else if (s === CallState.Ended) void hangup();
    else bump();
  };
  const onHangup = () => void hangup(); // their hangup, decline, no answer, ICE failure
  const onReplaced = (next: MatrixCall) => { // glare: we called each other at once and the SDK kept their call
    cleanup.forEach((f) => f());
    audio.remove();
    showLegacy(room, next, video);
  };
  mc.on(CallEvent.FeedsChanged, onFeeds).on(CallEvent.State, onState).on(CallEvent.Hangup, onHangup).on(CallEvent.Replaced, onReplaced);
  cleanup = [() => { mc.off(CallEvent.FeedsChanged, onFeeds).off(CallEvent.State, onState).off(CallEvent.Hangup, onHangup).off(CallEvent.Replaced, onReplaced); }, leaveOnUnload()];
  onFeeds();
  if (isNative) { nativeCallActive(true, video); nativeSpeaker(video); }
}

async function placeLegacy(room: Room, video: boolean) {
  const mc = client.createCall(room.roomId);
  if (!mc) throw new Error("این دستگاه از تماس پشتیبانی نمی‌کند");
  showLegacy(room, mc, video);
  try {
    await legacyStart(mc, () => video ? mc.placeVideoCall() : mc.placeVoiceCall()); // the SDK gets mic/camera before inviting
  } catch (e) {
    await hangup();
    throw e;
  }
}

async function answerLegacy(room: Room, mc: MatrixCall, video: boolean) {
  if (snap.active) await hangup();
  stopRinging();
  if (mc.callHasEnded()) return;
  showLegacy(room, mc, video);
  try {
    await legacyStart(mc, () => mc.answer(true, video));
  } catch (e) {
    await hangup();
    throw e;
  }
}

/** The SDK's call for an invite. A cold-started app replayed the invite from its cache before the SDK listened, so build it ourselves then. */
async function legacyCallFor(room: Room, invite: MatrixEvent) {
  const h = client.callEventHandler, c = invite.getContent<MCallInviteNegotiate>();
  if (!h) return;
  const known = h.calls.get(c.call_id);
  if (known || !isLegacyRing(c, invite.getTs(), client.getSafeUserId())) return known;
  const mc = createNewMatrixCall(client, room.roomId, { forceTURN: client.forceTURN });
  if (!mc) return;
  mc.callId = c.call_id;
  await mc.initWithInvite(invite);
  h.calls.set(mc.callId, mc);
  // candidates that came before we were listening
  for (const ev of room.getLiveTimeline().getEvents()) {
    if (ev.getType() === EventType.CallCandidates && ev.getContent().call_id === mc.callId) await mc.onRemoteIceCandidatesReceived(ev);
  }
  return mc;
}

const rejectedByUs = (room: Room, callId: string) => room.getLiveTimeline().getEvents()
  .some((e) => e.getType() === EventType.CallReject && e.getSender() === client.getUserId() && e.getContent().call_id === callId);

const inviteOf = (room: Room, callId: string) =>
  room.getLiveTimeline().getEvents().findLast((e) => e.getType() === EventType.CallInvite && e.getContent().call_id === callId);

/** Our decline of a legacy invite, for when there's no MatrixCall to reject() (headless page). */
function rejectInvite(roomId: string, invite: MatrixEvent) {
  const c = invite.getContent(), v1 = String(c.version) === "1";
  return client.sendEvent(roomId, v1 ? EventType.CallReject : EventType.CallHangup,
    { call_id: c.call_id, party_id: client.getDeviceId(), version: v1 ? "1" : 0 } as never).catch(() => {});
}

// ---------- ringing ----------

let stopRing: (() => void)[] = [];

/** missed: it stopped without any of our devices answering or declining (caller gave up, or it timed out). */
function stopRinging(missed = false) {
  const i = snap.incoming;
  if (missed && i) void callNotice(i.room, i.video ? "تماس تصویری از دست رفته" : "تماس صوتی از دست رفته");
  stopRing.forEach((f) => f());
  stopRing = [];
  if (isNative && snap.incoming && !snap.active) nativeCallActive(false, false); // a ring that woke the lock screen lets it sleep again
  if (snap.incoming) set({ incoming: undefined });
}

/** Busy, muted, ignored or already in that call on another device: don't ring. */
// ponytail: busy = ignore, no call waiting
const shouldRing = (room: Room, from: string) => !snap.incoming && !snap.active && !isMuted(room) && !client.isUserIgnored(from);

/** Rings for an m.rtc.notification if it's a ring meant for us, still fresh, and we're not in that call already. */
// ponytail: our own checks, not the SDK's parseCallNotificationContent: that one rejects rings in rooms without an m.rtc.slot, i.e. most of today's
function ring(room: Room, ev: MatrixEvent) {
  const c = ev.getContent<IRTCNotificationContent>(), me = client.getSafeUserId();
  const until = getCallNotificationExpiry(c, ev.getTs());
  const session = client.matrixRTC.getRoomSession(room);
  const inIt = session.memberships.some((m) => m.userId === me); // already in it (another device)
  if (ev.getSender() === me || inIt || isMuted(room) || client.isUserIgnored(ev.getSender()!)) return;
  const video = c["m.call.intent"] === "video";
  if (isGroupCallAlert(c, until, me)) return void callNotice(room, `${senderName(ev)} ${video ? "تماس تصویری" : "تماس صوتی"} گروهی را شروع کرد`, true);
  if (!isRing(c, until, me) || !shouldRing(room, ev.getSender()!)) return;
  startRinging({ room, ev, video }, until);
  // stops when the caller gives up or one of our devices answers
  const onMembers = () => {
    if (session.memberships.some((m) => m.userId === me)) stopRinging();
    else if (!session.memberships.length) stopRinging(true);
  };
  session.on(MatrixRTCSessionEvent.MembershipsChanged, onMembers);
  stopRing.push(() => session.off(MatrixRTCSessionEvent.MembershipsChanged, onMembers));
}

/** Rings for a legacy invite: with the SDK's call (VoIP on), or just the event (headless page, which only shows the notification). */
function ringLegacy(room: Room, ev: MatrixEvent, mc?: MatrixCall) {
  const c = ev.getContent<MCallInviteNegotiate>();
  if (mc?.callHasEnded() || !isLegacyRing(c, ev.getTs(), client.getSafeUserId()) || ev.getSender() === client.getUserId() || !shouldRing(room, ev.getSender()!)) return;
  startRinging({ room, ev, mc, video: isVideoOffer(c) }, ev.getTs() + c.lifetime);
  if (!mc) return; // headless: onEvent stops it on the hangup / answer events
  // caller gave up, or answered/declined on another of our devices
  const onEnd = () => stopRinging(mc.hangupReason !== CallErrorCode.AnsweredElsewhere && !rejectedByUs(room, mc.callId));
  mc.on(CallEvent.Hangup, onEnd);
  stopRing.push(() => mc.off(CallEvent.Hangup, onEnd));
}

/** Rings until `until`: Android's call notification in the background, our ring screen and tone otherwise. */
function startRinging(i: Incoming, until: number) {
  const { room, ev, video } = i;
  set({ incoming: i });
  const t = setTimeout(() => stopRinging(true), until - Date.now());
  stopRing = [() => clearTimeout(t)];

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

/** Declines a ring, whichever kind it is. */
function declineEvent(room: Room, ev: MatrixEvent, mc?: MatrixCall) {
  if (ev.getType() !== EventType.CallInvite) return void sendDecline(room.roomId, ev.getId()!);
  if (mc) mc.reject();
  else void rejectInvite(room.roomId, ev);
}

export function decline() {
  const i = snap.incoming;
  if (!i) return;
  stopRinging();
  declineEvent(i.room, i.ev, i.mc);
}

export async function answer(video: boolean) {
  const i = snap.incoming;
  if (i?.mc) await answerLegacy(i.room, i.mc, video);
  else if (i) await call(i.room, video, false);
}

const LEGACY_ENDS: string[] = [EventType.CallHangup, EventType.CallReject, EventType.CallSelectAnswer, EventType.CallAnswer];

function onEvent(ev: MatrixEvent, room: Room) {
  const type = ev.getType();
  if (type === EventType.RTCNotification) return ring(room, ev);
  // legacy invites: the SDK hands us its call (Incoming below) when VoIP is on; the headless page only has the event
  if (type === EventType.CallInvite) return void (!client.callEventHandler && ringLegacy(room, ev));
  if (LEGACY_ENDS.includes(type)) { // headless: caller gave up, or another of our devices answered/declined
    const i = snap.incoming;
    const ends = type !== EventType.CallAnswer || ev.getSender() === client.getUserId(); // an answer only ends our ring if it's ours
    if (ends && i && !i.mc && i.ev.getType() === EventType.CallInvite && i.ev.getContent().call_id === ev.getContent().call_id) {
      stopRinging(type === EventType.CallHangup && ev.getSender() !== client.getUserId());
    }
    return;
  }
  if (type !== EventType.RTCDecline) return;
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
  const onIncoming = (mc: MatrixCall) => {
    const room = client.getRoom(mc.roomId), ev = room && inviteOf(room, mc.callId);
    if (room && ev) ringLegacy(room, ev, mc);
  };
  client.on(RoomEvent.Timeline, onTimeline);
  client.on(CallEventHandlerEvent.Incoming, onIncoming);
  return () => { client.off(RoomEvent.Timeline, onTimeline); client.off(CallEventHandlerEvent.Incoming, onIncoming); };
}

/** Buttons on Android's call notification. */
export async function onNativeCall(a: CallAction) {
  const room = client.getRoom(a.roomId);
  if (!room) return;
  const i = snap.incoming;
  if (i?.ev.getId() === a.eventId) return a.action === "decline" ? decline() : a.action === "answer" ? answer(a.video) : undefined;
  // not ringing here: the app was cold-started, or this is the headless page
  const ev = await loadEvent(room, a.eventId).catch(() => null);
  if (!ev) return;
  const legacy = ev.getType() === EventType.CallInvite;
  const mc = legacy ? await legacyCallFor(room, ev) : undefined;
  if (a.action === "decline") return declineEvent(room, ev, mc);
  if (a.action === "answer") return legacy ? mc && answerLegacy(room, mc, a.video) : call(room, a.video, false);
  if (legacy) ringLegacy(room, ev, mc); // tapped: show the ring screen
  else ring(room, ev);
}
