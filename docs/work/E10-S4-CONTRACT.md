# E10-S4 — build contract (draft cards with a working Confirm)

Plan: `docs/work/E10-KITCHEN-ASSISTANT-PLAN.md` §4 S4 and §11 (decision D-a: every write is a draft
the server runs only when the person presses Confirm). Branch `agentic-mvp`.

**Scope.** One general mechanism, proven with one real kind: a **draft purchase order** for one
vendor (Rajeev's capability 4). Creating the draft PO is the write; sending it is not in S4.
The mechanism must let later kinds (meal plan, volunteer shift, send PO) plug in as one handler each.

## Shape the browser already uses (frontend/lib/api.ts, `AssistantProposal`)

`{ actionId, kind: "MEAL_PLAN"|"PURCHASE_ORDER", status: "PROPOSED"|"CONFIRMED"|"REJECTED"|"EXPIRED",
title, facts[{label,value,hint?}], lines[{name, localName, quantity, unit, rate, amount}], confirmSays,
expiresAt }` — plus one new field both sides add: `result: { label: string, href: string } | null`
(e.g. `{label: "PO-0047", href: "/orders/<id>"}`), set once confirmed.

## Endpoints and events

- SSE event `proposal` with data `AssistantProposal`, emitted by the relay when a propose tool created a
  draft during the run (before `done`).
- `POST /api/v1/assistant/actions/{actionId}/confirm` → 200 `AssistantProposal` (status CONFIRMED,
  `result` set). Runs the existing service as the **confirming person** (their own login), under the
  existing permission of the thing done (here MANAGE_PURCHASE_ORDERS) plus CONFIRM_ASSISTANT_ACTION.
- `POST /api/v1/assistant/actions/{actionId}/reject` → 200 `AssistantProposal` (status REJECTED).
- Refusals as JSON: KMS-400194 expired (24 h), KMS-400195 changed since drafted (hash), KMS-400196
  already handled. A second Confirm of a CONFIRMED draft is a no-op that returns the same result
  (idempotent), not an error — say which in the proof; the card must show the confirmed state either way.
- Only the person who owns the conversation can confirm or reject; another person or temple gets 404.
- `GET /api/v1/assistant/conversations/{id}`: each assistant message carries `proposals:
  AssistantProposal[]` (current status) so a reload shows the cards.

## Path ownership

| Task | Owns |
|---|---|
| S4-API (backend) | `backend/**` only: pending-action service, propose tools, confirm/reject endpoints, relay `proposal` event, tests, `runner/prompts/kitchen-assistant.md` (prompt rules for drafting) |
| S4-UI (frontend) | `frontend/components/assistant/**`, the assistant section of `frontend/lib/api.ts`, `frontend/__tests__/assistant-*` |
