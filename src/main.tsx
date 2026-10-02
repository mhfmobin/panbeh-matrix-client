import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { ClientEvent, SyncState } from "matrix-js-sdk";
import { cancelAdd, client, finishOAuth, isAdding, isOAuthCallback, logout, recoveryState, savedSession, start } from "./matrix.ts";
import { useTick } from "./hooks.ts";
import { startNotifications } from "./notify.ts";
import { isHeadless, isNative, onOpenRoom, requestNotifyPermission, setBackgroundService } from "./native.ts";
import { startBackButton } from "./back.ts";
import { isMarkedUnread, setMarkedUnread } from "./chats.ts";
import { Login } from "./ui/Login.tsx";
import { Sidebar } from "./ui/Sidebar.tsx";
import { Room } from "./ui/Room.tsx";
import { NowPlaying } from "./ui/Voice.tsx";
import { Settings, applyPrefs, loadPrefs } from "./ui/Settings.tsx";
import { VerificationListener } from "./ui/Verify.tsx";
import { Icon } from "./icons.tsx";
import "@fontsource-variable/vazirmatn";
import "./styles.css";

applyPrefs();

function App() {
  const [phase, setPhase] = useState<"boot" | "login" | "ready" | "error">((savedSession() && !isAdding()) || isOAuthCallback() ? "boot" : "login");
  const [loginError, setLoginError] = useState<unknown>();
  useEffect(() => {
    const s = savedSession();
    // keep the session on failure: dropping it silently would orphan this device and its crypto store
    // adding an account: the saved one must not boot underneath the login screen
    if (isOAuthCallback()) {
      const before = s?.userId;
      finishOAuth().then(() => setPhase("ready"), (e) => {
      console.error(e);
      // active account changed = login worked, only startup failed
      if (savedSession()?.userId !== before) return setPhase("error");
      setLoginError(e);
      setPhase("login");
      });
    } else if (s && !isAdding()) start(s).then(() => setPhase("ready"), (e) => { console.error(e); setPhase("error"); });
  }, []);
  if (phase === "login") return <Login onDone={() => setPhase("ready")} initialError={loginError} onCancel={savedSession() ? cancelAdd : undefined} />;
  if (phase === "boot") return <Splash text="در حال راه‌اندازی…" />;
  if (phase === "error") return (
    <div className="splash wallpaper">
      <div className="login-logo">✦</div>
      راه‌اندازی ممکن نشد.
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
  const [security, setSecurity] = useState<string>("ok");
  const refreshSecurity = () => recoveryState().then(setSecurity, () => {});

  // phones: the chat slides out over ~0.3s on back, so keep rendering it until then
  const [leaving, setLeaving] = useState<string>();
  const lastRoom = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (roomId) { lastRoom.current = roomId; setLeaving(undefined); return; }
    if (!lastRoom.current || !matchMedia("(max-width: 700px)").matches) return;
    setLeaving(lastRoom.current);
    const t = setTimeout(() => setLeaving(undefined), 350);
    return () => clearTimeout(t);
  }, [roomId]);

  const sync = client.getSyncState();
  const synced = client.isInitialSyncComplete();
  useEffect(() => { if (synced) refreshSecurity(); }, [synced]);
  useEffect(startNotifications, []);
  useEffect(startBackButton, []);
  useEffect(() => onOpenRoom((id) => { location.hash = id; }), []);
  useEffect(() => { // Android: first start asks for notification permission, then keeps syncing in the background
    if (!isNative || !loadPrefs().notify) return;
    requestNotifyPermission().then((p) => setBackgroundService(p === "granted"), () => {});
  }, []);
  useEffect(() => { // opening a chat clears its "mark as unread"
    const r = roomId && synced ? client.getRoom(roomId) : null;
    if (r && isMarkedUnread(r)) setMarkedUnread(r, false).catch(() => {});
  }, [roomId, synced]);
  useEffect(() => {
    const onHash = () => setRoomId(location.hash.slice(1) || undefined);
    addEventListener("hashchange", onHash);
    return () => removeEventListener("hashchange", onHash);
  }, []);

  if (!synced) return <Splash text="در حال همگام‌سازی گفتگوها…" />;
  // left/declined/kicked rooms linger in the SDK; treat them as closed. Joined spaces are folders, not chats.
  const found = roomId || leaving ? client.getRoom((roomId ?? leaving)!) : null;
  const room = found && ["join", "invite"].includes(found.getMyMembership()) && !(found.isSpaceRoom() && found.getMyMembership() === "join") ? found : null;
  const open = (id?: string) => { location.hash = id ?? ""; };

  return (
    <div className={"app" + (room && roomId ? " room-open" : "")}>
      <Sidebar selected={roomId} onSelect={open} onSettings={() => setSettings(true)}
        banner={<>
          {(sync === SyncState.Error || sync === SyncState.Reconnecting) && <div className="banner warn">در حال اتصال…</div>}
          {security !== "ok" && (
            <button className="banner" onClick={() => setSettings(true)}>
              <Icon name="lock" size={16} /> {security === "unlock" ? "برای خواندن پیام‌های رمزنگاری‌شده‌ی قبلی، این دستگاه را تأیید کنید" : "بازیابی رمزنگاری را راه‌اندازی کنید"}
            </button>
          )}
          {!roomId && <NowPlaying />}
        </>} />
      <main className="main wallpaper">
        {room ? <Room key={room.roomId} room={room} onBack={() => open()} /> : <div className="pill center">برای شروع پیام‌رسانی یک گفتگو را انتخاب کنید</div>}
      </main>
      {settings && <Settings onClose={() => setSettings(false)} onSecurityChange={refreshSecurity} />}
      <VerificationListener onTrustChange={refreshSecurity} />
    </div>
  );
}

const Splash = ({ text }: { text: string }) => (
  <div className="splash wallpaper"><div className="login-logo">✦</div><span className="spinner inline" /> {text}</div>
);

/** The background service's copy of the app (Android, app closed): sync and notify, nothing on screen. */
function headless() {
  const s = savedSession();
  if (!s || !loadPrefs().notify) return setBackgroundService(false);
  start(s).then(startNotifications, (e) => console.error("headless start failed", e));
}

if (isHeadless) headless();
else createRoot(document.getElementById("root")!).render(<App />);
