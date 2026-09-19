"use client";

import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api, type TemplateStatusCounts, type WhatsAppTemplateCatalogueEntry } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { moment, templeDay } from "@/lib/format";

/**
 * The platform operator's catalogue of WhatsApp templates (T-177).
 *
 * <p>Rajeev, 2026-09-13: *"We have no way of seeing the Text in each of these templates. I feel like
 * it is black box no one can see into."* So each template shows what Meta is given for it — its name,
 * category, language, body and the example values Meta's reviewer sees — and what in the app sends
 * it. Read-only, and no temple's data. Meta's status per temple is step 2 (T-178).
 *
 * <p>Its own destination beside Operations rather than a section of it, for the reason nav.ts gives
 * the Recipe library: Operations answers "what is failing", and a catalogue is not an answer to it.
 *
 * <p><strong>The dates say "seen", never "created".</strong> Nothing recorded when anybody wrote a
 * wording, and git history is not available to a running app. What is recorded, from the release
 * that added it, is when a running app first saw each wording (V130). So the labels say exactly that,
 * the line under the heading says when tracking began, and an empty date is said in words: a blank
 * would read as "unknown" to one person and "never" to the next.
 */
export default function WhatsAppTemplatesPage() {
  return (
    <RequireRole roles={["SUPER_ADMIN"]}>
      <WhatsAppTemplatesView />
    </RequireRole>
  );
}

/** Meta's category, as a word. The stored value is upper case and is not printed as it is. */
const CATEGORY_LABEL: Record<string, string> = {
  UTILITY: "Utility",
  MARKETING: "Marketing",
};

/** The language code Meta keys a template on, as a word. */
const LANGUAGE_LABEL: Record<string, string> = {
  en: "English",
};

