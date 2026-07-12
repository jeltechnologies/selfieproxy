#!/bin/sh
set -eu

# Requires: curl, dig

DOMAIN="${DOMAIN:?DOMAIN not set}"
REVERSE_PROXY_LISTENER="${REVERSE_PROXY_LISTENER:-proxylistener}"
SELFPROXY_ADMIN_DOMAIN="${SELFPROXY_ADMIN_DOMAIN:-selfieproxy}"
SELFPROXY_SSO_DOMAIN="${SELFPROXY_SSO_DOMAIN:-sso}"
ADMIN_FQDN="${REVERSE_PROXY_LISTENER}.${DOMAIN}"
SELFIEPROXY_FQDN="${SELFPROXY_ADMIN_DOMAIN}.${DOMAIN}"
SSO_FQDN="${SELFPROXY_SSO_DOMAIN}.${DOMAIN}"

echo "Checking DNS for ${DOMAIN}, ${ADMIN_FQDN}, ${SELFIEPROXY_FQDN} and ${SSO_FQDN}..."

PUBLIC_IP=$(curl -fsS https://ifconfig.me)
if [ -z "$PUBLIC_IP" ]; then
    echo "ERROR: could not determine public IP"
    exit 1
fi
echo "Public IP: ${PUBLIC_IP}"

check_domain() {
    name="$1"
    resolved_ip=$(dig +short "$name" | tail -n1)

    if [ -z "$resolved_ip" ]; then
        echo "ERROR: ${name} does not resolve to any IP. You must fix your DNS records before starting boringproxy."
        exit 1
    fi

    if [ "$resolved_ip" != "$PUBLIC_IP" ]; then
        echo "ERROR: ${name} resolves to ${resolved_ip}, expected ${PUBLIC_IP}. You must fix your DNS records before starting boringproxy."
        exit 1
    fi

    echo "OK: ${name} -> ${resolved_ip}"
}

check_domain "$DOMAIN"
check_domain "$ADMIN_FQDN"
check_domain "$SELFIEPROXY_FQDN"
check_domain "$SSO_FQDN"

echo "DNS check passed."
