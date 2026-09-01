
#!/bin/sh
set -eu
missing=0
for tool in cargo rustc node npm dart gleam; do
  if command -v "$tool" >/dev/null 2>&1; then
    printf '%s: ' "$tool"
    "$tool" --version 2>/dev/null | head -n 1 || true
  else
    echo "$tool: missing"
    missing=1
  fi
done
if [ "${REQUIRE_ALL_TOOLCHAINS:-0}" = "1" ] && [ "$missing" -ne 0 ]; then
  exit 1
fi
