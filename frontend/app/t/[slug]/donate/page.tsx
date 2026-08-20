"use client";

import { useParams } from "next/navigation";
import { DonatePage } from "@/components/give/DonatePage";

/** The public donation page: what a shared link opens, for someone who may not have an account. */
export default function PublicDonatePage() {
  const slug = useParams<{ slug: string }>().slug;
  // The whole window: a stranger opened a shared link, so this page carries its own banner.
  return <DonatePage slug={slug} standalone />;
}
