package net.hedinger.prototype.server;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.http.staticfiles.Location;

import net.hedinger.prototype.sim.SimCommand;
import net.hedinger.prototype.sim.SpawnItemCommand;

/**
 * The world server (MODERNIZATION.md Phase 2): one live, deterministic world
 * behind a small HTTP+WebSocket surface.
 *
 * <pre>
 *   GET  /                      debug viewer page (Phase 3 replaces it)
 *   GET  /api/health            liveness + current tick
 *   GET  /api/world             world info (seed, geometry, tick, viewers)
 *   POST /api/world/reset       fresh world from {"seed": n}
 *   GET  /api/world/layers/{z}.png   baked static ground layer for level z
 *   WS   /api/world/stream      hello+full on connect, ~10 Hz deltas after;
 *                               accepts {"cmd": ...} messages (see below)
 * </pre>
 *
 * WebSocket commands: {@code spawnItem} (kind food|crate|hazard, x, y) mutates
 * the world through the tick-boundary command queue — logged, deterministic,
 * replayable; {@code pause}/{@code resume}/{@code speed} shape wall-clock
 * pacing only; {@code reset} rebuilds the world from a seed.
 *
 * <p>Config via env or args: {@code PORT} (default 7070), {@code SEED}
 * (default 42).
 */
public final class ServerMain {

