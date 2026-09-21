import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import {
  type ApiError,
  type JobTitleOption,
  type StaffConductNoteView,
  type StaffDocumentView,
  type StaffPayView,
  type StaffRecordView,
} from "@/lib/api";
import { TITLES, document as documentFixture, kitchen, pay, previousJob, record } from "./staff-fixtures";

/**
 * A staff record as a record: its photograph, its papers and its past jobs (T-428).
 *
 * <p>Rajeev, 2026-09-20, reviewing the staff screens: the person's name should open a view page with
 * Edit on it, "their photo sits top right and their name is prominent", the PAN should be "displayed
 * in the same text box with an 'Eye' Icon", Notes should go unless it can be justified, and the
 * record should carry scans of the PAN and Aadhaar cards and where the person worked before.
 *
 * <p>The three things worth holding a test against, because each of them is a way to get this wrong
 * that would look right on the screen:
 *
 * <ul>
 *   <li><b>Nothing sensitive is fetched by opening the record.</b> Reading a PAN and opening a scan
 *       are both written to the audit log, so a page that fetched them to hide them would record
 *       reads nobody made. The photograph is the one file that is fetched, because showing it is
 *       what it is for.
 *   <li><b>An upload goes to the right kind.</b> Three boxes that all post PHOTO would look perfect.
 *   <li><b>Notes is gone from the form as well as from the record.</b> Half a removal leaves a box
 *       that saves nothing.
 * </ul>
 */

const {
  authRef,
  paramsRef,
  recordRef,
  payRef,
  titlesRef,
  conductRef,
  revealMock,
  uploadMock,
  documentMock,
  removeMock,
  updateMock,
  pushMock,
} = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  paramsRef: { current: { id: "s1" } },
  recordRef: {
    current: { data: null as StaffRecordView | null, error: null as ApiError | null, loading: false },
  },
  payRef: { current: { data: null as StaffPayView | null, error: null as ApiError | null, loading: false } },
  titlesRef: { current: { data: [] as JobTitleOption[], error: null, loading: false } },
  conductRef: { current: { data: [] as StaffConductNoteView[], error: null, loading: false } },
  revealMock: vi.fn(),
  uploadMock: vi.fn(),
  documentMock: vi.fn(),
  removeMock: vi.fn(),
  updateMock: vi.fn(),
  pushMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: pushMock }),
  useParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const source = fn.toString();
    if (source.includes("listKitchens")) {
      return { data: [kitchen()], error: null, loading: false, reload: vi.fn() };
    }
    const ref = source.includes("staffConductNotes")
      ? conductRef
      : source.includes("staffMember")
        ? recordRef
        : source.includes("staffPay")
          ? payRef
          : source.includes("jobTitles")
            ? titlesRef
            : { current: { data: [], error: null, loading: false } };
    return { ...ref.current, reload: vi.fn() };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      revealStaffPan: revealMock,
      uploadStaffDocument: uploadMock,
      staffDocument: documentMock,
      removeStaffDocument: removeMock,
      updateStaffMember: updateMock,
    },
  };
});

import StaffRecordPage from "@/app/staff/[id]/page";
import EditStaffPage from "@/app/staff/[id]/edit/page";

const jpeg = () => new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0])], "gopal.jpg", { type: "image/jpeg" });

function choosers(): HTMLInputElement[] {
  return Array.from(window.document.querySelectorAll<HTMLInputElement>('input[type="file"]'));
}

beforeEach(() => {
  authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  paramsRef.current = { id: "s1" };
  recordRef.current = { data: record(), error: null, loading: false };
  payRef.current = { data: pay(), error: null, loading: false };
  titlesRef.current = { data: TITLES, error: null, loading: false };
  conductRef.current = { data: [], error: null, loading: false };
  revealMock.mockReset().mockResolvedValue({ pan: "ABCDE1234F" });
  uploadMock.mockReset().mockResolvedValue(documentFixture());
  documentMock.mockReset().mockResolvedValue(new Blob([new Uint8Array([0xff, 0xd8])]));
  removeMock.mockReset().mockResolvedValue(undefined);
  updateMock.mockReset().mockResolvedValue(undefined);
  pushMock.mockReset();
  URL.createObjectURL = vi.fn(() => "blob:t428");
  URL.revokeObjectURL = vi.fn();
});

describe("the photograph at the top of a record", () => {
  it("says there is none rather than guessing at initials", () => {
    render(<StaffRecordPage />);
    expect(screen.getByRole("img", { name: "No photo" })).toBeInTheDocument();
    expect(documentMock).not.toHaveBeenCalled();
  });

  it("draws the stored photograph, fetched with the token rather than linked", async () => {
    recordRef.current = {
      data: record(undefined, { documents: [documentFixture({ id: "d-photo" })] }),
      error: null,
      loading: false,
    };
    render(<StaffRecordPage />);

    await waitFor(() => expect(documentMock).toHaveBeenCalledWith("s1", "d-photo", "test-token"));
    const picture = await screen.findByAltText("Gopal Das");
    expect(picture).toHaveAttribute("src", "blob:t428");
  });
});

