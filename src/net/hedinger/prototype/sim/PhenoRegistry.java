package net.hedinger.prototype.sim;

import java.util.concurrent.ConcurrentHashMap;

import net.hedinger.prototype.engine.ProcCreature;
import net.hedinger.prototype.entities.Genome;

/**
 * Maps a phenotype key (see {@link ProcCreature#phenoKey}) to the phenotype it
 * names, so the web layer can bake a sprite atlas for a creature it only knows
 * by that key. Snapshots register every genome they observe; the atlas endpoint
 * looks the phenotype back up. A handful of species means a handful of entries.
 *
 * <p>Process-global to match the single-world server (MODERNIZATION.md); a key
 * is stable across worlds of the same species, so re-registration is harmless.
 */
public final class PhenoRegistry {

	private static final ConcurrentHashMap<Long, ProcCreature.Phenotype> BY_KEY =
			new ConcurrentHashMap<Long, ProcCreature.Phenotype>();

	/** Derives the phenotype, records it under its key, and returns the key. */
	public static long register(Genome g) {
		ProcCreature.Phenotype ph = ProcCreature.phenotype(g);
		long key = ProcCreature.phenoKey(ph);
		BY_KEY.putIfAbsent(key, ph);
		return key;
	}

	/** The phenotype for a key, or null if nothing with that key has been seen. */
	public static ProcCreature.Phenotype get(long key) {
		return BY_KEY.get(key);
	}

	private PhenoRegistry() {
	}
}
