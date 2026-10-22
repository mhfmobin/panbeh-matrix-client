import { lang, t } from "../i18n.ts";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { renderSVG } from "uqr";
import { RoomStateEvent, type Room } from "matrix-js-sdk";
import { CryptoEvent, VerificationPhase, VerificationRequestEvent, VerifierEvent, type ShowQrCodeCallbacks, type ShowSasCallbacks, type UserVerificationStatus, type VerificationRequest } from "matrix-js-sdk/lib/crypto-api/index.js";
import { applyIsolation, client } from "../matrix.ts";
import { useTick } from "../hooks.ts";
import { num } from "../logic.ts";
import { Icon } from "../icons.tsx";
import { bdi, errText, Sheet } from "./common.tsx";
import { alertDialog, confirmDialog } from "./dialog.tsx";
import { resendAll } from "../sendQueue.ts";
import { QrScan } from "./QrScan.tsx";

// ---------- the one verification on screen (incoming or started from Settings/Profile) ----------
let current: VerificationRequest | null = null;
const subs = new Set<() => void>();
export const showVerification = (r: VerificationRequest | null) => { current = r; subs.forEach((f) => f()); };
const useCurrent = () => useSyncExternalStore((f) => (subs.add(f), () => void subs.delete(f)), () => current);
const finished = (r: VerificationRequest) => r.phase === VerificationPhase.Done || r.phase === VerificationPhase.Cancelled;

/** Shows incoming verification requests; `onTrustChange` re-checks the recovery banner (secrets arrive by gossip after self-verification). */
export function VerificationListener({ onTrustChange }: { onTrustChange: () => void }) {
  const req = useCurrent();
  const trustChanged = useRef(onTrustChange);
  trustChanged.current = onTrustChange; // a new function every Shell render; don't resubscribe for it
  useEffect(() => {
    // one at a time: a second request while one is in progress is left to time out
    const onRequest = (r: VerificationRequest) => { if (!current || finished(current)) showVerification(r); };
    const onTrust = () => trustChanged.current();
    client.on(CryptoEvent.VerificationRequestReceived, onRequest);
    client.on(CryptoEvent.KeyBackupDecryptionKeyCached, onTrust);
    client.on(CryptoEvent.UserTrustStatusChanged, onTrust);
    return () => {
      client.off(CryptoEvent.VerificationRequestReceived, onRequest);
      client.off(CryptoEvent.KeyBackupDecryptionKeyCached, onTrust);
      client.off(CryptoEvent.UserTrustStatusChanged, onTrust);
    };
  }, []);
  return req ? <VerificationSheet key={req.transactionId ?? ""} req={req} /> : null;
}

// ---------- trust changes: one client listener for every shield and banner on screen ----------
let trustVer = 0;
const trustSubs = new Set<() => void>();
const onTrust = () => { trustVer++; trustSubs.forEach((f) => f()); };
const TRUST_EVENTS = [CryptoEvent.UserTrustStatusChanged, CryptoEvent.DevicesUpdated] as const;
/** A number that changes whenever someone's identity or devices do: a dependency for re-checking trust. */
export const useTrustVersion = () => useSyncExternalStore((f) => {
  if (!trustSubs.size) TRUST_EVENTS.forEach((e) => client.on(e, onTrust));
  trustSubs.add(f);
  return () => { trustSubs.delete(f); if (!trustSubs.size) TRUST_EVENTS.forEach((e) => client.off(e, onTrust)); };
}, () => trustVer);

/** Cross-signing trust of a user, refreshed when it changes; undefined while loading. */
export function useUserTrust(userId: string) {
  const [s, setS] = useState<UserVerificationStatus>();
  const ver = useTrustVersion();
  useEffect(() => { client.getCrypto()?.getUserVerificationStatus(userId).then(setS, () => {}); }, [userId, ver]);
  return s;
}

/** Their identity changed since we first saw it (pinning), or since we verified them: worth stopping for. */
export const identityChanged = (s?: UserVerificationStatus) => !!s && (s.needsUserApproval || (s.wasCrossSigningVerified() && !s.isCrossSigningVerified()));

