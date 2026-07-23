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
  (see "Login" below) who can authenticate to exposed apps protected with single sign on but never the portal --
  keep that distinction in mind rather than assuming every login is the admin.
- A Homelab exposes several web services to the internet as subdomains of the Selfie Proxy domain.
- boringproxy terminology ("Client", "Tunnel") must never leak into the portal UI — it only adds
  confusion for the non-networking audience this product targets. Internally the portal maps its
  own concepts onto boringproxy's (see "Mapping to the boringproxy data model" below), but nothing
  user-facing should say "tunnel" or "client".

## Login

The portal has no login of its own (see root `CLAUDE.md`'s "Running" section for the full
OIDC/env-var picture) — boringproxy gates the portal domain before any request reaches this
container. After a successful login the user lands on the exposed applications page.

- A Web Application exposed app can opt in to the same single sign on gate (the authentication
  checkbox on its edit page) — available whenever Selfie Proxy itself terminates the public TLS connection: always for
  a plain HTTP homelab app (Selfie Proxy still adds the managed cert and converts to HTTPS at the
  server, forwarding plain HTTP onward), and for an HTTPS homelab app only under Server HTTPS (the
  recommended connectivity option). Client Raw TLS and Server Raw TLS are excluded since boringproxy
  never HTTP-parses those tunnels, so it has nothing to gate (see `ExposedApp.canProtectWithSso()`).
