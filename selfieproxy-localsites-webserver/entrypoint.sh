#!/bin/sh
set -eu

# Off by default -- every request to every local website would otherwise be
# logged, unlike boringproxy's own -debug flag which this mirrors.
if [ "${DEBUG_MODE:-false}" = "true" ]; then
	echo 'access_log /dev/stdout;' > /etc/nginx/access_log.conf
else
	echo 'access_log off;' > /etc/nginx/access_log.conf
fi

nginx -g 'daemon off;' &
NGINX_PID=$!
trap 'kill -TERM "$NGINX_PID" 2>/dev/null' TERM INT

# selfieproxy-portal writes/removes one conf file per own-domain static site
# under /etc/nginx/conf.d -- reload nginx whenever that changes. Validate
# first so a bad write can never take the whole webserver down.
inotifywait -m -e create,modify,delete,move --format '%f' /etc/nginx/conf.d | while read -r _; do
	if nginx -t; then
		nginx -s reload
	else
		echo "Skipping reload: nginx config is invalid" >&2
	fi
done &

wait "$NGINX_PID"
