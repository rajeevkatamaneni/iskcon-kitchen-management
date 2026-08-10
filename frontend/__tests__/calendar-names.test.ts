import { describe, expect, it } from "vitest";
import { masaName, paksaName, tithiName, fullTithiName } from "@/lib/calendar-names";

describe("calendar names", () => {
  it("names the Gaudiya masa", () => {
    expect(masaName(0)).toBe("Madhusudana");
    expect(masaName(11)).toBe("Visnu");
    expect(masaName(12)).toBe("Adhika");
  });

  it("names paksa by waxing/waning", () => {
    expect(paksaName(1)).toBe("Gaura");
    expect(paksaName(0)).toBe("Krsna");
  });

  it("names tithi, with the 15th as Amavasya (Krsna) or Purnima (Gaura)", () => {
    expect(tithiName(10)).toBe("Ekadasi"); // Krsna Ekadasi
    expect(tithiName(25)).toBe("Ekadasi"); // Gaura Ekadasi
    expect(tithiName(14)).toBe("Amavasya"); // new moon
    expect(tithiName(29)).toBe("Purnima"); // full moon
  });

  it("builds a full tithi name with paksa, omitting it for the moons", () => {
    expect(fullTithiName(10, 0)).toBe("Krsna Ekadasi");
    expect(fullTithiName(25, 1)).toBe("Gaura Ekadasi");
    expect(fullTithiName(29, 1)).toBe("Purnima");
  });
});
