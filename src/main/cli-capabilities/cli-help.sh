#!/bin/sh
exe=$1
case $exe in /*) ;; *) exit 1;; esac
case $(uname -s) in
    Darwin|*BSD) stamp=$(stat -Lf '%d:%i:%m:%c:%z' "$exe");;
    *) stamp=$(stat -Lc '%d:%i:%y:%z:%s' "$exe");;
esac
if [ -z "$stamp" ]; then exec "$exe" --help; fi
umask 077
root=${XDG_CACHE_HOME:-"$HOME/.cache"}/imux/cli-help-v1
key=$(printf '%s\n' "$exe" "$stamp" | cksum) || exec "$exe" --help
entry=$root/${key%% *}.help
if [ -s "$entry" ] && [ -n "$(find "$entry" -mtime -1 -print 2>/dev/null)" ]; then
    exec cat "$entry"
fi
mkdir -p "$root" || exec "$exe" --help
tmp=$(mktemp "$root/.help.XXXXXXXX") || exec "$exe" --help
trap 'rm -f "$tmp"' EXIT
if "$exe" --help >"$tmp" && [ -s "$tmp" ]; then
    cat "$tmp"
    mv -f "$tmp" "$entry"
fi
