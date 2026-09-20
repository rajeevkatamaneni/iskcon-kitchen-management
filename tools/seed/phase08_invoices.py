#!/usr/bin/env python3
"""
Phase 08 — the bills, and paying them.

Rajeev's brief: "Then create invoices when creating invoices attach sample receipts that you
create then simulate paying invoices. In invoices do cancel invoice reduce the amount on the
invoice because we returned something and we got credit from the vendor or a credit for late
delivery. Anything else you can think of basically in invoices."

So every delivery gets billed, each bill carries a real uploaded receipt, and between them they
cover: paid in full by bank transfer, paid by UPI with a screenshot, paid in cash with a signed
note and a photograph of who took it, a credit note for the sack that went back, a second credit
for the late delivery, a part payment, and one invoice voided outright.

**The lines must match the delivery exactly.** Not a subset, not a summary: one invoice line per
delivered line, none added and none missed, or the save is refused with KMS-400170. So the lines
are built from `billable-deliveries` rather than from the order.

**And the totals must add up to the paisa** — `subTotal + GST + charges − discount == grandTotal`,
or KMS-400168. GST is 5% on food in India, which is what the temple would really be charged.

**A bill will not save without a real file.** The upload endpoint sniffs the first bytes, so the
attachment is a genuine PDF built for this invoice, naming the vendor, the number and the total.

    python3 tools/seed/phase08_invoices.py --api http://localhost:8091 --tenant <id>
"""

from __future__ import annotations

import mimetypes
import sys
import urllib.request
import uuid
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ApiError, Tally, parse_args, sign_in, step, info, note  # noqa: E402
from common.config import TEMPLE_ADMIN, kitchen_manager  # noqa: E402
from common.receipt import bill_pdf, photo_png  # noqa: E402

PHASE = "phase08"

GST_RATE = Decimal("0.05")     # 5% on food, the rate a temple's grocery bill carries


def money(value) -> Decimal:
    return Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def upload(session, path: str, filename: str, content: bytes, content_type: str) -> str:
    """
    Multipart upload. Written out by hand because the standard library has no multipart encoder
    and the whole point of this toolkit is that it needs nothing installed.
    """
    boundary = f"----kms-seed-{uuid.uuid4().hex}"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()

    request = urllib.request.Request(
        f"{session.base}{path}", data=body, method="POST",
        headers={
            "Authorization": f"Bearer {session.token()}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Accept": "application/json",
        })
    import json as _json
    with urllib.request.urlopen(request, timeout=120) as response:
        return _json.load(response)["id"]


def extra_args(parser) -> None:
    parser.add_argument("--round", type=int, default=1, dest="round_no",
                        help="which ordering round these bills belong to")


