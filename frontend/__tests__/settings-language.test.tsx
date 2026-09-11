import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * The temple's language, and the defect that would have made it unsettable (T-077).
 *
 * <p>The picker held its value in `useState((initial ?? "en-IN").split("-")[0])`. A `useState`
 * argument is read once, on the first render, and never again — so a prop that arrives after the
 * first paint is a prop the picker never sees. The screen would have gone on offering **English**
 * while the temple's stored setting said Kannada, and pressing Save would have written English over
 * it without a single thing on the screen looking wrong.
 *
 * <p>The first test is the one that matters: it **changes the prop after mounting**. A fixture that
 * renders once with the final value passes whether or not the bug is there, which is a control that
 * proves nothing.
 */

const { setTempleLanguage } = vi.hoisted(() => ({ setTempleLanguage: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, setTempleLanguage } };
});

import { LanguageSection } from "@/components/LanguageSection";

/** The picker itself. The "i" beside the label is a button of the same name, so ask for the box. */
const picker = () => screen.getByRole("combobox", { name: /your temple’s language/i });

describe("the temple's language", () => {
  beforeEach(() => {
    setTempleLanguage.mockReset().mockResolvedValue(undefined);
  });

  it("reads the temple's own language when it arrives after the first render", () => {
    // Mounted before the setting has loaded, which is the case the whole defect is about.
    const { rerender } = render(<LanguageSection initial={null} getToken={async () => "t"} />);
    expect(picker()).toHaveValue("en");

    // The prop changes — the server has answered. Nothing remounts.
    rerender(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);
    expect(picker()).toHaveValue("kn");
  });

  it("saves the temple's own language, not the English it opened on", async () => {
    const { rerender } = render(<LanguageSection initial={null} getToken={async () => "t"} />);
    rerender(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);

    fireEvent.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(setTempleLanguage).toHaveBeenCalledTimes(1));
    // The bug's real cost: this argument would have been "en", quietly undoing the temple's setting.
    expect(setTempleLanguage.mock.calls[0][0]).toBe("kn");
  });

  it("keeps what the person picked when the prop changes underneath them", () => {
    // The reason this is derived rather than copied into state by an effect. An effect that mirrors
    // a prop would overwrite a choice already made the next time the prop moved; deriving from a
    // null-until-picked state cannot.
    const { rerender } = render(<LanguageSection initial="en-IN" getToken={async () => "t"} />);

    fireEvent.change(picker(), { target: { value: "ta" } });
    expect(picker()).toHaveValue("ta");

    rerender(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);
    expect(picker()).toHaveValue("ta");
  });

  it("strips the region, because a person picks a language and the record stores a locale", () => {
    render(<LanguageSection initial="mr-IN" getToken={async () => "t"} />);
    expect(picker()).toHaveValue("mr");
  });

  it("falls back to English when the temple has never had one set", () => {
    // Every temple's locale was null until this setting became writable, so this is not a corner.
    render(<LanguageSection initial={null} getToken={async () => "t"} />);
    expect(picker()).toHaveValue("en");
  });

  it("offers English and all twenty-two scheduled languages", () => {
    render(<LanguageSection initial="en-IN" getToken={async () => "t"} />);
    expect(screen.getAllByRole("option")).toHaveLength(23);
  });
});
