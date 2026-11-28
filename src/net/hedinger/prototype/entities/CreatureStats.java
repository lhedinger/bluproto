package net.hedinger.prototype.entities;

/**
 * A creature's career: counts of what it has done in its life. Book-keeping
 * only -- nothing in the simulation reads these -- so the inspector and
 * analysis can read a body's history off it. Each counter is saved with the
 * body (see {@link NPC#bodyState}), so a deploy that keeps the world keeps
 * the careers too.
 *
 * <p>A new counter goes here, not as a loose field on the creature: one
 * place to read, save and send.
 */
public final class CreatureStats {

	private int kills = 0;

	/** Animals this body has killed while hunting: predation bites that took
	 *  the last of a living animal's health. A plain combat bite or a
	 *  parasite's drain that ends a life is not a hunting kill. */
	public int kills() {
		return kills;
	}

	/** Credits one hunting kill. */
	public void recordKill() {
		kills++;
	}

	/** Puts back a saved count (see {@link NPC#restoreBody}). */
	void restoreKills(int n) {
		kills = Math.max(0, n);
	}
}
