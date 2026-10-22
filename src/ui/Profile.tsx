import { t } from "../i18n.ts";
import { useCallback, useMemo, useState } from "react";
import { ClientEvent, type Room } from "matrix-js-sdk";
import { client, findDM, openDM, setBlocked } from "../matrix.ts";
import { usePresence, usePromise, useTick } from "../hooks.ts";
import { Icon } from "../icons.tsx";
import { Avatar, errText, me, Sheet } from "./common.tsx";
import { MemberAdmin } from "./Admin.tsx";
import { identityChanged, showVerification, useUserTrust } from "./Verify.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";
import { start as startCall } from "./Call.tsx";
import { PhotoViewer } from "./Media.tsx";
import { matrixToLink } from "../uri.ts";
import { LinkSheet } from "./LinkSheet.tsx";

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
  const [qr, setQr] = useState(false);
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
    <Sheet title={t("profile.title")} onClose={onClose}>
      <div className="info-head">
        {photo ? (
          <button className="avatar-view" onClick={() => setViewing(true)} title={t("profile.viewPhoto")} aria-label={t("profile.viewPhoto")}><Avatar mxc={photo} name={name} id={userId} size={120} /></button>
        ) : <Avatar name={name} id={userId} size={120} />}
        {viewing && photo && <PhotoViewer mxc={photo} name={name} onClose={closeViewer} />}
        <div className="info-text"><h2 dir="auto">{name}</h2></div>
        <bdi dir="ltr" className="muted">{userId}</bdi>
        {userId !== me() && seen.text && <span className={seen.online ? "typing" : "muted"}>{seen.text}</span>}
        {other && trust?.known && (
          <span className={"trust" + (identityChanged(trust) ? " bad" : trust.isCrossSigningVerified() ? " ok" : "")}>
            <Icon name={identityChanged(trust) ? "shield" : "lock"} size={14} />
            {identityChanged(trust) ? t("profile.identityChanged") : trust.isCrossSigningVerified() ? t("profile.verified") : t("profile.unverified")}
          </span>
        )}
      </div>
      {other && <button className="primary" disabled={busy} onClick={open}>{dm ? t("profile.message") : t("profile.startChat")}</button>}
      {other && dmRoom && (
        <div className="profile-calls">
          <button className="secondary" disabled={busy} onClick={() => callIn(false)}><Icon name="phone" size={18} /> {t("call.voice")}</button>
          <button className="secondary" disabled={busy} onClick={() => callIn(true)}><Icon name="video" size={18} /> {t("call.video")}</button>
        </div>
      )}
      <button className="secondary" onClick={() => setQr(true)}><Icon name="link" size={18} /> {t("profile.linkQr")}</button>
      {qr && <LinkSheet title={t("profile.link")} link={matrixToLink(userId)} onClose={() => setQr(false)} />}
      {other && trust?.known && !trust.isCrossSigningVerified() && (
        <button className="secondary" disabled={busy} onClick={verify}>{t("profile.verify")}</button>
      )}
      {other && (
        <button className="danger" disabled={busy}
          onClick={async () => { if (blocked || await confirmDialog(t("profile.blockQ", { name }), { danger: true })) act(() => setBlocked(userId, !blocked)); }}>
          {blocked ? t("profile.unblock") : t("profile.block")}
        </button>
      )}
      {room && <MemberAdmin room={room} userId={userId} />}
    </Sheet>
  );
}
