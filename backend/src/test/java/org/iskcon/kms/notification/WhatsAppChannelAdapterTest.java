package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When "this temple's WhatsApp actually works" gets written down, and when it does not (T-136).
 *
 * <p>Rajeev's ruling of 2026-09-10 is that the Send on WhatsApp button appears "only after a message
 * has actually gone through it successfully", not merely configured. The whole of that ruling rests
 * on one line in {@link WhatsAppChannelAdapter}, so what this fixes in place is exactly where that
 * line sits: after Meta accepted the message, never before, and never on any other path.
 *
 * <p>A plain unit test with Mockito and no Spring context, deliberately. The question is which of
 * four paths through one method reaches one call, which needs no database and no HTTP — and a
 * {@code @MockBean} here would have cost the suite a whole extra application context for something
 * two mocks answer (see the note on context caching in {@code AbstractIntegrationTest}). The
 * database side of the same feature — the column, its RLS, and what NULL means — is
 * {@link WhatsAppLastSentIT}, which does need a real PostgreSQL.
 */
class WhatsAppChannelAdapterTest {

	private TenantWhatsAppSettingsService settings;
	private MetaWhatsAppClient meta;
	private WhatsAppChannelAdapter adapter;
	private OutboundMessage message;

	@BeforeEach
	void setUp() {
		settings = mock(TenantWhatsAppSettingsService.class);
		meta = mock(MetaWhatsAppClient.class);
		adapter = new WhatsAppChannelAdapter(settings, meta, "en");
		message = new OutboundMessage(
				NotificationTemplate.PO_DELIVERY,
				Map.of("poNumber", "PO-2026-0042", "vendor", "Govind Wholesale",
						"summary", "1 item(s): Rice", "raised", "1 Aug 2026", "neededBy", "20 Aug 2026"),
				NotificationTemplate.PO_DELIVERY.render(
						Map.of("poNumber", "PO-2026-0042", "vendor", "Govind Wholesale",
								"summary", "1 item(s): Rice", "raised", "1 Aug 2026",
								"neededBy", "20 Aug 2026")));
	}

	private void givenAConnectedTemple() {
		when(settings.sendingIdentity()).thenReturn(
				Optional.of(new TenantWhatsAppSettingsService.SendingIdentity("phone-1", "token-1")));
	}

	@Test
	@DisplayName("a message Meta accepted is what stamps the temple as able to send")
	void stampsOnlyAfterMetaAcceptedTheMessage() {
		givenAConnectedTemple();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenReturn("wamid.HBgM");

		SendResult result = adapter.send("+919812345678", message);

		assertThat(result.sent()).isTrue();
		assertThat(result.providerMessageId()).isEqualTo("wamid.HBgM");
		verify(settings).markMessageSent();
	}

	/**
	 * The case the whole ruling is about.
	 *
	 * <p>Every one of these failures happens with credentials that are perfectly valid — an
	 * unapproved template, a recipient outside Meta's test list, a spent messaging tier. They are
	 * precisely the temples where {@code whatsapp_verified_at} looks immaculate and no message has
	 * ever arrived anywhere, which is why gating the button on that date would have defeated the
	 * ruling exactly as written.
	 */
	@Test
	@DisplayName("Meta refusing the message stamps nothing, and falls through to the next channel")
	void refusalStampsNothing() {
		givenAConnectedTemple();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenThrow(new RuntimeException("(#132001) Template name does not exist"));

		SendResult result = adapter.send("+919812345678", message);

		assertThat(result.sent()).isFalse();
		assertThat(result.detail()).contains("Template name does not exist");
		verify(settings, never()).markMessageSent();
	}

	@Test
	@DisplayName("a temple that has connected nothing stamps nothing and never calls Meta")
	void unconnectedTempleStampsNothing() {
		when(settings.sendingIdentity()).thenReturn(Optional.empty());

		SendResult result = adapter.send("+919812345678", message);

		assertThat(result.sent()).isFalse();
		verify(settings, never()).markMessageSent();
		verify(meta, never()).sendTemplate(any(), any(), any(), any(), any(), any());
	}

	/**
	 * And the stamp is outside the try, which is not tidying.
	 *
	 * <p>If writing the column could be caught as a send failure, the adapter would report FAILED
	 * for a message Meta has already accepted — and the cascade would then send the same purchase
	 * order to the same vendor again by SMS. The vendor gets it twice because a column could not be
	 * written. So the failure has to escape: the dispatcher's transaction rolls back, nothing claims
	 * to have been sent, and the job retries the whole thing rather than half of it.
	 */
	@Test
	@DisplayName("a stamp that fails is not swallowed as a send failure")
	void aFailedStampIsNotReportedAsAFailedSend() {
		givenAConnectedTemple();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenReturn("wamid.HBgM");
		org.mockito.Mockito.doThrow(new IllegalStateException("connection closed"))
				.when(settings).markMessageSent();

		assertThatThrownBy(() -> adapter.send("+919812345678", message))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("connection closed");
	}
}
