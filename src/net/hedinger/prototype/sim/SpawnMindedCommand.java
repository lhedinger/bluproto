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
		w.spawnEntity(TestNPC.mindedForager(x, y, z, genome).withDeathspan(DEATHSPAN));
	}

	@Override
	public String describe() {
		// The encoded genome is whitespace-free, so it stays a single token when a
		// replay splits this line on whitespace.
		return "spawnMinded " + x + " " + y + " " + z + " " + GenomeCodec.encode(genome);
	}
}
