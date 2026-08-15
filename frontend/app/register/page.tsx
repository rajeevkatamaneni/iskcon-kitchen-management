"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import {
  createUserWithEmailAndPassword,
  RecaptchaVerifier,
  signInWithPhoneNumber,
  updateProfile,
  type ConfirmationResult,
} from "firebase/auth";
import { Button } from "@/components/ds/Button";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { JoinTempleForm } from "@/components/JoinTempleForm";
import { BusyPot, Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";
import { getFirebaseAuth } from "@/lib/firebase";

/**
 * Registering as a devotee (E1-S16).
 *
 * <p>Two halves, in the order they have to happen. First an identity — Google, an email and a
 * password, or a phone and a code — because a temple's list of people is a list of people who can
 * be reached and recognised. Then the temple itself, and the name and number it needs.
 *
 * <p>The second half is the same form as the one a Google sign-in with no temple lands on, because
 * it is the same question.
 */
export default function RegisterPage() {
  const router = useRouter();
  const { user, status, signInWithGoogle } = useAuth();

  if (status === "loading") {
    return (
      <main className="mx-auto grid min-h-screen max-w-prose place-items-center px-6">
        <Loading />
      </main>
    );
  }

  return (
    <main className="mx-auto grid min-h-screen max-w-prose content-start gap-6 px-6 py-14">
      <header className="grid gap-2">
        <h1 className="text-2xl font-semibold text-ink">Register</h1>
        <p className="text-ink-secondary">Serve at a temple, and give when you can.</p>
      </header>

      {user ? (
        <JoinTempleForm onJoined={() => router.replace("/")} submitLabel="Create my account" />
      ) : (
        <CreateIdentity onGoogle={signInWithGoogle} />
      )}

      <p className="text-sm text-ink-muted">
        Already registered? <Link href="/sign-in" className="text-accent-text hover:underline">Sign in</Link>
      </p>
    </main>
  );
}

/** The identity half: whichever of the three ways in suits them. */
function CreateIdentity({ onGoogle }: { onGoogle: () => Promise<void> }) {
  const [method, setMethod] = useState<"password" | "phone">("password");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function google() {
    setError(null);
    try {
      await onGoogle();
    } catch {
      setError("That didn't work. Try again, or use an email address instead.");
    }
  }

  return (
    <div className="grid gap-5">
      {error && <InlineNotice tone="warning">{error}</InlineNotice>}

      <Button variant="secondary" disabled={busy} onClick={google}>
        Continue with Google
      </Button>

      <div className="flex items-center gap-3 text-xs text-ink-muted">
        <span className="h-px flex-1 bg-hairline" />
        or
        <span className="h-px flex-1 bg-hairline" />
      </div>

      <div
        role="tablist"
        aria-label="How you'll sign in"
        className="flex gap-1 rounded-lg bg-sunken p-1"
      >
        {(["password", "phone"] as const).map((option) => (
          <button
            key={option}
            role="tab"
            aria-selected={method === option}
            onClick={() => setMethod(option)}
            className={[
              "min-h-touch flex-1 rounded px-3 text-sm transition-colors duration-state",
              method === option ? "bg-canvas font-medium text-ink shadow-sm" : "text-ink-secondary",
            ].join(" ")}
          >
            {option === "password" ? "Email and password" : "Phone and OTP"}
          </button>
        ))}
      </div>

      {method === "password" ? (
        <EmailPassword busy={busy} setBusy={setBusy} onError={setError} />
      ) : (
        <PhoneOtp busy={busy} setBusy={setBusy} onError={setError} />
      )}
    </div>
  );
}

function EmailPassword({
  busy, setBusy, onError,
}: {
  busy: boolean;
  setBusy: (b: boolean) => void;
  onError: (message: string | null) => void;
}) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");

  async function create() {
    onError(null);
    setBusy(true);
    try {
      const credential = await createUserWithEmailAndPassword(getFirebaseAuth(), email.trim(), password);
      if (name.trim()) {
        // Carried into the next step, where it fills the name fields in rather than asking twice.
        await updateProfile(credential.user, { displayName: name.trim() });
      }
    } catch (e) {
      onError(readableFirebaseError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-3">
      <label className="grid gap-1 text-sm text-ink-secondary">
        Your name
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
        />
      </label>
      <label className="grid gap-1 text-sm text-ink-secondary">
        Email address
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
        />
      </label>
      <label className="grid gap-1 text-sm text-ink-secondary">
        Create a password
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
        />
        <span className="text-xs text-ink-muted">At least eight characters.</span>
      </label>
      <Button disabled={busy || !email.trim() || password.length < 8} onClick={create}>
        {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Creating…</span>) : "Continue"}
      </Button>
    </div>
  );
}

function PhoneOtp({
  busy, setBusy, onError,
}: {
  busy: boolean;
  setBusy: (b: boolean) => void;
  onError: (message: string | null) => void;
}) {
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [pending, setPending] = useState<ConfirmationResult | null>(null);

  async function sendCode() {
    onError(null);
    setBusy(true);
    try {
      const auth = getFirebaseAuth();
      // Firebase requires a reCAPTCHA anchor for phone auth; invisible, so nobody is asked to
      // identify a traffic light before they can offer their seva.
      const verifier = new RecaptchaVerifier(auth, "recaptcha-anchor", { size: "invisible" });
      setPending(await signInWithPhoneNumber(auth, phone.trim(), verifier));
    } catch (e) {
      onError(readableFirebaseError(e));
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (!pending) return;
    onError(null);
    setBusy(true);
    try {
      await pending.confirm(code.trim());
    } catch {
      onError("That code didn't match. Check it and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-3">
      {!pending ? (
        <>
          <label className="grid gap-1 text-sm text-ink-secondary">
            Phone number
            <input
              type="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="+91 98765 43210"
              className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
            />
            <span className="text-xs text-ink-muted">With the country code.</span>
          </label>
          <Button disabled={busy || phone.trim().length < 8} onClick={sendCode}>
            {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Sending…</span>) : "Send me a code"}
          </Button>
        </>
      ) : (
        <>
          <label className="grid gap-1 text-sm text-ink-secondary">
            The six-digit code we sent to {phone}
            <input
              inputMode="numeric"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              className="min-h-touch rounded border border-hairline bg-canvas px-3 text-lg tabular-nums text-ink"
            />
          </label>
          <Button disabled={busy || code.trim().length < 6} onClick={confirm}>
            {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Checking…</span>) : "Confirm"}
          </Button>
        </>
      )}
      <div id="recaptcha-anchor" />
    </div>
  );
}

/** Firebase's codes are for us; this is for the person reading the screen. */
function readableFirebaseError(e: unknown): string {
  const code = typeof e === "object" && e && "code" in e ? String((e as { code: unknown }).code) : "";
  if (code.includes("email-already-in-use")) {
    return "There is already an account with that email. Sign in instead.";
  }
  if (code.includes("weak-password")) return "Choose a longer password — at least eight characters.";
  if (code.includes("invalid-email")) return "That email address doesn't look right.";
  if (code.includes("invalid-phone-number")) return "That number doesn't look right. Include the country code.";
  if (code.includes("too-many-requests")) return "Too many attempts just now. Wait a minute and try again.";
  return "That didn't work. Check what you entered and try again.";
}
