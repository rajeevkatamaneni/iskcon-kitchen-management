"use client";

import { useParams } from "next/navigation";
import { DonateFlow } from "@/components/give/DonateFlow";

/** The public donation page: what a shared link opens, for someone who may not have an account. */
export default function PublicDonatePage() {
  const slug = useParams<{ slug: string }>().slug;
  return <DonateFlow slug={slug} />;
}
