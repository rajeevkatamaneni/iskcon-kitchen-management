package org.iskcon.kms.communication;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writing to the temple's community (E8-S2, E8-S3).
 *
 * <p>Three things happen here and they are deliberately separate acts: a communication is
 * <b>drafted</b> (and re-drafted, and previewed, as often as anyone likes), <b>tested</b> by sending
 * it to the author alone, and only then <b>sent</b>. Nothing about a draft touches a devotee.
 *
 * <p>The audience is computed at send time and written down. "Who did this reach" must stay
 * answerable a year later, when the list of devotees has changed and recomputing it would give a
 * different answer.
 *
 * <p>What is not here, and cannot be: a WhatsApp message carrying the letter. Meta delivers only
 * templates it has already approved, so the WhatsApp form of a communication is a short approved
 * announcement with a link back to the web copy. Saying otherwise on the screen would be promising
 * something the channel will refuse.
 */
@Service
public class CommunicationService {

	private static final Logger log = LoggerFactory.getLogger(CommunicationService.class);

	private final JdbcTemplate jdbc;
	private final NewsletterHtml html;
	private final UnsubscribeTokens tokens;
	private final NotificationService notifications;
	private final AuditService auditService;
	private final TransactionTemplate transactions;
	private final String webBaseUrl;

	public CommunicationService(
			JdbcTemplate jdbc, NewsletterHtml html, UnsubscribeTokens tokens,
			NotificationService notifications, AuditService auditService,
			PlatformTransactionManager transactionManager,
			@Value("${kms.web-base-url:http://localhost:3000}") String webBaseUrl) {
		this.jdbc = jdbc;
		this.html = html;
		this.tokens = tokens;
		this.notifications = notifications;
		this.auditService = auditService;
		// A send is two transactions with a boundary that matters; see send(). Declared here rather
		// than reached for through self-invocation, which would not go through the proxy at all.
		this.transactions = new TransactionTemplate(transactionManager);
		this.webBaseUrl = webBaseUrl.endsWith("/")
				? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
	}

	// ---- Drafting -------------------------------------------------------

	@Transactional(readOnly = true)
	public List<CommunicationView> list() {
		return jdbc.query(SELECT + " ORDER BY c.created_at DESC", MAPPER);
	}

	@Transactional(readOnly = true)
	public CommunicationView get(UUID id) {
		return find(id).orElseThrow(() -> notFound(id));
	}

