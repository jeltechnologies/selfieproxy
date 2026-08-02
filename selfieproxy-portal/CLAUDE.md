# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working on `selfieproxy-portal`,
the admin-facing product. See the root `CLAUDE.md` for how this module fits into the rest of the
repo, and `selfieproxy-reverseproxy/CLAUDE.md` for the underlying tunnel engine this portal manages.

## Product principles (KISS)

- The portal is kept as simple as possible, deliberately hiding complex network setup from the
  user. This is the main design pressure on every UI decision here: prefer fewer steps and fewer
  concepts over configurability.
- The portal itself stays single-operator: one admin account manages many Homelabs, and only that
  admin can ever reach the portal. Selfie Proxy as a whole, though, also supports login-only Users
  (see "Login" below) who can authenticate to Servers protected with single sign on but never the portal --
  keep that distinction in mind rather than assuming every login is the admin.
- A Homelab exposes several web services to the internet as subdomains of the Selfie Proxy domain.
- boringproxy terminology ("Client", "Tunnel") must never leak into the portal UI — it only adds
  confusion for the non-networking audience this product targets. Internally the portal maps its
  own concepts onto boringproxy's (see "Mapping to the boringproxy data model" below), but nothing
  user-facing should say "tunnel" or "client".

## Login

The portal has no login of its own (see root `CLAUDE.md`'s "Running" section for the full
OIDC/env-var picture) — boringproxy gates the portal domain before any request reaches this
container. After a successful login the user lands on the Servers page.

- A Server's Web protocol can opt in to the same single sign on gate (the authentication
  checkbox on its edit page) — available for both HTTP and HTTPS homelab servers, since a Server's Web
  protocol is always end-to-end encrypted (`TlsMode.MANAGED`, boringproxy's "server" TLS
  termination either way -- see `Server.canProtectWithSso()`). The edit page used to expose an
  "Advanced settings" picker letting the admin choose two alternate HTTPS connectivity modes
  (`TlsMode.BYO_CERT`/`HOP_BY_HOP`, which boringproxy never HTTP-parses and so can't gate with
  single sign on); that picker has been removed from the UI since nobody used it, but the
  underlying `TlsMode` enum and `TunnelMapper` mapping are kept as-is in case it's reintroduced
  later -- every Web protocol submitted through the portal today is unconditionally MANAGED.
