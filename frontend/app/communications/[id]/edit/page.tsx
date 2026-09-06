"use client";

import { useCallback } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { ErrorNotice } from "@/components/ErrorNotice";
import { EmptyState } from "@/components/ds/EmptyState";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { api } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Composer } from "../../composer";

/**
 * Pick a draft back up.
 *
 * <p>The draft is fetched here rather than handed over from the list, so the letter survives a
 * reload and a link to it works — which is the whole reason the composer stopped being a panel.
 *
 * <p>It comes out of the list rather than from a fetch of its own because there is no endpoint for
 * one communication, and inventing one would be an API change on a build that is only moving a form
 * onto its own page. The list already carries the letter itself, so nothing is missing.
 */
export default function EditCommunicationPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <EditCommunicationView />
    </RequireRole>
  );
}

function EditCommunicationView() {
  const id = useParams<{ id: string }>().id;
  const { data, error, loading } = useAuthedQuery(
    useCallback((t: string | undefined) => api.listCommunications(t), [])
  );

  if (loading) return <Loading label="Loading the message…" />;
  if (error) return <ErrorNotice error={error} />;
  const existing = (data ?? []).find((c) => c.id === id);
  // A link to a letter that has since been sent or deleted. This rendered nothing at all — a white
  // screen with no explanation and no way back — which is the worst of the three things it could
  // do. Found by opening every screen against an empty backend.
  if (!existing) {
    return (
      <EmptyState
        title="That draft is no longer here"
        action={
          <Link href="/communications" className="btn btn-secondary min-h-touch px-4 text-sm">
            Back to messages
          </Link>
        }
      >
        It may have been sent already, or deleted. Sent messages cannot be edited.
      </EmptyState>
    );
  }
  return <Composer existing={existing} />;
}
