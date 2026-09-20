"""Signing in, and calling the API as a real person.

Standard library only, deliberately: these scripts get run on whatever machine is to hand, and a
pip install is one more thing to go wrong at the wrong moment.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Optional

from .log import note, warn

IDENTITY = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword"
CUSTOM_TOKEN_EXCHANGE = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken"

# A Firebase ID token lasts an hour. Seeding three months of operations takes longer than that in
# places, so tokens are refreshed well before the edge rather than at it.
TOKEN_LIFETIME = 3600
REFRESH_MARGIN = 300


class Unreachable(RuntimeError):
    """The request never got an answer: a timeout, a reset, a DNS blip. Worth retrying."""


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


def password_for(email: str) -> Optional[str]:
    """
    The account's password, from the environment and never from a file in this repository.

    KMS_SEED_PASSWORD is the shared one. One account has its own, so a per-account override is
    read first: ikms.temple-admin.1@… looks for KMS_SEED_PASSWORD_TEMPLE_ADMIN_1.

    Returns None when there is no password to be had, because there is a second way in that needs
    no password at all — see `_custom_token` below.
    """
    local = email.split("@")[0]
    if local.startswith("ikms."):
        local = local[len("ikms."):]
    specific = "KMS_SEED_PASSWORD_" + re.sub(r"[^A-Za-z0-9]+", "_", local).upper()
    if os.environ.get(specific):
        return os.environ[specific]
    return os.environ.get("KMS_SEED_PASSWORD")


# The Firebase project the accounts live in, and the admin SDK service account that can speak for
# any of them. Neither is a secret; the authority is the caller's own gcloud credentials.
FIREBASE_PROJECT = os.environ.get("KMS_SEED_FIREBASE_PROJECT", "iskcon-kms-2026-620ee")
ADMIN_SA = os.environ.get(
    "KMS_SEED_ADMIN_SA", f"firebase-adminsdk-fbsvc@{FIREBASE_PROJECT}.iam.gserviceaccount.com")
CUSTOM_TOKEN_AUD = ("https://identitytoolkit.googleapis.com/"
                    "google.identity.identitytoolkit.v1.IdentityToolkit")


def _gcloud(*args: str) -> str:
    out = subprocess.run(("gcloud",) + args, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"gcloud {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout.strip()


def _uid_for(email: str) -> str:
    """The Firebase uid behind an email, looked up as whoever is signed in to gcloud."""
    body = json.dumps({"email": [email]}).encode()
    status, payload = _request(
        "POST",
        f"https://identitytoolkit.googleapis.com/v1/projects/{FIREBASE_PROJECT}/accounts:lookup",
        headers={
            "Authorization": f"Bearer {_gcloud('auth', 'print-access-token')}",
            # Without this the call 403s about a missing quota project, which reads like a
            # permission problem and is not one.
            "x-goog-user-project": FIREBASE_PROJECT,
            "Content-Type": "application/json",
        },
        body=body)
    users = payload.get("users") if isinstance(payload, dict) else None
    if status != 200 or not users:
        raise RuntimeError(f"No Firebase account for {email} (status {status}): {payload}")
    return users[0]["localId"]


def _custom_token(email: str) -> str:
    """
    Sign in as somebody **without their password**.

    The test accounts' passwords are deliberately not in this repository, and a script should not
    need one to act as a person. Firebase provides for exactly this: the admin SDK service account
    may mint a custom token asserting any uid, and Identity Toolkit will exchange it for the same
    id token a real sign-in produces. Nothing about the account is touched or changed.

    The authority is the caller's own gcloud credentials, which must hold
    `roles/iam.serviceAccountTokenCreator` on the admin SDK service account. Granting it takes
    about thirty seconds to propagate, so a fresh grant may need one retry.
    """
    uid = _uid_for(email)
    now = int(time.time())
    claims = {"iss": ADMIN_SA, "sub": ADMIN_SA, "aud": CUSTOM_TOKEN_AUD,
              "iat": now, "exp": now + 3600, "uid": uid}
    with tempfile.TemporaryDirectory() as tmp:
        claims_path = Path(tmp) / "claims.json"
        jwt_path = Path(tmp) / "custom.jwt"
        claims_path.write_text(json.dumps(claims))
        _gcloud("iam", "service-accounts", "sign-jwt", str(claims_path), str(jwt_path),
                f"--iam-account={ADMIN_SA}")
        signed = jwt_path.read_text().strip()

    status, body = _request(
        "POST", f"{CUSTOM_TOKEN_EXCHANGE}?key={firebase_api_key()}",
        headers={"Content-Type": "application/json"},
        body=json.dumps({"token": signed, "returnSecureToken": True}).encode())
    if status != 200 or not isinstance(body, dict) or "idToken" not in body:
        reason = body.get("error", {}).get("message") if isinstance(body, dict) else body
        raise RuntimeError(f"Could not exchange a custom token for {email}: {reason}")
    return body["idToken"]


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
        # Raised as a distinct type so `call` can retry it. A socket timeout or a reset connection
        # is not a refusal by the application — it is the network — and over a run that makes tens
        # of thousands of calls to a Cloud Run service it will happen. Treating it as fatal meant
        # phase 01 died on a single timed-out /whoami after twenty minutes of work.
        raise Unreachable(f"{method} {url} could not be reached: {e.reason}") from e
    except TimeoutError as e:
        raise Unreachable(f"{method} {url} timed out") from e

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
        """
        Become this person. A password if the environment has one, otherwise a minted token.

        The password path is kept because it is the closest thing to what a real person does, and
        because it needs nothing but a network. Where there is no password — which is the normal
        case, since none of them live in this repository — the admin SDK mints a token for the
        account instead. Both produce the same id token and the server cannot tell them apart.
        """
        password = password_for(self.email)
        if password:
            payload = json.dumps({
                "email": self.email,
                "password": password,
                "returnSecureToken": True,
            }).encode()
            status, body = _request(
                "POST", f"{IDENTITY}?key={firebase_api_key()}",
                headers={"Content-Type": "application/json"}, body=payload)
            if status != 200 or not isinstance(body, dict) or "idToken" not in body:
                reason = body.get("error", {}).get("message") if isinstance(body, dict) else body
                raise RuntimeError(f"Could not sign {self.email} in to Firebase: {reason}")
            self._token = body["idToken"]
        else:
            self._token = _custom_token(self.email)
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
        attempts = 6
        for attempt in range(attempts):
            self.calls += 1
            try:
                status, parsed = _request(method, url, headers=headers, body=body)
            except Unreachable as e:
                last = e
                if attempt == attempts - 1:
                    raise
                wait = min(2 ** attempt, 20)
                warn(f"{method} {path} — {e}; retrying in {wait}s "
                     f"(attempt {attempt + 2} of {attempts})")
                time.sleep(wait)
                continue

            if 200 <= status < 300 or status in allow:
                return parsed

            if status == 401 and attempt == 0:
                # The token aged out mid-run. One silent re-sign, then treat it as real.
                note(f"token expired for {self.email}, signing in again")
                self._token = ""
                headers["Authorization"] = f"Bearer {self.token()}"
                continue

            if status in (429, 502, 503, 504) and attempt < attempts - 1:
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
