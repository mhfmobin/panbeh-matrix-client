import { ClientEvent, createClient, EventTimeline, EventType, SearchOrderBy, HttpApiEvent, IndexedDBStore, MatrixEvent, Method, OAuth2, Preset, SetPresence, Visibility, type ICreateRoomStateEvent, type MatrixClient, type MatrixError, type Room } from "matrix-js-sdk";
import { decodeRecoveryKey, deriveRecoveryKeyFromPassphrase } from "matrix-js-sdk/lib/crypto-api/index.js";
import { decryptAttachment, encryptAttachment, type IEncryptedFile } from "matrix-encrypt-attachment";
import { fitSize, normalizeServer, roomName } from "./logic.ts";
import { isHeadless, isNative, nativeCancelAll, setBackgroundService } from "./native.ts";
import { isDesktop, isWindowVisible, onWindowVisibility, openExternal } from "./desktop.ts";
import { confirmDialog } from "./ui/dialog.tsx";

type Session = { baseUrl: string; userId: string; deviceId: string; accessToken: string; refreshToken?: string; oauthClientId?: string; legacy?: boolean };
// several accounts, one running client at a time; switching reloads the page so every cache starts clean
const SESSIONS_KEY = "panbeh.sessions", ACTIVE_KEY = "panbeh.active", OLD_KEY = "panbeh.session", ADDING_KEY = "panbeh.adding";
if (localStorage.getItem(OLD_KEY)) { // single-account era: keep its original DB names so the crypto keys survive
  const old: Session = JSON.parse(localStorage.getItem(OLD_KEY)!);
  localStorage.setItem(SESSIONS_KEY, JSON.stringify([{ ...old, legacy: true }]));
  localStorage.setItem(ACTIVE_KEY, old.userId);
  localStorage.removeItem(OLD_KEY);
}
export const sessions = (): Session[] => JSON.parse(localStorage.getItem(SESSIONS_KEY) ?? "[]");
const setSessions = (l: Session[]) => localStorage.setItem(SESSIONS_KEY, JSON.stringify(l));
const save = (s: Session) => { // upsert and make active
  const old = sessions().find((x) => x.userId === s.userId);
  setSessions([...sessions().filter((x) => x.userId !== s.userId), { ...s, legacy: old?.legacy }]);
  localStorage.setItem(ACTIVE_KEY, s.userId);
  sessionStorage.removeItem(ADDING_KEY);
};
const dbNames = (s: Session) => s.legacy ? { sync: "panbeh-sync", crypto: "panbeh" } : { sync: `panbeh-sync:${s.userId}`, crypto: `panbeh:${s.userId}` };
let current: Session | undefined; // the account the running client belongs to
export const isAdding = () => !!sessionStorage.getItem(ADDING_KEY);
export const addAccount = () => { sessionStorage.setItem(ADDING_KEY, "1"); location.reload(); };
export const cancelAdd = () => { sessionStorage.removeItem(ADDING_KEY); location.reload(); };
export function switchAccount(userId: string) {
  localStorage.setItem(ACTIVE_KEY, userId);
  location.hash = "";
  location.reload();
}

export let client: MatrixClient;

/** Who invited us, for invites whose stripped state has no members to name the room after (e.g. Conduit). */
function inviter(room: Room | null) {
  if (room?.getMyMembership() !== "invite") return;
  const id = room.currentState.getStateEvents(EventType.RoomMember, room.myUserId)?.getSender();
  return id && (room.getMember(id)?.name ?? id);
}
// secret-storage key the user just typed/created; handed to the SDK via getSecretStorageKey
let ssKey: [string, Uint8Array<ArrayBuffer>] | null = null;

export const savedSession = (): Session | null => sessions().find((x) => x.userId === localStorage.getItem(ACTIVE_KEY)) ?? null;
export const isOAuth = () => !!savedSession()?.oauthClientId;

