package net.hedinger.prototype.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The quantised vegetation feed for one level: each tile is served as one of
 * six STATES (0 = never grows anything; 1..5 = sprite growth stage, trampled
 * remnants to fully grown) with sequence-numbered deltas, not as a continuous
 * 0..100 field. The viewer stamps a one-tile vegetation sprite per state over
 * the vegetation-free ground bake.
 *
 * <p>The continuous field was the web client's single biggest cost: with 17
 * dither buckets, world-wide regrowth moved thousands of buckets per poll on
 * an old world and every one meant a repaint. A handful of stages cuts the transition
 * rate ~4x, and the deltas cut the transfer from a whole base64 grid every
 * poll to a handful of (tile, state) pairs — a client sends the sequence
 * number it holds and receives only what moved since.
 *
 * <p>Change entries are packed {@code tile << 3 | state} ints. The journal
 * keeps the last {@link #JOURNAL} batches; a client whose sequence fell out
 * of the window (or a fresh one, {@code since=-1}) gets the full state grid.
 * The states refresh at most once per {@link #REFRESH_MS} regardless of how
 * many viewers poll.
 */
final class VegFeed {

	private static final int JOURNAL = 64;
	private static final long REFRESH_MS = 1000;

	private record Batch(long seq, int[] packed) {
	}

	private byte[] states;
	private long seq = 0;
	private long refreshedAt = 0;
	private final ArrayDeque<Batch> journal = new ArrayDeque<>();

	/** One raw 0..100/255 grass level quantised to a stage the viewer can
	 *  STAMP: 0 = this tile never grows anything (rock, water, bare dirt —
	 *  nothing to draw), 1..5 = growth stage of the vegetation sprite, 1 being
	 *  the trampled remnants a fully grazed tile keeps and 5 fully grown. The
	 *  ground bake carries no vegetation, so the distinction between "no grass
	 *  ever" and "lush grass" is load-bearing — conflating them would sprout
	 *  grass sprites on rock. */
	static int stateOf(int lvl) {
		if (lvl == 255) {
			return 0;
		}
		return Math.min(5, 1 + (int) Math.round(lvl * 4 / 100.0));
	}

	/** Builds the JSON response for a client holding {@code since} (-1 = none),
	 *  refreshing the quantised grid from {@code raw} when it is due. */
	/**
	 * The vegetation KIND field: which plant a tile grows, if any.
	 *
	 * <p>It rides only in the full grid, never in a delta. Terrain does not change
	 * at runtime, so the kind is a constant per tile: a client that has the full
	 * grid already knows it, and a delta only ever needs to say what the density
	 * did. That matters because a change entry packs {@code tile << 3 | state} and
	 * has exactly three bits for the state — there is no room for a kind in there,
	 * and widening the entry would cost every poll for a fact that never moves.
	 */
	static final int KIND_FUNGUS = 0x80;

	/** The cactus. It and the fungus keep the wire values they already had
	 *  rather than being renumbered into a tidier order: nothing is gained by
	 *  moving them, and a wire value that changes for aesthetic reasons is a
	 *  wire value that can change again. */
	static final int KIND_CACTUS = 0x40;

	/**
	 * The surface floras: the meadow's one crop drawn as four plants
	 * ({@link net.hedinger.prototype.engine.Tile#getFlora}). The field was two
	 * flag bits and had room for exactly one more plant, so it became a five-bit
	 * INDEX above the three stage bits -- {@code kind << KIND_SHIFT} -- with the
	 * fungus (16) and cactus (8) landing on the same bytes they always sent.
	 * The floras take the low indices, so the client can read them straight off
	 * the tile's own {@code FLORA_*} numbering.
	 */
	static final int KIND_SHIFT = 3;
	static final int KIND_FERN = 1 << KIND_SHIFT;
	static final int KIND_FLOWERS = 2 << KIND_SHIFT;
	static final int KIND_HEATHER = 3 << KIND_SHIFT;
	static final int KIND_MOSS = 4 << KIND_SHIFT;

	/** The whole kind field, for a reader that wants it rather than one flag. */
	static final int KIND_MASK = 0xF8;

	/** The stage bits: 0 nothing stands here, 1..5 the sprite to draw. */
	static final int STAGE_MASK = 0x07;

	/** How many rungs a plant's ladder has. Named because a caller that wants a
	 *  particular rung has to know how many there are to land on one. */
	static final int STAGES = 5;

	synchronized Map<String, Object> respond(byte[] raw, byte[] kinds, long since, long now,
			int cols, int rows) {
		if (states == null || states.length != raw.length || now - refreshedAt >= REFRESH_MS) {
			refresh(raw);
			refreshedAt = now;
		}
		if (since == seq) {
			return Map.of("seq", seq, "changes", List.of());
		}
		// Delta-able only when the journal still covers everything after `since`.
		if (since >= 0 && since < seq && !journal.isEmpty()
				&& journal.peekFirst().seq() <= since + 1) {
			List<Integer> merged = new ArrayList<>();
			for (Batch b : journal) {
				if (b.seq() > since) {
					for (int p : b.packed()) {
						merged.add(p);
					}
				}
			}
			return Map.of("seq", seq, "changes", merged);
		}
		// The kind is OR-ed in only here, on the way out. The `states` array itself
		// stays pure density, because it is what deltas are diffed against and what
		// they are packed from -- a kind bit left in it would ride into the three
		// state bits of every change entry and corrupt them.
		byte[] out = states;
		if (kinds != null && kinds.length == states.length) {
			out = new byte[states.length];
			for (int i = 0; i < out.length; i++) {
				// kinds[] already carries the bits; it used to carry a flag the
				// caller had to translate here, which only worked while there
				// was exactly one thing to translate it into.
				out[i] = (byte) (states[i] | (kinds[i] & KIND_MASK));
			}
		}
		return Map.of("cols", cols, "rows", rows, "seq", seq,
				"states", Base64.getEncoder().encodeToString(out));
	}

	private void refresh(byte[] raw) {
		byte[] fresh = new byte[raw.length];
		for (int i = 0; i < raw.length; i++) {
			fresh[i] = (byte) stateOf(raw[i] & 0xFF);
		}
		if (states == null || states.length != fresh.length) {
			states = fresh;
			seq++;
			journal.clear();
			return;
		}
		int n = 0;
		for (int i = 0; i < fresh.length; i++) {
			if (fresh[i] != states[i]) {
				n++;
			}
		}
		if (n == 0) {
			return;
		}
		int[] packed = new int[n];
		int k = 0;
		for (int i = 0; i < fresh.length; i++) {
			if (fresh[i] != states[i]) {
				packed[k++] = (i << 3) | fresh[i];
			}
		}
		states = fresh;
		seq++;
		journal.addLast(new Batch(seq, packed));
		while (journal.size() > JOURNAL) {
			journal.removeFirst();
		}
	}
}
