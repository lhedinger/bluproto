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
	/** Bounds for the scavenger cohort. Its own guardrails rather than the minded
	 *  cohort's, because it feeds on a completely different supply: carrion is
	 *  produced by every death in the world, so a scavenger population is coupled to
	 *  total mortality and not to grass. Held together they would mask each other. */
	private final int[] scavBounds;
	/** Bounds for the parasite cohort — again its own guardrails: parasites
	 *  feed on the standing herd (living mass, not carrion or grass), so their
	 *  population couples to host abundance and to nothing the other floors
	 *  and ceilings watch. */
	private final int[] paraBounds;
	private final Genome[] preySpecies, predSpecies;
	/** Floor for the emergent-mind lineage. NOT a population bound -- see think().
	 *  There is deliberately no matching ceiling: how many minded creatures the
	 *  world carries is decided by the role bounds they are counted under. */
	private final int mindedFloor;
	private final int cols, rows;
	private final int surfaceZ; // the open-air level the herd lives on
	private final int caveZ; // the underground level, or -1 for a one-level world
	private int n = 0; // rotates species / placement, deterministically
	private boolean seedBelow = false; // alternates minded reseeds between the levels

	/** Corpse lifespan for reseeded creatures (matches Worlds.ECO_DEATHSPAN). */
	private static final int ECO_DEATHSPAN = 90;

	WorldSteward(World w, Genome[] preySpecies, Genome[] predSpecies, int surfaceZ,
			int[] preyBounds, int[] predBounds, int mindedFloor) {
		this(w, preySpecies, predSpecies, surfaceZ, -1, preyBounds, predBounds, mindedFloor);
	}

	WorldSteward(World w, Genome[] preySpecies, Genome[] predSpecies, int surfaceZ,
			int caveZ, int[] preyBounds, int[] predBounds, int mindedFloor) {
		this(w, preySpecies, predSpecies, surfaceZ, caveZ, preyBounds, predBounds,
				mindedFloor, new int[] { 0, Integer.MAX_VALUE },
				new int[] { 0, Integer.MAX_VALUE });
	}

	WorldSteward(World w, Genome[] preySpecies, Genome[] predSpecies, int surfaceZ,
			int caveZ, int[] preyBounds, int[] predBounds, int mindedFloor,
			int[] scavBounds, int[] paraBounds) {
		super(w.getColums() / 2.0, w.getRows() / 2.0, surfaceZ, 0.0); // centre; direction ctor draws no RNG
		this.cols = w.getColums();
		this.rows = w.getRows();
		this.surfaceZ = surfaceZ;
		this.caveZ = caveZ;
		this.preySpecies = preySpecies;
		this.predSpecies = predSpecies;
		this.preyBounds = preyBounds;
		this.predBounds = predBounds;
		this.mindedFloor = mindedFloor;
		this.scavBounds = scavBounds;
		this.paraBounds = paraBounds;
	}

	@Override
	protected void think() {
		int preyMin = preyBounds[0];
		int preyMax = preyBounds[1];
		int predMin = predBounds[0];
		int predMax = predBounds[1];

		// One census, keyed on ROLE alone -- one bucket per trophic level, and every
		// creature in exactly one of them. Whether a body is steered by a hardcoded
		// loop or an evolved program is not an ecological fact about it and does not
		// belong in a population bound: a herbivore with a brain competes for the
		// same grass, is hunted by the same predators, and leaves the same carcass
		// as one without. Counting the minded apart made them a cohort with their
		// own guardrails, which meant the same animal was governed differently
		// depending on what was driving it -- and left every minded herbivore
		// missing from the prey count that is supposed to describe the herd.
		int prey = 0, pred = 0, scav = 0, para = 0, minded = 0;
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()) {
				switch (t.ecoRole()) {
					case "prey" -> prey++;
					case "predator" -> pred++;
					case "scavenger" -> scav++;
					case "parasite" -> para++;
					default -> { } // roleless: outside the ecosystem, ungoverned
				}
				// Tallied, but NOT a population bound -- see the lineage guard below.
				if (t.isMinded()) {
					minded++;
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
		// The one rule here that is NOT a population bound. Mindedness is a control
		// method, not a trophic level, so it has no cohort of its own in the census
		// above -- a minded herbivore is governed as prey, because that is what it
		// is. But the emergent lineage can still go extinct through ordinary
		// ecology, and if it does nothing brings it back: the prey floor reseeds
		// hardcoded breeders, so the world would quietly become an all-scripted one
		// with no test failing.
		//
		// This is therefore a LINEAGE guard, not a headcount, and deliberately has
		// no matching ceiling: how many minded creatures the world carries is
		// settled by the role bounds they are counted under, like any other animal.
		if (minded < mindedFloor) {
			seedMinded();
		}
		// Ceiling: trim a small, fixed number of the excess per tick, so the herd
		// is thinned gradually rather than culled in one blow.
		if (prey > preyMax) {
			trim("prey", Math.min(3, prey - preyMax));
		}
		if (pred > predMax) {
			trim("predator", Math.min(2, pred - predMax));
		}

		// The scavenger cohort. Its floor is conditional on there being anything to
		// scavenge: reseeding one into a world with no bodies is spawning it to
		// starve, and the corpse layer is the whole of its living.
		if (scav < scavBounds[0] && carrionPresent()) {
			seedScavenger();
		}
		if (scav > scavBounds[1]) {
			trim("scavenger", Math.min(3, scav - scavBounds[1]));
		}

		// The parasite cohort. Its floor is conditional on there being a body
		// worth riding — a parasite reseeded into an empty world starves on its
		// feet — and its ceiling is deliberately low: the supply is the standing
		// herd, and enough parasites bleed it faster than it breeds.
		if (para < paraBounds[0] && hostPresent()) {
			seedParasite();
		}
		if (para > paraBounds[1]) {
			trim("parasite", Math.min(3, para - paraBounds[1]));
		}
	}

	/** Whether any body big enough to host a parasite is alive — the
	 *  precondition for the niche existing at all. */
	private boolean hostPresent() {
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
					&& t.getSize() > TestNPC.PARASITE_MAX_SIZE
					&& !t.ecoRole().equals("parasite")) {
				return true;
			}
		}
		return false;
	}

	/** Spawns one minded parasite, under the same survivor-seeding as the rest
	 *  of the cohort, on the surface where the herds are. */
	private void seedParasite() {
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
		getWorld().spawnEntity(
				TestNPC.mindedParasite(x, y, surfaceZ, g).withDeathspan(ECO_DEATHSPAN));
	}

	/** Whether any carcass is lying about — the precondition for a scavenger
	 *  having a living at all. */
	private boolean carrionPresent() {
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof TestNPC t && t.isDead() && !t.isRemoved()) {
				return true;
			}
		}
		return false;
	}

	/** Spawns one minded scavenger, descending from the longest-lived minded
	 *  creature alive where there is one (same survivor-seeding as the rest of the
	 *  cohort), on the surface where the bodies mostly fall. */
	private void seedScavenger() {
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
		getWorld().spawnEntity(
				TestNPC.mindedScavenger(x, y, surfaceZ, g).withDeathspan(ECO_DEATHSPAN));
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

	/** Spawns one minded creature at a random open tile. Reseeds alternate
	 *  between the surface and the underground (when the world has one), so the
	 *  cave cohort persists instead of draining one-way to the surface. Under
	 *  survivor-seeding the newcomer descends from the longest-lived minded
	 *  creature currently alive (a mutated child, inheriting its brain); only a
	 *  wiped-out cohort falls back to a fresh random lineage. */
	private void seedMinded() {
		Genome g = Worlds.mindedReseedGenome(getWorld());
		int z = seedBelow && caveZ >= 0 ? caveZ : surfaceZ;
		seedBelow = !seedBelow;
		double x = cols / 2.0, y = rows / 2.0;
		for (int tries = 0; tries < 40; tries++) {
			double px = 3 + Utils.random() * (cols - 6);
			double py = 3 + Utils.random() * (rows - 6);
			var t = getWorld().getTile(px, py, z);
			// Underground, never onto a drop: pits on the lowest level are
			// bottomless, and a reseed into the void is a wasted creature.
			if (t.isWalkable() && !(z != surfaceZ && t.isDrop())) {
				x = px;
				y = py;
				break;
			}
		}
		getWorld().spawnEntity(TestNPC.mindedForager(x, y, z, g).withDeathspan(ECO_DEATHSPAN));
	}

	/**
	 * Removes up to {@code count} of the given role (iteration order).
	 *
	 * <p>Hand-placed creatures (a genome someone injected from the viewer) are
	 * never culled: deleting what a person deliberately dropped -- healthy, and
	 * with no corpse to show for it -- reads as the world eating your creature. An
	 * injection therefore displaces one of the steward's own, and the role stays
	 * just as bounded (the founder still counts toward the ceiling, and its
	 * offspring are ordinary cullable citizens). This protection used to live only
	 * in the minded cohort's own trim; now that every creature is trimmed by role
	 * it belongs here, or injecting a plain grazer would make it culler-bait.
	 */
	private void trim(String role, int count) {
		int removed = 0;
		for (Entity e : getWorld().getEntities()) {
			if (removed >= count) {
				break;
			}
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
					&& t.ecoRole().equals(role) && !t.isHandPlaced()) {
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
