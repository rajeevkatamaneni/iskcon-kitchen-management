"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { Button } from "@/components/ds/Button";
import { JoinTempleForm } from "@/components/JoinTempleForm";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";

/**
 * Where a devotee lands when Google has vouched for them and no temple has.
 *
 * <p>There is no way past it, and that is the point: every screen in this product belongs to a
 * temple, so until one is chosen there is nothing to show. They are not stuck — they are one
 * question from being somewhere.
 */
export default function ChooseTemplePage() {
  const router = useRouter();
  const { user, status, signOut } = useAuth();

  useEffect(() => {
    if (status === "signed-out") router.replace("/sign-in");
    if (status === "signed-in") router.replace("/");
  }, [status, router]);

  if (status === "loading" || !user) {
    return (
      <main className="mx-auto grid min-h-screen max-w-prose place-items-center px-6">
        <Loading />
      </main>
    );
  }

  return (
    <main className="mx-auto grid min-h-screen max-w-prose content-start gap-6 px-6 py-14">
      <header className="grid gap-2">
        <h1 className="text-2xl font-semibold text-ink">Which temple do you serve at?</h1>
        <p className="text-ink-secondary">
          Choose the temple you serve at to finish. You can join others later.
        </p>
      </header>

      <JoinTempleForm onJoined={() => router.replace("/")} pickerLabel="Search for your temple" />

      <p className="text-sm text-ink-muted">
        {user.email ?? user.phoneNumber} — not you?{" "}
        <Button variant="ghost" size="sm" onClick={() => signOut()}>
          Use a different account
        </Button>
      </p>
    </main>
  );
}
