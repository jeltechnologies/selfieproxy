# Selfie Proxy

Selfie Proxy provides a simplified, selfhosting solution for accessing home labs behind NAT/CGNAT, creating HTTPS subdomains via a small internet-facing server rather than complex configurations. It avoids unnecessary enterprise features like load balancing and auditing, focusing instead on ease of use for self-hosters.

## Why

Homelabs typically operate on a home internet network that can't be reached from the outside. 

Internet service providers introduced CGNAT, which creates a protective barrier, stopping hackers and malicious bots from scanning, targeting, or accessing home gadgets. While CGNAT is a good thing for normal consumers, it brings problems for homelabs because classic port forwarding does not work anymore.

Tools like Cloudflare Tunnel, Pangolin, Tailscale, and NetBird solve this, but they require real networking knowledge to configure. With many options and configurations, it takes many clicks to get things working. As a self-hoster, you don't need all these options. 

Most of these mentioned tools are built for enterprise use cases. They hide basic functions like backup and restore, terminal access, and remote desktop behind paid enterprise licenses.

Selfie Proxy is designed for people selfhosting for a hobby. It includes the essential security that homelabs actually need, while deliberately skipping enterprise bloat to keep things lightweight, zero-cost, and easy to run yourself.

Selfie Proxy deliberately lacks enterprise features that commercial alternatives provide:
- No high availability or load balancing
- No auditing and enterprise compliance tooling
- No dedicated support

If your business requires these features, use a commercial product instead!

## Features

- Admin portal to manage every exposed app and website.
- Automatic, auto-renewing HTTPS certificates.
- Built-in login (single sign on) protecting the admin portal and, optionally, individual exposed apps.
- Simplified user management: one admin account runs the portal, plus any number of additional
  Users who can only log in to the apps you've protected — nothing more.
- Static website hosting under your own domain/subdomain.
- Multiple homelabs (locations) can connect to one server.
- Remote Desktop and SSH terminal access to your homelab machines, right in your browser —
  no VPN, no separate RDP/VNC/SSH client to install or configure.
- Light and dark mode for its user interfaces.
- Back up your configuration, or move it to another Selfie Proxy server — everything at once,
  with the exception of passwords.
- Single Docker command to install and update.

## Background

Selfie Proxy's reverse tunnel engine (`selfieproxy-reverseproxy`) is a fork of
[boringproxy](https://github.com/boringproxy/boringproxy) by Anders Pitman, which is no longer
actively maintained. Written in Go, it's lightweight and fast. Selfie Proxy itself is a larger
system built around that engine — the admin portal, identity provider, and other services are
our own code, not part of the fork. 

On top of boringproxy we added:
- A new admin portal aimed at home users instead of boringproxy's networking-first UI.
- WebSocket support.
- Per-app authentication for exposed applications.
- Centralized agent ("client") management from the admin portal.
- Built-in single sign on login for the portal, with support for swapping in an external OIDC provider.
- Users management, so you can share login access to your apps without sharing the admin account.
- Static website hosting.
- Browser-based Remote Desktop and SSH terminal access to homelab machines.
- Export/import configuration for every homelab, application, and static website.
- Improved security by replacing RSA keys with Ed25519 encryption between agent and server.
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
[License](#license). For business-critical use cases, we recommend using supported enterprise products instead.

**Is this secure?** The homelab-to-server tunnel is encrypted, the server is under your own
control, every exposed app gets HTTPS automatically, and the admin portal (optionally any
app) sits behind login. Repeated failed login attempts are throttled with an increasing
delay, capped at 15 minutes, so password-guessing scripts get slower with every attempt
without ever locking a legitimate user out for longer than that. It's open source.

**macOS/Windows?** The homelab agent runs fine on macOS/Windows, in Docker's default bridge
mode — except it can't use a local DNS server, so use an IP address rather than a hostname
when adding the homelab. On macOS/Windows, remove `network_mode: host` from the agent
`docker-compose.yaml` snippet the Agents page generates, or the container won't start. The
server has no such flexibility: it requires `network_mode: host`, so it must run on Linux.

**Can I point Selfie Proxy at an NGINX reverse proxy already running in my homelab?** No —
point it directly at the application (HTTP, or HTTPS with a self-signed cert). Connecting
straight to the app is what lets Selfie Proxy manage certificates and auth for it;
forwarding through another reverse proxy breaks both.

**What's in a configuration export?** Everything, with the exception of passwords. Importing
walks you through what's new versus what already exists before anything changes. A restored
homelab always gets a new secret, which you then update on that homelab.

## License

MIT — see [LICENSE](LICENSE).
