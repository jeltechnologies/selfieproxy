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
- `domain/TerminalSettings.java` + `domain/TerminalSettingsStore.java` +
  `web/TerminalSettingsController.java` — the SSH console's Settings panel (font size, font
  family, color theme, `connect-terminal.html`'s `settings.js`) is persisted server-side here
  (`data/remote-console-settings.json`, `GET`/`POST /api/terminal-settings`) rather than in the
  browser's `localStorage` as it originally was, so the setting is shared across browsers/devices
  and can be included in a configuration export/import (`selfieproxy-portal`'s `BackupService`
  reads and, on restore, writes this same file through its own mirrored
  `domain/TerminalSettings.java`/`TerminalSettingsStore.java` -- same shared-`/data`-volume
  precedent as `ThemeStore`, not a Java dependency between the two modules). RDP/VNC's own
  `connect.html`/`connect.js` have no such panel and are untouched -- their on-screen colors come
  from whatever the remote server renders, not a client-side theme choice.
- No dependency on `BoringProxyClient`/boringproxy's REST API at all — this service never
  creates or deletes tunnels, only reads the already-resolved `tunnelPort` the portal persisted.

## Reached via

Its own always-SSO-gated domain carve-out in `selfieproxy-reverseproxy`
(`-console-domain`/`-console-port`, default subdomain `console`) — the same shape as
`-portal-domain`, including the admin-only `is_admin` check (the browser SSH/RDP/VNC console is
Homelab-management tooling, never reachable by a login-only User). See
`selfieproxy-reverseproxy/CLAUDE.md`'s "Console domain" section.

## Fixed: browser WebSocket handshake was rejected outright (missing subprotocol echo)

Found 2026-07-22, and unrelated to the RDP-specific issue below even though it produced
similar-looking symptoms: `GuacamoleWebSocketHandler` never implemented Spring's
`SubProtocolCapable`, so Spring's `DefaultHandshakeHandler` never echoed a
`Sec-WebSocket-Protocol` response header even though guacamole-common-js always opens its
tunnel requesting the `"guacamole"` subprotocol. Per RFC 6455 §4.2.2, a client that requests a
subprotocol must fail the connection if the server's 101 response doesn't confirm one back —
every real, spec-compliant browser (verified against both Chrome and Brave) rejected the
handshake instantly as a result, every single time, regardless of network, browser, or any
RDP-side setting. This is why the connection appeared to die right around
`RDPDR user logged on` in guacd's logs: that's guacd's *own* FreeRDP-side negotiation thread
continuing independently after the browser had already aborted the WebSocket entirely, not
guacd itself failing. A raw scripted WebSocket client doesn't validate this RFC requirement,
which is why every scripted reproduction during this investigation appeared to work fine and
initially pointed away from the real cause. Fixed by having
`GuacamoleWebSocketHandler` implement `SubProtocolCapable` and return `List.of("guacamole")`.

This also means two conclusions reached earlier in this same investigation, before the
subprotocol bug was found, were confounded by it and are **not reliable**:
- The conclusion that `disable-gfx=true` "must stay on" (a same-target guacd test with it
  removed reproduced the exact same hang-at-RDPDR symptom) was actually just this subprotocol
  bug again, present in both configurations tested. Whether GFX vs classic bitmap rendering
  actually matters against this target is still genuinely unresolved -- `disable-gfx=true` is
  left in place below since it's the last known-working setting, not because it was re-verified
  against a clean baseline.
- A reverseproxy-layer fix (skipping the SSO sliding-idle cookie refresh for WebSocket upgrade
  requests, since staging a `Set-Cookie` header on a `ResponseWriter` about to be hijacked was
  suspected of corrupting the handshake) was reverted -- it never actually addressed a real
  bug, it only appeared to help because, again, none of the scripted tests validated the
  subprotocol requirement either way.

## Fixed: RDP rendered solid blank/black until a manual fullscreen toggle

