import type { Config } from "tailwindcss";

// Minimal accent palette per REQUIREMENTS.md's design mandate: minimal color,
// used only to direct attention (CTAs, alerts, active nav) — not decoration.
const config: Config = {
  content: ["./app/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        accent: {
          DEFAULT: "#E07A1F",
          soft: "#FDEEE0",
        },
      },
    },
  },
  plugins: [],
};

export default config;
