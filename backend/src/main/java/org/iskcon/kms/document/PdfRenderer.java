package org.iskcon.kms.document;

/**
 * Renders an HTML page to PDF bytes (E2-S5). The one operation the rest of the app depends on, so
 * the heavy, deploy-only Chromium implementation sits behind this port and a stub can stand in for
 * the hermetic test suite.
 */
public interface PdfRenderer {

	/** Renders a full, self-contained HTML document (A4) to PDF bytes. */
	byte[] renderPdf(String html);
}
