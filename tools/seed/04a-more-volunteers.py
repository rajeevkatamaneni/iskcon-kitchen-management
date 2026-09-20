#!/usr/bin/env python3
"""
Phase 04a — ten more volunteers, each one registering themselves.

**Why.** A festival day at this temple runs four services of twelve places — forty-eight places in
all — and the temple had seven volunteer accounts. However the roster is filled, it reads as a
festival nobody came to, which is the opposite of true. The ceiling was the roll, not the rota.

**How, and why it has to be this way.** There is no admin path to add a volunteer, on purpose. The
comment on `UserController` says it plainly: *"A temple's people arrive two ways and neither is an
admin typing somebody's details: devotees register themselves, and staff are hired."* So each of
these people signs in and registers themselves at the temple, through
`POST /api/v1/temples/{templeId}/join`, exactly as a devotee does on their phone.

That needs a Firebase account each, because registering is something a signed-in person does. The
accounts are made through the Identity Toolkit admin API with the caller's own gcloud credentials,
with no password: **these accounts cannot be signed into with a password at all.** They are reached
the way every other seed script reaches an account, by minting a token (see `common/api.py`), and
so there is no new credential anywhere for anyone to leak.

Re-runnable: an account that already exists is reused, and somebody who has already joined is left
alone.

    python3 tools/seed/04a-more-volunteers.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, step, info  # noqa: E402
from common.api import FIREBASE_PROJECT, Session, _gcloud, _request  # noqa: E402

PHASE = "04a"

# The people. Names in the register the temple actually keeps — a mix of initiated names and
# householder names, because both turn up to serve. The local part follows the convention the
# existing accounts already use, so the whole roll reads as one list.
NEW_VOLUNTEERS = [
    ("ikms.volunteer.6",  "Ananta Das",          "+919845100106"),
    ("ikms.volunteer.7",  "Radhika Sharma",      "+919845100107"),
    ("ikms.volunteer.8",  "Nitai Gauranga Das",  "+919845100108"),
    ("ikms.volunteer.9",  "Sita Thakur",         "+919845100109"),
    ("ikms.volunteer.10", "Murari Gupta Das",    "+919845100110"),
    ("ikms.volunteer.11", "Padmavati Devi Dasi", "+919845100111"),
    ("ikms.volunteer.12", "Hari Prasad Nayak",   "+919845100112"),
    ("ikms.volunteer.13", "Tulasi Devi Dasi",    "+919845100113"),
    ("ikms.volunteer.14", "Keshava Murthy",      "+919845100114"),
    ("ikms.volunteer.15", "Jahnavi Rao",         "+919845100115"),
]

DOMAIN = "trading4good.org"


def firebase_account(email: str, display_name: str) -> str:
    """The uid for this person, creating the account if it is not there yet."""
    token = _gcloud("auth", "print-access-token")
    headers = {
        "Authorization": f"Bearer {token}",
        "x-goog-user-project": FIREBASE_PROJECT,
        "Content-Type": "application/json",
    }
    status, body = _request(
        "POST",
        f"https://identitytoolkit.googleapis.com/v1/projects/{FIREBASE_PROJECT}/accounts:lookup",
        headers=headers, body=json.dumps({"email": [email]}).encode())
    if status == 200 and isinstance(body, dict) and body.get("users"):
        return body["users"][0]["localId"]

    status, body = _request(
        "POST",
        f"https://identitytoolkit.googleapis.com/v1/projects/{FIREBASE_PROJECT}/accounts",
        headers=headers,
        # emailVerified matters: an unverified account cannot sign in to this application, which
        # is the trap recorded against the original test accounts.
        body=json.dumps({"email": email, "emailVerified": True,
                         "displayName": display_name}).encode())
    if status != 200 or not isinstance(body, dict) or "localId" not in body:
        raise RuntimeError(f"Could not create a Firebase account for {email}: {status} {body}")
    return body["localId"]


def main() -> int:
    args = parse_args(PHASE)
    tally = Tally("04a — ten more volunteers")

    step("their sign-in accounts")
    for local, name, _phone in NEW_VOLUNTEERS:
        email = f"{local}@{DOMAIN}"
        try:
            uid = firebase_account(email, name)
        except RuntimeError as e:
            tally.problem(str(e))
            continue
        info(f"{name:22} {email}  {uid}")

    step("each of them registering at the temple")
    for local, name, phone in NEW_VOLUNTEERS:
        email = f"{local}@{DOMAIN}"
        first, _, last = name.partition(" ")
        # Not `sign_in`: that asks `/whoami` on the way in, and somebody who has not joined yet has
        # no account to ask about — the application answers KMS-400020, "you don't have an account
        # at this temple yet", which is the correct answer and not an error here.
        person = Session(args.api, email, args.tenant)
        try:
            who = person.get("/api/v1/whoami")
        except ApiError as e:
            if e.code != "KMS-400020":
                tally.problem(f"{name}: {e}")
                continue
            who = {}
        if who.get("tenantId"):
            tally.kept("volunteer", f"{name} is already a member")
            continue
        if args.dry_run:
            info(f"{name} would join the temple")
            continue
        try:
            person.post(f"/api/v1/temples/{args.tenant}/join", {
                "firstName": first,
                "lastName": last or first,
                "phone": phone,
                "email": email,
            })
        except ApiError as e:
            tally.problem(f"{name} could not join: {e}")
            continue
        tally.made("volunteer", name)

    return tally.report()


if __name__ == "__main__":
    raise SystemExit(main())
