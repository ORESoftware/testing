#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly expected_repository='ORESoftware/testing'
readonly expected_pr='2'
readonly expected_login='ORESoftware'
readonly expected_head_ref='incubator/mcp-rust-libs'
readonly source_sha='069b1aa4251658c8348d2eb477ad71369d9b742b'
readonly source_subtree='mcp-rust-libs'
readonly source_manifest_sha256='b9ba89f29dca3e5020430d3a5d35967e523d3e94db9168a91cdf24a9bd5f2a33'
readonly k8s_trusted_sha='d94667636ebf66ac9423617e7cc0e7b88e11d4ed'
readonly tracking_pr='2'

[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]]
[[ "$source_manifest_sha256" =~ ^[0-9a-f]{64}$ ]]
[[ "$k8s_trusted_sha" =~ ^[0-9a-f]{40}$ ]]
test "$GITHUB_REPOSITORY" = "$expected_repository"

event="$(cat "$GITHUB_EVENT_PATH")"
if [[ "${GITHUB_EVENT_NAME:-}" = pull_request ]]; then
  test "$(jq -er '.pull_request.number | tostring' <<<"$event")" = "$expected_pr"
  test "$(jq -er '.pull_request.draft' <<<"$event")" = true
  test "$(jq -er '.pull_request.user.login' <<<"$event")" = "$expected_login"
  test "$(jq -er '.pull_request.head.repo.full_name' <<<"$event")" = "$expected_repository"
  test "$(jq -er '.pull_request.head.ref' <<<"$event")" = "$expected_head_ref"
fi

readonly comment_token="${GITHUB_TOKEN:?job token is required for tracking comments}"
readonly auth_dir="${RUNNER_TEMP:?}/mcp-rust-libs-owner-auth"
readonly auth_log="${RUNNER_TEMP}/mcp-rust-libs-owner-auth.log"
auth_pid=''
token=''

comment() {
  GH_TOKEN="$comment_token" gh api --method POST \
    "repos/${GITHUB_REPOSITORY}/issues/${tracking_pr}/comments" \
    -f body="$1" >/dev/null || true
}

cleanup() {
  if [[ -n "$auth_pid" ]] && kill -0 "$auth_pid" 2>/dev/null; then
    kill "$auth_pid" 2>/dev/null || true
    wait "$auth_pid" 2>/dev/null || true
  fi
  unset GH_TOKEN GITHUB_TOKEN GITHUB_REPOSITORY_ADMIN_TOKEN token encoded
  rm -f "$auth_log"
  rm -rf "$auth_dir" "$RUNNER_TEMP/k8s-publisher"
}
trap cleanup EXIT

GH_TOKEN="$comment_token" gh api "repos/ORESoftware/testing/commits/$source_sha" --jq .sha | grep -Fx "$source_sha"
GH_TOKEN="$comment_token" gh api "repos/ORESoftware/k8s-cluster/commits/$k8s_trusted_sha" --jq .sha | grep -Fx "$k8s_trusted_sha"

mkdir -p "$auth_dir" "$RUNNER_TEMP/k8s-publisher"
GH_TOKEN="$comment_token" gh api \
  "repos/ORESoftware/k8s-cluster/contents/scripts/ops/publish_mcp_rust_libs.sh?ref=$k8s_trusted_sha" \
  --jq .content | tr -d '\n' | base64 --decode > "$RUNNER_TEMP/k8s-publisher/publish.sh"
chmod 700 "$RUNNER_TEMP/k8s-publisher/publish.sh"
bash -n "$RUNNER_TEMP/k8s-publisher/publish.sh"

export GH_CONFIG_DIR="$auth_dir"
: >"$auth_log"
run_url="https://github.com/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
comment "Exact canonical mcp-rust-libs owner authorization started: ${run_url}. Source is pinned to \`ORESoftware/testing@${source_sha}:${source_subtree}\` with manifest \`${source_manifest_sha256}\`."

(
  env -u GH_TOKEN -u GITHUB_TOKEN \
    GH_PROMPT_DISABLED=1 \
    NO_COLOR=1 \
    BROWSER=/bin/false \
    gh auth login \
      --hostname github.com \
      --git-protocol https \
      --web \
      --scopes repo \
      --insecure-storage
) >"$auth_log" 2>&1 &
auth_pid=$!

code=''
for _ in $(seq 1 45); do
  code="$(tr -d '\r' <"$auth_log" | grep -Eo '[A-Z0-9]{4}-[A-Z0-9]{4}' | head -n1 || true)"
  if [[ -n "$code" ]]; then
    break
  fi
  if ! kill -0 "$auth_pid" 2>/dev/null; then
    break
  fi
  sleep 1
done

if [[ -z "$code" ]]; then
  wait "$auth_pid" 2>/dev/null || true
  auth_pid=''
  comment "GitHub CLI did not emit a device code for ${run_url}."
  sed -E 's/[A-Z0-9]{4}-[A-Z0-9]{4}/[REDACTED-CODE]/g' "$auth_log" >&2 || true
  exit 1
fi

instruction="Open **https://github.com/login/device** and enter **\`${code}\`** now. The workflow accepts only account \`ORESoftware\` and publishes only pinned source \`ORESoftware/testing@${source_sha}:${source_subtree}\`. Run: ${run_url}."
comment "$instruction"
echo "DEVICE_CODE=$code"
echo "::notice title=GitHub device authorization::Open https://github.com/login/device and enter $code"

set +e
wait "$auth_pid"
auth_rc=$?
set -e
auth_pid=''
if [[ "$auth_rc" -ne 0 ]]; then
  comment "Device authorization failed or expired for ${run_url}."
  sed -E 's/[A-Z0-9]{4}-[A-Z0-9]{4}/[REDACTED-CODE]/g' "$auth_log" >&2 || true
  exit "$auth_rc"
fi
rm -f "$auth_log"

actual_login="$(env -u GH_TOKEN -u GITHUB_TOKEN gh api user --jq .login)"
test "$actual_login" = "$expected_login"
token="$(env -u GH_TOKEN -u GITHUB_TOKEN gh auth token)"
test -n "$token"
[[ "$token" != *[[:space:]]* ]]
echo "::add-mask::$token"
encoded="$(printf '%s' "$token" | base64 --wrap=0)"

printf '%s\n' "$encoded" | \
  env -u GH_TOKEN -u GITHUB_TOKEN -u GITHUB_REPOSITORY_ADMIN_TOKEN -u CODEX_HOME \
    bash "$RUNNER_TEMP/k8s-publisher/publish.sh" "$k8s_trusted_sha"

metadata="$(env -u GH_TOKEN -u GITHUB_TOKEN gh api repos/ORESoftware/mcp-rust-libs)"
jq -e '
  .owner.login == "ORESoftware" and
  .visibility == "public" and
  .default_branch == "main"
' <<<"$metadata" >/dev/null
target_id="$(jq -er '.id' <<<"$metadata")"
comment "Created and verified public \`ORESoftware/mcp-rust-libs\` (repository id \`${target_id}\`) from exact pinned source. Run: ${run_url}."
