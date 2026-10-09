package ir.panbeh.app;

import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import androidx.annotation.RequiresApi;
import com.getcapacitor.JSObject;

/**
 * Android 8+ (callers check the version): our calls as self-managed Telecom calls. A Bluetooth headset, car or watch can answer and hang up, and a phone call
 * puts ours on hold. Ringing stays our own notification (Notifier) and the call screen stays the page; this only tells the system.
 * Everything here is best effort: if Telecom refuses (a phone call that can't be held, an OEM quirk), calls work as before.
 */
@RequiresApi(26)
public class CallConnectionService extends ConnectionService {
    /** The one call Telecom knows about: ringing (from Notifier.showCall) or in progress (from PanbehPlugin.callActive). */
    static Call current;
    /** What the next connection Telecom asks us for is about: set just before addNewIncomingCall / placeCall. */
    private static String pendingRoom, pendingEvent, pendingName;
    private static boolean pendingVideo;

    static class Call extends Connection {
        final Context ctx;
        final String roomId, eventId;
        final boolean video;

        Call(Context ctx, String roomId, String eventId, String name, boolean video) {
            this.ctx = ctx.getApplicationContext();
            this.roomId = roomId;
            this.eventId = eventId == null ? "" : eventId;
            this.video = video;
            setConnectionProperties(PROPERTY_SELF_MANAGED);
            setConnectionCapabilities(CAPABILITY_HOLD | CAPABILITY_SUPPORT_HOLD | CAPABILITY_MUTE);
            setAudioModeIsVoip(true);
            setAddress(Uri.fromParts("panbeh", roomId, null), TelecomManager.PRESENTATION_ALLOWED);
            setCallerDisplayName(name == null || name.isEmpty() ? ctx.getString(R.string.app_name) : name, TelecomManager.PRESENTATION_ALLOWED);
            setVideoState(video ? VideoProfile.STATE_BIDIRECTIONAL : VideoProfile.STATE_AUDIO_ONLY); // video calls start on the speaker
        }

        private void send(String action) {
            CallReceiver.deliver(new JSObject().put("action", action).put("roomId", roomId).put("eventId", eventId).put("video", video));
        }

