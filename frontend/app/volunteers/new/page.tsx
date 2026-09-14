"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { ShiftFields, SHIFT_FORM, readShiftForm } from "../shift-form";

/**
 * Post a shift — eight fields, so a screen of its own.
 *
 * <p>Only shifts not for a meal are posted here (D-27 answers 3 and 4, Rajeev 2026-09-13). Kitchen help
 * for a meal is asked for from that meal in the planner and saved with it, so this screen has no "is
 * this for a meal" box and no meal picker, and the request it sends has no meal field. The one sentence
 * above the boxes says where that is done, so nobody hunts this form for the box that used to be here.
 */
export default function NewShiftPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewShiftView />
    </RequireRole>
  );
}

function NewShiftView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function post(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const input = readShiftForm(new FormData(event.currentTarget));
    setBusy(true);
    setError(null);
    try {
      await api.createShift(input, await getToken());
      router.push(`/volunteers?posted=${encodeURIComponent(input.title)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t post that shift."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Post a shift"
      who="Volunteers see it the moment it is posted"
      activeHref="/volunteers"
      actions={
        <>
          <ButtonLink href="/volunteers" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={SHIFT_FORM} disabled={busy}>
            Post shift
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}
      <p className="text-sm text-ink-secondary">
        Kitchen help for a meal? Ask from that meal{" "}
        <Link href="/planner" className="text-accent-text underline">
          in the planner
        </Link>
        .
      </p>
      <ShiftFields editing={false} onSubmit={post} />
    </FocusScreen>
  );
}
