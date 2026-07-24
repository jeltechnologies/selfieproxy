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
  that manages `boringproxy` tunnels/clients through its REST API. Has its own `CLAUDE.md` with
  the full product spec (login flow, homelabs, exposed apps, tunnel mapping). No login of its
  own anymore — see `selfieproxy-identity-provider/` below.
- `selfieproxy-identity-provider/` — Selfie Proxy's own bundled, OIDC Identity Provider with simplified admin/User management
  (Java/Spring, same Maven/Dockerfile template as `selfieproxy-portal/`). Used by default to
  authenticate the admin portal and any exposed app with single sign on protection enabled; a BYO
  external IdP (Keycloak, Authentik, etc.) can be swapped in instead via
  `OIDC_ISSUER_URL`/`OIDC_CLIENT_ID`/`OIDC_CLIENT_SECRET`, since `boringproxy`'s embedded OIDC
  client only ever speaks generic OIDC. Self-provisions its RSA signing keypair into
  `data/selfieproxy/sso-signing-key.pem` on first boot, mirroring `ThisServerBootstrap`'s
  pattern. Reached via the same before-any-agent-exists domain carve-out as the portal (see
  `-sso-domain`/`-sso-port` in `selfieproxy-reverseproxy/CLAUDE.md`).
- `selfieproxy-localsites-webserver/` — a small self-reloading NGINX image (see its own Dockerfile/entrypoint.sh)
  that serves every Local Website (see `selfieproxy-portal/CLAUDE.md`) — one shared container, one
  `server_name` block per domain, since boringproxy always forwards a tunnel's own domain as
  the Host header end to end. Run as the `selfieproxy-local-websites` service.
- `selfieproxy-check-prerequisites/` — a tiny Alpine image (curl + bind-tools + `check-prerequisites.sh`
  baked in via its own Dockerfile) that fails fast before anything else starts if `PRIMARY_DOMAIN` and a
  `*.PRIMARY_DOMAIN` wildcard record don't already resolve to the host's public IP (checked as the literal
  DNS owner names `PRIMARY_DOMAIN` and `*.PRIMARY_DOMAIN`, covering every current and future subdomain — the
  fixed `proxylistener`/`selfieproxy`/`auth`/`console` subdomains and any exposed-app/tunnel subdomain created later
  — rather than enumerating each fixed subdomain by name). Run as the `check-prerequisites` service. This
  check only ever covers the primary domain — any secondary domain (see `selfieproxy-portal/CLAUDE.md`'s
  "Domains" section) is registered and DNS-checked entirely at runtime through the portal's own Domains
  settings page instead, since it's added long after this container has already started.
- `selfieproxy-remote-console/` — browser SSH/RDP/VNC console (Java/Spring, same Maven/Dockerfile
  template as `selfieproxy-portal`/`selfieproxy-identity-provider`), the CRUD for which lives in the
  admin portal as three of the four Network Service Modes an Application can have ("Terminal
  Access: SSH", "Desktop Access: RDP", "Desktop Access: VNC" -- see `selfieproxy-portal/CLAUDE.md`'s
  "Exposed applications" section) while this service itself only serves the live browser session.
  Pairs with the `selfieproxy-guacd` service (the official,
  unmodified `guacamole/guacd` Docker image — see `THIRD-PARTY-NOTICES.md`) to bridge a WebSocket
  connection to a Homelab's SSH/RDP/VNC endpoint, reached over an `AllowExternalTcp: false` tunnel (never
  internet-reachable — see this file's "Running" section). Reached via its own always-SSO-gated,
  before-any-agent-exists domain carve-out (`-console-domain`/`-console-port`), the same shape as
  `-portal-domain`/`-sso-domain` — see `selfieproxy-reverseproxy/CLAUDE.md`.

## Layout

