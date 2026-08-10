package org.iskcon.kms.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Initialises the Firebase Admin SDK.
 *
 * <p>Guarded by {@code kms.firebase.enabled} so the application starts without Firebase
 * credentials — necessary for tests and for local development against a database only. When
 * disabled, no {@link FirebaseAuth} bean exists, {@link FirebaseTokenVerifier} does not
 * activate, and a stub verifier can take its place.
 *
 * <p>Credentials come from Application Default Credentials rather than a checked-in service
 * account key: on Cloud Run the runtime service account supplies them automatically, so there
 * is no key file to leak, rotate, or accidentally commit.
 *
 * <p>The project id is set explicitly, and this matters: Firebase Auth lives in its own project
 * ({@code iskcon-kms-2026-620ee}), separate from the GCP project the runtime credentials belong to.
 * Token verification checks the token's audience against the SDK's project — so without pinning it
 * here, the SDK would take the credentials' project and reject every real token as the wrong
 * audience.
 */
@Configuration
@ConditionalOnProperty(name = "kms.firebase.enabled", havingValue = "true")
public class FirebaseConfiguration {

	private final String projectId;

	public FirebaseConfiguration(@Value("${kms.firebase.project-id}") String projectId) {
		this.projectId = projectId;
	}

	@Bean
	public FirebaseApp firebaseApp() throws IOException {
		if (!FirebaseApp.getApps().isEmpty()) {
			return FirebaseApp.getInstance();
		}

		FirebaseOptions options = FirebaseOptions.builder()
				.setCredentials(GoogleCredentials.getApplicationDefault())
				.setProjectId(projectId)
				.build();

		return FirebaseApp.initializeApp(options);
	}

	@Bean
	public FirebaseAuth firebaseAuth(FirebaseApp app) {
		return FirebaseAuth.getInstance(app);
	}
}
