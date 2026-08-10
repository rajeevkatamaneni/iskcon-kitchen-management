package org.iskcon.kms.document;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default renderer: a placeholder that lets the whole document pipeline — request, job, store,
 * status, download — run and be tested without a browser. It is NOT a rendered recipe card; it is a
 * tiny valid-enough PDF stand-in. Real rendering is {@link PlaywrightPdfRenderer}, selected with
 * {@code kms.documents.renderer=playwright}. Keeping this the default is what keeps the test suite
 * hermetic (CI has no Chromium).
 */
@Component
@ConditionalOnProperty(name = "kms.documents.renderer", havingValue = "stub", matchIfMissing = true)
public class StubPdfRenderer implements PdfRenderer {

	@Override
	public byte[] renderPdf(String html) {
		String placeholder = "%PDF-1.4\n"
				+ "% KMS stub renderer — not a real render (set kms.documents.renderer=playwright).\n"
				+ "% source HTML length: " + (html == null ? 0 : html.length()) + "\n"
				+ "%%EOF\n";
		return placeholder.getBytes(StandardCharsets.UTF_8);
	}
}
