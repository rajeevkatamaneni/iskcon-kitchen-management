#!/usr/bin/env python3
"""Mint a Kitchen Assistant agent token for LOCAL DEVELOPMENT ONLY (E10-T8).

Why this exists: until the assistant API (E10-T10) mints tokens itself, the only way to point a
hand-run `claude -p` at a local /mcp is to make a token the server will accept. This reproduces
`AgentTokenService.mint` byte-for-byte in shape:

  header  {"alg":"HS256","typ":"JWT"}
  claims  sub (users.id), tenant, conv, scope (space-separated, sorted), iat, exp (iat + 300 s)
  signed  HMAC-SHA256 with the base64-decoded `kms.assistant.token.key`

It needs the same key the local API was started with (KMS_ASSISTANT_TOKEN_KEY). It is a dev tool:
the key it is given must never be a deployed environment's key, and nothing here talks to anything
but stdout. Python stdlib only, so it runs on a fresh machine with no installs.

Usage:
  KMS_ASSISTANT_TOKEN_KEY=<base64> mint-token.py --user <uuid> --tenant <uuid> [--conv <uuid>]
      [--scope "agent:read"] [--ttl 300]
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

KNOWN_SCOPES = {"agent:read", "agent:propose"}


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def mint(key: bytes, user: str, tenant: str, conv: str, scopes: set, ttl: int) -> str:
    if len(key) < 32:
        sys.exit("key must be at least 256 bits once base64-decoded, as AgentTokenService requires")
    if not scopes or not scopes <= KNOWN_SCOPES:
        sys.exit(f"scopes must be a non-empty subset of {sorted(KNOWN_SCOPES)}")
    for name, value in (("user", user), ("tenant", tenant), ("conv", conv)):
        uuid.UUID(value)  # raises on a non-UUID, which the server would refuse anyway
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "sub": user,
        "tenant": tenant,
        "conv": conv,
        "scope": " ".join(sorted(scopes)),
        "iat": now,
        "exp": now + ttl,
    }
    signing_input = (
        b64url(json.dumps(header, separators=(",", ":")).encode())
        + "."
        + b64url(json.dumps(claims, separators=(",", ":")).encode())
    )
    sig = hmac.new(key, signing_input.encode("ascii"), hashlib.sha256).digest()
    return signing_input + "." + b64url(sig)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--user", required=True)
    p.add_argument("--tenant", required=True)
    p.add_argument("--conv", default=None, help="conversation id; a fresh UUID when omitted")
    p.add_argument("--scope", default="agent:read")
    p.add_argument("--ttl", type=int, default=300)
    a = p.parse_args()
    raw = os.environ.get("KMS_ASSISTANT_TOKEN_KEY", "").strip()
    if not raw:
        sys.exit("KMS_ASSISTANT_TOKEN_KEY is not set")
    key = base64.b64decode(raw)
    print(mint(key, a.user, a.tenant, a.conv or str(uuid.uuid4()), set(a.scope.split()), a.ttl))


if __name__ == "__main__":
    main()
