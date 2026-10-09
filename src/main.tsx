import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { ClientEvent, SyncState } from "matrix-js-sdk";
import { cancelAdd, client, finishOAuth, isAdding, isOAuthCallback, logout, recoveryState, savedSession, start } from "./matrix.ts";
import { useTick } from "./hooks.ts";
import { onNotifyButton, startNotifications } from "./notify.ts";
import { startSearchIndex } from "./searchIndex.ts";
import { isHeadless, isNative, onCallAction, onNotifyAction, onOpenLink, onOpenRoom, onShare, requestNotifyPermission, setBackgroundService } from "./native.ts";
import { onNativeCall, startCalls } from "./call.ts";
import { startBackButton } from "./back.ts";
import { drainLinks, handleIncomingLink } from "./openTarget.ts";
import { onDesktopLink } from "./desktop.ts";
import { parseMatrixHash } from "./uri.ts";
import { isMarkedUnread, setMarkedUnread } from "./chats.ts";
import { Login } from "./ui/Login.tsx";
import { Sidebar } from "./ui/Sidebar.tsx";
import { Room } from "./ui/Room.tsx";
import { ShareSheet } from "./ui/Forward.tsx";
import { shareInto } from "./ui/Composer.tsx";
import { NowPlaying } from "./ui/Voice.tsx";
import { Settings, applyPrefs, loadPrefs } from "./ui/Settings.tsx";
import { VerificationListener } from "./ui/Verify.tsx";
import { CallBar, CallLayer } from "./ui/Call.tsx";
import { alertDialog } from "./ui/dialog.tsx";
import { errText } from "./ui/common.tsx";
import { Icon } from "./icons.tsx";
import { LockScreen } from "./ui/Lock.tsx";
import { useLocked } from "./lock.ts";
import "@fontsource-variable/vazirmatn";
import "./styles.css";

applyPrefs();
// web: let the browser offer Panbeh for matrix: links; the hash form is picked up by Shell (see parseMatrixHash)
try { navigator.registerProtocolHandler?.("matrix", location.origin + location.pathname + "#/?uri=%s"); } catch { /* unsupported or not secure */ }

function App() {
  const [phase, setPhase] = useState<"boot" | "login" | "ready" | "error">((savedSession() && !isAdding()) || isOAuthCallback() ? "boot" : "login");
  const [loginError, setLoginError] = useState<unknown>();
  const [bootError, setBootError] = useState<unknown>();
  useEffect(() => {
    const s = savedSession();
    // keep the session on failure: dropping it silently would orphan this device and its crypto store
    // adding an account: the saved one must not boot underneath the login screen
    if (isOAuthCallback()) {
      const before = s?.userId;
      finishOAuth().then(() => setPhase("ready"), (e) => {
      console.error(e);
      // active account changed = login worked, only startup failed
      if (savedSession()?.userId !== before) { setBootError(e); return setPhase("error"); }
      setLoginError(e);
      setPhase("login");
      });
    } else if (s && !isAdding()) start(s).then(() => setPhase("ready"), (e) => { console.error(e); setBootError(e); setPhase("error"); });
  }, []);
  if (phase === "login") return <Login onDone={() => setPhase("ready")} initialError={loginError} onCancel={savedSession() ? cancelAdd : undefined} />;
  if (phase === "boot") return <Splash text="در حال راه‌اندازی…" />;
  if (phase === "error") return (
    <div className="splash wallpaper">
      <div className="login-logo">✦</div>
      راه‌اندازی ممکن نشد.
      {bootError !== undefined && <small className="muted boot-error" dir="auto">{errText(bootError)}</small>}
      <button className="primary" onClick={() => location.reload()}>تلاش دوباره</button>
      <button className="danger" onClick={() => logout()}>خروج</button>
    </div>
  );
  return <Shell />;
}

