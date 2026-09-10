package org.iskcon.kms.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deliberate, named exception: the one thing here that is asserted about the <em>text</em> of SQL.
 *
 * <p><b>Read this before copying anything out of it.</b> Every concurrency brief in waves 10–12 said
 * the opposite — <i>make no assertion about the text of the SQL</i> — and that rule is right, because
 * asserting that a string contains {@code FOR UPDATE} proves nothing about what the database does and
 * passes just as happily against a lock taken in the wrong place. Nothing here weakens that rule.
 * This class exists because of the single case the rule does not cover, and it is not a licence to
 * check SQL by eye anywhere else.
 *
 * <p><b>The case.</b> What makes "a letter is sent once" true is the predicate on {@code recordSend}'s
 * own UPDATE — {@code WHERE id = ? AND status = 'DRAFT'} — a guard in the same statement as the state
 * change (T-102). It is guarded twice over: {@code lockCommunication} takes the row first, so a second
 * sender blocks, re-reads and is refused by {@code requireDraft} long before the UPDATE runs. That
 * belt-and-braces is exactly the problem. <b>With the lock in front of it, no request can make the
 * compare-and-swap fire</b>, so no test driving the endpoint can be sensitive to the predicate's
 * deletion. T-102's own builder proved it and reported it: delete the predicate, leave the lock, and
 * all fourteen tests in {@link CommunicationRetryIT} stay green.
 *
 * <p><b>Why the statement-level test does not close it either.</b>
 * {@code CommunicationRetryIT.theSendStatementItselfRefusesASecondTransition} races two real
 * unprivileged connections and proves that a statement with that WHERE clause refuses the second
 * transition. But it runs <em>its own copy</em> of the SQL. It proves the mechanism works; it cannot
 * prove the service still contains it. So a green suite was, until this class, consistent with the
 * predicate being present <b>and</b> with its having been deleted — the exact state the negative-control
 * rule exists to make impossible, and the one place in this codebase where no behavioural test can
 * reach.
 *
 * <p><b>Why not a test-only seam instead.</b> The alternative on the table was a seam in
 * {@code recordSend} letting a test reach the UPDATE without the lock, which would make the refusal
 * behaviourally reachable. It was rejected, and not only because T-102's builder rightly declined to
 * change production shape on its own authority. A seam would put a lock-free path into the one method
 * where a lock-free path writes to four hundred devotees twice — a permanent hazard in production code,
 * bought to re-prove something already proved twice: that the clause refuses (the statement-level test,
 * against two real connections) and that the endpoint refuses a second admin (the end-to-end test).
 * The only fact actually missing was <b>whether the shipped method still carries the clause</b>, and
 * that is a property of the source, not of any run. A source assertion is the honest instrument for it,
 * and the cheap one.
 *
 * <p><b>What this asserts, and why not a grep for {@code status = 'DRAFT'}.</b> A literal search would
 * break the first time somebody rewraps the text block, and — worse — would pass against a predicate
 * that had been moved to a statement where it guards nothing. So the statement is located first, and
 * only then read:
 *
 * <ol>
 *   <li>exactly one statement in {@code CommunicationService} moves a communication to {@code SENT},
 *       and it stands inside {@code recordSend} — a second, unguarded transition added elsewhere fails
 *       here rather than being found by a devotee receiving the letter twice;</li>
 *   <li>that statement's WHERE clause, with whitespace collapsed, is <b>the same clause</b> that
 *       {@code CommunicationRetryIT.transitionToSent} runs — the clause whose refusal is proved against
 *       two real connections. This is the assertion worth having: it ties the proof to the shipped
 *       statement, which is the tie that was previously made by review alone. Reformatting either side
 *       cannot break it; deleting, weakening or relocating the predicate cannot survive it.</li>
 * </ol>
 *
 * <p>And the case the tie alone would miss — somebody editing the predicate out of <em>both</em> — is
 * caught from the other side: {@code transitionToSent} without its predicate makes the statement-level
 * test go red, because the second transition would then update a row. The two tests close each other's
 * gap, which is why neither is redundant. A coarse net for that case is asserted here as well, since it
 * costs one line and makes this class readable on its own.
 *
 * <p>It is a plain JUnit test on purpose: no Spring context, no Testcontainers, no Docker. It asserts a
 * property of the tree, not of a run, and it should cost nothing to run and be impossible to mistake
 * for an integration test. Its name says <i>source</i> for the same reason.
 *
 * <p>Precedent for the shape, and its limits: {@code TempleClockTest} and the frontend's
 * {@code design-system.test.ts} both assert on source, and both do so for the same reason — the drift
 * they guard is a future edit, not a wrong answer today.
 */
