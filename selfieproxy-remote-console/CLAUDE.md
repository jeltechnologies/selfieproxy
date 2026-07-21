# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working on
`selfieproxy-remote-console`. See the root `CLAUDE.md` for how this module fits into the rest of
the repo, and `selfieproxy-portal/CLAUDE.md`'s "Remote consoles" section for the product-facing
config/CRUD behavior (which lives entirely in the portal, not here).

## What this is

The live half of Selfie Proxy's browser SSH/RDP/VNC console feature: a small Java/Spring service
(same Maven/Dockerfile template as `selfieproxy-portal`/`selfieproxy-identity-provider`) that
bridges a browser WebSocket to [Apache Guacamole](https://guacamole.apache.org/)'s `guacd`
daemon (`selfieproxy-guacd` in `docker-compose.yaml`, the official unmodified
`guacamole/guacd` image — see `THIRD-PARTY-NOTICES.md`). This module owns none of the
configuration — `RemoteConsole` records (name, homelab, protocol, host/port, credentials) are
created and edited entirely in `selfieproxy-portal` (`RemoteConsoleController`,
`RemoteConsoleStore`), written to `data/selfieproxy/remote-consoles.json`. This service only
*reads* that same file (shared `/data` volume, read-only in spirit though the mount isn't
literally `:ro` since nothing here ever writes it) at connect time — the same loose,
shared-filesystem coupling `selfieproxy-local-websites`/`selfieproxy-localsites-agent` already
have with the portal, no API calls between the two at all.

## Why it's a separate service, and why it's `network_mode: host`

A Remote Console's tunnel is created (by the portal, via boringproxy's REST API) with
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

- `domain/RemoteConsole.java` + `domain/RemoteConsoleStore.java` — a deliberate small duplication
  of `selfieproxy-portal`'s own record/store (read-only here), rather than a shared Java library
  between two independently-built Maven projects — same precedent as portal/identity-provider
  never sharing Java code either.
- `security/RemoteConsoleCredentialCipher.java` — decrypt-only counterpart to the portal's own
  cipher of the same name. The AES/GCM parameters (key length, IV length, tag length, and the
  `iv || ciphertext` wire layout) must stay byte-for-byte identical between the two — this module
  never generates the key, only reads what the portal already provisioned into
  `remote-console-secret-key`.
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
`-portal-domain`, including the admin-only `is_admin` check (Remote Consoles are
Homelab-management tooling, never reachable by a login-only User). See
`selfieproxy-reverseproxy/CLAUDE.md`'s "Console domain" section.
