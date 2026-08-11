/**
 * The languages a document can be rendered in: English plus the 22 languages of the Eighth Schedule
 * of the Indian Constitution. A vendor's preferred language and a per-document language override are
 * both chosen from this list.
 *
 * <p>The codes are the ones the translation provider expects (Google Cloud Translation today). A few
 * scheduled languages have thinner machine-translation coverage; those improve when Bhashini — built
 * for all 22 — is wired as the provider. The list is what the UI offers; how well each renders is the
 * provider's concern, and content always falls back to English if a translation can't be produced.
 */
export interface Language {
  code: string;
  label: string;
}

export const ENGLISH: Language = { code: "en", label: "English" };

/** The 22 scheduled languages, by their English name, ordered alphabetically. */
export const SCHEDULED_LANGUAGES: Language[] = [
  { code: "as", label: "Assamese" },
  { code: "bn", label: "Bengali" },
  { code: "brx", label: "Bodo" },
  { code: "doi", label: "Dogri" },
  { code: "gu", label: "Gujarati" },
  { code: "hi", label: "Hindi" },
  { code: "kn", label: "Kannada" },
  { code: "ks", label: "Kashmiri" },
  { code: "gom", label: "Konkani" },
  { code: "mai", label: "Maithili" },
  { code: "ml", label: "Malayalam" },
  { code: "mni-Mtei", label: "Manipuri (Meitei)" },
  { code: "mr", label: "Marathi" },
  { code: "ne", label: "Nepali" },
  { code: "or", label: "Odia" },
  { code: "pa", label: "Punjabi" },
  { code: "sa", label: "Sanskrit" },
  { code: "sat", label: "Santali" },
  { code: "sd", label: "Sindhi" },
  { code: "ta", label: "Tamil" },
  { code: "te", label: "Telugu" },
  { code: "ur", label: "Urdu" },
];

/** English first, then the 22 scheduled languages — the full set a picker offers. */
export const ALL_LANGUAGES: Language[] = [ENGLISH, ...SCHEDULED_LANGUAGES];

/** The display label for a code, falling back to the code itself if unknown. */
export function languageLabel(code: string): string {
  return ALL_LANGUAGES.find((l) => l.code === code)?.label ?? code;
}
