import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { ALL_LANGUAGES, SCHEDULED_LANGUAGES } from "@/lib/languages";

/**
 * There is one list of languages, and it lives in lib/languages.ts.
 *
 * <p>This exists because the same complaint came back twice: the recipe screen offered five
 * languages while the purchase-order screen offered every one of them. It was never a regression —
 * nothing kept breaking. The shared list was introduced and some screens were converted to it, while
 * the recipe and glossary screens quietly kept private arrays of their own. Adding a language to the
 * shared list simply never reached them, and nothing anywhere said so.
 *
 * <p>So the fix is not another conversion, it is this: a second list cannot exist without failing
 * the build. Adding a language stays a one-line change to one file, which is what was intended.
 */
describe("one list of languages, and only one", () => {
  const roots = ["app", "components", "lib"];

  function sourceFiles(dir: string): string[] {
    const out: string[] = [];
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) {
        out.push(...sourceFiles(path));
      } else if (/\.tsx?$/.test(path)) {
        out.push(path);
      }
    }
    return out;
  }

  it("no screen declares a language list of its own", () => {
    // A local array of {code, label} is the shape every private copy took.
    const privateList = /(?:const|let)\s+\w*LANGUAGES\w*\s*(?::[^=]*)?=\s*\[/;
    const offenders = roots
      .flatMap(sourceFiles)
      .filter((f) => f !== join("lib", "languages.ts"))
      .filter((f) => privateList.test(readFileSync(f, "utf8")));

    expect(
      offenders,
      `these declare their own language list instead of importing ALL_LANGUAGES / SCHEDULED_LANGUAGES ` +
        `from lib/languages.ts:\n  ${offenders.join("\n  ")}`
    ).toEqual([]);
  });

  it("the shared list is the full set, not a sample of it", () => {
    // Guards against someone "simplifying" the shared list down to a handful, which would recreate
    // the original complaint in the one place every screen now trusts.
    expect(SCHEDULED_LANGUAGES.length).toBeGreaterThan(15);
    expect(ALL_LANGUAGES.length).toBe(SCHEDULED_LANGUAGES.length + 1); // + English
    expect(ALL_LANGUAGES.map((l) => l.code)).toContain("en");
    // The ones a temple in Bengaluru actually needs are present.
    for (const code of ["hi", "kn", "te", "ta", "mr", "bn", "gu", "or", "ml", "pa"]) {
      expect(ALL_LANGUAGES.map((l) => l.code)).toContain(code);
    }
  });
});
