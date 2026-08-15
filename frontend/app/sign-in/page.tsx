"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  RecaptchaVerifier,
  signInWithEmailAndPassword,
  signInWithPhoneNumber,
  type ConfirmationResult,
} from "firebase/auth";
import { Field } from "@/components/Field";
import { useAuth } from "@/lib/auth-context";
import { getFirebaseAuth, firebaseConfigured } from "@/lib/firebase";
import { IDLE_LIMIT_MS, takeAutomaticSignOutNote } from "@/lib/session-timeout";
import { BusyPot } from "@/components/Loading";

/**
 * Sign in.
 *
 * <p>Two methods, deliberately. Email suits staff and administrators; phone OTP matters because
 * many devotees in India live on a phone number rather than an email address, and asking them
 * to remember a password for an app they open twice a year is a good way to lose them.
 *
 * <p>Errors here are written the same way as everywhere else — what happened, what to do — but
 * they are deliberately vague about *which* credential was wrong. Saying "no account with that
 * email" tells anyone who asks which addresses are registered at a temple.
 */
export default function SignInPage() {
  const router = useRouter();
  const { signInWithGoogle } = useAuth();
  const [method, setMethod] = useState<"email" | "phone">("email");
  const [googleError, setGoogleError] = useState<string | null>(null);

  // Why they are here, when the app sent them (E1-S16 D6). Read after mount — localStorage does not
  // exist during the server render — and cleared as it is read, so it explains this sign-in only.
  const [signedOutForIdling, setSignedOutForIdling] = useState(false);
  useEffect(() => {
    setSignedOutForIdling(takeAutomaticSignOutNote(Date.now()));
  }, []);

  async function handleGoogle() {
    setGoogleError(null);
    try {
      await signInWithGoogle();
      router.push("/");
    } catch {
      // Includes the case where Google sign-in has not been enabled for the project yet.
      setGoogleError("Google sign-in isn't available right now. Use email or phone instead.");
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-prose flex-col justify-center px-6 py-12">
      <header className="mb-8">
        <h1>Sign in</h1>
        <p className="mt-1 text-ink-secondary">ISKCON Seva Kitchen</p>
      </header>

      {signedOutForIdling && (
        <div className="mb-6 rounded border border-accent-border bg-accent-bg p-4 text-accent-text">
          <p className="font-medium">We signed you out</p>
          <p className="mt-1 text-sm">
            Nothing was wrong — the app signs you out after {IDLE_LIMIT_MS / 60000} minutes without
            activity, so a shared device doesn&rsquo;t stay open as you. Sign in to carry on.
          </p>
        </div>
      )}

      {!firebaseConfigured && (
        <div className="mb-6 rounded border border-warning/20 bg-warning-bg p-4 text-warning">
          <p className="font-medium">Sign-in isn&rsquo;t configured on this environment.</p>
          <p className="mt-1 text-sm">
            Copy <span className="font-mono">.env.local.example</span> to{" "}
            <span className="font-mono">.env.local</span> and restart.
          </p>
        </div>
      )}

      {googleError && (
        <div role="alert" className="mb-4 rounded border border-danger/20 bg-danger-bg p-4 text-danger">
          {googleError}
        </div>
      )}

      {/*
        Accent-tinted, not solid olive: the same treatment the active nav item uses. It makes the
        recommended passwordless path the eye-catching option without claiming the screen's one
        solid-accent primary slot, which stays with the email/phone submit.
      */}
      <button
        type="button"
        onClick={handleGoogle}
        disabled={!firebaseConfigured}
        className="mb-6 min-h-touch w-full rounded border border-accent-border bg-accent-bg font-medium text-accent-text transition-colors duration-state hover:bg-accent-border disabled:opacity-60"
      >
        Continue with Google
      </button>

      <div className="mb-6 flex items-center gap-3 text-sm text-ink-muted">
        <span className="h-px flex-1 bg-hairline" />
        or
        <span className="h-px flex-1 bg-hairline" />
      </div>

      <div
        role="tablist"
        aria-label="Sign-in method"
        className="mb-6 flex gap-1 rounded-sm bg-sunken p-1"
      >
        {(["email", "phone"] as const).map((option) => (
          <button
            key={option}
            role="tab"
            aria-selected={method === option}
            onClick={() => setMethod(option)}
            className={[
              "min-h-touch flex-1 rounded-sm text-sm transition-colors duration-state",
              method === option
                ? "bg-canvas font-medium text-ink"
                : "text-ink-secondary hover:text-ink",
            ].join(" ")}
          >
            {option === "email" ? "Email" : "Phone"}
          </button>
        ))}
      </div>

      {method === "email" ? (
        <EmailSignIn onSignedIn={() => router.push("/")} />
      ) : (
        <PhoneSignIn onSignedIn={() => router.push("/")} />
      )}
    </main>
  );
}

function EmailSignIn({ onSignedIn }: { onSignedIn: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);

    const form = new FormData(event.currentTarget);

    try {
      await signInWithEmailAndPassword(
        getFirebaseAuth(),
        String(form.get("email")),
        String(form.get("password"))
      );
      onSignedIn();
    } catch {
      // Deliberately does not distinguish wrong password from unknown account.
      setError("That email address and password don't match. Check both and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-5">
      {error && (
        <div role="alert" className="rounded border border-danger/20 bg-danger-bg p-4 text-danger">
          {error}
        </div>
      )}

      <Field id="email" label="Email address" required>
        {(props) => <input {...props} name="email" type="email" autoComplete="email" />}
      </Field>

      <Field id="password" label="Password" required>
        {(props) => (
          <input {...props} name="password" type="password" autoComplete="current-password" />
        )}
      </Field>

      <button
        type="submit"
        disabled={busy || !firebaseConfigured}
        className="min-h-touch w-full rounded bg-accent px-6 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
      >
        {busy ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}

function PhoneSignIn({ onSignedIn }: { onSignedIn: () => void }) {
  const [confirmation, setConfirmation] = useState<ConfirmationResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function sendCode(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);

    const phone = String(new FormData(event.currentTarget).get("phone"));

    try {
      const auth = getFirebaseAuth();

      // Firebase requires a reCAPTCHA check before sending an SMS. Invisible, but it must be
      // attached to a real element — if OTP silently fails, this is the first thing to check.
      const verifier = new RecaptchaVerifier(auth, "recaptcha-container", {
        size: "invisible",
      });

      setConfirmation(await signInWithPhoneNumber(auth, phone, verifier));
    } catch {
      setError(
        "We couldn't send a code to that number. Check the country code and try again."
      );
    } finally {
      setBusy(false);
    }
  }

  async function verifyCode(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);

    const code = String(new FormData(event.currentTarget).get("code"));

    try {
      await confirmation!.confirm(code);
      onSignedIn();
    } catch {
      setError("That code isn't right. Check it and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {error && (
        <div
          role="alert"
          className="mb-5 rounded border border-danger/20 bg-danger-bg p-4 text-danger"
        >
          {error}
        </div>
      )}

      {confirmation === null ? (
        <form onSubmit={sendCode} className="space-y-5">
          <Field
            id="phone"
            label="Phone number"
            hint="Include the country code, for example +919876543210."
            required
          >
            {(props) => <input {...props} name="phone" type="tel" autoComplete="tel" />}
          </Field>

          <button
            type="submit"
            disabled={busy || !firebaseConfigured}
            className="min-h-touch w-full rounded bg-accent px-6 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
          >
            {busy ? "Sending code…" : "Send code"}
          </button>
        </form>
      ) : (
        <form onSubmit={verifyCode} className="space-y-5">
          <Field id="code" label="Code" hint="We sent a six-digit code by SMS." required>
            {(props) => (
              <input
                {...props}
                name="code"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
              />
            )}
          </Field>

          <button
            type="submit"
            disabled={busy}
            className="min-h-touch w-full rounded bg-accent px-6 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
          >
            {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Checking…</span>) : "Sign in"}
          </button>

          <button
            type="button"
            onClick={() => setConfirmation(null)}
            className="min-h-touch w-full rounded border border-hairline-strong transition-colors duration-state hover:bg-raised"
          >
            Use a different number
          </button>
        </form>
      )}

      <div id="recaptcha-container" />
    </>
  );
}
