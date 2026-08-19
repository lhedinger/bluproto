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
 *   GET  /api/health            liveness, current tick, and tick cost vs budget
 *   GET  /api/world             world info (seed, geometry, tick, viewers)
 *   POST /api/world/reset       fresh world from {"seed": n}
 *   GET  /api/world/layers/{z}/{cx}_{cy}.png  baked ground chunk (level z)
 *   GET  /api/world/layers/{z}/{cx}_{cy}_bare.png  same chunk, fully grazed
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
		// When this instance started running = when it was (re)deployed. Emitted
		// as an ISO-8601 instant (UTC) so the client can render it in the
		// viewer's local time.
		String deployedAt = java.time.Instant
				.ofEpochMilli(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime())
				.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
				.toString();

		// World size: 0 (default) uses the built-in size; set WORLD_COLS/WORLD_ROWS
		// to override — e.g. to scale down under a tight heap without a rebuild.
		int worldCols = Integer.parseInt(cfg(args, "WORLD_COLS", "0"));
		int worldRows = Integer.parseInt(cfg(args, "WORLD_ROWS", "0"));
		// The sprite catalog pre-renders BEFORE the live world exists: its staged
		// scenes tick and draw from the shared engine RNG, which must never be
		// touched once the deterministic sim thread is running.
		SpriteCatalog catalog = SpriteCatalog.render();
		WorldHost host = new WorldHost(seed, worldCols, worldRows);

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

		// Count every contact, once, before anything else handles it. Hashed inside
		// VisitorLog -- the address itself is read here and never stored. X-Forwarded-For
		// matters because Caddy sits in front: without it every visitor would look like
		// the proxy and the distinct count would be 1 forever.
		//
		// Only the initial dispatch counts. Jetty re-enters this handler when it
		// forwards "/" to the welcome file, so counting every dispatch would bill the
		// site root -- the single most requested URL here -- at double everything else.
		app.before(ctx -> {
			if (ctx.req().getDispatcherType() == jakarta.servlet.DispatcherType.REQUEST) {
				host.visitors.record(VisitorLog.clientAddress(ctx.ip(), ctx.header("X-Forwarded-For")));
			}
		});

		// Liveness, and whether the world is keeping up with real time. The runner
		// already times every tick into a rolling window; what was missing was anywhere
		// to read it. Cost alone is not actionable, so the budget it is measured
		// against travels with it: at 33 t/s a tick has 30.3 ms, and once the average
		// passes that the loop can no longer drain its time bank and the world quietly
		// runs slow -- no crash, no error, just behind. That is the failure worth being
		// able to see from outside.
		app.get("/api/health", ctx -> {
			double tickMs = host.runner().avgTickMillis();
			double budgetMs = 1000.0 / net.hedinger.prototype.sim.SimulationRunner.TICKS_PER_SECOND;
			ctx.json(Map.of("ok", true, "tick", host.runner().snapshot().tick(),
					"entities", host.runner().snapshot().entities().size(),
					"tickMillis", Math.round(tickMs * 1000) / 1000.0,
					"tickBudgetMillis", Math.round(budgetMs * 1000) / 1000.0,
					"tickLoad", budgetMs <= 0 ? 0 : Math.round(tickMs / budgetMs * 1000) / 1000.0,
					"keepingUp", tickMs < budgetMs,
					"commit", commitShort, "deployedAt", deployedAt));
		});

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

		// The sprite catalog: ONE page, at /sprites, drawn by the viewer's own code.
		// That is the question worth answering -- what does the art look like to
		// someone who opens the world -- and there is exactly one answer to it.
		//
		// The /web and /java variants are gone. /java rendered a second, differently
		// organised catalog from the Java pipeline, and /web was an alias for a
		// distinction that no longer exists. Two pages that both claimed to be "the
		// catalog" was not a feature: it is what let this route serve the java page
		// on every deploy for weeks without anyone being able to tell, because the
		// wrong answer looked like a real catalog. The java bakes are still served
		// as ASSETS under /sprites/*.png -- the client page composites several of
		// them -- they simply no longer have a page of their own.
		java.io.File clientSprites = new java.io.File(cfg(args, "CLIENT_DIR", "client/dist"),
				"sprites.html");
		// What this running build is, for cache validation. deployedAt is the
		// process start time, so this changes on every deploy AND every local
		// restart -- which is what a catalog needs: the art it shows is rebuilt
		// with the server, under filenames that never change.
		String buildTag = commitShort + "@" + deployedAt;

		io.javalin.http.Handler clientCatalog = ctx -> {
			// Never cached. The catalog is the normative record of what the art
			// system currently looks like (ART-STYLE.md), so a stale copy is worse
			// than no copy -- it asserts, with authority, something that is no
			// longer true. The page is small and rebuilt on every deploy under the
			// same URL, so there is nothing here worth a cache hit.
			ctx.header("Cache-Control", "no-store");
			ctx.contentType("text/html");
			// Two places the client page can live: the external dist a dev run points
			// at, and the copy `copyClient` folds into the jar -- which is the only
			// one that exists in the deployed image, since the Dockerfile's runtime
			// stage copies just the server install and res/. Asking only the
			// filesystem is what made this route serve something else entirely on
			// every deploy for weeks.
			if (clientSprites.isFile()) {
				ctx.result(java.nio.file.Files.readAllBytes(clientSprites.toPath()));
				return;
			}
			try (java.io.InputStream in =
					ServerMain.class.getResourceAsStream("/public/sprites.html")) {
				if (in != null) {
					ctx.result(in.readAllBytes());
					return;
				}
			}
			// No client build anywhere: say so, rather than substituting a different
			// page. Serving a plausible-looking stand-in is precisely how the missing
			// catalog stayed invisible -- a 503 that names the cause is worth more
			// than a page that answers a question nobody asked.
			ctx.status(503).result("<h1>sprite catalog unavailable</h1><p>No web client "
					+ "build was found — neither <code>client/dist/sprites.html</code> nor "
					+ "<code>public/sprites.html</code> in the server jar. Run "
					+ "<code>npm run build</code> in <code>client/</code>, or build the "
					+ "server with <code>./gradlew :server:installDist</code>, which folds "
					+ "the client in.</p>");
		};
		app.get("/sprites", clientCatalog); // the one catalog: what a viewer sees
		app.get("/sprites/{file}", ctx -> {
			String name = ctx.pathParam("file");
			byte[] bytes = name.matches("[A-Za-z0-9_-]+\\.(png|gif)") ? catalog.asset(name) : null;
			if (bytes == null) {
				ctx.status(404);
				return;
			}
			// These are baked by THIS build under names that never change, so a
			// plain max-age served week-old art from a browser (and from Cloudflare)
			// after a redeploy -- the catalog would show the previous release's
			// sprites next to this release's live canvases and read as drift.
			// `no-cache` keeps the copy but forces revalidation; the ETag carries
			// the build, so an unchanged deploy still costs only a 304.
			String etag = "\"" + buildTag + "-" + name + "\"";
			if (etag.equals(ctx.header("If-None-Match"))) {
				ctx.status(304);
				return;
			}
			ctx.contentType(SpriteCatalog.contentType(name))
					.header("Cache-Control", "no-cache")
					.header("ETag", etag)
					.result(bytes);
		});

		// Scenario recordings gallery: the animated GIFs the RecordScenario
		// tool writes into RECORDINGS_DIR (default "recordings/", git-ignored).
		// A plain dark page of looping clips -- watchable behaviour tests.
		String recordingsDir = cfg(args, "RECORDINGS_DIR", "recordings");
		app.get("/recordings", ctx -> {
			java.io.File[] gifs = new java.io.File(recordingsDir)
					.listFiles(f -> f.getName().endsWith(".gif"));
			StringBuilder b = new StringBuilder();
			b.append("<!doctype html><meta charset=utf-8><title>scenario recordings</title>")
					.append("<style>body{background:#14161a;color:#c8cdd5;font:14px system-ui;")
					.append("margin:2em}figure{margin:0 0 2.5em}img{max-width:100%;")
					.append("image-rendering:pixelated;border:1px solid #2a2e36}")
					.append("figcaption{margin:.4em 0;color:#8a93a0}</style>")
					.append("<h2>scenario recordings</h2>");
			if (gifs == null || gifs.length == 0) {
				b.append("<p>none yet -- run <code>RecordScenario &lt;ScenarioName&gt;</code>")
						.append(" and refresh.</p>");
			} else {
				java.util.Arrays.sort(gifs, java.util.Comparator.comparing(java.io.File::getName));
				for (java.io.File f : gifs) {
					String n = f.getName();
					b.append("<figure><figcaption>").append(n.replace(".gif", ""))
							.append(" (").append(f.length() / 1024).append(" KB)</figcaption>")
							.append("<img src=\"/recordings/").append(n).append("\"></figure>");
				}
			}
			ctx.contentType("text/html").result(b.toString());
		});
		app.get("/recordings/{file}", ctx -> {
			String name = ctx.pathParam("file");
			// Names come from the scenario class names; anything else -- and in
			// particular anything path-shaped -- is refused outright.
			if (!name.matches("[A-Za-z0-9_-]+\\.gif")) {
				ctx.status(404);
				return;
			}
			java.io.File f = new java.io.File(recordingsDir, name);
			if (!f.isFile()) {
				ctx.status(404);
				return;
			}
			ctx.contentType("image/gif").result(java.nio.file.Files.readAllBytes(f.toPath()));
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

		// Baked procedural-creature sprite atlas for a SHAPE key (the `pheno`
		// field on streamed entities). Colour-neutral: the client re-tints with
		// the entity's rgb, so one sheet serves a shape's every colour variant.
		// Immutable per key -> cache hard.
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

		// Mind inspector: the creature's evolvable LGP program, live registers and
		// program counter, and the sensor/actuator vectors it read/wrote last tick.
		app.get("/api/world/mind/{id}", ctx -> {
			var d = host.mindDetail(Integer.parseInt(ctx.pathParam("id")));
			if (d == null) {
				ctx.status(404).json(Map.of("error", "no such entity"));
				return;
			}
			ctx.json(d);
		});

		// Genome export (read-only): a creature's whole heritable definition, brain
		// included, as a portable savefile string — back it up, re-inject it below.
		app.get("/api/world/genome/{id}", ctx -> {
			var d = host.genomeExport(Integer.parseInt(ctx.pathParam("id")));
			if (d == null) {
				ctx.status(404).json(Map.of("error", "no such entity"));
				return;
			}
			ctx.json(d);
		});

		// Genome injection (token-gated, like reset/spawn): drop a creature built
		// from an exported genome into the live world. Body: {"genome": "<encoded>",
		// "x": .., "y": .., "z": ..}; position defaults to the surface centre.
		app.post("/api/world/genome", ctx -> {
			if (!token.isEmpty() && !token.equals(ctx.header("X-Command-Token"))) {
				ctx.status(403).json(Map.of("ok", false, "error", "command token required"));
				return;
			}
			JsonNode body = Protocol.read(ctx.body().isBlank() ? "{}" : ctx.body());
			String encoded = body.path("genome").asText("");
			var w = host.runner().world();
			double x = body.path("x").asDouble(w.getColums() / 2.0);
			double y = body.path("y").asDouble(w.getRows() / 2.0);
			double z = body.path("z").asDouble(w.getLevels() - 1); // surface (top level)
			long tick = host.injectGenome(encoded, x, y, z);
			if (tick < 0) {
				ctx.status(400).json(Map.of("ok", false, "error", "malformed genome"));
				return;
			}
			ctx.json(Map.of("ok", true, "tick", tick, "x", x, "y", y, "z", z));
		});

		// Baked ground as map chunks: level z, chunk (cx, cy). Immutable per world
		// -> cache hard. The client streams only the chunks in view.
		// Live vegetation for the depletion overlay. With ?since=SEQ (the web
		// client): quantised 5-state grid with sequence-numbered deltas — a
		// handful of packed (tile<<3|state) ints per poll instead of the whole
		// grid (see VegFeed). Without ?since: the legacy raw 0..100 byte grid,
		// kept for tooling.
		app.get("/api/world/vegetation/{z}", ctx -> {
			int z = Integer.parseInt(ctx.pathParam("z"));
			String since = ctx.queryParam("since");
			if (since != null) {
				var resp = host.vegetationSince(z, Long.parseLong(since));
				if (resp == null) {
					ctx.status(404);
					return;
				}
				ctx.json(resp);
				return;
			}
			byte[] v = host.vegetation(z);
			if (v == null) {
				ctx.status(404);
				return;
			}
			var w = host.runner().world();
			ctx.json(Map.of("cols", w.getColums(), "rows", w.getRows(),
					"data", java.util.Base64.getEncoder().encodeToString(v)));
		});

		// Static per-tile cover mask (1 = thicket) for the shrub canopy overlay.
		// Fetched once per level; the client draws foliage over entities in cover.
		app.get("/api/world/cover/{z}", ctx -> {
			byte[] c = host.cover(Integer.parseInt(ctx.pathParam("z")));
			if (c == null) {
				ctx.status(404);
				return;
			}
			var w = host.runner().world();
			ctx.json(Map.of("cols", w.getColums(), "rows", w.getRows(),
					"data", java.util.Base64.getEncoder().encodeToString(c)));
		});

		// Tap-to-inspect a tile (debug): type, fertility, live vegetation/food.
		app.get("/api/world/tile/{z}/{x}/{y}", ctx -> {
			var d = host.tileDetail(Integer.parseInt(ctx.pathParam("x")),
					Integer.parseInt(ctx.pathParam("y")), Integer.parseInt(ctx.pathParam("z")));
			if (d == null) {
				ctx.status(404).json(Map.of("error", "no such tile"));
				return;
			}
			ctx.json(d);
		});

		// {chunk} is "cx_cy" for the lush bake, "cx_cy_bare" for its fully-grazed
		// twin (the client dithers between the two to show live depletion).
		app.get("/api/world/layers/{z}/{chunk}.png", ctx -> {
			int z = Integer.parseInt(ctx.pathParam("z").replace(".png", ""));
			String[] p = ctx.pathParam("chunk").replace(".png", "").split("_");
			byte[] png = p.length == 2
					? host.chunk(z, Integer.parseInt(p[0]), Integer.parseInt(p[1]))
					: p.length == 3 && "bare".equals(p[2])
							? host.bareChunk(z, Integer.parseInt(p[0]), Integer.parseInt(p[1]))
							: null;
			if (png == null) {
				ctx.status(404);
				return;
			}
			ctx.contentType(ContentType.IMAGE_PNG)
					.header("Cache-Control", "public, max-age=86400")
					.result(png);
		});

		app.ws("/api/world/stream", ws -> {
			ws.onConnect(host::onConnect);
			ws.onClose(host::onClose);
			ws.onMessage(ctx -> {
				try {
					JsonNode m = Protocol.read(ctx.message());
					String cmd = m.path("cmd").asText("");
					if (cmd.equals("level")) {
						// A view preference, not a mutation: read-only viewers
						// switch levels too, so this sits before the token gate.
						host.setViewerLevel(ctx, m.path("z").asInt(0));
						return;
					}
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
