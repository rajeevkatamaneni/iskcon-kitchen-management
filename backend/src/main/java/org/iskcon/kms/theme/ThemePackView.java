package org.iskcon.kms.theme;

import java.util.Map;
import java.util.UUID;

/**
 * One theme pack, as both the catalogue and the signed-in session see it.
 *
 * <p>The palette travels as the twenty-two roles of {@code docs/DESIGN_SYSTEM.md} §2 mapped to
 * {@code #RRGGBB}. It is deliberately not converted to the {@code r g b} channel triples the
 * stylesheet actually needs: that conversion is a detail of how Tailwind's opacity modifier
 * compiles, it belongs on the side that owns the stylesheet, and a hex string is the form a person
 * reads, writes and pastes into a contrast checker.
 *
 * @param family one of {@code VIBRANT}, {@code BALANCED}, {@code MUTED} — how loud the pack is,
 *     which is the axis a temple chooses along before it looks at any individual colour
 */
public record ThemePackView(
		UUID id,
		String slug,
		String name,
		String family,
		String description,
		Map<String, String> palette) {
}
