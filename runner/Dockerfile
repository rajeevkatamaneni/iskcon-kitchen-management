# Kitchen Assistant runner (E10-T9). Built and deployed by S6; this file only has to be right.
#
# Two stages so the image carries the compiled server, the pinned CLI and the prompts, and not the
# TypeScript compiler. Runs as a non-root user whose home is empty: no $HOME/.claude is copied in,
# so the only credential a run can use is the one configured at deploy time (ANTHROPIC_API_KEY or
# CLAUDE_CODE_OAUTH_TOKEN, from Secret Manager). With neither set, runs fail on purpose.

FROM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npx tsc -p tsconfig.build.json && npm prune --omit=dev

FROM node:22-slim
ENV NODE_ENV=production PORT=8090
RUN useradd --create-home --home-dir /home/runner --shell /usr/sbin/nologin runner
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
COPY prompts ./prompts
# Fail the build, not the first request, if the pinned CLI did not install for this platform.
RUN node_modules/.bin/claude --version
USER runner
EXPOSE 8090
CMD ["node", "dist/src/server.js"]
