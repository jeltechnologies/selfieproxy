# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working on
`selfieproxy-remote-console`. See the root `CLAUDE.md` for how this module fits into the rest of
the repo, and `selfieproxy-portal/CLAUDE.md`'s "Exposed applications" section (specifically the
"Terminal Access: SSH"/"Desktop Access: RDP"/"Desktop Access: VNC" Network Service Modes and
"Connecting to an SSH/RDP/VNC-mode application") for the product-facing config/CRUD behavior,
which lives entirely in the portal, not here -- there is no separate "Remote consoles" concept or
nav tab anymore, this is just three of the four modes an ordinary Application can have.

## What this is

The live half of Selfie Proxy's browser SSH/RDP/VNC console feature: a small Java/Spring service
(same Maven/Dockerfile template as `selfieproxy-portal`/`selfieproxy-identity-provider`) that
bridges a browser WebSocket to [Apache Guacamole](https://guacamole.apache.org/)'s `guacd`
daemon (`selfieproxy-guacd` in `docker-compose.yaml`, the official unmodified
`guacamole/guacd` image — see `THIRD-PARTY-NOTICES.md`). This module owns none of the
configuration — every Application (name, homelab, mode, host/port, credentials) is created and
edited entirely in `selfieproxy-portal` (`ExposedAppController`, `ExposedAppStore`), written to
`data/selfieproxy/exposed-apps.json`. This service only *reads* that same file (shared `/data`
volume, read-only in spirit though the mount isn't literally `:ro` since nothing here ever writes
it) at connect time, filtered down to the SSH/RDP/VNC-mode entries by this module's own
`RemoteConsoleStore` (a deliberately partial mirror of `ExposedApp`, see its own javadoc) — the
same loose, shared-filesystem coupling `selfieproxy-local-websites`/`selfieproxy-localsites-agent`
already have with the portal, no API calls between the two at all.

## Why it's a separate service, and why it's `network_mode: host`

An SSH/RDP/VNC-mode application's tunnel is created (by the portal, via boringproxy's REST API) with
`allow-external-tcp: false` — see `selfieproxy-reverseproxy/CLAUDE.md`'s "Core types" section.
That binds the tunnel's listener to `127.0.0.1` on the server host, not `0.0.0.0`: deliberately
never reachable from the internet, only from a process in the **same network namespace as the
host**. A plain Docker bridge-network container cannot reach a strictly-`127.0.0.1`-bound host
port at all (not even via `extra_hosts: host.docker.internal` — that only reaches `0.0.0.0`-bound
ports), so whatever dials that tunnel port has to run `network_mode: host`, exactly like
`selfieproxy-reverseproxy`/`selfieproxy-localsites-agent` already do.

