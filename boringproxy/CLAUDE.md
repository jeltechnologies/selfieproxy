# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

boringproxy is a reverse tunnel / reverse proxy manager written in Go. A single binary runs in two roles:

- **server**: runs on a machine with a public IP, terminates HTTP/HTTPS/TLS-SNI on ports 80/443, manages an embedded web UI + JSON API, and issues short-lived SSH keypairs so agents can open reverse tunnels (via the local `sshd`, using `ssh.Listen` on the agent side).
- **agent**: runs on the machine hosting the actual service being exposed, polls the server's `/api/tunnels` for its assigned tunnels, and opens an SSH reverse tunnel (`client.Listen`) for each one, then proxies HTTP/TCP through it to the local service.

The root package `boringproxy` (module `github.com/boringproxy/boringproxy`) contains all core logic; `cmd/boringproxy` is the CLI entrypoint (`main.go`) that parses subcommands and calls into the package.

## Build

```bash
# One-time: logo.png must exist at repo root (embedded via go:embed in ui_handler.go)
./scripts/generate_logo.sh   # requires inkscape; logo.png is already checked in, so usually not needed

cd cmd/boringproxy
go build
# or with version info:
go build -ldflags "-X main.Version=$(git describe --tags)"

# allow binding to low ports (80/443) without root:
sudo setcap cap_net_bind_service=+ep boringproxy
```

`scripts/build.sh` does the build + setcap in one step. `scripts/build_release.sh` cross-compiles for all supported platforms (see `.goreleaser.yml`) and is used for tagged releases.

There are no test files in this repo (`*_test.go` does not exist) and no lint config — `go build ./...` and `go vet ./...` are the available correctness checks.

## Running

```bash
./boringproxy server                                                    # start a server
./boringproxy agent -server example.com -secret <SECRET> -agent-name <NAME> -user <USER>
./boringproxy tuntls -server example.com                                # raw TLS tunnel over stdin/stdout, ported from anderspitman/tuntls
```

Key server flags: `-admin-domain`, `-portal-domain`/`-portal-port`, `-db-dir`, `-cert-dir`, `-http-port`/`-https-port`, `-allow-http`, `-acme-email`, `-acme-use-staging`, `-behind-proxy`.

Key agent flags: `-server`, `-secret`, `-agent-name`, `-user`, `-poll-interval-ms` (min 100ms, 0 disables polling), `-dns-server`, `-behind-proxy`.

On first run with no admin domain, the server prompts interactively (or via the TakingNames.io integration) to set one; on first run with no users, it creates an `admin` user and a corresponding root token, printable via `-print-login`.

## Architecture

### Connection flow (server side, `boringproxy.go`)

A single raw `net.Listener` on the HTTPS port feeds `Server.handleConnection`, which peeks the TLS ClientHello (`sni.go`) to read the SNI hostname *without* consuming the connection, then dispatches based on the tunnel's `TlsTermination` mode looked up by SNI hostname in the DB:

- `client` / `passthrough` / `client-tls`: raw bytes are piped straight through to the client's tunnel port (`passthroughRequest`) — TLS is terminated by the client (or not at all).
- `server-tls`: the server terminates TLS itself and forwards decrypted TCP to the client (`tls_proxy.go`'s `ProxyTcp`).
- anything else (including admin domain / plain HTTP tunnels): handed to `PassthroughListener`, which feeds a normal `http.Serve` loop wired to certmagic for ACME certs, and requests are routed by `Host` header — either to the admin API/web UI, or proxied to the tunnel's local port (`http_proxy.go`'s `proxyRequest`).

### Agent tunnel lifecycle (`agent.go`)

`Agent.Run` registers the agent with the server (`POST /api/agents/`), then loops: `PollTunnels` does a conditional GET against `/api/tunnels` (ETag-based, ETag is an md5 of the tunnel list, so unchanged sets short-circuit) and calls `SyncTunnels`, which diffs the new tunnel map against the current one, starting (`BoreTunnel`) any new/changed tunnels via SSH and cancelling+removing (via per-tunnel `context.CancelFunc`) any that were deleted server-side. Each `BoreTunnel` dials the server over SSH using a private key issued by the server for that tunnel, opens a remote listener, and locally proxies each accepted connection to the agent's actual service (HTTP reverse-proxying for `client` TLS termination, raw/TLS TCP proxy otherwise).