function Shell() {
  useTick(client, [ClientEvent.Sync]);
  const [roomId, setRoomId] = useState<string | undefined>(() => location.hash.slice(1) || undefined);
  const [settings, setSettings] = useState(false);
  const [share, setShare] = useState<{ text: string; files: File[] } | null>(null);
  const [shareN, setShareN] = useState(0); // remounts an already-open chat so it picks up what was shared
  const [security, setSecurity] = useState<string>("ok");
  const refreshSecurity = () => recoveryState().then(setSecurity, () => {});

  const sync = client.getSyncState();
  const synced = client.isInitialSyncComplete();
  useEffect(() => { if (synced) refreshSecurity(); }, [synced]);
  useEffect(startNotifications, []);
  useEffect(startSearchIndex, []);
  useEffect(startCalls, []);
  // Android call notification buttons: answering may have cold-started the app, so wait for the rooms
  useEffect(() => (synced ? onCallAction((a) => void onNativeCall(a).catch((e) => alertDialog(errText(e)))) : undefined), [synced]);
  useEffect(startBackButton, []);
  useEffect(() => onOpenRoom((id) => { location.hash = id; }), []);
  useEffect(() => onNotifyAction((a) => void onNotifyButton(a)), []);
  useEffect(() => { // links from outside (Android intents, desktop protocol handler, web handler) wait here until the first sync
    const off = [onOpenLink(handleIncomingLink), onDesktopLink(handleIncomingLink)];
    return () => off.forEach((f) => f());
  }, []);
  useEffect(() => (synced ? drainLinks() : undefined), [synced]);
  useEffect(() => onShare((text, files) => setShare({ text, files })), []);
  useEffect(() => { // Android: first start asks for notification permission, then keeps syncing in the background
    if (!isNative || !loadPrefs().notify) return;
    requestNotifyPermission().then((p) => setBackgroundService(p === "granted"), () => {});
  }, []);
  useEffect(() => { // opening a chat clears its "mark as unread"
    const r = roomId && synced ? client.getRoom(roomId) : null;
    if (r && isMarkedUnread(r)) setMarkedUnread(r, false).catch(() => {});
  }, [roomId, synced]);
  useEffect(() => {
    const onHash = () => {
      // a pasted matrix.to-style hash (#/!room:server/$event) is a link, not a room id
      if (parseMatrixHash(location.hash)) { handleIncomingLink(location.hash); history.replaceState(null, "", location.pathname + location.search); return; }
      setRoomId(location.hash.slice(1) || undefined);
    };
    addEventListener("hashchange", onHash);
    return () => removeEventListener("hashchange", onHash);
  }, []);

  // left/declined/kicked rooms linger in the SDK; treat them as closed. Joined spaces are folders, not chats.
  const found = roomId && synced ? client.getRoom(roomId) : null;
  const room = found && ["join", "invite"].includes(found.getMyMembership()) && !(found.isSpaceRoom() && found.getMyMembership() === "join") ? found : null;
  // phones slide the chat away on back: keep it mounted until the slide ends instead of flashing the placeholder
  const lastRoom = useRef(room);
  const [leaving, setLeaving] = useState(false);
  useEffect(() => {
    if (room) { lastRoom.current = room; setLeaving(false); return; }
    if (!lastRoom.current || !matchMedia("(max-width: 700px)").matches) { lastRoom.current = null; return; }
    setLeaving(true);
    const t = setTimeout(() => { lastRoom.current = null; setLeaving(false); }, 350);
    return () => clearTimeout(t);
  }, [room]);
  const shown = room ?? (leaving ? lastRoom.current : null);
  const locked = useLocked();
  const open = (id?: string) => { location.hash = id ?? ""; };

  return (
    <>
    <div className={"app" + (room ? " room-open" : "")} inert={locked}>
      <Sidebar loading={!synced} selected={roomId} onSelect={open} onSettings={() => setSettings(true)}
        banner={<>
          {(sync === SyncState.Error || sync === SyncState.Reconnecting) && <div className="banner warn">در حال اتصال…</div>}
          {security !== "ok" && (
            <button className="banner" onClick={() => setSettings(true)}>
              <Icon name="lock" size={16} /> {security === "unlock" ? "برای خواندن پیام‌های رمزنگاری‌شده‌ی قبلی، این دستگاه را تأیید کنید" : "بازیابی رمزنگاری را راه‌اندازی کنید"}
            </button>
          )}
          {!room && <NowPlaying />}
          {!room && <CallBar />}
        </>} />
      <main className="main wallpaper" inert={!room && !!shown}>
        {shown ? <Room key={shown.roomId + ":" + shareN} room={shown} onBack={() => open()} /> : <div className="pill center">برای شروع پیام‌رسانی یک گفتگو را انتخاب کنید</div>}
      </main>
      {share && <ShareSheet onClose={() => setShare(null)} onPick={(id) => { shareInto(id, share.text, share.files); setShare(null); setShareN((n) => n + 1); open(id); }} />}
      {settings && <Settings onClose={() => setSettings(false)} onSecurityChange={refreshSecurity} />}
      <VerificationListener onTrustChange={refreshSecurity} />
      <CallLayer />
    </div>
    {locked && <LockScreen />}
    </>
  );
}

const Splash = ({ text }: { text: string }) => (
  <div className="splash wallpaper">
    <div className="login-logo">✦</div><span className="spinner inline" /> {text}
  </div>
);

/** The background service's copy of the app (Android, app closed): sync and notify, nothing on screen. */
function headless() {
  const s = savedSession();
  if (!s || !loadPrefs().notify) return setBackgroundService(false);
  onNotifyAction((a) => void onNotifyButton(a)); // before start: a button may be what woke the service
  start(s).then(() => { startNotifications(); startSearchIndex(); startCalls(); onCallAction((a) => void onNativeCall(a)); }, (e) => console.error("headless start failed", e));
}

if (isHeadless) headless();
else createRoot(document.getElementById("root")!).render(<App />);