- The topbar's user menu ("▾ Settings", `fragments/layout.html`) holds a theme toggle button (its
  label flips between "Change to dark mode"/"Change to light mode" depending on the current
  setting, `POST /appearance/toggle`, `web/AppearanceController.java` -- a one-click toggle rather
  than a picker page, since there are only two modes: `domain/Theme`/`domain/ThemeStore`, persisted
  to `data/selfieproxy/theme.json`; the same setting is also read by
  `selfieproxy-identity-provider`'s own read-only `ThemeStore` mirror, applying it to the login/
  change-password/logged-out pages too -- one shared appearance across both services, default Light.
  Toggling redirects back to whichever page the admin was on, using only the path+query of the
  request's `Referer` header, never its host, so this can't be turned into an open redirect. This
  is unrelated to `selfieproxy-remote-console`'s own Dracula/Light/Dark/Solarized Dark xterm.js
  terminal color themes for the SSH console -- that's a separate, independent per-session setting,
  not this shared UI-chrome mode), then every other entry in a fixed order (not alphabetical) --
  "Domains" (`/domains`, `web/DomainsController.java` -- see "Domains" below, always shown, no hide
  condition), "Users" (`/users`, `web/UsersController.java` -- add/edit/remove non-admin Users and
  change any user's password, hidden whenever an external IdP is configured, since Selfie Proxy no
  longer controls who can authenticate in that case), "Export configuration"
  (`/export-configuration`), "Import configuration" (`/import-configuration`) -- with "Log out"
  always pinned last regardless of alphabetical order, since it's a destructive/session-ending
  action, not a settings page, and shouldn't be interleaved with the rest. The Users page shares
  the portal's own look/topbar/logo
  like every other page here, but its data and validation (`UserStore`/`AdminUserStore`/
  `PasswordPolicy`) still live in `selfieproxy-identity-provider` -- `UsersController` is a thin
  client of `InternalUsersController`, identity-provider's own internal-only REST API, reached
  through `IdentityProviderClient` over the Docker bridge network only (never the public domain;
  see root `CLAUDE.md`'s "Running" section for how that API stays unreachable from the internet).
  This split exists because identity-provider is the one that actually checks a User's/the admin's
  credentials on login, so it has to remain the source of truth for that data; only the UI moved
  here. There is no separate "Change username / password" entry: the admin's own username and
  password are changed from the Users page's admin row (Edit / Change password) like any other
  row, rather than through a standalone self-service page -- `selfieproxy-identity-provider`'s old
  `/account` page and `AccountController` were removed once the Users page's admin row covered the
  same ground. Users are never included in a configuration export/import (`BackupService`) -- the
  underlying `users.json`/`admin-user.json` files live in `selfieproxy-identity-provider`'s own
  data directory, the same treatment as the admin account and its RSA signing key. The user-facing
  labels and URLs say
  "export"/"import configuration"; the Java domain types underneath (`BackupService`,
  `BackupController`, `RestoreSelection`, `RestoreResult`, templates named `backup.html`/
  `restore.html`/`restore-picker.html`) keep the shorter "backup"/"restore" naming (see "Backup
  and restore" below), the same kind of internal-vs-UI naming split as Homelab/Agent. Unlike that
  pair, "Server" is no longer split from its internal Java name -- `Server`/`ServerStore`/
  `ServerController` match the user-facing term directly (see "Mapping to the boringproxy data
  model" below for what's still split: the underlying BoringProxy Tunnel). Log out ends the portal's own session and clears boringproxy's single sign on cookie for
  the portal domain, landing on a confirmation page served by `selfieproxy-identity-provider` with
  a link back into the portal — which immediately requires logging in again, since both session
  and cookie are gone.

## Domains

- Selfie Proxy always has one **primary domain** (`PRIMARY_DOMAIN` in `.env`), fixed for the life of
  the deployment — it's needed to reach the portal/identity-provider before anything else exists, so
  it can never be renamed or removed. The admin can register any number of additional domains
  afterward through the "Domains" Settings-menu page (`/domains`, `web/DomainsController.java`) --
  called "secondary domains" internally (`DomainStore`/`DomainService`, `data/selfieproxy/domains.json`)
  but never described as "secondary" anywhere in the UI, since the user only ever sees "a domain", not
  a primary/secondary hierarchy.
- The Domains list shows the primary domain first (labeled "Primary", no Edit control -- it's not a
  row this page can act on), then every registered domain alphabetically, each with a status: **OK**
  when its DNS resolves to this server's own address, **Error** otherwise (`DomainService.statusOf`,
  reusing the same IP-resolution trick `check-prerequisites.sh` relies on at startup -- resolve the
  primary domain to get "this server's IP", no external lookup needed). Adding a domain only checks
  its syntax and uniqueness, not that its DNS is already correct -- that's exactly what the status
  column is for after the fact.
- Editing a domain's page shows a plain-language explanation of where its DNS currently points versus
  where it should, above the rename field, whenever its status is Error (`DomainService.dnsExplanation`).
  Renaming a domain **cascades**: every Server/Local Website already using it gets its tunnel
  recreated under the new domain (the same delete-tunnel-then-recreate-with-a-2s-wait pattern an
  ordinary edit already uses, just applied in bulk -- brief downtime for the affected items, which is
  accepted since this is a deliberate admin action), and a Local Website's content directory/NGINX
  config moves with it. A failure on one item is recorded and never blocks the rest of the rename.
  Removing a domain has **no cascade at all** -- any Server/Local Website still using it keeps
  working exactly as before, just flagged with a warning on its own list row (see "Servers"/"Local
  websites" below), since Selfie Proxy no longer tracks that domain but the
  underlying tunnel is untouched.
- Every place a domain is chosen (Add Server, Add Local Website's "Subdomain of ..." mode, the
  import wizard's per-item domain picker) lists the primary domain first, labeled e.g.
  `example.com (primary)`, then every other registered domain alphabetically with no special label.

## Homelabs

- Each exposed web service is bound to one subdomain of whichever domain it's assigned to (see
  "Domains" above), composing a FQDN automatically (subdomain `music` on domain `example.com` becomes
  `music.example.com`). The subdomain is optional -- leaving it blank exposes the service at the bare
  domain itself (`example.com`), including the primary domain, since nothing else listens on it bare
  (the fixed `proxylistener`/`selfieproxy`/`auth`/`console` subdomains are all subdomains *of* the
  primary domain, never the primary domain itself).
- The Homelabs page and the Servers page are two views over related data, not two
  separate concepts: a Homelab corresponds to one Agent (see Agents page below) and is managed
  there, not on the Servers page.

## Agents

- Agents are the connecting boringproxy-agent processes (one per Homelab) that open tunnels back
  to the Selfie Proxy server. Each has a name chosen by the user and a secret generated by the
  server.
- The Agents page lists all agents. For each, the user can view/reveal its current secret,
  generate a new one (invalidating the old), rename the agent, or remove it.
- A default agent (name `my-homelab` unless overridden via `DEFAULT_HOMELAB`) is created
  automatically the first time the portal starts, with a freshly generated secret (see
  `AgentBootstrap`).
- The boringproxy server only accepts connections from agents already in this list — an agent's
  secret is a boringproxy access token scoped to that agent's name, so it can't be used to act as,
  or register, any other agent.
- To deploy an agent, the user copies its name and secret from this page and runs the agent
  process on the homelab host — guidance for doing so comes from the portal itself, not from a
  compose file or `.env` template in this repo. Agent host requirement: Linux only (amd64 or
  64-bit arm), with outbound internet access — the generated connect snippet (`agents.js`'s
  `renderConnectInfo`) runs the agent container with `network_mode: host`/`--network host` so it
  inherits the homelab host's own `/etc/resolv.conf` and can resolve the homelab's local-DNS
  hostnames; this rules out Docker Desktop (macOS/Windows), same as the server's own host
  requirement. Unlike the server role (which needs root for `/root/.ssh` and low-port binding, and
  stays root), the agent doesn't need to run as root at all -- confirmed live against a real
  homelab. The generated snippet shows `--user 1000:1000`/`user: "1000:1000"` as a **commented-out,
  optional** line rather than an applied default (`# user: "1000:1000"  # Optional, replace with
  your own UID:GID to avoid running as root.`), since any UID/GID works equally well here: the
  snippet has no volume for anything to need matching ownership against (see below), so `1000` was
  never actually load-bearing for this role -- it's simply a convenient default matching the
  `boreagent` uid `1000` baked into the image (`Dockerfile`), which exists for this optional use
  here; the colocated `selfieproxy-localsites-agent` (root `CLAUDE.md`'s "Running" section) runs as
  root instead, since it's trusted server-side infrastructure, not a user-run homelab agent --
  operators are free to pick whichever UID:GID they already use on their own homelab host. The snippet has no volume at all: the agent
  role's old `-cert-dir` flag was removed entirely (`cmd/boringproxy/main.go`, no longer even
  parsed for the `agent` subcommand) rather than just hardcoded, once tracing `agent.go` confirmed
  its only write (`certConfig.ManageSync`, gated on `Tunnel.TlsTermination` being
  `"client"`/`"client-tls"`) can never actually fire in this product -- `TunnelMapper` (the single
  place every tunnel the portal creates goes through) only ever emits `"server"`/`"passthrough"`,
  and the old `TlsMode.BYO_CERT`/`HOP_BY_HOP` enum that would have produced those other two values
  is gone from the Java codebase entirely, not merely hidden behind a removed UI picker. So the
  agent binary never writes a certificate anywhere in this product -- only the *server* role does
  (its own separate `-cert-dir`, `boringproxy.go`, unrelated flag/code path) -- and there's nothing
  for a volume to back.

## Servers

A Server is not a single-protocol thing -- one Server can simultaneously expose up to four
independent protocols (Web, Terminal, Remote Desktop, Port Forwarding, see "Editing, adding, and
removing a Server" below), each becoming its own boringproxy tunnel (`TunnelMapper`). There is no
"Type"/"Mode" choice anymore (the old Web-server-vs-Network-service, TCP/SSH/RDP/VNC-exclusive
model, and the `NetworkServiceLabel`-derived internal Name it required, are gone) -- every protocol
is just an independent enable checkbox on the same Server, sharing one Homelab and one homelab-side
host/IP.

- The top of the page manages Homelab selection: a dropdown of Homelab names in alphabetical
  order, first one auto-selected. The user cannot add or delete Homelabs from this page.
- Servers are listed with sortable column headers (Domain, Homelab, Local address --
  click any header to sort ascending/descending, plain client-side JS,
  `static/js/sortable-table.js`, no server round-trip), and two independently remembered filter
  dropdowns side by side above the table (`DomainFilterPreferenceStore`, persisted across page
  loads and logins): "Filter by domain" (populated from every registered domain, see "Domains")
  and "Filter by homelab" (populated from every Homelab). Each row shows the Domain column as the
  Server's full fully-qualified domain name (`server.fqdn()` -- subdomain plus domain, or just the
  bare domain if the subdomain is blank; there is no separate Name column anymore, and no separate
  user-entered Name field), with a warning icon if that domain was since removed from the Domains
  page (the Server keeps working, it's just orphaned from Selfie Proxy's own domain registry) and/or
  a second warning icon if the Server's homelab no longer exists, then the Homelab, the address
  within the homelab, then three independent columns -- **Web**, **Terminal**, **Remote desktop**
  -- each showing a **Connect** button only when that Server has that protocol enabled (blank
  otherwise; Port Forwarding is never shown as a column here, its entries stay hidden). Web's
  Connect opens the Server's own public URL directly in a new tab; Terminal's/Remote desktop's
  open the browser console flow instead (see "Connecting to a Server's Terminal or Remote Desktop"
  below). There is no separate "not exposed to the internet" indicator anywhere on this list, since
  which Connect buttons a row shows already makes that obvious. Whenever a row's Status dot is red
  (`serverStatusMessage` -- homelab disconnected and/or a DNS mismatch, see `DashboardController`),
  every Connect button in that row stays visible but is disabled (`aria-disabled="true"` +
  `tabindex="-1"`, `.button-small[aria-disabled="true"]` in style.css -- an `<a>` has no native
  disabled state, and removing `href` instead would also drop its title/styling context) rather
  than hidden, so the row still reads the same at a glance either way.

### Editing, adding, and removing a Server

The edit page has one shared section, then one independent fieldset per protocol, each with its
own enable checkbox -- toggling one on/off never touches the others' fields or their live tunnels
(see `ServerController.syncTunnels`):

- **Shared fields**: **Homelab** (dropdown), **IP or hostname to homelab server** -- one value
  used by every protocol this Server enables, echoed read-only inside each protocol's own fieldset
  next to its own Protocol/port fields (`.host-echo`, kept live via JS as the shared field changes).
- **Web**: HTTP/HTTPS **Protocol** dropdown (defaults its port to 80/443 unless the admin already
  typed a custom one, `bindProtocolPortFollow` in `edit-server.js`), **Homelab server port**, and a
  "Protect by forcing authentication through Selfieproxy login" checkbox (`webSsoProtected` -- see
  "Login" above). **Subdomain**/**Domain** (dropdown, primary domain first) live in the shared
  top-of-form row, composing the FQDN shown live as a label -- they only matter for Web, since
  every other protocol lives on its own hidden, never-shown FQDN (see below) regardless of what
  Subdomain/Domain are set to.
- **Terminal**: always SSH, no Protocol choice. **Homelab server port** (default 22), **Username**
  (optional) and **Password** side by side -- always a real `<input type="password">`, no
  view/reveal button, no private-key option. On edit, the password field shows a `••••••••`
  placeholder when a credential is already stored (a fixed decoy, never the real password) and no
  placeholder otherwise, so it's visually obvious whether one exists without exposing it; leaving
  it blank on edit keeps the previous credential, leaving it blank when adding is allowed too (see
  "Connecting" below for what happens then). Username and Password mirror live into Remote
  Desktop's own Username/Password fields and vice versa (`bindMirrored` -- the common case is the
  same login on the same machine for both).
- **Remote Desktop**: RDP/VNC **Protocol** dropdown (defaults port 3389/5900 the same way Web's
  does), **Homelab server port**, Username/Password (as above). There is no "Accept a self-signed
  certificate" checkbox anymore -- `RemoteDesktopConfig.ignoreCertificate` is hardcoded `true` for
  every Remote Desktop Server (RDP/VNC targets are essentially always self-signed, e.g. Windows'
  own default RDP certificate, so there was never a real choice to expose).
- **Port Forwarding**: up to 8 individual forwarded TCP ports -- never a range, each is its own
  boringproxy tunnel (`MAX_PORT_FORWARDING_ENTRIES` in `ServerController`). Shown as a small table
  (`Homelab server port` | `Port exposed to the internet` | `Description (optional)` | blank),
  modeled on a typical router's port-forwarding page: each already-added row is read-only with a
  **Remove** button (no confirmation needed -- the page's own Cancel already covers "I didn't mean
  to change this"); a trailing blank row is always directly submittable as-is (typing into it and
  clicking the form's OK works without ever clicking the row's own **Add**), and clicking **Add**
  just locks that row read-only and appends a fresh blank row unless the 8-entry cap is reached.
  The Description column (`PortForwardingConfig.description`) is a free-text, per-entry label
  (e.g. "Minecraft server") -- purely cosmetic, optional, never validated or checked for
  uniqueness, and included as-is in a configuration export/import (`BackupService`) alongside the
  rest of the entry. Every port field is
  constrained to 1-65535 (`min`/`max`/`step` plus server-side `validatePortRange`); Add and direct
  submit both block, client-side, a homelab-server-port or public-port value that duplicates
  another row already in the table, ahead of the authoritative server-side check. Two static
  warnings are shown above the table: using this is at the admin's own risk (anyone scanning the
  domain can see the port is open), and to make sure the port is open in the firewall too. Always
  TCP -- no protocol choice, since nothing else exists yet (`PortForwardingProtocol`).

Terminal's and Remote Desktop's tunnels each live on an internal, never-shown FQDN under the
*primary* domain (`allow-external-tcp: false`, see `selfieproxy-reverseproxy/CLAUDE.md`'s "Core
types" section) -- there's no Domain/public-port concept for them at all. That hidden FQDN's
subdomain is a derived, collision-free label (`HiddenTunnelFqdnAssigner`/`HiddenTunnelLabel`) built
from the Server's own FQDN plus a fixed suffix -- `-terminal` / `-remotedesktop` -- rather than the
homelab-side port number the way it briefly used to be: the reverseproxy agent's tunnel-lifecycle
logs print every tunnel's FQDN unconditionally (no debug gate), and these FQDNs get real,
publicly-logged Certificate Transparency entries too, so embedding the port (e.g. `...-3389....`)
would leak which service a homelab is running to anyone who can read either. Port Forwarding's
hidden FQDN keeps its numeric suffix (the public port itself) instead, since that port is already
meant to be public and the number is what keeps multiple entries on the same Server distinct from
each other.

### Connecting to a Server's Terminal or Remote Desktop

The Servers list's Terminal/Remote desktop columns show a **Connect** button whenever that
protocol is enabled on the row's Server, opening `https://console.<domain>/connect/<hidden fqdn>`
in a new tab -- a live browser session served by the separate `selfieproxy-remote-console` service
(Apache Guacamole, consumed unmodified -- see `THIRD-PARTY-NOTICES.md`), paired with the
`selfieproxy-guacd` container. If no credential has ever been stored for that protocol on that
Server (left blank when adding/enabling, or arrived via a configuration import -- imports never
carry a password, see "Backup and restore" below), Connect instead opens a portal page
(`ConsoleConnectController`) prompting for one; submitting it encrypts and saves the credential
(`NetworkServiceCredentialCipher`, AES-256-GCM, key self-provisioned into
`data/selfieproxy/network-service-secret-key` the first time it's needed, same idiom as
`selfieproxy-identity-provider`'s `sso-signing-key.pem`) and proceeds straight into the session --
every later Connect skips that prompt. `selfieproxy-remote-console` only ever reads
`servers.json`/that key (shared `/data` volume) at connect time, never writes either --
credential entry always goes through the portal, keeping a single writer for the whole file.

Every Server's Web protocol is always end-to-end encrypted (`TlsMode.MANAGED`) with no user-facing
choice about it -- see the "Login" section above for why, and for the two alternate connectivity
modes (`TlsMode.BYO_CERT`/`HOP_BY_HOP`) this used to expose through an "Advanced settings" picker.

Button panel: Cancel (returns to the list, no changes), OK (add/update), Remove (edit only, red
background/white text, asks for confirmation in an overlay first).

Validation (`ServerController.validate`): before adding, check the subdomain isn't already taken on
the chosen domain (case-insensitive). At least one protocol must be enabled. Every port field
(Web/Terminal/Remote Desktop's homelab-side port, Port Forwarding's homelab-side and public ports)
must be 1-65535. Port Forwarding additionally requires: no more than 8 entries; each entry's
homelab-side port unique across this Server's own forwarded ports; each entry's public port not
reserved (<=1023, system services), unique across this Server's own forwarded ports, and unique
across every exposed port on this entire Selfie Proxy instance (one physical server, one port
namespace) -- checked against live boringproxy tunnels, excluding this Server's own previous
Port Forwarding FQDNs so editing an entry never collides with the tunnel it's about to replace.
`proxylistener`/`selfieproxy`/`auth`/`console` (or their env overrides,
`REVERSE_PROXY_LISTENER_SUBDOMAIN`/`SELFPROXY_ADMIN_DOMAIN`/`SELFPROXY_AUTH_DOMAIN`/
`SELFPROXY_CONSOLE_DOMAIN`) are reserved and cannot be used for a user's own Servers -- but only
when the primary domain is selected, since those reserved subdomains are hardcoded to the primary
domain alone (`docker-compose.yaml`); the same label under any other registered domain is a
perfectly ordinary, unreserved Server. Updating a Server diffs each protocol's (and each Port
Forwarding entry's) desired tunnel against what's already live (`ServerController.syncTunnels`) --
an unchanged protocol/entry is left completely alone, a removed one is deleted, an added or changed
one is deleted-then-recreated after a 2-second wait (no in-place tunnel update); only one 2-second
wait total is paid per save no matter how many protocols/entries actually changed.

## Local websites

Static sites Selfie Proxy hosts itself, entirely independent of the Homelab/Server concept —
no user-run address behind them, no Homelab to pick, just a domain. See root `CLAUDE.md` for the
`selfieproxy-local-websites`/`selfieproxy-localsites-agent` infrastructure behind this feature;
this section is the portal-side UI behavior.

- Two default Local Websites are created automatically the first time the portal ever starts
  (`LocalWebsiteDemoBootstrap`): a "Local website demo" content site at `www.PRIMARY_DOMAIN`,
  populated from `local-website-demo.zip` -- a single classpath resource the build zips up from
  `src/main/resources/local-website-demo/` (see `pom.xml`), so the demo content ships inside the
  portal's own Docker image with no separate download step -- and a redirect from the bare
  `PRIMARY_DOMAIN` to it. Like the default homelab (see "Agents" above), each is a one-time,
  independently-tracked bootstrap: deleting the demo content site does not bring it back on a
  later restart, deleting the redirect does not bring *it* back either, and deleting one has no
  effect on whether the other still exists. Because the bundled zip is baked into the jar at
  build time, a *fresh* install (no `local-website-demo-bootstrapped` marker yet) always gets
  whatever demo content shipped in the image it's running; an *existing* install upgrading to a
  newer image tag does not get its already-bootstrapped `www` site's content touched, so an
  admin's own edits to it are never silently overwritten by an image upgrade.
- While the demo content site is still present and unmodified, the list page shows a `.notice`
  banner (`LocalWebsiteDemoStatus`, visually distinct from the `.warning` cert-pending banner above
  -- a friendly heads-up, not a problem to act on) naming the demo site and, if it too is still
  unmodified, the apex redirect, saying both are safe to delete or replace. "Still unmodified" is a
  plain `LocalWebsite.demo()` boolean -- `LocalWebsiteDemoBootstrap` sets it true when it creates
  the site, `LocalWebsiteController` clears it the instant a ZIP is uploaded to it or the redirect
  target/mode is actually changed (this also covers the export/`manifest.json`'s own `demo` field,
  since `BackupService.buildManifest` serializes the stored records as-is); a rename or a domain
  rename (`DomainsController`) alone carries it forward unchanged, since neither touches the
  content/target itself, and a configuration import (`BackupService`) always forces it false
  regardless of what the export said, since "demo" is a single-server, single-bootstrap concept that must never follow an
  export onto a different server. This is a cheap field read on every list-page load, not a
  filesystem comparison -- editing the content directory directly (e.g. via `docker exec`, see
  below) isn't a documented user workflow, so it isn't accounted for and won't clear the flag. The
  banner independently stops mentioning the redirect the moment that's been repointed or removed,
  via its own separate (also flag-free) check against the redirect's stored target.
- The nav has a "Local websites" tab next to Servers. The list page shows every site's domain
  (opens in a new tab, with a warning icon if its chosen domain was since removed from the Domains
  page -- same treatment as the Servers page), an Edit button, and a Download button (streams
  the content directory as a ZIP, see `StaticSiteProvisioner.writeZip`) -- with the same
  sortable-column-headers and domain-filter treatment as the Servers page above.
- Adding one: a subdomain-label text field plus a dropdown of every registered domain, same
  ordering/labeling as a Server's domain dropdown -- composes `<subdomain>.<chosen domain>`.
  The label is optional, same as a Server's Subdomain field -- leaving it blank serves the
  site at the bare domain itself. Exactly like a Server, there's no way to point a Local
  Website at a domain that isn't registered on the Domains page first -- if a user needs one on a
  domain Selfie Proxy doesn't already know about, they register that domain there first, then add
  the website as a subdomain (or the bare domain) of it.
- Renaming one: change the subdomain and/or domain on the edit page. The tunnel is recreated under
  the new FQDN and the site's files are moved to the new folder — nothing is lost.
- Uploading a ZIP (add or edit page): replaces the site's entire content directory --
  `StaticSiteProvisioner.replaceContents` extracts the upload into a staging directory first and
  only swaps it in (a same-filesystem directory rename) once extraction fully succeeds, so a bad
  upload never touches the existing files. The edit page shows a warning that this is destructive;
  the add page doesn't, since there's nothing to lose there.
- Removing one: takes it off the internet (tunnel and NGINX config deleted) and permanently
  deletes its content directory from the server -- destructive, cannot be undone. Adding the same
  domain again later starts from an empty folder.
- Files live at `data/selfieproxy/sites/<domain>/` on the server, owned by the portal container's
  user — copy files in as root, or via `docker exec selfieproxy-portal`.
- **One warning** on the list page (`LocalWebsiteController.list`), recomputed live on every page
  load, no caching: a cert-pending banner (mirrors the Servers page's own, but scoped to This
  Server's tunnels instead of excluding them, since every Local Website tunnel belongs to the hidden
  "This Server" homelab) shown whenever boringproxy is still retrying Let's Encrypt for a site
  (self-signed cert served in the meantime -- expected and fine, not itself a bug). There's no
  separate DNS-mismatch check here -- a Local Website's domain is always a registered one, so its
  DNS correctness is already tracked centrally on the Domains settings page instead (see "Domains"
  above), exactly like a Server.
- **Type: Serve website files (default) or Redirect to another address** (`LocalWebsite.redirectTo`,
  blank/null for the default content mode, same "presence implies special mode" idiom the domain/
  subdomain fields already use for apex). Redirect mode has `StaticSiteProvisioner` write an NGINX
  `return 301 <redirectTo>$request_uri;` block instead of `root`/`index` -- the tunnel, cert, and
  domain machinery are identical to content mode, only the generated server block differs. The
  primary use case is a bare/apex domain redirecting to its own `www.` subdomain (e.g.
  `nordicsnerd.com` → `https://www.nordicsnerd.com`), but the target can be any registered or
  external domain. The target must be a bare `scheme://host` with no path/query/fragment
  (`RedirectUrlValidator`) -- NGINX appends the visited path onto it verbatim via `$request_uri`,
  so allowing a path in the target would make that concatenation ambiguous; this is meant to point
  a whole domain elsewhere, not rewrite individual URLs. A site can't be set to redirect to itself.
  Switching an existing site from content to redirect mode leaves its uploaded content directory
  untouched on disk (nothing to lose if switched back later) but hides the Download button and
  ZIP-upload field while in redirect mode, since there's nothing to download or replace. The target
  is picked either from a dropdown of every currently deployed Server exposing a Web protocol and other Local
  Website (`LocalWebsiteController.redirectTargets` -- excludes the site being edited itself, and
  any Server with no Web protocol enabled, since those have no public web address to redirect to) or typed as a
  custom address -- the two share one underlying `redirectTo` value, so which UI mode was used to
  set it doesn't change how it's stored or applied.

## Backup and restore

**Value to the user**: back up their configuration, or move it to another Selfie Proxy server.
Everything below is how that's done -- a single ZIP covering every Homelab, Server, and Local
Website (including each site's actual files), and an import wizard that reviews what's new versus
what already exists, item by item, before anything is applied.

"Export configuration" and "Import configuration" are two separate pages, each its own entry in
the topbar's Settings menu (not a nav tab) -- exporting and importing are different enough
workflows (one reads live state, the other stages an upload and steps through a picker) that they
don't share a page anymore, even though they share most of their underlying selection machinery.
User-facing text says "export"/"import configuration", and their URLs follow suit
(`/export-configuration`, `/import-configuration`); the Java types underneath keep the shorter
"backup"/"restore" naming (`BackupService`/`BackupController`, `RestoreSelection`/`RestoreResult`)
-- see the "Login" section's note on this split. Together the two pages cover every Homelab,
Server, and Local Website (config *and* its actual
content files) -- usable both for disaster recovery on the same server and for moving to a brand
new one. Each Server/Local Website already carries its own domain (`Server.domain()`/
`LocalWebsite.domain()`, see "Domains" above), so it flows into `manifest.json` for free; a restore
onto a different server doesn't assume the two servers share the same domain -- see the Servers/
Local Websites wizard steps' per-item domain picker below. `BackupService` does the work;
`BackupController` is the thin web layer for both pages.

- **Export configuration page** (`GET /export-configuration`): three flat checkbox lists over
  *live* server state, in a fixed order -- Homelabs, then Servers (each entry showing its own
  Homelab name, since Servers aren't nested/grouped under one), then Local Websites -- all pre-checked,
  plus "Select All"/"Select None" buttons above the lists (`backup.js`, targeting every checkbox
  under `#backup-form` at once). Submitting the form (`GET /export-configuration/download`, a plain
  query-string GET since it only reads state) streams a ZIP containing only what's checked:
  `BackupService.buildManifest` builds the full picture from live state, `BackupService.filterManifest`
  narrows it down to the submitted selection before it's serialized and zipped.
- **What's included** in a selected item: Homelab names; each selected Server's full settings
  (the same stored record `ServerController` itself edits, read directly from
  `ServerStore`) -- including every enabled protocol's own settings, and, for Terminal/Remote
  Desktop, their username, but never their stored credential (see below); each selected Local Website's settings
  (`LocalWebsiteStore`) plus its content directory, zipped under `local-websites/<fqdn>/` alongside
  a root-level `manifest.json` describing everything else. `manifest.json` is pretty-printed
  (Jackson `INDENT_OUTPUT`) since it's meant to be readable/hand-editable before an import, not
  just machine-consumed. Two more things are **always** included and applied, unconditionally --
  no checkbox, no wizard step, the same treatment `sourcePrimaryDomain`/`createdAt` already get,
  since a single global setting has no "pick some, not others" selection concept: the shared
  Light/Dark UI theme (`ThemeStore`, `manifest.theme`) and the SSH console's font
  size/font family/color theme (`selfieproxy-remote-console`'s `TerminalSettingsStore`, mirrored
  read/write here as `domain/TerminalSettings.java`/`TerminalSettingsStore.java`,
  `manifest.terminalSettings`) -- restoring either overwrites the target server's current setting.
- **What's deliberately excluded, always**: a Homelab's secret (its boringproxy access token) is
  never exported. Importing a Homelab that doesn't already exist on the target server always mints
  it a **brand-new** secret -- the import wizard's Homelabs step warns about this per item (only
  for the ones flagged New, see below), and the operator must re-paste the new secret into that
  homelab's `.env` afterward. A Server's Terminal/Remote Desktop encrypted credentials are never
  exported either (`Server.withoutSecrets`, `BackupService.buildManifest`) -- they're encrypted
  with a key that never leaves this server (`NetworkServiceCredentialCipher`), so exported
  ciphertext would be undecryptable elsewhere; importing a Server with Terminal and/or Remote
  Desktop enabled lands each in the same "no credential stored yet" state as freshly enabling one
  with a blank password, prompting for it on the first Connect (see "Servers" above). Also excluded:
  `selfieproxy-identity-provider`'s admin account and RSA signing key -- a configuration export
  must never be able to grant login access to a different server, so import never touches
  server-local auth material, only goes through the same `BoringProxyClient` REST calls the rest
  of the portal already uses.
- **Import configuration page** (`GET /import-configuration`): just the upload form -- a bare file
  input and a "Continue" button, no fieldset box around it, subtitle "Upload an exported
  configuration ZIP. In the next steps you choose what to import from this ZIP file." (plus any
  errors/result flashed back from the flow below). Uploading a ZIP (`POST
  /import-configuration/stage`) extracts it into a staging directory and validates `manifest.json`
  before anything live is touched, then redirects into a review wizard (`BackupController`'s
  `homelabsStep`/`serversStep`/`localWebsitesStep`/`overviewStep`, templates
  `restore-homelabs.html`/`restore-servers.html`/`restore-local-websites.html`/
  `restore-overview.html`): Homelabs, then Servers (the exposed-servers step -- user-facing
  copy says "Servers"/"servers" throughout, never "exposed server(s)"; the Java
  identifiers/JSON fields stay `servers` etc., this is display wording only), then Local
  Websites, then Overview -- except a category step is skipped entirely when the staged export has
  nothing in that category (`BackupController.firstStep`/`nextStep`/`previousStep`, checked
  against `manifest.homelabs()`/`servers()`/`localWebsites()` being empty), so the wizard's
  total step count and which category is effectively "first" both vary with what's actually in the
  ZIP -- an export with only Servers goes straight from upload to the Servers step,
  skipping Homelabs, and each step's "Step N of ..." label (`BackupController.stepNumber`/
  `totalSteps`) reflects the actual count, not a hardcoded 5. Each step's subtitle names the
  action, not just the category -- "Select the homelabs to import" / "Select which servers to
  import" / "Select which local websites to import" -- and the source domain/created-at from the
  manifest isn't shown per step at all, it's informational only and adds noise to a page the admin
  will click through repeatedly. Each surviving category step lists every item from the staged
  manifest for that category with a checkbox (unchecked by default -- the admin actively picks
  what to import, item by item, rather than starting from an implicit "everything selected") and a
  New/Existing status badge computed against live state (`BackupService.diffManifest`, against
  `boringProxyClient.listAgents()`/`ServerStore.find`/`LocalWebsiteStore.find`; this badge is
  computed against the ZIP's own domain and doesn't live-update if the domain picker below is
  changed, an accepted minor simplification). The Servers step and the Local Websites step
  also show a per-item domain `<select>` (same ordering/labeling as
  the Add Server page's -- primary domain first, then every other registered domain
  alphabetically) defaulting to the ZIP's own domain if it's still registered on this server, else
  the primary domain (`BackupController.targetDomainsForServers`/`targetDomainsForSites`) -- this is what
  lets a restore land on a domain other than the one the export was originally taken from. Each
  step's chosen domains are carried forward to the next as `domain__<fqdn>` hidden fields (the
  wizard's stateless carry-forward idiom, extended one step further), plus a Select
  All/Select None button pair above the list (`restore-wizard.js`, mirrors the export page's own
  `backup.js` pattern, scoped to that step's own `#wizard-form`) -- the Homelabs and Servers
  steps show their list plain with no box around it, the Local Websites step still wraps its list
  in a fieldset. None of the category steps show a warning next to New/Existing items -- that's
  reserved entirely for the Overview step, which summarizes everything actually selected (a
  category section is omitted entirely, not shown empty, when nothing was picked in it) with the
  same New/Existing badges plus the actual contextual warning per item: a **New** Homelab warns
  it'll get a brand-new secret (see above); an **Existing** Server warns "This server
  configuration will be overwritten"; an **Existing** Local Website warns it'll be replaced -- New
  Servers/Local Websites and Existing Homelabs get no warning, since nothing unexpected
  happens to them. If nothing was selected in any category, the Overview step shows only "Nothing
  to import." in place of the category sections and the cannot-be-undone warning. The tunnel
  delete-then-recreate mechanics an import actually performs are deliberately not mentioned in any
  wizard copy -- too technical for this audience (see "Product principles" above). Each step's
  selection carries forward statelessly via GET query params/hidden fields (no server-side
  session) as the admin clicks Next; Previous reconstructs the prior surviving step's upstream
  selections from those same params, though a step's own checkboxes reset to unchecked on a fresh
  render rather than preserving exact prior state -- an accepted simplification, since the common
  path is upload-then-review-forward, not repeated back-and-forth. Only one file can be staged at a
  time, so on whichever category step ends up effectively first (`previousUrl == null`), Previous
  is rendered as a submit button targeting the same `cancel-form` every step already has for
  Cancel, rather than being hidden -- clicking it abandons the current staged file (same effect as
  Cancel) and returns to the upload step so the admin can pick a different ZIP. The Overview step's
  final warning (when something was actually selected) is that importing cannot be undone.
  Applying (`POST /import-configuration/{stagingId}/apply`, fed
  by the Overview step's hidden fields) recreates each selected Server/Local Website's tunnel
  (the same delete-then-recreate-with-a-2s-wait pattern an ordinary edit already uses, just applied
  in bulk -- brief downtime for that homelab's users) at whichever domain its picker step chose
  (`BackupService.doApplyRestore` substitutes the ZIP's own domain with the picked one before
  building the tunnel request -- a Local Website's content directory, staged under the ZIP's
  *original* domain, is restored to the *new* one if they differ) and creates each selected new
  Homelab with a fresh secret; existing Homelabs are left untouched. A failure importing one item is
  recorded and never aborts the rest of the import. The staging directory is removed once the import completes
  or is cancelled (`POST /import-configuration/{stagingId}/cancel`, available from every wizard
  step), and either action redirects back to `/import-configuration`.
- **Download filename**: `selfieproxy-config-export-<domain>-<timestamp>.zip`, where the timestamp
  reflects the *browser's* local timezone, not the server's -- `backup.js` reads
  `Intl.DateTimeFormat().resolvedOptions().timeZone` and fills a hidden `tz` field on the export
  page's form with it, which `BackupController` validates as a real zone id before use (falling
  back to UTC otherwise). The same resolved zone is reused for the manifest's own `createdAt`
  field (millisecond precision, ISO-8601 with UTC offset and zone id, e.g.
  `2026-07-19T14:32:10.123+02:00[Europe/Amsterdam]`) -- both are the browser's local time, not the
  server's.

## Mapping to the boringproxy data model

This portal is built on a forked BoringProxy (`selfieproxy-reverseproxy/`), enhanced with
WebSocket support, a REST API, client connections without certificates, and `ssh-ed25519` instead
of `ssh-rsa` (required by current OpenSSL, and more secure). The portal's own concepts map onto
boringproxy's like this — user-facing text must use the left-hand terms, never the right-hand
ones:

| Portal concept | BoringProxy concept |
|---|---|
| Homelab | Agent |
| Server | Tunnel(s) |

Unlike Homelab/Agent, a Server doesn't map onto exactly one Tunnel: each protocol a Server has
enabled (Web, Terminal, Remote Desktop) becomes its own Tunnel, and Port Forwarding becomes one
more Tunnel per forwarded port (up to 8) -- so a single Server can correspond to anywhere from one
to eleven live Tunnels at once (`TunnelMapper.tunnelPlans`/`portForwardingTunnelPlans`,
`ServerController.syncTunnels`). For each: the Domain is the FQDN (the Server's own public FQDN for
Web and Port Forwarding, a derived hidden FQDN under the primary domain for Terminal/Remote
Desktop -- see "Servers" above), the Agent Name is the Homelab's name, the Client Address/Port are
the homelab-side host/IP and port, and TLS termination is always `MANAGED` for Web, passthrough
(`AllowExternalTcp`) for Port Forwarding. Integration happens through the forked BoringProxy's REST
API — changes are written to its database and tunnels take effect immediately (`BoringProxyClient`).

## Implementation conventions

- Spring 4 / Java, with JavaScript for the frontend. No Lombok — use modern Java (records, etc.)
  instead.
- Maven project, Java 25.
- Do not use HTTP GET for state-changing actions — it triggers the browser's "send information
  again?" prompt on refresh/back-navigation.