### Core types (`database.go`)

`Database` is a single JSON file (`boringproxy_db.json` in `-db-dir`) guarded by one mutex, persisted synchronously on every mutation (`persist()` calls `saveJson`) — there's no transaction/locking across read-then-write API sequences (a known TODO). Top-level entities: `Tokens` (map of token string -> `TokenData{Owner, Agent}`), `Tunnels` (map of domain -> `Tunnel`), `Users` (map of username -> `User{IsAdmin, Agents}`). A `Tunnel` carries both the routing/auth config (domain, TLS termination mode, optional basic-auth, owner) and the SSH connection details for that tunnel (per-tunnel keypair, tunnel port, allowed external TCP).

### Authorization model

Every access token maps to a `TokenData{Owner, Agent}`. If `Agent` is set, the token is scoped to one agent and can only be used to poll `/api/tunnels`/`/api/agents` for that agent — not to manage tunnels/users/tokens via the web UI or general API. Otherwise the token acts as its `Owner` user, and `User.IsAdmin` gates cross-user operations (creating tunnels/tokens for other users, managing all users). This scoped-token mechanism is also how Selfie Proxy's per-agent secrets work: an agent-scoped token is minted only for an agent name already registered under a user (`Api.CreateToken` refuses otherwise), so an agent's "secret" is just its own restricted access token. This logic is duplicated between `api.go` (JSON API, used by agents and directly) and `ui_handler.go` (server-rendered web UI, which calls into the same `Api` methods) — when changing authorization rules, both call sites matter, though several handlers have `// TODO: handle security checks in api` markers indicating the check still lives in `ui_handler.go` rather than `api.go`.

### Web UI (`ui_handler.go` + `templates/*.tmpl`) -- disabled in Selfie Proxy

Server-rendered via `html/template`, templates embedded with `go:embed`. `WebUiHandler.handleWebUiRequest` is a single big switch over `r.URL.Path` (not a router/mux) sitting behind the same token-cookie/`access_token` auth as the API. Slow operations (tunnel creation, which may block on ACME cert issuance) use a 100ms-timeout + polling `/loading` page pattern with a pending-request map (`pendingRequests`) keyed by a random ID, rather than blocking the initial request indefinitely.

Superseded by Selfie Proxy's own admin portal, this legacy UI is no longer wired up: `boringproxy.go`'s main handler never constructs a `WebUiHandler` and returns 403 for any admin-domain request that isn't under `/api/` or `/rest/` (both of which stay live, since the remote agent depends on `/api/` and selfieproxy-portal's `BoringProxyClient` depends on `/rest/`, both reached over the public admin-domain hostname). The `ui_handler.go`/`templates/` code itself is untouched -- only the routing call site was removed -- so re-enabling it is a one-line change in `boringproxy.go` if ever needed.

### Portal domain (`-portal-domain`/`-portal-port`, `boringproxy.go`)

A second special-cased host, alongside the admin domain: any request whose Host matches `-portal-domain` is reverse-proxied directly to `localhost:<-portal-port>` (via the same `proxyRequest` used for ordinary tunnels, with a synthetic `Tunnel{Domain: portalDomain}` -- no Agent, no SSH bore, no DB Tunnel record). This exists specifically so Selfie Proxy's own admin portal is reachable immediately on server startup, before any agent has ever connected -- the portal itself is where you go to create the first agent and read off its secret, so it can't depend on one already existing. Cert acquisition for this domain happens the same way as the admin domain, via `certConfig.ManageSync` at startup (gated on `autoCerts`).

### TakingNames.io / namedrop integration

Optional DNS automation via `github.com/takingnames/namedrop-go`: lets a user link a domain through TakingNames.io instead of manually pointing DNS at the server, handled via the `/namedrop/callback` route in `boringproxy.go`'s main handler and `Config.namedropClient`.
