package org.iskcon.kms.notification;

import java.time.Instant;
import java.util.List;

/**
 * A temple's WhatsApp connection as its administrator sees it (E1, E5).
 *
 * <p>Everything here is safe to show: two Meta ids that address a send, the callback URL Meta must
 * be told about, and the dates that say whether each half works. The access token, the app secret
 * and the verify token are not here and no endpoint returns the first two.
 *
 * @param connected     whether this temple can send a WhatsApp message at all
 * @param phoneNumberId Meta's id for the temple's business number
 * @param wabaId        the WhatsApp Business Account that owns the approved templates
 * @param displayNumber the number as Meta describes it, so an administrator can see they connected
 *                      the one they meant to. Null until the credentials have been checked once.
 * @param webhookUrl    the address Meta must be told to call
 * @param verifiedAt    when the credentials last reached Meta
 * @param webhookSeenAt when a correctly signed callback last arrived — the only proof the return
 *                      path works, and a different question from {@code verifiedAt}
 * @param templatesSubmittedAt when Meta last accepted, or already held, at least one of the message
 *                      templates. Null if no save has registered any.
 * @param refusedTemplates the templates the last save left needing an administrator's attention,
 *                      each with a sentence they can read (T-159) and what kind of problem it is
 *                      (T-168). Empty, never null, when there are none. The name is T-159's and is
 *                      kept because it is the column's and the screen's; since T-168 not every entry
 *                      is a refusal — see {@link RefusedTemplate#kind()}.
 * @param templatesPending what the Reload WhatsApp Templates button is waiting to send (T-169a). Never
 *                      null; all zero and false when nothing is waiting.
 */
public record TenantWhatsAppSettings(
		boolean connected,
		String phoneNumberId,
		String wabaId,
		String displayNumber,
		String webhookUrl,
		Instant verifiedAt,
		Instant webhookSeenAt,
		Instant templatesSubmittedAt,
		List<RefusedTemplate> refusedTemplates,
		TemplatesPending templatesPending) {

	public TenantWhatsAppSettings {
		refusedTemplates = refusedTemplates == null ? List.of() : List.copyOf(refusedTemplates);
		templatesPending = templatesPending == null ? TemplatesPending.NOTHING : templatesPending;
	}

	/** A temple that has not connected WhatsApp. */
	public static TenantWhatsAppSettings none() {
		return new TenantWhatsAppSettings(false, null, null, null, null, null, null, null, List.of(),
				TemplatesPending.NOTHING);
	}

	/**
	 * Why the Reload WhatsApp Templates button is the primary button (T-169a).
	 *
	 * <p>Rajeev, 2026-09-13, choosing the button that is always there: with nothing waiting it reads
	 * "Templates last sent to Meta on &lt;date&gt;"; with something waiting it says what, e.g. "3
	 * templates changed since they were last sent". These three are what it can say.
	 *
	 * <p><strong>Each number only counts what is known.</strong> A temple that sent its templates
	 * before fingerprints were kept (V129) has nothing to compare with, and reads zero changed until
	 * its next Reload records what Meta holds. A screen that said "20 changed" the morning after a
	 * deploy would be claiming something nobody checked. The rule is in
	 * {@code TenantWhatsAppSettingsService#pending}.
	 *
	 * @param changed        templates in this release whose wording differs from what Meta was last
	 *                       found holding, or that are new since the last send. Never counts one
	 *                       already counted in {@code refused}.
	 * @param refused        stored entries of kind {@link Kind#REFUSED} or {@link Kind#NOT_REACHED}.
	 *                       {@link Kind#HELD_UNDER_ANOTHER_CATEGORY} is not counted: Meta holds those,
	 *                       and a Reload cannot move a category, so counting them would keep the
	 *                       button asking for a press that can never help.
	 * @param accountChanged the WhatsApp Business Account id or phone number id differs from the one
	 *                       templates were last sent to
	 */
	public record TemplatesPending(int changed, int refused, boolean accountChanged) {

		public static final TemplatesPending NOTHING = new TemplatesPending(0, 0, false);
	}

	/**
	 * One template the last save left needing attention.
	 *
	 * @param name   Meta's name for it, e.g. {@code shift_reminder}
	 * @param reason a plain sentence saying why and what to do — never Meta's own developer text,
	 *               which goes to the log
	 * @param kind   what happened to it. Never null: an entry stored before T-168 had no kind, and
	 *               every entry then was written as a refusal, so it reads back as {@link Kind#REFUSED}
	 */
	public record RefusedTemplate(String name, String reason, Kind kind) {

		public RefusedTemplate {
			kind = kind == null ? Kind.REFUSED : kind;
		}
	}

	/**
	 * Why a template is on the list (T-168). Three kinds, because each asks something different of an
	 * administrator, and a screen that showed them alike would repeat staging's false alarm.
	 */
	public enum Kind {
		/** Meta did not accept it. It will not go by WhatsApp. */
		REFUSED,
		/** Meta could not be asked. A later save may register it. */
		NOT_REACHED,
		/**
		 * Meta holds it, under a category Meta chose, and will not take ours. It can be sent, but is
		 * priced and delivered as that category — marketing is not delivered to United States numbers.
		 */
		HELD_UNDER_ANOTHER_CATEGORY
	}
}
