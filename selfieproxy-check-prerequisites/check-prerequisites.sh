#!/bin/sh
set -eu

# Requires: curl, dig, nc (netcat-openbsd), jq

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

# 80/443: nothing is listening yet at this point in startup (selfieproxy-reverseproxy
# hasn't started), so bind temporary listeners on both before scanning, so an external
# scan sees them as open exactly like selfieproxy-reverseproxy will once it starts.
nc -l 80 >/dev/null 2>&1 &
LISTENER_80=$!
nc -l 443 >/dev/null 2>&1 &
LISTENER_443=$!
sleep 0.3

cleanup_listeners() {
    kill "$LISTENER_80" "$LISTENER_443" 2>/dev/null || true
    wait "$LISTENER_80" "$LISTENER_443" 2>/dev/null || true
}
trap cleanup_listeners EXIT

# A same-host self-dial to our own public IP cannot be trusted as a firewall test: Linux
# routes traffic addressed to a locally-assigned IP via the local/loopback path (RTN_LOCAL)
# rather than out over the real NIC, no matter which local address is used as the source --
# and ufw's default rules unconditionally ACCEPT everything on lo. So a host with 80/443
# actually firewalled off from the internet (e.g. Ubuntu's default ufw, which only allows 22
# out of the box -- the exact incident this check exists to prevent) still reports itself as
# reachable. There is no socket option that forces this host to route to its own address over
# the wire, so the only way to get a real answer is a genuine external vantage point:
# api.portscan.com's free scan API, which scans whatever IP the request originates from (i.e.
# us) with no key or params needed, covering ports 22/80/443 in a single "fast" scan.
SCAN_RESPONSE=""
if start_response=$(curl -fsS -m 10 -X POST https://api.portscan.com/v1/fast 2>/dev/null); then
    eta=$(echo "$start_response" | jq -r '.eta_seconds // 8')
    case "$eta" in ''|*[!0-9]*) eta=8 ;; esac
    sleep "$eta"

    waited=0
    while [ "$waited" -lt 45 ]; do
        poll_response=$(curl -fsS -m 10 -X GET https://api.portscan.com/v1/fast 2>/dev/null) || break
        status=$(echo "$poll_response" | jq -r '.status // empty')
        if [ "$status" = "complete" ]; then
            SCAN_RESPONSE="$poll_response"
            break
        fi
        sleep 3
        waited=$((waited + 3))
    done
fi

if [ -z "$SCAN_RESPONSE" ]; then
    # The external scan itself is just unreachable/unavailable -- not evidence of anything.
    # There's no meaningful fallback (a self-dial doesn't test what it claims to, see above),
    # so warn and let startup proceed rather than block on a third party being unreachable.
    echo "WARN: could not verify port reachability via external scan (api.portscan.com unreachable, rate-limited, or timed out). Proceeding without confirmation -- double check that inbound 80/tcp, 443/tcp (and 22/tcp, unless STEALTH_MODE=true) are actually open in the server's firewall."
else
    is_open() {
        echo "$SCAN_RESPONSE" | jq -e --argjson p "$1" '.ports_open[]? | select(.port == $p)' >/dev/null 2>&1
    }

    check_port_result() {
        port="$1"
        severity="$2"
        if is_open "$port"; then
            echo "OK: port ${port}/tcp is reachable at ${PUBLIC_IP} (confirmed by external scan)"
            return
        fi
        if [ "$severity" = "ERROR" ]; then
            echo "ERROR: port ${port}/tcp is not reachable at ${PUBLIC_IP} (confirmed by external scan). Open inbound ${port}/tcp in the server's firewall before starting Selfie Proxy."
            exit 1
        fi
        echo "WARN: port ${port}/tcp is not reachable at ${PUBLIC_IP} (confirmed by external scan). Homelab agents cannot connect until inbound ${port}/tcp is open (or set STEALTH_MODE=true to tunnel agent SSH over 443 instead)."
    }

    check_port_result 80 ERROR
    check_port_result 443 ERROR

    # Skipped entirely under STEALTH_MODE, which tunnels agent SSH over 443 instead.
    if [ "${STEALTH_MODE:-false}" = "true" ]; then
        echo "STEALTH_MODE is enabled, skipping the port 22 check (agent SSH tunnels over 443 instead)."
    else
        check_port_result 22 WARN
    fi
fi

echo "Port check passed."
echo "=============================================================================="