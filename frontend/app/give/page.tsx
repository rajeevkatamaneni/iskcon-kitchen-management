"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";

/**
 * Giving, from inside the app.
 *
 * <p>The donation page belongs to a temple and lives under its own public address, which is what a
 * devotee is sent when somebody shares a link. A devotee who is already signed in should not have to
 * be sent anything: this takes them to their own temple's page.
 */
export default function GivePage() {
  const router = useRouter();
  const { appUser, status } = useAuth();

  useEffect(() => {
    if (status === "signed-out") router.replace("/sign-in");
    if (appUser?.tenantSlug) router.replace(`/t/${appUser.tenantSlug}/donate`);
  }, [status, appUser, router]);

  return (
    <main className="mx-auto grid min-h-screen max-w-prose place-items-center px-6">
      <Loading label="Opening your temple's giving page…" />
    </main>
  );
}
