"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import {
  signOut as signOutFirebase,
  createUserWithEmailAndPassword,
  GoogleAuthProvider,
  RecaptchaVerifier,
  signInWithPhoneNumber,
  signInWithPopup,
  updateProfile,
  type ConfirmationResult,
  type User,
} from "firebase/auth";
import { Button } from "@/components/ds/Button";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { BusyPot } from "@/components/Loading";
import { ErrorNotice } from "@/components/ErrorNotice";
import { TemplePicker } from "@/components/TemplePicker";
import {
  api,
  setActiveTempleId,
  toApiError,
  type ApiError,
  type TempleSummary,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { getFirebaseAuth } from "@/lib/firebase";

/**
 * Registering as a devotee (E1-S17): one form, in the order it was drawn.
 *
 * <p>Temple, then who you are, then how you would like to sign in — and one button at the end that
 * does all of it. The first version of this screen asked for a credential first and the temple
 * afterwards, which was easier to build and wrong in every way that mattered: it created a Firebase
 * account before the person had finished, told them they were "signed in as" someone while they
 * were still filling the form in, and left half-made accounts behind when they stopped. Nothing is
 * created here until the whole form is answered.
 */

type Method = "password" | "phone" | "google";

export default function RegisterPage() {
  const router = useRouter();
  const { refresh } = useAuth();

  const [temple, setTemple] = useState<TempleSummary | null>(null);
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [method, setMethod] = useState<Method>("password");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [code, setCode] = useState("");
  const [pendingCode, setPendingCode] = useState<ConfirmationResult | null>(null);

  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const phoneOk = /^\+[1-9][0-9]{7,14}$/.test(phone.trim());
  const detailsOk = Boolean(temple && firstName.trim() && lastName.trim() && email.trim() && phoneOk);
  const passwordsMatch = password.length >= 8 && password === confirmPassword;
  const credentialOk =
    method === "google" ? true : method === "password" ? passwordsMatch : pendingCode !== null;

  async function createAccount() {
    if (!temple) return;
    setBusy(true);
    setMessage(null);
    setError(null);

    try {
      const auth = getFirebaseAuth();
      let credential: User;

      if (method === "google") {
        credential = (await signInWithPopup(auth, new GoogleAuthProvider())).user;
      } else if (method === "password") {
        credential = (await createUserWithEmailAndPassword(auth, email.trim(), password)).user;
      } else {
        if (!pendingCode) return;
        credential = (await pendingCode.confirm(code.trim())).user;
      }

      if (!credential.displayName) {
        await updateProfile(credential, { displayName: `${firstName.trim()} ${lastName.trim()}` });
      }

      await api.joinTemple(
        temple.id,
        {
          firstName: firstName.trim(),
          lastName: lastName.trim(),
          phone: phone.trim(),
          email: email.trim() || null,
        },
        await credential.getIdToken()
      );

      setActiveTempleId(temple.id);

      if (method === "password") {
        // Signed out on purpose, and only here: a password is the one credential that can be
        // mistyped into existence, so the first thing it ever does is let them back in. Google and
        // a phone code carry no such risk — and a second OTP costs a message and a wait — so those
        // two stay signed in.
        await signOutFirebase(getFirebaseAuth());
        router.replace(`/sign-in?registered=${encodeURIComponent(email.trim())}`);
        return;
      }

      await refresh();
      router.replace("/");
    } catch (e) {
      const firebase = readableFirebaseError(e);
      if (firebase) setMessage(firebase);
      else setError(toApiError(e, "We couldn't complete your registration."));
      setBusy(false);
    }
  }

  async function sendCode() {
    setMessage(null);
    setBusy(true);
    try {
      const auth = getFirebaseAuth();
      // Firebase requires a reCAPTCHA anchor for phone auth; invisible, so nobody is asked to
      // identify a traffic light before they can offer their seva.
      const verifier = new RecaptchaVerifier(auth, "recaptcha-anchor", { size: "invisible" });
      setPendingCode(await signInWithPhoneNumber(auth, phone.trim(), verifier));
    } catch (e) {
      setMessage(readableFirebaseError(e) ?? "We couldn't send that code. Check the number.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto grid min-h-screen max-w-prose content-start gap-6 px-6 py-14">
      <header className="grid gap-2">
        <h1 className="text-2xl font-semibold text-ink">Register</h1>
        <p className="text-ink-secondary">
          Join your temple as a volunteer — offer seva, and give when you can.
        </p>
      </header>

      {error && <ErrorNotice error={error} />}
      {message && <InlineNotice tone="warning">{message}</InlineNotice>}

      <TemplePicker token={undefined} value={temple} onChange={setTemple} />

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="grid gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">First name</span>
          <input
            autoComplete="given-name"
            value={firstName}
            onChange={(e) => setFirstName(e.target.value)}
            className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
          />
        </label>
        <label className="grid gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Last name</span>
          <input
            autoComplete="family-name"
            value={lastName}
            onChange={(e) => setLastName(e.target.value)}
            className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
          />
        </label>
      </div>

      <label className="grid gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Email</span>
        <input
          type="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
        />
      </label>

      <label className="grid gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Phone</span>
        <input
          type="tel"
          autoComplete="tel"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          placeholder="+91 98765 43210"
          className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
        />
        <span className="pl-field-inset text-xs text-ink-muted">
          With the country code. The kitchen reaches volunteers by phone.
        </span>
      </label>

      <fieldset className="grid gap-3">
        <legend className="mb-1 text-sm text-ink-secondary">How would you like to sign in?</legend>
        <div role="tablist" aria-label="How you'll sign in" className="flex gap-1 rounded-lg bg-sunken p-1">
          {(
            [
              ["password", "Password"],
              ["phone", "Phone and OTP"],
              ["google", "Google"],
            ] as const
          ).map(([option, label]) => (
            <button
              key={option}
              role="tab"
              type="button"
              aria-selected={method === option}
              onClick={() => setMethod(option)}
              className={[
                "min-h-touch flex-1 rounded px-3 text-sm transition-colors duration-state",
                method === option ? "bg-canvas font-medium text-ink" : "text-ink-secondary",
              ].join(" ")}
            >
              {label}
            </button>
          ))}
        </div>

        {method === "password" && (
          <div className="grid gap-3">
            <label className="grid gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Create a password</span>
              <span className="relative flex">
                <input
                  type={showPassword ? "text" : "password"}
                  // A password being created, not one being recalled: without this the browser
                  // offers an existing saved password on a registration form.
                  autoComplete="new-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="min-h-touch w-full rounded border border-hairline bg-canvas px-3 pr-20 text-ink"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((shown) => !shown)}
                  aria-pressed={showPassword}
                  className="absolute inset-y-0 right-0 px-3 text-sm text-accent-text hover:underline"
                >
                  {showPassword ? "Hide" : "Show"}
                </button>
              </span>
              <span className="pl-field-inset text-xs text-ink-muted">At least eight characters.</span>
            </label>

            <label className="grid gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Confirm password</span>
              <input
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
              />
              {confirmPassword.length > 0 && password !== confirmPassword && (
                <span className="pl-field-inset text-xs text-danger">Those two don&rsquo;t match yet.</span>
              )}
            </label>
          </div>
        )}

        {method === "phone" && (
          <div className="grid gap-2">
            {!pendingCode ? (
              <Button variant="secondary" disabled={busy || !phoneOk} onClick={sendCode}>
                {busy ? (
                  <span className="inline-flex items-center gap-2"><BusyPot />Sending…</span>
                ) : (
                  "Send a code to my phone"
                )}
              </Button>
            ) : (
              <label className="grid gap-1 text-sm text-ink-secondary">
                <span className="pl-field-inset font-medium text-ink">The six-digit code we sent to {phone}</span>
                <input
                  inputMode="numeric"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  className="min-h-touch rounded border border-hairline bg-canvas px-3 text-lg tabular-nums text-ink"
                />
              </label>
            )}
          </div>
        )}

        {method === "google" && (
          <p className="text-sm text-ink-muted">
            You&rsquo;ll be asked to choose your Google account when you finish.
          </p>
        )}
      </fieldset>

      <Button disabled={busy || !detailsOk || !credentialOk} onClick={createAccount}>
        {busy ? (
          <span className="inline-flex items-center gap-2"><BusyPot />Creating your account…</span>
        ) : (
          "Create my account"
        )}
      </Button>

      {!detailsOk && (
        <p className="-mt-3 text-sm text-ink-muted">
          {!temple
            ? "Choose your temple to continue."
            : !firstName.trim() || !lastName.trim()
              ? "Enter your name."
              : !email.trim()
                ? "Enter your email address."
                : "Enter your phone number, with the country code."}
        </p>
      )}

      <p className="text-sm text-ink-muted">
        Already registered?{" "}
        <Link href="/sign-in" className="text-accent-text hover:underline">
          Sign in
        </Link>
      </p>

      <div id="recaptcha-anchor" />
    </main>
  );
}

/** Firebase's codes are for us; this is for the person reading the screen. */
function readableFirebaseError(e: unknown): string | null {
  const code = typeof e === "object" && e && "code" in e ? String((e as { code: unknown }).code) : "";
  if (!code.startsWith("auth/")) return null;
  if (code.includes("email-already-in-use")) {
    return "There is already an account with that email. Sign in instead.";
  }
  if (code.includes("weak-password")) return "Choose a longer password — at least eight characters.";
  if (code.includes("invalid-email")) return "That email address doesn't look right.";
  if (code.includes("invalid-phone-number")) return "That number doesn't look right. Include the country code.";
  if (code.includes("invalid-verification-code")) return "That code didn't match. Check it and try again.";
  if (code.includes("popup-closed-by-user")) return "The Google window closed before you finished.";
  if (code.includes("too-many-requests")) return "Too many attempts just now. Wait a minute and try again.";
  return "That didn't work. Check what you entered and try again.";
}
