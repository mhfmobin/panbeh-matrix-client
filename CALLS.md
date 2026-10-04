# Calls: server setup

Panbeh's voice and video calls are MatrixRTC calls, the same kind Element Call and Element X make. The homeserver only relays signalling. Audio and video go through a **LiveKit SFU**, which hands out access through **lk-jwt-service**. These steps are the same for Synapse and Conduit/Tuwunel/Continuwuity.

Calls interoperate with Element X and with Element Web/Desktop when Element Call is enabled.

Legacy 1:1 `m.call.*` calls (FluffyChat, Nheko, SchildiChat, Element without Element Call) are off by default: Panbeh always calls with MatrixRTC, and an incoming legacy call only shows a notification saying it isn't supported. Turn on Settings › Developer options › «دریافت تماس‌های قدیمی» to ring and answer them; with developer options on, right-click or long-press the call button in a DM to place one. Those calls are peer to peer and don't use LiveKit at all. They only need the TURN server from step 5, and they work in DMs even without steps 1 to 3.

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

Reverse-proxy `https://livekit-jwt.example.org` to it. Keep it up to date: since the Rust rewrite, its old `/sfu/get` (Panbeh) and new `/get_token` (newer Element Call and Element X) put everyone in the same LiveKit room. Older Go versions split those clients into separate calls. Only users of the servers listed in `LIVEKIT_FULL_ACCESS_HOMESERVERS` can start calls on your SFU. Others can still join calls that are already running.

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

## 5. TURN for legacy 1:1 calls

LiveKit's built-in TURN only serves LiveKit. Peer-to-peer legacy calls get TURN credentials from the homeserver (`/voip/turnServer`), so run coturn:

```
# /etc/turnserver.conf
use-auth-secret
static-auth-secret=TURN_SECRET
realm=turn.example.org
listening-port=3478
tls-listening-port=5349
cert=/certs/turn.crt
pkey=/certs/turn.key
no-multicast-peers
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
```

If LiveKit's TURN already uses 3478 and 5349 on this host, give coturn other ports or its own IP.

Synapse:

```yaml
turn_uris: ["turn:turn.example.org:3478?transport=udp", "turn:turn.example.org:3478?transport=tcp", "turns:turn.example.org:5349?transport=tcp"]
turn_shared_secret: TURN_SECRET
turn_user_lifetime: 86400000
```

Conduit / Tuwunel / Continuwuity:

```toml
turn_uris = ["turn:turn.example.org:3478?transport=udp", "turn:turn.example.org:3478?transport=tcp", "turns:turn.example.org:5349?transport=tcp"]
turn_secret = "TURN_SECRET"
```

Without TURN, legacy calls only connect when both sides can reach each other directly (same network, open NAT). Panbeh never falls back to a public TURN server.

## Rooms

Joining a call sends an `org.matrix.msc3401.call.member` state event, which needs permission:
- Groups created by Panbeh allow it for everyone (power 0).
- In older groups, an admin is asked once to enable calls.
- In DMs both people are admins, so nothing is needed.
