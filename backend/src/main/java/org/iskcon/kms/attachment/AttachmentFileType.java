package org.iskcon.kms.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Decides what an uploaded file is from its first bytes, and nothing else.
 *
 * <p><strong>Why not the declared type or the file's name.</strong> Both are written by the sender.
 * A browser takes the Content-Type of a part from the file's extension, so "invoice.pdf" that is
 * really an executable arrives as {@code application/pdf}, and a phone's share sheet sometimes sends
 * {@code application/octet-stream} for a perfectly good photo. The bytes are the only witness the
 * sender cannot rename. What is stored as {@code content_type}, and later sent back as the
 * Content-Type of the download, is what this class found — so a file is always served as what it
 * is, which is what keeps a browser from being talked into rendering something it should not.
 *
 * <p><strong>Why these five.</strong> They are what a phone camera, a phone's screenshot, a scanner
 * and a vendor's WhatsApp PDF produce, and a browser can show all of them back except HEIC, which
 * iPhones produce by default and which the person can still download. KMS-400165 names them.
 *
 * <p>Each signature is the format's own, from its specification:
 * <ul>
 *   <li>JPEG: {@code FF D8 FF}, the start-of-image marker and the first marker after it.</li>
 *   <li>PNG: the eight-byte signature {@code 89 'PNG' 0D 0A 1A 0A}.</li>
 *   <li>WebP: a RIFF container, {@code 'RIFF' <size> 'WEBP'}.</li>
 *   <li>HEIC / HEIF: an ISO base-media file whose first box is {@code ftyp}, with a HEIF major
 *       brand. AVIF shares the container and is refused, because its major brand is {@code avif}.</li>
 *   <li>PDF: {@code %PDF-} at the very start. The PDF reference tolerates junk before it; nothing a
 *       phone or a scanner makes puts any there, and accepting a header anywhere in the first
 *       kilobyte would let a file that is something else first through.</li>
 * </ul>
 *
 * <p><strong>Public since T-428</strong>, and only that. A staff record now carries a photograph and
 * the scans of a PAN and an Aadhaar card, in a table of their own (V155) — the storage service is
 * shared, the table is not, which is V144's own rule. What must not be duplicated along with the
 * table is <em>this</em>: a second place deciding what a file is would be a second place that can get
 * it wrong, and the whole point of reading the bytes is that there is one answer. So the staff
 * service calls this class rather than keeping a copy of the signatures.
 */
public final class AttachmentFileType {

	private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
	private static final byte[] RIFF = ascii("RIFF");
	private static final byte[] WEBP = ascii("WEBP");
	private static final byte[] FTYP = ascii("ftyp");
	private static final byte[] PDF = ascii("%PDF-");

	/** HEVC-coded HEIF: what an iPhone writes, still images and bursts alike. */
	private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs");
	/** HEIF with the codec left to the image items: some Android cameras write these. */
	private static final Set<String> HEIF_BRANDS = Set.of("mif1", "msf1");

	private AttachmentFileType() {
	}

	/** The content type these bytes are, or empty when they are none of the five. */
	public static Optional<String> of(byte[] bytes) {
		if (startsWith(bytes, 0, JPEG)) {
			return Optional.of("image/jpeg");
		}
		if (startsWith(bytes, 0, PNG)) {
			return Optional.of("image/png");
		}
		if (startsWith(bytes, 0, RIFF) && startsWith(bytes, 8, WEBP)) {
			return Optional.of("image/webp");
		}
		if (startsWith(bytes, 4, FTYP) && bytes.length >= 12) {
			String brand = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
			if (HEIC_BRANDS.contains(brand)) {
				return Optional.of("image/heic");
			}
			if (HEIF_BRANDS.contains(brand)) {
				return Optional.of("image/heif");
			}
			return Optional.empty();
		}
		if (startsWith(bytes, 0, PDF)) {
			return Optional.of("application/pdf");
		}
		return Optional.empty();
	}

	/** The extension a download is named with when the device sent no name of its own. */
	public static String extensionFor(String contentType) {
		return switch (contentType) {
			case "image/jpeg" -> ".jpg";
			case "image/png" -> ".png";
			case "image/webp" -> ".webp";
			case "image/heic" -> ".heic";
			case "image/heif" -> ".heif";
			case "application/pdf" -> ".pdf";
			default -> "";
		};
	}

	private static boolean startsWith(byte[] bytes, int offset, byte[] signature) {
		return bytes.length >= offset + signature.length
				&& Arrays.equals(bytes, offset, offset + signature.length, signature, 0, signature.length);
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}
}
