// Drafts, per account. Kept in localStorage so a reload or Android killing the app doesn't lose them; a chat's main draft
// (not a thread's) also syncs to our other devices as room account data, sealed in encrypted rooms (see sealDraft).
// Last write wins by `ts`; a cleared draft stays as {body: "", ts} so an older copy elsewhere can't bring it back.
import { useSyncExternalStore } from "react";
import { RoomEvent, type MatrixEvent, type Room } from "matrix-js-sdk";
import { client } from "./matrix.ts";
import { deriveDraftKey, openDraft, sealDraft } from "./logic.ts";

export type Draft = { body: string; reply?: string; ts: number };
type Remote = { ts?: number; body?: string; reply?: string; v?: string; iv?: string; ciphertext?: string };
const TYPE = "ir.panbeh.draft";
const UPLOAD_MS = 2000;

const storeKey = () => `panbeh.drafts:${client.getUserId()}`;
let drafts: Map<string, Draft> | null = null;
const all = () => {
  if (!drafts) {
    let raw: Record<string, string | Draft> = {};
    try { raw = JSON.parse(localStorage.getItem(storeKey()) ?? "{}"); } catch { /* none yet */ }
    drafts = new Map(Object.entries(raw).map(([k, v]) => [k, typeof v === "string" ? { body: v, ts: 0 } : v])); // older builds kept bare text
  }
  return drafts;
};
const save = () => { try { localStorage.setItem(storeKey(), JSON.stringify(Object.fromEntries(all()))); } catch { /* full or blocked: memory only */ } };
const isMain = (key: string) => !key.includes("$"); // thread drafts are roomId + threadId

// the chat list re-renders on a draft change, except the open chat's: typing there mustn't redraw the whole list
let version = 0;
const subs = new Set<() => void>();
const emit = () => { version++; subs.forEach((f) => f()); };
const subscribe = (f: () => void) => (subs.add(f), () => void subs.delete(f));
export const useDrafts = () => useSyncExternalStore(subscribe, () => version);

let open: { roomId: string; onRemote: (d: Draft) => void; ts: number } | null = null;
/** The composer of `roomId`'s main chat is showing: its draft leaves the list, and another device's update goes to `onRemote`. */
export function showingDraft(roomId: string, onRemote: (d: Draft) => void) {
  const me = { roomId, onRemote, ts: draftTs(roomId) };
  open = me;
  emit();
  return () => { if (open === me) { open = null; emit(); } };
}
export const getDraft = (key: string) => all().get(key);
/** When `roomId`'s draft was last changed, for the list order; the open chat keeps the time it had when opened, so it doesn't
 * drop (preview hidden) or jump (typing) while open, and moves once it's left. */
export const draftTs = (roomId: string) => (open?.roomId === roomId ? open.ts : all().get(roomId)?.body ? all().get(roomId)!.ts : 0);
/** The draft to show in the chat list, unless that chat is open. */
export const draftPreview = (roomId: string) => (open?.roomId === roomId ? undefined : all().get(roomId)?.body ? all().get(roomId) : undefined);

export function setDraft(key: string, body: string, reply?: string) {
  if (!body) reply = undefined;
  const d = all(), old = d.get(key);
  if ((old?.body ?? "") === body && old?.reply === reply) return;
  if (body || isMain(key)) d.set(key, { body, ...(reply && { reply }), ts: Date.now() });
  else d.delete(key);
  save();
  if (!isMain(key)) return; // thread drafts stay here and aren't listed
  upload(key, !body); // a send/clear goes out at once, so other devices drop it right away
  if (open?.roomId !== key) emit();
}

// ---------- sync ----------

const timers = new Map<string, number>();
function upload(roomId: string, now: boolean) {
  clearTimeout(timers.get(roomId));
  if (now) void push(roomId);
  else timers.set(roomId, window.setTimeout(() => void push(roomId), UPLOAD_MS));
}
const flush = () => { for (const id of [...timers.keys()]) { clearTimeout(timers.get(id)); void push(id); } };
addEventListener("pagehide", flush);
document.addEventListener("visibilitychange", () => { if (document.hidden) flush(); });

// ponytail: a failed upload (offline) isn't retried; the draft is still local and the next edit sends it
async function push(roomId: string) {
  timers.delete(roomId);
  const d = all().get(roomId), room = client.getRoom(roomId);
  if (!d || !room) return;
  let content: Remote = { ts: d.ts };
  if (d.body) {
    const plain = { body: d.body, ...(d.reply && { reply: d.reply }) };
    if (!room.hasEncryptionStateEvent()) content = { ...plain, ts: d.ts };
    else {
      const k = await backupKey();
      if (!k) return; // no key backup on this device: the draft stays here
      content = { ...(await sealDraft(k.key, roomId, plain)), v: k.version, ts: d.ts };
    }
  }
  await client.setRoomAccountData(roomId, TYPE as never, content as never).catch(() => {});
}

// ponytail: cached for the page's life; a key-backup reset elsewhere needs a reload to pick up the new key
let keyP: Promise<{ key: CryptoKey; version: string } | null> | null = null;
function backupKey() {
  return keyP ??= (async () => {
    const crypto = client.getCrypto();
    const raw = await crypto?.getSessionBackupPrivateKey(), version = raw && await crypto!.getActiveSessionBackupVersion();
    if (!raw || !version) { keyP = null; return null; } // try again next time: the user may unlock backup meanwhile
    return { key: await deriveDraftKey(new Uint8Array(raw)), version };
  })();
}

async function take(ev: MatrixEvent, room: Room) {
  if (ev.getType() !== TYPE) return;
  const c = ev.getContent<Remote>(), id = room.roomId;
  const newer = () => typeof c.ts === "number" && c.ts > (all().get(id)?.ts ?? -1); // our own echo isn't newer
  if (!newer()) return;
  let plain: { body?: string; reply?: string } | null = c;
  if (c.ciphertext) {
    const k = await backupKey();
    if (!k || k.version !== c.v) return; // sealed under a backup we don't have
    plain = await openDraft<{ body?: string; reply?: string }>(k.key, id, c as { iv: string; ciphertext: string }).catch(() => null);
  }
  if (!plain || !newer()) return; // a local edit may have landed while decrypting
  const d: Draft = { body: plain.body ?? "", ...(plain.reply && { reply: plain.reply }), ts: c.ts! };
  all().set(id, d);
  save();
  if (open?.roomId === id) open.onRemote(d);
  else emit();
}

/** Before startClient, so the first sync's (and the cache's) account data is caught too. Not in the headless page. */
export function syncDrafts() {
  client.on(RoomEvent.AccountData, (ev, room) => void take(ev, room));
}

// ---------- "Share with Panbeh" ----------

const shared = new Map<string, File[]>(); // shared from another app, waiting for the chat to open

/** Text becomes the chat's draft, files open the send dialog. */
export function shareInto(roomId: string, text: string, files: File[]) {
  const d = getDraft(roomId);
  if (text) setDraft(roomId, [d?.body, text].filter(Boolean).join("\n"), d?.reply);
  if (files.length) shared.set(roomId, files);
}
export function takeShared(roomId: string) {
  const f = shared.get(roomId) ?? [];
  shared.delete(roomId);
  return f;
}
