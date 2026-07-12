# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in this repository.

## What this is

This is the **selfieproxy** project root. It does not contain application source itself —
it only orchestrates two independent subprojects via Docker Compose, plus the shared
runtime config/volumes they use:

- `boringproxy/` — a forked `github.com/boringproxy/boringproxy` reverse tunnel/proxy
  engine (Go). Has its own git repository and its own `CLAUDE.md` with full architecture
  docs (connection flow, tunnel lifecycle, DB schema, auth model, web UI). Read
  `boringproxy/CLAUDE.md` before working on anything under that directory.
- `selfieproxy-portal/` — the Selfie Proxy admin portal (Java/Spring), the product-facing UI
  that manages `boringproxy` tunnels/clients through its REST API. See `selfieproxy.md`
  for the product spec (login flow, homelabs, exposed apps, tunnel mapping).
- `sites-webserver/` — a small self-reloading NGINX image (see its own Dockerfile/entrypoint.sh)
  that serves every Local Website (see `selfieproxy.md`) — one shared container, one
  `server_name` block per domain, since boringproxy always forwards a tunnel's own domain as
  the Host header end to end. Run as the `selfieproxy-local-websites` service.

## Layout

```
.
├── .env                          # per-host config, copied from .env.server.example (server host) or .env.agent.example (agent host)
├── data/                         # runtime volumes — not committed
│   ├── boringproxy/               # everything owned by the boringproxy engine (DB, certmagic certs, ephemeral REST token, this-server-certmagic)
│   └── selfieproxy/                # Selfie Proxy's own state: exposed-apps.json (ExposedAppStore),
│       │                            # local-websites.json (LocalWebsiteStore), selfieproxy-local-agent-secret,
│       │                            # default-homelab-bootstrapped (marker, see AgentBootstrap)
│       ├── sites/                  # per-domain content roots for Local Websites — see StaticSiteProvisioner
│       └── sites-conf/             # generated NGINX server-block files, one per domain, consumed by selfieproxy-local-websites
├── check-dns.sh                  # DNS pre-flight check used by docker-compose-server.yaml
├── docker-compose-server.yaml     # builds and runs selfieproxy server + selfieproxy-portal + selfieproxy-local-websites + selfieproxy-local-agent (depends_on boringproxy)
├── docker-compose-agent.yaml      # builds and runs selfieproxy-agent, the connecting tunnel process (build context: ./boringproxy)
├── boringproxy/                  # forked engine — own git repo, own CLAUDE.md
├── selfieproxy-portal/           # admin portal — Java/Spring
└── sites-webserver/               # self-reloading NGINX image for "Selfie Proxy hosts this" static sites
```

## Running

```bash
docker compose -f docker-compose-server.yaml up -d --build       # selfieproxy server + admin portal
docker compose -f docker-compose-agent.yaml up -d --build        # selfieproxy-agent
```

The admin portal only runs alongside the server (it manages that server's tunnels via the
boringproxy REST API), so it's defined as a second service in `docker-compose-server.yaml`
rather than its own compose file, with `depends_on: boringproxy`. `boringproxy-server`'s
`-portal-domain`/`-portal-port` flags (set from `SELFPROXY_ADMIN_DOMAIN`/`DOMAIN` and the
selfieproxy-portal's published port, `8081`) make the portal reachable at startup by reverse-proxying
that domain directly to selfieproxy-portal, without going through any Agent/Tunnel — this is what lets
a fresh deployment reach the portal to create its first agent, before any agent exists.

`docker-compose-server.yaml` also runs two more services, both existing solely to power the
Local Websites feature (see `selfieproxy.md`): `selfieproxy-local-websites` (the `sites-webserver/`
image, a shared NGINX serving every Local Website by `server_name`) and `selfieproxy-local-agent`
(an ordinary boringproxy agent, `network_mode: host` like `boringproxy` itself, colocated with
the server instead of a remote network — the "This Server" homelab, deliberately hidden from
the Homelabs page and the Exposed Applications homelab dropdown, since it's not something a
user picks or manages directly). Unlike every other agent, `selfieproxy-local-agent` needs no secret
copy-pasted into `.env` — its entrypoint blocks on `data/selfieproxy/selfieproxy-local-agent-secret`
existing, a file `ThisServerBootstrap` (`selfieproxy-portal`) republishes on every startup, so
it self-provisions.

The server host's `.env` (from `.env.server.example`) only needs `DOMAIN` and
`ADMIN_PORTAL_USERNAME`/`ADMIN_PORTAL_PASSWORD`. Three more vars are optional, poweruser-only
overrides with sensible defaults baked into application.properties/docker-compose-server.yaml/
check-dns.sh (all three must agree, since they're not read from a single source of truth):
`REVERSE_PROXY_LISTENER` (default `proxylistener`, the subdomain boringproxy's admin/tunnel-control
plane listens on), `SELFPROXY_ADMIN_DOMAIN` (default `selfieproxy`, the portal's own subdomain),
and `DEFAULT_HOMELAB` (default `my-homelab`, the name selfieproxy-portal bootstraps a default
agent under on first boot, see the Agents page). The colocated homelab's name is hardcoded to
`selfieproxy-internal-agent` on both sides (docker-compose-server.yaml's selfieproxy-local-agent service
and selfieproxy-portal's `this-server.agent-name`) rather than even optionally env-configurable,
since the two must always match. Each
agent host's own `.env` (from `.env.agent.example`) is
separate and much smaller: `SELFIEPROXY_LISTENER` (the boringproxy admin domain the agent connects
to, i.e. `REVERSE_PROXY_LISTENER.DOMAIN` from the server's `.env` — a single value rather than two,
since the agent has no other use for either half) and `AGENT_NAME`/`AGENT_SECRET`
for the connecting `selfieproxy-agent` process in `docker-compose-agent.yaml` — that secret is
server-generated and must be copied from the admin portal's Agents page into the agent host's
`.env` after the server first starts. Neither compose file passes `-acme-email` to boringproxy —
it's optional for ACME/Let's Encrypt (used only for expiry notices), so `-accept-ca-terms` alone
is enough to issue certs unattended.
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
  JSON API, the web UI templates) belong in `boringproxy/` — see `boringproxy/CLAUDE.md`.
- Changes to the admin-facing product (login, exposed-app management, homelab
  selection) belong in `selfieproxy-portal/` — see `selfieproxy.md` for the intended behavior.
- Changes to how the pieces are deployed together (compose files, `.env` shape, volume
  layout) belong at this root level.
