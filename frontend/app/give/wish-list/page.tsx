"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";

/** The temple's wish list, reached from a devotee's own menu rather than from a shared link. */
export default function WishListRedirect() {
  const router = useRouter();
  const { appUser, status } = useAuth();

  useEffect(() => {
    if (status === "signed-out") router.replace("/sign-in");
    if (appUser?.tenantSlug) router.replace(`/t/${appUser.tenantSlug}/wishlist`);
  }, [status, appUser, router]);

  return (
    <main className="mx-auto grid min-h-screen max-w-prose place-items-center px-6">
      <Loading label="Opening your temple's wish list…" />
    </main>
  );
}