/** Someone in an encrypted chat has a new identity: could be a new phone, could be someone (or the server) posing as them.
 *  Says so until it's accepted or verified. A verified contact's change pauses sending until then (the SDK refuses). */
export function IdentityBanner({ room, onVerify }: { room: Room; onVerify: (userId: string) => void }) {
  const ver = useTrustVersion();
  useTick(room, [RoomStateEvent.Members]);
  const ids = room.hasEncryptionStateEvent() ? [...room.getMembersWithMembership("join"), ...room.getMembersWithMembership("invite")].map((m) => m.userId).filter((id) => id !== client.getUserId()) : [];
  const key = ids.join();
  const [who, setWho] = useState<{ id: string; verified: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let live = true;
    const crypto = client.getCrypto();
    // ponytail: one status call per member, in turn; batch or cap if very large encrypted rooms get slow
    void (async () => {
      for (const id of crypto ? ids : []) {
        const s = await crypto!.getUserVerificationStatus(id).catch(() => undefined);
        if (!live) return;
        if (identityChanged(s)) return setWho({ id, verified: s!.wasCrossSigningVerified() });
      }
      if (live) setWho(null);
    })();
    return () => { live = false; };
  }, [room, key, ver]);
  if (!who) return null;
  const name = room.getMember(who.id)?.name ?? who.id;
  // no emoji check possible (they're not around): take the new identity as theirs, unverified, so chatting goes on
  const accept = async () => {
    if (who.verified && !await confirmDialog(t("trust.acceptQ", { name }), { danger: true, ok: t("trust.accept") })) return;
    setBusy(true);
    const crypto = client.getCrypto()!;
    (who.verified ? crypto.withdrawVerificationRequirement(who.id) : crypto.pinCurrentUserIdentity(who.id))
      .then(() => { applyIsolation(); resendAll(); }, (e) => alertDialog(errText(e))).finally(() => setBusy(false)); // applyIsolation: their hidden messages show
  };
  return (
    <div className={"banner identity" + (who.verified ? " danger" : " warn")} role="alert">
      <Icon name="shield" size={16} />
      <span>{who.verified ? t("trust.violation", { name }) : t("trust.reset", { name })}</span>
      <button disabled={busy} onClick={() => onVerify(who.id)}>{t("trust.verify")}</button>
      <button disabled={busy} onClick={accept}>{who.verified ? t("trust.accept") : t("common.ok")}</button>
    </div>
  );
}

const started = new WeakSet<object>(); // verify() once per verifier

/** req.scanQRCode, around an SDK bug: we show our own code and scan theirs on one request (Element never does both), and the
 *  SDK keeps the verifier of the code we show, so the request stays "ready" here though the scan went out and the other side
 *  asks to confirm. With no verifier, its change hook adopts the scan's.
 *  ponytail: matrix-js-sdk privates (_verifier, onChange); drop when the SDK swaps QR verifiers itself (checked on 43.0.0) */
async function scanCode(req: VerificationRequest, bytes: Uint8ClampedArray) {
  await req.scanQRCode(bytes);
  const r = req as unknown as { _verifier?: unknown; onChange?: () => void };
  if (req.phase === VerificationPhase.Ready && typeof r.onChange === "function") {
    r._verifier = undefined;
    r.onChange();
  }
}