function WhatsAppTemplatesView() {
  const catalogue = useAuthedQuery(api.whatsappTemplateCatalogue);
  const data = catalogue.data;
  // T-178: Meta's status, counted across temples. A read of its own, so a failure to count never hides
  // the wording, which is the half of this page that holds no temple's data at all.
  const counts = useAuthedQuery(api.whatsappTemplateStatusCounts);
  const countsByName = new Map((counts.data ?? []).map((c) => [c.name, c]));

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/whatsapp-templates" />

      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>WhatsApp templates</h1>
            {data && (
              <p className="mt-1 text-sm text-ink-secondary">
                {data.trackingSince ? (
                  <>Tracking began on {templeDay(data.trackingSince)}. Nothing earlier was recorded.</>
                ) : (
                  <>Tracking has not begun. No wording has been recorded yet.</>
                )}
              </p>
            )}
          </header>

          {counts.error && (
            <div className="mb-6">
              <ErrorNotice error={counts.error} />
            </div>
          )}

          {catalogue.error ? (
            <ErrorNotice error={catalogue.error} />
          ) : catalogue.loading || !data ? (
            <Loading label="Loading the templates…" />
          ) : (
            <ul className="grid gap-4">
              {data.templates.map((template) => (
                <li key={template.name}>
                  <TemplateCard template={template} counts={countsByName.get(template.name)} />
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </div>
  );
}

function TemplateCard({
  template,
  counts,
}: {
  template: WhatsAppTemplateCatalogueEntry;
  counts: TemplateStatusCounts | undefined;
}) {
  const headingId = `template-${template.name}`;

  return (
    <article className="card px-6 py-5" aria-labelledby={headingId}>
      <h2 id={headingId} className="font-mono text-lg">
        {template.name}
      </h2>
      <p className="mt-1 text-sm text-ink-secondary">
        {CATEGORY_LABEL[template.category] ?? template.category} ·{" "}
        {LANGUAGE_LABEL[template.language] ?? template.language}
      </p>

      {counts && <StatusAcrossTemples counts={counts} />}

      <h3 className="mt-4 text-sm font-medium">Body</h3>
      {/* `rounded-control`, the corner every box of text in the application wears (T-235). Plain
          `rounded` is Tailwind's default here, 12px, left over from before the theme packs set the
          control corner — so this one box was rounder than everything around it. */}
      <p className="mt-1 whitespace-pre-wrap rounded-control bg-sunken px-4 py-3 text-sm">{template.body}</p>

      {template.exampleValues.length > 0 && (
        <>
          <h3 className="mt-4 text-sm font-medium">Example values</h3>
          <dl className="mt-1 grid gap-1 text-sm">
            {template.exampleValues.map((value, index) => (
              <div key={index} className="flex gap-3">
                <dt className="shrink-0 font-mono text-ink-secondary">{`{{${index + 1}}}`}</dt>
                <dd className="min-w-0">{value}</dd>
              </div>
            ))}
          </dl>
        </>
      )}

      <h3 className="mt-4 text-sm font-medium">Used by</h3>
      <ul className="mt-1 list-disc pl-5 text-sm">
        {template.usedBy.map((use) => (
          <li key={use}>{use}</li>
        ))}
      </ul>

      <dl className="mt-4 flex flex-wrap gap-x-10 gap-y-3 text-sm">
        <div>
          <dt className="text-ink-secondary">Wording first seen by the app</dt>
          <dd className="mt-1">{firstSeenWords(template)}</dd>
        </div>
        <div>
          <dt className="text-ink-secondary">Wording last changed</dt>
          <dd className="mt-1">{lastChangedWords(template)}</dd>
        </div>
      </dl>
    </article>
  );
}

/**
 * Meta's status for this template, counted across every temple's stored copy (T-178).
 *
 * <p><strong>Counts, never temples.</strong> The operator reads one temple's own answer on that temple's
 * page. Here only numbers cross temples, as the Operations page's send totals do, and the API carries
 * nothing that could name one.
 *
 * <p>The line saying who is not counted is on every card on purpose: "approved in 3 of 5" reads as five
 * temples in total unless something says a temple without WhatsApp is not among them.
 *
 * <p>A formatting refusal is flagged for everyone because Meta's formatting rules are the same in every
 * temple: wording refused for its form in one will be refused in the next.
 */
function StatusAcrossTemples({ counts }: { counts: TemplateStatusCounts }) {
  return (
    <div className="mt-4">
      <h3 className="text-sm font-medium">Meta’s status across temples</h3>
      <p className="mt-1 text-sm">{countsWords(counts)}</p>
      <p className="mt-1 text-sm text-ink-secondary">
        Temples without WhatsApp, or never checked, are not counted.
      </p>
      {counts.formattingRefusal && (
        <div className="mt-2">
          {/* Red: Meta refused it, so no temple can send it until it is fixed (Rajeev, 2026-09-18, T-227). */}
          <InlineNotice tone="danger">
            Meta refused this for its formatting in a temple. That applies to every temple.
          </InlineNotice>
        </div>
      )}
    </div>
  );
}

/**
 * The counts as sentences. What is left over after approved, pending and refused (not held, not answered,
 * or a status Meta rarely uses) is said as its own number rather than dropped, so the parts add up to the
 * temples counted.
 */
function countsWords(c: TemplateStatusCounts): string {
  if (c.templesCounted === 0) return "No temple has a stored copy of Meta’s status yet.";
  const temples = c.templesCounted === 1 ? "temple" : "temples";
  const rest = c.templesCounted - c.approved - c.pending - c.refused;
  const sentences = [
    `Approved in ${c.approved} of ${c.templesCounted} ${temples}.`,
    `Pending in ${c.pending}.`,
    `Refused in ${c.refused}.`,
    `Held as marketing in ${c.marketing}.`,
  ];
  if (rest > 0) sentences.push(`Not held, unanswered or other in ${rest}.`);
  return sentences.join(" ");
}

function firstSeenWords(template: WhatsAppTemplateCatalogueEntry): string {
  return template.wordingFirstSeenAt ? moment(template.wordingFirstSeenAt) : "Not recorded";
}

/**
 * A null change means one of two things, and they are said differently. With a first-seen date, the
 * app has recorded one wording and no other since. Without one, nothing was recorded at all, and
 * "not changed" would be a claim about something nobody saw.
 */
function lastChangedWords(template: WhatsAppTemplateCatalogueEntry): string {
  if (template.wordingLastChangedAt) return moment(template.wordingLastChangedAt);
  return template.wordingFirstSeenAt ? "Not changed since tracking began" : "Not recorded";
}
