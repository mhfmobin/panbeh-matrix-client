# Calls: server setup

Panbeh's voice and video calls are MatrixRTC calls, the same kind Element Call and Element X make. The homeserver only relays signalling. Audio and video go through a **LiveKit SFU**, which hands out access through **lk-jwt-service**. These steps are the same for Synapse and Conduit/Tuwunel/Continuwuity.

Calls interoperate with Element X and with Element Web/Desktop when Element Call is enabled. Legacy 1:1 `m.call.*` calls (Element Web's DM calls) are supported too, see [Legacy calls](#legacy-calls).

## 1. LiveKit

A `livekit.yaml` with LiveKit's built-in TURN enabled. With an SFU, every client only talks to LiveKit, so you don't need a separate coturn. TURN over TLS on 443 gets through networks that block UDP.

```yaml
port: 7880
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 60000
  use_external_ip: true
turn:
  enabled: true
  domain: turn.example.org      # its own name and certificate
  tls_port: 5349                # or 443 if nothing else uses it
  udp_port: 3478
  cert_file: /certs/turn.crt
  key_file: /certs/turn.key
keys:
  LK_KEY: LK_SECRET_AT_LEAST_32_CHARS
```

Open 7881/tcp, 50000-60000/udp, 3478/udp and 5349/tcp. Reverse-proxy `wss://livekit.example.org` to port 7880.

## 2. lk-jwt-service

```sh
docker run -d --name lk-jwt -p 8080:8080 \
  -e LIVEKIT_URL=wss://livekit.example.org \
  -e LIVEKIT_KEY=LK_KEY -e LIVEKIT_SECRET=LK_SECRET_AT_LEAST_32_CHARS \
  -e LIVEKIT_FULL_ACCESS_HOMESERVERS=example.org \
  ghcr.io/element-hq/lk-jwt-service:latest
```

Reverse-proxy `https://livekit-jwt.example.org` to it. Only users of the servers listed in `LIVEKIT_FULL_ACCESS_HOMESERVERS` can start calls on your SFU. Others can still join calls that are already running.

## 3. Tell clients where it is

In `https://example.org/.well-known/matrix/client`, served with `Access-Control-Allow-Origin: *`:

```json
{
  "m.homeserver": { "base_url": "https://matrix.example.org" },
  "org.matrix.msc4143.rtc_foci": [
    { "type": "livekit", "livekit_service_url": "https://livekit-jwt.example.org" }
  ]
}
```

Panbeh first asks the homeserver (`/rtc/transports`, newer Synapse) and then falls back to this file. Conduit-family servers can generate the well-known from their config, or nginx can serve the file.

## 4. Synapse only (recommended)

```yaml
# delayed events (MSC4140): a client that crashes mid-call drops out within seconds, not after the membership expires
max_event_delay_duration: 24h
experimental_features:
  msc4222_enabled: true
rc_message:
  per_second: 0.5
  burst_count: 30
rc_delayed_event_mgmt:
  per_second: 1
  burst_count: 20
```

Without delayed events (Conduit family), calls still work. A member whose app crashed simply shows as "in the call" until their membership expires.

## Rooms

Joining a call sends an `org.matrix.msc3401.call.member` state event, which needs permission:
- Groups created by Panbeh allow it for everyone (power 0).
- In older groups, an admin is asked once to enable calls.
- In DMs both people are admins, so nothing is needed.

## Legacy calls

Panbeh answers legacy 1:1 `m.call.invite` calls in DMs, and places them when your server has no SFU (no `/rtc/transports`, no `rtc_foci`). These calls are peer to peer, so they need a **TURN server** that your homeserver hands out through `/voip/turnServer`. LiveKit's built-in TURN does not do that. Without one, calls only connect on the same network or with open UDP.

Synapse (`homeserver.yaml`) with a coturn that uses `use-auth-secret`:

```yaml
turn_uris: ["turns:turn.example.org:443?transport=tcp", "turn:turn.example.org:3478?transport=udp"]
turn_shared_secret: "same as static-auth-secret in turnserver.conf"
turn_user_lifetime: 86400000
```

Panbeh never falls back to turn.matrix.org, since that would leak IP addresses. Check `https://your.server/_matrix/client/v3/voip/turnServer` (with an access token) returns credentials.
