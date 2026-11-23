#!/bin/sh
set -eu
edge_token=${AUTOCUIDADO_EDGE_TOKEN:-}
if [ "${#edge_token}" -ne 64 ]; then
    echo 'Configure a credencial da origem com 32 bytes em hexadecimal.' >&2
    exit 1
fi
case "$edge_token" in *[!0-9a-fA-F]*) exit 1;; esac
umask 077
sed "s/__AUTOCUIDADO_EDGE_TOKEN__/$edge_token/" /app/nginx.cloudflare.conf > /tmp/autocuidado-nginx.conf
# Nginx reopens its access log; keep that stream owned by the application user.
output_dir=$(mktemp -d /tmp/autocuidado-output.XXXXXX)
mkfifo "$output_dir/stream"
cat "$output_dir/stream" &
output_pid=$!
cleanup_output() {
    kill -TERM "$output_pid" 2>/dev/null || true
    wait "$output_pid" 2>/dev/null || true
    rm -f "$output_dir/stream"
    rmdir "$output_dir"
}
trap cleanup_output EXIT
exec >"$output_dir/stream" 2>&1
nginx -t -c /tmp/autocuidado-nginx.conf
java -XX:MaxRAMPercentage=60 -jar /app/autocuidado.jar &
api_pid=$!
nginx -c /tmp/autocuidado-nginx.conf -g 'daemon off;' &
proxy_pid=$!
stop() {
    trap - TERM INT
    kill -TERM "$api_pid" "$proxy_pid" 2>/dev/null || true
    wait "$api_pid" "$proxy_pid" 2>/dev/null || true
    exec 1>&- 2>&-
    wait "$output_pid" 2>/dev/null || true
}
trap 'stop; exit 0' TERM INT
while kill -0 "$api_pid" 2>/dev/null && kill -0 "$proxy_pid" 2>/dev/null \
        && kill -0 "$output_pid" 2>/dev/null; do
    sleep 1
done
set +e
if ! kill -0 "$api_pid" 2>/dev/null; then wait "$api_pid"
elif ! kill -0 "$proxy_pid" 2>/dev/null; then wait "$proxy_pid"
else wait "$output_pid"; fi
status=$?
stop
if [ "$status" -eq 0 ]; then status=1; fi
exit "$status"
