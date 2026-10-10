// App lock: a PIN (and on Android fingerprint/face) in front of the UI. It hides the app, it doesn't encrypt storage.
// Device-wide, not per account. The headless page never shows it.
import { useSyncExternalStore } from "react";
import { hashPin, lockDelay } from "./logic.ts";

/** after: minutes hidden before it locks again (0 = as soon as the app is left). len: the PIN's length, so entry ends by itself. */
export type LockConf = { salt: string; hash: string; iter: number; len: number; after: number; bio: boolean };
const KEY = "panbeh.lock", FAILS = "panbeh.lockFails";
// ponytail: a 4-digit PIN can't resist an offline guess whatever the iterations; this only has to keep unlocking quick
const ITER = 100_000;

export const lockConf = (): LockConf | null => { try { return JSON.parse(localStorage.getItem(KEY) ?? "null"); } catch { return null; } };
const save = (c: LockConf | null) => (c ? localStorage.setItem(KEY, JSON.stringify(c)) : localStorage.removeItem(KEY));

let locked = !!lockConf(); // starting up locks
const subs = new Set<() => void>();
const setLocked = (v: boolean) => { locked = v; subs.forEach((f) => f()); };
export const isLocked = () => locked;
export const onLockChange = (f: () => void) => { subs.add(f); return () => { subs.delete(f); }; };
export const useLocked = () => useSyncExternalStore(onLockChange, isLocked);

let hiddenAt = 0;
document.addEventListener("visibilitychange", () => {
  const c = lockConf();
  if (!c) return;
  if (document.hidden) { hiddenAt = Date.now(); if (c.after === 0) setLocked(true); }
  else if (hiddenAt && Date.now() - hiddenAt >= c.after * 60_000) setLocked(true);
});

// wrong tries survive a reload, so reloading doesn't reset the wait
type Fails = { n: number; last: number };
const fails = (): Fails => { try { return JSON.parse(localStorage.getItem(FAILS) ?? "null") ?? { n: 0, last: 0 }; } catch { return { n: 0, last: 0 }; } };
/** ms until the next try is allowed. */
export const waitLeft = () => { const f = fails(); return Math.max(0, f.last + lockDelay(f.n) - Date.now()); };

/** Whether `pin` is the PIN; wrong ones count toward the wait. */
export async function checkPin(pin: string) {
  const c = lockConf();
  if (!c) return true;
  if (waitLeft() > 0) return false;
  if ((await hashPin(pin, c.salt, c.iter)) === c.hash) { localStorage.removeItem(FAILS); return true; }
  const f = fails();
  localStorage.setItem(FAILS, JSON.stringify({ n: f.n + 1, last: Date.now() }));
  return false;
}

export async function unlock(pin: string) {
  const ok = await checkPin(pin);
  if (ok) setLocked(false);
  return ok;
}
export const unlockedByBiometric = () => { localStorage.removeItem(FAILS); setLocked(false); };

/** Sets (or changes) the PIN; other settings stay. */
export async function setPin(pin: string) {
  const c = lockConf(), salt = crypto.randomUUID();
  save({ salt, hash: await hashPin(pin, salt, ITER), iter: ITER, len: pin.length, after: c?.after ?? 5, bio: c?.bio ?? false });
}
export const setLockOpts = (p: Partial<Pick<LockConf, "after" | "bio">>) => { const c = lockConf(); if (c) save({ ...c, ...p }); };
/** keepLocked: the PIN is forgotten and every account is being signed out; the app stays covered until the reload. */
export function removeLock(keepLocked = false) {
  save(null);
  localStorage.removeItem(FAILS);
  if (!keepLocked) setLocked(false);
}
