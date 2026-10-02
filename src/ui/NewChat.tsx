import { useEffect, useState, type FormEvent } from "react";
import { EventType, type IPublicRoomsChunkRoom } from "matrix-js-sdk";
import { client, createChat, openDM } from "../matrix.ts";
import { aliasLocalpart, isUserId, num } from "../logic.ts";
import { Icon, type IconName } from "../icons.tsx";
import { Avatar, errText, me, Sheet } from "./common.tsx";

type Kind = "dm" | "group" | "space" | "join";
const MENU: [Kind, IconName, string][] = [
  ["dm", "user", "پیام جدید"],
  ["group", "group", "گروه جدید"],
  ["space", "space", "فضای جدید"],
  ["join", "link", "پیوستن به گفتگو"],
];
const TITLES = Object.fromEntries(MENU.map(([k, , t]) => [k, t])) as Record<Kind, string>;

/** Floating pencil button + menu; each item opens its own sheet. */
/** `onOpen(id, true)` = a space: shown as a folder, not opened as a chat. */
export function NewChat({ activeSpace, onOpen }: { activeSpace?: string; onOpen: (roomId: string, space: boolean) => void }) {
  const [menu, setMenu] = useState(false);
  const [kind, setKind] = useState<Kind | null>(null);
  const done = (id: string, space = kind === "space") => { setKind(null); onOpen(id, space); };
  return (
    <>
      {menu && (
        <div className="fab-backdrop" onClick={() => setMenu(false)} onKeyDown={(e) => e.key === "Escape" && setMenu(false)}>
          <div className="fab-menu" role="menu">
            {MENU.map(([k, icon, label]) => (
              <button key={k} role="menuitem" onClick={() => { setKind(k); setMenu(false); }}><Icon name={icon} /> {label}</button>
            ))}
          </div>
        </div>
      )}
      <button className={"fab" + (menu ? " open" : "")} onClick={() => setMenu((m) => !m)} aria-label="گفتگوی جدید" title="گفتگوی جدید">
        <Icon name={menu ? "close" : "pencil"} size={24} />
      </button>
      {kind && (
        <Sheet title={TITLES[kind]} onClose={() => setKind(null)}>
          {kind === "dm" ? <NewDM onDone={done} />
            : kind === "join" ? <JoinChat onDone={done} />
            : <ChatForm kind={kind} space={activeSpace} onDone={done} />}
        </Sheet>
      )}
    </>
  );
}

function NewDM({ onDone }: { onDone: (roomId: string) => void }) {
  const [busy, setBusy] = useState(false);
  const pick = (p: Person) => {
    setBusy(true);
    openDM(p.userId).then(onDone, (e) => { alert(errText(e)); setBusy(false); });
  };
  return busy ? <p className="muted">در حال ساخت گفتگو…</p> : <UserPicker onPick={pick} />;
}

// ---------- user search ----------

export type Person = { userId: string; name: string; avatar?: string };

/** Search the user directory; a full @user:server is also looked up directly, since some servers
 *  (Conduit) only list users you already share a room with. */
export function UserPicker({ onPick, exclude = [], autoFocus = true }: { onPick: (p: Person) => void; exclude?: string[]; autoFocus?: boolean }) {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<Person[] | null>(null);

  useEffect(() => {
    const term = q.trim();
    if (!term) { setResults(null); return; }
    let live = true;
    const t = setTimeout(async () => {
      const [dir, direct] = await Promise.all([
        client.searchUserDirectory({ term, limit: 20 }).then((r) => r.results, () => []),
        isUserId(term)
          ? client.getProfileInfo(term).then(
            (p) => ({ userId: term, name: p.displayname ?? term, avatar: p.avatar_url }),
            // unknown user = drop it; other failures (e.g. profile lookups disabled) still let you try the ID
            (e) => (e?.errcode === "M_NOT_FOUND" ? null : { userId: term, name: term }))
          : null,
      ]);
      const people: Person[] = dir.map((u) => ({ userId: u.user_id, name: u.display_name ?? u.user_id, avatar: u.avatar_url }));
      if (direct && !people.some((p) => p.userId === direct.userId)) people.unshift(direct);
      if (live) setResults(people);
    }, 250);
    return () => { live = false; clearTimeout(t); };
  }, [q]);

  const hide = new Set([me(), ...exclude]);
  const shown = results?.filter((p) => !hide.has(p.userId));
  return (
    <div className="picker">
      <label className="search">
        <Icon name="search" size={16} />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="نام یا شناسه، مثل ‎@ali:example.org" aria-label="جستجوی کاربر" autoFocus={autoFocus} />
      </label>
      {shown?.length === 0 && <p className="muted">کاربری پیدا نشد. شناسه‌ی کامل را وارد کنید.</p>}
      {shown?.map((p) => (
        <button key={p.userId} type="button" className="user-row" onClick={() => { onPick(p); setQ(""); }}>
          <Avatar mxc={p.avatar} name={p.name} id={p.userId} size={42} />
          <span><b>{p.name}</b><small dir="ltr">{p.userId}</small></span>
        </button>
      ))}
    </div>
  );
}

