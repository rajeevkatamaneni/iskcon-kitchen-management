"""Signing in, and calling the API as a real person.

Standard library only, deliberately: these scripts get run on whatever machine is to hand, and a
pip install is one more thing to go wrong at the wrong moment.
"""

from __future__ import annotations

import json
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Optional

from .log import note, warn

IDENTITY = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword"

# A Firebase ID token lasts an hour. Seeding three months of operations takes longer than that in
# places, so tokens are refreshed well before the edge rather than at it.
TOKEN_LIFETIME = 3600
REFRESH_MARGIN = 300


class ApiError(RuntimeError):
    """A refusal from the API, with the KMS code pulled out so a caller can branch on it."""

    def __init__(self, status: int, body: Any, method: str, path: str) -> None:
        self.status = status
        self.body = body
        self.method = method
        self.path = path
        self.code = ""
        self.field_errors: list[dict] = []
        if isinstance(body, dict):
            self.code = body.get("code") or ""
            self.field_errors = body.get("fieldErrors") or []
        message = body.get("message") if isinstance(body, dict) else str(body)
        super().__init__(f"{method} {path} -> {status} {self.code} {message}")

    def field(self, name: str) -> Optional[str]:
        """The message against one field, if the refusal named it."""
        for fe in self.field_errors:
            if fe.get("field") == name:
                return fe.get("message")
        return None

    def detail(self, name: str) -> Optional[str]:
        """A value carried in fieldErrors, e.g. existingIngredientId on a duplicate."""
        return self.field(name)


def firebase_api_key() -> str:
    """The web API key. Not a secret — it is already committed — but overridable."""
    key = os.environ.get("KMS_SEED_FIREBASE_API_KEY")
    if key:
        return key
    here = Path(__file__).resolve().parents[3]
    example = here / "frontend" / ".env.local.example"
    if example.exists():
        found = re.search(r"NEXT_PUBLIC_FIREBASE_API_KEY=(\S+)", example.read_text())
        if found:
            return found.group(1)
    raise RuntimeError(
        "No Firebase web API key. Set KMS_SEED_FIREBASE_API_KEY, or make sure "
        "frontend/.env.local.example is present."
    )


def password_for(email: str) -> str:
    """
    The account's password, from the environment and never from a file in this repository.

    KMS_SEED_PASSWORD is the shared one. One account has its own, so a per-account override is
    read first: ikms.temple-admin.1@… looks for KMS_SEED_PASSWORD_TEMPLE_ADMIN_1.
    """
    local = email.split("@")[0]
    if local.startswith("ikms."):
        local = local[len("ikms."):]
    specific = "KMS_SEED_PASSWORD_" + re.sub(r"[^A-Za-z0-9]+", "_", local).upper()
    if os.environ.get(specific):
        return os.environ[specific]
    shared = os.environ.get("KMS_SEED_PASSWORD")
    if shared:
        return shared
    raise RuntimeError(
        f"No password for {email}. Set KMS_SEED_PASSWORD for the shared one, or {specific} "
        f"for this account. Never put one in the repository."
    )


def _request(method: str, url: str, *, headers: dict, body: Optional[bytes]) -> tuple[int, Any]:
    req = urllib.request.Request(url, data=body, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            raw = response.read()
            status = response.status
    except urllib.error.HTTPError as e:
        raw = e.read()
        status = e.code
    except urllib.error.URLError as e:
        raise RuntimeError(f"{method} {url} could not be reached: {e.reason}") from e

    if not raw:
        return status, None
    try:
        return status, json.loads(raw)
    except json.JSONDecodeError:
        return status, raw.decode("utf-8", "replace")


class Session:
    """One signed-in person. Every call this object makes is made as them."""

    def __init__(self, base: str, email: str, tenant: Optional[str] = None) -> None:
        self.base = base.rstrip("/")
        self.email = email
        self.tenant = tenant
        self._token = ""
        self._got_at = 0.0
        self.whoami: dict = {}
        self.calls = 0

    # ---- auth -------------------------------------------------------------

    def _sign_in(self) -> None:
        payload = json.dumps({
            "email": self.email,
            "password": password_for(self.email),
            "returnSecureToken": True,
        }).encode()
        status, body = _request(
            "POST", f"{IDENTITY}?key={firebase_api_key()}",
            headers={"Content-Type": "application/json"}, body=payload)
        if status != 200 or not isinstance(body, dict) or "idToken" not in body:
            reason = body.get("error", {}).get("message") if isinstance(body, dict) else body
            raise RuntimeError(f"Could not sign {self.email} in to Firebase: {reason}")
        self._token = body["idToken"]
        self._got_at = time.time()

    def token(self) -> str:
        if not self._token or time.time() - self._got_at > TOKEN_LIFETIME - REFRESH_MARGIN:
            self._sign_in()
        return self._token

    # ---- calls ------------------------------------------------------------

    def call(self, method: str, path: str, payload: Any = None, *,
             allow: tuple[int, ...] = ()) -> Any:
        """
        One request. Returns the parsed body, or raises ApiError.

        `allow` names statuses that are an answer rather than a failure — a 409 from creating
        something that already exists, for instance, which on a re-run is the normal outcome.
        """
        url = f"{self.base}{path}"
        headers = {"Authorization": f"Bearer {self.token()}", "Accept": "application/json"}
        if self.tenant:
            # Only meaningful for someone who belongs to more than one temple, but harmless
            # otherwise and it removes a whole class of "which temple did that go to".
            headers["X-KMS-Temple"] = self.tenant
        body = None
        if payload is not None:
            body = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"

        last: Optional[Exception] = None
        for attempt in range(3):
            self.calls += 1
            status, parsed = _request(method, url, headers=headers, body=body)

            if 200 <= status < 300 or status in allow:
                return parsed

            if status == 401 and attempt == 0:
                # The token aged out mid-run. One silent re-sign, then treat it as real.
                note(f"token expired for {self.email}, signing in again")
                self._token = ""
                headers["Authorization"] = f"Bearer {self.token()}"
                continue

            if status in (429, 502, 503, 504) and attempt < 2:
                wait = 2 ** attempt
                warn(f"{method} {path} -> {status}, retrying in {wait}s")
                time.sleep(wait)
                continue

            raise ApiError(status, parsed, method, path)

        raise last or RuntimeError(f"{method} {path} failed after retries")

    def get(self, path: str, **kw) -> Any:
        return self.call("GET", path, **kw)

    def post(self, path: str, payload: Any = None, **kw) -> Any:
        return self.call("POST", path, payload, **kw)

    def put(self, path: str, payload: Any = None, **kw) -> Any:
        return self.call("PUT", path, payload, **kw)

    def patch(self, path: str, payload: Any = None, **kw) -> Any:
        return self.call("PATCH", path, payload, **kw)

    def delete(self, path: str, **kw) -> Any:
        return self.call("DELETE", path, **kw)


_sessions: dict[str, Session] = {}


def sign_in(base: str, email: str, tenant: Optional[str] = None) -> Session:
    """A session for this person, reused across phases in one run."""
    key = f"{base}|{email}"
    if key not in _sessions:
        session = Session(base, email, tenant)
        session.whoami = session.get("/api/v1/whoami")
        _sessions[key] = session
    return _sessions[key]