function VerificationSheet({ req }: { req: VerificationRequest }) {
  useTick(req, [VerificationRequestEvent.Change]);
  const [sas, setSas] = useState<ShowSasCallbacks | null>(null);
  const [reciprocate, setReciprocate] = useState<ShowQrCodeCallbacks | null>(null); // they scanned our code: did it work there?
  const [qr, setQr] = useState<string | null>(null); // our code, as SVG
  const [scanning, setScanning] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [scanned, setScanned] = useState(false); // we scanned their code: they confirm it there
  const [error, setError] = useState("");
  const verifier = req.verifier;
  const run = (fn: () => Promise<unknown>) => fn().catch((e) => setError(errText(e)));

  useEffect(() => {
    if (!verifier) return;
    setSas(verifier.getShowSasCallbacks());
    setReciprocate(verifier.getReciprocateQrCodeCallbacks());
    verifier.on(VerifierEvent.ShowSas, setSas);
    verifier.on(VerifierEvent.ShowReciprocateQr, setReciprocate);
    if (!started.has(verifier)) {
      started.add(verifier);
      verifier.verify().catch(() => {}); // cancellation shows up as the request's phase
    }
    return () => { verifier.off(VerifierEvent.ShowSas, setSas); verifier.off(VerifierEvent.ShowReciprocateQr, setReciprocate); };
  }, [verifier]);

  // ready: show our code if they can scan it. Without QR on either side, we asked: start the emoji comparison at once
  const canScan = req.phase === VerificationPhase.Ready && req.otherPartySupportsMethod("m.qr_code.show.v1");
  const autoStarted = useRef(false);
  useEffect(() => {
    if (req.phase !== VerificationPhase.Ready || req.verifier) return;
    if (req.otherPartySupportsMethod("m.qr_code.scan.v1")) {
      req.generateQRCode().then((b) => b && setQr(renderSVG([...b], { border: 2 })), () => {});
    } else if (req.initiatedByMe && !req.otherPartySupportsMethod("m.qr_code.show.v1") && !autoStarted.current) {
      autoStarted.current = true;
      run(() => req.startVerification("m.sas.v1"));
    }
  }, [req.phase]);

  const close = () => {
    if (!finished(req)) req.cancel().catch(() => {});
    showVerification(null);
  };
  const self = req.isSelfVerification;
  const other = self ? t("verify.otherDevice") : bdi(client.getUser(req.otherUserId)?.displayName ?? req.otherUserId);

  let body;
  switch (req.phase) {
    case VerificationPhase.Unsent:
    case VerificationPhase.Requested:
      body = req.initiatedByMe ? (
        <>
          <p>{self ? t("verify.openOther") : t("verify.waitAccept", { other })}</p>
          <span className="spinner inline" />
        </>
      ) : (
        <>
          <p>{self ? t("verify.incomingSelf") : t("verify.incoming", { other })}</p>
          <div className="verify-actions">
            <button onClick={close}>{t("invite.decline")}</button>
            <button className="primary" disabled={req.accepting} onClick={() => run(() => req.accept())}>{t("verify.accept")}</button>
          </div>
        </>
      );
      break;
    case VerificationPhase.Ready:
      body = scanning ? (
        <QrScan onClose={() => setScanning(false)} onCode={(b) => { setScanning(false); setScanned(true); run(() => scanCode(req, b)); }} />
      ) : qr || canScan ? (
        <>
          {qr && <>
            <p>{t("verify.scanThis", { other })}</p>
            <div className="qr" role="img" aria-label={t("verify.qr")} dangerouslySetInnerHTML={{ __html: qr }} />
          </>}
          <div className="verify-methods">
            {canScan && <button className={qr ? "" : "primary"} onClick={() => setScanning(true)}><Icon name="camera" size={18} /> {t("verify.scanTheirs", { other })}</button>}
            <button onClick={() => run(() => req.startVerification("m.sas.v1"))}><Icon name="smile" size={18} /> {t("verify.byEmoji")}</button>
          </div>
        </>
      ) : req.initiatedByMe ? <span className="spinner inline" /> : (
        <>
          <p>{t("verify.accepted")}</p>
          <div className="verify-methods">
            <button className="primary" onClick={() => run(() => req.startVerification("m.sas.v1"))}><Icon name="smile" size={18} /> {t("verify.byEmoji")}</button>
          </div>
        </>
      );
      break;
    case VerificationPhase.Started:
      body = reciprocate && !sas ? (confirmed ? <span className="spinner inline" /> : (
        <>
          <p>{t("verify.scannedQ", { other: self ? t("verify.otherDeviceCap") : other })}</p>
          <div className="verify-actions">
            <button onClick={() => reciprocate.cancel()}>{t("common.no")}</button>
            <button className="primary" onClick={() => { setConfirmed(true); reciprocate.confirm(); }}>{t("common.yes")}</button>
          </div>
        </>
      )) : !sas && scanned ? (
        <>
          <p className="card ok"><Icon name="check" size={18} /> {t("verify.scanned")}</p>
          <p>{t("verify.pressYes", { other })}</p>
          <span className="spinner inline" />
        </>
      ) : !sas ? <><p>{t("verify.waiting", { other })}</p><span className="spinner inline" /></> : confirmed ? (
        <><p>{t("verify.waitingConfirm", { other })}</p><span className="spinner inline" /></>
      ) : (
        <>
          <p>{t("verify.compare", { other })}</p>
          {sas.sas.emoji ? (
            <div className="sas-emoji" dir="ltr">
              {sas.sas.emoji.map(([e, name], i) => <div key={i}><span>{e}</span><small>{(lang === "fa" && EMOJI_FA[name.toLowerCase()]) || name}</small></div>)}
            </div>
          ) : <p className="sas-decimal" dir="ltr">{sas.sas.decimal?.map((n) => num(n)).join("  ")}</p>}
          <div className="verify-actions">
            <button onClick={() => sas.mismatch()}>{t("verify.noMatch")}</button>
            <button className="primary" onClick={() => { setConfirmed(true); run(() => sas.confirm()); }}>{t("verify.match")}</button>
          </div>
        </>
      );
      break;
    case VerificationPhase.Done:
      body = (
        <>
          <p className="card ok"><Icon name="check" size={18} /> {self ? t("verify.doneSelf") : t("verify.done", { other })}</p>
          <button className="primary" onClick={close}>{t("common.close")}</button>
        </>
      );
      break;
    default:
      body = (
        <>
          <p className="error">{CANCEL_REASONS[req.cancellationCode ?? ""] ?? t("verify.cancelled")}</p>
          <button className="primary" onClick={close}>{t("common.close")}</button>
        </>
      );
  }

  return (
    <Sheet title={self ? t("verify.titleSelf") : t("verify.title")} onClose={close}>
      <div className="verify">
        {body}
        {error && <p className="error">{error}</p>}
      </div>
    </Sheet>
  );
}

