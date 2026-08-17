package net.hedinger.prototype.server;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.hedinger.prototype.sim.EntityState;

/**
 * The wire protocol between the world server and its viewers — JSON messages
 * over one WebSocket, deliberately debuggable (MODERNIZATION.md: JSON first,
 * binary only if measured to matter). Server → client:
 *
 * <ul>
 *   <li>{@code hello} — world identity and geometry, sent once on connect</li>
 *   <li>{@code full} — the complete entity list (on connect, and on resync)</li>
 *   <li>{@code delta} — entities changed since the previous broadcast
 *       ({@code upsert}) and ids that vanished ({@code gone})</li>
 *   <li>{@code status} — pause/speed changes</li>
 *   <li>{@code ack}/{@code error} — response to a client command</li>
 * </ul>
 *
 * Client → server is a single {@code cmd} message; see {@link ServerMain} for
 * the accepted verbs. All classes here are dumb shapes: no behaviour, so the
 * encoding can move to binary without touching the sim or the server logic.
 */
final class Protocol {

	static final ObjectMapper JSON = new ObjectMapper();

	record Hello(String type, long seed, int cols, int rows, int levels, int tileSize,
			long tick, boolean paused, double speed, List<String> layers, int chunkTiles, String build) {
		static Hello of(long seed, int cols, int rows, int levels, int tileSize,
				long tick, boolean paused, double speed, List<String> layers, int chunkTiles, String build) {
			return new Hello("hello", seed, cols, rows, levels, tileSize, tick, paused, speed, layers, chunkTiles,
					build);
		}
	}

	/** {@code total} is the WORLD population, across every level — the entity
	 *  list itself is filtered to the viewer's level, so the HUD needs the
	 *  true count carried separately. */
	record Full(String type, long tick, List<EntityState> entities, int total) {
		static Full of(long tick, List<EntityState> entities, int total) {
			return new Full("full", tick, entities, total);
		}
	}

	record Delta(String type, long tick, List<EntityState> upsert, List<Integer> gone, int total) {
		static Delta of(long tick, List<EntityState> upsert, List<Integer> gone, int total) {
			return new Delta("delta", tick, upsert, gone, total);
		}
	}

	record Status(String type, long tick, boolean paused, double speed) {
		static Status of(long tick, boolean paused, double speed) {
			return new Status("status", tick, paused, speed);
		}
	}

	record Ack(String type, String cmd, long tick) {
		static Ack of(String cmd, long tick) {
			return new Ack("ack", cmd, tick);
		}
	}

	record Error(String type, String message) {
		static Error of(String message) {
			return new Error("error", message);
		}
	}

	static String write(Object msg) {
		try {
			return JSON.writeValueAsString(msg);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("unencodable protocol message", e);
		}
	}

	static JsonNode read(String json) throws JsonProcessingException {
		return JSON.readTree(json);
	}

	private Protocol() {
	}
}