export async function start(s: Session) {
  current = s;
  // cached sync state: reopening the app shows chats instantly instead of waiting for a full initial sync
  const store = new IndexedDBStore({ indexedDB, localStorage, dbName: dbNames(s).sync });
  const c = createClient({
    ...s,
    // OAuth sessions: the SDK refreshes short-lived tokens itself and revokes them on logout
    onTokenRefresh: (t) => save({ ...sessions().find((x) => x.userId === s.userId)!, accessToken: t.accessToken, refreshToken: t.refreshToken }),
    store,
    timelineSupport: true,
    disableVoip: true, // legacy 1:1 m.call.* stack; our calls are MatrixRTC (call.ts)
    verificationMethods: ["m.sas.v1"], // emoji only: we can't show or scan QR codes, so don't let the other side pick them
    roomNameGenerator: (roomId, state) => roomName(state, inviter(c.getRoom(roomId))),
    cryptoCallbacks: {
      getSecretStorageKey: async ({ keys }) => (ssKey && keys[ssKey[0]] ? ssKey : null),
      cacheSecretStorageKey: (keyId, _info, key) => { ssKey = [keyId, key]; },
    },
  });
  client = c;
  await store.startup(); // after createClient: restoring cached presence needs the client's createUser
  // token revoked elsewhere: the SDK just stops syncing, so drop the session and go back to login
  c.once(HttpApiEvent.SessionLoggedOut, () => logout());
  c.on(ClientEvent.Room, (r) => { if (r.getMyMembership() === "invite") r.recalculate(); });
  await c.initRustCrypto({ cryptoDatabasePrefix: dbNames(s).crypto });
  await c.startClient({ threadSupport: true, lazyLoadMembers: true, initialSyncLimit: 30 });
  if (!shareLastSeen()) await setShareLastSeen(false);
  if (isNative) { // the app syncs in the background too: that mustn't show the user as online
    const away = () => { if (shareLastSeen()) void c.setSyncPresence(isHeadless || document.visibilityState === "hidden" ? SetPresence.Unavailable : undefined); };
    away();
    document.addEventListener("visibilitychange", away);
  } else if (isDesktop) { // the same while the window sits in the tray
    const away = () => { if (shareLastSeen()) void c.setSyncPresence(isWindowVisible() ? undefined : SetPresence.Unavailable); };
    away();
    onWindowVisibility(away);
  }
  if (import.meta.env.DEV) Object.assign(window, { mx: c });
  return c;
}

export async function login(server: string, user: string, password: string) {
  const baseUrl = normalizeServer(server);
  const r = await createClient({ baseUrl }).loginRequest({
    type: "m.login.password",
    identifier: { type: "m.id.user", user },
    password,
    initial_device_display_name: isNative ? "Panbeh Android" : isDesktop ? "Panbeh Desktop" : "Panbeh Web",
  });
  const s = { baseUrl, userId: r.user_id, deviceId: r.device_id, accessToken: r.access_token };
  save(s);
  return start(s);
}

// ---------- OAuth 2.0 (next-gen auth, e.g. MAS) ----------

const PENDING_KEY = "panbeh.oauthPending";
// Android and desktop: login happens in the browser, which hands the code back through the app's own URL scheme.
// MAS wants a native app's scheme to be its client_uri's host reversed.
const NATIVE_REDIRECT = "ir.panbeh.app:/oauth", NATIVE_CLIENT_URI = "https://app.panbeh.ir/";
const isApp = isNative || isDesktop;
const redirectUri = () => isApp ? NATIVE_REDIRECT : location.origin + location.pathname;
// the app's page may be recreated while the browser is in front, losing sessionStorage
const pendingStore = () => isApp ? localStorage : sessionStorage;

/** How this server logs in: on its own page (OAuth, e.g. MAS) or with a password. Throws if it isn't reachable. */
export async function loginMode(baseUrl: string) {
  const c = createClient({ baseUrl });
  if (await c.getAuthMetadata().catch(() => null)) return "oauth";
  await c.getVersions(); // no OAuth: make sure it's a Matrix server at all before asking for a password
  return "password";
}

