package org.iskcon.kms.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The API tells the browser it accepts the frontend's origin — without which every call from the
 * (separately-served) frontend would be blocked before it left the browser.
 */
@AutoConfigureMockMvc
class CorsIT extends AbstractIntegrationTest {

	private static final String FRONTEND = "http://localhost:3000";

	@Autowired
	private MockMvc mvc;

	@Test
	@DisplayName("a preflight from the allowed frontend origin is accepted")
	void preflightFromAllowedOriginIsAccepted() throws Exception {
		mvc.perform(options("/api/v1/whoami")
						.header("Origin", FRONTEND)
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
	}

	@Test
	@DisplayName("every header the app actually sends is allowed through the preflight")
	void preflightAllowsTheHeadersWeSend() throws Exception {
		// The one that was missing cost a whole registration flow: the browser refused every request
		// the moment a person belonged to a temple, while curl — which has no preflight — showed a
		// perfectly healthy API. A header the app sends and CORS does not name is an outage.
		mvc.perform(options("/api/v1/temples")
						.header("Origin", FRONTEND)
						.header("Access-Control-Request-Method", "GET")
						.header("Access-Control-Request-Headers", "authorization,content-type,x-kms-temple"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
				// Compared case-insensitively: a browser lower-cases what it echoes back, and the
				// header's identity is its name, not its capitalisation.
				.andExpect(header().string("Access-Control-Allow-Headers",
						org.hamcrest.Matchers.containsStringIgnoringCase(AuthenticationFilter.TEMPLE_HEADER)));
	}

	@Test
	@DisplayName("an actual request carries the allow-origin header and exposes what the page must read")
	void actualRequestCarriesCorsHeaders() throws Exception {
		mvc.perform(get("/health").header("Origin", FRONTEND))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
				// X-Request-Id so a person can quote it; Content-Disposition because a browser hides
				// every other response header from a cross-origin page, and without it the temple data
				// export downloaded under an invented name (UAT003-1).
				.andExpect(header().string(
						"Access-Control-Expose-Headers", "X-Request-Id, Content-Disposition"));
	}

	@Test
	@DisplayName("a preflight from an unknown origin is not granted")
	void preflightFromUnknownOriginIsRejected() throws Exception {
		mvc.perform(options("/api/v1/whoami")
						.header("Origin", "https://evil.example")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isForbidden());
	}
}
