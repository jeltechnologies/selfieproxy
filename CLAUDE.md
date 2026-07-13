# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in this repository.

## What this is

This is the **selfieproxy** project root. It does not contain application source itself —
it only orchestrates two independent subprojects via Docker Compose, plus the shared
runtime config/volumes they use:

- `selfieproxy-reverseproxy/` — a forked `github.com/boringproxy/boringproxy` reverse tunnel/proxy
  engine (Go), embedding the OIDC Relying Party too (see `oidc_auth.go`). Despite the fork
  origin, this checkout keeps it as a plain subdirectory of the root repo, not a separate git
  repository or submodule. Has its own `CLAUDE.md` with full architecture docs (connection
  flow, tunnel lifecycle, DB schema, auth model, web UI, OIDC). Read `selfieproxy-reverseproxy/CLAUDE.md`
  before working on anything under that directory.
- `selfieproxy-portal/` — the Selfie Proxy admin portal (Java/Spring), the product-facing UI
  that manages `boringproxy` tunnels/clients through its REST API. See `selfieproxy.md`
  for the product spec (login flow, homelabs, exposed apps, tunnel mapping). No login of its
  own anymore — see `selfieproxy-identity-provider/` below.
- `selfieproxy-identity-provider/` — Selfie Proxy's own bundled, single-user OIDC Identity Provider
  (Java/Spring, same Maven/Dockerfile template as `selfieproxy-portal/`). Used by default to
  authenticate the admin portal and any exposed app with "Protect with SSO" enabled; a BYO
  external IdP (Keycloak, Authentik, etc.) can be swapped in instead via
  `OIDC_ISSUER_URL`/`OIDC_CLIENT_ID`/`OIDC_CLIENT_SECRET`, since `boringproxy`'s embedded OIDC
  client only ever speaks generic OIDC. Self-provisions its RSA signing keypair into
  `data/selfieproxy/sso-signing-key.pem` on first boot, mirroring `ThisServerBootstrap`'s
  pattern. Reached via the same before-any-agent-exists domain carve-out as the portal (see
  `-sso-domain`/`-sso-port` in `selfieproxy-reverseproxy/CLAUDE.md`).
- `sites-webserver/` — a small self-reloading NGINX image (see its own Dockerfile/entrypoint.sh)
  that serves every Local Website (see `selfieproxy.md`) — one shared container, one
  `server_name` block per domain, since boringproxy always forwards a tunnel's own domain as
  the Host header end to end. Run as the `selfieproxy-local-websites` service.

## Layout

```
.
├── .env                          # server config, copied from .env.example
├── data/                         # runtime volumes — not committed
│   ├── boringproxy/               # everything owned by the boringproxy engine (DB, certmagic certs, ephemeral REST token, this-server-certmagic)
│   └── selfieproxy/                # Selfie Proxy's own state: exposed-apps.json (ExposedAppStore),
│       │                            # local-websites.json (LocalWebsiteStore), selfieproxy-local-agent-secret,
│       │                            # default-homelab-bootstrapped (marker, see AgentBootstrap),
│       │                            # sso-signing-key.pem (selfieproxy-identity-provider's self-provisioned RSA key)
│       ├── sites/                  # per-domain content roots for Local Websites — see StaticSiteProvisioner
│       └── sites-conf/             # generated NGINX server-block files, one per domain, consumed by selfieproxy-local-websites
├── check-dns.sh                  # DNS pre-flight check used by docker-compose.yaml
├── docker-compose.yaml            # builds and runs selfieproxy-reverseproxy + selfieproxy-portal + selfieproxy-identity-provider + selfieproxy-local-websites + selfieproxy-local-agent (depends_on selfieproxy-reverseproxy)
├── selfieproxy-reverseproxy/      # forked engine + embedded OIDC Relying Party — subdirectory of this repo, own CLAUDE.md
├── selfieproxy-portal/           # admin portal — Java/Spring, no login of its own (see selfieproxy-identity-provider)
├── selfieproxy-identity-provider/ # bundled single-user OIDC Identity Provider — Java/Spring, same build template as selfieproxy-portal
└── sites-webserver/               # self-reloading NGINX image for "Selfie Proxy hosts this" static sites
```

## Running

```bash
docker compose -f docker-compose.yaml up -d --build       # selfieproxy server + admin portal
```

This repo only runs the server side. Agent hosts are not provisioned or run from here — the
admin portal's Agents page is the source of truth for connecting a homelab (it issues the
agent name/secret an operator needs), and guidance for running the agent process itself lives
there, not in a compose file or `.env` template in this repo.

The admin portal only runs alongside the server (it manages that server's tunnels via the
boringproxy REST API), so it's defined as a second service in `docker-compose.yaml`
rather than its own compose file, with `depends_on: selfieproxy-reverseproxy`. The
`selfieproxy-reverseproxy` container's `-portal-domain`/`-portal-port` flags (set from `SELFPROXY_ADMIN_DOMAIN`/`DOMAIN` and the
selfieproxy-portal's published port, `8081`) make the portal reachable at startup by reverse-proxying
that domain directly to selfieproxy-portal, without going through any Agent/Tunnel — this is what lets
a fresh deployment reach the portal to create its first agent, before any agent exists.