        /** Answered from a headset, car or watch: the page answers; the app is opened if it isn't running. */
        @Override
        public void onAnswer() {
            setActive();
            Notifier.cancelCall(ctx, roomId);
            JSObject a = new JSObject().put("action", "answer").put("roomId", roomId).put("eventId", eventId).put("video", false);
            // ponytail: from the background Android may refuse to open the app (no Telecom exemption); the ring notification stays then
            if (!PanbehPlugin.deliverCall(a)) {
                try {
                    ctx.startActivity(new Intent(ctx, MainActivity.class).setAction(Intent.ACTION_VIEW)
                        .putExtra(Notifier.EXTRA_CALL, "answer").putExtra(Notifier.EXTRA_ROOM, roomId).putExtra(Notifier.EXTRA_EVENT, eventId)
                        .putExtra(Notifier.EXTRA_VIDEO, false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                } catch (RuntimeException e) {
                    // not allowed from the background
                }
            }
            // the page never took it over (no mic, app didn't open): don't leave a phantom call behind
            new Handler(Looper.getMainLooper()).postDelayed(() -> { if (current == this && !PanbehPlugin.inCall()) end(this, DisconnectCause.LOCAL); }, 30_000);
        }

        @Override
        public void onReject() {
            Notifier.cancelCall(ctx, roomId);
            send("decline");
            end(this, DisconnectCause.REJECTED);
        }

        @Override
        public void onDisconnect() {
            send("hangup");
            end(this, DisconnectCause.LOCAL);
        }

        @Override
        public void onAbort() {
            onDisconnect();
        }

        @Override
        public void onHold() {
            setOnHold();
            send("hold");
        }

        @Override
        public void onUnhold() {
            setActive();
            send("unhold");
        }
    }

    private static void end(Call c, int cause) {
        if (c == null || c.getState() == Connection.STATE_DISCONNECTED) return;
        c.setDisconnected(new DisconnectCause(cause));
        c.destroy();
        if (current == c) current = null;
    }

    private static PhoneAccountHandle account(Context ctx) {
        return new PhoneAccountHandle(new ComponentName(ctx, CallConnectionService.class), "panbeh");
    }

    /** Registers our account (again: it's cheap and survives the user clearing it); null if Telecom won't have it. */
    private static TelecomManager telecom(Context ctx) {
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            tm.registerPhoneAccount(PhoneAccount.builder(account(ctx), ctx.getString(R.string.app_name))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED | PhoneAccount.CAPABILITY_VIDEO_CALLING | PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING)
                .build());
            return tm;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A ring was shown (Notifier.showCall): tell Telecom, so a headset button can answer it. */
    static void incoming(Context ctx, String roomId, String eventId, String caller, boolean video) {
        TelecomManager tm = telecom(ctx);
        if (tm == null || current != null || !tm.isIncomingCallPermitted(account(ctx))) return;
        pendingRoom = roomId;
        pendingEvent = eventId;
        pendingName = caller;
        pendingVideo = video;
        try {
            tm.addNewIncomingCall(account(ctx), new Bundle());
        } catch (RuntimeException e) {
            pendingRoom = null;
        }
    }

    /** The ring stopped (declined, missed, answered here or elsewhere). An answered Telecom call is no longer ringing and stays. */
    static void ringEnded(String roomId) {
        Call c = current;
        if (c != null && c.getState() == Connection.STATE_RINGING && c.roomId.equals(roomId)) end(c, DisconnectCause.CANCELED);
    }

    /** A call got under way on the page: take over the call answered from a headset, or tell Telecom about a new one. */
    static void started(Context ctx, String roomId, String name, boolean video) {
        Call c = current;
        if (c != null && c.roomId.equals(roomId) && c.getState() != Connection.STATE_RINGING) return;
        end(c, DisconnectCause.CANCELED); // a ring from elsewhere, or a leftover
        TelecomManager tm = telecom(ctx);
        if (tm == null || !tm.isOutgoingCallPermitted(account(ctx))) return;
        pendingRoom = roomId;
        pendingEvent = null;
        pendingName = name;
        pendingVideo = video;
        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account(ctx));
        extras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, video ? VideoProfile.STATE_BIDIRECTIONAL : VideoProfile.STATE_AUDIO_ONLY);
        try {
            tm.placeCall(Uri.fromParts("panbeh", roomId, null), extras);
        } catch (RuntimeException e) {
            pendingRoom = null;
        }
    }

    /** The page's call ended. */
    static void ended() {
        end(current, DisconnectCause.LOCAL);
    }

    /** Sends call audio to a device through Telecom, which owns routing during its calls. False when there's no Telecom call. */
    static boolean route(AudioDeviceInfo d) {
        Call c = current;
        if (c == null) return false;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: c.setAudioRoute(CallAudioState.ROUTE_SPEAKER); return true;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: c.setAudioRoute(CallAudioState.ROUTE_EARPIECE); return true;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET: c.setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET); return true;
            default: break;
        }
        // Bluetooth: that very headset when Telecom lists it (Android 9+), else whichever one Telecom picks
        CallAudioState s = c.getCallAudioState();
        if (Build.VERSION.SDK_INT >= 28 && s != null) {
            try {
                for (BluetoothDevice b : s.getSupportedBluetoothDevices()) {
                    if (b.getAddress().equalsIgnoreCase(d.getAddress())) { c.requestBluetoothAudio(b); return true; }
                }
            } catch (SecurityException e) {
                // no Bluetooth permission to read addresses: fall through
            }
        }
        c.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH);
        return true;
    }

    /** Speaker on/off through Telecom (the toggle when there's no headset list). False when there's no Telecom call. */
    static boolean speaker(boolean on) {
        Call c = current;
        if (c == null) return false;
        c.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
        return true;
    }

    private Connection create(boolean incoming) {
        if (pendingRoom == null) return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
        Call c = new Call(this, pendingRoom, pendingEvent, pendingName, pendingVideo);
        pendingRoom = null;
        if (incoming) c.setRinging();
        else c.setActive(); // the page is already connecting
        current = c;
        return c;
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle h, ConnectionRequest r) {
        return create(true);
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle h, ConnectionRequest r) {
        return create(false);
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle h, ConnectionRequest r) {
        pendingRoom = null;
    }

    @Override
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle h, ConnectionRequest r) {
        pendingRoom = null;
    }
}