def main() -> int:
    args = parse_args(PHASE, extra_args)
    round_no = args.raw.round_no
    tally = Tally(f"phase 08 — invoices and payments (round {round_no})")

    manager = sign_in(args.api, kitchen_manager(args.api, args.tenant, needs_approval=False), args.tenant)
    # Paying, crediting and voiding are MANAGE_VENDOR_PAYMENTS, which only the Temple Admin holds.
    # A Kitchen Manager can record the bill and then gets 403 on all three.
    admin = sign_in(args.api, TEMPLE_ADMIN, args.tenant)

    vendors = manager.get("/api/v1/vendors")
    today = date.today()

    step("billing every delivery")
    invoices: list[dict] = []
    for position, vendor in enumerate(sorted(vendors, key=lambda v: v["name"])):
        billable = manager.get(
            f"/api/v1/vendor-invoices/billable-deliveries?vendorId={vendor['id']}")
        if not billable:
            continue

        key = f"{PHASE}.r{round_no}.invoice.{vendor['name']}"
        if args.state.has(key):
            invoices.append({"id": args.state.get(key), "vendor": vendor["name"]})
            tally.kept("invoice", vendor["name"])
            continue

        # Price every delivered line at what the temple expected to pay for it.
        lines, receipt_ids, described = [], [], []
        sub_total = Decimal("0")
        for delivery in billable:
            receipt_ids.append(delivery["receiptId"])
            for line in delivery["lines"]:
                quantity = Decimal(str(line["deliveredQty"]))
                if quantity <= 0:
                    continue
                supplies = manager.get(
                    f"/api/v1/vendors/supplies?ingredientId={line['ingredientId']}")
                rate = next((Decimal(str(s["lastPrice"])) for s in supplies
                             if s["vendorId"] == vendor["id"] and s.get("lastPrice")), Decimal("0"))
                amount = money(quantity * rate)
                sub_total += amount
                lines.append({
                    "goodsReceiptLineId": line["goodsReceiptLineId"],
                    "billedQty": float(quantity),
                    "unit": line["unit"],
                    "amount": float(amount),
                })
                described.append(f"{line['itemName']} {quantity} {line['unit']} @ {rate}")

        if not lines:
            continue

        gst = money(sub_total * GST_RATE)
        charges = money(Decimal("150")) if position % 3 == 0 else Decimal("0")
        discount = money(sub_total * Decimal("0.02")) if position % 4 == 1 else Decimal("0")
        grand = money(sub_total + gst + charges - discount)

        number = f"INV-2026-{1000 + round_no * 100 + position}"
        invoice_date = today - timedelta(days=4)

        pdf = bill_pdf(f"{vendor['name']} - Tax Invoice", [
            f"Invoice {number}",
            f"Date {invoice_date.strftime('%d %B %Y')}",
            f"GSTIN {vendor.get('gstin') or 'unregistered'}",
            "",
            *described[:18],
            *(["..."] if len(described) > 18 else []),
            "",
            f"Sub total      Rs {sub_total:,.2f}",
            f"GST at 5%      Rs {gst:,.2f}",
            *([f"Delivery       Rs {charges:,.2f}"] if charges else []),
            *([f"Less discount  Rs {discount:,.2f}"] if discount else []),
            f"Grand total    Rs {grand:,.2f}",
        ])
        try:
            attachment = upload(manager, "/api/v1/vendor-invoices/bill-uploads",
                                f"{number}.pdf", pdf, "application/pdf")
        except Exception as e:
            tally.problem(f"uploading the bill for {vendor['name']}: {e}")
            continue

        payload = {
            "vendorId": vendor["id"],
            "invoiceNumber": number,
            "invoiceDate": invoice_date.isoformat(),
            "dueDate": (invoice_date + timedelta(days=21)).isoformat(),
            "description": f"Supplies delivered to the temple store, {len(receipt_ids)} delivery(s)",
            "receiptIds": receipt_ids,
            "lines": lines,
            "gstAmount": float(gst),
            "otherCharges": float(charges),
            "otherChargesNote": "Delivery to the rear gate" if charges else None,
            "discount": float(discount),
            "grandTotal": float(grand),
            "billAttachmentId": attachment,
        }
        try:
            made = manager.post("/api/v1/vendor-invoices", payload)
            invoice = made["invoice"]
            args.state.put(key, invoice["id"])
            invoices.append({"id": invoice["id"], "vendor": vendor["name"],
                             "number": number, "amount": float(grand)})
            tally.made("invoice",
                       f"{number} {vendor['name']} — {len(lines)} line(s), Rs {grand:,.2f}")
        except ApiError as e:
            tally.problem(f"invoice for {vendor['name']}: {e}")

    if not invoices:
        tally.problem("nothing was billed — run phase 07 first")
        return tally.report()

    # ---- credit notes ------------------------------------------------------
    step("credit notes")
    for position, invoice in enumerate(invoices):
        detail = admin.get(f"/api/v1/vendor-invoices/{invoice['id']}")
        amount = Decimal(str(detail.get("amount") or 0))
        if position == 0:
            key, why, share = (f"{PHASE}.r{round_no}.credit.return.{invoice['id']}",
                               "Credit for the spoiled sack sent back on the 16th.",
                               Decimal("0.04"))
        elif position == 2:
            key, why, share = (f"{PHASE}.r{round_no}.credit.late.{invoice['id']}",
                               "Agreed credit for delivering four days after the date asked for.",
                               Decimal("0.03"))
        else:
            continue
        if args.state.has(key):
            tally.kept("credit note")
            continue
        credit = money(amount * share)
        if credit <= 0:
            continue
        try:
            admin.post(f"/api/v1/vendor-invoices/{invoice['id']}/credit",
                       {"amount": float(credit), "reason": why})
            args.state.put(key, True)
            tally.made("credit note",
                       f"{invoice.get('number', invoice['id'][:8])} reduced by Rs {credit:,.2f} — {why}")
        except ApiError as e:
            tally.problem(f"credit on {invoice.get('number')}: {e}")

    # ---- paying ------------------------------------------------------------
    step("paying")
    for position, invoice in enumerate(invoices):
        detail = admin.get(f"/api/v1/vendor-invoices/{invoice['id']}")
        if detail.get("status") == "VOIDED":
            continue
        owed = Decimal(str(detail.get("amount") or 0)) - Decimal(str(detail.get("creditedAmount") or 0))
        if owed <= 0:
            continue

        key = f"{PHASE}.r{round_no}.payment.{invoice['id']}"
        if args.state.has(key):
            tally.kept("payment")
            continue

        # The last one is left unpaid, and one is deliberately voided instead of paid, so the
        # payables screen has something on it.
        if position == len(invoices) - 1:
            tally.skip("payment", f"{invoice.get('number')} left outstanding on purpose")
            continue
        if position == 3:
            continue        # voided below

        method = ["BANK_TRANSFER", "UPI", "CASH"][position % 3]
        part = position == 1                      # one part payment, the rest in full
        amount = money(owed * Decimal("0.5")) if part else money(owed)

        payload = {
            "paidOn": (today - timedelta(days=2)).isoformat(),
            "amount": float(amount),
            "method": method,
            "note": "Part payment; balance agreed for the end of the month." if part else None,
        }
        try:
            if method == "CASH":
                # Cash needs three things: who took it, their signed note, and a photograph.
                signed = upload(admin, "/api/v1/vendor-invoices/payment-uploads?kind=CASH_SIGNED_NOTE",
                                "signed-note.pdf",
                                bill_pdf("Received with thanks", [
                                    f"Received Rs {amount:,.2f} in cash",
                                    f"From ISKCON South Bengaluru on {today - timedelta(days=2)}",
                                    f"Towards {invoice.get('number')}",
                                    "", "Signature: ______________________",
                                ]), "application/pdf")
                photo = upload(admin, "/api/v1/vendor-invoices/payment-uploads?kind=CASH_RECEIVER_PHOTO",
                               "receiver.png", photo_png(position + 7), "image/png")
                payload["receivedByName"] = "Raju, counter clerk"
                payload["signedNoteAttachmentId"] = signed
                payload["receiverPhotoAttachmentId"] = photo
                payload["reference"] = "Cash at the temple office"
            else:
                proof = upload(admin, "/api/v1/vendor-invoices/payment-uploads?kind=PAYMENT_PROOF",
                               "proof.png", photo_png(position + 1), "image/png")
                payload["proofAttachmentId"] = proof
                payload["reference"] = (f"UPI/{today.strftime('%y%m%d')}/{4100 + position}"
                                        if method == "UPI"
                                        else f"NEFT/HDFC/{88200 + position}")

            admin.post(f"/api/v1/vendor-invoices/{invoice['id']}/payments", payload)
            args.state.put(key, True)
            tally.made("payment",
                       f"{invoice.get('number')} Rs {amount:,.2f} by {method}"
                       + (" (part)" if part else " (in full)"))
        except ApiError as e:
            tally.problem(f"paying {invoice.get('number')}: {e}")
        except Exception as e:
            tally.problem(f"paying {invoice.get('number')}: {e}")

    # ---- one voided --------------------------------------------------------
    # One voided bill on the system, decided across the whole book rather than this round's slice.
    # `invoices[3]` meant "the fourth invoice of this run", and a run that billed three never
    # voided anything — four rounds on staging produced nine invoices and not one void.
    step("voiding one")
    already = [i for i in admin.get("/api/v1/vendor-invoices") if i["status"] == "VOIDED"]
    if already:
        tally.kept("void", f"{already[0]['invoiceNumber']} is already voided")
    else:
        # A bill nobody has paid yet: voiding one with payments against it is refused
        # (KMS-400154), and rightly.
        payable = [i for i in invoices
                   if admin.get(f"/api/v1/vendor-invoices/{i['id']}").get("status") == "PENDING"
                   and not admin.get(f"/api/v1/vendor-invoices/{i['id']}/payments")]
        invoice = payable[-1] if payable else None
        if not invoice:
            tally.skip("void", "no unpaid bill to void")
        else:
            try:
                admin.post(f"/api/v1/vendor-invoices/{invoice['id']}/void", {
                    "reason": "Raised against the wrong order. The vendor is re-issuing it.",
                })
                tally.made("void", f"{invoice.get('number')} — wrong order, vendor re-issuing")
            except ApiError as e:
                tally.problem(f"voiding {invoice.get('number')}: {e}")

    # ---- what is owed ------------------------------------------------------
    step("what the temple owes")
    all_invoices = admin.get("/api/v1/vendor-invoices")
    by_status: dict[str, int] = {}
    for invoice in all_invoices:
        by_status[invoice["status"]] = by_status.get(invoice["status"], 0) + 1
    for status, n in sorted(by_status.items()):
        info(f"  {status:12} {n}")

    payables = admin.get("/api/v1/payables")
    outstanding = sum(float(p["outstanding"]) for p in payables)
    info(f"{len(payables)} bill(s) outstanding, Rs {outstanding:,.2f} owed")
    for payable in payables[:6]:
        info(f"  {payable['invoiceNumber']:16} {payable['vendorName']:30} "
             f"Rs {float(payable['outstanding']):>12,.2f}  {payable.get('agingBucket')}")

    return tally.report()


if __name__ == "__main__":
    sys.exit(main())
