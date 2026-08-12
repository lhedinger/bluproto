package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.Utils;
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

	// Population bounds {min, max}. Fixed, not seasonal: the world's headcount is
	// left to grass, predation and starvation, and these are only the guardrails
	// that stop it emptying or swarming.
	private final int[] preyBounds, predBounds;
	private final Genome[] preySpecies, predSpecies;
	private final int mindedFloor, mindedMax; // hold the minded cohort within [floor, max]
	private final int cols, rows;
	private final int surfaceZ; // the open-air level the herd lives on
	private int n = 0; // rotates species / placement, deterministically

	/** Corpse lifespan for reseeded creatures (matches Worlds.ECO_DEATHSPAN). */
	private static final int ECO_DEATHSPAN = 90;

	WorldSteward(World w, Genome[] preySpecies, Genome[] predSpecies, int surfaceZ,
			int[] preyBounds, int[] predBounds, int mindedFloor, int mindedMax) {
		super(w.getColums() / 2.0, w.getRows() / 2.0, surfaceZ, 0.0); // centre; direction ctor draws no RNG
		this.cols = w.getColums();
		this.rows = w.getRows();
		this.surfaceZ = surfaceZ;
		this.preySpecies = preySpecies;
		this.predSpecies = predSpecies;
		this.preyBounds = preyBounds;
		this.predBounds = predBounds;
		this.mindedFloor = mindedFloor;
		this.mindedMax = mindedMax;
	}

	@Override
	protected void think() {
		int preyMin = preyBounds[0];
		int preyMax = preyBounds[1];
		int predMin = predBounds[0];
		int predMax = predBounds[1];

		int prey = 0, pred = 0, minded = 0;
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()) {
				if (t.isMinded()) {
					minded++; // the hybrid cohort is its own category (role emerges)
				} else {
					String r = t.ecoRole();
					if (r.equals("prey")) {
						prey++;
					} else if (r.equals("predator")) {
						pred++;
					}
				}
			}
		}

		// Floor: reseed a couple per tick until the minimum is restored, so a
		// crash recovers as a bloom rather than an empty map.
		if (prey < preyMin) {
			seed(preySpecies, true);
			seed(preySpecies, true);
		}
		if (pred < predMin && prey > predMin * 4) {
			seed(predSpecies, false);
		}
		// Keep the small minded cohort from vanishing: a fully-random mind rarely
		// feeds itself, so the lineage would otherwise die out and the A/B seam with
		// it. Reseed one fresh random-brained creature per tick until the floor is
		// restored (fresh random, not inherited: pure emergence, per the design).
		if (minded < mindedFloor) {
			seedMinded();
		}
		// Ceiling on the minded cohort: a last-resort backstop, not the primary
		// control. Predators hunt minded creatures like anything else their size or
		// smaller, so predation and starvation are what actually hold this cohort in
		// check; the cap sits far above the population those forces settle at and
		// should almost never fire.
		if (minded > mindedMax) {
			trimMinded(Math.min(3, minded - mindedMax));
		}

		// Ceiling: trim a small, fixed number of the excess per tick, so the herd
		// is thinned gradually rather than culled in one blow.
		if (prey > preyMax) {
			trim("prey", Math.min(3, prey - preyMax));
		}
		if (pred > predMax) {
			trim("predator", Math.min(2, pred - predMax));
		}
	}

	/** Spawns one creature of a rotating species at a random open surface tile. */
	private void seed(Genome[] pool, boolean isPrey) {
		Genome g = Genome.child(pool[n % pool.length], 0.08); // lineage flavour, slight drift
		n++;
		double x = cols / 2.0, y = rows / 2.0;
		for (int tries = 0; tries < 40; tries++) {
			double px = 3 + Utils.random() * (cols - 6);
			double py = 3 + Utils.random() * (rows - 6);
			if (getWorld().getTile(px, py, surfaceZ).isWalkable()) {
				x = px;
				y = py;
				break;
			}
		}
		TestNPC t = isPrey ? TestNPC.breeder(x, y, surfaceZ, g).withHerding() // born at its size-scaled reserve
				: TestNPC.predator(x, y, surfaceZ, g);
		getWorld().spawnEntity(t.withDeathspan(ECO_DEATHSPAN));
	}

	/** Spawns one minded creature at a random open surface tile. Under survivor-
	 *  seeding it descends from the longest-lived minded creature currently alive
	 *  (a mutated child, inheriting its brain); only a wiped-out cohort falls back
	 *  to a fresh random lineage. */
	private void seedMinded() {
		Genome g = Worlds.mindedReseedGenome(getWorld());
		double x = cols / 2.0, y = rows / 2.0;
		for (int tries = 0; tries < 40; tries++) {
			double px = 3 + Utils.random() * (cols - 6);
			double py = 3 + Utils.random() * (rows - 6);
			if (getWorld().getTile(px, py, surfaceZ).isWalkable()) {
				x = px;
				y = py;
				break;
			}
		}
		getWorld().spawnEntity(TestNPC.mindedForager(x, y, surfaceZ, g).withDeathspan(ECO_DEATHSPAN));
	}

	/** Removes up to {@code count} minded creatures (iteration order) -- the ceiling
	 *  for the hybrid cohort, which the role-keyed {@link #trim} does not cover.
	 *
	 *  <p>Hand-placed creatures (a genome someone injected from the viewer) are
	 *  never culled: deleting what a person deliberately dropped -- healthy, and
	 *  with no corpse to show for it -- reads as the world eating your creature.
	 *  An injection therefore displaces one of the steward's own, and the cohort
	 *  stays just as bounded (the founder still counts toward the ceiling, and its
	 *  offspring are ordinary cullable citizens). */
	private void trimMinded(int count) {
		int removed = 0;
		for (Entity e : getWorld().getEntities()) {
			if (removed >= count) {
				break;
			}
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved() && t.isMinded()
					&& !t.isHandPlaced()) {
				t.remove();
				removed++;
			}
		}
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
	public String getEntityTypeName() {
		return "steward";
	}
}
