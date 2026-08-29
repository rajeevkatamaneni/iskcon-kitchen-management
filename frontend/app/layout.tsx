import type { Metadata } from "next";
import {
  Anek_Latin,
  Anek_Devanagari,
  Anek_Telugu,
  Anek_Tamil,
} from "next/font/google";
import { AuthProvider } from "@/lib/auth-context";
import { ThemeProvider } from "@/lib/theme-context";
import { THEME_PREPAINT_SCRIPT } from "@/lib/theme";
import { SessionGuard } from "@/components/SessionGuard";
import "./globals.css";

/**
 * Anek, by Ek Type (Mumbai). Ten scripts, all drawn simultaneously rather than one
 * adapted from another — so Latin, Devanagari, Telugu and Tamil share proportions,
 * weight and rhythm by design instead of merely coexisting.
 *
 * This matters more here than it would elsewhere. Recipes and purchase orders are
 * translated in Phase 1, so a single screen routinely carries an English label beside a
 * Hindi ingredient name. With two unrelated families that seam is visible; with Anek it
 * is not.
 *
 * Loaded per script because browsers resolve missing glyphs family by family: English
 * renders in Anek Latin, Devanagari picks up Anek Devanagari, and nothing in application
 * code ever switches fonts.
 */
const anekLatin = Anek_Latin({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-latin",
  display: "swap",
});

const anekDevanagari = Anek_Devanagari({
  subsets: ["devanagari"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-devanagari",
  display: "swap",
});

const anekTelugu = Anek_Telugu({
  subsets: ["telugu"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-telugu",
  display: "swap",
});

const anekTamil = Anek_Tamil({
  subsets: ["tamil"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-tamil",
  display: "swap",
});

export const metadata: Metadata = {
  title: "ISKCON Seva Kitchen",
  description: "Kitchen management for ISKCON temples",
  // The temple's own mark in the browser tab. An SVG so it stays crisp at every size a browser
  // asks for; checked at 16px, where the lotus still reads as a lotus.
  icons: { icon: "/brand/favicon.svg" },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const fontVariables = [
    anekLatin.variable,
    anekDevanagari.variable,
    anekTelugu.variable,
    anekTamil.variable,
  ].join(" ");

  return (
    <html lang="en" className={fontVariables}>
      <body>
        {/*
          The temple's colours, painted before anything else is. This runs synchronously ahead of
          React from the last palette this browser saw; without it every load of every page renders
          in the default terracotta and then repaints, which is the one flaw people actually notice
          in a themed application. ThemeProvider corrects it a moment later if whoami disagrees.
        */}
        <script dangerouslySetInnerHTML={{ __html: THEME_PREPAINT_SCRIPT }} />
        <AuthProvider>
          <ThemeProvider>
            {children}
            {/* Above every screen, so an idle shared device signs itself out wherever it was left. */}
            <SessionGuard />
          </ThemeProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
