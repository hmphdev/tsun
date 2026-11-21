#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )); then
    printf 'Usage: %s [DEMO_DIRECTORY]\n' "$0" >&2
    exit 2
fi

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
target=${1:-"${XDG_STATE_HOME:-$HOME/.local/state}/tsun-demo"}
umask 077
mkdir -p -- "$(dirname -- "$target")"
if ! mkdir -- "$target"; then
    printf 'Choose a new directory for the demo; existing data was not overwritten.\n' >&2
    exit 1
fi
target=$(cd -- "$target" && pwd -P)
state="$target/state"
mkdir -- "$state"
cp -R -- "$repo/examples/workspace" "$target/workspace"

openssl rand -hex 32 > "$state/token"
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
    -noenc -days 825 -sha256 -subj '/CN=Tsun daemon' \
    -addext 'subjectAltName=IP:127.0.0.1' -addext 'extendedKeyUsage=serverAuth' \
    -keyout "$state/key.pem" -out "$state/cert.pem"

python3 - "$repo/examples/config.json" "$state/config.json" "$target/workspace" <<'PY'
import json
from pathlib import Path
import sys

source, destination, workspace = sys.argv[1:]
config = json.loads(Path(source).read_text(encoding="utf-8"))
config["workspaces"][0]["root"] = workspace
Path(destination).write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
PY

printf 'Daemon state: %s\nWorkspace copy: %s\n' "$state" "$target/workspace"
printf 'Start with: %q %q\n' "$repo/build/mobile-harness" "$state"
