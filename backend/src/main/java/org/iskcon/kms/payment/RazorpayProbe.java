package org.iskcon.kms.payment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Proves a temple's Razorpay credentials by asking Razorpay for one order and reading nothing from
 * the answer but its status code.
 *
 * <p>A read rather than a write: pressing Test must never leave anything behind in the temple's
 * Razorpay account. {@code count=1} keeps the response small; a brand-new account with no orders at
 * all still answers 200 with an empty list, which is a pass — the question is whether the key is
 * accepted, not whether the temple has taken money yet.
 *
 * <p>Note what this deliberately does <em>not</em> implement: {@link WebhookRegistrar}. Razorpay's
 * webhook API is a partner API, addressed to a sub-merchant {@code account_id} and usable only by a
 * platform that onboarded that merchant. A temple holding its own merchant keys cannot call it, so
 * pretending we might would only produce a failure at the least helpful moment. Razorpay temples
 * follow the manual steps the Settings screen sets out. Registering webhooks for them is possible
 * only by becoming a Razorpay partner and onboarding temples as sub-merchants, which is a business
 * relationship rather than a change to this class.
 */
@Component
public class RazorpayProbe implements PaymentProviderProbe {

	private static final URI ORDERS = URI.create("https://api.razorpay.com/v1/orders?count=1");
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Override
	public String provider() {
		return "RAZORPAY";
	}

	@Override
	public void verify(String keyId, String keySecret) {
		String basic = Base64.getEncoder().encodeToString(
				(keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(ORDERS)
				.timeout(TIMEOUT)
				.header("Authorization", "Basic " + basic)
				.GET()
				.build();
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status == 401 || status == 403) {
				throw new PaymentCredentialsRejected(
						"Razorpay did not accept that key id and secret.");
			}
			if (status >= 400) {
				throw new PaymentCredentialsRejected(
						"Razorpay answered with an error (HTTP " + status + ").");
			}
		} catch (IOException e) {
			throw new PaymentCredentialsRejected("Could not reach Razorpay just now.", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PaymentCredentialsRejected("Could not reach Razorpay just now.", e);
		}
	}
}
