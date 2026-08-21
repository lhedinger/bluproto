package net.hedinger.prototype.sim;

import java.util.concurrent.ConcurrentHashMap;

import net.hedinger.prototype.engine.ProcCreature;
import net.hedinger.prototype.entities.Genome;

/**
 * Maps a SHAPE key (see {@link ProcCreature#shapeKey}) to a phenotype wearing
 * it, so the web layer can bake a sprite atlas for a creature it only knows by
 * that key. Snapshots register every genome they observe; the atlas endpoint
 * looks the phenotype back up. Keys deliberately exclude colour — the atlas is
 * baked colour-neutral and the client re-tints per creature (the creature's
 * rgb rides the entity stream already), so a whole lineage's colour drift
 * shares one sheet. A handful of shapes means a handful of entries.
 *
 * <p>Process-global to match the single-world server (MODERNIZATION.md); a key
 * is stable across worlds of the same species, so re-registration is harmless.
 */
public final class PhenoRegistry {

	private static final ConcurrentHashMap<Long, ProcCreature.Phenotype> BY_KEY =
			new ConcurrentHashMap<Long, ProcCreature.Phenotype>();

	/** Derives the phenotype, records it under its shape key, and returns the key. */
	public static long register(Genome g) {
		ProcCreature.Phenotype ph = ProcCreature.phenotype(g);
		long key = ProcCreature.shapeKey(ph);
		BY_KEY.putIfAbsent(key, ph);
		return key;
	}

	/**
	 * Records a phenotype built by hand and returns its shape key.
	 *
	 * <p>The genome overload cannot reach every body: the plan follows the genome's
	 * diet, so the forms no diet selects are unreachable through it. The reference
	 * page still needs an atlas for those, and an atlas is only servable for a key
	 * the registry has seen.
	 */
	public static long register(ProcCreature.Phenotype ph) {
		long key = ProcCreature.shapeKey(ph);
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