const CANCEL_REASONS: Record<string, string> = {
  "m.mismatched_sas": t("verify.mismatch"),
  "m.user": t("verify.cancelled"),
  "m.timeout": t("verify.timeout"),
  "m.accepted": t("verify.elsewhere"),
};

// Persian SAS emoji names (spec order), keyed by the SDK's English name
const EMOJI_FA: Record<string, string> = {
  dog: "سگ", cat: "گربه", lion: "شیر", horse: "اسب", unicorn: "تک‌شاخ", pig: "خوک", elephant: "فیل", rabbit: "خرگوش",
  panda: "پاندا", rooster: "خروس", penguin: "پنگوئن", turtle: "لاک‌پشت", fish: "ماهی", octopus: "هشت‌پا", butterfly: "پروانه", flower: "گل",
  tree: "درخت", cactus: "کاکتوس", mushroom: "قارچ", globe: "کره‌ی زمین", moon: "ماه", cloud: "ابر", fire: "آتش", banana: "موز",
  apple: "سیب", strawberry: "توت‌فرنگی", corn: "ذرت", pizza: "پیتزا", cake: "کیک", heart: "قلب", smiley: "لبخند", robot: "ربات",
  hat: "کلاه", glasses: "عینک", spanner: "آچار", santa: "بابانوئل", "thumbs up": "لایک", umbrella: "چتر", hourglass: "ساعت شنی", clock: "ساعت",
  gift: "هدیه", "light bulb": "لامپ", book: "کتاب", pencil: "مداد", paperclip: "گیره", scissors: "قیچی", lock: "قفل", key: "کلید",
  hammer: "چکش", telephone: "تلفن", flag: "پرچم", train: "قطار", bicycle: "دوچرخه", aeroplane: "هواپیما", rocket: "موشک", trophy: "جام",
  ball: "توپ", guitar: "گیتار", trumpet: "شیپور", bell: "زنگ", anchor: "لنگر", headphones: "هدفون", folder: "پوشه", pin: "سنجاق",
};
