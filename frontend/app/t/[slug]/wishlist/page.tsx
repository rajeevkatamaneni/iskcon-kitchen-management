"use client";

import { useParams } from "next/navigation";
import { WishListView } from "@/components/give/WishListView";

/** The public wish list: what a shared link opens. */
export default function PublicWishListPage() {
  const slug = useParams<{ slug: string }>().slug;
  return <WishListView slug={slug} />;
}
