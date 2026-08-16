package org.iskcon.kms.tenancy;

import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A temple's secrets in Google Secret Manager (E7).
 *
 * <p>The name is derived from the tenant id — {@code kms-{env}-tenant-{uuid}-{kind}} — so nothing
 * has to be read from the database before a temple can be erased, and any secret orphaned by a
 * failed deletion can be found again from the tenant id alone.
 *
 * <p>Writing a new value adds a <em>version</em> rather than replacing the secret, which is what
 * makes a temple rotating its key a non-event: the old version is retained and readable until it is
 * disabled, and {@code latest} moves forward.
 */
@Component
@ConditionalOnProperty(name = "kms.secrets.store", havingValue = "gcp")
public class GcpTenantSecretStore implements TenantSecretStore {

	private static final Logger log = LoggerFactory.getLogger(GcpTenantSecretStore.class);

	private final String projectId;
	private final String environment;

	public GcpTenantSecretStore(
			@Value("${kms.secrets.project-id:}") String projectId,
			@Value("${kms.environment:staging}") String environment) {
		this.projectId = projectId;
		this.environment = environment;
	}

	@Override
	public void put(UUID tenantId, Kind kind, String value) {
		String secretId = secretId(tenantId, kind);
		try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
			SecretName name = SecretName.of(projectId, secretId);
			try {
				client.getSecret(name);
			} catch (NotFoundException notThereYet) {
				// Automatic replication: this is a regional deployment and the secret follows the
				// project, not the service.
				client.createSecret(CreateSecretRequest.newBuilder()
						.setParent(ProjectName.of(projectId).toString())
						.setSecretId(secretId)
						.setSecret(Secret.newBuilder()
								.setReplication(Replication.newBuilder()
										.setAutomatic(Replication.Automatic.newBuilder().build())
										.build())
								.build())
						.build());
			}
			client.addSecretVersion(AddSecretVersionRequest.newBuilder()
					.setParent(name.toString())
					.setPayload(SecretPayload.newBuilder()
							.setData(ByteString.copyFrom(value, StandardCharsets.UTF_8))
							.build())
					.build());
		} catch (IOException e) {
			throw new IllegalStateException("Could not reach Secret Manager to store " + secretId, e);
		}
	}

	@Override
	public Optional<String> get(UUID tenantId, Kind kind) {
		String secretId = secretId(tenantId, kind);
		try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
			return Optional.of(client
					.accessSecretVersion(SecretVersionName.of(projectId, secretId, "latest"))
					.getPayload().getData().toStringUtf8());
		} catch (NotFoundException neverSet) {
			return Optional.empty();
		} catch (IOException e) {
			throw new IllegalStateException("Could not reach Secret Manager to read " + secretId, e);
		}
	}

	@Override
	public void deleteAll(UUID tenantId) {
		try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
			for (Kind kind : Kind.values()) {
				String secretId = secretId(tenantId, kind);
				try {
					client.deleteSecret(SecretName.of(projectId, secretId));
				} catch (NotFoundException alreadyGone) {
					// A temple that never configured payments has nothing to erase.
				} catch (RuntimeException e) {
					// The temple's rows are already gone, so this cannot be retried in place. Say so
					// loudly enough that the leftover can be swept up: the name is derivable from the
					// tenant id, so nothing is needed to find it again.
					log.error("Left an orphaned secret {} behind after deleting temple {}: {}",
							secretId, tenantId, e.toString());
				}
			}
		} catch (IOException e) {
			log.error("Could not reach Secret Manager to erase the secrets of temple {}: {}",
					tenantId, e.toString());
		}
	}

	/** {@code kms-staging-tenant-{uuid}-payment-key-secret} — derivable, never stored. */
	private String secretId(UUID tenantId, Kind kind) {
		return "kms-" + environment + "-tenant-" + tenantId + "-" + kind.suffix();
	}
}