/** Registers Panbeh with the server's auth service (once per issuer) and leaves the page for its login screen. */
export async function startOAuth(server: string) {
  const baseUrl = normalizeServer(server);
  const meta = await createClient({ baseUrl }).getAuthMetadata();
  const idKey = `panbeh.oauthClient:${meta.issuer}:${redirectUri()}`;
  let clientId = localStorage.getItem(idKey);
  if (!clientId) {
    // MAS only registers https web apps; over http it takes us as a "native" app with a localhost/127.0.0.1
    // redirect, but still wants an https homepage, so borrow the server's
    const https = location.protocol === "https:" && !isApp;
    clientId = await OAuth2.registerClient(meta, {
      client_name: "Panbeh", client_uri: isApp ? NATIVE_CLIENT_URI : https ? location.origin + "/" : baseUrl + "/", redirect_uris: [redirectUri()],
      application_type: https ? "web" : "native",
    });
    localStorage.setItem(idKey, clientId);
  }
  const o = new OAuth2(meta, { clientId });
  const state = crypto.randomUUID();
  pendingStore().setItem(PENDING_KEY, JSON.stringify({ state, baseUrl, ...o.context }));
  // query, not fragment: the hash is the open room
  const url = await o.generateAuthorizationCodeGrantUrl(state, redirectUri(), "query");
  if (isDesktop) openExternal(url); // the browser, where the user's password manager and passkeys are
  else location.href = url;
}

/** True if this page load is the auth service redirecting back to us. */
export const isOAuthCallback = () => new URLSearchParams(location.search).has("state");

export async function finishOAuth() {
  const q = new URLSearchParams(location.search);
  history.replaceState(null, "", location.origin + location.pathname); // the code is single-use; don't leave it in the URL
  const pending = JSON.parse(pendingStore().getItem(PENDING_KEY) ?? "null");
  pendingStore().removeItem(PENDING_KEY);
  if (q.get("error")) throw new Error(q.get("error_description") || q.get("error")!);
  if (!pending || pending.state !== q.get("state")) throw new Error("ورود ناتمام ماند؛ دوباره تلاش کنید");
  const { baseUrl, clientId, deviceId } = pending;
  const meta = await createClient({ baseUrl }).getAuthMetadata();
  const t = await new OAuth2(meta, pending).completeAuthorizationCodeGrant(q.get("code")!, redirectUri());
  const { user_id } = await createClient({ baseUrl, accessToken: t.access_token }).whoami();
  const s = { baseUrl, userId: user_id, deviceId, accessToken: t.access_token, refreshToken: t.refresh_token, oauthClientId: clientId };
  save(s);
  return start(s);
}

export async function logout() {
  // drop only this account, first, so a hang below can't leave a dead session behind
  const left = sessions().filter((x) => x.userId !== current?.userId);
  setSessions(left);
  if (left.length) localStorage.setItem(ACTIVE_KEY, left[0].userId); else localStorage.removeItem(ACTIVE_KEY);
  if (isNative) { nativeCancelAll(); if (!left.length) setBackgroundService(false); }
  // client is unset if start() failed before creating it (boot error screen)
  await client?.logout(true).catch(() => {});
  // also wipes the rust crypto IndexedDB
  // ponytail: 3s cap; a blocked IDB delete would otherwise hang sign-out forever
  await Promise.race([client?.clearStores().catch(() => {}), new Promise((r) => setTimeout(r, 3000))]);
  // clearStores can leave the rust crypto DBs behind; best effort, a blocked delete just stays pending
  if (current) for (const n of ["matrix-sdk-crypto", "matrix-sdk-crypto-meta"]) indexedDB.deleteDatabase(`${dbNames(current).crypto}::${n}`);
  mediaCache.forEach((p) => p.then(URL.revokeObjectURL, () => {}));
  location.reload();
}

// ---------- E2EE: recovery ----------

/** "unlock" = account has secret storage we can load; "setup" = nothing yet; "ok" = done. */
export async function recoveryState(): Promise<"ok" | "unlock" | "setup"> {
  const crypto = client.getCrypto()!;
  if ((await crypto.isCrossSigningReady()) && (await crypto.getSessionBackupPrivateKey())) return "ok";
  return (await client.secretStorage.getDefaultKeyId()) ? "unlock" : "setup";
}

