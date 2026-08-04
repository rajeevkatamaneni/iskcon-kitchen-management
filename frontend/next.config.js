/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Emits a minimal self-contained server bundle so the runtime container
  // doesn't need node_modules — smaller image, faster Cloud Run cold starts.
  output: "standalone",
};

module.exports = nextConfig;
