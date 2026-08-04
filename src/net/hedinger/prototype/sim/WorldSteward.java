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

	// Population bounds {min, max} at the two extremes of the year; the live
	// bounds are interpolated between them by the season, so the world holds
	// more life in summer and less in winter.
	private final int[] preyWinter, preySummer, predWinter, predSummer;
	private final Genome[] preySpecies, predSpecies;
	private final int mindedFloor, mindedMax; // hold the minded cohort within [floor, max]
	private final int cols, rows;
	private final int surfaceZ; // the open-air level the herd lives on
	private int n = 0; // rotates species / placement, deterministically

	/** Corpse lifespan for reseeded creatures (matches Worlds.ECO_DEATHSPAN). */
	private static final int ECO_DEATHSPAN = 90;

	// --- seasons: a slow fertility cycle that makes the world breathe ---------
	/** Ticks in one seasonal cycle (~3 min at 33 t/s): summer -> winter -> summer. */
	private static final int YEAR_TICKS = 6000;
	/** Fertility scale oscillates in [MID-AMP, MID+AMP]: summers push the grass to
	 *  its lush cap, winters starve it back. (setFertility clamps at 1.0, so the
	 *  summer overshoot just means the peak plateaus at full lushness.) */
	private static final double SEASON_MID = 0.85, SEASON_AMP = 0.40;
	/** Each floor tile's world-gen fertility, captured once; seasons scale this. */
	private double[] baseFertility;

	/** Seasonal fertility multiplier at a tick — 1.0 at peak summer down to
	 *  {@code MID-AMP} in deep winter. Pure function of the tick, so it replays
	 *  identically. */
	public static double seasonFactor(long tick) {
		return SEASON_MID + SEASON_AMP * Math.sin(2 * Math.PI * (tick % YEAR_TICKS) / YEAR_TICKS);
	}

	/** Season phase in [0,1]: 0 = deep winter, 1 = peak summer. */
	public static double seasonPhase(long tick) {
		return (seasonFactor(tick) - (SEASON_MID - SEASON_AMP)) / (2 * SEASON_AMP);
	}

	/** Human label for the current point in the year, from the phase and whether
	 *  it is waxing (spring) or waning (autumn). */
	public static String seasonLabel(long tick) {
		double p = seasonPhase(tick);
		boolean waxing = seasonPhase(tick + 1) >= p;
		if (p > 0.75) {
			return "summer";
		}
		if (p < 0.25) {
			return "winter";
		}
		return waxing ? "spring" : "autumn";
	}

	WorldSteward(World w, Genome[] preySpecies, Genome[] predSpecies, int surfaceZ,
			int[] preyWinter, int[] preySummer, int[] predWinter, int[] predSummer,
			int mindedFloor, int mindedMax) {
		super(w.getColums() / 2.0, w.getRows() / 2.0, surfaceZ, 0.0); // centre; direction ctor draws no RNG
		this.cols = w.getColums();
		this.rows = w.getRows();
		this.surfaceZ = surfaceZ;
		this.preySpecies = preySpecies;
		this.predSpecies = predSpecies;
		this.preyWinter = preyWinter;
		this.preySummer = preySummer;
		this.predWinter = predWinter;
		this.predSummer = predSummer;
		this.mindedFloor = mindedFloor;
		this.mindedMax = mindedMax;
	}

	/** Interpolates an integer bound between its winter and summer value. */
	private static int lerp(int winter, int summer, double phase) {
		return (int) Math.round(winter + (summer - winter) * phase);
	}

	@Override
	protected void think() {
		long tick = getWorld().getTick();
		applySeason(tick);

		// Live bounds track the season: high in summer, low in winter, so the
		// headcount visibly rises and falls with the grass.
		double phase = seasonPhase(tick);
		int preyMin = lerp(preyWinter[0], preySummer[0], phase);
		int preyMax = lerp(preyWinter[1], preySummer[1], phase);
		int predMin = lerp(predWinter[0], predSummer[0], phase);
		int predMax = lerp(predWinter[1], predSummer[1], phase);

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

		// Floor: reseed a couple per tick until the (seasonal) minimum is
		// restored, so a crash — or a spring thaw — recovers as a bloom.
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
		// Ceiling on the minded cohort too: a viable (fed, breeding) lineage would
		// otherwise swarm, since minded creatures aren't culled by the prey/predator
		// caps. Keep it a small, watchable A/B group.
		if (minded > mindedMax) {
			trimMinded(Math.min(3, minded - mindedMax));
		}

		// Ceiling: trim a small, fixed number of the excess per tick, so a
		// falling seasonal cap thins the herd gradually rather than culling it.
		if (prey > preyMax) {
			trim("prey", Math.min(3, prey - preyMax));
		}
		if (pred > predMax) {
			trim("predator", Math.min(2, pred - predMax));
		}
	}

	/**
	 * Seasons: scale every floor tile's fertility by {@link #seasonFactor} of the
	 * current tick. The base (world-gen, patchy) fertility is captured on the
	 * first tick and never mutated, so the seasonal cycle preserves the map's
	 * rich/poor habitat pattern while raising and lowering the whole grassland's
	 * carrying capacity. Grass is lush in summer (prey breed and boom) and scarce
	 * in winter (prey thin, predators follow) — a Lotka-Volterra rhythm the flat
	 * steward floor otherwise damps out. Cheap: a single sweep of doubles.
	 */
	private void applySeason(long tick) {
		World w = getWorld();
		if (baseFertility == null) {
			baseFertility = new double[cols * rows];
			for (int y = 0; y < rows; y++) {
				for (int x = 0; x < cols; x++) {
					baseFertility[y * cols + x] = w.getTile(x, y, surfaceZ).getFertility();
				}
			}
		}
		double factor = seasonFactor(tick);
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				w.getTile(x, y, surfaceZ).setFertility(baseFertility[y * cols + x] * factor);
			}
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
	protected void draw(Graphics g, View v) {
		// Invisible: the steward is machinery, not a creature.
	}

	@Override
	public String getEntityTypeName() {
		return "steward";
	}
}