/** Accepts either the recovery key or the security passphrase. */
export async function unlock(input: string) {
  const crypto = client.getCrypto()!;
  const found = await client.secretStorage.getKey();
  if (!found) throw new Error("بازیابی برای این حساب راه‌اندازی نشده است");
  const [keyId, info] = found;
  let key: Uint8Array<ArrayBuffer> | undefined;
  try { key = decodeRecoveryKey(input.trim()); } catch {
    if (info.passphrase) key = await deriveRecoveryKeyFromPassphrase(input, info.passphrase.salt, info.passphrase.iterations);
  }
  if (!key || !(await client.secretStorage.checkKey(key, info))) throw new Error("کلید یا عبارت امنیتی درست نیست");
  ssKey = [keyId, key];
  await crypto.bootstrapCrossSigning({}); // pulls cross-signing keys from secret storage, signs this device
  await crypto.loadSessionBackupPrivateKeyFromSecretStorage();
  await crypto.checkKeyBackupAndEnable();
  crypto.restoreKeyBackup().catch(console.warn); // old message keys, in the background
}

/** Fresh setup: new cross-signing + secret storage + key backup. Returns the recovery key to show once.
 *  OAuth accounts have no password: the server's auth service approves the upload instead. */
export async function setupRecovery(password?: string) {
  const crypto = client.getCrypto()!;
  let recoveryKey = "";
  await crypto.bootstrapCrossSigning({
    setupNewCrossSigning: true,
    authUploadDeviceSigningKeys: async (makeRequest) => {
      if (password) return void await makeRequest({ type: "m.login.password", identifier: { type: "m.id.user", user: client.getSafeUserId() }, password });
      try { await makeRequest(null); } catch (e) {
        // first upload needs no auth; replacing existing keys must be approved in the auth service (MSC4312)
        const params = (e as MatrixError).data?.params as Record<string, { url?: string }> | undefined;
        const url = params?.["m.oauth"]?.url ?? params?.["org.matrix.cross_signing_reset"]?.url;
        if (!url) throw e;
        window.open(url, "_blank");
        if (!(await confirmDialog("بازنشانی کلیدها را در صفحه‌ی بازشده تأیید کنید، سپس «تأیید» را بزنید."))) throw new Error("لغو شد");
        await makeRequest(null);
      }
    },
  });
  await crypto.bootstrapSecretStorage({
    setupNewSecretStorage: true,
    setupNewKeyBackup: true,
    createSecretStorageKey: async () => {
      const k = await crypto.createRecoveryKeyFromPassphrase();
      recoveryKey = k.encodedPrivateKey!;
      return k;
    },
  });
  return recoveryKey;
}

// ---------- media ----------

type FileContent = { url?: string; file?: IEncryptedFile & { url: string; mimetype?: string }; info?: { mimetype?: string } };
const mediaCache = new Map<string, Promise<string>>();

/** Blob URL for an mxc (authenticated media, decrypting if needed). Cached for the session. */
export function mediaUrl(c: FileContent, thumb?: { w: number; h: number }): Promise<string> | null {
  const mxc = c.file?.url ?? c.url;
  if (!mxc) return null;
  const cacheKey = mxc + (thumb && !c.file ? `@${thumb.w}` : "");
  let p = mediaCache.get(cacheKey);
  if (!p) {
    // encrypted media can't be thumbnailed server-side; any resize arg switches the SDK to /thumbnail
    const t = thumb && !c.file ? thumb : undefined;
    const http = new URL(client.mxcUrlToHttp(mxc, t?.w, t?.h, t && "scale", false, true, true)!);
    // through the SDK, not fetch: OAuth access tokens expire every few minutes and it refreshes them
    p = client.http.authedRequest<Blob>(Method.Get, http.pathname, Object.fromEntries(http.searchParams), undefined,
      { baseUrl: http.origin, prefix: "", rawResponseBody: true })
      .then((b) => b.arrayBuffer())
      // typed so <audio>/<video> don't have to sniff; thumbnails may be another image type, so leave those untyped
      .then(async (buf) => URL.createObjectURL(new Blob([c.file ? await decryptAttachment(buf, c.file) : buf],
        { type: thumb ? "" : c.info?.mimetype ?? c.file?.mimetype ?? "" })));
    p.catch(() => mediaCache.delete(cacheKey));
    mediaCache.set(cacheKey, p);
  }
  return p;
}

type SendOpts = { onProgress?: (loaded: number, total: number) => void; abort?: AbortController; caption?: string; onUploaded?: () => void; asFile?: boolean };