`docker-compose.yaml` also runs two more services, both existing solely to power the
Local Websites feature (see `selfieproxy.md`): `selfieproxy-local-websites` (the `sites-webserver/`
image, a shared NGINX serving every Local Website by `server_name`) and `selfieproxy-local-agent`
(an ordinary boringproxy agent, `network_mode: host` like `boringproxy` itself, colocated with
the server instead of a remote network — the "This Server" homelab, deliberately hidden from
the Homelabs page and the Exposed Applications homelab dropdown, since it's not something a
user picks or manages directly). Unlike every other agent, `selfieproxy-local-agent` needs no secret
copy-pasted into `.env` — its entrypoint blocks on `data/selfieproxy/selfieproxy-local-agent-secret`
existing, a file `ThisServerBootstrap` (`selfieproxy-portal`) republishes on every startup, so
it self-provisions.

The server host's `.env` (from `.env.example`) only needs `DOMAIN` and
`ADMIN_PORTAL_USERNAME`/`ADMIN_PORTAL_PASSWORD` — now consumed by `selfieproxy-identity-provider`
(the bundled OIDC IdP), not `selfieproxy-portal`, which has no login of its own left. Four more
vars are optional, poweruser-only overrides with sensible defaults baked into
application.properties/docker-compose.yaml/check-dns.sh (all four must agree, since
they're not read from a single source of truth): `REVERSE_PROXY_LISTENER` (default
`proxylistener`, the subdomain boringproxy's admin/tunnel-control plane listens on),
`SELFPROXY_ADMIN_DOMAIN` (default `selfieproxy`, the portal's own subdomain),
`SELFPROXY_AUTH_DOMAIN` (default `auth`, `selfieproxy-identity-provider`'s own subdomain), and
`DEFAULT_HOMELAB` (default `my-homelab`, the name selfieproxy-portal bootstraps a default
agent under on first boot, see the Agents page). Separately, `OIDC_ISSUER_URL`/
`OIDC_CLIENT_ID`/`OIDC_CLIENT_SECRET` (all blank by default) override the admin portal's OIDC
issuer to an external IdP instead of the bundled server — see `selfieproxy-identity-provider/` above
and `selfieproxy-reverseproxy/CLAUDE.md`'s OIDC section. `BORINGPROXY_DEBUG` (default `false`) turns on
boringproxy's `-debug` per-request access log (timestamp, remote IP, method, host, path) to
stdout — off by default since every agent poll and every Homelabs-page auto-refresh tick would
otherwise log a line. The colocated homelab's name is hardcoded to
`selfieproxy-internal-agent` on both sides (docker-compose.yaml's selfieproxy-local-agent service
and selfieproxy-portal's `this-server.agent-name`) rather than even optionally env-configurable,
since the two must always match. Agent hosts are out of scope for this repo entirely — there's
no compose file or `.env` template for them here. An agent connects with just a name and a
server-generated secret (`AGENT_NAME`/`AGENT_SECRET`) issued from the admin portal's Agents
page, plus the boringproxy admin domain to dial (`REVERSE_PROXY_LISTENER.DOMAIN` from this
server's `.env`); the portal itself is the source of guidance for running that agent process.
`docker-compose.yaml` doesn't pass `-acme-email` to boringproxy — it's optional for
ACME/Let's Encrypt (used only for expiry notices), so `-accept-ca-terms` alone is enough to
issue certs unattended.
`data/boringproxy/storage` and `data/boringproxy/certmagic`/`this-server-certmagic` persist the
boringproxy database and TLS certs (server's and selfieproxy-local-agent's, kept separate) across restarts;
`data/selfieproxy` persists selfieproxy-portal's own exposed-app records (see ExposedAppStore in
`selfieproxy-portal/CLAUDE.md`-adjacent code — boringproxy's own Tunnel schema can't represent
everything Selfie Proxy needs, eg. homelab protocol), the completely separate Local Website
records (LocalWebsiteStore — Local Websites don't share ExposedAppStore/the Exposed Applications
page at all, by design), the selfieproxy-local-agent secret file, and the `sites`/`sites-conf`
directories `selfieproxy-local-websites` also mounts.

## Working on this repo

- Changes to the reverse-tunnel engine itself (tunnel lifecycle, TLS termination, the
  JSON API, the web UI templates) belong in `selfieproxy-reverseproxy/` — see
  `selfieproxy-reverseproxy/CLAUDE.md`.
- Changes to the admin-facing product (login, exposed-app management, homelab
  selection) belong in `selfieproxy-portal/` — see `selfieproxy.md` for the intended behavior.
- Changes to how the pieces are deployed together (compose files, `.env` shape, volume
  layout) belong at this root level.
