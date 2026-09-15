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
 *   <li><b>Floor (never empty):</b> if a clade falls below its minimum it is
 *       restored at double that minimum, as kin groups of {@link #RESEED_GROUP}
 *       founders each -- a boost, and company, rather than a trickle of
 *       strangers. A clade whose food is other bodies waits for the food.</li>
 *   <li><b>Ceiling (never a swarm):</b> if a population overshoots its cap it
 *       trims the excess a few at a time — a safety net above the natural
 *       predation/starvation control, so it rarely actually fires.</li>
 * </ul>
 *
 * <p>The ceiling is no longer enforced by deletion. When a cohort overshoots,
 * the steward publishes a {@link CullOrders cull order} — a cohort and the
 * headcount to leave standing — and the {@link StewardDrone} flies out of the
 * buried base and does the killing. The steward keeps counting either way, and
 * drops the order the moment the target is met. Deletion survives only as the
 * {@link #BACKSTOP} below: a population that has run far past its ceiling, or
 * one in a world with no drone to send, is still trimmed outright, because a
 * grounded drone must not become a way for the world to swarm.
 *
 * It is deterministic (all placement via the seeded {@link Utils#random}) and
 * runs inside {@code world.think()}, so a snapshot stream stays reproducible.
 * It perceives nothing, collides with nothing, and is filtered out of snapshots
 * — the viewer never sees it.
 */
public final class WorldSteward extends Entity implements CullOrders {

	// Population bounds {min, max}. Fixed, not seasonal: the world's headcount is
	// left to grass, predation and starvation, and these are only the guardrails
	// that stop it emptying or swarming.
	private final int[] herbBounds, predBounds;
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
	/** How many founders a reseed lands at once, as one kin group: a species'
	 *  worth of company, clustered, so a restored niche is a herd or a pack and
	 *  not a scatter of strangers who never meet. */
	public static final int RESEED_GROUP = 5;
	/** How far past its floor a clade is restored, as a multiple of the floor:
	 *  a clade that has crossed its floor is failing, and putting it back exactly
	 *  on the line has it crossing again within the hour. Double is a boost to
	 *  breed from. */
	public static final int RESEED_BOOST = 2;
	private final int cols, rows;
	private final int surfaceZ; // the open-air level the herd lives on
	private final int caveZ; // the underground level, or -1 for a one-level world
	private boolean seedBelow = false; // alternates minded reseeds between the levels


	/**
	 * Where a cull stops: the drone thins a cohort to this fraction of its
	 * ceiling, not to the ceiling itself.
	 *
	 * <p>Culling to the line exactly would put the population back one animal
	 * over it within a few ticks of breeding, and the drone would live in the
	 * air — permanently out, permanently killing, and the world would read as
	 * policed rather than as governed. Leaving a margin buys the world room to
	 * breed back into and buys the drone a long spell docked between sorties,
	 * which is the whole difference between machinery you notice and machinery
	 * that is always there.
	 */
	public static final double CULL_TO = 0.7;

	/**
	 * How far past its ceiling a cohort must run before the steward stops
	 * waiting for the drone and deletes the excess itself.
	 *
	 * <p>The drone is the visible mechanism, not the guarantee. It can be
	 * across the map, walled off behind rock, or simply absent from a world
	 * nobody built a base into — and none of those may be allowed to become a
	 * way for a population to run away, because the ceiling is the promise this
	 * whole class exists to keep. So the old deletion stays, moved just far
	 * enough up to leave the drone room to act first.
	 *
	 * <p>Only just far enough, and the number is measured rather than chosen.
	 * At 1.5 the seeded world was run 20k ticks and the band plainly did not
	 * work: one drone kills at roughly 0.08 bodies a tick even in a dense herd,
	 * a herd near its carrying capacity breeds faster than that, and so prey sat
	 * pinned at 240 against a ceiling of 160 and the minded cohort at 375
	 * against 250 — every standing population half again as large as its ceiling
	 * said, for as long as the world ran. A backstop the drone cannot beat is
	 * not a backstop, it is a new and higher ceiling. At 1.1 the ceilings mean
	 * what they meant before the drone existed, and what the drone owns is the
	 * margin below them.
	 */
	public static final double BACKSTOP = 1.1;

	// The standing order, republished (or cleared) every tick from the fresh
	// counts. One at a time: there is one drone, and an order it cannot get to
	// the end of is not an order.
	private String cullRole = null;
	private int cullTarget = 0;

	/** The floor the steward holds {@code clade} above -- a probe for the
	 *  scenario suite, so a test can ask the warden rather than restate its
	 *  numbers. */
	public int floor(Genome.Clade clade) {
		return bounds(clade)[0];
	}

	/** The ceiling it holds {@code clade} under. */
	public int ceiling(Genome.Clade clade) {
		return bounds(clade)[1];
	}

	private int[] bounds(Genome.Clade clade) {
		return switch (clade) {
		case HERBIVORE -> herbBounds;
		case PREDATOR -> predBounds;
		case SCAVENGER -> scavBounds;
		case PARASITE -> paraBounds;
		};
	}

	@Override
	public String cullRole() {
		return cullRole;
	}

	@Override
	public int cullTarget() {
		return cullTarget;
	}

	/**
	 * Which of the steward's cohorts a creature belongs to — "herbivore",
	 * "predator", "scavenger", "parasite", or "" for a body outside
	 * the bookkeeping entirely.
	 *
	 * <p>One definition, three readers: the per-tick count, the backstop trim,
	 * and the drone's choice of target. They were separate before the drone
	 * existed and could afford to be, since the counter and the trimmer sat ten
	 * lines apart in the same file. With the killing moved into another entity
	 * they cannot: a drone that disagreed with the steward about what counts as
	 * a scavenger would fly out and kill until the count it was not looking at
	 * came down, which is to say until the world ran out of scavengers.
	 *
	 * <p>The order of the tests is the meaning. Scavengers and parasites are
	 * minded too, and are named first precisely so they are counted apart: each
	 * eats from a supply of its own (carrion; the standing herd) that the
	 * minded cohort's floor and ceiling know nothing about.
	 */
	public static String cohortOf(TestNPC t) {
		var c = cohortCladeOf(t);
		return c == null ? "" : c.wireName();
	}

	/**
	 * The typed form of {@link #cohortOf}: the clade a creature is governed
	 * under, or null for a body outside the bookkeeping. The role, and only
	 * the role. Whether a hardcoded loop or an evolved program is steering is
	 * not an ecological fact about a creature and does not belong in a
	 * population bound: a herbivore with a brain competes for the same grass,
	 * is hunted by the same predators, and leaves the same carcass as one
	 * without. Counting the minded apart made them a cohort with guardrails of
	 * their own, so the same animal was governed one way with a brain and
	 * another way without -- and every minded herbivore was missing from the
	 * count that is supposed to describe the herd.
	 */
	public static Genome.Clade cohortCladeOf(TestNPC t) {
		if (t == null || t.isDead() || t.isRemoved()) {
			return null;
		}
		return t.ecoClade();
	}

	/**
	 * Whether the drone may kill this body — the cull's one exemption, and the
	 * same one the trim has always honoured for the minded cohort, now applied
	 * to every cohort alike.
	 *
	 * <p>Deleting what a person deliberately dropped into the world — healthy,
	 * and with no corpse to show for it — reads as the world eating your
	 * creature. An injection therefore displaces one of the steward's own and
	 * the cohort stays just as bounded: the founder still counts toward the
	 * ceiling, and its offspring are ordinary cullable citizens.
	 *
	 * <p>It matters more now than it did. A silent deletion was at least
	 * ambiguous; a machine flying across the map to shoot the animal someone
	 * placed by hand is not.
	 */
	public static boolean isCullable(TestNPC t, String role) {
		return t != null && !t.isHandPlaced() && cohortOf(t).equals(role);
	}

	WorldSteward(World w, int surfaceZ,
			int[] herbBounds, int[] predBounds) {
		this(w, surfaceZ, -1, herbBounds, predBounds);
	}

	WorldSteward(World w, int surfaceZ,
			int caveZ, int[] herbBounds, int[] predBounds) {
		this(w, surfaceZ, caveZ, herbBounds, predBounds,
				new int[] { 0, Integer.MAX_VALUE },
				new int[] { 0, Integer.MAX_VALUE });
	}

	WorldSteward(World w, int surfaceZ,
			int caveZ, int[] herbBounds, int[] predBounds,
			int[] scavBounds, int[] paraBounds) {
		super(w.getColums() / 2.0, w.getRows() / 2.0, surfaceZ, 0.0); // centre; direction ctor draws no RNG
		this.cols = w.getColums();
		this.rows = w.getRows();
		this.surfaceZ = surfaceZ;
		this.caveZ = caveZ;
		this.herbBounds = herbBounds;
		this.predBounds = predBounds;
		this.scavBounds = scavBounds;
		this.paraBounds = paraBounds;
	}

	@Override
	protected void think() {
		int herb = 0, pred = 0, scav = 0, para = 0;
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof TestNPC t) {
				var cohort = cohortCladeOf(t);
				if (cohort != null) { // else: the drone, hand-placed oddities,
					// anything whose role has not settled into a guild
					switch (cohort) {
					case HERBIVORE -> herb++;
					case PREDATOR -> pred++;
					case SCAVENGER -> scav++;
					case PARASITE -> para++;
					}
				}
			}
		}

		// Floors. A clade that has fallen below its minimum is restored at
		// RESEED_BOOST times that minimum, all at once, as kin groups of
		// RESEED_GROUP founders -- one rule for every clade. It used to be a
		// trickle: one or two bodies a tick until the line was met, each a
		// stranger scattered across the map, so a clade held at its floor was a
		// niche kept occupied and extinct at the same time. Three of the clades eat
		// other bodies, and each of those waits for its food to exist: a hunter
		// reseeded into a world with no herd, a scavenger into one with no corpse,
		// a parasite into one with nothing big enough to ride, is spawned to
		// starve.
		if (herb < herbBounds[0]) {
			restore(Genome.Clade.HERBIVORE, herbBounds[0]);
		}
		if (pred < predBounds[0] && preyPresent()) {
			restore(Genome.Clade.PREDATOR, predBounds[0]);
		}
		if (scav < scavBounds[0] && carrionPresent()) {
			restore(Genome.Clade.SCAVENGER, scavBounds[0]);
		}
		if (para < paraBounds[0] && hostPresent()) {
			restore(Genome.Clade.PARASITE, paraBounds[0]);
		}

		ceilings(new int[] { herb, pred, scav, para },
				new int[] { herbBounds[1], predBounds[1], scavBounds[1], paraBounds[1] });
	}

	/** Puts {@link #RESEED_BOOST} x {@code floor} founders of {@code clade} into
	 *  the world, in whole kin groups -- rounded up, so a floor that is not a
	 *  multiple of the group still lands complete groups. */
	private void restore(Genome.Clade clade, int floor) {
		int want = RESEED_BOOST * floor;
		int groups = (want + RESEED_GROUP - 1) / RESEED_GROUP;
		for (int i = 0; i < groups; i++) {
			seedGroup(clade);
		}
	}

	/**
	 * One kin group of {@code clade}: a founder recipe drawn once, and
	 * {@link #RESEED_GROUP} bodies that are each a small mutation of it, landed
	 * together within {@link Worlds#SEED_CLUSTER_RADIUS} of one open spot. Kin
	 * because a group of unrelated founders is five strangers who cannot breed
	 * with each other; a founder's markers are drawn at random, and a mate has to
	 * be alike. Together because a body of any clade lives on what is near it,
	 * and a sexual lineage on who is.
	 *
	 * <p>Herbivore groups alternate between the surface and the underground
	 * (when the world has one), so the cave cohort persists instead of draining
	 * one-way to the surface. The others live where the herds are.
	 */
	private void seedGroup(Genome.Clade clade) {
		int z = surfaceZ;
		if (clade == Genome.Clade.HERBIVORE && caveZ >= 0) {
			z = seedBelow ? caveZ : surfaceZ;
			seedBelow = !seedBelow;
		}
		boolean avoidDrops = z != surfaceZ;
		double[] anchor = openSpot(z, avoidDrops);
		Genome founder = Worlds.founderGenome(clade);
		for (int i = 0; i < RESEED_GROUP; i++) {
			double[] p = Worlds.spotNear(getWorld(), anchor[0], anchor[1], z, avoidDrops);
			if (p == null) {
				p = anchor; // nothing walkable within reach of the anchor: pile on it
			}
			Genome g = Genome.child(founder, Worlds.KIN_RATE);
			TestNPC body = switch (clade) {
			case HERBIVORE -> TestNPC.mindedForager(p[0], p[1], z, g);
			case PREDATOR -> TestNPC.mindedPredator(p[0], p[1], z, g);
			case SCAVENGER -> TestNPC.mindedScavenger(p[0], p[1], z, g);
			case PARASITE -> TestNPC.mindedParasite(p[0], p[1], z, g);
			};
			getWorld().spawnEntity(body); // its corpse lasts as long as its body took to build
		}
	}

	/** A random walkable spot on level {@code z} -- the anchor a kin group lands
	 *  around. Underground, never a drop: a pit on the lowest level is
	 *  bottomless, and a founder seeded into one is wasted. */
	private double[] openSpot(int z, boolean avoidDrops) {
		for (int tries = 0; tries < 40; tries++) {
			double px = 3 + Utils.random() * (cols - 6);
			double py = 3 + Utils.random() * (rows - 6);
			var t = getWorld().getTile(px, py, z);
			if (t.isWalkable() && !(avoidDrops && t.isDrop())) {
				return new double[] { px, py };
			}
		}
		return new double[] { cols / 2.0, rows / 2.0 };
	}

	/** The cohort keys — the clades, whose declaration order is the fixed
	 *  order the ceilings are checked in, so which cohort the drone is sent
	 *  after when two overshoot at once is a fact about the world and not
	 *  about iteration order. Deriving from the enum means a new clade can
	 *  never be missing from the governor's books. */
	private static final Genome.Clade[] COHORTS = Genome.Clade.values();

	/**
	 * Every cohort's ceiling, from this tick's fresh counts: republishes the
	 * order the drone flies on, and deletes outright anything that has run past
	 * {@link #BACKSTOP}.
	 *
	 * <p>Two lines, not one. An order is <b>raised</b> when a cohort crosses its
	 * ceiling and <b>cleared</b> only once it is back down to {@link #CULL_TO}
	 * of it — the band that turns a boundary into a job of work. Culling to the
	 * ceiling exactly would put the population one animal over it again within
	 * a few ticks of breeding, and the drone would never get home; the margin is
	 * what buys the world room to breed back into and the drone a long spell
	 * docked between sorties.
	 *
	 * <p>A running order keeps its place ahead of a fresh overshoot elsewhere.
	 * Abandoning a half-finished cull to start another is a trip across the map
	 * for nothing, and with the counts moving under it the drone could be handed
	 * back and forth between two cohorts without finishing either.
	 *
	 * <p>Everything is re-derived from the counts rather than remembered, so an
	 * order clears itself the moment the population is back under target —
	 * including when what brought it down was starvation or a predator rather
	 * than the drone. Nothing has to remember to cancel it.
	 */
	private void ceilings(int[] counts, int[] maxes) {
		String running = cullRole;
		cullRole = null;
		for (int i = 0; i < COHORTS.length; i++) {
			int max = maxes[i];
			if (max <= 0 || max == Integer.MAX_VALUE) {
				continue; // no ceiling worth the name (the unbounded default)
			}
			int target = (int) Math.round(max * CULL_TO);
			boolean keep = COHORTS[i].wireName().equals(running) && counts[i] > target;
			if (keep || (counts[i] > max && cullRole == null)) {
				cullRole = COHORTS[i].wireName();
				cullTarget = target;
			}
			if (counts[i] > max * BACKSTOP) {
				// Far past the line: stop waiting for a machine that may never
				// arrive and take the excess off by hand, a few at a time,
				// exactly as the steward always did.
				trim(COHORTS[i].wireName(), Math.min(3, counts[i] - max));
			}
		}
	}

	/** Whether any body big enough to host a parasite is alive — the
	 *  precondition for the niche existing at all.
	 *
	 *  <p>Compared in PIXELS ({@link TestNPC#getPixelSize()}), the unit the
	 *  parasite size cap is written in. Asking {@code getSize()} instead
	 *  compares tiles against a pixel constant — every body in the world is
	 *  under a third of a tile across, so the test answered "no host anywhere"
	 *  forever and this floor never once fired. */
	private boolean hostPresent() {
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
					&& t.getPixelSize() > TestNPC.PARASITE_MAX_SIZE_PX
					&& t.ecoClade() != Genome.Clade.PARASITE) {
				return true;
			}
		}
		return false;
	}

	/** Whether any carcass is lying about — the precondition for a scavenger
	 *  having a living at all. */
	private boolean carrionPresent() {
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof TestNPC t && t.isDead() && !t.isRemoved() && t.meatLeft() > 0) {
				return true; // a carcass with flesh on it: a kill eaten out is not a living
			}
		}
		return false;
	}

	/** Whether a live herbivore exists — the precondition for reseeding a hunter,
	 *  the way carrion is for a scavenger. The herd is what a hunter lives on, and
	 *  this asks the coarse question "is there a food supply at all" rather than
	 *  re-deciding the size ratio: nearestPrey owns which bodies are actually
	 *  takeable, and a second copy of that rule here would be one to keep in step
	 *  for no gain. */
	private boolean preyPresent() {
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
					&& t.getGenome() != null
					&& t.getGenome().clade == Genome.Clade.HERBIVORE) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Removes up to {@code count} of the given cohort outright (iteration
	 * order) — the backstop, and the last thing in the class that still kills
	 * by deletion.
	 *
	 * <p>Honours the same hand-placed exemption the drone does, via the shared
	 * {@link #isCullable} test: the two of them are enforcing one ceiling
	 * between them, and a body the drone is forbidden to shoot must not be one
	 * the steward quietly deletes instead.
	 */
	private void trim(String role, int count) {
		int removed = 0;
		for (Entity e : getWorld().getEntities()) {
			if (removed >= count) {
				break;
			}
			if (e instanceof TestNPC t && isCullable(t, role)) {
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