class CommunicationSendGuardSourceTest {

	/**
	 * The statement that records a send, wherever it has been wrapped. Anchored on the transition
	 * itself ({@code SET status = 'SENT'}) and closed on the text block's terminator, so that
	 * re-indenting or re-wrapping the SQL changes nothing here.
	 */
	private static final Pattern SENT_TRANSITION = Pattern.compile(
			"UPDATE\\s+communications\\s+SET\\s+status\\s*=\\s*'SENT'.*?\"\"\"", Pattern.DOTALL);

	/**
	 * Where a class member starts: one tab in, which every method, field and javadoc block in these two
	 * files is and no line inside a method body is. Used only to say <em>which method</em> a statement
	 * was found in.
	 */
	private static final Pattern MEMBER_START =
			Pattern.compile("(?m)^\t(?:/\\*\\*|@|private |public |protected |static )");

	private static final Pattern WHERE = Pattern.compile("(?i)\\bWHERE\\b");

	private static final String SERVICE = "main/java/org/iskcon/kms/communication/CommunicationService.java";
	private static final String PROOF = "test/java/org/iskcon/kms/communication/CommunicationRetryIT.java";

	@Test
	@DisplayName("SOURCE ASSERTION, not behaviour: recordSend's UPDATE still carries the clause that "
			+ "was proved against two connections")
	void theShippedStatementCarriesTheClauseThatWasProved() throws IOException {
		String service = read(SERVICE);
		String proof = read(PROOF);

		// The service's transition, and the one the statement-level test races. Scoped to a method
		// each, because CommunicationRetryIT deliberately contains a *second* update to SENT — a
		// fixture at the bottom that moves a letter to sent with no predicate at all, on purpose. A
		// grep across either file would have read the wrong one of those two and said nothing useful.
		String shipped = theOneStatementIn(service, SERVICE, "private Sent recordSend(");
		String proved = theOneStatementIn(proof, PROOF, "private int transitionToSent(");

		String shippedClause = whereClauseOf(shipped, SERVICE);
		String provedClause = whereClauseOf(proved, PROOF);

		assertThat(shippedClause)
				.as("""
						recordSend's UPDATE no longer carries the WHERE clause that \
						CommunicationRetryIT.theSendStatementItselfRefusesASecondTransition proves \
						refuses a second transition. That test runs its own copy of this statement, so \
						it stays green either way — this assertion is the only thing in the suite that \
						notices. If the change was deliberate, change both, and read the statement-level \
						test's proof again before you believe the new clause.""")
				.isEqualTo(provedClause);

		// The coarse net, for the case where somebody edits both sides together and the tie above
		// therefore holds. The statement-level test catches that from the other side — its second
		// transition would update a row — but this says in one line what the clause has to be.
		assertThat(shippedClause)
				.as("the clause no longer constrains status to DRAFT, so the statement that records a "
						+ "send has stopped being what makes a letter send once")
				.matches("(?i).*\\bstatus\\s*=\\s*'DRAFT'.*");
	}

