/** Parsing of matrix.to links and `matrix:` URIs (MSC2312 / Matrix spec appendix). Pure: no SDK, no DOM. */
export type Target = {
  kind: "user" | "room" | "roomAlias";
  /** Full identifier with sigil: @user:server, !room:server or #alias:server. */
  id: string;
  eventId?: string;
  via: string[];
  action?: "join" | "chat";
};

const dec = (s: string) => { try { return decodeURIComponent(s); } catch { return null; } };
const USER = /^@[^:\s]+:\S+$/, ROOM = /^![^\s]+$/, ALIAS = /^#[^:\s]+:\S+$/, EVENT = /^\$\S+$/;

function build(id: string | null, eventId: string | null | undefined, query: string): Target | null {
  if (!id) return null;
  const kind = USER.test(id) ? "user" : ALIAS.test(id) ? "roomAlias" : ROOM.test(id) ? "room" : null;
  if (!kind) return null;
  if (eventId != null && (kind === "user" || !EVENT.test(eventId))) return null;
  const q = new URLSearchParams(query);
  const action = q.get("action");
  return {
    kind, id,
    ...(eventId ? { eventId } : {}),
    via: q.getAll("via").filter(Boolean),
    ...(action === "join" || action === "chat" ? { action } : {}),
  };
}

/** `https://matrix.to/#/<id>[/<$event>][?via=…]` */
function fromMatrixTo(s: string): Target | null {
  const m = /^https:\/\/matrix\.to\/#\/([^?]*)(?:\?(.*))?$/i.exec(s);
  if (!m) return null;
  const [a, b, ...rest] = m[1].split("/");
  if (rest.length) return null;
  return build(dec(a), b === undefined || b === "" ? undefined : dec(b), m[2] ?? "");
}

/** `matrix:u/user:server`, `matrix:r/alias:server[/e/event]`, `matrix:roomid/room:server[/e/event]` */
function fromMatrixUri(s: string): Target | null {
  const m = /^matrix:(?:\/\/[^/]*\/)?([^?#]*)(?:\?([^#]*))?(?:#.*)?$/i.exec(s);
  if (!m) return null;
  const parts = m[1].split("/");
  const sigil = { u: "@", r: "#", roomid: "!" }[parts[0].toLowerCase()];
  const name = parts[1] && dec(parts[1]);
  if (!sigil || !name) return null;
  let eventId: string | undefined;
  if (parts.length === 4 && parts[2] === "e" && parts[3]) {
    const e = dec(parts[3]);
    if (!e) return null;
    eventId = "$" + e;
  } else if (parts.length !== 2) return null;
  return build(sigil + name, eventId, m[2] ?? "");
}

export const parseMatrixLink = (s: string): Target | null => {
  const t = s.trim();
  return /^matrix:/i.test(t) ? fromMatrixUri(t) : fromMatrixTo(t);
};

/** The `matrix.to` hash form (`#/!room:server/$event`) of a page hash such as location.hash. */
export const parseMatrixHash = (hash: string): Target | null =>
  hash.startsWith("#/") ? fromMatrixTo("https://matrix.to/" + hash) : null;
