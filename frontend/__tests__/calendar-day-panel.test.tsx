import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { CalendarDayView } from "@/lib/api";

const { setOverrideSpy, revertSpy } = vi.hoisted(() => ({
  setOverrideSpy: vi.fn(async () => undefined),
  revertSpy: vi.fn(async () => undefined),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken: async () => "token" }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      setCalendarOverride: setOverrideSpy,
      revertCalendarOverride: revertSpy,
    },
  };
});

import { CalendarDayPanel } from "@/components/CalendarDayPanel";

/** Ekadasi in the Gaura paksa: tithi 25 is index 10 of the waxing fortnight. */
const EKADASHI_DAY: CalendarDayView = {
  date: "2026-06-06",
  tithi: 25,
  paksa: 1,
  masa: 3,
  gaurabdaYear: 540,
  naksatra: 12,
  isEkadashi: true,
  ekadashiName: "Pandava Nirjala",
  mahadvadashi: null,
  fastType: "Fast from grains and beans",
  sunrise: "05:58",
  sunset: "18:47",
  festivals: [{ text: "Pandava Nirjala Ekadasi", priority: 3 }],
  overridden: false,
  overrideReason: null,
};

const ORDINARY_DAY: CalendarDayView = {
  ...EKADASHI_DAY,
  date: "2026-06-07",
  tithi: 26,
  isEkadashi: false,
  ekadashiName: null,
  fastType: null,
  festivals: [],
};

describe("the calendar day panel", () => {
  beforeEach(() => {
    setOverrideSpy.mockClear();
    revertSpy.mockClear();
  });

  it("explains why a day is marked as it is, so an Ekadashi warning isn't a mystery", () => {
    render(
      <CalendarDayPanel
        date="2026-06-06"
        day={EKADASHI_DAY}
        canCorrect={false}
        onClose={() => {}}
        onChanged={() => {}}
      />
    );

    expect(screen.getByText("Gaura Ekadasi")).toBeInTheDocument();
    expect(screen.getByText("Pandava Nirjala")).toBeInTheDocument();
    expect(screen.getByText("Fast from grains and beans")).toBeInTheDocument();
    expect(screen.getByText("Pandava Nirjala Ekadasi")).toBeInTheDocument();
  });

  it("offers no correction to kitchen staff", () => {
    render(
      <CalendarDayPanel
        date="2026-06-06"
        day={EKADASHI_DAY}
        canCorrect={false}
        onClose={() => {}}
        onChanged={() => {}}
      />
    );

    expect(screen.queryByRole("button", { name: /correct this date/i })).not.toBeInTheDocument();
  });

  it("lets a Temple Admin correct the fasting day, with a reason, and reloads after", async () => {
    const onChanged = vi.fn();
    render(
      <CalendarDayPanel
        date="2026-06-06"
        day={EKADASHI_DAY}
        canCorrect
        onClose={() => {}}
        onChanged={onChanged}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /correct this date/i }));

    // Turn the fast off for this date and say why.
    fireEvent.click(screen.getByLabelText(/this is an ekadashi fasting day/i));
    fireEvent.change(screen.getByLabelText(/why are you correcting this/i), {
      target: { value: "Local GBC ruling — we fast on the 7th this year." },
    });
    fireEvent.click(screen.getByRole("button", { name: /save correction/i }));

    await waitFor(() =>
      expect(setOverrideSpy).toHaveBeenCalledWith(
        "2026-06-06",
        {
          isEkadashi: false,
          ekadashiName: "Pandava Nirjala",
          tithi: 25,
          festivalNote: null,
          reason: "Local GBC ruling — we fast on the 7th this year.",
        },
        "token"
      )
    );
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it("can mark an ordinary day as a fast, choosing the tithi by name", async () => {
    render(
      <CalendarDayPanel
        date="2026-06-07"
        day={ORDINARY_DAY}
        canCorrect
        onClose={() => {}}
        onChanged={() => {}}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /correct this date/i }));
    fireEvent.click(screen.getByLabelText(/this is an ekadashi fasting day/i));
    fireEvent.change(screen.getByLabelText(/^tithi$/i), { target: { value: "25" } });
    fireEvent.change(screen.getByLabelText(/ekadashi name/i), {
      target: { value: "Pandava Nirjala" },
    });
    fireEvent.change(screen.getByLabelText(/why are you correcting this/i), {
      target: { value: "Follows the published local calendar." },
    });
    fireEvent.click(screen.getByRole("button", { name: /save correction/i }));

    await waitFor(() =>
      expect(setOverrideSpy).toHaveBeenCalledWith(
        "2026-06-07",
        expect.objectContaining({ isEkadashi: true, tithi: 25, ekadashiName: "Pandava Nirjala" }),
        "token"
      )
    );
  });

  it("will not save a correction without a reason", () => {
    render(
      <CalendarDayPanel
        date="2026-06-06"
        day={EKADASHI_DAY}
        canCorrect
        onClose={() => {}}
        onChanged={() => {}}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /correct this date/i }));
    fireEvent.click(screen.getByRole("button", { name: /save correction/i }));

    // The required field blocks submission, so nothing was sent.
    expect(setOverrideSpy).not.toHaveBeenCalled();
  });

  it("shows a corrected day as hand-made, with its reason, and lets an admin undo it", async () => {
    const onChanged = vi.fn();
    render(
      <CalendarDayPanel
        date="2026-06-06"
        day={{ ...EKADASHI_DAY, overridden: true, overrideReason: "Local GBC ruling" }}
        canCorrect
        onClose={() => {}}
        onChanged={onChanged}
      />
    );

    expect(screen.getByText(/corrected by hand/i)).toBeInTheDocument();
    expect(screen.getByText(/Local GBC ruling/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /undo the correction/i }));

    await waitFor(() => expect(revertSpy).toHaveBeenCalledWith("2026-06-06", "token"));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it("shows the correction as hand-made to staff, but without the undo", () => {
    render(
      <CalendarDayPanel
        date="2026-06-06"
        day={{ ...EKADASHI_DAY, overridden: true, overrideReason: "Local GBC ruling" }}
        canCorrect={false}
        onClose={() => {}}
        onChanged={() => {}}
      />
    );

    expect(screen.getByText(/corrected by hand/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /undo the correction/i })).not.toBeInTheDocument();
  });

  it("says plainly when a date hasn't been calculated yet, and offers no correction", () => {
    render(
      <CalendarDayPanel
        date="2028-01-01"
        day={undefined}
        canCorrect
        onClose={() => {}}
        onChanged={() => {}}
      />
    );

    expect(screen.getByText(/hasn’t been calculated yet/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /correct this date/i })).not.toBeInTheDocument();
  });
});