	@Test
	@DisplayName("SOURCE ASSERTION, not behaviour: only one statement in the service moves a letter to "
			+ "SENT")
	void nothingElseInTheServiceMovesALetterToSent() throws IOException {
		String service = read(SERVICE);

		List<String> transitions = statementsIn(service);

		assertThat(transitions)
				.as("""
						a second statement writing status = 'SENT' has appeared in CommunicationService. \
						The guard above is a property of one statement, so a second one is a second \
						answer to "is this letter still a draft" — and the one that is unguarded is the \
						one that sends the letter twice. If a second transition is genuinely wanted, it \
						needs its own predicate and this test needs rewriting to say so.""")
				.hasSize(1);
	}

	/**
	 * The single transition inside the named method, or a failure that says which of the two ways it
	 * went wrong.
	 *
	 * <p>Separating "not found" from "found in the wrong place" matters: the first usually means the
	 * SQL stopped being a text block, and the second means the transition has moved out from behind
	 * {@code lockCommunication}. They call for quite different reading.
	 */
	private static String theOneStatementIn(String source, String file, String declaration) {
		int start = source.indexOf(declaration);
		if (start < 0) {
			return fail("%s no longer declares `%s`, so this test cannot find the statement it is "
					+ "about. If the method was renamed, rename it here too — do not delete the "
					+ "assertion.", file, declaration);
		}
		Matcher nextMember = MEMBER_START.matcher(source).region(start + declaration.length(),
				source.length());
		int end = nextMember.find() ? nextMember.start() : source.length();

		List<String> found = statementsIn(source.substring(start, end));
		if (found.size() != 1) {
			return fail("expected exactly one statement writing status = 'SENT' inside `%s` in %s, "
					+ "found %d. Either the SQL is no longer a text block, or the transition has moved "
					+ "out of the method that locks the row first.", declaration, file, found.size());
		}
		return found.get(0);
	}

	/** Every SENT transition in the given source, whitespace collapsed so wrapping cannot matter. */
	private static List<String> statementsIn(String source) {
		List<String> found = new ArrayList<>();
		Matcher matcher = SENT_TRANSITION.matcher(source);
		while (matcher.find()) {
			String statement = matcher.group();
			found.add(normalise(statement.substring(0, statement.length() - "\"\"\"".length())));
		}
		return found;
	}

	/**
	 * What the statement is guarded by: everything after its {@code WHERE}.
	 *
	 * <p>Refuses to guess if there is more than one {@code WHERE} — a subquery would make "the WHERE
	 * clause" ambiguous, and a test that quietly picked one of them would be worse than no test. If the
	 * statement ever grows one, this extraction has to be rewritten by somebody who has thought about
	 * which clause carries the guard.
	 */
	private static String whereClauseOf(String statement, String file) {
		Matcher matcher = WHERE.matcher(statement);
		if (!matcher.find()) {
			return fail("the statement recording a send in %s has no WHERE clause at all: `%s`",
					file, statement);
		}
		int clause = matcher.end();
		if (matcher.find()) {
			return fail("the statement recording a send in %s now has more than one WHERE, so which "
					+ "one carries the guard is no longer obvious: `%s`", file, statement);
		}
		return statement.substring(clause).strip();
	}

	private static String normalise(String sql) {
		return sql.replaceAll("\\s+", " ").strip();
	}

	/**
	 * Reads a source file from the tree.
	 *
	 * <p>Gradle runs tests with {@code backend/} as the working directory; the fallback is for a runner
	 * started at the repository root, which {@code BaseQuantityIT} handles the same way.
	 */
	private static String read(String relative) throws IOException {
		Path fromModule = Path.of("src", relative);
		Path path = Files.exists(fromModule) ? fromModule : Path.of("backend", "src", relative);
		assertThat(path)
				.as("this test reads the source it is asserting about, and cannot find it — check the "
						+ "working directory before changing the path")
				.exists();
		return Files.readString(path);
	}
}