```
.
├── .env                          # server config, copied from .env.example
├── data/                         # runtime volumes — not committed
│   ├── reverseproxy/               # everything owned by the boringproxy engine (DB, certmagic certs, ephemeral REST token, this-server-certmagic)
│   └── selfieproxy/                # Selfie Proxy's own state: exposed-apps.json (ExposedAppStore --
│       │                            # also covers every SSH/RDP/VNC-mode Network Service, read by
│       │                            # selfieproxy-remote-console over the shared volume) + network-service-secret-key
│       │                            # (self-provisioned, see selfieproxy-portal/CLAUDE.md's "Exposed applications"),
│       │                            # local-websites.json (LocalWebsiteStore), domains.json (DomainStore --
│       │                            # registered secondary domains only, the primary domain is never stored here),
│       │                            # selfieproxy-localsites-agent-secret,
│       │                            # default-homelab-bootstrapped (marker, see AgentBootstrap),
│       │                            # local-website-demo-bootstrapped / local-website-demo-redirect-bootstrapped
│       │                            # (markers, see LocalWebsiteDemoBootstrap -- independently tracked so
│       │                            # deleting one of the two default Local Websites doesn't recreate it),
│       │                            # sso-signing-key.pem (selfieproxy-identity-provider's self-provisioned RSA key),
│       │                            # theme.json (ThemeStore -- shared Light/Dark UI theme, written by
│       │                            # selfieproxy-portal, read by both it and selfieproxy-identity-provider),
│       │                            # remote-console-settings.json (TerminalSettingsStore -- SSH console
│       │                            # font/theme settings, written by selfieproxy-remote-console's own
│       │                            # settings panel, also read/written by selfieproxy-portal for
│       │                            # configuration export/import)
│       ├── sites/                  # per-domain content roots for Local Websites — see StaticSiteProvisioner
│       └── sites-conf/             # generated NGINX server-block files, one per domain, consumed by selfieproxy-local-websites
├── selfieproxy-check-prerequisites/ # DNS pre-flight check, own Dockerfile — published as selfieproxy-check-prerequisites
├── docker-compose.yaml            # builds and runs selfieproxy-reverseproxy + selfieproxy-portal + selfieproxy-identity-provider + selfieproxy-local-websites + selfieproxy-localsites-agent + selfieproxy-remote-console + selfieproxy-guacd (depends_on selfieproxy-reverseproxy)
├── selfieproxy-reverseproxy/      # forked engine + embedded OIDC Relying Party — subdirectory of this repo, own CLAUDE.md
├── selfieproxy-portal/           # admin portal — Java/Spring, no login of its own (see selfieproxy-identity-provider)
├── selfieproxy-identity-provider/ # bundled OIDC Identity Provider with simplified admin/User management — Java/Spring, same build template as selfieproxy-portal
├── selfieproxy-remote-console/    # browser SSH/RDP/VNC console bridge — Java/Spring, same build template as selfieproxy-portal, pairs with the unmodified guacamole/guacd image
├── selfieproxy-localsites-webserver/ # self-reloading NGINX image for "Selfie Proxy hosts this" static sites
├── THIRD-PARTY-NOTICES.md         # attribution for third-party software distributed alongside this repo's own MIT-licensed code (currently: Apache Guacamole)
└── licenses/                      # full license texts referenced from THIRD-PARTY-NOTICES.md
```

## Running

Host requirement: Linux only (amd64 or 64-bit arm, e.g. a 64-bit Raspberry Pi OS on Pi 3/4/5) — `network_mode: host` rules out Docker Desktop, and only `linux/amd64`/`linux/arm64` images are published (no Windows, no 32-bit arm).

```bash
docker compose up -d --build       # selfieproxy server + admin portal
```

Every service that carries a `HEALTHCHECK` (`selfieproxy-reverseproxy`, `selfieproxy-portal`,
`selfieproxy-identity-provider`) also sets `init: true`, as does `selfieproxy-localsites-agent`
for consistency — none of the four Dockerfiles run tini/dumb-init, so each container's own
process (the Go binary or the JVM) is PID 1. Neither `exec.Command` nor any Java equivalent
exists in these codebases, but Docker's own healthcheck runner still execs a short-lived shell
into the container's PID namespace on every tick and abandons it once the check completes; that
process gets reparented to PID 1 per Linux PID-namespace semantics, and since the app itself
never calls `wait()` on children it didn't spawn, it becomes a permanent zombie. `init: true`
makes compose inject `tini` as the real PID 1 so it reaps them instead. Don't remove this
thinking it's unnecessary — without it, zombies silently accumulate at roughly one per
healthcheck interval (60s for reverseproxy, 10s for portal/identity-provider) for as long as the
container runs.

