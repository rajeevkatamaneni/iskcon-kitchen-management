package org.iskcon.kms.notification;

import java.util.UUID;

/**
 * Published when a notification reaches a terminal delivery status (E1-S10), so features that
 * originated a message can react without the notification service knowing anything about them —
 * a PO send reflecting delivery onto its trail and flagging an unreachable vendor (E5-S7), for
 * instance. Fired within the acting tenant's context, on the thread that applied the status.
 */
public record NotificationStatusChanged(UUID notificationId, DeliveryStatus status) {
}
