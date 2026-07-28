package net.hedinger.prototype.sim;

import java.awt.Graphics;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.simtest.TestNPC;

/**
 * The invisible warden of a public, always-on ecosystem. A living world of
 * breeders and predators is chaotic per seed — left alone it tends to either
 * die out or explode (see MODERNIZATION.md) — which is fine for a lab but not
 * for a URL anyone can open. This steward rides inside the world as an inert
 * entity and, every tick, keeps the two populations inside sane bounds:
 *
 * <ul>
 *   <li><b>Floor (never empty):</b> if prey fall below the minimum it seeds a
 *       fresh lineage; likewise predators, once there is enough prey to hunt.</li>
 *   <li><b>Ceiling (never a swarm):</b> if a population overshoots its cap it
 *       trims the excess a few at a time — a safety net above the natural
 *       predation/starvation control, so it rarely actually fires.</li>
 * </ul>
 *
 * It is deterministic (all placement via the seeded {@link Utils#random}) and
 * runs inside {@code world.think()}, so a snapshot stream stays reproducible.
 * It perceives nothing, collides with nothing, and is filtered out of snapshots
 * — the viewer never sees it.
 */
public final class WorldSteward extends Entity {

	private final int preyMin, preyMax, predMin, predMax;
	private final Genome[] preySpecies, predSpecies;
	private final int cols, rows;
	private int n = 0; // rotates species / placement, deterministically

	/** Corpse lifespan for reseeded creatures (matches Worlds.ECO_DEATHSPAN). */
	private static final int ECO_DEATHSPAN = 90;

	WorldSteward(World w, Genome[] preySpecies, Genome[] predSpecies,
			int preyMin, int preyMax, int predMin, int predMax) {
		super(w.getColums() / 2.0, w.getRows() / 2.0, 0, 0.0); // centre; direction ctor draws no RNG
		this.cols = w.getColums();
		this.rows = w.getRows();
		this.preySpecies = preySpecies;
		this.predSpecies = predSpecies;
		this.preyMin = preyMin;
		this.preyMax = preyMax;
		this.predMin = predMin;
		this.predMax = predMax;
	}

	@Override
	protected void think() {
		int prey = 0, pred = 0;
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()) {
				String r = t.ecoRole();
				if (r.equals("prey")) {
					prey++;
				} else if (r.equals("predator")) {
					pred++;
				}
			}
		}

		// Floor: reseed a couple per tick until the minimum is restored, so a
		// crash recovers quickly but not instantly (it reads as a bloom).
		if (prey < preyMin) {
			seed(preySpecies, true);
			seed(preySpecies, true);
		}
		if (pred < predMin && prey > predMin * 4) {
			seed(predSpecies, false);
		}

		// Ceiling: trim a small, fixed number of the excess per tick. Removed
		// (not killed) so a hard cap does not carpet the world in corpses.
		if (prey > preyMax) {
			trim("prey", Math.min(3, prey - preyMax));
		}
		if (pred > predMax) {
			trim("predator", Math.min(2, pred - predMax));
		}
	}

	/** Spawns one creature of a rotating species at a random interior tile. */
	private void seed(Genome[] pool, boolean isPrey) {
		Genome g = Genome.child(pool[n % pool.length], 0.08); // lineage flavour, slight drift
		n++;
		double x = 3 + Utils.random() * (cols - 6);
		double y = 3 + Utils.random() * (rows - 6);
		TestNPC t = isPrey ? TestNPC.breeder(x, y, 0, g).withHerding() : TestNPC.predator(x, y, 0, g);
		getWorld().spawnEntity(t.withDeathspan(ECO_DEATHSPAN));
	}

	/** Removes up to {@code count} of the given role (iteration order). */
	private void trim(String role, int count) {
		int removed = 0;
		for (Entity e : getWorld().getEntities()) {
			if (removed >= count) {
				break;
			}
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved() && t.ecoRole().equals(role)) {
				t.remove();
				removed++;
			}
		}
	}

	@Override
	protected void draw(Graphics g, View v) {
		// Invisible: the steward is machinery, not a creature.
	}

	@Override
	public String getEntityTypeName() {
		return "steward";
	}
}
