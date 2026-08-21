"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api, toApiError, type ApiError, type NoticeSeverity } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Raise a platform notice — a screen, not a panel, and for a second reason as well as the count.
 *
 * <p>What is written here goes out immediately, under this temple's name, to every temple on the
 * platform, and nobody reviews it first. A panel that opens over the board makes that read like an
 * entry being added to a list. A screen of its own says what it is.
 */

const FORM = "raise-notice";

const SEVERITIES: { value: NoticeSeverity; label: string; hint: string }[] = [
  {
    value: "INFORMATION",
    label: "Information",
    hint: "Sits quietly at the top of Today.",
  },
  {
    value: "IMPORTANT",
    label: "Important",
    hint: "Marked, but not loud.",
  },
  {
    value: "URGENT",
    label: "Urgent",
    hint: "A recall or a contaminated batch.",
  },
];

export default function NewNoticePage() {
  return (
    <RequireRole roles={["SUPER_ADMIN", "TEMPLE_ADMIN"]}>
      <NewNoticeView />
    </RequireRole>
  );
}

function NewNoticeView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function raise(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const subject = String(f.get("subject") ?? "").trim();
    setBusy(true);
    setError(null);
    try {
      await api.raiseNotice(
        {
          severity: String(f.get("severity") ?? "INFORMATION") as NoticeSeverity,
          subject,
          body: String(f.get("body") ?? "").trim(),
        },
        await getToken()
      );
      router.push(`/notices?raised=${encodeURIComponent(subject)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t post that notice."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Raise a notice"
      who="Goes to every temple on the platform"
      activeHref="/notices"
      actions={
        <>
          <ButtonLink href="/notices" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Post to every temple
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      <InlineNotice tone="warning" title="This goes out immediately.">
        Nobody reviews it first, and it carries your temple’s name.
      </InlineNotice>

      <form id={FORM} className="grid gap-4" aria-label="Raise a platform notice" onSubmit={raise}>
        <fieldset className="grid gap-2">
          <legend className="pl-field-inset text-sm font-medium text-ink">Severity</legend>
          {SEVERITIES.map((s, i) => (
            <label key={s.value} className="flex items-baseline gap-2 text-sm">
              <input type="radio" name="severity" value={s.value} defaultChecked={i === 0} className="mt-1" />
              <span>
                <span className="text-ink">{s.label}</span> <span className="text-ink-muted">{s.hint}</span>
              </span>
            </label>
          ))}
        </fieldset>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Subject</span>
          <input name="subject" required maxLength={120} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">What happened, and what other temples should do</span>
          <textarea name="body" required rows={8} maxLength={4000} className="rounded border border-hairline bg-canvas px-3 py-2" />
        </label>
      </form>
    </FocusScreen>
  );
}