`guacd` is the piece that actually dials the tunnel port (it receives a `GuacamoleConfiguration`
naming `hostname`/`port` and connects to that itself — see `GuacamoleWebSocketHandler`), so
`guacd` must be host-networked and bound to `127.0.0.1:4822` only (`GUACD_BIND_HOST`, since it has
no authentication of its own and must never be reachable by anything else on this host, let alone
the internet). This service, `selfieproxy-remote-console`, is also host-networked purely so it can
reach that same `127.0.0.1:4822` — it has no other reason to need host networking, and
deliberately has **no dependency on anything reachable only over the Docker bridge network**
(unlike `selfieproxy-portal`, which must stay off host networking because it depends on
`selfieproxy-identity-provider`'s internal API being bridge-network-only). This is also why the
WebSocket bridge could not simply be bolted onto `selfieproxy-portal` directly — portal must
never become host-networked.

Configuration/CRUD staying in the portal (rather than duplicated here) mirrors how Local
Websites' config lives in the portal even though its runtime is served by separate containers —
same pattern, applied to a feature with a much stronger reason (network topology, not just
tidiness) to keep the live session in its own service.

## Architecture

- `domain/RemoteConsole.java` + `domain/RemoteConsoleStore.java` — a deliberate small, partial
  duplication of `selfieproxy-portal`'s own `ExposedApp`/`ExposedAppStore` (read-only here,
  trimmed to the fields this service needs, filtered to SSH/RDP/VNC-mode Network Services --
  see `RemoteConsole`'s own javadoc), rather than a shared Java library between two independently-
  built Maven projects — same precedent as portal/identity-provider never sharing Java code either.
- `security/RemoteConsoleCredentialCipher.java` — decrypt-only counterpart to the portal's own
  `NetworkServiceCredentialCipher`. The AES/GCM parameters (key length, IV length, tag length, and
  the `iv || ciphertext` wire layout) must stay byte-for-byte identical between the two — this
  module never generates the key, only reads what the portal already provisioned into
  `network-service-secret-key`.
- `ws/GuacamoleWebSocketHandler.java` — the actual bridge, a plain Spring `WebSocketHandler` (not
  guacamole-common's own JSR-356 `GuacamoleWebSocketTunnelEndpoint` base class, which expects
  container-managed `Endpoint` registration rather than Spring MVC's handler-registry model). On
  connect: loads the `RemoteConsole` record (id supplied by
  `ws/ConsoleIdHandshakeInterceptor`, since Spring's WebSocket handler registry has no built-in
  path-variable binding the way `@GetMapping` does), decrypts its credential, builds a
  `GuacamoleConfiguration` (protocol/hostname/port/credentials per Guacamole's own documented
  parameter names for `ssh`/`rdp`/`vnc`), opens a `ConfiguredGuacamoleSocket` against
  `guacd:4822`, wraps it in a `SimpleGuacamoleTunnel`, and relays frames both directions
  (`GuacamoleReader`/`GuacamoleWriter`'s `char[]`-based API) until either side closes.
- `web/ConnectController.java` — serves `connect.html` for `GET /connect/{id}`: a full-page
  canvas wiring vendored `guacamole-common-js` (`Guacamole.Client` +
  `Guacamole.WebSocketTunnel` + `Guacamole.Keyboard`/`Guacamole.Mouse`). Guacamole renders SSH as
  a terminal through the same canvas/display protocol as RDP/VNC, so this one frontend component
  covers all three protocols.
- No dependency on `BoringProxyClient`/boringproxy's REST API at all — this service never
  creates or deletes tunnels, only reads the already-resolved `tunnelPort` the portal persisted.

## Reached via

Its own always-SSO-gated domain carve-out in `selfieproxy-reverseproxy`
(`-console-domain`/`-console-port`, default subdomain `console`) — the same shape as
`-portal-domain`, including the admin-only `is_admin` check (the browser SSH/RDP/VNC console is
Homelab-management tooling, never reachable by a login-only User). See
`selfieproxy-reverseproxy/CLAUDE.md`'s "Console domain" section.

## Known limitation: RDP sometimes needs a resize to "kick" the first paint

Investigated 2026-07-22 against a real Kubuntu/xrdp target. Symptom: right after connecting
(and again after leaving fullscreen), the display can render solid blank/black — but the
session is genuinely alive the whole time (status shows "Connected", and if you move the
mouse you see the remote cursor icon move over the blank area). Toggling fullscreen once
reliably makes the desktop actually paint.

What was ruled out / fixed along the way (all still in place, all correct — this section is
about the one remaining gap, not a to-do list):

- `resize-method=reconnect` (a first attempt at making `client.sendSize()` do anything for
  RDP) was **actively harmful**, not just ineffective: it sent guacd's internal FreeRDP client
  into a repeated disconnect/reconnect storm for ~30s after every single connect, independent
  of whether the browser ever resized — RDPDR channel renegotiation failing on every cycle,
  the same fragility tracked upstream as
  [GUACAMOLE-876](https://issues.apache.org/jira/browse/GUACAMOLE-876)/
  [GUACAMOLE-900](https://issues.apache.org/jira/browse/GUACAMOLE-900). That alone produced
  most of what looked like "blank until fullscreen" at the time. Replaced with
  `resize-method=display-update` (RDP's own Display Control channel, no reconnect involved) —
  confirmed working cleanly against this xrdp target (`Display update channel will be used
  for display size changes` / `Server resized display to WxH` in guacd's logs, no storm).
- Odd-pixel width/height (eg. a browser content-box height landing on an odd number) is
  fixed client-side by flooring to the nearest even number before both the initial
  `client.connect()` call and every `client.sendSize()` (`evenDown()` in `connect.js`) — RDP's
  GFX/AVC pipeline chroma-subsamples in 2x2 blocks and can silently fail to decode an odd
  dimension into any visible frame.
- The `#display-container:fullscreen` CSS override (`height: 100%`, not the windowed view's
  `calc(100% - 48px)`) fixes a real aspect-ratio bug: the toolbar isn't part of the
  fullscreen element, so subtracting its height while fullscreen requested a resolution 48px
  shorter than the true screen.
- A guard in `connect.js`'s `requestRemoteResize()` discards any `sendSize()` call where
  width or height reads below 100px — exiting fullscreen can report a transient near-zero
  `clientWidth`/`clientHeight` before the browser finishes reflowing `display-container` back
  into the page, and sending that straight through blanks the display with nothing afterward
  to correct it (no further resize/repaint is ever triggered on its own once
  `fullscreenchange` has already fired once).
- `disable-gfx=true` (forcing guacd's classic pre-GFX bitmap-update rendering instead of the
  AVC420/444/Progressive-codec RDPGFX pipeline) was tried on the theory that RDPGFX surface
  binding was the culprit, since the cursor (a separate, always-on channel) renders while the
  framebuffer doesn't. **Did not fix it** — ruled out, left disabled again would be a wasted
  bandwidth trade for no benefit, but the flag itself is harmless if re-enabled later for
  other reasons.

Still open: something about the very first framebuffer after connect (and again after the
post-fullscreen-exit reconnect-via-display-update) doesn't reach the canvas until a
`display-update` resize forces guacd to rebuild/redraw. Whether that's an xrdp-side quirk on
this particular Homelab (eg. a compositor not repainting for a hidden/off-screen virtual
display until RandR reports a mode change) or something in guacd's own repaint-after-resize
path wasn't isolated — `disable-gfx` ruled out the GFX pipeline specifically, but nothing else
was tested (eg. `enable-desktop-composition`, forcing a synthetic `client.sendSize()` call
immediately post-connect as a deliberate "kick" rather than a real resize, or a different
target xrdp version). The known-working workaround for a user hitting this is simply:
toggle fullscreen once after connecting.
