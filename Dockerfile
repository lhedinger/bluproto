# World server (MODERNIZATION.md Phase 2): one live world behind HTTP+WS.
#   docker build -t bluproto .
#   docker run -p 7070:7070 -e SEED=42 bluproto

FROM gradle:8.14-jdk21 AS build
WORKDIR /build
COPY . .
RUN gradle :server:installDist --no-daemon -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/server/build/install/server/ ./
# Sprites and ground textures load from the filesystem-relative res/ dir.
COPY res/ ./res/
ENV PORT=7070 SEED=42
EXPOSE 7070
CMD ["./bin/server"]
