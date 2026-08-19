package net.hedinger.prototype.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The quantised vegetation feed for one level: grass is served as one of five
 * STATES per tile (0 lush/none .. 4 bare) with sequence-numbered deltas, not
 * as a continuous 0..100 field.
 *
 * <p>The continuous field was the web client's single biggest cost: with 17
 * dither buckets, world-wide regrowth moved thousands of buckets per poll on
 * an old world and every one meant a repaint. Five states cut the transition
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

	/** One raw 0..100/255 grass level quantised to a stage: 0 = lush grass or
	 *  no grass at all (nothing to draw), 1..4 = quarters of depletion. */
	static int stateOf(int lvl) {
		if (lvl >= 100 || lvl == 255) {
			return 0;
		}
		int d = 100 - lvl; // 1..100
		return 1 + (d - 1) * 4 / 100;
	}

	/** Builds the JSON response for a client holding {@code since} (-1 = none),
	 *  refreshing the quantised grid from {@code raw} when it is due. */
	synchronized Map<String, Object> respond(byte[] raw, long since, long now, int cols, int rows) {
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
		return Map.of("cols", cols, "rows", rows, "seq", seq,
				"states", Base64.getEncoder().encodeToString(states));
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
