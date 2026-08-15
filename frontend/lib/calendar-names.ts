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

/**
 * The 27 naksatras, in the order the engine numbers them. Shown because a pujari reads the day by
 * its naksatra as much as by its tithi — it is the first thing they look for on a printed calendar.
 */
const NAKSATRA = [
  "Asvini", "Bharani", "Krttika", "Rohini", "Mrgasira", "Ardra", "Punarvasu", "Pusya", "Aslesa",
  "Magha", "Purva-phalguni", "Uttara-phalguni", "Hasta", "Citra", "Svati", "Visakha", "Anuradha",
  "Jyestha", "Mula", "Purva-asadha", "Uttara-asadha", "Sravana", "Dhanistha", "Satabhisa",
  "Purva-bhadra", "Uttara-bhadra", "Revati",
];

export function naksatraName(naksatra: number | null | undefined): string | null {
  if (naksatra == null) return null;
  return NAKSATRA[naksatra] ?? null;
}

/** "Gaura Dvitiya · Purva-phalguni naksatra · Sridhara masa" — how a day is named aloud. */
export function dayLabel(day: {
  tithi: number;
  paksa: number;
  masa: number;
  naksatra?: number | null;
}): string {
  const parts = [`${paksaName(day.paksa)} ${tithiName(day.tithi)}`];
  const nak = naksatraName(day.naksatra);
  if (nak) parts.push(`${nak} naksatra`);
  parts.push(`${masaName(day.masa)} masa`);
  return parts.join(" · ");
}
