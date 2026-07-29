# World server (MODERNIZATION.md Phase 2/3): the live world + the built web
# client, one image.
#   docker build -t bluproto .
#   docker run -p 7070:7070 -e SEED=42 -e COMMAND_TOKEN=secret bluproto

# 1 · Build the Vite/TS client -> client/dist.
FROM node:22-slim AS client
WORKDIR /client
COPY client/package.json client/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY client/ ./
RUN npm run build

# 2 · Build the server, folding the prebuilt client into its static resources.
FROM gradle:8.14-jdk21 AS build
WORKDIR /build
# Dependency layer first: copy only the build scripts and resolve dependencies,
# so this expensive download is a cache hit whenever only source changes (the
# layer is keyed on the build files, not the sources). Downloads land in the
# image's Gradle home, so the cached layer carries them into the build below.
COPY settings.gradle ./
COPY engine/build.gradle ./engine/
COPY server/build.gradle ./server/
RUN gradle :server:dependencies --no-daemon -q || true
# Sources, then the actual build (reuses the warm dependency cache above).
COPY . .
COPY --from=client /client/dist ./client/dist
RUN gradle :server:installDist --no-daemon -q

# 3 · Runtime.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/server/build/install/server/ ./
# Sprites and ground textures load from the filesystem-relative res/ dir.
COPY res/ ./res/
# Stamp the built commit so /api/health reports exactly what's live (set by
# the publish workflow; "dev" for a plain local build).
ARG GIT_SHA=dev
ENV PORT=7070 SEED=42 BUILD_VERSION=$GIT_SHA
# Cap the heap so the one-time layer bake fits a 2 GB VPS instead of OOM-ing
# (the default 25%-of-RAM heap is too small for baking the big map). Steady
# state is well under this; the ceiling only matters during the startup bake.
ENV JAVA_OPTS="-Xmx1024m"
EXPOSE 7070
CMD ["./bin/server"]
