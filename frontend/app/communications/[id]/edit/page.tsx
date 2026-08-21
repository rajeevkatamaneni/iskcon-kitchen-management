"use client";

import { useCallback } from "react";
import { useParams } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
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
  if (!existing) return null;
  return <Composer existing={existing} />;
}
