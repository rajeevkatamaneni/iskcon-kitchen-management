package org.iskcon.kms.notification;

import java.util.LinkedHashMap;
import org.iskcon.kms.communication.CommunicationCategory;
import java.util.List;
import java.util.Map;

/**
 * The messages the system is allowed to send. Kept as a small enum rather than free text because a
 * WhatsApp utility message must correspond to a template Meta has approved — the name here maps to
 * that approved template, and the body is what the SMS and email channels render locally.
 *
 * <p>Epic 1 ships the two the story names; later epics add their own the same way.
 */
public enum NotificationTemplate {

	SHIFT_REMINDER("shift_reminder") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Shift reminder",
					"Reminder: your %s shift at %s is on %s at %s.".formatted(
							value(params, "role"), value(params, "temple"),
							value(params, "date"), value(params, "time")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("role", "temple", "date", "time");
		}
	},

	/**
	 * A purchase order reaching its vendor, with all three of its dates.
	 *
	 * <p>Rajeev, 2026-09-05: <em>"There cannot be any confusion IF an order was sent late or if the
	 * merchant send the items late or whar ever question sarise from this process."</em> The gap
	 * between raised and sent is whether the temple was late; the gap between sent and needed-by is
	 * what the vendor was given to work with. A message carrying only one of them cannot settle
	 * either argument, and this is the copy the vendor keeps on their phone.
	 *
	 * <p>The send date is not a parameter: this message <em>is</em> the send, and WhatsApp stamps it
	 * with a timestamp the vendor can see. Putting a second one in the body would be the app's
	 * opinion of a moment the platform already records.
	 *
	 * <p><strong>Changing this changes the Meta template.</strong> The body is registered with Meta
	 * under {@code po_delivery} and approved before it can be sent; five parameters where there were
	 * three means re-registering it, and until that is approved every send fails as an unapproved
	 * template and cascades to SMS. Done now because the product is pre-beta and no temple depends on
	 * it; after that it would need a second template and a migration between them.
	 */
	PO_DELIVERY("po_delivery") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Purchase order " + value(params, "poNumber"),
					"Purchase order %s for %s is ready: %s. Raised %s, needed by %s.".formatted(
							value(params, "poNumber"), value(params, "vendor"),
							value(params, "summary"), value(params, "raised"),
							value(params, "neededBy")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("poNumber", "vendor", "summary", "raised", "neededBy");
		}
	},

	/**
	 * A gift that could only partly go where the donor sent it, because it finished the item on the
	 * way in (T-081).
	 *
	 * <p>Two devotees give towards the same grinder; the second's card is captured before the item is
	 * re-checked, because that is the only safe order. If the first gift lands in between, the second
	 * no longer fits — so exactly what the grinder still needed is applied, which finishes it, and the
	 * remainder goes to general funds. This message is the honest account of that.
	 *
	 * <p><strong>Rajeev wrote the brief for it himself, 2026-09-10, and it is quoted rather than
	 * summarised because a previous session kept only a paraphrase of its tone and lost the copy:</strong>
	 * <em>"put the 4K towards the grinder and the 10K to general fund. Then, in the thank you note to
	 * the user, be honest about it. Tell them, we were only able to apply 4K of their 14K donation
	 * towards the grinder purchase and they helped to get this to the finish line and make it a
	 * reality. The rest of the 10K is added to the general fund which needs more funds than it gets so
	 * their donation is a HUGE help there and will help us feed every person that walks into our
	 * temple. Obviously word it nicely and make it read warmly and with a very thankful tone."</em>
	 *
	 * <p>Four things the body must do, and each sentence below is doing one of them:
	 * <ol>
	 *   <li><strong>Name both actual amounts.</strong> "Part of your gift" would be the comfortable
	 *       thing to write and it is the one thing forbidden — honesty about the split is the point,
	 *       and a donor comparing this against their bank statement must find the same figures.</li>
	 *   <li><strong>Credit them with finishing it.</strong> This message can always say so, which is
	 *       why it is a separate template from {@link #WISHLIST_SPONSORSHIP_CONVERTED}: the applied
	 *       amount is exactly what was owed, so the item is bought, every time.</li>
	 *   <li><strong>Treat the remainder as a real good.</strong> The general fund is chronically short
	 *       and it is what feeds everyone who walks in. "That is not second best" says it outright
	 *       rather than hoping the reader infers it, because the sentence before it is about
	 *       something the donor did not entirely get.</li>
	 *   <li><strong>Stay warm.</strong> No "we regret", no "your transaction", no apology anywhere.</li>
	 * </ol>
	 *
	 * <p>Two mechanical constraints shaped the wording as much as the tone. The parameters appear in
	 * the body in the order {@link #parameterOrder()} lists them, because Meta numbers them
	 * positionally; and none of them appears twice, which is why the second sentence says "It is
	 * fully funded now" rather than naming the item again.
	 */
	WISHLIST_GIFT_SPLIT("wishlist_gift_split") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Thank you — your gift completed the " + value(params, "item"),
					("Dear %s, your gift of %s reached us just as the %s was almost paid for — only %s of "
							+ "it was still needed there, and that was the amount that finished it. It is "
							+ "fully funded now, and you are the one who got it over the line. The remaining "
							+ "%s has gone to our general fund, and that is not second best: the general fund "
							+ "is the one that is always short, and it is what puts rice and dal in front of "
							+ "every person who walks into %s and sits down to eat. Thank you for both. "
							+ "Hare Krishna.")
							.formatted(value(params, "donor"), value(params, "amount"), value(params, "item"),
									value(params, "applied"), value(params, "remainder"),
									value(params, "temple")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("donor", "amount", "item", "applied", "remainder", "temple");
		}
	},

	/**
	 * A gift that could do nothing for the item it was sent to, because the item was already paid for
	 * by the time the payment completed.
	 *
	 * <p>Unchanged by T-081, and deliberately. It is a different fact from a split — nothing was
	 * applied, so nothing can be credited — and one message hedging across both would leave every
	 * donor unsure whether their money did anything. Where a gift <em>could</em> be partly applied,
	 * {@link #WISHLIST_GIFT_SPLIT} is sent instead.
	 */
	WISHLIST_SPONSORSHIP_CONVERTED("wishlist_sponsorship_converted") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Thank you — your gift to " + value(params, "temple"),
					"That wish-list item was just fully sponsored by the time your payment completed, so your generous gift to %s has been received as a general donation instead. Thank you for your seva. Hare Krishna."
							.formatted(value(params, "temple")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("temple");
		}
	},

	DONATION_THANK_YOU("donation_thank_you") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Thank you for your donation",
					"Dear %s, thank you for your generous donation to %s on %s. Hare Krishna."
							.formatted(value(params, "donor"), value(params, "temple"), value(params, "date")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("donor", "temple", "date");
		}
	},

	/**
	 * The donor's 80G receipt has been issued, and can be sent again as often as they ask (T-110).
	 *
	 * <p><strong>Separate from {@link #DONATION_THANK_YOU}, and not a replacement for it.</strong>
	 * The thank-you goes once, unprompted, the moment the money settles, and it is a temple thanking
	 * a devotee. This one is a tax document being handed over, usually weeks later and usually
	 * because somebody asked for it in March. Folding the two would mean either thanking a donor
	 * twice for one gift or making the receipt arrive dressed as a thank-you.
	 *
	 * <p>It carries the receipt number rather than the amount. The number is what a donor quotes back
	 * to the office when they cannot find the paper, and it is the same number for ever — whereas an
	 * amount in a message a person keeps on their phone is one more figure that can be misread
	 * against the one on the receipt itself.
	 *
	 * <p><strong>The body is registered with Meta under {@code donation_receipt} and must be approved
	 * before WhatsApp will carry it.</strong> Until it is, a send falls through to SMS and email, as
	 * every unapproved template does.
	 */
	DONATION_RECEIPT("donation_receipt") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Your donation receipt " + value(params, "receiptNumber"),
					("Dear %s, your receipt %s for the donation you made to %s on %s has been issued. "
							+ "Please ask at the temple office for a copy. Hare Krishna.").formatted(
									value(params, "donor"), value(params, "receiptNumber"),
									value(params, "temple"), value(params, "date")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("donor", "receiptNumber", "temple", "date");
		}
	},

	SHIFT_BROADCAST("shift_broadcast") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Update about your shift: " + value(params, "title"),
					"Update about your %s shift: %s".formatted(value(params, "title"), value(params, "message")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "message");
		}
	},

	VOLUNTEER_SHIFT_REMINDER("volunteer_shift_reminder") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Reminder: " + value(params, "title"),
					"Reminder: your %s shift is on %s, %s at %s. If you can't make it, please release your spot in the app."
							.formatted(value(params, "title"), value(params, "date"),
									value(params, "time"), value(params, "location")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "date", "time", "location");
		}
	},

	SHIFT_SIGNUP_CONFIRMED("shift_signup_confirmed") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"You're signed up: " + value(params, "title"),
					"Hare Krishna! You're signed up for %s on %s, %s at %s. Thank you for your seva."
							.formatted(value(params, "title"), value(params, "date"),
									value(params, "time"), value(params, "location")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "date", "time", "location");
		}
	},

	WAITLIST_PROMOTED("waitlist_promoted") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"A spot opened: you're in for " + value(params, "title"),
					"Good news! A spot opened and you're now signed up for %s on %s, %s at %s. See you there!"
							.formatted(value(params, "title"), value(params, "date"),
									value(params, "time"), value(params, "location")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "date", "time", "location");
		}
	},

	/**
	 * The volunteer a coordinator has taken off a roster, told so (T-080).
	 *
	 * <p>The sibling of {@link #WAITLIST_PROMOTED} and the reason this exists: a removal promoted the
	 * head of the waitlist into the freed spot and sent <em>them</em> "a spot opened, you're in",
	 * while the person who had just lost the shift was sent nothing at all and found out by looking.
	 * {@code WAITLIST_PROMOTED} is deliberately unchanged — the promotion is still good news and
	 * still reads like it.
	 *
	 * <p><strong>{@code reason} is the structured half, and never the coordinator's note.</strong>
	 * T-080 records two things against a removal: four fixed answers the coordinator picks, and a
	 * mandatory free-text note kept internal. Only the first reaches this template, already rendered
	 * into a plain clause by {@code RemoveVolunteerRequest.Reason}. The note has no route to any
	 * channel, which is the whole design: the coordinator must say why either way, and the volunteer
	 * gets the version that does not sting.
	 *
	 * <p>One template with a hole rather than four, which is the opposite of the call made for the
	 * three leave decisions below — and the difference is what the hole can do to the sentence.
	 * There, the hole would have flipped an approval into a refusal, and the three messages did not
	 * say the same thing past their first clause. Here all four say one thing — you are no longer on
	 * this shift — and differ only in a because-clause; none of them turns the news good. The four
	 * values are constants in our own code and cannot be typed by anybody, so Meta is being shown a
	 * genuinely fixed body with a genuinely small set of fillings.
	 *
	 * <p>The copy is written for somebody who may have been let down rather than let go. It thanks
	 * them, points them somewhere, and carries no hint that the reason might be about them.
	 */
	REMOVED_FROM_SHIFT("removed_from_shift") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"A change to your shift: " + value(params, "title"),
					"Hare Krishna. You're no longer on the %s shift on %s, %s at %s. Reason: %s. Thank you for offering to serve — please check the app for other shifts."
							.formatted(value(params, "title"), value(params, "date"),
									value(params, "time"), value(params, "location"),
									value(params, "reason")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "date", "time", "location", "reason");
		}
	},

	SHIFT_CANCELLED("shift_cancelled") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Shift cancelled: " + value(params, "title"),
					"We're sorry — the %s shift on %s at %s has been cancelled. Thank you for offering to serve; please check the app for other shifts."
							.formatted(value(params, "title"), value(params, "date"), value(params, "temple")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "date", "temple");
		}
	},

	STAFF_SCHEDULE_UPDATED("staff_schedule_updated") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Your schedule at " + value(params, "temple") + " has changed",
					"Hare Krishna %s, your work schedule at %s has been updated. Please check the app for your latest hours."
							.formatted(value(params, "name"), value(params, "temple")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("name", "temple");
		}
	},

	/**
	 * A letter a temple wrote (E8-S2). Its body does not travel in these parameters — see
	 * {@link OutboundBodySource} — so what renders here is the fallback for a communication whose
	 * record has since gone, which should not happen and should still say something sensible.
	 */
	TEMPLE_COMMUNICATION("temple_communication") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(value(params, "subject"), value(params, "subject"));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("subject");
		}

		@Override
		public CommunicationCategory category() {
			return null; // the sender states it, per send
		}

		@Override
		public String whatsappCategory() {
			return "MARKETING";
		}
	},

	/**
	 * The same letter, on WhatsApp, which cannot carry it.
	 *
	 * <p>Meta delivers only templates it has already approved, so a pasted newsletter has no road
	 * onto this channel at all. What goes instead is this: the temple's name, the subject, one line
	 * the admin writes, and a link to the full thing. It is the one MARKETING template we have —
	 * priced higher, reviewed harder, and rate-limited by the number's quality rating — and calling
	 * it UTILITY to avoid that would be untrue.
	 */
	TEMPLE_ANNOUNCEMENT("temple_announcement") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					value(params, "subject"),
					"A message from %s — %s: %s Read it here: %s".formatted(
							value(params, "temple"), value(params, "subject"),
							value(params, "intro"), value(params, "link")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("temple", "subject", "intro", "link");
		}

		@Override
		public CommunicationCategory category() {
			return null; // the sender states it, per send
		}

		@Override
		public String whatsappCategory() {
			return "MARKETING";
		}
	},

	// Three templates for one decision, rather than one with an "outcome" parameter.
	//
	// Meta approves a body, not a sentence with a hole in it, and a hole that flips the sentence
	// from good news to bad is exactly what gets a template rejected — or, worse, approved and then
	// used to send "Your leave has been declined" rendered as an approval because a caller passed
	// the wrong word. The three also do not say the same thing beyond their first clause: an
	// approval needs nothing further, a refusal has to point somewhere, and a revocation has to be
	// unmistakable that the person is expected in after all.
	LEAVE_APPROVED("leave_approved") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Your leave at " + value(params, "temple") + " is approved",
					"Hare Krishna %s, your leave at %s for %s has been approved."
							.formatted(value(params, "name"), value(params, "temple"), value(params, "dates")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("name", "temple", "dates");
		}
	},

	LEAVE_DECLINED("leave_declined") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Your leave at " + value(params, "temple") + " was not approved",
					"Hare Krishna %s, your leave request at %s for %s was not approved. Please speak to your manager, who has recorded the reason."
							.formatted(value(params, "name"), value(params, "temple"), value(params, "dates")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("name", "temple", "dates");
		}
	},

	/**
	 * Leave granted and then taken back. Not in the brief's list of two, and added anyway: somebody
	 * who has been told they are off arranges their week around it, and letting them find out by
	 * turning up on the wrong day would be a worse failure than any this system otherwise has.
	 */
	LEAVE_REVOKED("leave_revoked") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Your leave at " + value(params, "temple") + " has been withdrawn",
					"Hare Krishna %s, the leave you were granted at %s for %s has been withdrawn, so you are expected as usual. Please speak to your manager."
							.formatted(value(params, "name"), value(params, "temple"), value(params, "dates")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("name", "temple", "dates");
		}
	},

	LOW_STOCK_DIGEST("low_stock_digest") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Low stock at " + value(params, "temple"),
					"%s item(s) at %s are below their reorder level: %s.".formatted(
							value(params, "count"), value(params, "temple"), value(params, "items")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("count", "temple", "items");
		}
	},

	/**
	 * The test message a temple administrator sends from Settings to a phone they name (T-151).
	 *
	 * <p>Rajeev, 2026-09-12: <em>"Ask the use for a phone number to send a test message."</em> Until
	 * then the Test button only re-read the number's details from Meta and put nothing in front of
	 * anybody, and the Send on WhatsApp button on a purchase order waits for a message that actually
	 * went out (T-136). A temple that uses WhatsApp only for orders could never earn it.
	 *
	 * <p><strong>Why a template of our own and not something already there.</strong> Three things were
	 * established from Meta's documentation before this was added, and each rules out an alternative:
	 * <ul>
	 *   <li>Free text cannot be used. "Template messages are the only type of message that can be
	 *       sent to WhatsApp users outside of a customer service window", and an administrator typing
	 *       a colleague's number has no open window with them.</li>
	 *   <li>Meta's {@code hello_world} sample cannot be relied on. Meta's documentation names it only
	 *       inside the Get Started flow, whose test account and test number "are automatically created
	 *       for you". Nothing Meta publishes says a temple's own production account has it, and a Test
	 *       button that works on a developer's account and fails on a temple's is worse than none.</li>
	 *   <li>A reminder or an order with invented values would reach a real person as a real-looking
	 *       shift or purchase order. That is not a test, it is a false message.</li>
	 * </ul>
	 *
	 * <p>The cost, stated so nobody is surprised by it: like every template here it is registered when
	 * the temple presses Connect or Save, and "Templates must have a status of APPROVED before they can
	 * be sent". Meta says "Review can take up to 24 hours". Until then the test is refused, and
	 * the screen says so. A temple connected before this existed has to press Save once to register it.
	 *
	 * <p>UTILITY and OPERATIONAL like the rest: it is the direct consequence of an administrator
	 * pressing a button to send it, to a number they chose, and there is nothing in it to opt out of.
	 */
	WHATSAPP_TEST("connection_test") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"WhatsApp test message",
					"Hare Krishna. This is a test message from %s. If it has reached you, the temple can send WhatsApp messages."
							.formatted(value(params, "temple")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("temple");
		}
	};

	private final String whatsappTemplateName;

	NotificationTemplate(String whatsappTemplateName) {
		this.whatsappTemplateName = whatsappTemplateName;
	}

	/** The name of the corresponding Meta-approved WhatsApp template. */
	public String whatsappTemplateName() {
		return whatsappTemplateName;
	}

	public abstract RenderedMessage render(Map<String, Object> params);

	/**
	 * The parameters this message's body reads, in the order the body reads them.
	 *
	 * <p>Meta's templates are positional — {@code {{1}}}, {@code {{2}}} — while everything else here
	 * addresses parameters by name, and this is the one place the two meet. Getting the order wrong
	 * does not fail: it sends somebody a shift reminder with the date where the temple should be.
	 */
	public abstract List<String> parameterOrder();

	/**
	 * The body as Meta stores it, with numbered placeholders — derived by rendering the message with
	 * each parameter set to its own placeholder.
	 *
	 * <p>Derived rather than written out a second time on purpose. A hand-copied WhatsApp body would
	 * drift from the SMS one the first time a sentence was reworded, and nothing would notice: the
	 * WhatsApp copy lives at Meta, months from the code that no longer matches it. This way there is
	 * one sentence, and WhatsApp gets a view of it.
	 */
	public String whatsappBodyText() {
		Map<String, Object> placeholders = new LinkedHashMap<>();
		List<String> order = parameterOrder();
		for (int i = 0; i < order.size(); i++) {
			placeholders.put(order.get(i), "{{" + (i + 1) + "}}");
		}
		return render(placeholders).body();
	}

	/**
	 * Every message here is UTILITY: each one is the consequence of something the temple or the
	 * person already did — a shift taken, an order placed, a gift given. None of it is marketing,
	 * which Meta prices differently and judges more harshly, and calling it so would be untrue as
	 * well as expensive.
	 */
	public String whatsappCategory() {
		return "UTILITY";
	}

	/**
	 * What kind of message this is, for the purposes of a devotee's preferences (E8-S1) — or null
	 * where only the sender can say.
	 *
	 * <p>Almost every template here is OPERATIONAL, and that is the same sentence as the paragraph
	 * above: each is the consequence of something the person already did, which is exactly what makes
	 * it something they cannot be asked to opt out of.
	 *
	 * <p>The two that carry a letter somebody wrote are the exception, and they return null rather
	 * than a plausible guess. Their category is chosen per send — the same template carries a
	 * newsletter one week and a festival announcement the next — so a default here would be a fact
	 * invented to fill a field, and the wrong one would silently deliver to somebody who had opted
	 * out. {@code NotificationService} refuses to send them without being told.
	 */
	public CommunicationCategory category() {
		return CommunicationCategory.OPERATIONAL;
	}

	/** Sample values for Meta's reviewer, who will not approve a template without them. */
	public List<String> whatsappExampleValues() {
		return parameterOrder().stream().map(NotificationTemplate::example).toList();
	}

	private static String example(String parameter) {
		return switch (parameter) {
			case "temple" -> "ISKCON South Bengaluru";
			case "date" -> "12 August";
			case "time" -> "6:00 am";
			case "role", "title" -> "Kitchen seva";
			case "location" -> "Main kitchen";
			case "donor", "name" -> "Radha Devi";
			case "poNumber" -> "PO-1042";
			// A donation receipt (T-110), in the shape DonationReceiptService actually issues.
			case "receiptNumber" -> "R-2026-0042";
			case "raised" -> "1 Aug 2026";
			case "neededBy" -> "5 Aug 2026";
			case "vendor" -> "Sri Balaji Traders";
			case "summary" -> "25 kg rice, 10 kg dal";
			case "message" -> "Please arrive fifteen minutes early";
			// Why a volunteer came off a roster (T-080). One of exactly four clauses, and the sample
			// is one of them verbatim rather than an invented sentence — Meta's reviewer is being
			// shown the real range of this hole, which is the argument for it being one template.
			case "reason" -> "the rota changed";
			case "count" -> "3";
			case "items" -> "rice, toor dal, ghee";
			case "subject" -> "Janmashtami at the temple";
			case "intro" -> "Kitchen seva starts at 4am and everyone is welcome.";
			case "link" -> "https://example.org/c/2f6a1c";
			case "dates" -> "12 to 14 August 2026";
			// A split wish-list gift (T-081). Meta's reviewer sees the grinder Rajeev argued it from:
			// ₹14,000 given, ₹4,000 of it all that was still owed, ₹10,000 to the general fund.
			case "item" -> "A wet grinder";
			case "amount" -> "₹14,000";
			case "applied" -> "₹4,000";
			case "remainder" -> "₹10,000";
			default -> parameter;
		};
	}

	static String value(Map<String, Object> params, String key) {
		Object raw = params == null ? null : params.get(key);
		return raw == null ? "" : raw.toString();
	}
}
