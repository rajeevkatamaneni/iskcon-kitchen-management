"use client";

import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { api, type WhatsAppTemplateCatalogueEntry } from "@/lib/api";
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

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/whatsapp-templates" />

      <main className="min-w-0 flex-1 px-8 py-10">
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

          {catalogue.error ? (
            <ErrorNotice error={catalogue.error} />
          ) : catalogue.loading || !data ? (
            <Loading label="Loading the templates…" />
          ) : (
            <ul className="grid gap-4">
              {data.templates.map((template) => (
                <li key={template.name}>
                  <TemplateCard template={template} />
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </div>
  );
}

function TemplateCard({ template }: { template: WhatsAppTemplateCatalogueEntry }) {
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

      <h3 className="mt-4 text-sm font-medium">Body</h3>
      <p className="mt-1 whitespace-pre-wrap rounded bg-sunken px-4 py-3 text-sm">{template.body}</p>

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
