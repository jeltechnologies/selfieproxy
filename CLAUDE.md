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
  the Host header end to end. Run as the `selfieproxy-sites` service.

## Layout

```
.
├── .env                          # shared config: DOMAIN, BORING_PROXY_ADMIN_SUBDOMAIN, ACME_EMAIL, CLIENT_* — read by all compose files
├── data/                         # runtime volumes — not committed
│   ├── boringproxy/               # everything owned by the boringproxy engine (DB, certmagic certs, ephemeral REST token, this-server-certmagic)
│   └── selfieproxy/                # Selfie Proxy's own state: exposed-apps.json (ExposedAppStore),
│       │                            # local-websites.json (LocalWebsiteStore), this-server-agent-secret
│       ├── sites/                  # per-domain content roots for Local Websites — see StaticSiteProvisioner
│       └── sites-conf/             # generated NGINX server-block files, one per domain, consumed by selfieproxy-sites
├── check-dns.sh                  # DNS pre-flight check used by docker-compose-server.yaml
├── docker-compose-server.yaml     # builds and runs selfieproxy server + selfieproxy-portal + selfieproxy-sites + this-server-agent (depends_on boringproxy)
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
`-portal-domain`/`-portal-port` flags (set from `SELFIEPROXY_SUBDOMAIN`/`DOMAIN` and the
selfieproxy-portal's published port, `8081`) make the portal reachable at startup by reverse-proxying
that domain directly to selfieproxy-portal, without going through any Agent/Tunnel — this is what lets
a fresh deployment reach the portal to create its first agent, before any agent exists.

`docker-compose-server.yaml` also runs two more services, both existing solely to power the
Local Websites feature (see `selfieproxy.md`): `selfieproxy-sites` (the `sites-webserver/`
image, a shared NGINX serving every Local Website by `server_name`) and `this-server-agent`
(an ordinary boringproxy agent, `network_mode: host` like `boringproxy` itself, colocated with
the server instead of a remote network — the "This Server" homelab, deliberately hidden from
the Homelabs page and the Exposed Applications homelab dropdown, since it's not something a
user picks or manages directly). Unlike every other agent, `this-server-agent` needs no secret
copy-pasted into `.env` — its entrypoint blocks on `data/selfieproxy/this-server-agent-secret`
existing, a file `ThisServerBootstrap` (`selfieproxy-portal`) republishes on every startup, so
it self-provisions.

All read `.env` for shared config (`DOMAIN`, `BORING_PROXY_ADMIN_SUBDOMAIN`, `ACME_EMAIL`,
`AGENT_DEFAULT_NAME`, `THIS_SERVER_AGENT_NAME`, `AGENT_NAME`, `AGENT_SECRET`). `AGENT_DEFAULT_NAME`
is the name selfieproxy-portal bootstraps a default agent under on first boot (see the Agents
page); `THIS_SERVER_AGENT_NAME` (default `this-server`) is the colocated homelab's name, bootstrapped
the same way; `AGENT_NAME`/`AGENT_SECRET` configure the connecting `selfieproxy-agent` process in
`docker-compose-agent.yaml` for a genuinely remote homelab — that secret is server-generated and
must be copied from the admin portal's Agents page into `.env` after the server first starts.
`data/boringproxy/storage` and `data/boringproxy/certmagic`/`this-server-certmagic` persist the
boringproxy database and TLS certs (server's and this-server-agent's, kept separate) across restarts;
`data/selfieproxy` persists selfieproxy-portal's own exposed-app records (see ExposedAppStore in
`selfieproxy-portal/CLAUDE.md`-adjacent code — boringproxy's own Tunnel schema can't represent
everything Selfie Proxy needs, eg. homelab protocol), the completely separate Local Website
records (LocalWebsiteStore — Local Websites don't share ExposedAppStore/the Exposed Applications
page at all, by design), the this-server-agent secret file, and the `sites`/`sites-conf`
directories `selfieproxy-sites` also mounts.

## Working on this repo

- Changes to the reverse-tunnel engine itself (tunnel lifecycle, TLS termination, the
  JSON API, the web UI templates) belong in `boringproxy/` — see `boringproxy/CLAUDE.md`.
- Changes to the admin-facing product (login, exposed-app management, homelab
  selection) belong in `selfieproxy-portal/` — see `selfieproxy.md` for the intended behavior.
- Changes to how the pieces are deployed together (compose files, `.env` shape, volume
  layout) belong at this root level.