/** `extra` is merged over the generated content (voice messages set msgtype, duration, waveform). A caption goes in body (MSC2530), the name in filename. */
export async function sendFile(room: Room, file: File, threadId: string | null, replyTo?: MatrixEvent, extra?: Record<string, unknown>, opts: SendOpts = {}) {
  const { onProgress, abort, caption, asFile } = opts;
  const up = { progressHandler: onProgress && (({ loaded, total }: { loaded: number; total: number }) => onProgress(loaded, total)), abortController: abort };
  const encrypted = client.getCrypto() ? await client.getCrypto()!.isEncryptionEnabledInRoom(room.roomId) : false;
  if (!asFile && !extra) file = await compressImage(file);
  const info: Record<string, unknown> = { mimetype: file.type, size: file.size };
  const msgtype = asFile ? "m.file" : file.type.startsWith("image/") ? "m.image" : file.type.startsWith("video/") ? "m.video" : "m.file";
  if (msgtype === "m.image") Object.assign(info, await imageSize(file));

  const content: Record<string, unknown> = { msgtype, body: caption || file.name, ...(caption && { filename: file.name }), ...extra, info: { ...info, ...(extra?.info as object) } };
  if (encrypted) {
    const { data, info: fileInfo } = await encryptAttachment(await file.arrayBuffer());
    const { content_uri } = await client.uploadContent(new Blob([data]), { type: "application/octet-stream", includeFilename: false, ...up });
    content.file = { ...fileInfo, url: content_uri, mimetype: file.type };
  } else {
    content.url = (await client.uploadContent(file, up)).content_uri;
  }
  if (abort?.signal.aborted) return;
  if (replyTo) content["m.relates_to"] = { "m.in_reply_to": { event_id: replyTo.getId() } };
  opts.onUploaded?.();
  await client.sendMessage(room.roomId, threadId, content as never);
}

// ---------- pending uploads (shown as bubbles until the SDK's local echo takes over) ----------

export type Upload = {
  id: number; roomId: string; threadId: string | null; file: File; previewUrl?: string; extra?: Record<string, unknown>;
  loaded: number; total: number; error?: string; abort: AbortController; retry: () => void;
};
let uploads: Upload[] = [];
const subs = new Set<() => void>();
const emit = (u: Upload[]) => { uploads = u; subs.forEach((f) => f()); };
const patch = (id: number, p: Partial<Upload>) => emit(uploads.map((u) => (u.id === id ? { ...u, ...p } : u)));
export const subscribeUploads = (f: () => void) => { subs.add(f); return () => { subs.delete(f); }; };
export const getUploads = () => uploads;

function removeUpload(id: number) {
  const u = uploads.find((x) => x.id === id);
  if (!u) return;
  if (u.previewUrl) URL.revokeObjectURL(u.previewUrl);
  emit(uploads.filter((x) => x !== u));
}
/** Cancel (aborts a running upload) and remove. */
export const cancelUpload = (id: number) => { uploads.find((u) => u.id === id)?.abort.abort(); removeUpload(id); };

let queue: Promise<unknown> = Promise.resolve(); // one upload at a time, in order
let nextId = 1;

export function startUpload(room: Room, file: File, threadId: string | null, replyTo?: MatrixEvent, extra?: Record<string, unknown>, caption?: string, asFile?: boolean) {
  const id = nextId++;
  const preview = !asFile && /^(image|video|audio)\//.test(file.type); // audio: a voice note plays from here while it uploads
  const run = () => {
    const abort = new AbortController();
    patch(id, { abort, error: undefined, loaded: 0 });
    queue = queue.then(async () => {
      if (abort.signal.aborted) return;
      try {
        await sendFile(room, file, threadId, replyTo, extra, {
          caption, asFile, abort, onProgress: (loaded, total) => patch(id, { loaded, total }), onUploaded: () => removeUpload(id),
        });
      } catch (e) {
        if (!abort.signal.aborted) patch(id, { error: (e as Error).message || "error" });
      }
    });
  };
  emit([...uploads, {
    id, roomId: room.roomId, threadId, file, extra, previewUrl: preview ? URL.createObjectURL(file) : undefined,
    loaded: 0, total: file.size, abort: new AbortController(), retry: run,
  }]);
  run();
}

