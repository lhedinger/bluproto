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
COPY . .
COPY --from=client /client/dist ./client/dist
RUN gradle :server:installDist --no-daemon -q

# 3 · Runtime.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/server/build/install/server/ ./
# Sprites and ground textures load from the filesystem-relative res/ dir.
COPY res/ ./res/
ENV PORT=7070 SEED=42
EXPOSE 7070
CMD ["./bin/server"]
