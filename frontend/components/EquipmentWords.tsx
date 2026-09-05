"use client";

import { Badge } from "@/components/ds/Badge";
import type {
  EquipmentCondition,
  EquipmentSource,
  EquipmentView,
  ServiceIntervalUnit,
} from "@/lib/api";
import { dateWithYear, todayIso, wholeDaysBetween } from "@/lib/format";

/**
 * How the equipment register is said out loud, in one place — the list, the item page and the
 * register form all read from here.
 *
 * <p>Every value on an equipment row is stored in upper case with underscores, and a screen that
 * prints one straight out says NEEDS_REPAIR to a storekeeper. The maps below are the same answer
 * the donations ledger reached after its rows read CASH and BANK_TRANSFER: a stored value is not a
 * word, and there is exactly one place that turns it into one.
 */

export const CONDITION_LABEL: Record<EquipmentCondition, string> = {
  GOOD: "Good",
  NEEDS_REPAIR: "Needs repair",
  IN_REPAIR: "In repair",
  SCRAPPED: "Scrapped",
};

export const SOURCE_LABEL: Record<EquipmentSource, string> = {
  PURCHASED: "Purchased",
  DONATED: "Donated",
};

/** The interval units, in the order a service contract offers them (E3-S10 D3). */
export const INTERVAL_UNITS: readonly ServiceIntervalUnit[] = ["DAYS", "WEEKS", "MONTHS", "YEARS"];

/**
 * How each unit is said, singular and plural.
 *
 * <p>Named `INTERVAL_WORDS` rather than anything ending in `UNIT_LABEL`, which is the name the one
 * food-unit vocabulary in `lib/format` holds and which `design-system.test.ts` guards by name. A
 * service interval is a different vocabulary from what an ingredient is measured in — nothing here
 * converts to a kilogram — and borrowing the guarded name would either trip that check or, worse,
 * teach the next person that there are two unit maps after all.
 */
const INTERVAL_WORDS: Record<ServiceIntervalUnit, { one: string; many: string }> = {
  DAYS: { one: "day", many: "days" },
  WEEKS: { one: "week", many: "weeks" },
  MONTHS: { one: "month", many: "months" },
  YEARS: { one: "year", many: "years" },
};

/** A unit on its own, for the picker beside the count. Plural, because the count usually is. */
export function intervalUnitLabel(unit: ServiceIntervalUnit): string {
  return INTERVAL_WORDS[unit].many;
}

/**
 * The schedule in the words it was entered in — "Every 6 months", never "Every 180 days".
 *
 * <p>The count and the unit both come back from the server for exactly this: the days are what the
 * arithmetic runs on, and the unit is what makes the round trip lossless.
 */
export function intervalWords(
  count: number | null,
  unit: ServiceIntervalUnit | null
): string | null {
  if (count == null || unit == null) return null;
  const words = INTERVAL_WORDS[unit];
  return `Every ${count} ${count === 1 ? words.one : words.many}`;
}

/**
 * The condition, as a marker rather than as a word in a sentence.
 *
 * <p>Only the two that ask something of somebody are coloured. Good is the ordinary case and would
 * be a wall of green down the column, and scrapped is the end of the story rather than a problem —
 * colouring either would spend the reader's attention where there is nothing to spend it on, which
 * is what makes the amber one stop working.
 */
export function ConditionBadge({ condition }: { condition: EquipmentCondition }) {
  const tone = condition === "NEEDS_REPAIR" ? "warning" : condition === "IN_REPAIR" ? "accent" : "neutral";
  return <Badge tone={tone}>{CONDITION_LABEL[condition]}</Badge>;
}

/**
 * Where a machine stands against its next service, in words as well as colour (E3-S11 D3).
 *
 * <p>*Overdue by 12 days*, not a red date; *Due in 9 days*, not an amber one. Colour alone fails
 * anybody who cannot see the difference between the two, and a bare date makes the reader do the
 * arithmetic this column exists to do for them.
 *
 * <p>The date itself stays underneath, with what it was counted from where that is not a service —
 * *from the purchase, never serviced* — because a derived date read as a service that happened is
 * the one misreading this feature cannot afford (E3-S10 D4).
 */
export function ServiceState({ item }: { item: EquipmentView }) {
  if (item.serviceStatus === "NOT_SCHEDULED" || item.nextServiceOn == null) {
    // A phrase, not a blank cell. Nobody has decided about this machine, which is a different fact
    // from it being up to date, and an empty cell says neither.
    return <span className="text-sm text-ink-muted">Not scheduled</span>;
  }

  const days = wholeDaysBetween(todayIso(), item.nextServiceOn);
  const overdue = item.serviceStatus === "OVERDUE" || days < 0;
  const late = Math.abs(days);
  const noun = late === 1 ? "day" : "days";

  const phrase = overdue
    ? `Overdue by ${late} ${noun}`
    : days === 0
      ? "Due today"
      : `Due in ${late} ${noun}`;

  return (
    <div>
      {item.serviceStatus === "OK" ? (
        <span className="text-sm">{dateWithYear(item.nextServiceOn)}</span>
      ) : (
        <Badge tone={overdue ? "danger" : "warning"}>{phrase}</Badge>
      )}
      <span className="mt-1 block text-xs text-ink-muted">
        {item.serviceStatus === "OK" ? null : `${dateWithYear(item.nextServiceOn)} · `}
        {item.nextServiceBasis === "PURCHASED"
          ? "from the purchase, never serviced"
          : "from the last service"}
      </span>
    </div>
  );
}