Every service in `docker-compose.yaml` carries both `image:` (a published `ghcr.io/jeltechnologies/*`
tag, built and pushed by `.github/workflows/docker-publish.yml` on every push to `main`/`v*.*.*` tag)
and `build:` (a local Dockerfile context under this repo). On a `v*.*.*` tag push, that workflow's
`release` job also creates the GitHub Release itself, once every image has finished building —
its notes are just `git log <previous-tag>..<this-tag>` (commit subject + short hash), not
PR-based, since this repo takes commits straight to `main` with no PR/branch workflow. Compose's
default `pull_policy` tries
the registry image first and only falls back to a local build if the pull fails — so a deployer
who only has `docker-compose.yaml` and `.env` (no git checkout of this repo at all) can run
`docker compose up -d` with no `--build` and no source directories present; `--build` is only
needed by someone iterating on source in this checkout (see `feedback_rebuild_after_source_edit`
in memory). This is why `selfieproxy-check-prerequisites/` has its own Dockerfile rather than
being a bare `alpine` image with the script bind-mounted in — a bind mount would require the
script file to exist on disk, defeating the self-contained deploy.

This repo only runs the server side. Agent hosts are not provisioned or run from here — the
admin portal's Agents page is the source of truth for connecting a homelab (it issues the
agent name/secret an operator needs), and guidance for running the agent process itself lives
there, not in a compose file or `.env` template in this repo.

The admin portal only runs alongside the server (it manages that server's tunnels via the
boringproxy REST API), so it's defined as a second service in `docker-compose.yaml`
rather than its own compose file, with `depends_on: selfieproxy-reverseproxy`. The
`selfieproxy-reverseproxy` container's `-portal-domain`/`-portal-port` flags (set from `SELFPROXY_ADMIN_DOMAIN`/`PRIMARY_DOMAIN` and the
selfieproxy-portal's published port, `8081`) make the portal reachable at startup by reverse-proxying
that domain directly to selfieproxy-portal, without going through any Agent/Tunnel — this is what lets
a fresh deployment reach the portal to create its first agent, before any agent exists.

`docker-compose.yaml` also runs two more services, both existing solely to power the
Local Websites feature (see `selfieproxy-portal/CLAUDE.md`): `selfieproxy-local-websites` (the `selfieproxy-localsites-webserver/`
image, a shared NGINX serving every Local Website by `server_name`) and `selfieproxy-localsites-agent`
(an ordinary boringproxy agent, `network_mode: host` like `boringproxy` itself, colocated with
the server instead of a remote network — the "This Server" homelab, deliberately hidden from
the Homelabs page and the Exposed Applications homelab dropdown, since it's not something a
user picks or manages directly). Unlike every other agent, `selfieproxy-localsites-agent` needs no secret
copy-pasted into `.env` — its entrypoint blocks on `data/selfieproxy/selfieproxy-localsites-agent-secret`
existing, a file `ThisServerBootstrap` (`selfieproxy-portal`) republishes on every startup, so
it self-provisions. `selfieproxy-local-websites` is deliberately the last service to start in
the stack — its `depends_on` waits on both `check-prerequisites` (`service_completed_successfully`)
and `selfieproxy-localsites-agent` (`service_started`, the last service in every other service's
dependency chain), so the shared NGINX only comes up once everything upstream of it — DNS
preflight, the OIDC IdP, boringproxy, the portal, and the colocated agent — has already started.

