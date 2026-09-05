package org.iskcon.kms.document;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import java.util.Map;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The real renderer: headless Chromium via Playwright (E2-S5, TECH_STACK.md). Chosen over a
 * pure-JVM PDF library because E2-S6 renders Devanagari and Kannada, and correct complex-script
 * shaping (conjuncts, matras) reliably needs a real browser engine.
 *
 * <p>Enabled with {@code kms.documents.renderer=playwright}. The image bundles Chromium and the
 * Noto fonts it needs. A single Playwright/Browser is reused across renders — launching Chromium
 * per call would be far too slow.
 *
 * <p><strong>The browser starts on the first render, not at boot.</strong> Launching it in the
 * constructor once took the whole application down: the environment was switched to this renderer
 * before the image carrying Chromium was deployed, and every instance crash-looped on startup with
 * no service at all. A missing browser should cost you your PDFs, not your temple's kitchen — so a
 * failure to launch is reported as a document failure, with the incident id, and the rest of the
 * product carries on.
 */
@Component
@ConditionalOnProperty(name = "kms.documents.renderer", havingValue = "playwright")
public class PlaywrightPdfRenderer implements PdfRenderer, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(PlaywrightPdfRenderer.class);

	private Playwright playwright;
	private Browser browser;

	@Override
	public synchronized byte[] renderPdf(String html, Footer footer) {
		try (Page page = browser().newPage()) {
			page.setContent(html);
			// Wait for fonts to load so Indic scripts render, not tofu.
			page.waitForLoadState();

			Page.PdfOptions options = new Page.PdfOptions()
					.setFormat("A4")
					.setPrintBackground(true);
			if (footer == null) {
				return page.pdf(options.setMargin(
						new Margin().setTop("14mm").setBottom("14mm").setLeft("14mm").setRight("14mm")));
			}
			// A footer needs room to sit in, and Chromium clips it against the page edge if the bottom
			// margin is not deeper than the footer itself. 18mm is the 14mm the rest of the document
			// uses plus the footer's own height.
			return page.pdf(options
					.setMargin(new Margin().setTop("14mm").setBottom("18mm").setLeft("14mm").setRight("14mm"))
					.setDisplayHeaderFooter(true)
					// An empty header is not the same as no header: leave it out and Chromium supplies
					// its own, which is the document title and the date in a font nobody chose.
					.setHeaderTemplate("<span></span>")
					.setFooterTemplate(footerTemplate(footer)));
		}
	}

	/**
	 * Chromium's footer, which is a separate little document with none of the page's own styling.
	 *
	 * <p>Three things about it are not obvious and all three have bitten somebody: it inherits no
	 * stylesheet, so every rule here is inline; its default font size is around 8px whatever the page
	 * uses, so the size must be stated or the footer prints too small to read; and
	 * {@code .pageNumber} and {@code .totalPages} are the only way to get a page number out of
	 * Chromium at all, since Blink implements neither {@code @page} margin boxes nor
	 * {@code counter(page)}.
	 *
	 * <p>The words match {@code JobCardTemplate.footerLeft} exactly, because the browser print view
	 * renders the same footer from the document itself and the two must not drift.
	 */
	private static String footerTemplate(Footer footer) {
		return "<div style=\"width:100%;margin:0 14mm;font-family:system-ui,sans-serif;"
				+ "font-size:8pt;color:#4A4A4A;border-top:1px solid #D9D9D9;padding-top:3mm;"
				+ "display:flex;justify-content:space-between;align-items:baseline\">"
				+ "<span>" + esc(footer.left()) + "</span>"
				+ "<span>Page <span class=\"pageNumber\"></span> of <span class=\"totalPages\"></span></span>"
				+ "<span style=\"font-size:9pt;font-weight:700;color:#141414\">"
				+ esc(footer.right()) + "</span>"
				+ "</div>";
	}

	private static String esc(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/** Launched once, on first use. Retried on the next request if the launch failed. */
	private Browser browser() {
		if (browser != null) {
			return browser;
		}
		try {
			playwright = Playwright.create();
			browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
			return browser;
		} catch (RuntimeException e) {
			close();
			log.error("Could not start the PDF browser. Is Chromium present in this image?", e);
			throw new ApplicationException(
					ErrorCode.DOCUMENT_GENERATION_FAILED, Map.of("reason", "renderer unavailable"), e);
		}
	}

	@Override
	public synchronized void close() {
		if (browser != null) {
			browser.close();
			browser = null;
		}
		if (playwright != null) {
			playwright.close();
			playwright = null;
		}
	}
}
