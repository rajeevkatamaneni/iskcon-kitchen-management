"use client";

import type { ReactNode } from "react";

/** The page frame: a 1200px content column, 32px gutters, 24px between blocks. */
export function Screen({ children }: { children: ReactNode }) {
  return <div className="mx-auto grid max-w-content gap-6 p-8">{children}</div>;
}