describe("the papers on a record", () => {
  it("offers one box per kind, and posts the kind the box is for", async () => {
    render(<StaffRecordPage />);
    const papers = screen.getByRole("region", { name: "Documents" });

    expect(within(papers).getByRole("group", { name: /^Photo/ })).toBeInTheDocument();
    expect(within(papers).getByRole("group", { name: /^PAN card/ })).toBeInTheDocument();
    expect(within(papers).getByRole("group", { name: /^Aadhaar card/ })).toBeInTheDocument();
    // Who can open these, said before somebody uploads one rather than after.
    expect(
      within(papers).getByText(/Only people who can manage staff can open these/)
    ).toBeInTheDocument();

    // The third box is the Aadhaar card, and choosing a file there must not post a PHOTO.
    const file = jpeg();
    await act(async () => {
      fireEvent.change(choosers()[2], { target: { files: [file] } });
    });
    expect(uploadMock).toHaveBeenCalledWith("s1", "AADHAAR_SCAN", file, "test-token");
  });

  it("does not fetch a stored scan to draw it, because fetching one is a recorded read", () => {
    recordRef.current = {
      data: record(undefined, {
        documents: [documentFixture({ id: "d-pan", kind: "PAN_SCAN", originalName: "pan.jpg" })],
      }),
      error: null,
      loading: false,
    };
    render(<StaffRecordPage />);

    // The card is on the screen, by name, with a way to open it — and nothing has been read yet.
    expect(screen.getByText("pan.jpg")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open pan.jpg" })).toBeInTheDocument();
    expect(documentMock).not.toHaveBeenCalled();
  });

  it("reads the file only when it is opened", async () => {
    recordRef.current = {
      data: record(undefined, {
        documents: [documentFixture({ id: "d-aadhaar", kind: "AADHAAR_SCAN", originalName: "aadhaar.jpg" })],
      }),
      error: null,
      loading: false,
    };
    render(<StaffRecordPage />);

    fireEvent.click(screen.getByRole("button", { name: "Open aadhaar.jpg" }));
    await waitFor(() => expect(documentMock).toHaveBeenCalledWith("s1", "d-aadhaar", "test-token"));
  });
});

describe("where they worked before", () => {
  it("is on the record, with the manager and the span written out", () => {
    recordRef.current = {
      data: record(undefined, { previousEmployment: [previousJob()] }),
      error: null,
      loading: false,
    };
    render(<StaffRecordPage />);
    const panel = screen.getByRole("region", { name: "Previous employment" });

    expect(within(panel).getByText(/Adyar Ananda Bhavan/)).toBeInTheDocument();
    expect(within(panel).getByText(/Tandoor Assistant/)).toBeInTheDocument();
    expect(within(panel).getByText("March 2019 – July 2023")).toBeInTheDocument();
    expect(within(panel).getByText(/Suresh Kumar/)).toBeInTheDocument();
    expect(within(panel).getByText(/\+919845012345/)).toBeInTheDocument();
    expect(within(panel).getByText(/The branch closed\./)).toBeInTheDocument();
  });

  it("says so when there are none, rather than leaving an empty panel", () => {
    render(<StaffRecordPage />);
    const panel = screen.getByRole("region", { name: "Previous employment" });
    expect(within(panel).getByText("No previous jobs recorded.")).toBeInTheDocument();
  });

  it("is edited on the record's own Edit screen, and the whole list is sent", async () => {
    recordRef.current = {
      data: record(undefined, { previousEmployment: [previousJob()] }),
      error: null,
      loading: false,
    };
    render(<EditStaffPage />);

    const form = screen.getByRole("form", { name: /edit a staff member/i });
    expect(within(form).getByDisplayValue("Adyar Ananda Bhavan")).toBeInTheDocument();
    expect(within(form).getByDisplayValue("Suresh Kumar")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Add work history" }));
    const blocks = form.querySelectorAll("[data-previous-job]");
    expect(blocks).toHaveLength(2);

    fireEvent.change(blocks[1].querySelector('input[name="prevEmployer"]')!, {
      target: { value: "Sri Krishna Caterers" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    expect(updateMock.mock.calls[0][1].previousEmployment).toEqual([
      {
        employer: "Adyar Ananda Bhavan",
        theirTitle: "Tandoor Assistant",
        managerName: "Suresh Kumar",
        managerPhone: "+919845012345",
        fromDate: "2019-03-01",
        toDate: "2023-07-31",
        reasonForLeaving: "The branch closed.",
      },
      {
        employer: "Sri Krishna Caterers",
        theirTitle: null,
        managerName: null,
        managerPhone: null,
        fromDate: null,
        toDate: null,
        reasonForLeaving: null,
      },
    ]);
  });

  it("sends an empty list once the last job is removed, so the record loses it too", async () => {
    recordRef.current = {
      data: record(undefined, { previousEmployment: [previousJob()] }),
      error: null,
      loading: false,
    };
    render(<EditStaffPage />);

    fireEvent.click(screen.getByRole("button", { name: "Remove" }));
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    expect(updateMock.mock.calls[0][1].previousEmployment).toEqual([]);
  });
});

describe("Notes", () => {
  it("is gone from the record", () => {
    render(<StaffRecordPage />);
    // The label, not the word: "Conduct notes" is a different panel and is staying.
    expect(screen.queryByText("Notes", { selector: "dt" })).not.toBeInTheDocument();
  });

  it("is gone from the form, so nothing is typed into a box that saves nothing", () => {
    render(<EditStaffPage />);
    const form = screen.getByRole("form", { name: /edit a staff member/i });
    expect(form.querySelector('input[name="notes"]')).toBeNull();
  });
});
