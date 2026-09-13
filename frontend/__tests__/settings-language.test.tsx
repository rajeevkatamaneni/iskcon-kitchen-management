import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";

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

    // Read-only until Edit (T-185). Pressing Edit must not disturb what the picker reads.
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
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

    // Read-only until Edit (T-185): a pick is only ever made in an open section.
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
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

/**
 * Read-only until Edit, then Cancel and Save, as on every other section of Settings (T-185).
 *
 * <p>Rajeev, 2026-09-13: *"The default state of the screen shuld be read only to avoid accidental
 * mistakes. The way to get it to edit is using the 'Edit' button. When clicked the fields become
 * editable and the button reads 'Save'. This applies for all sections on the settinsg scree"*. The
 * other five sections are pinned in `settings-edit-mode.test.tsx`. These tests ask the same questions
 * of this one.
 */
describe("the temple's language opens read-only", () => {
  beforeEach(() => {
    setTempleLanguage.mockReset().mockResolvedValue(undefined);
  });

  /** Lets a handler that awaits a token reach its API call, so "not called" is not merely "not yet". */
  const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));
  const button = (name: string) => screen.queryByRole("button", { name });

  it("opens with the temple's language shown, the picker shut, an Edit button, and no Save", () => {
    render(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);

    // Not vacuous: the choice is on the screen, it simply cannot be changed.
    expect(picker()).toHaveValue("kn");
    // A <select> has no read-only state, so it is disabled, with the sunken fill the other sections use.
    expect(picker()).toBeDisabled();
    expect(picker()).toHaveClass("disabled:bg-sunken");
    expect(button("Edit")).toBeEnabled();
    expect(button("Save")).not.toBeInTheDocument();
    expect(button("Cancel")).not.toBeInTheDocument();
  });

  it("Edit opens the picker, and puts Cancel then Save where Edit was", () => {
    render(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);

    fireEvent.click(button("Edit")!);

    expect(picker()).toBeEnabled();
    const cancel = screen.getByRole("button", { name: "Cancel" });
    const save = screen.getByRole("button", { name: "Save" });
    // Secondary first, then the primary (§4), with the same classes as the other sections.
    expect(cancel.compareDocumentPosition(save) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(cancel).toHaveClass("btn", "btn-quiet");
    expect(save).toHaveClass("btn", "btn-primary");
    expect(button("Edit")).not.toBeInTheDocument();
  });

  it("Save sends the chosen language once, says Saved, and shuts the picker again", async () => {
    render(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);

    fireEvent.click(button("Edit")!);
    fireEvent.change(picker(), { target: { value: "hi" } });
    fireEvent.click(button("Save")!);

    await waitFor(() => expect(setTempleLanguage).toHaveBeenCalledTimes(1));
    expect(setTempleLanguage).toHaveBeenCalledWith("hi", "t");
    expect(await screen.findByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(screen.getByText("Saved.")).toBeInTheDocument();
    expect(picker()).toBeDisabled();
    expect(picker()).toHaveValue("hi");
    await settle();
    expect(setTempleLanguage).toHaveBeenCalledTimes(1);
  });

  it("Cancel puts back the language shown before Edit, and sends nothing", async () => {
    render(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);

    fireEvent.click(button("Edit")!);
    fireEvent.change(picker(), { target: { value: "ta" } });
    expect(picker()).toHaveValue("ta");
    fireEvent.click(button("Cancel")!);

    expect(picker()).toHaveValue("kn");
    expect(picker()).toBeDisabled();
    // And it is the value held, not only the value drawn: opening the section again starts from it.
    fireEvent.click(button("Edit")!);
    expect(picker()).toHaveValue("kn");
    await settle();
    expect(setTempleLanguage).not.toHaveBeenCalled();
  });

  it("Cancel after an earlier save puts back that saved choice, not the language it opened on", async () => {
    render(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);

    fireEvent.click(button("Edit")!);
    fireEvent.change(picker(), { target: { value: "ta" } });
    fireEvent.click(button("Save")!);
    await screen.findByRole("button", { name: "Edit" });

    fireEvent.click(button("Edit")!);
    fireEvent.change(picker(), { target: { value: "hi" } });
    fireEvent.click(button("Cancel")!);

    expect(picker()).toHaveValue("ta");
    await settle();
    expect(setTempleLanguage).toHaveBeenCalledTimes(1);
  });

  it("still reads a language that arrives later, after an Edit that was cancelled", () => {
    // The snapshot is what the component held, which before any pick is nothing. If Cancel pinned
    // the shown English instead, the temple's Kannada would never show: T-077 again.
    const { rerender } = render(<LanguageSection initial={null} getToken={async () => "t"} />);

    fireEvent.click(button("Edit")!);
    fireEvent.click(button("Cancel")!);
    rerender(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);

    expect(picker()).toHaveValue("kn");
  });

  it("greys out Save only while the save is in flight", async () => {
    let finish: () => void = () => {};
    setTempleLanguage.mockReturnValue(new Promise<void>((resolve) => (finish = resolve)));
    render(<LanguageSection initial="kn-IN" getToken={async () => "t"} />);

    fireEvent.click(button("Edit")!);
    // Nothing can be blank here, so there is no other reason for Save to be grey.
    expect(button("Save")).toBeEnabled();
    fireEvent.click(button("Save")!);

    const saving = await screen.findByRole("button", { name: "Saving…" });
    expect(saving).toBeDisabled();
    expect(button("Cancel")).toBeDisabled();

    await act(async () => finish());
    expect(await screen.findByRole("button", { name: "Edit" })).toBeEnabled();
  });
});
