/** @type {import('next').NextConfig} */

// Local development against a deployed backend. Set KMS_DEV_API_PROXY to an API base URL
// (e.g. the staging Cloud Run API) and leave NEXT_PUBLIC_API_URL empty: the browser then
// calls this dev server same-origin and Next proxies /api through, so no CORS entry has to
// be added to a shared environment just to run the UI on a laptop. Unset in every build.
const devApiProxy = process.env.KMS_DEV_API_PROXY;

const nextConfig = {
  reactStrictMode: true,
  // Emits a minimal self-contained server bundle so the runtime container
  // doesn't need node_modules — smaller image, faster Cloud Run cold starts.
  output: "standalone",
  // /order-list was the shopping list until the name was corrected (OL1). Permanent, because
  // the old path is never coming back — but it stays, because somebody has it bookmarked and a
  // 404 is a worse way to learn a screen was renamed.
  async redirects() {
    return [{ source: "/order-list", destination: "/shopping-list", permanent: true }];
  },
  ...(devApiProxy
    ? {
        async rewrites() {
          return [{ source: "/api/:path*", destination: `${devApiProxy}/api/:path*` }];
        },
      }
    : {}),
};

module.exports = nextConfig;