On first boot, `selfieproxy-portal` also auto-creates two default Local Websites through this
same `selfieproxy-local-websites` infrastructure: a demo content site at `www.PRIMARY_DOMAIN`
(bundled with the portal itself, see `selfieproxy-portal/CLAUDE.md`'s "Local websites" section)
and a redirect from the bare `PRIMARY_DOMAIN` to it — see `LocalWebsiteDemoBootstrap`, the same
one-time, marker-file-gated pattern `AgentBootstrap` already uses for the default homelab above,
applied independently to each of the two.

Two further services power the browser SSH/RDP/VNC console feature (the "Terminal Access: SSH"/
"Desktop Access: RDP"/"Desktop Access: VNC" Network Service Modes in the portal, see
`selfieproxy-portal/CLAUDE.md`): `selfieproxy-guacd` (the official, unmodified
`guacamole/guacd` Docker image — the one deliberate exception to every other service here
carrying both `image:` and `build:`, since there is nothing of ours to build) and
`selfieproxy-remote-console` (the WebSocket bridge between a browser and `guacd`, own
Java/Spring module, same build template as `selfieproxy-portal`). Both run `network_mode: host`,
like `selfieproxy-reverseproxy`/`selfieproxy-localsites-agent` — this is load-bearing, not just
consistency: one of these apps' underlying tunnel is created with `AllowExternalTcp: false`
(see `selfieproxy-reverseproxy/CLAUDE.md`'s "Core types" section), which binds its listener to
`127.0.0.1` on the server host rather than `0.0.0.0` — deliberately never reachable from the
internet, only from a process sharing the host's own network namespace. `guacd` itself is
further restricted to bind only `127.0.0.1:4822` (`GUACD_BIND_HOST`), since it has no
authentication of its own and must never be reachable by anything but
`selfieproxy-remote-console` on this same host. The two communicate over that same loopback
interface; `selfieproxy-remote-console` reaches boringproxy's REST API only indirectly (it never
creates/deletes tunnels itself -- the portal does, same as any other Application) and reads/
decrypts credentials from `data/selfieproxy/exposed-apps.json`/`network-service-secret-key`, both
owned and self-provisioned by `selfieproxy-portal` (this service only ever reads them). The browser reaches
`selfieproxy-remote-console` through its own always-SSO-gated, admin-only domain carve-out
(`-console-domain`/`-console-port`, default subdomain `console`) — see
`selfieproxy-reverseproxy/CLAUDE.md`. See `THIRD-PARTY-NOTICES.md` for how Apache Guacamole is
consumed (unmodified Docker image, unmodified Maven dependency, unmodified vendored JS asset) --
unlike `selfieproxy-reverseproxy`, it is not forked.

The server host's `.env` (from `.env.example`) only needs `PRIMARY_DOMAIN` and
`ADMIN_PORTAL_USERNAME`/`ADMIN_PORTAL_BOOTSTRAP_PASSWORD` — now consumed by `selfieproxy-identity-provider`
(the bundled OIDC IdP), not `selfieproxy-portal`, which has no login of its own left. `PRIMARY_DOMAIN` is
fixed for the lifetime of the deployment — it's needed to reach the portal/identity-provider before
anything else exists, so it can never be changed or removed once set. Additional domains ("secondary"
domains internally, never called that in the UI) can be registered later entirely through the portal's
own Domains settings page (`selfieproxy-portal/CLAUDE.md`'s "Domains" section) — they have no `.env`
representation at all, since they're admin-managed runtime state (`data/selfieproxy/domains.json`,
`DomainStore`), not server bootstrap configuration.
`ADMIN_PORTAL_BOOTSTRAP_PASSWORD` is a one-time seed, not a live credential: `AdminUserStore`
bcrypt-hashes it into a persisted admin record (`data/selfieproxy/admin-user.json`) only on first
boot, when no record exists yet, and the first login is forced through a change-password screen
before the admin's OIDC session is issued — after that, the `.env` value is permanently ignored
and the real password only lives in that hashed record. Sibling to that record is
`data/selfieproxy/users.json` (`UserStore`), the list of non-admin Users — login-only identities
that can authenticate against any exposed app protected with single sign on but never the portal (see the "Users"
entry under the admin portal's Settings menu, `selfieproxy-portal/CLAUDE.md`'s Login section). No
bootstrap: the file simply doesn't exist until the admin adds the first user. Like the admin
record, `data/selfieproxy/users.json` is never included in a configuration export/import, and is
irrelevant whenever `OIDC_ISSUER_URL` is set — an external IdP means Selfie Proxy no longer
controls who can authenticate at all, so the Users list itself is hidden from the Settings menu in
that case. Five more
vars are optional, poweruser-only overrides with sensible defaults baked into
application.properties/docker-compose.yaml (both must agree, since they're not read from a
single source of truth): `REVERSE_PROXY_LISTENER_SUBDOMAIN` (default
`proxylistener`, the subdomain boringproxy's admin/tunnel-control plane listens on),
`SELFPROXY_ADMIN_DOMAIN` (default `selfieproxy`, the portal's own subdomain),
`SELFPROXY_AUTH_DOMAIN` (default `auth`, `selfieproxy-identity-provider`'s own subdomain),
`SELFPROXY_CONSOLE_DOMAIN` (default `console`, `selfieproxy-remote-console`'s own subdomain), and
`DEFAULT_HOMELAB` (default `my-homelab`, the name selfieproxy-portal bootstraps a default
agent under on first boot, see the Agents page). Separately, `OIDC_ISSUER_URL`/
`OIDC_CLIENT_ID`/`OIDC_CLIENT_SECRET` (all blank by default) override the admin portal's OIDC
issuer to an external IdP instead of the bundled server — see `selfieproxy-identity-provider/` above
and `selfieproxy-reverseproxy/CLAUDE.md`'s OIDC section. `selfieproxy-portal` also reads
`OIDC_ISSUER_URL` directly (`OidcProperties`, `application.properties`'s `oidc.issuer-url`) to
decide whether to show the topbar user menu's "Users" link at all — the bundled
`selfieproxy-identity-provider`'s `/users` page (which is also where the admin's own username/
password are changed now, from its pinned admin row — there's no separate self-service page for
that anymore) is meaningless once an external IdP is doing the real authentication, so the link is
hidden (`GlobalModelAttributes`) whenever `OIDC_ISSUER_URL` is set; Logout is unaffected since it
always goes through boringproxy's generic `/oidc/logout` carve-out regardless of issuer.
`selfieproxy-identity-provider` also reads `OIDC_ISSUER_URL` itself (its own
`OidcProperties`/`oidc.issuer-url`, not to be confused with `sso.issuer-url`, this server's own
issuer identity) so `AdminUserStore` can skip bootstrapping the admin password record entirely
when it's set — that record would otherwise seed a live admin account (from
`ADMIN_PORTAL_BOOTSTRAP_PASSWORD`, blank by default) that no external-IdP deployment would ever
legitimately need or check. `DEBUG_MODE` (default `false`) turns on
boringproxy's `-debug` per-request access log (timestamp, remote IP, method, host, path) to
stdout — off by default since every agent poll and every Homelabs-page auto-refresh tick would
otherwise log a line. The same flag also gates `selfieproxy-local-websites`' NGINX access log
(`access_log off;` by default, `access_log /dev/stdout;` only when `DEBUG_MODE=true` — see its
`entrypoint.sh`, which generates `/etc/nginx/access_log.conf` from the env var at container
start), since every request to every Local Website would otherwise log a line there too. It also
gates whether `selfieproxy-portal`, `selfieproxy-identity-provider`, and `selfieproxy-remote-console`
send `Cache-Control: no-cache` for their own static JS/CSS
(`spring.web.resources.cache.cachecontrol.no-cache`, each module's own `application.properties`) —
normal browser caching (faster, the default) unless `DEBUG_MODE=true`, in which case every load
force-revalidates so a rebuilt+redeployed container never keeps serving stale JS/CSS to an
already-open tab; `selfieproxy-remote-console` has no `env_file: .env` of its own the other two
get for free, so it needs `DEBUG_MODE` passed through explicitly via `docker-compose.yaml`'s
`environment:` block instead.
`LETSENCRYPT_EMAIL` (blank by default) is passed through as `-acme-email`
to boringproxy — see below. `SSO_SESSION_IDLE_MINUTES`/`SSO_SESSION_MAX_MINUTES` (default `30`/
`600`, i.e. 30 minutes idle / 10 hours absolute, matching Keycloak's own SSO Session Idle/Max
defaults) become boringproxy's `-sso-idle-minutes`/`-sso-max-minutes`, governing the `_selfieproxy_sso`
cookie's sliding idle deadline and absolute cap, and also size `selfieproxy-identity-provider`'s own
login session (`sso.session-idle-minutes`/`sso.session-max-minutes`, `IdpSessionService`) — the
session that makes sign-on actually silent between domains, since a valid one lets a second
single-sign-on-protected domain's authorization round trip skip the login form entirely — see
`selfieproxy-reverseproxy/CLAUDE.md`'s OIDC section. The colocated homelab's name is hardcoded to
`selfieproxy-internal-agent` on both sides (docker-compose.yaml's selfieproxy-localsites-agent service
and selfieproxy-portal's `this-server.agent-name`) rather than even optionally env-configurable,
since the two must always match. Agent hosts are out of scope for this repo entirely — there's
no compose file or `.env` template for them here. An agent connects with just a name and a
server-generated secret (`AGENT_NAME`/`AGENT_SECRET`) issued from the admin portal's Agents
page, plus the boringproxy admin domain to dial (`REVERSE_PROXY_LISTENER_SUBDOMAIN.PRIMARY_DOMAIN` from this
server's `.env`); the portal itself is the source of guidance for running that agent process.
`STEALTH_MODE` (default `false`) disguises every agent's SSH reverse tunnel as HTTPS on port 443
instead of dialing port 22 directly -- for homelabs on networks that only allow outbound `80`/`443`
(corporate firewalls, hotel/campus Wi-Fi, some mobile carriers), which block port 22 entirely.
Rather than a separate protocol-multiplexer process (e.g. `sslh`) sharing port 443 with
boringproxy's own HTTPS listener -- which would need `NET_ADMIN`/TPROXY/iptables to preserve the
real client IP, and would silently reintroduce the client-IP-spoofing problem `-behind-proxy`'s
removal (above) closed off if run in its simpler, non-transparent mode -- this is handled entirely
in-process by `selfieproxy-reverseproxy` (its `-stealth-mode` flag, see
`selfieproxy-reverseproxy/CLAUDE.md`'s "What this is"/"Connection flow" sections for the full
design): the connection is real-TLS-terminated against the admin domain's already-managed cert and
marked with a custom ALPN protocol ID, so it's indistinguishable from ordinary HTTPS to that domain
to any network-level inspection, then the decrypted bytes are piped to the host's real, unconfigurable
sshd on port 22. No new subdomain, DNS record, or cert is needed -- agents already dial the admin
domain for SSH. Toggling `STEALTH_MODE` is a global switch, not a per-homelab setting: every
connected agent picks up the new `ServerPort`/`SshTls` values on its next poll and re-bores all its
tunnels (the same `SyncTunnels` diff-and-restart mechanism already used for any tunnel change).
`docker-compose.yaml` passes `-acme-email "${LETSENCRYPT_EMAIL:-}"` to both boringproxy
invocations (the main server and the colocated `selfieproxy-localsites-agent`, which does its
own independent certmagic issuance into `this-server-certmagic`) — it's optional for ACME/Let's
Encrypt (used only for expiry notices), so `-accept-ca-terms` alone is already enough to issue
certs unattended, and leaving `LETSENCRYPT_EMAIL` unset in `.env` is fine.
`data/reverseproxy/storage` and `data/reverseproxy/certmagic`/`this-server-certmagic` persist the
boringproxy database and TLS certs (server's and selfieproxy-localsites-agent's, kept separate) across restarts;
`data/selfieproxy` persists selfieproxy-portal's own exposed-app records (see ExposedAppStore in
`selfieproxy-portal/CLAUDE.md`-adjacent code — boringproxy's own Tunnel schema can't represent
everything Selfie Proxy needs, eg. homelab protocol), the completely separate Local Website
records (LocalWebsiteStore — Local Websites don't share ExposedAppStore/the Exposed Applications
page at all, by design), the selfieproxy-localsites-agent secret file, and the `sites`/`sites-conf`
directories `selfieproxy-local-websites` also mounts.

## Working on this repo

- Changes to the reverse-tunnel engine itself (tunnel lifecycle, TLS termination, the
  JSON API, the web UI templates) belong in `selfieproxy-reverseproxy/` — see
  `selfieproxy-reverseproxy/CLAUDE.md`.
- Changes to the admin-facing product (login, exposed-app management, homelab
  selection) belong in `selfieproxy-portal/` — see `selfieproxy-portal/CLAUDE.md` for the intended behavior.
- Changes to how the pieces are deployed together (compose files, `.env` shape, volume
  layout) belong at this root level.
