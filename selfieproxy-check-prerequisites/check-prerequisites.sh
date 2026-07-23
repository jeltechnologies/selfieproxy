#!/bin/sh
set -eu

# Requires: curl, dig

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