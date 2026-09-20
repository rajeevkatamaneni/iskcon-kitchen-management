"""
Makes the paperwork the temple would have photographed: vendor bills, UPI screenshots, signed
cash notes.

Rajeev asked for real attachments — "when creating invoices attach sample receipts that you
create" — and the application will not take a placeholder: `AttachmentService` **sniffs the
content type from the first bytes** and refuses anything that is not really a JPEG, PNG, WebP,
HEIC or PDF. So these are genuine files rather than text with a misleading name.

Written by hand, with no library, for the same reason the rest of tools/seed/ uses only the
standard library: a seeding run should not depend on a pip install working on the day.
"""

from __future__ import annotations

import zlib


# Helvetica in a hand-built PDF is a Latin-1 font, so anything outside it has to be turned into
# something rather than encoded. Left to `errors="replace"` an em-dash becomes a literal "?" on
# the bill, which looks like a fault in the data rather than in the typesetting — checked by
# rendering one. The rupee sign has no Latin-1 code point either; bills say "Rs".
_TRANSLITERATE = str.maketrans({
    "—": "-", "–": "-", "‘": "'", "’": "'",
    "“": '"', "”": '"', "…": "...", "₹": "Rs", " ": " ",
})


def _escape(text: str) -> str:
    text = text.translate(_TRANSLITERATE)
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def bill_pdf(title: str, lines: list[str]) -> bytes:
    """
    A one-page A4 PDF that reads like a vendor's bill.

    Built by hand: five objects, an xref table pointing at each one's byte offset, and a trailer.
    The offsets have to be exact — a PDF reader finds every object through that table — so they
    are measured as the file is assembled rather than guessed.
    """
    text_ops = ["BT", "/F1 16 Tf", "72 780 Td", f"({_escape(title)}) Tj", "/F1 11 Tf"]
    for line in lines:
        text_ops += ["0 -20 Td", f"({_escape(line)}) Tj"]
    text_ops.append("ET")
    stream = "\n".join(text_ops).encode("latin-1", "replace")

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
        b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode() + body + b"\nendobj\n"

    xref_at = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode()
    out += b"0000000000 65535 f \n"
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode()
    out += (f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_at}\n%%EOF\n").encode()
    return bytes(out)


def _png(width: int, height: int, pixels: bytes) -> bytes:
    """A PNG from raw RGB rows. Used for the photographs a payment needs."""
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (len(data).to_bytes(4, "big") + tag + data
                + zlib.crc32(tag + data).to_bytes(4, "big"))

    header = (width.to_bytes(4, "big") + height.to_bytes(4, "big")
              + bytes([8, 2, 0, 0, 0]))          # 8-bit, truecolour
    raw = bytearray()
    row_bytes = width * 3
    for y in range(height):
        raw.append(0)                             # filter: none
        raw += pixels[y * row_bytes:(y + 1) * row_bytes]
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


def photo_png(seed: int = 0, width: int = 240, height: int = 160) -> bytes:
    """
    A small image standing in for a photograph — the signed note, the person who took the cash.

    Deliberately not a solid colour: a plausible photograph has variation in it, and a reviewer
    opening the attachment should see something that looks like a picture rather than a test
    pattern. `seed` makes each one different so two payments do not share a byte-identical file.
    """
    pixels = bytearray()
    for y in range(height):
        for x in range(width):
            pixels += bytes((
                (x * 7 + seed * 13) % 256,
                (y * 5 + seed * 29) % 256,
                ((x + y) * 3 + seed * 47) % 256,
            ))
    return _png(width, height, bytes(pixels))
