#!/bin/sh
set -eu

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
