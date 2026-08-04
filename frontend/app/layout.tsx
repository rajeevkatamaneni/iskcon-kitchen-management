import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "ISKCON Seva Kitchen",
  description: "Kitchen Management System for ISKCON temples",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