/** Downscale photos to 1280px JPEG; keeps the original for gif/svg or if the result isn't smaller. */
async function compressImage(file: File): Promise<File> {
  if (!/^image\/(jpeg|png|webp|bmp)$/.test(file.type)) return file;
  try {
    const bmp = await createImageBitmap(file);
    const { w, h } = fitSize(bmp.width, bmp.height);
    const canvas = document.createElement("canvas");
    canvas.width = w; canvas.height = h;
    const ctx = canvas.getContext("2d")!;
    ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, w, h); // JPEG has no alpha
    ctx.drawImage(bmp, 0, 0, w, h);
    const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, "image/jpeg", 0.82));
    if (!blob || blob.size >= file.size) return file;
    return new File([blob], file.name.replace(/\.[^.]+$/, "") + ".jpg", { type: "image/jpeg" });
  } catch { return file; }
}

const imageSize = (file: File) =>
  createImageBitmap(file).then((b) => ({ w: b.width, h: b.height }), () => ({}));

export const avatarUrl = (mxc: string | null | undefined, size = 96) =>
  mxc ? mediaUrl({ url: mxc }, { w: size, h: size }) : null;

// ---------- rooms ----------

const ENCRYPTION: ICreateRoomStateEvent = { type: EventType.RoomEncryption, state_key: "", content: { algorithm: "m.megolm.v1.aes-sha2" } };

/** Record a DM in m.direct (is_direct only lives on the invite event, so clients must keep this list). */
export async function addDirect(userId: string, roomId: string) {
  const direct = client.getAccountData(EventType.Direct)?.getContent<Record<string, string[]>>() ?? {};
  if (direct[userId]?.includes(roomId)) return;
  await client.setAccountData(EventType.Direct, { ...direct, [userId]: [...(direct[userId] ?? []), roomId] });
}

/** A DM: listed in m.direct, or an invite flagged is_direct. */
export function isDirect(room: Room) {
  const direct = client.getAccountData(EventType.Direct)?.getContent<Record<string, string[]>>() ?? {};
  return !!room.getDMInviter() || Object.values(direct).some((ids) => ids.includes(room.roomId));
}

/** The other person in a DM (m.direct, else the one other joined member). */
export function dmPeer(room: Room) {
  const direct = client.getAccountData(EventType.Direct)?.getContent<Record<string, string[]>>() ?? {};
  return Object.entries(direct).find(([, ids]) => ids.includes(room.roomId))?.[0]
    ?? room.getJoinedMembers().find((m) => m.userId !== client.getSafeUserId())?.userId;
}

// presence: others see us as offline when off. Kept in prefs (localStorage) so it applies at startup too.
export const shareLastSeen = () => JSON.parse(localStorage.getItem("panbeh.prefs") ?? "{}").shareLastSeen !== false;
export const setShareLastSeen = (on: boolean) => client.setSyncPresence(on ? undefined : SetPresence.Offline);

/** Joined DM room id with the user, if any. */
export function findDM(userId: string) {
  const direct = client.getAccountData(EventType.Direct)?.getContent<Record<string, string[]>>() ?? {};
  return direct[userId]?.find((id) => client.getRoom(id)?.getMyMembership() === "join");
}

/** Existing joined DM with the user, or a new encrypted one. Returns the room id. */
export async function openDM(userId: string) {
  const existing = findDM(userId);
  if (existing) return existing;
  const { room_id } = await client.createRoom({ is_direct: true, invite: [userId], preset: Preset.TrustedPrivateChat, initial_state: [ENCRYPTION] });
  await addDirect(userId, room_id);
  return room_id;
}

export type NewChat = {
  kind: "group" | "space"; name: string; topic?: string; avatar?: File | null;
  invite: string[]; encrypted: boolean; alias?: string; parentSpace?: string;
};

export async function createChat(o: NewChat) {
  const initial_state: ICreateRoomStateEvent[] = [];
  if (o.avatar) initial_state.push({ type: EventType.RoomAvatar, state_key: "", content: { url: await uploadAvatar(o.avatar) } });
  if (o.encrypted && o.kind === "group") initial_state.push(ENCRYPTION);
  const { room_id } = await client.createRoom({
    name: o.name,
    topic: o.topic || undefined,
    invite: o.invite,
    preset: o.alias ? Preset.PublicChat : Preset.PrivateChat,
    visibility: o.alias ? Visibility.Public : Visibility.Private,
    room_alias_name: o.alias || undefined,
    creation_content: o.kind === "space" ? { type: "m.space" } : undefined,
    initial_state,
  });
  if (o.kind === "group") await allowCalls(room_id).catch(() => {});
  if (o.parentSpace) await addToSpace(o.parentSpace, room_id);
  return room_id;
}