Investigated 2026-07-22 against a real Kubuntu/xrdp target, once the subprotocol bug above was
separately fixed and connections could actually stay alive. Symptom: right after connecting
(and again after leaving fullscreen), the display could render solid blank/black — but the
session was genuinely alive the whole time (status shows "Connected", and if you move the
mouse you see the remote cursor icon move over the blank area, and keyboard/mouse input
worked normally). Toggling fullscreen once reliably made the desktop actually paint.

This was **not** the xrdp/RandR-level "won't repaint without a resolution change" quirk it
initially looked like (that theory, and a citation of upstream xrdp/Guacamole issues about it,
was wrong -- left here as a note against re-treading it). Live testing ruled it out directly:
- `client.sendSize()` calls of every kind were tried against the real target -- a 2px-short
  connect immediately corrected up, the exact same size re-sent, a small placeholder (640x480)
  jumped to the true size, at multiple delays (0ms, 100ms, 300ms, 4000ms) -- and **none of them
  ever made it paint** in the normal (non-fullscreen) window.
- A genuine, large, manual **OS-level window resize** (dragging the browser window itself
  bigger/smaller, no fullscreen involved) also never fixed it.
- Only the Fullscreen API transition itself ever did -- despite calling the exact same
  `client.sendSize()` as every other path.

Since resizing (of any size, magnitude, or timing) never helped except via Fullscreen
specifically, the real cause was a **browser-side canvas compositing issue**, not a
server/xrdp-side repaint quirk: RDPGFX/AVC-decoded frames land in a GPU-composited canvas
layer, and that layer can get stuck showing nothing until the browser is forced to recomposite
it. Entering fullscreen forces exactly that (tearing down and rebuilding the element's render
layer as a side effect of the Fullscreen API transition) -- the resize that happens to
accompany it is incidental, not the actual fix.

Confirmed by comparing against [Termix-SSH/Termix](https://github.com/Termix-SSH/Termix)'s own
guacd-based RDP viewer (`src/ui/features/guacamole/GuacamoleDisplay.tsx`): it never asks the
server to match the window size at all. It fits the canvas to its container purely client-side,
via `Guacamole.Display.scale()` -- a CSS **transform**, not a resize/layout change. A `display:
none`/`''` toggle (forcing a full layout-tree removal) was tried here first and did **not**
fix it -- browsers treat transform changes very differently from layout removal for
GPU-composited content. Nudging `Display.scale()` away from and back to `1` (an effectively
invisible, momentary transform change) forces the same recomposite Termix gets from its own
scale-to-fit logic, live-verified working against the real target.

`connect.js`'s `forceRepaint()` does this nudge (`display.scale(0.999999)` then, on the next
animation frame, `display.scale(1)`), called once right after the tunnel reaches Connected, and
again after every real `sendSize()` from `requestRemoteResize()` (window resize/fullscreenchange,
including leaving fullscreen back to the windowed view) for consistency. SSH/VNC don't have
this bug and are untouched.

What was ruled out / fixed along the way getting here (all still in place, all correct):

- `resize-method=reconnect` (a first attempt at making `client.sendSize()` do anything for
  RDP) was **actively harmful**, not just ineffective: it sent guacd's internal FreeRDP client
  into a repeated disconnect/reconnect storm for ~30s after every single connect, independent
  of whether the browser ever resized — RDPDR channel renegotiation failing on every cycle,
  the same fragility tracked upstream as
  [GUACAMOLE-876](https://issues.apache.org/jira/browse/GUACAMOLE-876)/
  [GUACAMOLE-900](https://issues.apache.org/jira/browse/GUACAMOLE-900). Replaced with
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
  into the page.
- `disable-gfx` is deliberately left **unset** (guacd's default modern RDPGFX/AVC pipeline),
  matching Termix's own guacd defaults, which don't set it either. An earlier "live test" had
  concluded this flag needed to stay on, but that was confounded by the subprotocol bug above
  (present with the flag either way) -- once that was fixed, GFX worked fine against this
  target with the flag removed.

Live-verified 2026-07-22, working end to end: connects and paints immediately in the normal
windowed view, no manual fullscreen toggle needed, GFX pipeline enabled (no `disable-gfx`).
