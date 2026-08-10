import { type FirebaseApp, getApps, initializeApp } from "firebase/app";
import { type Auth, getAuth } from "firebase/auth";

/**
 * Firebase client initialisation.
 *
 * <p>Firebase authenticates; it does not authorise. A valid token proves someone controls an
 * email address or phone number and nothing more — which temple they belong to and what they
 * may do comes from our own user record, checked by the backend on every request. That
 * separation is why a temple can disable someone and have it take effect immediately, rather
 * than waiting for a token to expire.
 *
 * <p>Note the project here is not the main GCP project: Firebase could not reuse that name, so
 * Auth lives in its own project. Token verification is unaffected — the backend validates
 * against Google's public keys and checks the audience claim — but it does mean billing and
 * SMS quota are managed separately. See docs/DEPLOYMENT.md.
 */

const config = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};

/** True when the app has been configured. False locally if .env.local is missing. */
export const firebaseConfigured = Boolean(config.apiKey && config.projectId);

let app: FirebaseApp | undefined;

function getFirebaseApp(): FirebaseApp {
  if (!firebaseConfigured) {
    // Fails loudly rather than silently authenticating nobody. A misconfigured deployment
    // should be obviously broken, not quietly locked.
    throw new Error(
      "Firebase is not configured. Copy .env.local.example to .env.local."
    );
  }

  if (!app) {
    app = getApps().length ? getApps()[0] : initializeApp(config);
  }
  return app;
}

export function getFirebaseAuth(): Auth {
  return getAuth(getFirebaseApp());
}
