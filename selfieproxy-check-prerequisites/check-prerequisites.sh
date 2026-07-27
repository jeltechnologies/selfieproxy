#!/bin/sh
set -eu

# Requires: curl, dig, nc (netcat-openbsd)

PRIMARY_DOMAIN="${PRIMARY_DOMAIN:?PRIMARY_DOMAIN not set}"
WILDCARD_FQDN="*.${PRIMARY_DOMAIN}"

echo "=============================================================================="
echo ""
echo " S E L F I E P R O X Y - prerequisites check"
echo ""

PUBLIC_IP=$(curl -fsS https://ifconfig.me)
if [ -z "$PUBLIC_IP" ]; then
    echo "ERROR: could not determine public IP"
    exit 1
fi
echo "Selfie Proxy is accessible from the internet at: ${PUBLIC_IP}, which must match the DNS records of '*.${PRIMARY_DOMAIN}'"

check_domain() {
    name="$1"
    resolved_ip=$(dig +short "$name" | tail -n1)

    if [ -z "$resolved_ip" ]; then
        echo "ERROR: ${name} does not resolve to ${PUBLIC_IP}. Please fix the DNS records of ${PRIMARY_DOMAIN} before starting Selfie Proxy."
        exit 1
    fi

    if [ "$resolved_ip" != "$PUBLIC_IP" ]; then
        echo "ERROR: ${name} resolves to ${resolved_ip}, expected ${PUBLIC_IP}. Please fix the DNS records of ${PRIMARY_DOMAIN} before starting Selfie Proxy."
        exit 1
    fi

    echo "OK: ${name} -> ${resolved_ip}"
}

check_domain "$PRIMARY_DOMAIN"
check_domain "$WILDCARD_FQDN"

echo "DNS check passed."
echo "=============================================================================="

# Self-listen + self-dial over the real host network path: this container runs with
# network_mode: host, and every supported deployment of Selfie Proxy has a public IPv4
# address bound directly to this host (never NAT/CGNAT -- see the README's Requirements
# section), so dialing our own public IP exercises the exact same path a real external
# client (or Let's Encrypt) would take. This only verifies reachability at the Docker
# host boundary -- deliberately in scope only for what this project controls.
port_reachable() {
    port="$1"
    attempt=1
    while [ "$attempt" -le 2 ]; do
        if nc -z -w 3 "$PUBLIC_IP" "$port" 2>/dev/null; then
            return 0
        fi
        attempt=$((attempt + 1))
        [ "$attempt" -le 2 ] && sleep 2
    done
    return 1
}

check_port() {
    port="$1"
    severity="$2"
    bind_first="$3"

    listener_pid=""
    if [ "$bind_first" = "yes" ]; then
        nc -l "$port" >/dev/null 2>&1 &
        listener_pid=$!
        sleep 0.3
    fi

    if port_reachable "$port"; then
        ok=1
    else
        ok=0
    fi

    if [ -n "$listener_pid" ]; then
        kill "$listener_pid" 2>/dev/null || true
        wait "$listener_pid" 2>/dev/null || true
    fi

    if [ "$ok" -eq 1 ]; then
        echo "OK: port ${port}/tcp is reachable at ${PUBLIC_IP}"
        return 0
    fi

    if [ "$severity" = "ERROR" ]; then
        echo "ERROR: port ${port}/tcp is not reachable at ${PUBLIC_IP}. Open inbound ${port}/tcp in the server's firewall before starting Selfie Proxy."
        exit 1
    fi

    echo "WARN: port ${port}/tcp is not reachable at ${PUBLIC_IP}. Homelab agents cannot connect until inbound ${port}/tcp is open (or set STEALTH_MODE=true to tunnel agent SSH over 443 instead)."
}

# 80/443: nothing is listening yet at this point in startup (selfieproxy-reverseproxy
# hasn't started), so bind a temporary listener first to test against.
check_port 80 ERROR yes
check_port 443 ERROR yes

# 22: the host's own sshd is already listening, so no temporary listener is needed.
# Skipped entirely under STEALTH_MODE, which tunnels agent SSH over 443 instead.
if [ "${STEALTH_MODE:-false}" = "true" ]; then
    echo "STEALTH_MODE is enabled, skipping the port 22 check (agent SSH tunnels over 443 instead)."
else
    check_port 22 WARN no
fi

echo "Port check passed."
echo "=============================================================================="