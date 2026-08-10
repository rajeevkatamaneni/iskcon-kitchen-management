/**
 * Display names for the calendar codes the API returns (E4). The engine emits numeric tithi/masa/
 * paksa; these map them to the Gaudiya names, kept on the client so the calendar table stays lean.
 */

const MASA = [
  "Madhusudana", "Trivikrama", "Vamana", "Sridhara", "Hrsikesa", "Padmanabha",
  "Damodara", "Kesava", "Narayana", "Madhava", "Govinda", "Visnu", "Adhika",
];

const TITHI_15 = [
  "Pratipat", "Dvitiya", "Tritiya", "Caturthi", "Pancami", "Sasthi", "Saptami",
  "Astami", "Navami", "Dasami", "Ekadasi", "Dvadasi", "Trayodasi", "Caturdasi",
];

export function masaName(masa: number): string {
  return MASA[masa] ?? `Masa ${masa}`;
}

/** Paksa 1 is Gaura (waxing), 0 is Krsna (waning). */
export function paksaName(paksa: number): string {
  return paksa === 1 ? "Gaura" : "Krsna";
}

/** Tithi 0..14 are Krsna paksa, 15..29 Gaura; the 15th is Amavasya (new) or Purnima (full). */
export function tithiName(tithi: number): string {
  const within = tithi % 15;
  if (within === 14) return tithi < 15 ? "Amavasya" : "Purnima";
  return TITHI_15[within] ?? `Tithi ${tithi}`;
}

export function fullTithiName(tithi: number, paksa: number): string {
  const t = tithiName(tithi);
  if (t === "Amavasya" || t === "Purnima") return t;
  return `${paksaName(paksa)} ${t}`;
}