// ---------- new group / space ----------

export function ChatForm({ kind, space, onDone }: { kind: "group" | "space"; space?: string; onDone: (roomId: string) => void }) {
  const [name, setName] = useState("");
  const [topic, setTopic] = useState("");
  const [avatar, setAvatar] = useState<File | null>(null);
  const [invite, setInvite] = useState<Person[]>([]);
  const [isPublic, setPublic] = useState(false);
  const [alias, setAlias] = useState("");
  const [encrypted, setEncrypted] = useState(true);
  const [parent, setParent] = useState(space ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const preview = useObjectUrl(avatar);
  const spaces = client.getRooms().filter((r) => r.isSpaceRoom() && r.getMyMembership() === "join" &&
    r.currentState.maySendStateEvent(EventType.SpaceChild, me()));

  function togglePublic(on: boolean) {
    setPublic(on);
    setEncrypted(!on); // public rooms are readable by anyone who joins; encryption only slows them down
    if (on && !alias) setAlias(aliasLocalpart(name));
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      onDone(await createChat({
        kind, name: name.trim(), topic: topic.trim(), avatar, invite: invite.map((p) => p.userId),
        encrypted, alias: isPublic ? alias.trim() : undefined, parentSpace: parent || undefined,
      }));
    } catch (err) {
      setError((err as { errcode?: string }).errcode === "M_ROOM_IN_USE" ? "این نشانی قبلاً گرفته شده است" : errText(err));
      setBusy(false);
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      <div className="form-head">
        <label className="avatar-pick" title="انتخاب عکس">
          <input type="file" accept="image/*" hidden onChange={(e) => setAvatar(e.target.files?.[0] ?? null)} />
          {preview ? <img src={preview} alt="" /> : <Icon name={kind === "space" ? "space" : "group"} size={28} />}
        </label>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder={kind === "space" ? "نام فضا" : "نام گروه"} required autoFocus />
      </div>
      <input value={topic} onChange={(e) => setTopic(e.target.value)} placeholder="توضیحات (اختیاری)" />

      <label className="switch-row"><span>عمومی<small>هر کسی می‌تواند پیدا کند و بپیوندد</small></span>
        <input type="checkbox" role="switch" checked={isPublic} onChange={(e) => togglePublic(e.target.checked)} /></label>
      {isPublic && (
        <div className="alias-input" dir="ltr">
          <span>#</span>
          <input value={alias} onChange={(e) => setAlias(e.target.value.replace(/[\s:#]/g, ""))} placeholder="address" required />
          <span>:{client.getDomain()}</span>
        </div>
      )}
      {kind === "group" && (
        <label className="switch-row"><span>رمزنگاری سرتاسری<small>پس از روشن کردن، خاموش نمی‌شود</small></span>
          <input type="checkbox" role="switch" checked={encrypted} onChange={(e) => setEncrypted(e.target.checked)} /></label>
      )}
      {spaces.length > 0 && (
        <label className="select-row">داخل فضای
          <select value={parent} onChange={(e) => setParent(e.target.value)}>
            <option value="">هیچ‌کدام</option>
            {spaces.map((s) => <option key={s.roomId} value={s.roomId}>{s.name}</option>)}
          </select>
        </label>
      )}

      <h3>اعضا {invite.length > 0 && `(${num(invite.length)})`}</h3>
      {invite.length > 0 && (
        <div className="chips">
          {invite.map((p) => (
            <button key={p.userId} type="button" className="chip" onClick={() => setInvite(invite.filter((x) => x !== p))} title="حذف">
              <Avatar mxc={p.avatar} name={p.name} id={p.userId} size={24} /> {p.name} <Icon name="close" size={14} />
            </button>
          ))}
        </div>
      )}
      <UserPicker exclude={invite.map((p) => p.userId)} onPick={(p) => setInvite([...invite, p])} autoFocus={false} />

      {error && <p className="error">{error}</p>}
      <button className="primary" disabled={busy}>{busy ? "در حال ساخت…" : kind === "space" ? "ساخت فضا" : "ساخت گروه"}</button>
    </form>
  );
}

function useObjectUrl(file: File | null) {
  const [url, setUrl] = useState<string>();
  useEffect(() => {
    if (!file) { setUrl(undefined); return; }
    const u = URL.createObjectURL(file);
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [file]);
  return url;
}

// ---------- join by address / directory ----------

function JoinChat({ onDone }: { onDone: (roomId: string, space: boolean) => void }) {
  const [q, setQ] = useState("");
  const [rooms, setRooms] = useState<IPublicRoomsChunkRoom[] | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let live = true;
    const t = setTimeout(() => {
      const term = q.trim();
      client.publicRooms({ limit: 30, filter: term && !/^[#!]/.test(term) ? { generic_search_term: term } : undefined })
        .then((r) => live && setRooms(r.chunk), () => live && setRooms([]));
    }, 250);
    return () => { live = false; clearTimeout(t); };
  }, [q]);

  const join = (idOrAlias: string, space = false) => {
    setBusy(true);
    client.joinRoom(idOrAlias).then((r) => onDone(r.roomId, space || r.isSpaceRoom()), async (e) => {
      // knock-only (or invite-only) rooms refuse a join; asking to be let in is the only way
      if (e?.errcode === "M_FORBIDDEN" && confirm("پیوستن به این گفتگو نیاز به تأیید مدیران دارد. درخواست عضویت فرستاده شود؟")) {
        try {
          await client.knockRoom(idOrAlias);
          alert("درخواست فرستاده شد. اگر پذیرفته شود، دعوت‌نامه برایتان می‌آید.");
        } catch (e2) { alert((e2 as { errcode?: string }).errcode === "M_FORBIDDEN" ? "این گفتگو درخواست عضویت نمی‌پذیرد" : errText(e2)); }
      } else if (e?.errcode !== "M_FORBIDDEN") alert(errText(e));
      setBusy(false);
    });
  };
  const joined = (id: string) => client.getRoom(id)?.getMyMembership() === "join";
  const address = /^[#!]\S+:\S+$/.test(q.trim());

  return (
    <div className="picker">
      <form className="search" onSubmit={(e) => { e.preventDefault(); if (address) join(q.trim()); }}>
        <Icon name="search" size={16} />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="جستجو یا نشانی، مثل ‎#room:example.org" aria-label="جستجوی گفتگو" autoFocus />
      </form>
      {address && <button className="primary" disabled={busy} onClick={() => join(q.trim())}>پیوستن به <bdi dir="ltr">{q.trim()}</bdi></button>}
      {!address && rooms?.length === 0 && <p className="muted">گفتگوی عمومی‌ای پیدا نشد</p>}
      {!address && rooms?.map((r) => (
        <button key={r.room_id} className="user-row" disabled={busy} onClick={() => (joined(r.room_id) ? onDone(r.room_id, r.room_type === "m.space") : join(r.room_id, r.room_type === "m.space"))}>
          <Avatar mxc={r.avatar_url} name={r.name ?? r.canonical_alias ?? r.room_id} id={r.room_id} size={42} />
          <span>
            <b>{r.name ?? r.canonical_alias ?? r.room_id}</b>
            <small>{r.canonical_alias && <bdi dir="ltr">{r.canonical_alias}</bdi>} · {num(r.num_joined_members)} عضو</small>
          </span>
          <em>{joined(r.room_id) ? "باز کردن" : "پیوستن"}</em>
        </button>
      ))}
    </div>
  );
}
