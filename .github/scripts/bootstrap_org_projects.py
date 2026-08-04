#!/usr/bin/env python3
"""One-shot GitHub Projects v2 bootstrap using an ephemeral RSA handoff."""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

API = "https://api.github.com"
GRAPHQL = f"{API}/graphql"
REPOSITORY = os.environ["BOOTSTRAP_REPOSITORY"]
GITHUB_TOKEN = os.environ["GH_TOKEN"]
NONCE = os.environ["BOOTSTRAP_NONCE"]
PUBLIC_KEY_PATH = os.environ["BOOTSTRAP_PUBLIC_KEY_PATH"]
CIPHERTEXT_PATH = os.environ["BOOTSTRAP_CIPHERTEXT_PATH"]
RESULT_PATH = os.environ["BOOTSTRAP_RESULT_PATH"]
BRANCH = os.environ.get("BOOTSTRAP_BRANCH", "master")

ORGS = [
    "channelsiege",
    "OmniBlitz",
    "streamkore",
    "hypeblitz",
    "agent-pontifex",
    "fanwaave",
    "3FA-app",
    "messaging-intel",
    "akrion-sim",
    "athlet-o",
    "benefactor-cc",
    "canonical-cloud",
    "claritas-viz",
    "cliptown",
    "daedalus-fab",
    "declarative-migrations",
    "fiducia-cloud",
    "anticaptrad",
    "opto-sync",
    "quaestor-ledger",
    "sagitta-stack",
    "shared-auth",
    "scintilla-run",
    "rust-ssr-demos",
    "sonus-auris",
    "usa-acc",
    "voxletra",
    "zed-pkg",
    "zed-pkg-test",
    "memebank",
    "meta-agents-demo",
    "networking-components",
    "StreemPilot",
    "unreal-unity-poc",
    "file-tunnel",
    "hypesiege",
    "discrete-event-systems",
    "drone-mngr",
    "fifa-math",
    "gha-indie-worker",
]

LIST_QUERY = """
query($login:String!) {
  organization(login:$login) {
    id
    projectsV2(first:100) {
      nodes { id number title url closed }
    }
  }
}
"""
CREATE_MUTATION = """
mutation($ownerId:ID!, $title:String!) {
  createProjectV2(input:{ownerId:$ownerId, title:$title}) {
    projectV2 { id number title url closed }
  }
}
"""
REOPEN_MUTATION = """
mutation($projectId:ID!) {
  updateProjectV2(input:{projectId:$projectId, closed:false}) {
    projectV2 { id number title url closed }
  }
}
"""


