package org.iskcon.kms.document;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The real renderer: headless Chromium via Playwright (E2-S5, TECH_STACK.md). Chosen over a
 * pure-JVM PDF library because E2-S6 renders Devanagari and Kannada, and correct complex-script
 * shaping (conjuncts, matras) reliably needs a real browser engine.
 *
 * <p>Enabled with {@code kms.documents.renderer=playwright}, only where a browser is installed
 * (the worker image bundles Chromium + Noto fonts). A single Playwright/Browser is reused across
 * renders — launching Chromium per call would be far too slow.
 */
@Component
@ConditionalOnProperty(name = "kms.documents.renderer", havingValue = "playwright")
public class PlaywrightPdfRenderer implements PdfRenderer, AutoCloseable {

	private final Playwright playwright;
	private final Browser browser;

	public PlaywrightPdfRenderer() {
		this.playwright = Playwright.create();
		this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
	}

	@Override
	public synchronized byte[] renderPdf(String html) {
		try (Page page = browser.newPage()) {
			page.setContent(html);
			// Wait for fonts to load so Indic scripts render, not tofu.
			page.waitForLoadState();
			return page.pdf(new Page.PdfOptions()
					.setFormat("A4")
					.setPrintBackground(true)
					.setMargin(new Margin().setTop("14mm").setBottom("14mm").setLeft("14mm").setRight("14mm")));
		}
	}

	@Override
	public void close() {
		browser.close();
		playwright.close();
	}
}
