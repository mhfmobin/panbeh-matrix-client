import { useMemo, useState } from "react";
import { ClientEvent, type Room } from "matrix-js-sdk";
import { client, findDM, openDM, setBlocked } from "../matrix.ts";
import { usePresence, usePromise, useTick } from "../hooks.ts";
import { Icon } from "../icons.tsx";
import { Avatar, errText, me, Sheet } from "./common.tsx";
import { MemberAdmin } from "./Admin.tsx";
import { showVerification, useUserTrust } from "./Verify.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";

/** Someone's profile with a shortcut to message them. */
export function UserProfile({ userId, room, onClose, onOpened }: { userId: string; room?: Room; onClose: () => void; onOpened?: () => void }) {
  useTick(client, [ClientEvent.AccountData]); // block list
  const [busy, setBusy] = useState(false);
  const m = room?.getMember(userId);
  const p = usePromise(useMemo(() => client.getProfileInfo(userId).catch(() => undefined), [userId])); // fresher than the room member, but may be missing
  const trust = useUserTrust(userId);
  const seen = usePresence(userId);
  const name = p?.displayname ?? m?.name ?? userId;
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
  return (
    <Sheet title="اطلاعات کاربر" onClose={onClose}>
      <div className="info-head">
        <Avatar mxc={p?.avatar_url ?? m?.getMxcAvatarUrl()} name={name} id={userId} size={120} />
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