/** Lets ordinary members join calls: their m.call.member state events need power 0 (what Element's rooms set too). */
export async function allowCalls(roomId: string) {
  const pl = await client.getStateEvent(roomId, EventType.RoomPowerLevels, "");
  await client.sendStateEvent(roomId, EventType.RoomPowerLevels, { ...pl, events: { ...pl.events, [EventType.GroupCallMemberPrefix]: 0 } } as never);
}

const uploadAvatar = async (file: File) => (await client.uploadContent(file)).content_uri;

/** Returns the new mxc. */
export async function setMyAvatar(file: File) {
  const url = await uploadAvatar(file);
  await client.setAvatarUrl(url);
  return url;
}

export const setRoomAvatar = async (roomId: string, file: File) =>
  client.sendStateEvent(roomId, EventType.RoomAvatar, { url: await uploadAvatar(file) }, "");

// a child without `via` counts as removed (see useRooms)
export const addToSpace = (spaceId: string, roomId: string) =>
  client.sendStateEvent(spaceId, EventType.SpaceChild, { via: [client.getDomain()!] }, roomId);
export const removeFromSpace = (spaceId: string, roomId: string) =>
  client.sendStateEvent(spaceId, EventType.SpaceChild, {}, roomId);

/** Who (besides me) has read up to `ev`, newest first.
 *  ponytail: ts is the user's *latest* receipt, which can be later than when they read this exact message;
 *  Matrix only keeps the latest receipt per user, so it can't be exact. */
export function seenBy(room: Room, ev: MatrixEvent) {
  const myId = client.getSafeUserId(), id = ev.getId();
  if (!id || ev.status) return [];
  // getUsersReadUpTo only lists receipts sitting exactly on `ev`; anyone whose receipt is on a later event read it too
  // ponytail: members × own messages per render; fine for groups, index receipts by event if big rooms lag
  return room.getJoinedMembers().map((m) => m.userId).filter((u) => u !== myId && room.hasUserReadEvent(u, id))
    .map((u) => ({ userId: u, member: room.getMember(u), ts: room.getReadReceiptForUserId(u)?.data.ts ?? 0 }))
    .sort((a, b) => b.ts - a.ts);
}

// ---------- pins ----------

export const pinnedIds = (room: Room): string[] =>
  room.currentState.getStateEvents(EventType.RoomPinnedEvents, "")?.getContent().pinned?.filter((id: unknown) => typeof id === "string") ?? [];

export const togglePin = (room: Room, id: string) => {
  const ids = pinnedIds(room);
  return client.sendStateEvent(room.roomId, EventType.RoomPinnedEvents,
    { pinned: ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id] }, "");
};

const fetched = new Map<string, Promise<MatrixEvent>>();

/** An event by id: from the loaded timelines, else fetched from the server (and decrypted). Cached. */
export function loadEvent(room: Room, id: string): Promise<MatrixEvent> {
  const local = room.findEventById(id);
  if (local) return Promise.resolve(local);
  let p = fetched.get(id);
  if (!p) {
    p = client.fetchRoomEvent(room.roomId, id).then(async (raw) => {
      const ev = new MatrixEvent(raw);
      await client.decryptEventIfNeeded(ev);
      return ev;
    });
    p.catch(() => fetched.delete(id));
    fetched.set(id, p);
  }
  return p;
}

// ---------- forwarding ----------
/** Content for a copy of `ev` in another room; remembers the original author across re-forwards. */
export function forwardContent(ev: MatrixEvent): Record<string, unknown> {
  const c: Record<string, unknown> = { ...ev.getContent() }; // getContent already applies edits
  delete c["m.relates_to"];
  delete c["m.new_content"];
  c["m.mentions"] = {}; // a forward must not ping anyone
  if (typeof c.body === "string") c.body = c.body.replace(/^(> .*\n)+\n?/, "");
  if (typeof c.formatted_body === "string") c.formatted_body = c.formatted_body.replace(/<mx-reply>[\s\S]*?<\/mx-reply>/, "");
  const sender = ev.getSender()!;
  c["app.panbeh.forwarded"] = ev.getContent()["app.panbeh.forwarded"] ?? { sender, name: client.getRoom(ev.getRoomId()!)?.getMember(sender)?.name ?? sender };
  return c;
}

