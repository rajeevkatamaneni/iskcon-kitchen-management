import type {
  BanCategoryOption,
  EmploymentBanView,
  FormerStaffView,
  JobTitleOption,
  Kitchen,
  PreviousEmploymentView,
  StaffDocumentView,
  StaffKitchenCheckView,
  StaffRecordView,
  StaffAdvanceView,
  StaffPaymentView,
  StaffPayView,
  StaffProfileView,
} from "@/lib/api";

/**
 * The records the staff screens are tested against.
 *
 * <p>Shared because there are six of those screens now — the register, hiring, updating,
 * terminating, the record and pay — and they are all about the same person. A fixture copied into
 * each file drifts, and a test that quietly stops describing the same Gopal Das as its neighbour is
 * worse than no fixture at all.
 */

export function member(o: Partial<StaffProfileView> = {}): StaffProfileView {
  return {
    id: "s1",
    userId: "u1",
    fullName: "Gopal Das",
    phone: "+919876500001",
    email: "gopal@example.com",
    jobTitle: "HEAD_COOK",
    jobTitleOther: null,
    jobTitleLabel: "Head Cook",
    employmentType: "FULL_TIME",
    dateOfJoining: "2026-02-01",
    dateOfBirth: null,
    address: null,
    emergencyContactName: null,
    emergencyContactRelationship: null,
    emergencyContactPhone: null,
    panLast4: null,
    systemAccess: "KITCHEN_STAFF",
    employmentStatus: "ACTIVE",
    lastWorkingDay: null,
    endReason: null,
    kitchenId: "k1",
    kitchenName: "Main kitchen",
    kitchenNeedsCheck: false,
    createdAt: "2026-02-01T00:00:00Z",
    ...o,
  };
}

/**
 * One person's whole record, as `GET /api/v1/staff/members/{id}` answers it (T-428).
 *
 * <p>What the four screens about one person read since the register stopped being fetched whole and
 * filtered in the browser. Defaults to Gopal Das with no documents and no past jobs, which is every
 * record a temple has on the day this ships.
 */
export function record(
  profile: StaffProfileView = member(),
  o: Partial<Omit<StaffRecordView, "profile">> = {},
): StaffRecordView {
  return { profile, banned: false, documents: [], previousEmployment: [], ...o };
}

/** A file on a record: a photograph by default, because that is the one that has a place to show. */
export function document(o: Partial<StaffDocumentView> = {}): StaffDocumentView {
  return {
    id: "d1",
    kind: "PHOTO",
    contentType: "image/jpeg",
    sizeBytes: 48_000,
    originalName: "gopal.jpg",
    uploadedAt: "2026-09-20T09:00:00Z",
    ...o,
  };
}

/** One job somebody held before this temple. Everything filled in; most real rows will not be. */
export function previousJob(o: Partial<PreviousEmploymentView> = {}): PreviousEmploymentView {
  return {
    id: "pe1",
    employer: "Adyar Ananda Bhavan",
    theirTitle: "Tandoor Assistant",
    managerName: "Suresh Kumar",
    managerPhone: "+919845012345",
    fromDate: "2019-03-01",
    toDate: "2023-07-31",
    reasonForLeaving: "The branch closed.",
    ...o,
  };
}

/** Somebody who has left, with or without a record standing against them. */
export function former(o: Partial<StaffProfileView> = {}, banned = false): FormerStaffView {
  return {
    profile: member({
      id: "s2",
      fullName: "Yamuna Devi Dasi",
      employmentStatus: "RESIGNED",
      lastWorkingDay: "2026-06-30",
      endReason: "Moved to Mayapur",
      ...o,
    }),
    banned,
  };
}

export function payment(o: Partial<StaffPaymentView> = {}): StaffPaymentView {
  return {
    id: "p1",
    paidOn: "2026-07-31",
    gross: 18000,
    deducted: 0,
    net: 18000,
    mode: "CHEQUE",
    modeLabel: "Cheque",
    reference: "114523",
    purpose: "SALARY",
    purposeLabel: "Salary",
    note: null,
    recordedByName: "Temple Admin",
    voidedAt: null,
    deductions: [],
    ...o,
  };
}

export function advance(o: Partial<StaffAdvanceView> = {}): StaffAdvanceView {
  return {
    id: "a1",
    paidOn: "2026-06-10",
    amount: 5000,
    recovered: 2000,
    outstanding: 3000,
    mode: "CASH",
    modeLabel: "Cash",
    reference: null,
    note: null,
    recordedByName: "Temple Admin",
    voidedAt: null,
    ...o,
  };
}

export function pay(o: Partial<StaffPayView> = {}): StaffPayView {
  return {
    staffId: "s1",
    fullName: "Gopal Das",
    currency: "INR",
    monthlySalary: 18000,
    advanceBalance: 3000,
    lastSalaryPayment: payment(),
    payments: [payment()],
    advances: [advance()],
    ...o,
  };
}

export function ban(o: Partial<EmploymentBanView> = {}): EmploymentBanView {
  return {
    id: "b1",
    staffProfileId: "s2",
    personName: "Yamuna Devi Dasi",
    category: "THEFT",
    categoryLabel: "Theft or misappropriation",
    account: "Took ₹18,000 from the donation box over three weeks.",
    raisedAt: "2026-07-01T09:00:00Z",
    raisedBy: "Temple Admin",
    fadesOn: "2036-07-01",
    retracted: false,
    retractedAt: null,
    retractionReason: null,
    ...o,
  };
}

export const TITLES: JobTitleOption[] = [
  {
    value: "TEMPLE_ADMINISTRATOR",
    label: "Temple Administrator",
    group: "ADMINISTRATION",
    suggestedAccess: "TEMPLE_ADMIN",
  },
  { value: "HEAD_COOK", label: "Head Cook", group: "KITCHEN", suggestedAccess: "KITCHEN_STAFF" },
  { value: "COOK", label: "Cook", group: "KITCHEN", suggestedAccess: "KITCHEN_STAFF" },
  { value: "DRIVER", label: "Driver", group: "SUPPORT", suggestedAccess: null },
  { value: "OTHER", label: "Other", group: "OTHER", suggestedAccess: null },
];

export const CATEGORIES: BanCategoryOption[] = [
  { value: "THEFT", label: "Theft or misappropriation" },
  { value: "HARASSMENT", label: "Harassment or abuse" },
];

/** A kitchen (Epic 12). The main one by default, which is where every existing record was put. */
export function kitchen(o: Partial<Kitchen> = {}): Kitchen {
  return {
    id: "k1",
    name: "Main kitchen",
    description: null,
    location: null,
    isMain: true,
    usesMealPlanner: true,
    inChargeUserId: null,
    inChargeName: null,
    staffCount: 1,
    contactPhone: null,
    status: "ACTIVE",
    createdAt: "2026-01-01T00:00:00Z",
    ...o,
  };
}

/** A row of the Temple Admin's "Check these kitchen assignments" list. */
export function kitchenCheck(o: Partial<StaffKitchenCheckView> = {}): StaffKitchenCheckView {
  return {
    staffId: "s1",
    fullName: "Gopal Das",
    jobTitleLabel: "Head Cook",
    kitchenId: "k1",
    kitchenName: "Main kitchen",
    ...o,
  };
}
