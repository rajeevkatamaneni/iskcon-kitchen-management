package org.iskcon.kms.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Reads a phone number the way a person types one, and hands validation the number itself (T-157).
 *
 * <p>{@code KMS-400003} tells somebody to write their number "for example +91 98765 43210", and
 * until this class existed that example was refused: every E.164 field carries
 * {@code @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$")}, which has no room for a space. The message was
 * right about how people write a number; the check was wrong to hold the spacing against them.
 *
 * <p><strong>What is removed, and what deliberately is not.</strong> Only characters that separate
 * digits and never stand for one:
 *
 * <ul>
 *   <li>whitespace of every kind, including the non-breaking space autofill and a paste from a
 *       document bring along ({@code \s} and {@code \p{Z}});
 *   <li>hyphens and the other dashes, because a phone's keyboard or a word processor turns a typed
 *       hyphen into an en dash without asking ({@code \p{Pd}});
 *   <li>invisible format characters: the zero-width space and joiners, the byte-order mark, and the
 *       left-to-right marks Android's contacts app wraps around a number when it is copied
 *       ({@code \p{Cf}}).
 * </ul>
 *
 * <p>Brackets are not removed, and that is the one choice here that could reasonably have gone the
 * other way. A bracket in a phone number usually carries a trunk prefix that must be <em>dropped</em>
 * when the country code is present — {@code +91 (0) 80 2345 6789} — so deleting the brackets and
 * keeping what was inside them would turn it into {@code +910802345…}, which passes the pattern and
 * rings nobody. Refusing it costs the person one retype; accepting it would store a wrong number that
 * looks right. Dots are not removed either: nobody writing an Indian number uses them, and a rule for
 * a case nobody can name is a rule nobody can check.
 *
 * <p>Nothing else is removed, and that is the point rather than a gap. The screen that provisions a
 * temple used to keep "the plus and the digits" and throw away everything else, so a mistyped
 * {@code +91 98765 4321X} lost its {@code X} and arrived as a well-formed eleven-digit number — a
 * typo silently corrected into somebody else's phone. Here the letter stays, the pattern fails, and
 * the reader is told {@code KMS-400003}.
 *
 * <p><strong>Why a deserializer, on each field, rather than a compact constructor in each record.</strong>
 * Both would run before Bean Validation sees the value, which is the property that matters. The
 * deserializer was chosen for three reasons. It sits on the field beside the {@code @Pattern} it
 * serves, so whoever reads the rule reads the leniency in the same place. It is one implementation
 * rather than seven call sites into a helper that would have to live somewhere a request record can
 * import. And it acts only where a person's typing arrives — the JSON body — leaving the records'
 * Java constructors meaning exactly what they say, which matters on a record like
 * {@code UpdateStaffRequest} where null and blank carry different instructions. The cost, stated
 * plainly: a new phone field has to remember the annotation, exactly as it has to remember the
 * {@code @Pattern}. {@code PhoneNormalisationIT} names all nine fields so that a tenth missing it is
 * at least noticed by whoever adds it there.
 *
 * <p><strong>Blank stays blank.</strong> A box of spaces becomes an empty string, never null: an empty
 * string still fails {@code @NotBlank} where a number is required, and still fails the pattern where
 * it is optional, so "Enter a phone number." and the vendor form's refusal of a typed space are both
 * unchanged. Turning it into null would have made a vendor's box of spaces mean "no number", which
 * is a decision that belongs to the screen, and the screen already makes it.
 *
 * <p>{@code frontend/lib/phone.ts} removes the same set of characters, so a box that enables its
 * button is a box the server accepts. The two lists are kept in step by hand, and by tests on each
 * side that use the same characters.
 */
public final class PhoneNumberDeserializer extends StdScalarDeserializer<String> {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s\\p{Z}\\p{Pd}\\p{Cf}]");

	public PhoneNumberDeserializer() {
		super(String.class);
	}

	@Override
	public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		// Through Jackson's own String reader first, so whatever it would coerce to a string — and
		// whatever it would refuse — is handled exactly as on any other String field. JSON null never
		// reaches here: Jackson answers it with getNullValue(), which is null.
		return normalise(StringDeserializer.instance.deserialize(parser, context));
	}

	/** The number with its separators taken out, or null for null. Nothing else is changed. */
	static String normalise(String typed) {
		return typed == null ? null : SEPARATORS.matcher(typed).replaceAll("");
	}
}
