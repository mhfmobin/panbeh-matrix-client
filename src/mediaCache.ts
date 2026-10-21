// Downloaded media kept across restarts (Cache API), so avatars, thumbnails and opened files aren't fetched again.
// Bytes are stored as downloaded: encrypted attachments stay encrypted at rest and are decrypted on each use.
import { toEvict } from "./logic.ts";

const NAME = "panbeh-media";
export const EMOJI_CACHE = "panbeh-emoji"; // Apple's emoji pictures (jsx-runtime.ts), cleared along with the media
export const CACHE_MAX_FILE = 100 * 1024 * 1024; // bigger files aren't worth evicting everything else for
export const CACHE_MB_DEFAULT = 1024;
// the Cache API wants request URLs; the size (and k=1 for a kept one) rides along in the URL so the index can be
// rebuilt from keys() alone
const urlOf = (key: string, size: number, keep?: boolean) => `https://media.panbeh.invalid/${encodeURIComponent(key)}?s=${size}${keep ? "&k=1" : ""}`;
type Entry = { url: string; size: number; keep: boolean };

/** key → its URL and size, oldest first (keys() lists in insertion order). Built once. */
let index: Promise<{ cache: Cache; entries: Map<string, Entry> } | null> | null = null;
const load = () => index ??= (async () => {
  try {
    const cache = await caches.open(NAME);
    const entries = new Map<string, Entry>();
    for (const r of await cache.keys()) {
      const u = new URL(r.url);
      entries.set(decodeURIComponent(u.pathname.slice(1)), { url: r.url, size: +(u.searchParams.get("s") ?? 0), keep: u.searchParams.has("k") });
    }
    return { cache, entries };
  } catch { return null; } // no Cache API here (insecure origin, private mode): just don't cache
})();

const capBytes = () => {
  try { return (JSON.parse(localStorage.getItem("panbeh.prefs") ?? "{}").cacheMB ?? CACHE_MB_DEFAULT) * 1024 * 1024; } catch { return CACHE_MB_DEFAULT * 1024 * 1024; }
};

export async function cacheGet(key: string): Promise<ArrayBuffer | null> {
  const ix = await load();
  const e = ix?.entries.get(key);
  if (!ix || !e) return null;
  try {
    const res = await ix.cache.match(e.url);
    if (res) return await res.arrayBuffer();
  } catch { /* fall through to the network */ }
  ix.entries.delete(key); // evicted by the browser meanwhile
  return null;
}

/** Whether `key` is kept, without reading it. */
export const cacheHas = async (key: string) => !!(await load())?.entries.has(key);

// ponytail: FIFO, not LRU: an avatar seen every day still ages out after a cache's worth of newer media; refresh on hit if that bites
/** `keep`: never evicted, and not counted against the cap (custom emoji and stickers); "Clear cache" still drops it. */
export function cachePut(key: string, buf: ArrayBuffer, keep?: boolean) {
  if (buf.byteLength > CACHE_MAX_FILE) return;
  const body = new Blob([buf]); // copied now: the caller may hand the buffer on
  void (async () => {
    const ix = await load();
    if (!ix || ix.entries.has(key)) return;
    const url = urlOf(key, body.size, keep);
    try { await ix.cache.put(url, new Response(body)); } catch { return; } // quota: skip
    ix.entries.set(key, { url, size: body.size, keep: !!keep });
    await trim(ix);
  })();
}

type Index = NonNullable<Awaited<ReturnType<typeof load>>>;
async function trim(ix: Index) {
  for (const k of toEvict([...ix.entries].flatMap(([k, e]) => (e.keep ? [] : [[k, e.size] as [string, number]])), capBytes())) {
    const e = ix.entries.get(k)!;
    ix.entries.delete(k);
    await ix.cache.delete(e.url).catch(() => {});
  }
}

/** Bytes in the cache, as indexed. */
export const cacheSize = async () => [...((await load())?.entries.values() ?? [])].reduce((n, e) => n + e.size, 0);

export async function cacheClear() {
  index = null;
  await Promise.all([NAME, EMOJI_CACHE].map((n) => caches.delete(n).catch(() => {})));
}

/** Called when the size setting changes: trims down to the new cap at once. */
export async function cacheTrim() {
  const ix = await load();
  if (ix) await trim(ix);
}
