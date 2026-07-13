# Selfie Proxy

Put the things running in your home on the internet — without becoming a network engineer.

## Why you'd want this

You're running something at home. A photo library, a home automation dashboard, a game
server, a little blog, a NAS admin page — whatever it is, it lives on a machine in your
house, and you'd like to open it up to yourself (or friends and family) from anywhere.

The problem is that most home internet connections these days simply can't be reached
from outside anymore. Your Internet Service Provider hides you behind shared addresses, port forwarding doesn't
work, and your IP address changes all the time anyway. Plenty of tools solve this
problem well — Cloudflare, Pangolin, NetBird, Fast Reverse Proxy, and others. But they're
built for enterprises, and it shows: getting them right takes real networking knowledge,
and most of what they offer is functionality a self-hoster will never touch.

Selfie Proxy is built for exactly one person: you, running your own stuff, without a
network engineering degree. You only need one small, cheap server on the internet with a
domain name attached to it. You connect your home network to it, and from that point on
every app or website you want to share gets its own friendly, secure web address — set
up from a simple admin page, not a config file. It's secure by default, out of the box.

## What Selfie Proxy is not

Selfie Proxy is not a solution for the enterprise. It deliberately lacks the features a
business depends on: high availability, load balancing, role-based user management, and
managed Software-as-a-Service offerings. Those things matter a great deal for business
continuity, but they're also notoriously hard to configure correctly — and for a hobbyist
they add nothing but bloat and confusion. If you're running this for a company, use a
properly supported commercial product instead.

## Features

- One admin portal to manage every home app and website you expose
- Automatic, auto-renewing HTTPS certificates for everything you expose
- Built-in login screen protecting your admin portal and, optionally, any app you choose
- Works from behind restrictive home internet connections — no static IP or port
  forwarding required
- Host simple static websites directly, under their own subdomain or your own domain
- Connect as many homes/locations ("homelabs") as you like to one account
- Deliberately single-user — no accounts, roles or permissions to manage
- Installed and updated with a single Docker command

## Why we built this

Selfie Proxy is a fork from [boringproxy](https://github.com/boringproxy/boringproxy)
by Anders Pitman — a small, elegant tunneling tool that solves exactly this problem, and
solves it well. Unfortunately, boringproxy is no longer actively maintained, does not work for many modern web portals, and its
interface was built by and for people who are already comfortable with networking
terminology, which made it a rough starting point for the audience we wanted to serve.

Our thanks to Anders for the excellent foundation. On top of it, we added:

- A brand new admin portal built specifically for home users, replacing the original
  networking-first interface, focusing on the absolute minimum configuration needed to expose the home network to the internet.
- Support for WebSockets, needed by most modern web application interfaces
- Support for authenticating individual exposed web applications.
- Centralized control of agents (called "clients" in boringproxy) from the admin portal.
- A built-in login screen (single sign-on) protecting the portal, with the option to use
  your own external login provider instead
- Support for hosting plain static websites, for simple home pages to save on hosting costs.
- A one-command Docker install, with minimal setup steps.

## What you need

- A domain name you control the DNS for
- A small virtual server (VPS) from any cloud provider — the cheapest tier is enough
  (around $5/month, 1 GB memory, 1 vCPU). This you can source from vendors like Hetzner, Vultr and DigitalOcean.
  The VPS must have a public IPv4 address.
- A machine at home — physical or virtual — capable of running Docker, to connect your homelab to Selfie Proxy.

## Installation

1. Point your domain's DNS at your server. Selfie Proxy needs both a domain and a
   wildcard subdomain pointed at your server's IP address, for example:

   ```
   example.com      A    <your server IP>
   *.example.com     A    <your server IP>
   ```
   
   It is also possible to use Selfie Proxy for a subdomain, for example homelab.example.com. In that case the DNS setup is:

   ```
   homelab.example.com      A    <your server IP>
   *.homelab.example.com    A    <your server IP>
   ```

2. On the server, download the two files you need to get started:

   ```bash
   curl -O https://raw.githubusercontent.com/jeltechnologies/selfieproxy/main/docker-compose.yaml
   curl -o .env https://raw.githubusercontent.com/jeltechnologies/selfieproxy/main/.env.example
   ```

   (Browsable on GitHub: [docker-compose.yaml](https://github.com/jeltechnologies/selfieproxy/blob/main/docker-compose.yaml),
   [.env.example](https://github.com/jeltechnologies/selfieproxy/blob/main/.env.example))

3. Open `.env` and fill in at least your domain and admin bootstrap credentials. After logging in you will have to change your password, and can then discard the ADMIN_PORTAL_BOOTSTRAP_PASSWORD.

   ```
   DOMAIN=example.com
   ADMIN_PORTAL_USERNAME=admin
   ADMIN_PORTAL_BOOTSTRAP_PASSWORD=change-me
   ```

4. Start Selfie Proxy:

   ```bash
   docker compose -f docker-compose.yaml up -d
   ```

5. Visit your admin portal at `selfieproxy.<your domain>`, log in with the credentials
   from step 3, and set a new password when prompted. From there, the portal walks you
   through connecting your first homelab and exposing your first app.

   > [!CAUTION]
   > Store this new password somewhere safe. Selfie Proxy is single-user with no
   > "forgot password" flow, so losing it will lock you out of your own admin portal.

## License

MIT — see [LICENSE](LICENSE).
