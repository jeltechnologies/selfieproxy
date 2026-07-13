#!/bin/sh
set -eu

# Requires: curl, dig

DOMAIN="${DOMAIN:?DOMAIN not set}"
REVERSE_PROXY_LISTENER="${REVERSE_PROXY_LISTENER:-proxylistener}"
SELFPROXY_ADMIN_DOMAIN="${SELFPROXY_ADMIN_DOMAIN:-selfieproxy}"
SELFPROXY_AUTH_DOMAIN="${SELFPROXY_AUTH_DOMAIN:-auth}"
ADMIN_FQDN="${REVERSE_PROXY_LISTENER}.${DOMAIN}"
SELFIEPROXY_FQDN="${SELFPROXY_ADMIN_DOMAIN}.${DOMAIN}"
AUTH_FQDN="${SELFPROXY_AUTH_DOMAIN}.${DOMAIN}"

echo "=============================================================================="
echo ""
echo " S E L F I E P R O X Y - prerequisites check"
echo ""
echo "Check that the DNS records of ${DOMAIN} point to this server...."
echo ""

PUBLIC_IP=$(curl -fsS https://ifconfig.me)
if [ -z "$PUBLIC_IP" ]; then
    echo "ERROR: could not determine public IP"
    exit 1
fi
echo "Selfieproxy is accessible from the internet at: ${PUBLIC_IP}, which must match the DNS records of '*.${DOMAIN}'"

check_domain() {
    name="$1"
    resolved_ip=$(dig +short "$name" | tail -n1)

    if [ -z "$resolved_ip" ]; then
        echo "ERROR: ${name} does not resolve to ${PUBLIC_IP}. Please fix the DNS records of ${DOMAIN} before starting Selfieproxy."
        exit 1
    fi

    if [ "$resolved_ip" != "$PUBLIC_IP" ]; then
        echo "ERROR: ${name} resolves to ${resolved_ip}, expected ${PUBLIC_IP}. Please fix the DNS records of ${DOMAIN} before starting Selfieproxy."
        exit 1
    fi

    echo "OK: ${name} -> ${resolved_ip}"
}

check_domain "$DOMAIN"
check_domain "$ADMIN_FQDN"
check_domain "$SELFIEPROXY_FQDN"
check_domain "$AUTH_FQDN"

echo "DNS check passed."
