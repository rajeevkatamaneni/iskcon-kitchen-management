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
	public synchronized byte[] renderPdf(String html) {
		try (Page page = browser().newPage()) {
			page.setContent(html);
			// Wait for fonts to load so Indic scripts render, not tofu.
			page.waitForLoadState();
			return page.pdf(new Page.PdfOptions()
					.setFormat("A4")
					.setPrintBackground(true)
					.setMargin(new Margin().setTop("14mm").setBottom("14mm").setLeft("14mm").setRight("14mm")));
		}
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
