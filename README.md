# Selfie Proxy

Expose apps and services running at home to the internet, without dealing with port
forwarding, dynamic IPs, or certificates by hand.

## Why

Most home internet connections can't be reached from outside: ISPs use shared/dynamic
addresses and port forwarding often doesn't work. Tools like Cloudflare Tunnel, Pangolin,
or NetBird solve this, but are built for enterprise use cases and require real networking
knowledge to configure.

Selfie Proxy is scoped to a single use case: one person exposing their own home services
through one small internet-facing server. You connect your home network to it, and every
app you want to share gets its own HTTPS subdomain, managed from an admin portal instead
of config files. No high availability, load balancing, role-based permissions, or auditing
and enterprise compliance tooling — if you need those, use a commercial product instead.

## Features

- Admin portal to manage every exposed app and website.
- Automatic, auto-renewing HTTPS certificates.
- Built-in login (single sign on) protecting the admin portal and, optionally, individual exposed apps.
- Simple user management: one admin account runs the portal, plus any number of additional
  Users who can only log in to the apps you've protected — nothing more.
- Works behind NAT/CGNAT — no static IP or port forwarding needed.
- Static website hosting under your own domain/subdomain.
- Multiple homelabs (locations) can connect to one server.
- Back up your configuration, or move it to another Selfie Proxy server — a single portable ZIP
  covering every homelab, application, and static website, with a step-by-step
  review of what's new versus what already exists before anything changes.
- Single Docker command to install and update.

## Background

Selfie Proxy is a fork of [boringproxy](https://github.com/boringproxy/boringproxy) by
Anders Pitman, which is no longer actively maintained. On top of it we added:

- A new admin portal aimed at home users instead of boringproxy's networking-first UI.
- WebSocket support.
- Per-app authentication for exposed applications.
- Centralized agent ("client") management from the admin portal.
- Built-in single sign on login for the portal, with support for swapping in an external OIDC provider.
- Admin-managed Users, so you can share login access to your apps without sharing the admin account.
- Static website hosting.
- Export/import configuration for every homelab, application, and static website.
- Fixed tunnel authentication failing on modern OpenSSH (8.8+), which rejects the old
  RSA/`ssh-rsa` keys by default, by switching to Ed25519 — also a more secure algorithm.
- A one-command Docker install.

## Requirements

- A domain name — cheap domains run around $10-15/year.
- A small VPS with a public IPv4 address, Linux, and Docker (~$5/month tier is enough —
  e.g. Hetzner, Vultr, DigitalOcean).
- A machine at home capable of running Docker, to connect as a homelab agent.

## Installation

1. Point DNS at your server — both the domain and a wildcard subdomain are required:

   ```
   example.com      A    <your server IP>
   *.example.com    A    <your server IP>
   ```

   A subdomain also works, e.g.:

   ```
   homelab.example.com      A    <your server IP>
   *.homelab.example.com    A    <your server IP>
   ```

2. On the server, download the compose file and env template:

   ```bash
   curl -O https://raw.githubusercontent.com/jeltechnologies/selfieproxy/main/docker-compose.yaml
   curl -o .env https://raw.githubusercontent.com/jeltechnologies/selfieproxy/main/.env.example
   ```

3. Edit `.env`:

   ```
   PRIMARY_DOMAIN=example.com
   ADMIN_PORTAL_USERNAME=admin
   ADMIN_PORTAL_BOOTSTRAP_PASSWORD=change-me
   ```

   `ADMIN_PORTAL_BOOTSTRAP_PASSWORD` is a one-time seed — you're forced to change it on
   first login, after which it's no longer used.

4. Start it:

   ```bash
   docker compose up -d
   ```

5. Visit `selfieproxy.<your domain>`, log in with the credentials from step 3, and set a
   new password. The portal then walks you through connecting your first homelab and
   exposing your first app.

   > [!CAUTION]
   > There's no "forgot password" flow. Losing this password locks you out of the portal.

## FAQ

**Is this free?** Yes, MIT-licensed, no restrictions on hobby or commercial use — see
[License](#license). For business-critical use, prefer a supported enterprise product.

**macOS/Windows?** The homelab agent runs fine on macOS/Windows, in Docker's default bridge
mode — except it can't use a local DNS server, so use an IP address rather than a hostname
when adding the homelab. On macOS/Windows, remove `network_mode: host` from the agent
`docker-compose.yaml` snippet the Agents page generates, or the container won't start. The
server has no such flexibility: it requires `network_mode: host`, so it must run on Linux.

**Can I point Selfie Proxy at an NGINX reverse proxy already running in my homelab?** No —
point it directly at the application (HTTP, or HTTPS with a self-signed cert). Connecting
straight to the app is what lets Selfie Proxy manage certificates and auth for it;
forwarding through another reverse proxy breaks both.

**Is this secure?** The homelab-to-server tunnel is encrypted, the server is under your own
control, every exposed app gets HTTPS automatically, and the admin portal (optionally any
app) sits behind login. It's open source.

**What's in a configuration export?** Every homelab, application, and static website —
including website files — in a single ZIP you can store safely or move to a new server.
Homelab secrets and the admin login are never included; importing walks you through a
step-by-step wizard showing what's new versus what already exists, with a warning wherever
something will actually change, before anything is applied. A restored homelab always gets a
new secret, which you then update on that homelab.

## License

MIT — see [LICENSE](LICENSE).