	@Transactional
	public UUID save(AuthenticatedUser actor, UUID id, SaveCommunicationRequest request) {
		requireComposable(request.category());
		String safeHtml = html.sanitise(request.bodyHtml());
		String text = html.toPlainText(safeHtml);
		requireSomethingToSay(request, text);

		if (id == null) {
			UUID created = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO communications (
						id, tenant_id, category, channel, subject, body_html, body_text,
						whatsapp_summary, created_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
					""", created, request.category().name(), request.channel().name(),
					request.subject().trim(), safeHtml, text,
					trimToNull(request.whatsappSummary()), actor.getUserId());
			return created;
		}

		CommunicationView existing = find(id).orElseThrow(() -> notFound(id));
		requireDraft(existing);
		jdbc.update("""
				UPDATE communications SET category = ?, channel = ?, subject = ?, body_html = ?,
					body_text = ?, whatsapp_summary = ?, updated_at = now() WHERE id = ?
				""", request.category().name(), request.channel().name(), request.subject().trim(),
				safeHtml, text, trimToNull(request.whatsappSummary()), id);
		return id;
	}

	@Transactional
	public void delete(UUID id) {
		CommunicationView existing = find(id).orElseThrow(() -> notFound(id));
		requireDraft(existing);
		jdbc.update("DELETE FROM communications WHERE id = ?", id);
	}

	/**
	 * Exactly what will arrive, rendered the same way the send renders it.
	 *
	 * <p>The same method builds the preview and the message, which is the only way a preview is worth
	 * anything: two code paths that agree today would disagree the first time one was edited, and the
	 * preview would go on being reassuring while being wrong.
	 */
	@Transactional(readOnly = true)
	public PreviewView preview(UUID id) {
		CommunicationView c = find(id).orElseThrow(() -> notFound(id));
		String temple = templeName();
		return new PreviewView(
				c.subject(),
				html.frame(temple, c.subject(), c.bodyHtml(),
						// A sample link, because a preview has no recipient to issue a real one for.
						webBaseUrl + "/unsubscribe?token=sample", webUrl(c.publicToken())),
				NotificationTemplate.TEMPLE_ANNOUNCEMENT.render(Map.of(
						"temple", temple,
						"subject", c.subject(),
						"intro", c.whatsappSummary() == null ? "" : c.whatsappSummary(),
						"link", webUrl(c.publicToken()))).body(),
				c.bodyText());
	}

	// ---- Sending --------------------------------------------------------

	/**
	 * The author's own copy, before four hundred other people get one.
	 *
	 * <p>Offered because a preview and a real message are not quite the same claim. The preview shows
	 * what we will hand the relay; only an actual send shows what Gmail or Outlook then makes of it,
	 * and that is the thing nobody can promise from inside this system.
	 */
	@Transactional
	public void sendTest(AuthenticatedUser actor, UUID id) {
		CommunicationView c = find(id).orElseThrow(() -> notFound(id));
		queueFor(c, actor.getUserId(), true);
		log.info("Test copy of communication {} queued for its author {}", id, actor.getUserId());
	}

	/**
	 * Sends to everyone who has not declined this kind of message.
	 *
	 * <p><b>Written down first, handed to the relay second, and the boundary between the two is a
	 * transaction (T-094).</b> Both used to happen inside one, and that arrangement could not keep
	 * the promise {@link #queueFor}'s own comment makes. {@code NotificationService.notify} is itself
	 * {@code @Transactional}, so an exception leaving it is not merely caught here: Spring marks the
	 * whole surrounding transaction rollback-only on the way out, and the commit at the end of the
	 * send then fails with {@code UnexpectedRollbackException} whatever this code did with the
	 * exception. One devotee the relay refused therefore <em>did</em> abandon the other three hundred
	 * and ninety — and took with it every recipient row already written for the copies it had
	 * accepted, so afterwards nothing anywhere named who this message had been meant to reach.
	 *
	 * <p>So: the audience is written down and the message marked sent in one transaction that touches
	 * nobody, and the copies go to the relay afterwards, outside it. That is the same two steps
	 * {@code BroadcastService.plan}/{@code deliver} has used since E6-S7, for the same reason, against
	 * the table {@code communication_recipients} was modelled on.
	 *
	 * <p><b>What this changes, said plainly: a partial send is now durable.</b> If the process dies
	 * between the two steps, or the relay refuses every copy, the message stays SENT with a row for
	 * every intended recipient naming no notification — which the screen reads as <i>Failed</i> and
	 * <i>Send it to them again</i> then reaches. The failure that is <em>not</em> recoverable is the
	 * other one: rolling the record back while copies are already on their way, after which pressing
	 * Send again writes to those devotees twice.
	 */
	public SendResultView send(AuthenticatedUser actor, UUID id) {
		Sent sent = transactions.execute(status -> recordSend(actor, id));

		int queued = 0;
		for (UUID userId : sent.audience()) {
			if (queueFor(sent.communication(), userId, false)) {
				queued++;
			}
		}

		log.info("Communication {} sent to {} recipients ({} queued)",
				id, sent.audience().size(), queued);
		return new SendResultView(sent.audience().size(), queued);
	}

	/**
	 * Who this message is for, written down before a word of it is handed to anybody (T-094).
	 *
	 * <p>Every intended recipient gets a row here, naming no notification yet, because the record of
	 * who a message was <em>meant</em> to reach belongs to the send and not to whatever the relay
	 * makes of each copy one at a time. A row was previously written only after {@code notify}
	 * returned, so a devotee it threw on got no row at all — and a person with no row is not a failed
	 * recipient, they are not a recipient: {@link #failedRecipients} joins from this table and could
	 * never name them, {@link #retryFailed} could therefore never reach them, and
	 * {@link #deliveries} did not list them on the screen that answers "did it actually go?".
	 * Meanwhile {@code audience_count} was written from the audience, so the count said forty and the
	 * rows said thirty-nine with nothing anywhere naming the missing one. The count and the rows are
	 * now written in the same transaction from the same list and cannot disagree.
	 *
	 * <p>A plain INSERT, with no {@code ON CONFLICT} clause, and it is no longer what makes a draft
	 * send once (T-102). What says so is the {@code WHERE id = ? AND status = 'DRAFT'} on the UPDATE
	 * below — a predicate in the same statement as the state change, which cannot be lost while
	 * somebody edits this INSERT. That matters because the obvious edit here <em>is</em> an
	 * {@code ON CONFLICT} clause, added to stop the loser of a race dying on a duplicate key, and
	 * until T-102 that clause would have quietly removed the last thing standing between four hundred
	 * devotees and a second copy of the letter. It no longer can: a second sender is refused by the
	 * row that records the send, before {@link #queueFor} is reached and before anybody is written to.
	 * The INSERT stays plain all the same, because a conflict here would still be a fact worth
	 * hearing about rather than a row to skip.
	 */
	private Sent recordSend(AuthenticatedUser actor, UUID id) {
		// Before the status is read, not after (T-096). The read below, the decision requireDraft
		// takes on it and the UPDATE that writes SENT are one act or they are nothing, and only this
		// makes them one. A lock taken after find() would leave the decision standing on a snapshot
		// the lock does not cover, which reads as a fix and is not one.
		lockCommunication(id);

		CommunicationView c = find(id).orElseThrow(() -> notFound(id));
		requireDraft(c);

		List<UUID> audience = audienceFor(c);
		if (audience.isEmpty()) {
			throw new ApplicationException(ErrorCode.COMMUNICATION_HAS_NO_AUDIENCE,
					Map.of("communicationId", id, "category", c.category().name()));
		}

		jdbc.batchUpdate("""
				INSERT INTO communication_recipients (
					id, tenant_id, communication_id, recipient_user_id)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?)
				""", audience.stream().map(userId -> new Object[] {id, userId}).toList());

		// The transition guards itself (T-102). "A letter is sent once" was true of this method and
		// was nowhere stated in the statement that made it true: requireDraft read the status fifteen
		// lines and a whole audience resolution above an UPDATE that wrote it unconditionally, and what
		// joined the two was the row lock plus, by accident, a unique index on another table. The
		// predicate belongs here, beside the state change, where the edit that would break it is made.
		//
		// Under READ COMMITTED a second sender that somehow reached this line with a stale DRAFT in
		// hand blocks on the row, re-evaluates status = 'DRAFT' once the first commits, matches nothing,
		// and is refused in the same plain sentence requireDraft would have given it. That is now true
		// independently of lockCommunication and of communication_recipients_once: take either away and
		// the letter still cannot go twice, which is what stops the index from being load-bearing.
		int rows = jdbc.update("""
				UPDATE communications SET status = 'SENT', sent_at = now(), audience_count = ?,
					updated_at = now()
				WHERE id = ? AND status = 'DRAFT'
				""", audience.size(), id);
		if (rows != 1) {
			// Matching no row means the status moved under us, which is the same fact requireDraft names
			// and deserves the same words: the message has already been sent. Nothing has been written —
			// the recipient rows this transaction inserted go with it when the exception rolls it back.
			throw new ApplicationException(ErrorCode.COMMUNICATION_ALREADY_SENT,
					Map.of("communicationId", id));
		}

		auditService.record(actor, AuditAction.COMMUNICATION_SENT,
				AuditEntityType.COMMUNICATION, id, null,
				Map.of("category", c.category().name(), "channel", c.channel().name(),
						"subject", c.subject(), "recipients", audience.size()),
				null);

		return new Sent(c, audience);
	}

	/**
	 * Sends it again to the people it failed for, and to nobody else (B6).
	 *
	 * <p>Forty failed WhatsApp deliveries used to mean writing the whole letter again, with the list
	 * of who it failed for sitting on the screen in front of the person retyping it. Nothing about
	 * that was necessary: {@code communication_recipients} already names each person and the
	 * notification their copy became, so the failures are already known by name.
	 *
	 * <p><b>Only delivery is retried.</b> The letter itself stays frozen the moment it is sent —
	 * {@link #requireDraft} goes on refusing edit, delete and send exactly as before, and this path
	 * reads the subject and body back out of the row rather than taking anything from the request.
	 * The person who received a copy at nine o'clock and the person who receives one at ten must be
	 * reading the same words, or "who did this reach" stops meaning anything.
	 *
	 * <p>Only {@code FAILED} is a failure. {@code SUPPRESSED} is a decision — a devotee turned this
	 * kind off, or never agreed to be contacted at all — and sending to them again would be
	 * overriding them; the second attempt would be suppressed identically anyway. {@code PENDING} is
	 * a copy still on its way, and re-queueing it would deliver the message twice.
	 *
	 * <p>This one stays in a single transaction, unlike {@link #send}, and the asymmetry is
	 * deliberate (T-094). A retry's whole safety rests on {@link #lockCommunication} still being held when
	 * the recipient rows are re-pointed, and a lock ends where its transaction does; handing the
	 * copies to the relay outside it would open exactly the window the lock exists to close. The
	 * trade it accepts in return is that a relay that throws here rolls the retry back whole — which
	 * costs nothing, because the recipients it was retrying are left saying FAILED, the button is
	 * still there, and nothing has been recorded that did not happen. A send cannot make that trade:
	 * there the record of who it was for is the only copy of a list that will never be recomputed the
	 * same way again.
	 *
	 * <p><b>And that rollback is now something the sender is told, in words (T-100).</b> It reached
	 * them as KMS-500001 — <i>"Something went wrong at our end"</i>, a bare 500 with an incident id
	 * and no next step — because nothing anywhere handled the {@code UnexpectedRollbackException}
	 * the paragraph above describes as the <em>chosen</em> behaviour. An outcome this javadoc calls
	 * correct should not arrive at a temple admin as an incident. It is
	 * {@code COMMUNICATION_RETRY_FAILED}, and its next step carries the one fact that makes it
	 * bearable: nobody was written to, so pressing the button again cannot give anybody two copies.
	 *
	 * <p>Which is the only reason the transaction is opened through {@link TransactionTemplate} here
	 * rather than declared with {@code @Transactional}. <b>A method cannot catch the failure of its
	 * own commit:</b> the {@code UnexpectedRollbackException} is raised by the proxy <em>after</em>
	 * the annotated method has returned, so with the annotation the nearest place able to see it is
	 * {@code GlobalExceptionHandler} — and an {@code @ExceptionHandler} for
	 * {@code UnexpectedRollbackException} is a handler for <em>every</em> rollback anywhere in the
	 * application, which would answer <i>"we couldn't send those copies"</i> to a failed stock
	 * adjustment. Opening the transaction explicitly puts the commit inside a method that can see
	 * it, so the translation is scoped to this one path by construction rather than by sniffing the
	 * request path in a handler. {@link #send} already opens its transaction the same way.
	 */
	public RetryResultView retryFailed(AuthenticatedUser actor, UUID id) {
		try {
			return transactions.execute(status -> retryWithin(actor, id));
		} catch (UnexpectedRollbackException e) {
			// The one thing that can mark this transaction rollback-only without throwing on its own
			// account is queueFor()'s catch: notify() is transactional, so a relay that refuses a copy
			// unwinds the whole retry however politely queueFor logs it. Every other step in the body
			// throws in its own name and never reaches here. So this is not a general "some rollback
			// happened" mapping wearing a specific sentence — it is the specific outcome, named.
			//
			// And the next step's promise is a statement about this rollback, so it is worth saying
			// where it is true: notify() writes the notification row and enqueues its send on Quartz,
			// whose job store is jdbc, so LocalDataSourceJobStore takes its connection through
			// DataSourceUtils and joins this very transaction. The rollback therefore takes back the
			// notification rows, the re-pointed recipient rows and the triggers together; nothing was
			// handed to the relay, and nobody gets a second copy for pressing again.
			log.warn("Retry of communication {} by {} rolled back whole — the relay refused a copy",
					id, actor.getUserId(), e);
			throw new ApplicationException(
					ErrorCode.COMMUNICATION_RETRY_FAILED, Map.of("communicationId", id), e);
		}
	}

	private RetryResultView retryWithin(AuthenticatedUser actor, UUID id) {
		CommunicationView c = find(id).orElseThrow(() -> notFound(id));

		lockCommunication(id);

		List<UUID> failed = failedRecipients(id);
		if (failed.isEmpty()) {
			throw nothingToRetry(id, c);
		}

		// This path is total-or-nothing, and cannot be anything else (T-100).
		//
		// queueFor() returns false only from its catch, and that catch is precisely what makes the
		// commit fail: notify() is transactional, so the exception it swallowed has already marked
		// this transaction rollback-only. No execution that reaches the return below can therefore
		// have retried < failed.size(). The two are always equal, the audit entry's "failed" and
		// "retried" always agree, and RetryResultView(retried) is always failed.size(). A reader —
		// or somebody querying the audit rows — looking for the partial retry those three numbers
		// appear to allow will not find one, because none can be produced.
		//
		// That is the exact opposite of send(), where partial is genuine and is the point: send()
		// hands the copies to the relay outside any transaction (T-094), so one devotee the relay
		// refuses leaves the other three hundred and ninety queued, and its queued < audience is a
		// real number about a real state. The asymmetry between the two is the confusing part, so it
		// is written down here rather than left to be re-derived from two transaction boundaries.
		//
		// The count is nonetheless kept as a count rather than collapsed to failed.size(). Deriving
		// it from what queueFor() actually reported stays true if the transaction shape here is ever
		// changed the way T-094 changed send()'s; substituting the constant would turn a number that
		// is correct by construction into a claim that would go on being asserted after it stopped
		// being true, which is the shape of defect this codebase keeps finding in its own sums.
		int retried = 0;
		for (UUID userId : failed) {
			// The same queueFor() the original send used, so a retry is the send it is retrying and
			// not a second implementation of it that would drift from it by the second edit.
			if (queueFor(c, userId, false)) {
				retried++;
			}
		}

		// Audited, and under an action of its own (T-084). Re-sending a newsletter to forty devotees
		// is the same class of act as sending it — outward-facing, to real people, by a named user —
		// so "who caused this message to reach this devotee, and when" has to be answerable for the
		// second attempt as it already was for the first, and a log line is not that: the audit log is
		// the per-tenant record operators and admins actually read.
		//
		// COMMUNICATION_RETRIED rather than a second COMMUNICATION_SENT carrying a distinguishing
		// field. A count over an action name goes on compiling perfectly when the meaning of the rows
		// underneath it changes, so anything totalling COMMUNICATION_SENT as "messages this temple
		// sent" would quietly begin counting retries of them as well, and nothing would ever say so.
		auditService.record(actor, AuditAction.COMMUNICATION_RETRIED,
				AuditEntityType.COMMUNICATION, id, null,
				Map.of("category", c.category().name(), "channel", c.channel().name(),
						"subject", c.subject(), "failed", failed.size(), "retried", retried),
				null);

		log.info("Communication {} retried by {} for {} of its {} failed recipients",
				id, actor.getUserId(), retried, failed.size());
		return new RetryResultView(retried);
	}

	/**
	 * Holds the communication row for the rest of this transaction, before a word is read about who
	 * its message is for or who it failed for (T-084, T-096).
	 *
	 * <p>The same lock, for the same reason, as {@code ServedMealService.correct} and
	 * {@code SignupService.lockShift}: what follows is a read, a decision taken on it, and a write,
	 * and under READ COMMITTED those three do not belong to one another unless something says so.
	 * Two admins pressing <i>Send it to them again</i> at the same moment otherwise both run
	 * {@link #failedRecipients}, both see the identical list — neither has committed anything the
	 * other can see — and both queue a fresh copy for every name on it. <b>Every failed recipient is
	 * then written to twice.</b> {@code queueFor}'s {@code ON CONFLICT … DO UPDATE} does not save it:
	 * that clause decides only which of the two notifications the recipient row ends up naming, and
	 * the copy it does not name has already been handed to the relay. The screen's disabled button
	 * covers one browser tab and nothing else.
	 *
	 * <p>With the lock the second transaction waits, then reads the failed set the first one left
	 * behind — empty, because a retried copy is PENDING and no longer failed — and is refused. A
	 * refusal is the right answer there: the message <em>is</em> on its way, and telling somebody so
	 * is the whole of what they wanted to know.
	 *
	 * <p><b>{@link #recordSend} takes the same lock, and had wanted it longer (T-096).</b> Two admins
	 * pressing <i>Send</i> at the same moment otherwise both read DRAFT — neither has committed
	 * anything the other can see — both pass {@link #requireDraft}, and both go on to write the whole
	 * letter down and hand it to the relay for the whole audience.
	 *
	 * <p><b>What stood between four hundred devotees and a second copy of the letter used to be
	 * neither this lock nor any other line of this code: it was an index (T-102).</b>
	 * {@code communication_recipients_once} refused the second transaction's rows, so with the lock
	 * removed the loser of the race died on a duplicate key. That was a backstop and not an answer,
	 * for two reasons. It reported a <em>technical</em> failure — a constraint name and a 500 — to
	 * somebody whose whole mistake was pressing a button that was already pressed, when the plain
	 * sentence for exactly that is the one {@link #requireDraft} already carries. And it held only
	 * while that INSERT stayed a plain INSERT: giving it an {@code ON CONFLICT} clause, the obvious
	 * way to make the 500 go away, would have taken the backstop with it and said nothing.
	 *
	 * <p>{@link #recordSend}'s UPDATE now carries {@code AND status = 'DRAFT'} and refuses when it
	 * matches no row, so the invariant is stated by the statement that records the send. This lock is
	 * therefore defence in depth on that path rather than the thing holding it up — it still serialises
	 * the read of the audience, and it still gives the second admin the friendly refusal from
	 * {@link #requireDraft} rather than one produced by a failed write. Both can now be reasoned about
	 * separately, which is the point: remove either and the letter still cannot go twice.
	 *
	 * <p>With the lock the second transaction waits, reads the SENT the first one left behind, and is
	 * refused with COMMUNICATION_ALREADY_SENT — which is true, is plain, and is what happened. It is
	 * taken inside the first of {@link #send}'s two transactions and not around both: {@code send}
	 * itself is not transactional, so a lock taken there would commit and release on the spot and
	 * hold nothing, while a lock held across the second half would hold one row for the length of a
	 * four-hundred-copy relay run.
	 *
	 * <p>{@code queryForList} rather than {@code queryForObject} so a row deleted between
	 * {@link #find} and here is no rows rather than an exception nobody could act on; the caller's
	 * own guards speak for that case.
	 */
	private void lockCommunication(UUID id) {
		jdbc.queryForList("SELECT id FROM communications WHERE id = ? FOR UPDATE", UUID.class, id);
	}

	/**
	 * Why there is nothing to send again — which is three different facts, and used to be one
	 * sentence (T-084).
	 *
	 * <p>An empty failed set has three causes and they are not variations on each other. Every copy
	 * genuinely arrived. Or copies are still on their way, so nothing has failed <em>yet</em> and
	 * nothing has been confirmed delivered either. Or the message is a draft and has no recipients at
	 * all, having never been sent to anybody. {@code NOTHING_FAILED_TO_RETRY} said <i>"Every copy of
	 * this message was delivered"</i> for all three, which for the last one told the sender that a
	 * letter nobody has ever received was fully delivered — the confidently wrong sentence the
	 * error-code rule exists to keep out.
	 *
	 * <p>The branch is on the recipient rows <b>and on the status</b>, which is a correction (T-094).
	 * It was on the rows alone, with a note that a message marked SENT whose every queueing attempt
	 * threw has no rows either and is caught by the same clause. It is — and it was then told
	 * <i>"This message hasn't been sent yet. Send it first."</i>, which sent the reader to
	 * {@link #send}, where {@link #requireDraft} refused them with COMMUNICATION_ALREADY_SENT. Two
	 * errors contradicting each other and the message reaching nobody by either route: the shape the
	 * error-code rule exists to keep out, an error whose next step names a door the reader is not
	 * allowed through. No rows and DRAFT is genuinely not-sent; no rows and SENT is a send that
	 * reached nobody, and says so.
	 *
	 * <p>Since {@code send} began writing a row for every intended recipient, the second branch
	 * describes only messages sent by the code that did not — it cannot be produced by a send made
	 * today. It is kept for those, and because a count is never the whole of a fact: which of two
	 * sentences is true here depends on the status, whatever else changes underneath.
	 *
	 * <p>PENDING alone counts as in flight, deliberately, and SENT does not. SENT means a provider
	 * has taken it; for email no delivery receipt is ever coming, so treating SENT as unfinished
	 * would leave those messages saying <i>"check back shortly"</i> for ever and make the delivered
	 * case unreachable.
	 */
	private ApplicationException nothingToRetry(UUID id, CommunicationView c) {
		Map<String, Object> counts = jdbc.queryForMap("""
				SELECT count(*) AS recipients,
					count(*) FILTER (WHERE n.status = 'PENDING') AS still_pending
				FROM communication_recipients r
				LEFT JOIN notifications n ON n.id = r.notification_id
				WHERE r.communication_id = ?
				""", id);
		long recipients = ((Number) counts.get("recipients")).longValue();
		long stillPending = ((Number) counts.get("still_pending")).longValue();

		Map<String, Object> detail = Map.of("communicationId", id, "status", c.status().name(),
				"recipients", recipients, "stillPending", stillPending);

		if (recipients == 0) {
			return new ApplicationException(
					c.status() == CommunicationStatus.DRAFT
							? ErrorCode.COMMUNICATION_NOT_SENT
							: ErrorCode.COMMUNICATION_REACHED_NOBODY,
					detail);
		}
		if (stillPending > 0) {
			return new ApplicationException(ErrorCode.NOTHING_FAILED_YET, detail);
		}
		return new ApplicationException(ErrorCode.NOTHING_FAILED_TO_RETRY, detail);
	}

	/**
	 * The people whose copy of this message failed — by name, from the notification that carries the
	 * outcome, rather than from anything copied into the recipient row and left to drift.
	 *
	 * <p>A recipient naming <b>no</b> notification is one of them (T-094). It means the relay was
	 * asked for their copy and gave us nothing back, so there is no notification to read a status
	 * from and never will be: a failure, recorded by the send rather than reported by a provider.
	 * This has to be a LEFT JOIN to see them at all — an inner join from a row whose
	 * {@code notification_id} is null returns nothing, which is how such a devotee stayed both
	 * invisible and unreachable for as long as the retry has existed.
	 *
	 * <p>Nothing else can produce a null here. The column's {@code ON DELETE SET NULL} could, in
	 * principle, but nothing in the application ever deletes a notification, so a null is not an
	 * arrived copy whose record was tidied away — it is a copy that was never made.
	 */
	private List<UUID> failedRecipients(UUID id) {
		return jdbc.queryForList("""
				SELECT r.recipient_user_id
				FROM communication_recipients r
				LEFT JOIN notifications n ON n.id = r.notification_id
				WHERE r.communication_id = ?
				  AND (n.status = 'FAILED' OR r.notification_id IS NULL)
				ORDER BY r.created_at
				""", UUID.class, id);
	}

	/**
	 * Everyone this may go to: the temple's devotees, minus those who have declined this category.
	 *
	 * <p>Staff are deliberately not on it. A newsletter is written for the community that comes to
	 * the temple, and the cooks already hear everything in the kitchen; if a temple wants its staff
	 * included, that is a decision to make out loud rather than a side effect of them holding an
	 * account.
	 */
	@Transactional(readOnly = true)
	public List<UUID> audienceFor(CommunicationView c) {
		return jdbc.queryForList("""
				SELECT u.id FROM users u
				WHERE u.role = 'VOLUNTEER'
				  AND u.status = 'ACTIVE'
				  AND u.contact_consent_at IS NOT NULL
				  AND u.optional_communications_opt_out_at IS NULL
				  AND NOT EXISTS (
					  SELECT 1 FROM communication_preferences p
					  WHERE p.user_id = u.id AND p.category = ?)
				ORDER BY u.full_name
				""", UUID.class, c.category().name());
	}

	/** How many people a draft would reach right now, for the confirmation before sending. */
	@Transactional(readOnly = true)
	public int audienceSize(UUID id) {
		return audienceFor(find(id).orElseThrow(() -> notFound(id))).size();
	}

	/**
	 * Hands one person's copy to the relay and points their recipient row at it.
	 *
	 * <p>It no longer <em>creates</em> that row (T-094): {@link #recordSend} has already written one
	 * for every intended recipient, so what happens here is the second half of the same sentence —
	 * naming the copy that stands. When the relay throws there is no copy to name and the row is left
	 * exactly as the send wrote it, saying that this devotee was meant to have one and has not had
	 * it. That is the durable, findable record the old arrangement lacked, and it is written by the
	 * step that knows the audience rather than by the step that may not survive.
	 *
	 * <p>An UPDATE rather than the {@code INSERT … ON CONFLICT DO UPDATE} this used to be, and the
	 * reasoning behind that clause (B6/T-015) is unchanged and now simply says itself: one row per
	 * person per message, whose job is to name <b>the attempt that stands</b>. A retry produces a new
	 * notification and the row must follow it, or the screen goes on saying <i>Failed</i> beside
	 * somebody who has just been written to and the retry reports a success it did not have.
	 *
	 * <p>Guarded on the notification being real, so a failed <em>retry</em> leaves the failure it was
	 * retrying in place rather than erasing which copy it was: null already means failed, and the old
	 * one still says which channel it went out on and when.
	 */
	private boolean queueFor(CommunicationView c, UUID userId, boolean isTest) {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new IllegalStateException("A communication is sent within a tenant context"));

		Map<String, Object> params = new HashMap<>();
		params.put("communicationId", c.id().toString());
		params.put("subject", c.subject());
		params.put("temple", templeName());
		params.put("link", webUrl(c.publicToken()));
		params.put("intro", c.whatsappSummary() == null ? "" : c.whatsappSummary());
		params.put("unsubscribeUrl", webBaseUrl + "/unsubscribe?token="
				+ tokens.issue(tenantId, userId, c.category()));

		NotificationTemplate template = c.channel() == CommunicationChannel.WHATSAPP
				? NotificationTemplate.TEMPLE_ANNOUNCEMENT
				: NotificationTemplate.TEMPLE_COMMUNICATION;
		NotificationChannel channel = c.channel() == CommunicationChannel.WHATSAPP
				? NotificationChannel.WHATSAPP
				: NotificationChannel.EMAIL;

		UUID notificationId;
		try {
			// A test copy carries the author's own category gate as any message would — if the person
			// sending the newsletter has opted out of newsletters, they should see that happen.
			notificationId = notifications.notify(NotificationRecipient.user(userId), template, params,
					channel, c.category());
		} catch (RuntimeException e) {
			// One unreachable devotee is not a reason to abandon the other three hundred and ninety —
			// which is true of a send, now that the send is not one transaction around all of them
			// (T-094), and was not true before. In a retry it still is not: notify() is transactional,
			// so a failure inside it marks this method's caller rollback-only and the whole retry
			// unwinds however politely this is logged. That is the right answer for a retry and the
			// wrong one for a send; see retryFailed() for why the two differ.
			log.warn("Could not queue communication {} for {}: {}", c.id(), userId, e.toString());
			return false;
		}

		if (!isTest) {
			int rows = jdbc.update("""
					UPDATE communication_recipients SET notification_id = ?
					WHERE communication_id = ? AND recipient_user_id = ?
					""", notificationId, c.id(), userId);
			if (rows != 1) {
				// Never expected: a send writes the row first and a retry only ever names rows it has
				// just read. Said out loud rather than swallowed, because a copy that reached somebody
				// while nothing recorded it is precisely the silence this task existed to end.
				log.warn("Communication {} queued copy {} for {} but matched {} recipient rows",
						c.id(), notificationId, userId, rows);
			}
		}
		return true;
	}

	// ---- The sent log ---------------------------------------------------

	/**
	 * Who it went to and what became of each one — the answer to "did it actually go?".
	 *
	 * <p>A recipient naming no notification reads as FAILED (T-094), which is what it is: the relay
	 * was asked for their copy and gave nothing back, so no status is coming. It read as UNKNOWN,
	 * which this screen renders as <i>Queued</i> — a devotee nobody had written to appearing on the
	 * list as one whose letter was on its way. The column is nullable only because of an
	 * {@code ON DELETE SET NULL} nothing in the application ever triggers, so there is no second
	 * meaning to protect.
	 */
	@Transactional(readOnly = true)
	public List<DeliveryView> deliveries(UUID id) {
		return jdbc.query("""
				SELECT u.full_name, n.status, n.final_channel, n.preferred_channel, n.suppressed_reason
				FROM communication_recipients r
				JOIN users u ON u.id = r.recipient_user_id
				LEFT JOIN notifications n ON n.id = r.notification_id
				WHERE r.communication_id = ?
				ORDER BY u.full_name
				""", (rs, n) -> new DeliveryView(
						rs.getString("full_name"),
						rs.getString("status") == null ? "FAILED" : rs.getString("status"),
						rs.getString("final_channel") != null
								? rs.getString("final_channel") : rs.getString("preferred_channel"),
						rs.getString("suppressed_reason")), id);
	}

	/** The public web copy, by its unguessable name. Sent communications only. */
	@Transactional(readOnly = true)
	public Optional<PublicCommunicationView> publicCopy(String token) {
		return jdbc.query("""
				SELECT c.subject, c.body_html, c.sent_at, t.name AS temple_name
				FROM communications c JOIN tenants t ON t.id = c.tenant_id
				WHERE c.public_token = ? AND c.status = 'SENT'
				""", (rs, n) -> new PublicCommunicationView(
						rs.getString("temple_name"), rs.getString("subject"),
						rs.getString("body_html"),
						rs.getObject("sent_at", OffsetDateTime.class) == null
								? null : rs.getObject("sent_at", OffsetDateTime.class).toInstant()),
				token).stream().findFirst();
	}

	// ---------------------------------------------------------------------

	private String webUrl(String publicToken) {
		return webBaseUrl + "/c/" + publicToken;
	}

	private static void requireComposable(CommunicationCategory category) {
		if (category == null || !category.isOptional()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "category", "reason",
							"a message somebody wrote is never operational — that category is for "
									+ "what the system sends of its own accord"));
		}
	}

	private static void requireSomethingToSay(SaveCommunicationRequest request, String text) {
		if (request.channel() == CommunicationChannel.EMAIL && text.isBlank()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "bodyHtml", "reason", "an empty letter is not a letter"));
		}
		if (request.channel() == CommunicationChannel.WHATSAPP
				&& trimToNull(request.whatsappSummary()) == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "whatsappSummary", "reason",
							"WhatsApp carries one line and a link, so the line has to say something"));
		}
	}

	private static void requireDraft(CommunicationView c) {
		if (c.status() != CommunicationStatus.DRAFT) {
			throw new ApplicationException(ErrorCode.COMMUNICATION_ALREADY_SENT,
					Map.of("communicationId", c.id()));
		}
	}

	private String templeName() {
		try {
			return jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
		} catch (RuntimeException e) {
			return "the temple";
		}
	}

	private Optional<CommunicationView> find(UUID id) {
		return jdbc.query(SELECT + " WHERE c.id = ?", MAPPER, id).stream().findFirst();
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("communicationId", id));
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static final String SELECT = """
			SELECT c.id, c.category, c.channel, c.subject, c.body_html, c.body_text,
			       c.whatsapp_summary, c.status, c.audience_count, c.public_token,
			       c.created_at, c.sent_at, u.full_name AS author
			FROM communications c LEFT JOIN users u ON u.id = c.created_by
			""";

	private static final RowMapper<CommunicationView> MAPPER = (rs, n) -> new CommunicationView(
			rs.getObject("id", UUID.class),
			CommunicationCategory.valueOf(rs.getString("category")),
			CommunicationChannel.valueOf(rs.getString("channel")),
			rs.getString("subject"),
			rs.getString("body_html"),
			rs.getString("body_text"),
			rs.getString("whatsapp_summary"),
			CommunicationStatus.valueOf(rs.getString("status")),
			(Integer) rs.getObject("audience_count"),
			rs.getString("public_token"),
			rs.getString("author"),
			toInstant(rs.getObject("created_at", OffsetDateTime.class)),
			toInstant(rs.getObject("sent_at", OffsetDateTime.class)));

	private static java.time.Instant toInstant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}

	/**
	 * What the recorded half of a send hands to the half that hands out copies (T-094).
	 *
	 * <p>The audience travels with it rather than being recomputed after the commit: it is the list
	 * {@code audience_count} and the recipient rows were both written from, and a devotee who
	 * consents, or stops consenting, in the seconds between the two steps must not change who this
	 * message is on its way to.
	 */
	private record Sent(CommunicationView communication, List<UUID> audience) {
	}

	/** Everything a temple's own screen shows about one communication. */
	public record SendResultView(int audience, int queued) {
	}

	/** How many people the message was re-queued for — never how many failed, which is not the same. */
	public record RetryResultView(int retried) {
	}

	/** The preview: the email exactly as framed, the WhatsApp line exactly as Meta would carry it. */
	public record PreviewView(String subject, String emailHtml, String whatsappText, String plainText) {
	}

	public record DeliveryView(
			String recipientName, String status, String channel, String suppressedReason) {
	}

	public record PublicCommunicationView(
			String templeName, String subject, String bodyHtml, java.time.Instant sentAt) {
	}
}
