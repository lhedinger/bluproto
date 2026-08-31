package net.hedinger.prototype.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.sim.EntityState;

/**
 * The binary encoding of the entity stream — what a client that connected with
 * {@code ?bin=1} receives for full/delta frames (control traffic stays JSON).
 *
 * <p>Two ideas carry the ~10x saving over JSON. First, an entity's static
 * identity — kind, phenotype, colour, body — is sent ONCE as a <em>birth
 * record</em> when it first appears on a level's stream; every tick thereafter
 * ships only a 14-byte <em>pose</em> (position, heading, flags, aux). Second,
 * the pose fields are quantised to what the renderer can actually use: 1/128
 * tile positions (far below a screen pixel), 256 heading buckets (the sprites
 * quantise to 8), 1/256 aux.
 *
 * <p>Size never rides a pose at all: growth is deterministic (a fixed
 * {@link NPC#GROWTH_RATE} toward the birth record's {@code sizeMax}), so the
 * client extrapolates the body from the record and the frame tick, freezing it
 * when the F_DEAD flag arrives.
 *
 * <p>Layout (little-endian; counts are u16):
 * <pre>
 * u8 type (1 full / 2 delta) · u32 tick · u32 total
 * dict:   n × [u16 kindId · u8 len · utf8]     (kinds referenced by births)
 * births: n × [i32 id · u16 kindId · f64 pheno · u32 rgb · f32 size ·
 *              f32 sizeMax · f32 growthRate]
 * poses:  n × [i32 id · u16 x*128 · u16 y*128 · u8 dir · i8 z · u16 flags ·
 *              i16 aux*256]
 * gone:   n × [i32 id]
 * attach: n × [i32 id · i32 carrierId]         (poses with a carrier)
 * </pre>
 * A full's births cover every entity; a delta's cover entities new to the
 * level (fresh ids, or bodies that walked a ramp in). The kind dictionary is
 * cumulative client-side; entries repeat harmlessly whenever a birth uses
 * them, so the encoder stays stateless per message.
 */
final class BinaryProtocol {

	static final byte TYPE_FULL = 1, TYPE_DELTA = 2;
	/** Fixed-point scales, mirrored in client/src/binproto.ts. */
	static final double POS_SCALE = 128, AUX_SCALE = 256;

	private static final ConcurrentHashMap<String, Integer> KIND_IDS = new ConcurrentHashMap<>();
	private static final AtomicInteger NEXT_KIND = new AtomicInteger();

	private static int kindId(String kind) {
		return KIND_IDS.computeIfAbsent(kind, k -> NEXT_KIND.getAndIncrement());
	}

	/** A full snapshot: birth records and poses for every entity on the level. */
	static byte[] full(long tick, List<EntityState> ents, int total) {
		return encode(TYPE_FULL, tick, total, ents, ents, List.of());
	}

	/**
	 * A delta: poses for every changed entity, births only for those new to
	 * this level's stream — fresh ids ({@code prevZ} has no entry) or bodies
	 * that ramped in ({@code prevZ} says they were elsewhere).
	 */
	static byte[] delta(long tick, List<EntityState> upsert, List<Integer> gone, int total,
			Map<Integer, Integer> prevZ, java.util.Set<Integer> watched) {
		// A body is a BIRTH to this stream when its previous level was not
		// among the levels the viewer watches — brand new, or walked in from
		// a floor outside the subscription. With the floor-below cohort in
		// the stream, "watched" is a set rather than the one level it was.
		List<EntityState> births = new ArrayList<>();
		for (EntityState e : upsert) {
			Integer was = prevZ.get(e.id());
			if (was == null || !watched.contains(was)) {
				births.add(e);
			}
		}
		return encode(TYPE_DELTA, tick, total, births, upsert, gone);
	}

	private static byte[] encode(byte type, long tick, int total,
			List<EntityState> births, List<EntityState> poses, List<Integer> gone) {
		// Kind dictionary: the entries this message's births reference.
		Map<Integer, byte[]> dict = new LinkedHashMap<>();
		for (EntityState e : births) {
			int kid = kindId(e.kind());
			dict.computeIfAbsent(kid, k -> e.kind().getBytes(StandardCharsets.UTF_8));
		}
		int attach = 0;
		for (EntityState e : poses) {
			if (e.attachedTo() >= 0) {
				attach++;
			}
		}
		int dictBytes = 0;
		for (byte[] b : dict.values()) {
			dictBytes += 3 + b.length;
		}
		ByteBuffer buf = ByteBuffer.allocate(9 + 5 * 2 + dictBytes
				+ births.size() * 30 + poses.size() * 14 + gone.size() * 4 + attach * 8)
				.order(ByteOrder.LITTLE_ENDIAN);
		buf.put(type);
		buf.putInt((int) tick);
		buf.putInt(total);
		buf.putShort((short) dict.size());
		for (Map.Entry<Integer, byte[]> en : dict.entrySet()) {
			buf.putShort(en.getKey().shortValue());
			buf.put((byte) en.getValue().length);
			buf.put(en.getValue());
		}
		buf.putShort((short) births.size());
		for (EntityState e : births) {
			buf.putInt(e.id());
			buf.putShort((short) kindId(e.kind()));
			buf.putDouble(e.pheno()); // longs here fit a double exactly (< 2^53)
			buf.putInt(e.rgb());
			buf.putFloat(e.size());
			buf.putFloat(e.sizeMax());
			// Tiles per tick, matching the wire's size unit (the sim's raw
			// GROWTH_RATE is in pixels of body radius).
			buf.putFloat(e.sizeMax() > 0
					? (float) (NPC.GROWTH_RATE / net.hedinger.prototype.engine.ResourceManager.tileSize)
					: 0f);
		}
		buf.putShort((short) poses.size());
		for (EntityState e : poses) {
			buf.putInt(e.id());
			buf.putShort((short) Math.max(0, Math.min(0xFFFF, (int) Math.round(e.x() * POS_SCALE))));
			buf.putShort((short) Math.max(0, Math.min(0xFFFF, (int) Math.round(e.y() * POS_SCALE))));
			double d = e.dir() % (2 * Math.PI);
			if (d < 0) {
				d += 2 * Math.PI;
			}
			buf.put((byte) ((int) Math.round(d / (2 * Math.PI) * 256) & 0xFF));
			buf.put((byte) Math.round(e.z()));
			buf.putShort((short) e.flags());
			buf.putShort((short) Math.max(Short.MIN_VALUE,
					Math.min(Short.MAX_VALUE, Math.round(e.aux() * AUX_SCALE))));
		}
		buf.putShort((short) gone.size());
		for (int id : gone) {
			buf.putInt(id);
		}
		buf.putShort((short) attach);
		for (EntityState e : poses) {
			if (e.attachedTo() >= 0) {
				buf.putInt(e.id());
				buf.putInt(e.attachedTo());
			}
		}
		byte[] out = new byte[buf.position()];
		buf.flip();
		buf.get(out);
		return out;
	}

	private BinaryProtocol() {
	}
}
