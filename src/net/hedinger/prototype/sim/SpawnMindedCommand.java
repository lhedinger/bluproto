package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.GenomeCodec;
import net.hedinger.prototype.simtest.TestNPC;

/**
 * Injects a minded creature built from a supplied {@link Genome} into the live
 * world — the "load a saved genome" verb, paired with the genome export
 * endpoint. Like every mutating command it runs at a tick boundary and is
 * logged, so an injection replays exactly (the genome travels in the log via
 * {@link GenomeCodec}).
 *
 * <p>Deterministic: {@link TestNPC#mindedForager} draws no randomness (the body
 * is a pure function of the genome), and {@link World#spawnEntity} rejects
 * out-of-bounds coordinates — so a hostile or malformed client cannot corrupt
 * the world through this path. The genome carries the creature's own evolvable
 * brain, so once injected it lives, breeds and evolves like any minded citizen.
 */
public record SpawnMindedCommand(Genome genome, double x, double y, double z) implements SimCommand {

	/** Corpse lifespan for an injected creature (ticks ≈ 3 s), matching the
	 *  ecosystem's other bodies so its remains clear rather than pile up. */
	private static final int DEATHSPAN = 90;

	/** Parses the wire form (an encoded genome + position); null if the genome
	 *  string is malformed, so a bad payload is rejected rather than applied. */
	public static SpawnMindedCommand parse(String encodedGenome, double x, double y, double z) {
		try {
			return new SpawnMindedCommand(GenomeCodec.decode(encodedGenome), x, y, z);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	@Override
	public void apply(World w) {
		// Land the creature on walkable ground near the requested spot. Dropping a
		// land body onto a wall makes it try to climb, run off the top level, and
		// die instantly; onto water it's stuck and starves. Snapping to the nearest
		// open tile makes "tap to place" forgiving of a tap on the rocky rim, a
		// thicket edge or a lake. Deterministic (a fixed outward ring scan over
		// static terrain), so it still replays exactly.
		double[] p = nearestWalkable(w, x, y, (int) z);
		w.spawnEntity(TestNPC.mindedForager(p[0], p[1], z, genome).withDeathspan(DEATHSPAN));
	}

	private static double[] nearestWalkable(World w, double x, double y, int z) {
		int cx = (int) x, cy = (int) y;
		if (walkable(w, cx, cy, z)) {
			return new double[] { x, y }; // requested spot is fine: keep it exact
		}
		for (int r = 1; r <= 8; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dy = -r; dy <= r; dy++) {
					if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
						continue; // only the outer ring at this radius
					}
					if (walkable(w, cx + dx, cy + dy, z)) {
						return new double[] { cx + dx + 0.5, cy + dy + 0.5 };
					}
				}
			}
		}
		return new double[] { x, y }; // nothing open nearby; land as asked (rare)
	}

	private static boolean walkable(World w, int x, int y, int z) {
		return w.isValid(x, y, z) && w.getTile(x, y, z).isWalkable();
	}

	@Override
	public String describe() {
		// The encoded genome is whitespace-free, so it stays a single token when a
		// replay splits this line on whitespace.
		return "spawnMinded " + x + " " + y + " " + z + " " + GenomeCodec.encode(genome);
	}
}
