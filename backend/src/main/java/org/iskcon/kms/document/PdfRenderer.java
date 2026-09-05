package org.iskcon.kms.document;

/**
 * Renders an HTML page to PDF bytes (E2-S5). The one operation the rest of the app depends on, so
 * the heavy, deploy-only Chromium implementation sits behind this port and a stub can stand in for
 * the hermetic test suite.
 */
public interface PdfRenderer {

	/**
	 * A running footer repeated on every page, with a page number the document itself cannot produce.
	 *
	 * <p>This exists because of one gap in Blink: it supports neither {@code @page} margin boxes nor
	 * {@code counter(page)}, so no stylesheet a template can write will ever say "Page 3 of 7". The
	 * renderer's own footer is the only place a page number can come from, which is why a document
	 * that wants one has to hand its footer text out here rather than draw it itself.
	 *
	 * @param left  what sits on the outside left — for a job card, its version and print time.
	 * @param right what sits on the outside right — for a job card, its number.
	 */
	record Footer(String left, String right) {
	}

	/** Renders a full, self-contained HTML document (A4) to PDF bytes. */
	default byte[] renderPdf(String html) {
		return renderPdf(html, null);
	}

	/**
	 * Renders a document that carries a running footer on every page.
	 *
	 * <p>A null footer is the ordinary case and renders exactly as {@link #renderPdf(String)} does:
	 * most documents in this application are one or two pages that nobody separates.
	 */
	byte[] renderPdf(String html, Footer footer);
}
