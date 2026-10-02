// Megolm key-export files (the format Element reads/writes). WebCrypto only, so `node --test` can run it.
// Body: 0x01 | salt(16) | iv(16) | rounds(4, BE) | AES-256-CTR ciphertext | HMAC-SHA256 of everything before it.

const HEADER = "-----BEGIN MEGOLM SESSION DATA-----";
const FOOTER = "-----END MEGOLM SESSION DATA-----";
const subtle = globalThis.crypto.subtle;

async function keys(pass: string, salt: Uint8Array<ArrayBuffer>, rounds: number) {
  const base = await subtle.importKey("raw", new TextEncoder().encode(pass), "PBKDF2", false, ["deriveBits"]);
  const bits = new Uint8Array(await subtle.deriveBits({ name: "PBKDF2", salt, iterations: rounds, hash: "SHA-512" }, base, 512));
  return Promise.all([
    subtle.importKey("raw", bits.slice(0, 32), "AES-CTR", false, ["encrypt", "decrypt"]),
    subtle.importKey("raw", bits.slice(32), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]),
  ]);
}

/** Encrypts exportRoomKeysAsJson() output into an armored key file. */
export async function encryptKeyFile(json: string, pass: string, rounds = 500_000): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(16));
  iv[8] &= 0x7f; // clear bit 63 of the counter so it can't wrap (as Element does)
  const [aes, hmac] = await keys(pass, salt, rounds);
  const ct = new Uint8Array(await subtle.encrypt({ name: "AES-CTR", counter: iv, length: 64 }, aes, new TextEncoder().encode(json)));
  const body = new Uint8Array(1 + 16 + 16 + 4 + ct.length + 32);
  body[0] = 1;
  body.set(salt, 1);
  body.set(iv, 17);
  new DataView(body.buffer).setUint32(33, rounds);
  body.set(ct, 37);
  body.set(new Uint8Array(await subtle.sign("HMAC", hmac, body.subarray(0, 37 + ct.length))), 37 + ct.length);
  const b64 = btoa(Array.from(body, (b) => String.fromCharCode(b)).join(""));
  return [HEADER, ...(b64.match(/.{1,96}/g) ?? []), FOOTER, ""].join("\n");
}

/** Decrypts a key file back to the JSON for importRoomKeysAsJson(). Throws on a wrong passphrase. */
export async function decryptKeyFile(text: string, pass: string): Promise<string> {
  const a = text.indexOf(HEADER), b = text.indexOf(FOOTER);
  if (a < 0 || b < a) throw new Error("این فایل، فایل کلید رمزنگاری نیست");
  const bin = atob(text.slice(a + HEADER.length, b).replace(/\s+/g, ""));
  const body = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  if (body[0] !== 1 || body.length < 1 + 16 + 16 + 4 + 32) throw new Error("نسخه‌ی فایل کلید پشتیبانی نمی‌شود");
  const end = body.length - 32;
  const [aes, hmac] = await keys(pass, body.slice(1, 17), new DataView(body.buffer).getUint32(33));
  if (!(await subtle.verify("HMAC", hmac, body.slice(end), body.subarray(0, end)))) throw new Error("عبارت عبور درست نیست");
  const pt = await subtle.decrypt({ name: "AES-CTR", counter: body.slice(17, 33), length: 64 }, aes, body.subarray(37, end));
  return new TextDecoder().decode(pt);
}
