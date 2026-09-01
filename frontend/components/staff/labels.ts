import type { EmploymentStatus, EmploymentType, JobTitleGroup, StaffProfileView, SystemAccess } from "@/lib/api";

/**
 * The words the staff screens use for the things the API sends back as enum names.
 *
 * <p>Kept in one module because there are five screens now rather than one — the register, hiring,
 * updating, terminating and the record — and a second copy of any of these lists would be free to
 * drift away from the first. That is exactly what happened to the payment modes before they were
 * pulled into `PayPanel`.
 */

export const GROUP_LABELS: Record<JobTitleGroup, string> = {
  ADMINISTRATION: "Administration",
  KITCHEN: "Kitchen",
  STORE: "Store",
  SUPPORT: "Support",
  OTHER: "Other",
};

export const ACCESS_LABELS: Record<SystemAccess, string> = {
  TEMPLE_ADMIN: "Temple admin",
  KITCHEN_MANAGER: "Kitchen manager",
  KITCHEN_STAFF: "Kitchen staff",
};

/** How an employment ended, in the temple's words rather than the database's. */
export const STATUS_LABELS: Record<EmploymentStatus, string> = {
  ACTIVE: "Active",
  RESIGNED: "Resigned",
  TERMINATED: "Dismissed",
  CONTRACT_ENDED: "Contract ended",
};

export const EMPLOYMENT_TYPES = [
  { value: "FULL_TIME", label: "Full-time" },
  { value: "PART_TIME", label: "Part-time" },
  { value: "CONTRACT", label: "Contract" },
] as const;

export function employmentTypeLabel(type: EmploymentType): string {
  return EMPLOYMENT_TYPES.find((t) => t.value === type)?.label ?? "";
}

/**
 * "2024-03-04" → "4 March 2024".
 *
 * <p>Not `longDate`, which leads with the weekday. Which day of the week somebody joined on is of no
 * use to anybody, and it is half the length of the line it sits in.
 */
export function dayMonthYear(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "long",
    year: "numeric",
  });
}

/**
 * The one line under the heading of a focus screen: whose record this is.
 *
 * <p>Name, job and the date that places them — the day they joined for somebody still employed, the
 * day they left for somebody who is not. Two devotees at one temple can share a name, so the job is
 * there to tell them apart, and terminating or paying the wrong person is not a mistake this app can
 * undo.
 */
export function whoLine(staff: StaffProfileView): string {
  const when =
    staff.employmentStatus === "ACTIVE"
      ? `joined ${dayMonthYear(staff.dateOfJoining)}`
      : staff.lastWorkingDay
        ? `left ${dayMonthYear(staff.lastWorkingDay)}`
        : STATUS_LABELS[staff.employmentStatus];
  return `${staff.fullName} · ${staff.jobTitleLabel} · ${when}`;
}