	public static void main(String[] args) {
		long seed = Long.parseLong(cfg(args, "SEED", "42"));
		int port = Integer.parseInt(cfg(args, "PORT", "7070"));
		// Public-deploy gate (MODERNIZATION.md Phase 5): when COMMAND_TOKEN is
		// set, viewing stays open but every mutating verb — WS commands and the
		// reset endpoint — requires the token. Unset (local dev) means open.
		String token = cfg(args, "COMMAND_TOKEN", "");
		// Built commit (set by the publish workflow -> Docker GIT_SHA); lets
		// /api/health report exactly which commit is live, so a deploy is
		// verifiable from outside. "dev" for a plain local run. Shown short.
		String commit = cfg(args, "BUILD_VERSION", "dev");
		String commitShort = commit.length() > 7 ? commit.substring(0, 7) : commit;
		// When this instance started running = when it was (re)deployed. Read
		// once at startup, formatted human-readable in UTC.
		String deployedAt = java.time.format.DateTimeFormatter
				.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
				.format(java.time.Instant
						.ofEpochMilli(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime())
						.atZone(java.time.ZoneOffset.UTC));

		WorldHost host = new WorldHost(seed);

		Javalin app = Javalin.create(c -> {
			c.showJavalinBanner = false;
			// The real client (client/dist, built by `npm run build`) wins when
			// present; the bundled Phase 2 debug page remains the fallback so the
			// server is usable straight from a bare checkout. Handlers resolve in
			// the order added.
			String clientDir = cfg(args, "CLIENT_DIR", "client/dist");
			if (java.nio.file.Files.isDirectory(java.nio.file.Path.of(clientDir))) {
				c.staticFiles.add(clientDir, Location.EXTERNAL);
				System.out.println("serving web client from " + clientDir);
			}
			c.staticFiles.add("/public", Location.CLASSPATH);
		});

		app.get("/api/health", ctx ->
				ctx.json(Map.of("ok", true, "tick", host.runner().snapshot().tick(),
						"entities", host.runner().snapshot().entities().size(),
						"commit", commitShort, "deployedAt", deployedAt)));

		// Ops metrics: sim cost, world size, viewers, uptime, heap.
		app.get("/api/metrics", ctx -> ctx.json(host.metrics()));

		// Prometheus text exposition of the same numbers, for scraping.
		app.get("/metrics", ctx -> {
			StringBuilder b = new StringBuilder();
			host.metrics().forEach((k, v) -> {
				if (v instanceof Number || v instanceof Boolean) {
					double num = v instanceof Boolean bool ? (bool ? 1 : 0) : ((Number) v).doubleValue();
					b.append("bluproto_").append(k).append(' ').append(num).append('\n');
				}
			});
			ctx.contentType("text/plain; version=0.0.4").result(b.toString());
		});

		// Download the current session as a replayable recording (seed + log).
		app.get("/api/world/recording", ctx ->
				ctx.header("Content-Disposition", "attachment; filename=recording.json")
						.json(host.recording()));

		// Replay/reconstruct: rebuild the world at any past tick. GET replays the
		// live session's recording; POST replays an uploaded one.
		app.get("/api/replay", ctx -> {
			long tick = ctx.queryParamAsClass("tick", Long.class).getOrDefault(Long.MAX_VALUE);
			ctx.json(host.replay(host.recording(), tick));
		});
		app.post("/api/replay", ctx -> {
			net.hedinger.prototype.sim.Recording rec =
					Protocol.JSON.readValue(ctx.body(), net.hedinger.prototype.sim.Recording.class);
			long tick = ctx.queryParamAsClass("tick", Long.class).getOrDefault(Long.MAX_VALUE);
			ctx.json(host.replay(rec, tick));
		});

		app.get("/api/world", ctx -> {
			var w = host.runner().world();
			var s = host.runner().snapshot();
			ctx.json(Map.of(
					"seed", host.seed(),
					"cols", w.getColums(),
					"rows", w.getRows(),
					"levels", w.getLevels(),
					"tileSize", net.hedinger.prototype.engine.ResourceManager.tileSize,
					"tick", s.tick(),
					"entities", s.entities().size(),
					"paused", host.runner().isPaused(),
					"speed", host.runner().getSpeed(),
					"viewers", host.viewers()));
		});

		app.post("/api/world/reset", ctx -> {
			if (!token.isEmpty() && !token.equals(ctx.header("X-Command-Token"))) {
				ctx.status(403).json(Map.of("ok", false, "error", "command token required"));
				return;
			}
			JsonNode body = Protocol.read(ctx.body().isBlank() ? "{}" : ctx.body());
			long s = body.path("seed").asLong(System.nanoTime() & 0xFFFFFF);
			host.reset(s);
			ctx.json(Map.of("ok", true, "seed", s));
		});

		// Baked procedural-creature sprite atlas for a phenotype key (the `pheno`
		// field on streamed entities). Immutable per key -> cache hard.
		app.get("/api/world/atlas/{key}.png", ctx -> {
			byte[] png = AtlasBaker.atlas(Long.parseLong(ctx.pathParam("key").replace(".png", "")));
			if (png == null) {
				ctx.status(404);
				return;
			}
			ctx.contentType(ContentType.IMAGE_PNG)
					.header("Cache-Control", "public, max-age=86400")
					.result(png);
		});

		// Tap-to-inspect: full detail for one entity (genome, energy, state).
		app.get("/api/world/entity/{id}", ctx -> {
			var d = host.entityDetail(Integer.parseInt(ctx.pathParam("id")));
			if (d == null) {
				ctx.status(404).json(Map.of("error", "no such entity"));
				return;
			}
			ctx.json(d);
		});

		app.get("/api/world/layers/{z}.png", ctx -> {
			byte[] png = host.layer(Integer.parseInt(ctx.pathParam("z").replace(".png", "")));
			if (png == null) {
				ctx.status(404);
				return;
			}
			ctx.contentType(ContentType.IMAGE_PNG).result(png);
		});

		app.ws("/api/world/stream", ws -> {
			ws.onConnect(host::onConnect);
			ws.onClose(host::onClose);
			ws.onMessage(ctx -> {
				try {
					JsonNode m = Protocol.read(ctx.message());
					String cmd = m.path("cmd").asText("");
					if (!token.isEmpty() && !token.equals(m.path("token").asText(""))) {
						ctx.send(Protocol.write(Protocol.Error.of("command token required")));
						return;
					}
					switch (cmd) {
					case "spawnItem" -> {
						SimCommand c = SpawnItemCommand.parse(m.path("kind").asText(""),
								m.path("x").asDouble(), m.path("y").asDouble(), m.path("z").asDouble(0));
						if (c == null) {
							ctx.send(Protocol.write(Protocol.Error.of("unknown item kind")));
							return;
						}
						long tick = host.runner().enqueue(c);
						ctx.send(Protocol.write(Protocol.Ack.of(cmd, tick)));
					}
					case "pause" -> {
						host.runner().pause();
						host.announceStatus();
					}
					case "resume" -> {
						host.runner().resume();
						host.announceStatus();
					}
					case "speed" -> {
						host.runner().setSpeed(m.path("value").asDouble(1.0));
						host.announceStatus();
					}
					case "reset" -> host.reset(m.path("seed").asLong(host.seed()));
					default -> ctx.send(Protocol.write(Protocol.Error.of("unknown cmd: " + cmd)));
					}
				} catch (Exception e) {
					ctx.send(Protocol.write(Protocol.Error.of("bad message: " + e.getMessage())));
				}
			});
		});

		app.start(port);
		System.out.println("world server up: http://localhost:" + port + "  (seed " + seed + ")");
	}

	private static String cfg(String[] args, String key, String def) {
		for (String a : args) {
			if (a.startsWith(key + "=")) {
				return a.substring(key.length() + 1);
			}
		}
		String env = System.getenv(key);
		return env != null ? env : def;
	}

	private ServerMain() {
	}
}