- The topbar's user menu ("▾ Settings", `fragments/layout.html`) holds a theme toggle button (its
  label flips between "Change to dark mode"/"Change to light mode" depending on the current
  setting, `POST /appearance/toggle`, `web/AppearanceController.java` -- a one-click toggle rather
  than a picker page, since there are only two modes: `domain/Theme`/`domain/ThemeStore`, persisted
  to `data/selfieproxy/theme.json`; the same setting is also read by
  `selfieproxy-identity-provider`'s own read-only `ThemeStore` mirror, applying it to the login/
  change-password/logged-out pages too -- one shared appearance across both apps, default Light.
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
  and restore" below), the same kind of internal-vs-UI naming split as Homelab/Agent and Exposed
  App/Tunnel. Log out ends the portal's own session and clears boringproxy's single sign on cookie for
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
  Renaming a domain **cascades**: every Exposed App/Local Website already using it gets its tunnel
  recreated under the new domain (the same delete-tunnel-then-recreate-with-a-2s-wait pattern an
  ordinary edit already uses, just applied in bulk -- brief downtime for the affected items, which is
  accepted since this is a deliberate admin action), and a Local Website's content directory/NGINX
  config moves with it. A failure on one item is recorded and never blocks the rest of the rename.
  Removing a domain has **no cascade at all** -- any Exposed App/Local Website still using it keeps
  working exactly as before, just flagged with a warning on its own list row (see "Exposed
  applications"/"Local websites" below), since Selfie Proxy no longer tracks that domain but the
  underlying tunnel is untouched.
- Every place a domain is chosen (Add Application, Add Local Website's "Subdomain of ..." mode, the
  import wizard's per-item domain picker) lists the primary domain first, labeled e.g.
  `example.com (primary)`, then every other registered domain alphabetically with no special label.

## Homelabs

- Each exposed web service is bound to one subdomain of whichever domain it's assigned to (see
  "Domains" above), composing a FQDN automatically (subdomain `music` on domain `example.com` becomes
  `music.example.com`). The subdomain is optional -- leaving it blank exposes the service at the bare
  domain itself (`example.com`), including the primary domain, since nothing else listens on it bare
  (the fixed `proxylistener`/`selfieproxy`/`auth`/`console` subdomains are all subdomains *of* the
  primary domain, never the primary domain itself).
- The Homelabs page and the Exposed Applications page are two views over related data, not two
  separate concepts: a Homelab corresponds to one Agent (see Agents page below) and is managed
  there, not on the Exposed Applications page.

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
  hostnames (same pattern as `selfieproxy-localsites-agent` in root `CLAUDE.md`'s "Running"
  section); this rules out Docker Desktop (macOS/Windows), same as the server's own host
  requirement.

## Exposed applications

- The top of the page manages Homelab selection: a dropdown of Homelab names in alphabetical
  order, first one auto-selected. The user cannot add or delete Homelabs from this page.
- Exposed apps are listed with sortable column headers (Name, Domain, Homelab, Local address --
  click any header to sort ascending/descending, plain client-side JS,
  `static/js/sortable-table.js`, no server round-trip) and a domain-only filter dropdown above the
  table (populated from every registered domain, see "Domains"). Each row shows Name (the subdomain
  for a Web application; the user-entered Name for a Network service, since its internal `svc-`
  subdomain is never shown), Domain (with a warning icon if that domain was since removed from the
  Domains page -- the app keeps working, it's just orphaned from Selfie Proxy's own domain
  registry; always the primary domain for an SSH/RDP/VNC-mode app, which still shows normally here
  like any other domain, warning icon included, rather than being special-cased -- see
  `ExposedAppController.toExposedApp`), the Homelab, the address within the homelab, and a
  right-most unlabeled, unsortable column right before the Edit button: a **Connect** button
  (opening the app's URL in a new tab) for a Web application, `domain:port` as plain text for a
  TCP-mode Network Service (nothing to open -- it's just an address, not a page), or a **Connect**
  button for an SSH/RDP/VNC-mode one (see "Connecting to an SSH/RDP/VNC-mode application" below)
  -- there is no separate "not exposed to the internet" indicator anywhere on this list, since the
  Connect button itself already makes that mode's nature obvious without needing to spell it out.
  Whenever that row's Status dot is red (`appStatusMessage` -- homelab disconnected and/or a DNS
  mismatch, see DashboardController), its Connect button stays visible but is disabled
  (`aria-disabled="true"` + `tabindex="-1"`, `.button-small[aria-disabled="true"]` in style.css --
  an `<a>` has no native disabled state, and removing `href` instead would also drop its title/
  styling context) rather than hidden, so the row still reads the same at a glance either way.

### Editing, adding, and removing an exposed app

The edit page fields, in order:

1. **Type**: Web application (default) or Network service. Selecting Network service shows a
   warning: "Use this at your own risk. Anyone on the internet who scans your domain can see that
   port is open and attempt to connect to it." (this warning, and the port-scanning risk itself,
   only really applies to TCP mode below -- shown for every Network Service mode anyway,
   since Selecting Network Service is the one action that turns on a whole extra section of the form).
2. **Mode** (Network service only): one of four --
   - **TCP** (default, label deliberately terse -- "Protocol: TCP" is already implied and no
     longer separately shown, see point 4 below) -- today's original behavior, internet-reachable
     at a chosen **Exposed port** (ports 1-1023 are reserved for system services and cannot be
     exposed; a port can only be exposed by one app at a time), on a subdomain of a chosen
     **Domain** (dropdown of every registered domain, primary first -- same ordering/labeling as a
     Web application's Domain field). The subdomain itself is never shown to the user -- a
     generated internal `svc-` value, exactly like every mode below.
   - **Terminal Access: SSH**, **Desktop Access: RDP**, **Desktop Access: VNC** -- a browser
     SSH/RDP/VNC session instead, reached through a **Connect** action on the Applications list
     (see below) rather than a public URL: the underlying tunnel is created with
     `allow-external-tcp: false` (see `selfieproxy-reverseproxy/CLAUDE.md`'s "Core types" section
     on `Tunnel.AllowExternalTcp`) and always lives on an internal, never-shown subdomain of the
     *primary* domain -- there is no Domain/Exposed port field for these modes at all, since
     there's no public FQDN concept for them. Selecting one of these three reveals, inside the
     "Address in the homelab" fieldset (see below): **Username** (optional -- VNC often has none)
     and **Password** side by side on one line, always a real `<input type="password">` with no
     view/reveal button -- there is no private-key auth option, every mode authenticates with a
     password only -- and **Accept a self-signed certificate on the target** (RDP/VNC only). On
     edit, the password field shows a `••••••••` placeholder when a credential is already stored
     (a fixed decoy, not the real password -- `<input placeholder>` is never submitted and never
     puts the actual secret in the page) and no placeholder when adding or when none is stored yet,
     so it's visually obvious whether a credential exists without ever exposing it. Port defaults
     per mode when Mode is changed (22/3389/5900), the same auto-fill idiom the Protocol dropdown
     already has for HTTP/HTTPS. Leaving the password field blank on an
     edit keeps the previously stored one unchanged; leaving it blank when adding is allowed too
     (eg. a VNC target with no password) -- see "Connecting" below for what happens then.
   **Subdomain** (Web application only): the label, composing the FQDN as `<subdomain>.<domain>` --
   optional; leaving it blank exposes the app at the bare domain itself (see "Homelabs" above).
3. The FQDN itself (Web application and TCP mode only): a label (not a text field), shown
   immediately after the subdomain/exposed port, updated live as the user edits the form
   (including changing the domain dropdown). Not a hyperlink.
4. The "Address in the homelab" fieldset: **Name** (Network service only, required, for every
   mode -- a label shown in the list, not part of the domain, not unique), Homelab, then a row with
   Protocol (HTTP/HTTPS dropdown, Web application only -- no equivalent field for a Network
   Service, since Mode already says TCP/SSH/RDP/VNC and repeating "Protocol: TCP" next to it added
   nothing), host/IP (always shown, needed for every type/mode), and port (defaults to 80 for HTTP,
   443 for HTTPS, or the SSH/RDP/VNC mode's own default above), then (SSH/RDP/VNC mode only) a
   second row with Username and Password side by side, then Accept a self-signed certificate --
   see Mode above for these last three.

### Connecting to an SSH/RDP/VNC-mode application

The Applications list shows a **Connect** action (alongside Edit) for any app in one of these
three modes, opening `https://console.<domain>/connect/<fqdn>` in a new tab -- a live browser
session served by the separate `selfieproxy-remote-console` service (Apache Guacamole, consumed
unmodified -- see `THIRD-PARTY-NOTICES.md`), paired with the `selfieproxy-guacd` container. If no
credential has ever been stored for that app (left blank when adding, or arrived via a
configuration import -- imports never carry a password, see "Backup and restore" below), Connect
instead opens a portal page prompting for one; submitting it encrypts and saves the credential
(`NetworkServiceCredentialCipher`, AES-256-GCM, key self-provisioned into
`data/selfieproxy/network-service-secret-key` the first time it's needed, same idiom as
`selfieproxy-identity-provider`'s `sso-signing-key.pem`) and proceeds straight into the session --
every later Connect skips that prompt. `selfieproxy-remote-console` only ever reads
`exposed-apps.json`/that key (shared `/data` volume) at connect time, never writes either --
credential entry always goes through the portal, keeping a single writer for the whole file.

When HTTPS is selected as the homelab-side protocol, an "Advanced settings" button reveals three
Connectivity options between Selfie Proxy and the homelab:

1. **End-to-end encrypted** (default, recommended, "Server HTTPS") — Selfie Proxy automatically
   creates and renews a signed certificate, and is the only HTTPS connectivity option that can
   also be protected with single sign on (see `ExposedApp.canProtectWithSso()` and the "Protect by forcing
   authentication through Selfieproxy login" checkbox above).
2. **End-to-end encrypted, self-provided** ("Client Raw TLS") — **not supported behind a reverse
   proxy** (e.g. NGINX) in the homelab: the agent must connect directly to the web application,
   which provides its own certificate and handles authentication itself. This isn't a soft
   recommendation -- Selfie Proxy sends the tunnel's public domain as the TLS SNI straight to
   whatever the agent dials (see `selfieproxy-reverseproxy/CLAUDE.md`'s "Agent tunnel lifecycle"
   section), and a reverse proxy in between would need to itself recognize that same SNI to route
   the connection, which isn't a topology Selfie Proxy supports. Shows a warning: "A reverse proxy
   (such as NGINX) in front of your web application is not supported for this option. The agent
   must connect directly to your web application, which must provide its own valid signed
   certificate from Let's Encrypt and handle any authentication itself -- Selfie Proxy does not
   protect this connection."
3. **Hop-by-hop encryption** ("Server Raw TLS", compatibility mode) — Selfie Proxy automatically
   creates and renews a signed certificate; use when a web application breaks on normal HTTPS.

Button panel: Cancel (returns to the list, no changes), OK (add/update), Remove (edit only, red
background/white text, asks for confirmation in an overlay first).

Validation: before adding, check the subdomain isn't already taken on the chosen domain
(case-insensitive) — this also applies to a Network service's generated internal subdomain,
regenerated until it doesn't collide. `proxylistener`/`selfieproxy`/`auth` (or their env overrides,
`REVERSE_PROXY_LISTENER_SUBDOMAIN`/`SELFPROXY_ADMIN_DOMAIN`/`SELFPROXY_AUTH_DOMAIN`) are reserved and
cannot be used for a user's own exposed apps -- but only when the primary domain is selected, since
those reserved subdomains are hardcoded to the primary domain alone (`docker-compose.yaml`); the same
label under any other registered domain is a perfectly ordinary, unreserved app. Updating an exposed
app (including just changing its domain) removes the boringproxy tunnel, waits 2 seconds, then
recreates it with the new values (no in-place tunnel update).

## Local websites

Static sites Selfie Proxy hosts itself, entirely independent of the Homelab/Exposed App concept —
no user-run address behind them, no Homelab to pick, just a domain. See root `CLAUDE.md` for the
`selfieproxy-local-websites`/`selfieproxy-localsites-agent` infrastructure behind this feature;
this section is the portal-side UI behavior.

- The nav has a "Local websites" tab next to Applications. The list page shows every site's domain
  (opens in a new tab, with a warning icon if its chosen domain was since removed from the Domains
  page -- same treatment as the Applications page), an Edit button, and a Download button (streams
  the content directory as a ZIP, see `StaticSiteProvisioner.writeZip`) -- with the same
  sortable-column-headers and domain-filter treatment as the Applications page above.
- Adding one: a subdomain-label text field plus a dropdown of every registered domain, same
  ordering/labeling as an Exposed App's domain dropdown -- composes `<subdomain>.<chosen domain>`.
  The label is optional, same as an Exposed App's Subdomain field -- leaving it blank serves the
  site at the bare domain itself. Exactly like an Exposed App, there's no way to point a Local
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
  load, no caching: a cert-pending banner (mirrors the Applications page's own, but scoped to This
  Server's tunnels instead of excluding them, since every Local Website tunnel belongs to the hidden
  "This Server" homelab) shown whenever boringproxy is still retrying Let's Encrypt for a site
  (self-signed cert served in the meantime -- expected and fine, not itself a bug). There's no
  separate DNS-mismatch check here -- a Local Website's domain is always a registered one, so its
  DNS correctness is already tracked centrally on the Domains settings page instead (see "Domains"
  above), exactly like an Exposed App.

## Backup and restore

**Value to the user**: back up their configuration, or move it to another Selfie Proxy server.
Everything below is how that's done -- a single ZIP covering every Homelab, Application, and Local
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
Exposed App ("server" in the picker's own wording), and Local Website (config *and* its actual
content files) -- usable both for disaster recovery on the same server and for moving to a brand
new one. Each Exposed App/Local Website already carries its own domain (`ExposedApp.domain()`/
`LocalWebsite.domain()`, see "Domains" above), so it flows into `manifest.json` for free; a restore
onto a different server doesn't assume the two servers share the same domain -- see the Applications/
Local Websites wizard steps' per-item domain picker below. `BackupService` does the work;
`BackupController` is the thin web layer for both pages.

- **Export configuration page** (`GET /export-configuration`): three flat checkbox lists over
  *live* server state, in a fixed order -- Homelabs, then Exposed Apps (each entry showing its own
  Homelab name, since apps aren't nested/grouped under one), then Local Websites -- all pre-checked,
  plus "Select All"/"Select None" buttons above the lists (`backup.js`, targeting every checkbox
  under `#backup-form` at once). Submitting the form (`GET /export-configuration/download`, a plain
  query-string GET since it only reads state) streams a ZIP containing only what's checked:
  `BackupService.buildManifest` builds the full picture from live state, `BackupService.filterManifest`
  narrows it down to the submitted selection before it's serialized and zipped.
- **What's included** in a selected item: Homelab names; each selected Exposed App's full settings
  (the same merged view `ExposedAppController` itself edits: `TunnelMapper.toExposedApp` overlaid
  with `ExposedAppStore.reconcile`) -- including, for an SSH/RDP/VNC-mode app, its username, but
  never its stored credential (see below); each selected Local Website's settings
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
  homelab's `.env` afterward. An SSH/RDP/VNC-mode Network Service's encrypted credential is never
  exported either (`ExposedApp.withoutSecret`, `BackupService.buildManifest`) -- it's encrypted
  with a key that never leaves this server (`NetworkServiceCredentialCipher`), so an exported
  ciphertext would be undecryptable elsewhere; importing one of these apps lands it in the same
  "no credential stored yet" state as a freshly added one left blank, prompting for a password on
  its first Connect (see "Exposed applications" above). Also excluded:
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
  `homelabsStep`/`exposedAppsStep`/`localWebsitesStep`/`overviewStep`, templates
  `restore-homelabs.html`/`restore-exposed-apps.html`/`restore-local-websites.html`/
  `restore-overview.html`): Homelabs, then Applications (the exposed-apps step -- user-facing
  copy says "Applications"/"applications" throughout, never "exposed app(s)"; the Java
  identifiers/JSON fields stay `exposedApps` etc., this is display wording only), then Local
  Websites, then Overview -- except a category step is skipped entirely when the staged export has
  nothing in that category (`BackupController.firstStep`/`nextStep`/`previousStep`, checked
  against `manifest.homelabs()`/`exposedApps()`/`localWebsites()` being empty), so the wizard's
  total step count and which category is effectively "first" both vary with what's actually in the
  ZIP -- an export with only Applications goes straight from upload to the Applications step,
  skipping Homelabs, and each step's "Step N of ..." label (`BackupController.stepNumber`/
  `totalSteps`) reflects the actual count, not a hardcoded 5. Each step's subtitle names the
  action, not just the category -- "Select the homelabs to import" / "Select which applications to
  import" / "Select which local websites to import" -- and the source domain/created-at from the
  manifest isn't shown per step at all, it's informational only and adds noise to a page the admin
  will click through repeatedly. Each surviving category step lists every item from the staged
  manifest for that category with a checkbox (unchecked by default -- the admin actively picks
  what to import, item by item, rather than starting from an implicit "everything selected") and a
  New/Existing status badge computed against live state (`BackupService.diffManifest`, against
  `boringProxyClient.listAgents()`/`ExposedAppStore.find`/`LocalWebsiteStore.find`; this badge is
  computed against the ZIP's own domain and doesn't live-update if the domain picker below is
  changed, an accepted minor simplification). The Applications step and the Local Websites step
  also show a per-item domain `<select>` (same ordering/labeling as
  the Add Application page's -- primary domain first, then every other registered domain
  alphabetically) defaulting to the ZIP's own domain if it's still registered on this server, else
  the primary domain (`BackupController.targetDomainsForApps`/`targetDomainsForSites`) -- this is what
  lets a restore land on a domain other than the one the export was originally taken from. Each
  step's chosen domains are carried forward to the next as `domain__<fqdn>` hidden fields (the
  wizard's stateless carry-forward idiom, extended one step further), plus a Select
  All/Select None button pair above the list (`restore-wizard.js`, mirrors the export page's own
  `backup.js` pattern, scoped to that step's own `#wizard-form`) -- the Homelabs and Applications
  steps show their list plain with no box around it, the Local Websites step still wraps its list
  in a fieldset. None of the category steps show a warning next to New/Existing items -- that's
  reserved entirely for the Overview step, which summarizes everything actually selected (a
  category section is omitted entirely, not shown empty, when nothing was picked in it) with the
  same New/Existing badges plus the actual contextual warning per item: a **New** Homelab warns
  it'll get a brand-new secret (see above); an **Existing** Application warns "This application
  configuration will be overwritten"; an **Existing** Local Website warns it'll be replaced -- New
  Applications/Local Websites and Existing Homelabs get no warning, since nothing unexpected
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
  by the Overview step's hidden fields) recreates each selected Application/Local Website's tunnel
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
| Exposed app | Tunnel |

When creating a tunnel: the exposed app becomes a Tunnel, the Domain is the FQDN, the Agent Name
is the Homelab's name, the Client Address/Port are the homelab-side host/IP and port, and TLS
termination follows the Connectivity option chosen above. Integration happens through the forked
BoringProxy's REST API — changes are written to its database and tunnels take effect immediately
(`BoringProxyClient`).

## Implementation conventions

- Spring 4 / Java, with JavaScript for the frontend. No Lombok — use modern Java (records, etc.)
  instead.
- Maven project, Java 25.
- Do not use HTTP GET for state-changing actions — it triggers the browser's "send information
  again?" prompt on refresh/back-navigation.