export const forwardTo = (roomId: string, ev: MatrixEvent) => client.sendEvent(roomId, ev.getType() as never, forwardContent(ev) as never);

// ---------- devices ----------

export class NeedsPassword extends Error {}

/** Runs a user-interactive-auth request: first without auth (some servers don't ask), then with the account password.
 *  Throws NeedsPassword if the server wants one and none was given. */
export async function withPassword<T>(run: (auth?: never) => Promise<T>, password?: string): Promise<T> {
  try {
    return await run();
  } catch (e) {
    const err = e as MatrixError;
    if (err.httpStatus !== 401 || !err.data?.flows) throw e;
    if (!password) throw new NeedsPassword();
    try {
      return await run({ type: "m.login.password", identifier: { type: "m.id.user", user: client.getSafeUserId() }, password, session: err.data.session } as never);
    } catch (e2) {
      if ([401, 403].includes((e2 as MatrixError).httpStatus ?? 0)) throw new Error("رمز عبور درست نیست");
      throw e2;
    }
  }
}

export const deleteDevices = (ids: string[], password?: string) =>
  withPassword((auth) => client.deleteMultipleDevices(ids, auth), password);

/** OAuth accounts end sessions on the account-management page, not through the client API. */
export async function deviceManageUrl(deviceId?: string) {
  const meta = await client.getAuthMetadata();
  if (!meta.account_management_uri) return null;
  const has = (a: string) => meta.account_management_actions_supported?.includes(a);
  const url = new URL(meta.account_management_uri);
  if (deviceId) {
    url.searchParams.set("action", has("org.matrix.device_delete") ? "org.matrix.device_delete" : "org.matrix.session_end");
    url.searchParams.set("device_id", deviceId);
  } else url.searchParams.set("action", has("org.matrix.sessions_list") && !has("org.matrix.devices_list") ? "org.matrix.sessions_list" : "org.matrix.devices_list");
  return url.href;
}

/** Account page of an OAuth server (password, deactivation…), opened at `action` when it supports it. */
export async function accountManageUrl(action?: string) {
  const meta = await client.getAuthMetadata();
  if (!meta.account_management_uri) return null;
  const url = new URL(meta.account_management_uri);
  if (action && meta.account_management_actions_supported?.includes(action)) url.searchParams.set("action", action);
  return url.href;
}

// ---------- search ----------

/** Server-side search; finds nothing in encrypted rooms. */
export async function searchServer(term: string, roomId?: string): Promise<MatrixEvent[]> {
  try {
    const r = await client.search({ body: { search_categories: { room_events: { search_term: term, order_by: SearchOrderBy.Recent, filter: roomId ? { rooms: [roomId] } : {} /* Conduit rejects a missing filter */ } } } });
    return (r.search_categories.room_events?.results ?? []).map((x) => new MatrixEvent(x.result));
  } catch {
    return [];
  }
}

export const isEncrypted = (room: Room) => room.hasEncryptionStateEvent();

/** Page the live timeline back and decrypt what arrived. Resolves to whether older history remains. */
export async function searchOlder(room: Room, pages = 5): Promise<boolean> {
  const tl = room.getLiveTimeline();
  try {
    for (let i = 0; i < pages && tl.getPaginationToken(EventTimeline.BACKWARDS); i++) await client.paginateEventTimeline(tl, { backwards: true, limit: 40 });
    await Promise.all(tl.getEvents().map((e) => client.decryptEventIfNeeded(e).catch(() => {})));
  } catch { /* offline: keep what we have */ }
  return !!tl.getPaginationToken(EventTimeline.BACKWARDS);
}

// ---------- blocking (m.ignored_user_list: the server stops sending their events and invites) ----------

export const setBlocked = (userId: string, blocked: boolean) => {
  const ids = client.getIgnoredUsers().filter((id) => id !== userId);
  return client.setIgnoredUsers(blocked ? [...ids, userId] : ids);
};
