import type { Metadata } from "next";
import {
  Anek_Latin,
  Anek_Devanagari,
  Anek_Telugu,
  Anek_Tamil,
  Anek_Kannada,
  Anek_Bangla,
  Anek_Gujarati,
  Anek_Malayalam,
  Anek_Odia,
  Anek_Gurmukhi,
  Noto_Sans_Ol_Chiki,
  Noto_Sans_Meetei_Mayek,
  Noto_Nastaliq_Urdu,
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

/*
 * The six Anek scripts that were never loaded, added 2026-09-21.
 *
 * The application offers 22 scheduled languages (`Languages.SCHEDULED`), which between them need 13
 * scripts. Four were loaded. **Kannada was not one of them** — the temple is in Bengaluru, the app
 * translates to Kannada, and that text was being drawn by whatever font the reader's machine
 * happened to have. Anek had all six of these the whole time; nobody had added the import.
 *
 * `preload: false` on every non-Latin family, deliberately. A self-hosted @font-face is only
 * fetched when something on the page actually matches it, so declaring thirteen families costs
 * nothing until a screen carries that script — but `preload: true` would put all thirteen in the
 * document head on every page load, which is the opposite of what this is for. Latin keeps its
 * preload because every screen uses it.
 */
const anekKannada = Anek_Kannada({
  subsets: ["kannada"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-kannada",
  display: "swap",
  preload: false,
});

const anekBangla = Anek_Bangla({
  subsets: ["bengali"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-bangla",
  display: "swap",
  preload: false,
});

const anekGujarati = Anek_Gujarati({
  subsets: ["gujarati"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-gujarati",
  display: "swap",
  preload: false,
});

const anekMalayalam = Anek_Malayalam({
  subsets: ["malayalam"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-malayalam",
  display: "swap",
  preload: false,
});

const anekOdia = Anek_Odia({
  subsets: ["oriya"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-odia",
  display: "swap",
  preload: false,
});

const anekGurmukhi = Anek_Gurmukhi({
  subsets: ["gurmukhi"],
  weight: ["400", "500", "600"],
  variable: "--font-anek-gurmukhi",
  display: "swap",
  preload: false,
});

/*
 * The three scripts Anek does not draw at all, so that nothing can render as an empty box.
 *
 * Santali is written in Ol Chiki, Manipuri in Meetei Mayek, and Urdu, Kashmiri and Sindhi in
 * Nastaliq. Verified against Google Fonts on 2026-09-21: there is no Anek family for any of the
 * three. Without these a machine with no such system font shows tofu — and a downloaded job card
 * carries whatever the browser found, so the vendor's copy and the temple's copy would differ.
 */
const notoOlChiki = Noto_Sans_Ol_Chiki({
  subsets: ["ol-chiki"],
  weight: ["400"],
  variable: "--font-noto-ol-chiki",
  display: "swap",
  preload: false,
});

const notoMeeteiMayek = Noto_Sans_Meetei_Mayek({
  subsets: ["meetei-mayek"],
  weight: ["400"],
  variable: "--font-noto-meetei-mayek",
  display: "swap",
  preload: false,
});

const notoNastaliqUrdu = Noto_Nastaliq_Urdu({
  subsets: ["arabic"],
  weight: ["400"],
  variable: "--font-noto-nastaliq",
  display: "swap",
  preload: false,
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
    anekKannada.variable,
    anekBangla.variable,
    anekGujarati.variable,
    anekMalayalam.variable,
    anekOdia.variable,
    anekGurmukhi.variable,
    notoOlChiki.variable,
    notoMeeteiMayek.variable,
    notoNastaliqUrdu.variable,
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
