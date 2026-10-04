import { useCallback, useMemo, useState } from "react";
import { ClientEvent, type Room } from "matrix-js-sdk";
import { client, findDM, openDM, setBlocked } from "../matrix.ts";
import { usePresence, usePromise, useTick } from "../hooks.ts";
import { Icon } from "../icons.tsx";
import { Avatar, errText, me, Sheet } from "./common.tsx";
import { MemberAdmin } from "./Admin.tsx";
import { showVerification, useUserTrust } from "./Verify.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";
import { start as startCall } from "./Call.tsx";
import { PhotoViewer } from "./Media.tsx";

/** Someone's profile with a shortcut to message them. */
export function UserProfile({ userId, room, onClose, onOpened }: { userId: string; room?: Room; onClose: () => void; onOpened?: () => void }) {
  useTick(client, [ClientEvent.AccountData]); // block list
  const [busy, setBusy] = useState(false);
  const m = room?.getMember(userId);
  const p = usePromise(useMemo(() => client.getProfileInfo(userId).catch(() => undefined), [userId])); // fresher than the room member, but may be missing
  const trust = useUserTrust(userId);
  const seen = usePresence(userId);
  const name = p?.displayname ?? m?.name ?? userId;
  const photo = p?.avatar_url ?? m?.getMxcAvatarUrl();
  const [viewing, setViewing] = useState(false);
  const closeViewer = useCallback(() => setViewing(false), []);
  const dm = findDM(userId);
  const blocked = client.isUserIgnored(userId);
  const act = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    try { await fn(); } catch (e) { alertDialog(errText(e)); }
    setBusy(false);
  };
  const open = async () => {
    setBusy(true);
    try {
      location.hash = dm ?? await openDM(userId);
      onClose();
      onOpened?.();
    } catch (e) {
      alertDialog(errText(e));
      setBusy(false);
    }
  };
  const verify = () => act(async () => showVerification(await client.getCrypto()!.requestVerificationDM(userId, dm ?? await openDM(userId))));
  const other = userId !== me();
  const dmRoom = dm ? client.getRoom(dm) : null; // calls only in a DM they've joined: an invite can't ring
  const callIn = (video: boolean) => { location.hash = dmRoom!.roomId; onClose(); onOpened?.(); startCall(dmRoom!, video).catch((e) => alertDialog(errText(e))); };
  return (
    <Sheet title="اطلاعات کاربر" onClose={onClose}>
      <div className="info-head">
        {photo ? (
          <button className="avatar-view" onClick={() => setViewing(true)} title="نمایش عکس" aria-label="نمایش عکس"><Avatar mxc={photo} name={name} id={userId} size={120} /></button>
        ) : <Avatar name={name} id={userId} size={120} />}
        {viewing && photo && <PhotoViewer mxc={photo} name={name} onClose={closeViewer} />}
        <div className="info-text"><h2 dir="auto">{name}</h2></div>
        <bdi dir="ltr" className="muted">{userId}</bdi>
        {userId !== me() && seen.text && <span className={seen.online ? "typing" : "muted"}>{seen.text}</span>}
        {other && trust?.known && (
          <span className={"trust" + (trust.isCrossSigningVerified() ? " ok" : "")}>
            <Icon name="lock" size={14} /> {trust.isCrossSigningVerified() ? "هویت تأییدشده" : "هویت تأییدنشده"}
          </span>
        )}
      </div>
      {other && <button className="primary" disabled={busy} onClick={open}>{dm ? "پیام" : "شروع گفتگو"}</button>}
      {other && dmRoom && (
        <div className="profile-calls">
          <button className="secondary" disabled={busy} onClick={() => callIn(false)}><Icon name="phone" size={18} /> تماس صوتی</button>
          <button className="secondary" disabled={busy} onClick={() => callIn(true)}><Icon name="video" size={18} /> تماس تصویری</button>
        </div>
      )}
      {other && trust?.known && !trust.isCrossSigningVerified() && (
        <button className="secondary" disabled={busy} onClick={verify}>تأیید هویت با شکلک‌ها</button>
      )}
      {other && (
        <button className="danger" disabled={busy}
          onClick={async () => { if (blocked || await confirmDialog(`${name} مسدود شود؟ دیگر پیام و دعوتی از او نمی‌بینید.`, { danger: true })) act(() => setBlocked(userId, !blocked)); }}>
          {blocked ? "رفع مسدودیت" : "مسدود کردن"}
        </button>
      )}
      {room && <MemberAdmin room={room} userId={userId} />}
    </Sheet>
  );
}
