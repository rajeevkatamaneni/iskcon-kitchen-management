package org.iskcon.kms.meal;

/**
 * What repeating an event forward actually did (E4-S15 D8).
 *
 * <p>What it makes is <strong>copies, not a series.</strong> Each one is a plan in its own right and
 * can be edited or cancelled without touching the others, and no screen ever has to ask *this one or
 * all of them?* A true recurrence rule with per-occurrence exceptions was considered and deferred:
 * it is a feature that grows teeth, and the temple's actual problem is not wanting to type the same
 * Saturday reading fifty-two times.
 *
 * @param copied        how many preparations were written. Six weekly copies of a two-dish event is
 *                      twelve.
 * @param weeksCopied   how many of the requested weeks landed.
 * @param refusedOnFast weeks skipped because the recipe does not suit an Ekadashi falling there. The
 *                      copy is refused rather than acknowledged on the planner's behalf — nobody is
 *                      looking at that meal to say it is all right.
 */
public record RepeatEventResult(int copied, int weeksCopied, int refusedOnFast) {
}
