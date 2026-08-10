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
	@DisplayName("an actual request carries the allow-origin header and exposes the request id")
	void actualRequestCarriesCorsHeaders() throws Exception {
		mvc.perform(get("/health").header("Origin", FRONTEND))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
				.andExpect(header().string("Access-Control-Expose-Headers", "X-Request-Id"));
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
