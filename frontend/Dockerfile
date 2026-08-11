# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Dependencies
# ---------------------------------------------------------------------------
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# NEXT_PUBLIC_* values are inlined at build time, so anything the browser needs
# must be supplied here rather than only at runtime.
#
# The API URL changes per deploy, so it is passed as a build-arg (see
# infra/deploy.sh / cloudbuild.yaml). The Firebase web config does not change per
# deploy and is NOT secret — these values ship in browser JS by design (security
# is Firebase's own rules plus the backend verifying every token). They default to
# the staging Firebase project here so a plain `docker build` produces a working
# sign-in; override the build-args for a different Firebase project.
ARG NEXT_PUBLIC_API_URL=""
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL

ARG NEXT_PUBLIC_FIREBASE_API_KEY="AIzaSyCE3ZhZQnncECDaieQCu0wp2DP-Biosf5I"
ENV NEXT_PUBLIC_FIREBASE_API_KEY=$NEXT_PUBLIC_FIREBASE_API_KEY
ARG NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN="iskcon-kms-2026-620ee.firebaseapp.com"
ENV NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=$NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN
ARG NEXT_PUBLIC_FIREBASE_PROJECT_ID="iskcon-kms-2026-620ee"
ENV NEXT_PUBLIC_FIREBASE_PROJECT_ID=$NEXT_PUBLIC_FIREBASE_PROJECT_ID
ARG NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET="iskcon-kms-2026-620ee.firebasestorage.app"
ENV NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET=$NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET
ARG NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID="970623926730"
ENV NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID=$NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID
ARG NEXT_PUBLIC_FIREBASE_APP_ID="1:970623926730:web:db50c68f75ad9d5de58f33"
ENV NEXT_PUBLIC_FIREBASE_APP_ID=$NEXT_PUBLIC_FIREBASE_APP_ID

ENV NEXT_TELEMETRY_DISABLED=1

RUN npm run build

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

RUN addgroup --system --gid 1001 nodejs \
    && adduser --system --uid 1001 nextjs

COPY --from=build /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs

ENV PORT=8080
ENV HOSTNAME=0.0.0.0
EXPOSE 8080

CMD ["node", "server.js"]
