package org.iskcon.kms.translation;

import com.google.cloud.ServiceOptions;
import com.google.cloud.translate.v3.LocationName;
import com.google.cloud.translate.v3.TranslateTextRequest;
import com.google.cloud.translate.v3.TranslateTextResponse;
import com.google.cloud.translate.v3.Translation;
import com.google.cloud.translate.v3.TranslationServiceClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real machine translation via Google Cloud Translation v3 (E2-S6). Enabled with
 * {@code kms.translation.provider=google}. Authenticates via ADC — the runtime service account
 * (granted roles/cloudtranslate.user) when deployed, the developer's ADC locally.
 */
@Component
@ConditionalOnProperty(name = "kms.translation.provider", havingValue = "google")
public class GoogleCloudTranslationProvider implements TranslationProvider, AutoCloseable {

	private final TranslationServiceClient client;
	private final String parent;

	public GoogleCloudTranslationProvider(
			@Value("${kms.gcp.project-id:}") String projectId,
			@Value("${kms.translation.location:global}") String location) {
		// The project is optional in configuration so a developer's ADC project can stand in, but it
		// has to be resolved to something: an empty one builds the parent "projects//locations/global",
		// which Google rejects per request as INVALID_ARGUMENT. Refuse to start instead — a
		// misconfigured deployment should not look healthy until someone clicks Translate.
		String project = (projectId == null || projectId.isBlank())
				? ServiceOptions.getDefaultProjectId()
				: projectId;
		if (project == null || project.isBlank()) {
			throw new IllegalStateException(
					"kms.translation.provider=google needs a GCP project: set kms.gcp.project-id "
							+ "(GCP_PROJECT_ID), or give the credentials a default project");
		}
		try {
			this.client = TranslationServiceClient.create();
		} catch (IOException e) {
			throw new IllegalStateException("Could not create the Translation client", e);
		}
		this.parent = LocationName.of(project, location).toString();
	}

	@Override
	public List<String> translate(List<String> texts, String sourceLanguage, String targetLanguage) {
		if (texts.isEmpty()) {
			return List.of();
		}
		try {
			TranslateTextRequest request = TranslateTextRequest.newBuilder()
					.setParent(parent)
					.setMimeType("text/plain")
					.setSourceLanguageCode(sourceLanguage)
					.setTargetLanguageCode(targetLanguage)
					.addAllContents(texts)
					.build();
			TranslateTextResponse response = client.translateText(request);
			List<String> out = new ArrayList<>(texts.size());
			for (Translation t : response.getTranslationsList()) {
				out.add(t.getTranslatedText());
			}
			return out;
		} catch (RuntimeException e) {
			throw new ApplicationException(ErrorCode.TRANSLATION_FAILED, java.util.Map.of(), e);
		}
	}

	@Override
	public String name() {
		return "google";
	}

	@Override
	public void close() {
		client.close();
	}
}