def api_request(
    method: str,
    url: str,
    token: str,
    payload: dict[str, Any] | None = None,
) -> Any:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
            "User-Agent": "org-project-bootstrap/1.0",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = response.read()
            return None if not body else json.loads(body.decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub API {method} {url} failed: HTTP {exc.code}: {body}") from exc


def contents_url(path: str) -> str:
    return f"{API}/repos/{REPOSITORY}/contents/{path}"


def put_file(path: str, data: bytes, message: str) -> None:
    api_request(
        "PUT",
        contents_url(path),
        GITHUB_TOKEN,
        {
            "message": message,
            "content": base64.b64encode(data).decode("ascii"),
            "branch": BRANCH,
        },
    )


def get_file(path: str) -> tuple[bytes, str] | None:
    try:
        response = api_request("GET", contents_url(path), GITHUB_TOKEN)
    except RuntimeError as exc:
        if "HTTP 404" in str(exc):
            return None
        raise
    encoded = "".join(response.get("content", "").splitlines())
    return base64.b64decode(encoded), response["sha"]


def delete_file(path: str, message: str) -> None:
    current = get_file(path)
    if current is None:
        return
    _, sha = current
    try:
        api_request(
            "DELETE",
            contents_url(path),
            GITHUB_TOKEN,
            {"message": message, "sha": sha, "branch": BRANCH},
        )
    except RuntimeError:
        pass


def graphql(token: str, query: str, variables: dict[str, Any]) -> dict[str, Any]:
    response = api_request(
        "POST", GRAPHQL, token, {"query": query, "variables": variables}
    )
    if not isinstance(response, dict):
        raise RuntimeError("GitHub GraphQL returned a non-object response")
    return response


def project_result(org: str, action: str, project: dict[str, Any]) -> dict[str, Any]:
    return {
        "org": org,
        "action": action,
        "title": project.get("title"),
        "number": project.get("number"),
        "url": project.get("url"),
    }


def ensure_project(token: str, org: str) -> dict[str, Any]:
    title = f"{org}-project"
    lookup = graphql(token, LIST_QUERY, {"login": org})
    if lookup.get("errors"):
        return {"org": org, "title": title, "action": "failed", "errors": lookup["errors"]}

    organization = (lookup.get("data") or {}).get("organization")
    if not organization:
        return {
            "org": org,
            "title": title,
            "action": "failed",
            "error": "Organization not visible to the token",
        }

    existing = next(
        (
            project
            for project in organization.get("projectsV2", {}).get("nodes", [])
            if project.get("title") == title
        ),
        None,
    )
    if existing:
        if existing.get("closed"):
            reopened = graphql(token, REOPEN_MUTATION, {"projectId": existing["id"]})
            if reopened.get("errors"):
                return {
                    "org": org,
                    "title": title,
                    "action": "failed",
                    "errors": reopened["errors"],
                }
            project = ((reopened.get("data") or {}).get("updateProjectV2") or {}).get(
                "projectV2"
            )
            if not project:
                return {
                    "org": org,
                    "title": title,
                    "action": "failed",
                    "error": "Reopen mutation returned no project",
                }
            return project_result(org, "reopened", project)
        return project_result(org, "existing", existing)

    created = graphql(
        token,
        CREATE_MUTATION,
        {"ownerId": organization["id"], "title": title},
    )
    if created.get("errors"):
        return {"org": org, "title": title, "action": "failed", "errors": created["errors"]}
    project = ((created.get("data") or {}).get("createProjectV2") or {}).get("projectV2")
    if not project:
        return {
            "org": org,
            "title": title,
            "action": "failed",
            "error": "Create mutation returned no project",
        }
    return project_result(org, "created", project)


def secure_remove(path: Path) -> None:
    if not path.exists():
        return
    subprocess.run(["shred", "-u", str(path)], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    path.unlink(missing_ok=True)


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="org-project-bootstrap-") as tmp:
        tmpdir = Path(tmp)
        private_key = tmpdir / "private.pem"
        public_key = tmpdir / "public.pem"
        encrypted_token = tmpdir / "token.enc"
        decrypted_token = tmpdir / "token.txt"

        subprocess.run(
            [
                "openssl",
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:4096",
                "-out",
                str(private_key),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        subprocess.run(
            ["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

        put_file(
            PUBLIC_KEY_PATH,
            public_key.read_bytes(),
            "ci: publish ephemeral project-bootstrap public key",
        )

        ciphertext_b64: bytes | None = None
        for _ in range(300):
            remote = get_file(CIPHERTEXT_PATH)
            if remote and remote[0].strip():
                ciphertext_b64 = remote[0].strip()
                break
            time.sleep(3)

        if not ciphertext_b64:
            result = {
                "nonce": NONCE,
                "created": 0,
                "existing": 0,
                "reopened": 0,
                "failed": len(ORGS),
                "results": [
                    {
                        "org": org,
                        "title": f"{org}-project",
                        "action": "failed",
                        "error": "Encrypted credential file was not received",
                    }
                    for org in ORGS
                ],
            }
            put_file(
                RESULT_PATH,
                (json.dumps(result, indent=2) + "\n").encode("utf-8"),
                "ci: record organization project bootstrap failure",
            )
            delete_file(PUBLIC_KEY_PATH, "ci: remove expired project-bootstrap public key")
            return 1

        encrypted_token.write_bytes(base64.b64decode(ciphertext_b64))
        subprocess.run(
            [
                "openssl",
                "pkeyutl",
                "-decrypt",
                "-inkey",
                str(private_key),
                "-in",
                str(encrypted_token),
                "-out",
                str(decrypted_token),
                "-pkeyopt",
                "rsa_padding_mode:oaep",
                "-pkeyopt",
                "rsa_oaep_md:sha256",
                "-pkeyopt",
                "rsa_mgf1_md:sha256",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

        user_token = decrypted_token.read_text(encoding="utf-8").strip()
        if not user_token:
            raise RuntimeError("Decrypted credential was empty")
        print(f"::add-mask::{user_token}")

        results: list[dict[str, Any]] = []
        for org in ORGS:
            try:
                results.append(ensure_project(user_token, org))
            except Exception as exc:
                results.append(
                    {
                        "org": org,
                        "title": f"{org}-project",
                        "action": "failed",
                        "error": str(exc),
                    }
                )

        counts = {
            action: sum(1 for item in results if item.get("action") == action)
            for action in ("created", "existing", "reopened", "failed")
        }
        result = {"nonce": NONCE, **counts, "results": results}
        put_file(
            RESULT_PATH,
            (json.dumps(result, indent=2) + "\n").encode("utf-8"),
            "ci: record organization project bootstrap results",
        )

        delete_file(PUBLIC_KEY_PATH, "ci: remove project-bootstrap public key")
        delete_file(CIPHERTEXT_PATH, "ci: remove project-bootstrap ciphertext")
        for path in (private_key, public_key, encrypted_token, decrypted_token):
            secure_remove(path)

        return 1 if counts["failed"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"bootstrap failed: {exc}", file=sys.stderr)
        raise
