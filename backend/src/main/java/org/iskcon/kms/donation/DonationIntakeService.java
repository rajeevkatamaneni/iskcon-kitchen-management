package org.iskcon.kms.donation;

import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-kind donation intake (E3-S5), orchestrating two steps that must not be one transaction.
 *
 * <p>First {@link DonationRecorder} records the donation and its goods atomically. Then, only once
 * that has committed, a thank-you goes out through the notification service — best-effort. The split
 * is deliberate: the physical goods are already on the shelf, so a thank-you we cannot queue (a
 * transient scheduler hiccup, say) must be logged and shrugged off, never allowed to roll back a
 * donation that really happened. An anonymous gift, or one with no contact details, is simply not
 * acknowledged.
 */
@Service
public class DonationIntakeService {

	private static final Logger log = LoggerFactory.getLogger(DonationIntakeService.class);

	private final DonationRecorder recorder;
	private final NotificationService notificationService;

	public DonationIntakeService(DonationRecorder recorder, NotificationService notificationService) {
		this.recorder = recorder;
		this.notificationService = notificationService;
	}

	public UUID recordInKind(AuthenticatedUser actor, RecordInKindDonationRequest request) {
		DonationReceipt receipt = recorder.record(actor, request);
		sendThankYou(receipt);
		return receipt.donationId();
	}

	private void sendThankYou(DonationReceipt receipt) {
		if (receipt.anonymous() || (receipt.donorPhone() == null && receipt.donorEmail() == null)) {
			return;
		}
		try {
			notificationService.notify(
					NotificationRecipient.contact(receipt.donorPhone(), receipt.donorEmail()),
					NotificationTemplate.DONATION_THANK_YOU,
					Map.of(
							"donor", receipt.donorName(),
							"temple", receipt.templeName(),
							"date", receipt.donatedOn().toString()),
					null);
			recorder.markAcknowledged(receipt.donationId());
		} catch (RuntimeException e) {
			// The goods are received and recorded. A thank-you we couldn't queue must not undo that.
			log.warn("Could not queue thank-you for donation {}: {}", receipt.donationId(), e.toString());
		}
	}
}
