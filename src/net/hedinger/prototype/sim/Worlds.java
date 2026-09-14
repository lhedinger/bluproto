package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.GroundTextures;
import net.hedinger.prototype.engine.Perf;
import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.simtest.TestNPC;

/**
 * Deterministic world factories for headless hosting: seed in, living world
 * out. The demo world the web server serves is a living, evolving ecosystem —
 * a fertile grassland grazed by breeding herbivores, hunted by predators, kept
 * inside sane population bounds by a {@link WorldSteward} so a public,
 * always-on world never dies out or swarms.
 *
 * <p>Uses {@code TestNPC} fixtures for the population — deliberately: the
 * roadmap retires the legacy bestiary in favour of genome-driven species, and
 * the fixtures are today's cleanest bodies. When real species graduate out of
 * the fixture package, this factory swaps them in.
 */
public final class Worlds {

	private Worlds() {
	}


	/** Herbivore "species": small, grazing prey — distinct marker barcodes drive
	 *  distinct procedural bodies/colours; all metabolic breeders (they evolve). */
	private static net.hedinger.prototype.entities.Genome[] herbivoreSpecies() {
		// Warm/cool hues, deliberately NOT green — herbivores should read clearly
		// against the green meadow, not camouflage into it.
		double[][] markers = {
				{ 0.90, 0.72, 0.40 }, // sand
				{ 0.55, 0.72, 0.92 }, // sky blue
				{ 0.74, 0.46, 0.86 }, // violet
				{ 0.95, 0.60, 0.35 }, // amber
		};
		double[] sizes = { 7, 8, 6, 9 };
		// Neutral metabolism efficiency (META_REF): the size-scaled energy model
		// does the work — reserve, resting burn and fasting endurance all follow
		// body size, so these small grazers hold a few minutes of reserve and the
		// bigger ones a little more. The herd stays food-limited: it booms where the
		// grass is rich and thins where grazing has stripped it.
		return species(markers, sizes, 0.018, 0.03, 0.02);
	}

	/** Predator "species": bigger, faster hunters — reddish barcodes so they read
	 *  as menacing against the green prey. Metabolic; they hunt, breed, starve. */
	private static net.hedinger.prototype.entities.Genome[] predSpecies() {
		double[][] markers = {
				{ 0.90, 0.20, 0.22 }, // red hunter
				{ 0.78, 0.28, 0.48 }, // crimson hunter
		};
		// Apex-sized, at the top of the band every genome is clamped to
		// (Genome.SIZE_MAX): paired with the "up to my own size" hunting rule this
		// makes a founder hunter able to take ANY creature in the world, including a
		// minded one that has drifted to the largest body a genome can express.
		double[] sizes = { 20, 18 };
		// Neutral metabolism efficiency (META_REF): the size-scaled model gives
		// these big hunters a large reserve and a long fasting endurance (bigger
		// body, bigger tank), so a predator drains gently between kills. Running
		// prey down is what costs it: movement is charged as mass * v^2, so a
		// full-speed pursuit burns far harder than its patrol and a long fruitless
		// chase still thins it.
		net.hedinger.prototype.entities.Genome[] out = species(markers, sizes, 0.045, 0.055, 0.02);
		for (net.hedinger.prototype.entities.Genome g : out) {
			// Say so in the genome. These have always hunted -- thinkPredator does the
			// work -- but the genome described a herbivore with no appetite for it,
			// which meant nothing downstream could tell a hunter from a grazer. The
			// body plan reads `diet`, and `predatory` is a disposition that ought to
			// match the animal carrying it; leaving it at zero on the world's actual
			// predators made the gene decorative. Behaviour is unaffected: a hunter
			// runs thinkPredator, not the react() weights this feeds.
			g.clade = net.hedinger.prototype.entities.Genome.Clade.PREDATOR;
			g.predatory = 0.9;
		}
		return out;
	}

	/** Minded "species": a small cohort whose behaviour comes from a fully-random
	 *  evolvable {@link net.hedinger.prototype.entities.Brain}, not a hardcoded rule.
	 *  Random bodies (so a role can emerge — a big one may learn to hunt, a small one
	 *  to graze) with a distinct greenish barcode, and a random brain each. They
	 *  compete inside the same world as the scripted species; most will flounder at
	 *  first (a random mind rarely feeds itself), which is the point of watching. */
	private static net.hedinger.prototype.entities.Genome[] mindedSpecies(int count) {
		net.hedinger.prototype.entities.Genome[] out =
				new net.hedinger.prototype.entities.Genome[count];
		for (int i = 0; i < count; i++) {
			out[i] = mindedGenome(i);
		}
		return out;
	}

	/** One founder minded genome: random dispositions, markers and body inside the
	 *  sane size band, and the hand-written {@link #starterBrain()} — a minimal
	 *  forager that mutation and survivor-seeding then refine. A fully-random brain
	 *  was tried first (Phase 3/4): it never stumbled onto feeding, so selection had
	 *  no gradient to climb. Seeding a viable-but-crude brain gives evolution a
	 *  foothold to improve from, while every other gene stays random. */
	static net.hedinger.prototype.entities.Genome mindedGenome() {
		return mindedGenome(0);
	}

	/**
	 * As {@link #mindedGenome()}, but picks the founder's starting brain by index
	 * so the cohort does not all begin with the same idea. Every third founder is a
	 * {@link #hitchhikerBrain() hitch-hiker} rather than a plain forager, so both
	 * strategies are in the world from the first tick and can be watched competing
	 * — which is the whole point of the minded cohort.
	 *
	 * <p>The index only chooses a (fixed, RNG-free) program, so the deterministic
	 * stream is identical to drawing every founder the old way.
	 */
	static net.hedinger.prototype.entities.Genome mindedGenome(int index) {
		net.hedinger.prototype.entities.Genome g = net.hedinger.prototype.entities.Genome.random();
		g.size = 5 + Utils.random() * 12; // 5..17: room for both grazer and hunter builds
		g.speed = 0.04 + Utils.random() * 0.03;
		g.metabolism = 0.02;
		// The founder's mind substrate, by index so the cohort does not all begin
		// with the same idea AND both decision methods are in the world from tick
		// zero. A quarter run the MLP network (its forage prior is its warm seed,
		// the way the starter brain is the LGP cohort's); of the rest, a third are
		// LGP hitch-hikers and the others LGP foragers. The two substrates then
		// compete under one economy — the A/B the whole minded cohort exists for.
		int pick = index % 4;
		if (pick == 3) {
			g.mlp = net.hedinger.prototype.entities.MlpBrain.random();
		} else {
			g.brain = (pick == 2) ? hitchhikerBrain() : starterBrain();
		}
		return g;
	}

	/**
	 * A minimal hand-written forager brain — the warm seed the minded cohort starts
	 * from, so it survives long enough for selection to have something to work on.
	 *
	 * <p>It is written as <b>intents</b>, and that is what makes it short. The whole
	 * of its living is one actuator: {@code A_SEEK = forage} sends it looking for
	 * grass, walks it there at a cheap pace, and grazes when it arrives — steering,
	 * searching, throttle and eating, from one instruction. When something bigger
	 * comes close the same slot flips to {@code -threat} and the identical machinery
	 * runs it away flat out. It breeds when it can afford to.
	 *
	 * <p>Nine instructions where the motor-level version needed fourteen, and under
	 * one-instruction-per-tick that is not a tidiness win: it is a third off the
	 * lineage's reaction time. That is the trade intents exist to offer — see
	 * {@link net.hedinger.prototype.entities.AgentIO#A_SEEK}.
	 *
	 * <p>Still crude on purpose. Mutation and survivor-seeding are meant to sharpen
	 * it — a better threat threshold, hunting, using the waypoint it never marks —
	 * which is the whole experiment.
	 */
	public static net.hedinger.prototype.entities.Brain starterBrain() {
		final int SET = net.hedinger.prototype.entities.Brain.SET;
		final int MOV = net.hedinger.prototype.entities.Brain.MOV;
		final int ADD = net.hedinger.prototype.entities.Brain.ADD;
		final int SENSE = net.hedinger.prototype.entities.Brain.SENSE;
		final int WRITE = net.hedinger.prototype.entities.Brain.WRITE;
		final int GT = net.hedinger.prototype.entities.Brain.GT;
		final int SKIPZ = net.hedinger.prototype.entities.Brain.SKIPZ;
		int[][] code = {
				{ SET, 1, 6, 0 }, // r1 = 0.1 (const[6]) -- the forage intent
				{ SET, 5, 7, 0 }, // r5 = 0.25 (const[7]) -- a cheap amble; movement costs v^2
				// The drink reflex: parched outranks foraging (a threat, below,
				// still outranks both). SEEK_WATER lives at magnitude >= 9, so the
				// intent value is composed as 4+4+2 from the const pool -- the warm
				// seed knows how to drink; evolution tunes or loses it from here.
				{ SENSE, 7, net.hedinger.prototype.entities.AgentIO.S_THIRST, 0 }, // r7 = how dry
				{ SET, 8, 8, 0 }, // r8 = 0.5 (const[8]) -- the dry line
				{ GT, 9, 7, 8 }, // r9 = parched?
				{ SET, 10, 11, 0 }, // r10 = 4 (const[11])
				{ ADD, 10, 10, 10 }, // r10 = 8
				{ SET, 11, 10, 0 }, // r11 = 2 (const[10])
				{ ADD, 10, 10, 11 }, // r10 = 10 -- names the water intent
				{ SKIPZ, 9, 0, 0 }, // sated -> keep foraging
				{ MOV, 1, 10, 0 }, // parched -> steer to water instead
				{ SENSE, 2, net.hedinger.prototype.entities.AgentIO.S_THREAT_PROX, 0 },
				{ SET, 3, 7, 0 }, // r3 = 0.25 (const[7]) threat threshold
				{ GT, 4, 2, 3 }, // r4 = something bigger is close?
				{ SKIPZ, 4, 0, 0 }, // nothing near -> keep foraging
				{ SET, 1, 1, 0 }, // r1 = -1 (const[1]) -- flee the threat
				{ SKIPZ, 4, 0, 0 }, // ...and only then
				{ SET, 5, 9, 0 }, // r5 = 1.0 (const[9]) -- run flat out
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_SEEK, 1, 0 }, // where, and what to do there
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_THROTTLE, 5, 0 }, // how hard: the mind's call
				{ SET, 6, 9, 0 }, // r6 = 1.0 (const[9])
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_MATE, 6, 0 }, // breed when well-fed
		};
		return new net.hedinger.prototype.entities.Brain(code);
	}

	/**
	 * A hitch-hiker: the forager's mirror image. Where the forager turns AWAY from
	 * anything bigger than itself, this one turns TOWARDS it and clings on — the
	 * same two sensors, read with the opposite sign.
	 *
	 * <p>Riding is a real strategy rather than a novelty. A voluntary passenger
	 * pays half metabolism, pays nothing at all for movement (the host covers the
	 * ground, and a load is billed to whoever is carrying it), and is not frozen —
	 * it keeps grazing and breeding while aboard. The catch is that "bigger than
	 * me" includes predators, so a hitch-hiker courts exactly the creatures most
	 * likely to eat it, and it feeds only from whatever tile its host happens to
	 * be standing on.
	 *
	 * <p>Boarding needs contact: {@code attachTo} refuses a host further away than
	 * the two bodies touching, which is why this steers toward its target instead
	 * of merely holding the actuator down and hoping.
	 *
	 * <p>Note that a juvenile is 35% of its adult size, so a young hitch-hiker's
	 * own parent counts as "bigger" — a lineage running this brain will be seen
	 * riding its mothers until it grows out of them.
	 */
	public static net.hedinger.prototype.entities.Brain hitchhikerBrain() {
		final int SET = net.hedinger.prototype.entities.Brain.SET;
		final int SENSE = net.hedinger.prototype.entities.Brain.SENSE;
		final int WRITE = net.hedinger.prototype.entities.Brain.WRITE;
		final int GT = net.hedinger.prototype.entities.Brain.GT;
		final int MOV = net.hedinger.prototype.entities.Brain.MOV;
		final int SKIPZ = net.hedinger.prototype.entities.Brain.SKIPZ;
		int[][] code = {
				{ SET, 1, 9, 0 }, // r1 = 1.0 (const[9]) -- seek the THREAT channel, i.e.
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_SEEK, 1, 0 }, // ...ride what's bigger
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_THROTTLE, 1, 0 }, // chase it down
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_EAT, 1, 0 }, // graze, aboard or not:
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_MATE, 1, 0 }, // a seek-threat doesn't feed you
				{ SENSE, 2, net.hedinger.prototype.entities.AgentIO.S_THREAT_PROX, 0 }, // r2 = how close
				{ SET, 3, 7, 0 }, // r3 = 0.25 (const[7]) boarding threshold
				{ GT, 4, 2, 3 }, // r4 = something bigger is within reach?
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_ATTACH, 4, 0 }, // cling; 0 lets go
		};
		return new net.hedinger.prototype.entities.Brain(code);
	}

	/**
	 * The genome for the steward's next minded reseed of one clade, under
	 * survivor-seeding: a mutated child of the longest-lived creature of THAT
	 * clade currently alive. Living longest <em>is</em> the fitness — a metabolic
	 * creature that can't feed itself starves, so the oldest one alive is the one
	 * coping best — so a lineage's reseeds descend from its own survival champion
	 * and inherit its (mutated) brain, rather than starting from scratch each
	 * death.
	 *
	 * <p>The clade used to be no part of this, and that was an accident of build
	 * order rather than a decision: when this was written "minded" was a single
	 * cohort, so one champion for it was the whole story. Scavengers were then
	 * added as a variant of that cohort and reused this as-is, parasites followed,
	 * and {@link net.hedinger.prototype.entities.Genome.Clade} — the concept that
	 * would have separated them — only arrived afterwards. Nothing revisited who
	 * the reseeds descend from.
	 *
	 * <p>What it cost is not subtle. All three minded seeders drew from one global
	 * argmax, so whichever body happened to be oldest parented every reseed in the
	 * world: measured over 100k ticks of the live world the champion was a
	 * HERBIVORE, and every scavenger and parasite spawned in that time was handed
	 * its grazing brain with a different clade stamped on top. A scavenger reseed
	 * inheriting a forager's mind is not survivor-seeding at all — the trait that
	 * kept the champion alive was competence at grass, which is not the job.
	 *
	 * <p>So a clade seeds from its own. When a clade has no survivors it restarts
	 * from a founder rather than borrowing another clade's champion: a fresh
	 * starter brain is a worse mind than a proven one but an honest ancestor for
	 * the role, and borrowing is exactly the mixing this exists to stop.
	 *
	 * <p><b>The champion is a share of the reseeds, not all of them.</b> Copying
	 * the single oldest survivor every time is the narrowest search there is: one
	 * parent, one small step, and — because nothing ages out — an incumbent that
	 * can only be displaced by dying rather than by being beaten. That ratchets a
	 * cohort onto whatever first worked and holds it there. So a fifth of reseeds
	 * come from the founder recipe instead, and another fifth from the champion at
	 * {@link #WILD_RESEED_RATE}: the line keeps its incumbent most of the time, and
	 * still gets a supply of genuinely different starting points to be beaten by.
	 *
	 * <p>The wild share is deliberately a big mutation of a working parent rather
	 * than a fresh random mind. GENOME.md records what fully-random brains did when
	 * they were tried: they never stumbled onto feeding, so selection had no
	 * gradient to climb. Entropy is worth having; entropy that cannot eat is not.
	 */
	public static net.hedinger.prototype.entities.Genome mindedReseedGenome(World w,
			net.hedinger.prototype.entities.Genome.Clade clade) {
		TestNPC best = null;
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e instanceof TestNPC t && t.isMinded() && !t.isDead() && !t.isRemoved()
					&& t.getGenome() != null && t.getGenome().clade == clade
					&& (best == null || t.getAge() > best.getAge())) {
				best = t;
			}
		}
		if (best == null) {
			return founderReseed(); // this clade is gone: restart its line from a founder
		}
		// The mix. Drawn before anything else is decided so the roll is one draw
		// from the seeded stream whatever it selects, and the sim stays reproducible.
		double roll = Utils.random();
		if (roll < FOUNDER_SHARE) {
			return founderReseed();
		}
		double rate = roll < FOUNDER_SHARE + WILD_SHARE ? WILD_RESEED_RATE : RESEED_RATE;
		return net.hedinger.prototype.entities.Genome.child(best.getGenome(), rate);
	}

	/** How hard a routine reseed mutates its parent: the settled rate, a nudge. */
	private static final double RESEED_RATE = 0.08;
	/**
	 * How hard a <em>wild</em> reseed mutates it — five times the nudge, which is a
	 * jump rather than a step. Far enough to leave the champion's basin, near
	 * enough to still be built on something that demonstrably feeds itself.
	 */
	private static final double WILD_RESEED_RATE = 0.4;
	/** Share of reseeds that ignore the champion and start the line again from the
	 *  founder recipe. */
	private static final double FOUNDER_SHARE = 0.2;
	/** Share that descend from the champion but at {@link #WILD_RESEED_RATE}. */
	private static final double WILD_SHARE = 0.2;

	/**
	 * A founder-recipe genome for a reseed: exactly what the world seeds a minded
	 * cohort with at tick zero — random dispositions and markers, a body inside the
	 * sane band, and one of the two hand-written starter brains. The strategy is
	 * drawn rather than fixed so the forager/hitch-hiker split arrives in roughly
	 * the same one-in-three proportion the founding cohort has.
	 */
	private static net.hedinger.prototype.entities.Genome founderReseed() {
		// 0..3, so a reseed can draw any of the four founder minds — including the
		// MLP substrate, which keeps the network cohort topped up rather than
		// letting it fade out of the mix as bodies die.
		return mindedGenome((int) (Utils.random() * 4));
	}

	/** A founder genome carrying the MLP substrate — the steward's MLP floor seeds
	 *  these to keep the second decision method in the world. Index 3 is the MLP
	 *  slot in {@link #mindedGenome(int)}. */
	public static net.hedinger.prototype.entities.Genome mlpFounderGenome() {
		return mindedGenome(3);
	}

	private static net.hedinger.prototype.entities.Genome[] species(double[][] markers, double[] sizes,
			double speedLo, double speedHi, double metabolism) {
		net.hedinger.prototype.entities.Genome[] out =
				new net.hedinger.prototype.entities.Genome[markers.length];
		for (int i = 0; i < markers.length; i++) {
			net.hedinger.prototype.entities.Genome g = new net.hedinger.prototype.entities.Genome();
			g.markers = markers[i];
			g.size = sizes[i];
			g.speed = speedLo + (speedHi - speedLo) * (markers.length == 1 ? 0 : i / (double) (markers.length - 1));
			g.turnRate = 5;
			g.metabolism = metabolism;
			out[i] = g;
		}
		return out;
	}

	/**
	 * Default world size (tiles). Large enough for real biomes and a proper
	 * underground network; {@link WorldAudit} verifies the whole space stays
	 * connected and that the sim keeps far more than real-time headroom here.
	 * {@code WORLD_COLS}/{@code WORLD_ROWS} (see {@code ServerMain}) override
	 * this without a rebuild.
	 *
	 * <p>The one-time startup bake of the ground layers fits the deploy VPS's
	 * small ({@code -Xmx512m}) heap because the procedural tile sprites are
	 * shared/cached (see {@code ProcTiles}) and each level is rendered one
	 * chunk-row BAND at a time (see {@code LayerBaker}) -- so bake memory is
	 * bounded by distinct tile shapes and map width, not map area. That is what
	 * made this size affordable: a whole-level image here would be 830 MB.
	 */
	static final int COLS = 288, ROWS = 176;

	/**
	 * The map area the founder counts and steward bounds below were tuned at.
	 *
	 * <p>Populations scale with map area, and the reference has to be a FIXED
	 * area rather than {@code COLS * ROWS} -- otherwise growing the default map
	 * silently divides its density by the growth, spreading the same headcount
	 * over four times the ground and leaving a world that reads as empty
	 * everywhere. Pinning it here means the same numbers mean the same density
	 * at any size, and enlarging the map adds inhabitants instead of thinning
	 * them.
	 */
	private static final double DENSITY_AREA = 144 * 88;

	/**
	 * The reseed floor every clade shares: no niche is left standing with fewer
	 * than this many bodies (scaled down with map area for the small worlds the
	 * suite builds). One number on purpose — the floors are about the niche
	 * existing at all, not about its natural headcount, and ten is enough of a
	 * scatter to be a breeding population rather than a token occupant. The
	 * predator, scavenger and parasite floors stay conditional on their food
	 * being present (prey, carrion, hosts): a floor reseeds a cohort into a
	 * world that can feed it, never one that starves it on arrival.
	 */
	private static final int CLADE_FLOOR = 10;

	/** Level indices. The engine treats a HIGHER index as physically UP (a HOLE
	 *  drops you to the level below, index-1; a RAMPUP climbs to index+1), so the
	 *  open-air surface must sit ABOVE the cave: surface is the higher index. */
	/**
	 * The three floors, bottom to top.
	 *
	 * <p>They are numbered rather than named in the engine, and the numbering
	 * only runs upward — {@code lvl + 1} is up, and there is no index below
	 * zero. So a floor UNDER the caves cannot be appended; it has to take index
	 * zero and push the other two up, which is why these constants changed
	 * together and why nothing else had to. Every reference in the world builder
	 * already went through these names, so the renumbering is the constants and
	 * the world's depth, and no coordinate anywhere needed touching.
	 */
	static final int DEEP_Z = 0, CAVE_Z = 1, SURFACE_Z = 2;

	/**
	 * The open air above the ground. Adding it is the first time a floor sits
	 * ABOVE the surface, which is why {@link World#getSurfaceZ()} had to become
	 * a stored fact: with a sky in the array the top index is no longer the
	 * ground, and every reader that derived "the surface" from {@code levels-1}
	 * would quietly have renamed the world's floors by one.
	 *
	 * <p>It is mostly {@code TYPE_VOID}. What stands in it is what the surface's
	 * own elevation says should stand there — the highland outcrops and the mesa
	 * buttes, whose tops carry on up past the ground plane. A viewer on this
	 * level sees a scatter of summits over an otherwise open drop onto the
	 * surface below.
	 *
	 * <p>The tallest of those summits are TABLES rather than blocks: flat
	 * ground, with a ramp cut up the hillside onto each. For a long time nothing
	 * up here was walkable at all, which made the sky the one floor of the world
	 * no animal could reach — a level that existed to be looked at. It is a
	 * place now, thin pasture and all.
	 */
	static final int SKY_Z = 3;

	/** How many drones the facility berths, and therefore how many charge pads
	 *  are cut into its deck. They share one standing order rather than dividing
	 *  the work: the steward recounts every tick and drops the order the moment
	 *  the target is met, so four machines converge on it four times as fast and
	 *  stop together. Splitting the cohorts between them would need a second
	 *  scoreboard, which is the one thing the order was designed not to have. */
	public static final int DRONE_RANK = 4;

	/**
	 * The demo world's terrain — same seed, same tiles, same fertility, no
	 * creatures: an exact twin of {@link #demo}'s ground, from which the
	 * server bakes the static layer images (one per level). The only entities
	 * are the buried installation's doors (structural furniture, part of the
	 * terrain's story rather than its population). Two levels:
	 *
	 * <ul>
	 *   <li><b>Level 0 — the surface:</b> a patchwork of biomes inside a rocky
	 *       rim — meadow, reed-fringed water, marsh, dry badlands with sand
	 *       pans, sight-blocking thickets, and stone-and-scree highlands — laid
	 *       out from coordinate noise.</li>
	 *   <li><b>Level 1 — underground:</b> solid rock with carved caverns,
	 *       subterranean pools, and bioluminescent fungus beds skirting
	 *       them.</li>
	 * </ul>
	 *
	 * The levels are linked by a few two-way ramps and a few open pits (holes).
	 * Layout is sampled from {@link Utils#noise2} (deterministic, draws no RNG),
	 * so the terrain is fully reproducible and does not perturb the entity RNG.
	 */
	public static World demoTerrain(long seed) {
		return demoTerrain(seed, COLS, ROWS);
	}

	/**
	 * A region of the surface: the numbers that bend the one terrain rule into
	 * somewhere with a character of its own, and the few hard rules that make
	 * the character unmistakable.
	 *
	 * <p>The surface used to be a single global blend — one elevation field,
	 * one moisture field, one ladder of thresholds — with variety at the scale
	 * of a few tiles and none at the scale of a journey. The first cut at
	 * regions only shifted the blend's inputs per region, and it read as a
	 * patchwork: six tints of the same place, because every region could still
	 * have a lake, a thicket and a sand pan wherever its noise said so.
	 *
	 * <p>So a biome is now two things. The biases still bend the shared ladder,
	 * which keeps the regions blending at their borders. And a handful of
	 * per-biome thresholds decide what a region simply DOES NOT HAVE: the
	 * badlands have no water and no thicket, the wetland has no sand and no
	 * outcrops, the woodland is thicket first and clearing second. Absence is
	 * what makes a place recognisable; a desert with a pond in it is a meadow
	 * that happens to be dry.
	 */
	static final class Biome {
		final String name;
		/** Added to the elevation field: up for uplands, down for basins. Also
		 *  read by {@link #raiseSkyline}, so a stony region carries a skyline
		 *  and a marsh does not — one opinion about how high the land is. */
		final double elevBias;
		/** Added to the moisture field. */
		final double moistBias;
		/** The meadow fertility line, {@code fertBase + fertGain * moisture}. */
		final double fertBase, fertGain;
		/** Thicket: detail noise above {@code coverCut} on ground moister than
		 *  {@code coverMoist}. A cut above 1 means no thicket at all. */
		final double coverCut, coverMoist;
		/** Open water: moisture above {@code waterMoist} on ground lower than
		 *  {@code waterElev}. Reed and marsh rings hang off the same numbers.
		 *  A moisture above 1 means no water at all. */
		final double waterMoist, waterElev;
		/** Sand pans: ground higher than {@code sandElev} and drier than
		 *  {@code sandMoist}. An elevation above 1 means no sand. */
		final double sandElev, sandMoist;
		/** Bare dirt: high ground drier than this grows nothing. */
		final double dustMoist;

		Biome(String name, double elevBias, double moistBias, double fertBase, double fertGain,
				double coverCut, double coverMoist, double waterMoist, double waterElev,
				double sandElev, double sandMoist, double dustMoist) {
			this.name = name;
			this.elevBias = elevBias;
			this.moistBias = moistBias;
			this.fertBase = fertBase;
			this.fertGain = fertGain;
			this.coverCut = coverCut;
			this.coverMoist = coverMoist;
			this.waterMoist = waterMoist;
			this.waterElev = waterElev;
			this.sandElev = sandElev;
			this.sandMoist = sandMoist;
			this.dustMoist = dustMoist;
		}
	}

	/** Never: a threshold no sample can clear. */
	private static final double NEVER = 9;

	/** The meadow is the world exactly as it was before regions existed, so
	 *  the temperate parts of the map still read as the world these thresholds
	 *  were tuned for. Everything else is defined by its distance from it. */
	private static final Biome MEADOW = new Biome("meadow",
			0, 0, 0.15, 1.25, 0.62, 0.55, 0.70, 0.45, 0.58, 0.30, 0.40);
	/** Dry open grass: thin, few features, the odd copse and pond. */
	private static final Biome STEPPE = new Biome("steppe",
			0.01, -0.08, 0.10, 0.90, 0.86, 0.60, 0.84, 0.40, 0.62, 0.28, 0.42);
	/** Sand pans, bare dust, quicksand and mesas. No water, no thicket. */
	private static final Biome BADLANDS = new Biome("badlands",
			0.05, -0.20, 0.03, 0.60, NEVER, NEVER, NEVER, 0, 0.50, 0.48, 0.62);
	/** Rock, scree and rocky pasture; most of the skyline stands over it. */
	private static final Biome UPLAND = new Biome("upland",
			0.14, -0.04, 0.08, 0.80, 0.82, 0.60, 0.86, 0.35, NEVER, 0, 0.30);
	/** Thicket first and clearing second: the one region that hides things. */
	private static final Biome WOODLAND = new Biome("woodland",
			-0.02, 0.08, 0.22, 1.10, 0.33, 0.30, 0.76, 0.45, NEVER, 0, 0.25);
	/** Lakes, reed beds and marsh, on the richest ground there is. No sand,
	 *  and it sits too low to carry outcrops. */
	private static final Biome WETLAND = new Biome("wetland",
			-0.08, 0.16, 0.28, 1.30, 0.62, 0.62, 0.60, 0.52, NEVER, 0, 0.20);

	/** One region: a Voronoi site and the biome it carries. */
	static final class Region {
		final int x, y;
		final Biome biome;

		Region(int x, int y, Biome biome) {
			this.x = x;
			this.y = y;
			this.biome = biome;
		}
	}

	/**
	 * The map's regions: a handful of sites on a jittered grid, each carrying a
	 * biome, with every biome placed at least once.
	 *
	 * <p>Voronoi cells rather than thresholds on a low-frequency field, and the
	 * reason is control over SIZE. A field's regions are wherever its contours
	 * happen to fall: at a frequency low enough for big regions a single seed's
	 * map was two biomes and a rumour of a third, and at a frequency high
	 * enough to show all six they were confetti. A site owns everything nearer
	 * to it than to any other site, so there are exactly as many regions as
	 * sites, each about a grid cell across — on the default map, eight regions
	 * of roughly seventy by ninety tiles, which is what makes a badland a
	 * badland rather than a scatter of dry patches.
	 *
	 * <p>Everything here comes off the noise lattice rather than the RNG, so
	 * the layout is a fact of the seed and the entity stream is untouched.
	 */
	static Region[] regionSites(int cols, int rows) {
		int nx = Math.max(2, (int) Math.round(cols / 72.0));
		int ny = Math.max(2, (int) Math.round(rows / 88.0));
		Biome[] deck = { WETLAND, BADLANDS, UPLAND, WOODLAND, MEADOW, STEPPE };
		// A per-seed shuffle of the deck, so which biomes neighbour which is
		// the seed's business and not a fixed pattern every map repeats.
		for (int i = deck.length - 1; i > 0; i--) {
			int j = (int) (Utils.noise2(i * 41 + 7, 3, 0.37) * (i + 1)) % (i + 1);
			Biome t = deck[i];
			deck[i] = deck[j];
			deck[j] = t;
		}
		Region[] out = new Region[nx * ny];
		double cw = cols / (double) nx, ch = rows / (double) ny;
		for (int j = 0; j < ny; j++) {
			for (int i = 0; i < nx; i++) {
				int k = j * nx + i;
				// The site sits somewhere in the middle half of its cell, so
				// neighbouring regions are never the same size twice.
				double jx = 0.25 + 0.5 * Utils.noise2(i * 37 + 11, j * 53 + 7, 0.41);
				double jy = 0.25 + 0.5 * Utils.noise2(i * 29 + 5, j * 47 + 13, 0.43);
				// Past the six, the extras are the two temperate ones: a map
				// with nine regions is not a map with a second badland.
				Biome b = k < deck.length ? deck[k] : (k % 2 == 0 ? MEADOW : STEPPE);
				out[k] = new Region((int) ((i + jx) * cw), (int) ((j + jy) * ch), b);
			}
		}
		return out;
	}

	/**
	 * Which region (x, y) belongs to: the nearest site, measured from a point
	 * the low-frequency warp has pushed a dozen tiles or so off (x, y). The
	 * warp is what turns straight Voronoi edges into coastlines; without it the
	 * borders are the polygon edges of a diagram, which no landscape has.
	 */
	static Biome biomeAt(Region[] regions, int x, int y) {
		double wx = x + BIOME_WARP * (Utils.noise2(x + 3100, y + 2200, 0.022) - 0.5) * 2;
		double wy = y + BIOME_WARP * (Utils.noise2(x + 4300, y + 900, 0.022) - 0.5) * 2;
		Region best = regions[0];
		double bd = Double.MAX_VALUE;
		for (Region r : regions) {
			double d = (wx - r.x) * (wx - r.x) + (wy - r.y) * (wy - r.y);
			if (d < bd) {
				bd = d;
				best = r;
			}
		}
		return best.biome;
	}

	/** How far the warp may move a region border, in tiles. Enough to make the
	 *  edges wander, far too little to move a region somewhere else. */
	private static final double BIOME_WARP = 14;

	/** Clamps a biased noise sample back into the [0, 1] the thresholds
	 *  ladder assumes. */
	private static double clamp01(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	/**
	 * The demo terrain at an arbitrary size. Biomes are sampled from the same
	 * coordinate noise (so a bigger map is more of the same world, not a
	 * different one), and the two levels are wired together by
	 * {@link #connectLevels} — which places underground links adaptively so the
	 * whole walkable space stays one connected region at any size (verified by
	 * {@link WorldAudit}).
	 */
	public static World demoTerrain(long seed, int cols, int rows) {
		Utils.seed(seed);
		Perf.stopwatch = new StopWatch();

		World w = new World(cols, rows, 4);
		// Said, not inferred. The ground is no longer the top of the array —
		// SKY_Z sits above it — so every reader that wants "the surface" has to
		// be told which floor that is instead of taking the highest index.
		w.setSurfaceZ(SURFACE_Z);

		// ---- the surface: regions inside a rocky boundary ----
		Region[] regions = regionSites(cols, rows);
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				boolean border = x < 2 || y < 2 || x >= cols - 2 || y >= rows - 2;
				double detail = Utils.noise2(x + 950, y + 640, 0.16);
				Biome b = biomeAt(regions, x, y);
				double elev = clamp01(Utils.noise2(x, y, 0.055) + b.elevBias);
				double moist = clamp01(Utils.noise2(x + 500, y + 300, 0.075) + b.moistBias);
				Tile.TileType t;
				double fert;
				if (border || elev > 0.87) {
					t = Tile.TileType.TYPE_WALL;
					fert = 0; // rocky rim + highland outcrops (the elevation noise
					// means ~0.67, so this high threshold keeps highlands an accent
					// everywhere but the upland, whose bias is what earns it more)
				} else if (elev > 0.845) {
					t = Tile.TileType.TYPE_RUBBLE;
					fert = 0; // scree collar hugging the outcrops
				} else if (elev > 0.82) {
					t = Tile.TileType.TYPE_STONE;
					fert = 0; // bare rock apron outside the scree
				} else if (elev > 0.74) {
					// The skirt where the highlands come apart into the meadow:
					// bedrock breaking the surface as slabs, thin grit between
					// them, and only what sward that grit can keep. Sparse by
					// nature — this is the poorest ground a grazer can still
					// make a living on, so it reads as a real frontier rather
					// than a texture swap.
					t = Tile.TileType.TYPE_ROCKY;
					fert = 0.10 + 0.30 * moist;
				} else if (moist > b.waterMoist && elev < b.waterElev) {
					t = Tile.TileType.TYPE_WATER;
					fert = 0; // lakes in the low, wet ground
				} else if (moist > b.waterMoist - 0.03 && elev < b.waterElev + 0.01) {
					t = Tile.TileType.TYPE_REEDS;
					fert = 0; // reed beds fringing the water: slow, sight-blocking
				} else if (moist > b.waterMoist - 0.10 && elev < b.waterElev + 0.07) {
					t = Tile.TileType.TYPE_MUD;
					fert = 0.30; // marshy shore, slows movement
				} else if (moist > b.coverMoist && detail > b.coverCut) {
					t = Tile.TileType.TYPE_COVER;
					fert = 0.90; // thickets: lush, and they block line of sight
				} else if (elev > b.sandElev && moist < b.sandMoist) {
					// A sand pan, with treacherous quicksand pockets where the
					// detail noise peaks — and, in the band between, the thorn
					// scrub that is the dry country's only cover. The desert
					// had none at all: a hunter there had nowhere to wait, and
					// the cactus is by its own description too narrow to hide
					// behind. Which regions get a pan at all is theirs to say:
					// the badlands are mostly this, and the wetland has none.
					t = detail > 0.78 ? Tile.TileType.TYPE_QUICKSAND
							: detail > 0.44 && detail < 0.58 ? Tile.TileType.TYPE_SCRUB
							: Tile.TileType.TYPE_SAND;
					fert = 0;
				} else if (elev > 0.58 && moist < b.dustMoist) {
					t = Tile.TileType.TYPE_FLOOR;
					fert = 0.0; // dry high ground: bare dirt, no grass, no food
				} else {
					// Meadow, and its richness is the story the ground tells:
					// fertility runs the whole band from thin scrub on the dry
					// margins to prime pasture in the damp hollows, following
					// the same moisture that decides where water and thicket
					// go — so a walk toward the lakes is a walk into greener
					// ground. The band starts at the art's bare threshold
					// (GroundTextures.SWARD_BARE), so the poorest living
					// pasture is exactly where the baked sward begins to close
					// over the earth, and a little detail noise breaks the
					// gradient up so richness reads as patches, not as bands.
					// Kept mean-neutral against the old narrow band, so the
					// world holds as much food as before — just spread far more
					// unevenly, which is what makes a habitat worth choosing.
					fert = b.fertBase + b.fertGain * moist + 0.08 * (detail - 0.5);
					fert = fert < 0 ? 0 : (fert > 1 ? 1 : fert);
					// The damp, rank end of the meadow stands up into tall
					// grass: the step the surface was missing between open
					// ground and a closed thicket. It keeps the fertility this
					// band would have given it and grazes exactly like the
					// meadow, so the herd's food is where it was — only now
					// some of it is somewhere a body can lie down.
					t = moist > 0.48 && detail > 0.56
							? Tile.TileType.TYPE_TALLGRASS : Tile.TileType.TYPE_FLOOR;
				}
				w.setTile(x, y, SURFACE_Z, t);
				w.getTile(x, y, SURFACE_Z).setFertility(fert);
			}
		}

		// ---- rivers: droplet walks down the same elevation field the biomes
		// read, so water runs out of the highlands, through the meadows, and
		// into the lakes (WORLDGEN-RESEARCH.md #2) ----
		carveRivers(w, cols, rows);

		// ---- cave: underground caverns carved from solid rock ----
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				double cave = Utils.noise2(x + 210, y + 770, 0.11);
				double pool = Utils.noise2(x + 1300, y + 90, 0.13);
				Tile.TileType t;
				if (x < 1 || y < 1 || x >= cols - 1 || y >= rows - 1) {
					t = Tile.TileType.TYPE_WALL; // sealed edge
				} else if (cave > 0.38 && cave < 0.66) {
					// Pool cores are water; the damp ground skirting a pool grows
					// bioluminescent fungus beds (the caves' only food); the
					// driest stone sprouts crystal clusters and, in the odd
					// geothermal pocket, vent mouths; the rest is bare stone.
					double deep = Utils.noise2(x + 40, y + 1500, 0.3);
					if (pool > 0.72) {
						t = Tile.TileType.TYPE_WATER;
					} else if (pool > 0.62) {
						t = Tile.TileType.TYPE_FUNGUS;
					} else if (pool < 0.18) {
						// Crystal fields grade by density from the core out: a
						// blocking formation spine, a packed bed that slows a
						// wader, then loose shards on ordinary stone.
						t = pool < 0.07 ? Tile.TileType.TYPE_CRYSTAL
								: pool < 0.125 ? Tile.TileType.TYPE_CRYSTAL_BED
								: Tile.TileType.TYPE_CRYSTAL_SPARSE;
					} else if (deep > 0.85) {
						t = Tile.TileType.TYPE_VENT;
					} else if (deep < 0.08) {
						// The odd cavern floor gives way entirely: a natural
						// pit, and on the lowest level a pit is bottomless --
						// whatever falls in leaves the world. The corridor
						// carver paves straight through pit fields, so the
						// backbone always bridges them.
						t = Tile.TileType.TYPE_HOLE;
					} else {
						t = Tile.TileType.TYPE_STONE;
					}
				} else {
					t = Tile.TileType.TYPE_WALL; // solid rock
				}
				w.setTile(x, y, CAVE_Z, t);
				// Fungus beds hold moderate food; nothing else grows underground.
				w.getTile(x, y, CAVE_Z).setFertility(
						t == Tile.TileType.TYPE_FUNGUS ? 0.6 : 0);
			}
		}

		// ---- cellular-automata polish: a couple of rounds over the cave's
		// wall/floor boundary rounds cavern edges organically and merges
		// near-touching pockets, so less ends up sealed later. Only plain rock
		// and plain stone flip; pools, fungus, crystal, vents and pits keep
		// their ground truth (WORLDGEN-RESEARCH.md #3). ----
		smoothCave(w, cols, rows);

		// ---- wire the levels together so every area is reachable ----
		connectLevels(w, cols, rows);

		// ---- a buried installation: someone built down here, once ----
		int[] facility = buryInstallation(w, cols, rows);

		// ---- the underdark: natural caverns on the bottom level, so the rock
		// under the caves is a place and not a backstop. Carved only from
		// virgin rock (the plant floor and its stairwell are already down
		// there and are not touched), then tied into the world with cut
		// stairwells wherever cave floor sits over cavern floor ----
		carveDeepCaverns(w, cols, rows);
		linkDeepCaverns(w, cols, rows);
		if (facility != null) {
			layDeepLine(w, cols, rows, facility[0], facility[1]);
		}

		// ---- the ravine: a gorge torn through the surface, with the caves
		// showing through it -- carved last so it can keep clear of everything
		// the passes above placed. Where the cave under the cut is solid rock
		// the gorge keeps going, through both levels, down to the underdark ----
		carveRavine(w, cols, rows);

		// ---- final connectivity repair: the underdark and the gorge were
		// carved after connectLevels' seal, so flood the world once more from
		// the (single, certified) mainland and seal whatever the new terrain
		// left unreachable — a cavern no stairwell found is rock again ----
		resealFromSurface(w, cols, rows);

		// ---- shallows: every shore-touching water tile becomes a walkable,
		// wading fringe, so lakes have fords instead of hard edges ----
		for (int z = CAVE_Z; z <= SURFACE_Z; z++) {
			java.util.ArrayList<int[]> shore = new java.util.ArrayList<int[]>();
			for (int x = 1; x < cols - 1; x++) {
				for (int y = 1; y < rows - 1; y++) {
					if (w.getTile(x, y, z).getType() != Tile.TileType.TYPE_WATER) {
						continue;
					}
					for (int k = 0; k < 4; k++) {
						int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
						int ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
						Tile.TileType n = w.getTile(nx, ny, z).getType();
						if (n != Tile.TileType.TYPE_WATER && n != Tile.TileType.TYPE_SHALLOWS
								&& w.getTile(nx, ny, z).isWalkable()) {
							shore.add(new int[] { x, y });
							break;
						}
					}
				}
			}
			for (int[] p : shore) {
				w.setTile(p[0], p[1], z, Tile.TileType.TYPE_SHALLOWS);
				w.getTile(p[0], p[1], z).setFertility(0);
			}
		}

		// Tune the surface grass's logistic recovery so a grazed-bare patch rests
		// (Tile.REGROW_DELAY, ~1 min) and then climbs back slowly over another
		// ~1.5 min, while a lightly-cropped patch springs back fast. Heavy grazing
		// thus leaves lasting bare patches, but the big map still sustains the herd
		// (unlike a small room). Non-grass tiles are unaffected.
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				w.getTile(x, y, SURFACE_Z).setRegrowRate(0.0025);
			}
		}

		// Last, so it reads the FINAL surface: every pass that could carve a
		// hilltop away (the ravine, the installation's shell, both seals) has
		// already run, and a summit is only raised where rock actually survived
		// under it.
		raiseSkyline(w, cols, rows);
		return w;
	}

	/**
	 * The skyline: what stands up into the open air above the ground.
	 *
	 * <p>The sky is {@code TYPE_VOID} everywhere except where the land is still
	 * climbing at this height. The surface calls anything over 0.87 elevation a
	 * highland outcrop; a summit is the core of that which is still rising at
	 * 0.90, and the gap between the two thresholds is what gives a hill a wide
	 * base and a narrow top rather than a column with vertical sides. The rim
	 * carries up too — the world is bounded by cliffs, and a cliff does not
	 * stop at the ground plane.
	 *
	 * <p>Nothing floats. A spire is the TOP of something, so it is raised only
	 * where the tile directly below it is solid; where a later pass cut the
	 * outcrop away, the air above it is air. That invariant is the one thing
	 * worth asserting about this level, because a floating rock is exactly what
	 * a second elevation opinion would produce and it would look deliberate.
	 */
	private static void raiseSkyline(World w, int cols, int rows) {
		Region[] regions = regionSites(cols, rows);
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				boolean border = x < 2 || y < 2 || x >= cols - 2 || y >= rows - 2;
				// The same biased elevation the ground read, so a stony upland
				// carries a skyline and a wetland does not -- one opinion about
				// how high the land is, not two.
				double n = clamp01(Utils.noise2(x, y, 0.055) + biomeAt(regions, x, y).elevBias);
				boolean summit = border || n > SUMMIT_N;
				setBare(w, x, y, SKY_Z, summit && w.getTile(x, y, SURFACE_Z).isSolid()
						? Tile.TileType.TYPE_WALL
						: Tile.TileType.TYPE_VOID);
			}
		}
		flattenSummits(w, cols, rows);
		rampTheSkyline(w, cols, rows);
	}

	/**
	 * Cuts the top off every summit wide enough to have one: a mesa's TABLE is
	 * its interior, and its rim is the ring of rock left standing round it.
	 *
	 * <p>Erosion rather than a third elevation threshold, and the difference is
	 * the whole reason the sky is a place. A threshold asks "is this cell high
	 * enough", so whether a summit gets a top depends on how high its own noise
	 * peak happens to run — and measured across seeds that was one mesa in the
	 * whole world with a top on it, an empty level with a single green patch on
	 * it. Erosion asks "is this cell WIDE enough", which is the actual property
	 * a mesa has and a spire does not: every summit broad enough to hold an
	 * interior gets one, at whatever height it stands, and a narrow stack stays
	 * a narrow stack.
	 *
	 * <p>Eroding by exactly one leaves a rim exactly one thick at the thinnest
	 * point, which is what the climbs need to breach and what reads from below
	 * as an edge rather than a slope.
	 */
	private static void flattenSummits(World w, int cols, int rows) {
		boolean[][] table = new boolean[cols][rows];
		for (int x = 3; x < cols - 3; x++) {
			for (int y = 3; y < rows - 3; y++) {
				if (w.getTile(x, y, SKY_Z).getType() != Tile.TileType.TYPE_WALL) {
					continue;
				}
				boolean inside = true;
				for (int dx = -1; dx <= 1 && inside; dx++) {
					for (int dy = -1; dy <= 1 && inside; dy++) {
						inside = w.getTile(x + dx, y + dy, SKY_Z).getType()
								!= Tile.TileType.TYPE_VOID;
					}
				}
				table[x][y] = inside;
			}
		}
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				if (table[x][y]) {
					setBare(w, x, y, SKY_Z, Tile.TileType.TYPE_ROCKY);
					w.getTile(x, y, SKY_Z).setFertility(PLATEAU_FERTILITY);
				}
			}
		}
	}

	/** The thin sward a wind-scoured mesa top keeps — well under the meadows
	 *  below, so the climb buys grazing that is real but never better than
	 *  staying down. */
	private static final double PLATEAU_FERTILITY = 0.45;

	/** Where the land stops climbing and starts standing up into the open air.
	 *
	 *  <p>Just under the 0.87 the ground calls an outcrop, so an outcrop and the
	 *  skyline over it are very nearly the same shape: a rocky hill has height,
	 *  and the sky is the relief map of the ground rather than a second, smaller
	 *  opinion about where the high country is. It was 0.90, which left the sky
	 *  a scatter of stacks too narrow for any of them to have a top. */
	private static final double SUMMIT_N = 0.875;

	/** The smallest table worth a climb. A handful of tiles up a ramp is a
	 *  landing, not a place, and every one of them costs a ramp cut through the
	 *  rim. */
	private static final int MIN_PLATEAU = 8;

	/**
	 * Ramps from the surface up onto the plateaus, and the demolition of every
	 * plateau that could not get one.
	 *
	 * <p>The sky was previously walls and void alone: there was nothing up
	 * there to stand on, so the level existed only as the thing that cast the
	 * skyline. Giving summits tables is half the change; this is the half that
	 * makes them part of the world, because a table with no way onto it is
	 * scenery with a floor texture. Any plateau that ends up without a ramp is
	 * put back to rim stone rather than left hanging — an unreachable walkable
	 * region is exactly what {@code WorldAudit.connectivity} exists to catch,
	 * and it would be right to.
	 */
	private static void rampTheSkyline(World w, int cols, int rows) {
		boolean[][] seen = new boolean[cols][rows];
		for (int sx = 0; sx < cols; sx++) {
			for (int sy = 0; sy < rows; sy++) {
				if (seen[sx][sy] || w.getTile(sx, sy, SKY_Z).getType() != Tile.TileType.TYPE_ROCKY) {
					continue;
				}
				java.util.ArrayList<int[]> table = new java.util.ArrayList<int[]>();
				floodPlateau(w, seen, cols, rows, sx, sy, table);
				int want = table.size() < MIN_PLATEAU ? 0
						: Math.max(1, table.size() / TILES_PER_SKY_RAMP);
				int cut = 0;
				if (want > 0) {
					cut = climbPlateau(w, cols, rows, seen, table, want);
				}
				if (cut == 0) {
					// Too small, or walled in on every side by its own rim:
					// back to the mass it was cut out of.
					for (int[] p : table) {
						setBare(w, p[0], p[1], SKY_Z, Tile.TileType.TYPE_WALL);
					}
				}
			}
		}
	}

	/** How much table buys another way up. A plateau is a destination rather
	 *  than a corridor, so this is far more generous than the underdark's
	 *  figure — two ramps onto a big mesa is enough for it not to be a trap. */
	private static final int TILES_PER_SKY_RAMP = 45;

	/** Collects one plateau's tiles, four-connected. */
	private static void floodPlateau(World w, boolean[][] seen, int cols, int rows,
			int sx, int sy, java.util.List<int[]> out) {
		java.util.Deque<int[]> q = new java.util.ArrayDeque<int[]>();
		q.add(new int[] { sx, sy });
		seen[sx][sy] = true;
		while (!q.isEmpty()) {
			int[] p = q.poll();
			out.add(p);
			for (int d = 0; d < 4; d++) {
				int nx = p[0] + Tile.dirDx(d), ny = p[1] + Tile.dirDy(d);
				if (nx < 0 || ny < 0 || nx >= cols || ny >= rows || seen[nx][ny]) {
					continue;
				}
				if (w.getTile(nx, ny, SKY_Z).getType() != Tile.TileType.TYPE_ROCKY) {
					continue;
				}
				seen[nx][ny] = true;
				q.add(new int[] { nx, ny });
			}
		}
	}

	/**
	 * Cuts up to {@code want} ramps onto one plateau and returns how many
	 * landed. Sites are taken farthest-apart-first, the same spreading rule the
	 * underdark's stairs use, so two ways up a mesa are on two sides of it.
	 */
	private static int climbPlateau(World w, int cols, int rows, boolean[][] seen,
			java.util.List<int[]> table, int want) {
		java.util.ArrayList<int[]> cut = new java.util.ArrayList<int[]>();
		while (cut.size() < want) {
			int[] bestSite = null;
			long best = Long.MIN_VALUE;
			for (int[] p : table) {
				for (int u = 0; u < 4; u++) {
					int run = hillRun(w, cols, rows, p[0], p[1], u);
					if (run < 0) {
						continue;
					}
					long near = 0; // no climb yet: every site is equally far from none
					for (int[] q : cut) {
						long d = (long) (p[0] - q[0]) * (p[0] - q[0])
								+ (long) (p[1] - q[1]) * (p[1] - q[1]);
						near = cut.get(0) == q ? d : Math.min(near, d);
					}
					if (!cut.isEmpty() && near < (long) MIN_SKY_RAMP_GAP * MIN_SKY_RAMP_GAP) {
						continue;
					}
					// Farthest from the climbs already cut; among equals, the
					// shortest spur, so a mesa is entered where its hillside is
					// thinnest rather than wherever the scan reached first.
					long rank = near * 8 - run;
					if (rank > best) {
						best = rank;
						bestSite = new int[] { p[0], p[1], u, run };
					}
				}
			}
			if (bestSite == null) {
				break;
			}
			raiseStair(w, seen, bestSite[0], bestSite[1], bestSite[2], bestSite[3]);
			cut.add(bestSite);
		}
		return cut.size();
	}

	/** The closest two ways up the same mesa may stand. */
	private static final int MIN_SKY_RAMP_GAP = 8;

	/**
	 * How much hillside stands between the plateau tile (px, py) and walkable
	 * ground along {@code -u}, or -1 where no climb can be cut arriving from
	 * that direction.
	 *
	 * <p>What is measured is the run of SOLID SURFACE, not the run of sky wall,
	 * and the difference matters. The surface calls anything over 0.87
	 * elevation an outcrop while the sky only stands up over 0.90, so every
	 * mesa sits on a skirt of solid rock that carries no skyline: measuring the
	 * wall alone left the ramp's landing out on bare stone every time, and no
	 * climb was ever cut. The run of solid ground is the hill, wall or no wall.
	 *
	 * <p>Past the hill there must be three tiles of open air over walkable
	 * ground — the landing the drop lands on, the tile between, and the
	 * climbing ramp itself. The hill has a depth limit because the climb cuts a
	 * spur of walkable stone along it: a short one reads as a notch in the
	 * mesa's edge, a long one as a causeway out across the plain.
	 */
	private static int hillRun(World w, int cols, int rows, int px, int py, int u) {
		int ax = Tile.dirDx(u), ay = Tile.dirDy(u);
		int run = 0;
		while (run < MAX_HILL_RUN) {
			int ex = px - (run + 1) * ax, ey = py - (run + 1) * ay;
			if (ex < 4 || ey < 4 || ex >= cols - 4 || ey >= rows - 4) {
				return -1;
			}
			if (!w.getTile(ex, ey, SURFACE_Z).isSolid()) {
				break; // (ex, ey) is the first open ground: the hill ended one back
			}
			Tile.TileType sky = w.getTile(ex, ey, SKY_Z).getType();
			if (sky != Tile.TileType.TYPE_WALL && sky != Tile.TileType.TYPE_VOID) {
				return -1; // a climb already cut through here
			}
			run++;
		}
		if (run == 0 || run >= MAX_HILL_RUN) {
			return -1;
		}
		for (int k = 1; k <= 3; k++) {
			int qx = px - (run + k) * ax, qy = py - (run + k) * ay;
			if (qx < 2 || qy < 2 || qx >= cols - 2 || qy >= rows - 2) {
				return -1;
			}
			if (w.getTile(qx, qy, SKY_Z).getType() != Tile.TileType.TYPE_VOID) {
				return -1;
			}
			Tile.TileType g = w.getTile(qx, qy, SURFACE_Z).getType();
			if (!w.getTile(qx, qy, SURFACE_Z).isWalkable() || g == Tile.TileType.TYPE_RAMPUP
					|| g == Tile.TileType.TYPE_RAMPDOWN || g == Tile.TileType.TYPE_HOLE) {
				return -1;
			}
		}
		return run;
	}

	/** The longest hillside a climb will run up. Past this the spur stops
	 *  reading as a notch in the mesa's edge and starts reading as a causeway. */
	private static final int MAX_HILL_RUN = 6;

	/**
	 * The climb from the ground up onto a plateau: {@link #sinkStairwell}'s cut
	 * turned the other way up, with the surface as the lower floor and the sky
	 * as the upper one.
	 *
	 * <p>Reading inward along {@code u} from the open plain: a hole in the sky
	 * to drop back through, the descending ramp beside it, the climbing ramp on
	 * the ground, and then the spur it steps out onto — the hillside between
	 * the ramp's exit and the table, raised into walkable stone. The mesa's
	 * edge overhangs its own stair, which is what the underdark's stairwells do
	 * a level down and what makes the descent a walk rather than a jump.
	 */
	private static void raiseStair(World w, boolean[][] seen, int px, int py, int u, int run) {
		int ax = Tile.dirDx(u), ay = Tile.dirDy(u);
		int bx = px - (run + 3) * ax, by = py - (run + 3) * ay;
		setBare(w, bx, by, SKY_Z, Tile.TileType.TYPE_HOLE);
		setBare(w, bx + ax, by + ay, SKY_Z, Tile.TileType.TYPE_RAMPDOWN);
		w.getTile(bx + ax, by + ay, SKY_Z).setRampUphill(u);
		setBare(w, bx + 2 * ax, by + 2 * ay, SURFACE_Z, Tile.TileType.TYPE_RAMPUP);
		w.getTile(bx + 2 * ax, by + 2 * ay, SURFACE_Z).setRampUphill(u);
		// One past the hill: the tile the descending ramp is stepped onto from
		// the spur, and the tile the climb exits beside. It stands over the
		// climbing ramp rather than over solid ground -- the stair's own
		// overhang, exactly as the underdark's stairwells overhang theirs a
		// level down. Without it the descending ramp is stranded a tile off the
		// spur and the mesa has a way up but no way down.
		for (int k = 1; k <= run + 1; k++) {
			int nx = px - k * ax, ny = py - k * ay;
			setBare(w, nx, ny, SKY_Z, Tile.TileType.TYPE_ROCKY);
			w.getTile(nx, ny, SKY_Z).setFertility(PLATEAU_FERTILITY);
			// Claimed, so the sweep that is still running does not come back
			// and flood the spur as a table of its own — which, being three
			// tiles long, would fail the size test and be demolished, taking
			// the only way onto the mesa with it.
			seen[nx][ny] = true;
		}
	}

	/**
	 * The facility: a two-storey installation buried in the rock, with tram
	 * lines running out of it across both underground levels through the
	 * other sectors.
	 *
	 * <p>Two authored floors on one origin. The HALLS on the cave level are the
	 * entrance — a concourse behind a blast door, the drone rank, the vault,
	 * and an atrium whose floor is open void crossed by gantries, looking down
	 * onto the WORKS on the level below. The works are the plant the halls
	 * keep referring to: the reactor and its coolant loop, the condensers, the
	 * turbine hall under the atrium, the shard workings, and the tram run.
	 * Reading the building means walking it in three dimensions, which is the
	 * one thing a drawing of a floor cannot show.
	 *
	 * <p>This replaced three plans chosen by pocket size — a 22x15 station, an
	 * 18x13 base and a 15x9 annex, each placed arithmetically off its corner
	 * and each requiring a rectangle of solid rock. The rock never offers one
	 * bigger than about 26x17 whatever the map size, so the building could not
	 * grow that way; and a building that may only stand where nothing else is
	 * is a building that stands in the least interesting place. The facility
	 * is sited where it buries the LEAST, and where it does bury a cavern it
	 * lets the cavern in: every stretch of wall a cavern touches gets a
	 * doorway. The buildings are hubs the caves pass through, not obstacles
	 * they stop at.
	 *
	 * <p>Returns the origin, or null on a map too small to hold it. Runs after
	 * {@link #connectLevels}, so the entrance gallery attaches to ground that
	 * is already part of the connected world.
	 */
	private static int[] buryInstallation(World w, int cols, int rows) {
		int[] site = findFacilitySite(w, cols, rows);
		if (site == null) {
			return null;
		}
		int x0 = site[0], y0 = site[1], W = FACILITY_W, H = FACILITY_H;
		stampPlan(w, CAVE_Z, x0, y0, HALLS, true);

		// The vault's steel ring in the halls' plan, and the mouth row: the
		// concourse's second row, so the blast door opens onto the platform.
		int vx = x0 + 1 + 34, vy = y0 + 1 + 1, vh = 6;
		if (!finishBase(w, cols, rows, x0, y0, W, H, vx, vy, vh, y0 + 3)) {
			return null; // un-carved: no way out through the rock
		}
		punchDoors(w, CAVE_Z, x0, y0, W, H);

		// Furnishing: the stack the loader marshals to, on the marshalling
		// deck; the vault's cache, and the hazard standing guard over it.
		for (int i = 0; i < 4; i++) {
			w.spawnEntity(Item.crate(x0 + 1 + 18 + i + 0.5, y0 + 1 + 24 + 0.5, CAVE_Z));
		}
		w.spawnEntity(Item.crate(x0 + 1 + 19 + 0.5, y0 + 1 + 23 + 0.5, CAVE_Z));
		w.spawnEntity(Item.food(x0 + 1 + 36 + 0.5, y0 + 1 + 2 + 0.5, CAVE_Z));
		w.spawnEntity(Item.food(x0 + 1 + 41 + 0.5, y0 + 1 + 4 + 0.5, CAVE_Z));
		w.spawnEntity(Item.hazard(x0 + 1 + 39 + 0.5, y0 + 1 + 4 + 0.5, CAVE_Z));

		// The ceiling: a ventilation shaft from the surface over the concourse,
		// so gravity is the building's third entrance.
		dropShaft(w, x0 + 2, x0 + 28, y0 + 2, y0 + 7);

		// The floor below, joined to this one by every stairwell the two plans
		// can agree on.
		sinkWorks(w, cols, rows, x0, y0, W, H);

		// The campus: three sectors packed against the halls and against each
		// other, sharing walls where they can and standing off a corridor's
		// width where they cannot. A site plan of a real complex of this kind
		// is a cluster of boxes with a ragged outline -- administration hard
		// up against the test labs, storage tucked under both, the whole
		// thing one continuous built thing that the labels are drawn ON --
		// not four rooms strung thirty tiles apart down a rail.
		Leg[] spilt = packCampus(w, cols, rows, CAVE_Z, x0, y0, W, H, new Leg[] {
				new Leg(0, 0, COOLANT, null), new Leg(0, 0, ADMIN, null),
				new Leg(0, 0, WAREHOUSE, null) });

		// And one outlier, out where the rail has to go to reach it. A campus
		// with nothing outside it is a blob; what makes a site plan read is
		// the near cluster AND the far annex, and the track between them.
		int d = freeSide(w, cols, rows, CAVE_Z, x0, y0, W, H);
		int[] pt = portalOn(d, x0, y0, W, H);
		layLine(w, cols, rows, CAVE_Z, pt[0], pt[1], outliers(
				new Leg(Tile.dirDx(d), Tile.dirDy(d), BIODOME, null), spilt));
		return site;
	}

	/** The facility's shell, both floors: the plans' interior plus one. */
	static final int FACILITY_W = 44 + 2, FACILITY_H = 26 + 2;

	/**
	 * Where the facility stands: the window on the cave level that buries the
	 * fewest tiles anyone can walk on, nearest the middle of the map among
	 * equals.
	 *
	 * <p>Scored rather than required. The old search demanded solid rock and
	 * so could only ever find a pocket the caves happened to leave; this asks
	 * what a site would cost and takes the cheapest, so the building can be as
	 * big as its drawing. Links between floors inside the footprint are built
	 * round rather than refused -- see
	 * {@link #stampPlan(World, int, int, int, String[], boolean)}.
	 */
	private static int[] findFacilitySite(World w, int cols, int rows) {
		int W = FACILITY_W, H = FACILITY_H;
		if (cols < W + 6 || rows < H + 6) {
			return null;
		}
		// The middle two thirds of the map first, so there is always a side for
		// a line to leave by -- scoring alone put the building on the rim on
		// one seed in six, and the rim is solid rock, which buries nothing.
		// Then the whole map, because a band that HAS no site is worse than a
		// site out at the edge: measured, the band alone left half the small
		// maps with no facility at all, which is not a building on the rim, it
		// is no building.
		int[] best = siteIn(w, cols, rows, W, H,
				Math.max(3, cols / 6), Math.min(cols - 3 - W, cols * 5 / 6 - W),
				Math.max(3, rows / 6), Math.min(rows - 3 - H, rows * 5 / 6 - H));
		return best != null ? best
				: siteIn(w, cols, rows, W, H, 3, cols - 3 - W, 3, rows - 3 - H);
	}

	/** The cheapest site in a window, or null if nothing in it can hold the
	 *  building. Cost is walkable tiles buried; distance from the middle is
	 *  half a tile each, so a site has to save a good deal of cavern to be
	 *  worth being far out. */
	private static int[] siteIn(World w, int cols, int rows, int W, int H,
			int xLo, int xHi, int yLo, int yHi) {
		int[] best = null;
		double bestScore = Double.MAX_VALUE;
		for (int x0 = Math.max(3, xLo); x0 <= xHi; x0 += 2) {
			for (int y0 = Math.max(3, yLo); y0 <= yHi; y0 += 2) {
				int cost = 0;
				for (int x = x0; x < x0 + W; x++) {
					for (int y = y0; y < y0 + H; y++) {
						Tile.TileType t = w.getTile(x, y, CAVE_Z).getType();
						// A link station inside the footprint is built ROUND,
						// not refused. Refusing left a quarter of maps with no
						// facility at all: a forty-six by twenty-eight window
						// laid anywhere on the cave level covers one of the
						// link stations more often than not, whatever the map
						// size, because the stations scale with the map too.
						// A station surfacing inside the building is a way in.
						if (t != Tile.TileType.TYPE_WALL) {
							cost++;
						}
					}
				}
				double d = Math.hypot(x0 + W * 0.5 - cols * 0.5, y0 + H * 0.5 - rows * 0.5);
				double score = cost + d * 0.5;
				if (score < bestScore) {
					bestScore = score;
					best = new int[] { x0, y0 };
				}
			}
		}
		return best;
	}

	/**
	 * A tile that must be left exactly as it is: one end of a link between
	 * floors, something wired, or the ROCK a ramp climbs into.
	 *
	 * <p>That last one is not decoration. An up ramp whose high side is open
	 * floor is a staircase into a ceiling -- it climbs to a surface tile
	 * resting on nothing, and the art has no mass to disappear into, which is
	 * why {@code DemoLevelsLinkSurfaceAndCave} asserts it. The ramp tile
	 * itself was always preserved; the rock it climbs into was not, so a
	 * building set against a link station took the mass out from over it and
	 * left the ramp climbing into air. It took packing the sectors against the
	 * core to find it -- the campus puts several times as much wall next to
	 * the link stations as a line of distant stops ever did.
	 */
	private static boolean mustStay(World w, int z, int x, int y) {
		Tile t = w.getTile(x, y, z);
		if (isRampOrDrop(t.getType())) {
			return true;
		}
		if (!t.isSolid()) {
			return false;
		}
		for (int d = 0; d < 4; d++) {
			Tile n = w.getTile(x - Tile.dirDx(d), y - Tile.dirDy(d), z);
			if (n.getType() == Tile.TileType.TYPE_RAMPUP && n.getRampUphill() == d) {
				return true;
			}
		}
		return false;
	}

	/** A tile that must never be built over: it is one end of a link between
	 *  two floors, or a control that something is wired to. */
	private static boolean isRampOrDrop(Tile.TileType t) {
		return t == Tile.TileType.TYPE_RAMPUP || t == Tile.TileType.TYPE_RAMPDOWN
				|| t == Tile.TileType.TYPE_HOLE || t == Tile.TileType.TYPE_SWITCH
				|| t == Tile.TileType.TYPE_DOCK;
	}

	/**
	 * Stamps a drawn plan onto level {@code z} at (x0, y0): a concrete shell
	 * around it, deck plate under it, and the plan's own tiles over that.
	 */
	private static void stampPlan(World w, int z, int x0, int y0, String[] plan) {
		stampPlan(w, z, x0, y0, plan, false);
	}

	/**
	 * As above, optionally BUILDING ROUND whatever was already there that has
	 * to stay: the ends of links between floors, and anything wired.
	 *
	 * <p>For the second floor of a two-storey stop. The cave level carries
	 * fifty-odd link stations, and a twenty-six by eighteen footprint laid
	 * anywhere on it covers one about seven times in eight -- so a floor that
	 * refused to stand over one never stood at all, and Lambda came out a
	 * basement on every seed measured. Preserving them instead costs a tile
	 * here and there out of the drawing and buys the building back, and it is
	 * the same thing the doorways already say: the facility is something the
	 * caves pass through. A ramp to the surface coming up inside the complex
	 * is a way in, not a defect.
	 */
	private static void stampPlan(World w, int z, int x0, int y0, String[] plan,
			boolean preserve) {
		int W = plan[0].length() + 2, H = plan.length + 2;
		for (int x = x0; x < x0 + W; x++) {
			for (int y = y0; y < y0 + H; y++) {
				if (preserve && mustStay(w, z, x, y)) {
					continue;
				}
				boolean shell = x == x0 || y == y0 || x == x0 + W - 1 || y == y0 + H - 1;
				setBare(w, x, y, z, shell
						? Tile.TileType.TYPE_WALL_CONCRETE : Tile.TileType.TYPE_PLATE);
			}
		}
		for (int j = 0; j < plan.length; j++) {
			for (int i = 0; i < plan[j].length(); i++) {
				if (preserve && mustStay(w, z, x0 + 1 + i, y0 + 1 + j)) {
					continue;
				}
				Tile.TileType t = plantTile(plan[j].charAt(i));
				if (t != null) {
					setBare(w, x0 + 1 + i, y0 + 1 + j, z, t);
					// The biodome's habitats are the one LIVING ground a plan
					// lays, and they have to grow what the same ground grows
					// outside or they are a picture of a habitat. setBare
					// zeroes fertility, because built ground grows nothing --
					// so these three put it back.
					Tile laid = w.getTile(x0 + 1 + i, y0 + 1 + j, z);
					if (t == Tile.TileType.TYPE_FUNGUS) {
						laid.setFertility(0.6);
					} else if (t == Tile.TileType.TYPE_COVER) {
						laid.setFertility(0.9);
					} else if (t == Tile.TileType.TYPE_MUD) {
						laid.setFertility(0.3);
					}
				}
			}
		}
	}

	/**
	 * Doorways wherever the world outside a building's wall is walkable and so
	 * is the room inside — one every few tiles along such a stretch, so a
	 * cavern the building landed across is reconnected through it rather than
	 * cut in two.
	 *
	 * <p>This is what lets a building stand anywhere. The old plans could only
	 * stand in solid rock because a shell dropped across a cavern severed it,
	 * and the severed half was quietly resealed for want of a way in; with a
	 * door at every contact the shell severs nothing. It also happens to be
	 * how a facility that was actually used would look — cut into the caves,
	 * with the caves coming in.
	 */
	private static void punchDoors(World w, int z, int x0, int y0, int W, int H) {
		int[][] walls = {
				{ x0, y0 + 1, 0, 1, 1, 0, H - 2 },          // west wall, walking south; outside is x-1
				{ x0 + W - 1, y0 + 1, 0, 1, -1, 0, H - 2 }, // east wall; outside is x+1
				{ x0 + 1, y0, 1, 0, 0, 1, W - 2 },          // north wall, walking east; outside is y-1
				{ x0 + 1, y0 + H - 1, 1, 0, 0, -1, W - 2 }, // south wall; outside is y+1
		};
		for (int[] wall : walls) {
			int x = wall[0], y = wall[1], dx = wall[2], dy = wall[3];
			int ix = wall[4], iy = wall[5]; // inward
			int since = DOOR_SPACING;
			for (int k = 0; k < wall[6]; k++, x += dx, y += dy, since++) {
				Tile shell = w.getTile(x, y, z);
				if (shell.getType() != Tile.TileType.TYPE_WALL_CONCRETE) {
					since = 0; // an opening already: a mouth, a portal, a duct
					continue;
				}
				Tile out = w.getTile(x - ix, y - iy, z), in = w.getTile(x + ix, y + iy, z);
				if (out == null || in == null || !out.isWalkable() || !in.isWalkable()
						|| since < DOOR_SPACING) {
					continue;
				}
				setBare(w, x, y, z, Tile.TileType.TYPE_PAVED);
				since = 0;
			}
		}
	}

	/** The closest two punched doorways stand along one wall. */
	private static final int DOOR_SPACING = 5;

	/**
	 * A tram line out of a portal in a building's wall: rails on the centre
	 * and paved shoulders either side, tunnelled through rock, laid straight
	 * across whatever floor it meets and carried over water and shafts on
	 * grated trestle. It runs THROUGH its stops in order -- each a sector
	 * stamped across the line with a portal in both end walls, so the track
	 * enters one side and leaves by the other -- and ends when it runs out of
	 * stops, of room, or of reach.
	 *
	 * <p>Lines are what make the underground a network rather than a set of
	 * rooms. The caves were carved and linked long before there was a
	 * facility, and a building sitting in them was a place the caves happened
	 * to reach; a line that runs sixty tiles out through three caverns and a
	 * lake to the coolant reserve is the facility reaching into the caves,
	 * which is a different thing to look at and to live beside.
	 *
	 * <p>They run through their stops rather than ending at one because that
	 * is the difference between a network and three sidings. A line that
	 * terminates at its outpost says the outpost is the end of the world; a
	 * line that carries on out the far wall says there is somewhere else.
	 *
	 * <p>A jog every so often, one tile at a time, so the run is a line
	 * somebody surveyed and not a ruler laid across the map. The bend is two
	 * tiles of rail, so the track stays joined through it.
	 *
	 * <p>A stop goes at the first point past a minimum run where its footprint
	 * is inside the map and clear of anything that must not be built over. A
	 * fixed length landed on a link station's hole two times in three -- the
	 * cave level has fifty-odd of them -- and a stop that will not fit is a
	 * stop that is not there. Where the map runs out before the stops do, the
	 * later ones simply do not exist on that seed.
	 *
	 * <p>{@code above}, when given, is a second plan stamped on the level over
	 * each stop on the same origin -- Lambda's upper floor -- and joined to it
	 * by every stairwell the two plans can agree on. It is taken back down if
	 * none can be cut.
	 */
	/**
	 * The annexes packed around what is already built, each joined to it by a
	 * doorway or a corridor rather than by a journey.
	 *
	 * <p>A line is the wrong tool for most of a complex. Sent down one, every
	 * sector stands alone in the rock with {@link #LINE_MIN_RUN} tiles of
	 * nothing between it and the next, and the result reads as stations rather
	 * than as a place. A site plan of a complex of this kind reads the other
	 * way round: a dense cluster of boxes of different sizes, abutting and
	 * overlapping, the outline ragged because each was added against whatever
	 * was already there -- with one or two genuine outliers far off, reached
	 * by track, precisely because they are the exception.
	 *
	 * <p>So the annexes go here and the outliers stay on {@link #layLine}.
	 * Each annex is tried against every side of every box already standing --
	 * the core first, then whatever has since been added to it, which is what
	 * lets the third one tuck into the corner the first two made. It takes the
	 * cheapest site that buries the least cavern, prefers to share a wall over
	 * standing off one, and prefers to be somewhere the other annexes are not,
	 * so the cluster grows round the core instead of down one side of it.
	 */
	private static Leg[] packCampus(World w, int cols, int rows, int z,
			int x0, int y0, int cw, int ch, Leg[] annexes) {
		int[][] boxes = new int[annexes.length + 1][];
		int nb = 0;
		boxes[nb++] = new int[] { x0, y0, cw, ch };
		double cx = x0 + cw * 0.5, cy = y0 + ch * 0.5;
		Leg[] spilt = new Leg[annexes.length];
		int ns = 0;
		for (Leg a : annexes) {
			int[] at = null;
			Leg best = null;
			int[] from = null;
			double bestScore = Double.MAX_VALUE;
			for (int bi = 0; bi < nb; bi++) {
				int[] b = boxes[bi];
				for (int d = 0; d < 4; d++) {
					Leg leg = new Leg(Tile.dirDx(d), Tile.dirDy(d), a.stop, a.above);
					int lo = (leg.dx != 0 ? b[1] : b[0]) - CAMPUS_SLIDE;
					int hi = (leg.dx != 0 ? b[1] + b[3] : b[0] + b[2]) + CAMPUS_SLIDE;
					for (int along = lo; along <= hi; along++) {
						for (int gap = 0; gap <= CAMPUS_GAP; gap++) {
							int ex = leg.dx != 0
									? (leg.dx > 0 ? b[0] + b[2] - 1 + gap : b[0] - gap) : along;
							int ey = leg.dy != 0
									? (leg.dy > 0 ? b[1] + b[3] - 1 + gap : b[1] - gap) : along;
							double score = campusScore(w, cols, rows, z, ex, ey, leg,
									a.above, boxes, nb, gap, cx, cy);
							if (score < bestScore) {
								bestScore = score;
								at = new int[] { ex, ey };
								best = leg;
								from = b;
							}
						}
					}
				}
			}
			if (at == null) {
				spilt[ns++] = a; // no room on the campus: it goes out on track
				continue;
			}
			joinTo(w, z, from, best, at[0], at[1]);
			stampStop(w, z, at[0], at[1], best, a.above);
			boxes[nb++] = stopBox(at[0], at[1], best, best.stop);
		}
		Leg[] out = new Leg[ns];
		System.arraycopy(spilt, 0, out, 0, ns);
		return out;
	}

	/**
	 * A route out to the outliers: the sectors that are meant to be off on
	 * their own, followed by any the campus had no room for.
	 *
	 * <p>The second kind is why this exists. A cluster is bounded by the
	 * cavern around it, and on a map whose caves come in close there is a
	 * seed or two where the third annex simply has nowhere to stand -- and a
	 * sector that does not exist is much worse than a sector further out than
	 * intended. Each one past the first turns, alternately north and south,
	 * so the overflow spreads instead of queueing down one heading.
	 */
	private static Leg[] outliers(Leg first, Leg[] spilt) {
		Leg[] route = new Leg[spilt.length + 1];
		route[0] = first;
		for (int i = 0; i < spilt.length; i++) {
			route[i + 1] = new Leg(0, i % 2 == 0 ? -1 : 1, spilt[i].stop, spilt[i].above);
		}
		return route;
	}

	/**
	 * What an annex would cost standing here, or {@code MAX_VALUE} where it
	 * cannot stand at all: cavern buried, plus a penalty for every tile it
	 * stands off what it joins, less a bonus for being clear of the annexes
	 * already placed.
	 */
	private static double campusScore(World w, int cols, int rows, int z, int ex, int ey,
			Leg leg, String[] above, int[][] boxes, int nb, int gap, double cx, double cy) {
		if (!stopFits(w, cols, rows, z, ex, ey, leg, above)) {
			return Double.MAX_VALUE;
		}
		int[] box = stopBox(ex, ey, leg, leg.stop);
		// An annex abuts what is already built; it does not stand ON it. This
		// is the same test the upper floors use, asked of its own level.
		if (!upperFits(w, z, box[0], box[1], box[2], box[3])) {
			return Double.MAX_VALUE;
		}
		int cost = 0;
		for (int x = box[0]; x < box[0] + box[2]; x++) {
			for (int y = box[1]; y < box[1] + box[3]; y++) {
				if (w.getTile(x, y, z).getType() != Tile.TileType.TYPE_WALL) {
					cost++;
				}
			}
		}
		// Clear of the OTHER annexes, not of the core -- the core is the thing
		// they are all meant to be against. Index zero is the core.
		double bx = box[0] + box[2] * 0.5, by = box[1] + box[3] * 0.5;
		double clear = CAMPUS_SPREAD;
		int crowd = 0;
		for (int i = 1; i < nb; i++) {
			int[] o = boxes[i];
			double ox = o[0] + o[2] * 0.5, oy = o[1] + o[3] * 0.5;
			clear = Math.min(clear, Math.hypot(bx - ox, by - oy));
			// And on a different side of the CORE, which distance alone does
			// not buy: three annexes in a row all clear each other by a full
			// footprint and still come out as one arm off one wall, because
			// the far side of the core is more cavern and so always scores
			// worse on cost. A complex wraps what it grew from.
			if (quadrant(bx - cx, by - cy) == quadrant(ox - cx, oy - cy)) {
				crowd++;
			}
		}
		return cost + gap * 6.0 - clear * 2.0 + crowd * CAMPUS_CROWD
				+ Utils.noise2(box[0] * 5 + 900, box[1] * 3 + 400, 0.37) * 10.0;
	}

	/** Which way a box lies from the middle of the campus, as one of four. */
	private static int quadrant(double dx, double dy) {
		return Math.abs(dx) >= Math.abs(dy) ? (dx >= 0 ? 0 : 1) : (dy >= 0 ? 2 : 3);
	}

	/**
	 * The way in: a doorway punched through the wall of the box being joined,
	 * and track across whatever gap is left to the annex's own portal. The
	 * annex's near wall is opened by {@link #stampStop}, so this meets it.
	 */
	private static void joinTo(World w, int z, int[] b, Leg leg, int ex, int ey) {
		int wx = leg.dx != 0 ? (leg.dx > 0 ? b[0] + b[2] - 1 : b[0]) : ex;
		int wy = leg.dy != 0 ? (leg.dy > 0 ? b[1] + b[3] - 1 : b[1]) : ey;
		setBare(w, wx, wy, z, Tile.TileType.TYPE_RAIL);
		for (int x = wx + leg.dx, y = wy + leg.dy;
				leg.dx != 0 ? (leg.dx > 0 ? x <= ex : x >= ex) : (leg.dy > 0 ? y <= ey : y >= ey);
				x += leg.dx, y += leg.dy) {
			if (!layRail(w, z, x, y)) {
				break;
			}
			shoulder(w, z, x, y, leg);
		}
	}

	/**
	 * The wall a line can actually leave a core by: the one with the longest
	 * clear run out of it, east on a tie because that is the end the tram
	 * shed's own track runs to.
	 *
	 * <p>Measured rather than inferred. The first version asked which walls
	 * the campus had built against and took one of the others, and that is
	 * the wrong question twice over: an annex set against the east wall but
	 * slid well north of it does not block a line leaving eastward on the
	 * shed's row, while one nominally to the north can sit squarely across
	 * it. Walking the tiles answers what the bookkeeping only guessed at --
	 * two seeds in twenty-four laid sixteen tiles of track into the side of
	 * the mine head they had just built and stopped there.
	 */
	private static int freeSide(World w, int cols, int rows, int z,
			int x0, int y0, int cw, int ch) {
		int best = Tile.DIR_E, far = -1;
		for (int d : new int[] { Tile.DIR_E, Tile.DIR_W, Tile.DIR_S, Tile.DIR_N }) {
			int[] pt = portalOn(d, x0, y0, cw, ch);
			int run = 0;
			while (run < LINE_REACH) {
				int nx = pt[0] + Tile.dirDx(d) * (run + 1), ny = pt[1] + Tile.dirDy(d) * (run + 1);
				if (nx < 4 || ny < 4 || nx >= cols - 4 || ny >= rows - 4
						|| !railable(w, z, nx, ny)) {
					break;
				}
				run++;
			}
			if (run > far) {
				far = run;
				best = d;
			}
		}
		return best;
	}

	/** Where a line leaves a core by a given wall: on the tram shed's own row
	 *  where that wall is an end of it, on its middle column where it is a
	 *  side. */
	private static int[] portalOn(int d, int x0, int y0, int cw, int ch) {
		return new int[] {
				d == Tile.DIR_E ? x0 + cw - 1 : d == Tile.DIR_W ? x0 : x0 + 1 + 21,
				d == Tile.DIR_S ? y0 + ch - 1 : d == Tile.DIR_N ? y0 : y0 + 1 + 21 };
	}

	/** How far an annex may slide past the end of the side it is set against,
	 *  so a campus comes out with a ragged outline rather than a flush one. */
	private static final int CAMPUS_SLIDE = 6;

	/** The most an annex may stand off what it joins. Zero is a shared wall;
	 *  past four it stops reading as one complex. */
	private static final int CAMPUS_GAP = 4;

	/** How far apart annexes are worth keeping. Past this they are simply on
	 *  different sides of the core and no further bonus is owed. */
	private static final int CAMPUS_SPREAD = 34;

	/** What it costs an annex to be on the same side of the core as one that
	 *  is already there. Enough to outweigh the cavern the far side buries,
	 *  which is the only reason every annex wanted the same wall. */
	private static final double CAMPUS_CROWD = 120.0;

	private static void layLine(World w, int cols, int rows, int z, int px, int py, Leg[] route) {
		if (walkLine(w, cols, rows, z, px, py, route, true) < route.length && anyPaired(route)) {
			// A leg could not place its stop with both its floors, so run the
			// route again taking ground floors. A two-storey Lambda is the
			// better building and a Lambda is better than none: insisting on
			// the pair left three seeds in eight with no complex at all,
			// because an upper floor may not stand on another building and the
			// cave line's stops get first call on the ground above.
			//
			// Asked as "placed nothing at all" this never fired once Lambda
			// sat behind a mine head on the same line -- the mine head places,
			// the count is one, and the retry the last leg needed was skipped.
			// The line is short enough that laying it twice costs nothing, and
			// every stop is placed at the same first fit both times.
			walkLine(w, cols, rows, z, px, py, route, false);
		}
	}

	/** Whether any leg of a route asks for a second floor. */
	private static boolean anyPaired(Leg[] route) {
		for (Leg leg : route) {
			if (leg.above != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * One leg of a route: a direction to travel, the stop to put at the end of
	 * it, and optionally a second floor to stand over that stop.
	 *
	 * <p>A route is a list of these, and the line TURNS between them. That is
	 * the whole difference between a transit system and a spine: the first
	 * version stepped only in x, every line left the facility on the same row
	 * of its tram shed, and every stop a line could meet had to be drawn with
	 * a horizontal row of track — so five sectors came out in a single
	 * east-west band across the middle of the map, which is not a network, it
	 * is a corridor with rooms off it.
	 */
	private static final class Leg {
		/** The plan to put at the end of this leg, or null for a RUN: a leg
		 *  that lays track and then simply turns.
		 *
		 *  <p>A route needs corners more often than it needs sectors at them.
		 *  Lambda sent straight out of the works spends its whole reach on one
		 *  axis, and on a seed where that axis runs under the campus above it
		 *  the upper floor never finds room -- the complex comes out a
		 *  basement, which is the single largest thing in the world quietly
		 *  half-built. Behind a mine head it had its corner, but the mine head
		 *  cost it thirty tiles and a footprint of reach, and on a map that
		 *  sites the works west that put the head on the rim with no room to
		 *  turn at all. A run is the corner without the price. */
		final int dx, dy;
		final String[] stop;
		final String[] above;

		Leg(int dx, int dy, String[] stop, String[] above) {
			this.dx = dx;
			this.dy = dy;
			this.stop = stop;
			this.above = above;
		}
	}

	/**
	 * One pass of a route, returning how many stops it managed to place. A leg
	 * that cannot place its stop ends the line: the legs after it are places
	 * this seed's map had no room for.
	 */
	private static int walkLine(World w, int cols, int rows, int z, int px, int py,
			Leg[] route, boolean paired) {
		setBare(w, px, py, z, Tile.TileType.TYPE_RAIL); // the portal through the shell
		int x = px, y = py;
		int placed = 0;
		for (int li = 0; li < route.length; li++) {
			Leg drawn = route[li];
			String[] above = paired ? drawn.above : null;
			// A TURN's direction is a preference and the map gets a veto:
			// pointed somewhere with no room for its stop, it turns the other
			// way instead.
			//
			// The veto used to be measured in MAP EDGES -- is there further to
			// go this way than that -- and that asks the wrong question. What
			// stops a stop is almost never the rim; it is the campus on the
			// floor above, which an upper floor may not stand on and which the
			// distance to the edge knows nothing about. Four seeds in
			// twenty-four sent Lambda down an axis with room to spare and no
			// site on it, and it came out a basement or not at all.
			//
			// So ask the real question instead, by walking the heading and
			// asking stopFits at each step. Nothing is laid, so it costs only
			// the scan -- and it subsumes the old test, since running out of
			// map is one of the ways a heading has no site on it.
			//
			// The FIRST leg still never flips. Its direction is which portal
			// of the shed the line leaves by, and two lines that both consult
			// the map both leave by the same one and run down each other.
			Leg leg = drawn;
			if (li > 0 && drawn.stop != null) {
				Leg back = new Leg(-drawn.dx, -drawn.dy, drawn.stop, drawn.above);
				// And it overrides only when the drawn way genuinely CANNOT
				// hold the stop, not merely when the other way holds it
				// sooner. Flipping on "sooner" sent both of a level's branches
				// the same way whenever the facility sat slightly off centre,
				// which throws away the one thing the route is drawn to decide.
				if (reachToFit(w, cols, rows, z, x, y, drawn, above) < 0
						&& reachToFit(w, cols, rows, z, x, y, back, above) >= 0) {
					leg = back;
				}
			}
			int laid = 0;
			boolean done = false;
			for (int i = 1; i <= LINE_REACH && !done; i++) {
				int nx = x + leg.dx, ny = y + leg.dy;
				if (nx < 4 || ny < 4 || nx >= cols - 4 || ny >= rows - 4) {
					break;
				}
				// The jog is ACROSS the run, so a line that turns wanders the
				// same way whichever way it is pointing.
				if (i % 13 == 0) {
					double turn = Utils.noise2(nx * 3 + 5000, ny * 7 + 1200, 0.31);
					int step = turn > 0.6 ? 1 : turn < 0.4 ? -1 : 0;
					int jx = nx + step * leg.dy, jy = ny + step * leg.dx;
					if (step != 0 && jx >= 4 && jy >= 4 && jx < cols - 4 && jy < rows - 4
							&& layRail(w, z, nx, ny)) {
						shoulder(w, z, nx, ny, leg);
						nx = jx;
						ny = jy;
					}
				}
				if (!layRail(w, z, nx, ny)) {
					break; // another building: the line stops at its wall
				}
				shoulder(w, z, nx, ny, leg);
				x = nx;
				y = ny;
				laid++;
				if (laid >= LINE_MIN_RUN && leg.stop == null) {
					// A RUN: a leg with nowhere to go, which exists only so the
					// route can change axis. See the Leg doc comment. So it
					// ends where the route can ACTUALLY change axis, not after
					// a fixed thirty tiles: stopping at the first thirty put
					// the corner wherever it happened to land, and where that
					// was hard against a wall on both perpendiculars the leg
					// after it had nowhere at all to go. Walking on costs
					// nothing -- track is the cheapest thing here -- and the
					// corner lands somewhere a complex can stand.
					Leg nxt = li + 1 < route.length ? route[li + 1] : null;
					if (nxt != null && nxt.stop != null) {
						String[] na = paired ? nxt.above : null;
						Leg alt = new Leg(-nxt.dx, -nxt.dy, nxt.stop, nxt.above);
						if (reachToFit(w, cols, rows, z, x, y, nxt, na) < 0
								&& reachToFit(w, cols, rows, z, x, y, alt, na) < 0) {
							continue;
						}
					}
					placed++;
					done = true;
					continue;
				}
				if (laid >= LINE_MIN_RUN && leg.stop != null
						&& stopFits(w, cols, rows, z, x, y, leg, above)) {
					int[] exit = stampStop(w, z, x, y, leg, above);
					x = exit[0];
					y = exit[1];
					// Clear the building before the next leg turns. A portal
					// sits IN a wall, so the tile beside it on the turn is the
					// stop's own shell -- and a line that turns there stops
					// dead against it. Every second stop on every line went
					// missing that way, which reads as the map being too
					// small and is nothing of the kind.
					for (int k = 0; k < TURN_CLEARANCE; k++) {
						int cx = x + leg.dx, cy = y + leg.dy;
						if (cx < 4 || cy < 4 || cx >= cols - 4 || cy >= rows - 4
								|| !layRail(w, z, cx, cy)) {
							break;
						}
						shoulder(w, z, cx, cy, leg);
						x = cx;
						y = cy;
					}
					placed++;
					done = true;
				}
			}
			if (!done) {
				break;
			}
		}
		return placed;
	}

	/**
	 * How far along a heading the first site its stop would fit is, or -1 if
	 * there is none within reach. A pure query -- nothing is laid — so a route
	 * can ask which way to turn before it commits to turning.
	 */
	private static int reachToFit(World w, int cols, int rows, int z, int x, int y,
			Leg leg, String[] above) {
		for (int i = 1; i <= LINE_REACH; i++) {
			int nx = x + leg.dx * i, ny = y + leg.dy * i;
			// Where the LINE can get to, not merely where a stop would fit:
			// asked without this, a heading that has a site sixty tiles out
			// behind a wall at forty reads as open, the veto does not fire,
			// and the line walks into the wall. Which is how a route turned
			// back into its own campus.
			if (nx < 4 || ny < 4 || nx >= cols - 4 || ny >= rows - 4
					|| !railable(w, z, nx, ny)) {
				return -1;
			}
			if (i >= LINE_MIN_RUN && stopFits(w, cols, rows, z, nx, ny, leg, above)) {
				return i;
			}
		}
		return -1;
	}

	/** Whether track could be laid here at all. The read-only half of
	 *  {@link #layRail}: everything else it meets, it builds over or bridges. */
	private static boolean railable(World w, int z, int x, int y) {
		Tile.TileType ty = w.getTile(x, y, z).getType();
		return ty != Tile.TileType.TYPE_WALL_CONCRETE && ty != Tile.TileType.TYPE_WALL_STEEL;
	}

	/** The paved shoulders either side of a tile of track, across the run. */
	private static void shoulder(World w, int z, int x, int y, Leg leg) {
		layShoulder(w, z, x + leg.dy, y + leg.dx);
		layShoulder(w, z, x - leg.dy, y - leg.dx);
	}

	/** How far a line runs past its last stop before it simply ends. */
	private static final int LINE_REACH = 220;

	/** How far a line runs on past a stop before it is allowed to turn: enough
	 *  to be clear of the shell the portal is set in, corner included. */
	private static final int TURN_CLEARANCE = 3;

	/** The least a line runs between stops. A stop is somewhere the line goes
	 *  TO, and thirty tiles is about the shortest journey that reads as one. */
	private static final int LINE_MIN_RUN = 30;

	/**
	 * One tile of track, or false where track cannot go. Rock is tunnelled;
	 * open floor is crossed; water and shafts are bridged on catwalk; the
	 * ends of links between floors and anything wired are left exactly as
	 * they are, because a rail laid over a ramp is a ramp that no longer
	 * climbs. A built wall stops the line.
	 */
	private static boolean layRail(World w, int z, int x, int y) {
		Tile t = w.getTile(x, y, z);
		Tile.TileType ty = t.getType();
		if (ty == Tile.TileType.TYPE_WALL_CONCRETE || ty == Tile.TileType.TYPE_WALL_STEEL) {
			return false;
		}
		if (isRampOrDrop(ty) || ty == Tile.TileType.TYPE_DUCT) {
			return true; // left alone, and the line carries on past it
		}
		if (mustStay(w, z, x, y)) {
			// The rock over a ramp's high side. Track cannot pass through it
			// without leaving the ramp climbing into air, so the line stops
			// here exactly as it stops at a wall.
			return false;
		}
		if (ty == Tile.TileType.TYPE_WATER || ty == Tile.TileType.TYPE_SHAFT) {
			setBare(w, x, y, z, Tile.TileType.TYPE_CATWALK);
			return true;
		}
		setBare(w, x, y, z, Tile.TileType.TYPE_RAIL);
		return true;
	}

	/** The shoulder beside the track: paved through rock, trestle over water,
	 *  and whatever it already was where it was already ground. */
	private static void layShoulder(World w, int z, int x, int y) {
		if (mustStay(w, z, x, y)) {
			return; // see mustStay: a shoulder is not worth a ramp
		}
		Tile.TileType ty = w.getTile(x, y, z).getType();
		if (ty == Tile.TileType.TYPE_WALL || ty == Tile.TileType.TYPE_CRYSTAL) {
			setBare(w, x, y, z, Tile.TileType.TYPE_PAVED);
		} else if (ty == Tile.TileType.TYPE_WATER || ty == Tile.TileType.TYPE_SHAFT) {
			setBare(w, x, y, z, Tile.TileType.TYPE_CATWALK);
		}
	}

	/**
	 * A plan turned a quarter turn clockwise, so a sector drawn along an
	 * east-west track can stand on a north-south one. A ninety-degree lattice
	 * rotation is lossless on a square grid (ART-STYLE section 5), which is
	 * why the sectors are drawn once and turned rather than drawn twice.
	 */
	private static String[] turned(String[] plan) {
		int h = plan.length, wd = plan[0].length();
		String[] out = new String[wd];
		for (int i = 0; i < wd; i++) {
			StringBuilder sb = new StringBuilder(h);
			for (int j = 0; j < h; j++) {
				sb.append(plan[h - 1 - j].charAt(i));
			}
			out[i] = sb.toString();
		}
		return out;
	}

	/** The plan as the line meets it: drawn for an east-west run, turned for a
	 *  north-south one. */
	private static String[] oriented(String[] plan, Leg leg) {
		return leg.dx != 0 ? plan : turned(plan);
	}

	/**
	 * Where the track runs through a plan: the index of the one line of it that
	 * is nothing but rail -- a row for a stop on an east-west run, a column for
	 * one on a north-south run.
	 *
	 * <p>Read off the drawing rather than written down beside it, so a sector
	 * redrawn with its track one row over still meets its line.
	 */
	private static int trackAt(String[] plan, boolean vertical) {
		int n = vertical ? plan[0].length() : plan.length;
		outer: for (int k = 0; k < n; k++) {
			if (!vertical) {
				if (plan[k].chars().allMatch(ch -> ch == 'r')) {
					return k;
				}
				continue;
			}
			for (String row : plan) {
				if (row.charAt(k) != 'r') {
					continue outer;
				}
			}
			return k;
		}
		throw new IllegalArgumentException("a stop needs a line of rail");
	}

	/** A stop's shell corner, given the tile the line has reached and the leg
	 *  it is travelling: {x0, y0, width, height}. */
	private static int[] stopBox(int ex, int ey, Leg leg, String[] plan) {
		String[] o = oriented(plan, leg);
		int sw = o[0].length() + 2, sh = o.length + 2;
		int t = trackAt(o, leg.dy != 0);
		if (leg.dx != 0) {
			return new int[] { leg.dx > 0 ? ex + 1 : ex - sw, ey - 1 - t, sw, sh };
		}
		return new int[] { ex - 1 - t, leg.dy > 0 ? ey + 1 : ey - sh, sw, sh };
	}

	/** Whether a stop could stand where a line has reached (ex, ey) on this
	 *  leg: inside the map, and on nothing that must not be built over. */
	private static boolean stopFits(World w, int cols, int rows, int z, int ex, int ey, Leg leg,
			String[] above) {
		int[] box = stopBox(ex, ey, leg, leg.stop);
		int x0 = box[0], y0 = box[1], sw = box[2], sh = box[3];
		if (x0 < 2 || y0 < 2 || x0 + sw > cols - 2 || y0 + sh > rows - 2) {
			return false;
		}
		// Links between floors inside the footprint are built ROUND, not
		// refused -- the same rule the facility and the upper floors already
		// follow. Refusing does not scale with the stop: the underdark's
		// stairwells scale with the map, so an eighteen by twenty-six
		// footprint covers one about six times in seven, and Lambda's ground
		// floor stopped landing on three seeds in eight the moment its line
		// was turned to reach somewhere the cave line had not already taken.
		// A stairwell surfacing inside the complex is a way in.
		//
		// A two-floor stop has to fit on BOTH floors, and the test belongs
		// HERE rather than after the lower floor is stamped. Checked
		// afterwards, the line commits to a position the upper floor cannot
		// use and Lambda comes out a basement: measured across eight seeds,
		// the cave level over a twenty-six by eighteen footprint holds one of
		// the fifty-odd link stations about seven times in eight, so the
		// upper floor landed exactly never. Asked here, the line simply walks
		// on until it finds somewhere both floors can stand.
		return above == null || !matched(leg.stop, above)
				|| upperFits(w, z + 1, x0, y0, sw, sh);
	}

	/** Whether a paired plan is drawn to the same extent as the one it sits
	 *  over -- the two floors share an origin, so a mismatch is a drawing
	 *  error rather than something to accommodate. */
	private static boolean matched(String[] plan, String[] above) {
		return above.length == plan.length && above[0].length() == plan[0].length();
	}

	/**
	 * A stop across the line: the plan stamped -- turned a quarter if the line
	 * is running north-south -- so its track meets the rail where the line
	 * arrived, a portal through each of the two walls the track crosses, and
	 * doors wherever a cavern meets it. Returns the far portal, which is where
	 * the line resumes. The caller has already checked it fits.
	 */
	private static int[] stampStop(World w, int z, int ex, int ey, Leg leg, String[] above) {
		// The plan's OWN size, not one figure for every stop: the sectors are
		// twenty by twelve, the mine head twelve by eight and Lambda
		// twenty-four by sixteen, and a portal placed at a fixed width lands
		// outside the smaller ones' walls -- which is a stop the line enters
		// and never leaves.
		int[] box = stopBox(ex, ey, leg, leg.stop);
		int x0 = box[0], y0 = box[1], sw = box[2], sh = box[3];
		stampPlan(w, z, x0, y0, oriented(leg.stop, leg), true);
		// The two portals, on the walls the track runs through.
		if (leg.dx != 0) {
			setBare(w, x0, ey, z, Tile.TileType.TYPE_RAIL);
			setBare(w, x0 + sw - 1, ey, z, Tile.TileType.TYPE_RAIL);
		} else {
			setBare(w, ex, y0, z, Tile.TileType.TYPE_RAIL);
			setBare(w, ex, y0 + sh - 1, z, Tile.TileType.TYPE_RAIL);
		}
		punchDoors(w, z, x0, y0, sw, sh);
		if (above != null && matched(leg.stop, above) && z + 1 < w.getLevels()) {
			stampPlan(w, z + 1, x0, y0, oriented(above, leg), true);
			punchDoors(w, z + 1, x0, y0, sw, sh);
			if (stairsBetween(w, x0, y0, sw, sh, z + 1, z) == 0) {
				// An upper floor no stair reaches is a room nothing can enter:
				// back to rock, and the lower floor stands on its own.
				for (int x = x0; x < x0 + sw; x++) {
					for (int y = y0; y < y0 + sh; y++) {
						setBare(w, x, y, z + 1, Tile.TileType.TYPE_WALL);
					}
				}
			}
		}
		if (leg.dx != 0) {
			return new int[] { leg.dx > 0 ? x0 + sw - 1 : x0, ey };
		}
		return new int[] { ex, leg.dy > 0 ? y0 + sh - 1 : y0 };
	}

	/** Whether a paired upper floor can stand over a stop: on no OTHER
	 *  building. Links between floors are built round rather than refused --
	 *  see {@link #stampPlan(World, int, int, int, String[], boolean)}. */
	private static boolean upperFits(World w, int z, int x0, int y0, int sw, int sh) {
		if (z >= w.getLevels()) {
			return false;
		}
		for (int x = x0; x < x0 + sw; x++) {
			for (int y = y0; y < y0 + sh; y++) {
				Tile.TileType t = w.getTile(x, y, z).getType();
				if (t == Tile.TileType.TYPE_WALL_CONCRETE
						|| t == Tile.TileType.TYPE_WALL_STEEL || t == Tile.TileType.TYPE_PLATE) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * The underdark: a mine head packed against the works, and the long line
	 * out to the Lambda complex. Laid after the underdark's caverns are carved
	 * and linked, so the line crosses them rather than being carved around,
	 * and so its stops can stand among them.
	 *
	 * <p>Lambda is the ONE sector meant to be far away -- a separate complex
	 * with its own bunker, reached across open ground -- so it is the one that
	 * gets a line to itself. Everything else on this floor belongs to the
	 * works and stands against it.
	 *
	 * <p>It used to ride behind a mine head on the same line, and that cost it
	 * {@link #LINE_MIN_RUN} tiles plus the mine head's own footprint before it
	 * could even start looking. On a map that sites the works well west, the
	 * mine head landed on the rim and there was no room left to turn: Lambda
	 * did not exist at all, which is the largest thing in the world missing
	 * without a word. The mine head packs into the campus now, so the line
	 * spends its whole reach on the complex it is for.
	 */
	private static void layDeepLine(World w, int cols, int rows, int x0, int y0) {
		packCampus(w, cols, rows, DEEP_Z, x0, y0, FACILITY_W, FACILITY_H, new Leg[] {
				new Leg(0, 0, MINEHEAD, null) });
		// Out of a wall the mine head is not against, and turning at the far
		// end, so Lambda's upper floor comes down somewhere the cave campus
		// has not already built: an upper floor may not stand on another
		// building, and sent along one axis it spends its whole reach walking
		// out from under the sectors above.
		int d = freeSide(w, cols, rows, DEEP_Z, x0, y0, FACILITY_W, FACILITY_H);
		int[] pt = portalOn(d, x0, y0, FACILITY_W, FACILITY_H);
		// And it turns toward the LARGER half of the map. Lambda wants
		// LINE_MIN_RUN tiles plus its own twenty-six before it can stand, and
		// a works sited in the southern third has barely forty rows south of
		// it -- turned that way by a constant, the complex had nowhere to go
		// on a map with a hundred and fifteen rows the other way. There is
		// only one line on this floor, so nothing else can be sent into.
		int t = d == Tile.DIR_E || d == Tile.DIR_W
				? (y0 + FACILITY_H / 2 > rows / 2 ? Tile.DIR_N : Tile.DIR_S)
				: (x0 + FACILITY_W / 2 > cols / 2 ? Tile.DIR_W : Tile.DIR_E);
		layLine(w, cols, rows, DEEP_Z, pt[0], pt[1], new Leg[] {
				new Leg(Tile.dirDx(d), Tile.dirDy(d), null, null),
				new Leg(Tile.dirDx(t), Tile.dirDy(t), LAMBDA_LOWER, LAMBDA_UPPER) });
	}

	/**
	 * The works' plan, in its own interior coordinates: forty-four tiles wide
	 * by twenty-six tall, with the concrete shell added around it.
	 *
	 * <pre>
	 *   .  deck plate      C  coolant run     S  waste sump     P  pipe run
	 *   X  heat exchanger  w  catwalk         R  collapsed deck V  vent grille
	 *   T  loading deck    L  lit grating     B  shard bed      b  loose shards
	 *   s  steel bulkhead  #  concrete wall   p  paved aisle    r  tram rail
	 *   d  crawl duct      D  charge dock     E  server bank    H  drop shaft
	 *   F  fungus bed      K  crystal cluster  t  turbine         c  control panel
	 *   l  lift shaft      G  window wall      k  desk            u  bunk
	 *   z  hazard stripe   v  conveyor         W  dead machine
	 *   ~  water           M  mud              Y  reed bed        Q  thicket
	 * </pre>
	 *
	 * <p>Drawn rather than computed, and that is the whole of the method. An
	 * earlier version placed each feature by arithmetic off the room's centre —
	 * exchangers at cx+-2, coolant at cy+-3, a sump four in from the corner —
	 * and the features quietly wrote over one another in the order they
	 * happened to be listed. The loading walk erased the coolant loop's entire
	 * south leg, so the loop was three sides of a rectangle; the collapse ate
	 * its east end; the stairwell landed in the sump and cut it from three
	 * tiles to one. None of that is visible in the source, all of it is
	 * obvious in a render, and none of it could fail a test. ART-STYLE.md
	 * section 5 already says authored beats computed for discrete things, and a
	 * building is a discrete thing. At this size it is also the only way the
	 * drawing stays readable at all.
	 *
	 * <pre>
	 *   rows  0..8    reactor hall | pump gallery | store and vault
	 *   row   9       partition, with doorways and a crawl duct
	 *   rows 10..17   the turbine hall, under the halls' atrium | the workings
	 *   row  18       partition
	 *   rows 19..25   the lower spine: tram run, drain, marshalling deck
	 * </pre>
	 *
	 * <p>The building is one fixed size rather than the station's, because it
	 * is cut into virgin rock and nothing up there constrains it — which is
	 * also why it is far bigger than any station above it. The caves never
	 * leave a rock pocket larger than about 26x17 whatever the map size, so the
	 * shell up there cannot grow; down here there is no pocket to find and
	 * nothing to displace, and the rooms that would not fit are simply here.
	 * The plan is centred under whichever shell it is handed and pushed back
	 * inside the map if that would hang it off an edge.
	 *
	 * <p>What is down here is the plant the rooms above keep referring to. The
	 * machine wing has a coolant run and an exchanger dumping heat, and they
	 * used to come from nowhere: the run started at a wall. Now it starts
	 * somewhere, and the somewhere has a second stage — a condenser block on
	 * its own closed loop, the settling sumps it drains through, and the
	 * gallery walk between them. Past that, a shard-bed working lit from below,
	 * a shaft bay crossed by gantries, and a tram run down the whole length of
	 * the level tying the three halls together.
	 *
	 * <p>Every tile it uses already existed. The point of the rooms is not new
	 * terrain but somewhere for the terrain to mean something: the crystal bed
	 * has been in the caves all along, and putting lit grating and a walkway
	 * around one says a facility was studying it.
	 */
	static final String[] WORKS = {
			"PV..CCCCCCCw.RRR#PPPPPPPPPPPPPP#p...........",
			"P...CXXXXXCw.RRR#......CCCCCCCC#pssssssssss.",
			"P...CXXXXXCwsTTT#.SSwSSCXXXXXXC#ps....B...s.",
			"P...CXXXXXCwsTTT#.SSwSSCXXXXXXC.p..EE.LEE.s.",
			"P...CCCCCCCwsTTT..SSwSSCCCCCCCC#ps.EE..EE.s.",
			"PLLL.......w....#.SSwSS........#ps....L...s.",
			"PLBL.TTTTTTTT...#.V..........V.dpssssssssss.",
			"PLLL...SSS......d.TTTTTTTTTTTT.#p..........V",
			"P..V...SSSV.....#.......RR.....#p.DDDDDDDD..",
			"##dddp######p#######p#########p######p###p##",
			"..LLLLLLLLLLLLLLLLLLLLLLLL.#.LLLLww.LLLL.FF.",
			".VLCCCCCCCCCCCCLLTTLLLSSLL.d.BBBBww.BBBB.FFV",
			"..LCBBBBBBBBBBCLLTTLLLSSLL.#.BBBBww.BBBB.FF.",
			"..LCBBBBBBBBBBCLLTTLLLLLLV...LLLLww.LLLL.FF.",
			"..LCCCCCCCCCCCCLLTTLLLLLLL.#.....ww.........",
			".VLLLLLLLLLLLLLLLLLLLLLLLL.#TTTTTTTTTTTTTTT.",
			"..LLLLLLLLLLLLLLLLLLLLLLLL.#.Sb..ww.........",
			"...........................#.Sb..ww.........",
			"#######p###ddd#p########p########p######p###",
			".PPPPPPPP.V......................V..........",
			".pppppppppppppppppppppppppppppppppppppppppp.",
			"rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr",
			".pppppppppppppppppppppppppppppppppppppppppp.",
			"...vvvvvvvvvv............EE..........HHHHH..",
			"...SSSS..RRRR..TTTTTTTT..EE..LLLLLL.wwwwww..",
			"...SSSS..RRRR..TTTTTTTT..EE..LLBLLL..HHHHH..",
	};

	/** The plan's interior extent, and the shell around it. */
	static final int WORKS_W = FACILITY_W, WORKS_H = FACILITY_H;

	/**
	 * The halls' plan: the facility's upper floor, on the cave level, in the
	 * same interior coordinates as the works beneath it and on the same origin.
	 *
	 * <pre>
	 *   rows  0..7    the concourse, the platform, the drone rank, the vault
	 *   row   8       partition
	 *   rows  9..17   the ATRIUM | the machine wing
	 *   row  18       partition
	 *   rows 19..25   the tram shed: the line wall to wall, and the stores
	 * </pre>
	 *
	 * <p>The atrium is the reason the two floors share an origin. Its floor is
	 * open void — {@code H}, the same drop shaft the storage bay below used to
	 * be — crossed by a ring of gantries and a cross of them meeting at a
	 * landing, and what you see through the grating is the turbine hall of the
	 * works directly beneath, whose floor is deliberately nothing but deck,
	 * coolant and grille so that a body stepping off a gantry lands on
	 * something. It is the one room in the world that has to be walked on two
	 * floors to be understood, and the client draws the floor below through
	 * every opening at parallax, so from above it reads as depth.
	 *
	 * <p>What the drone rank, the vault, the stores and the tram shed are for,
	 * the works' plan explains. Same legend.
	 */
	static final String[] HALLS = {
			".PPPPPPPPPPPPPPPPPPPPPPPPPPP................",
			"....EE...EE...........V.......DD..sssssssss.",
			"....EE...EE...V.........LLLL..DD..s..EE.B.s.",
			"........................LLLL......s..EE.L.s.",
			"..........................................s.",
			".TTTTTTTTTTTTTTTTTTTTTTTTTTT......s.......s.",
			"..................................ssssdssss.",
			".TTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTT",
			"#####p#######p#######p##ddd###p#######p#####",
			".wwwwwwwwwwwwwwwwwwwwwwwwww#.....G.PPPPPPPP.",
			".wHHHHHHHHHHHwHHHHHHHHHHHHwGc.cc.G.CCCCCCV..",
			".wHHHHHHHHHHHLHHHHHHHHHHHHwGc......CttttC...",
			".wHHHHHHHHHHTTTHHHHHHHHHHHwGc.kk.G.CttttC...",
			".wwwwwwLwwwwTTTwwwwLwwwwwww........CCCCCCSS.",
			".wHHHHHHHHHHTTTHHHHHHHHHHHw#.EE..........SS.",
			".wHHHHHHHHHHHLHHHHHHHHHHHHwd.RR.............",
			".wHHHHHHHHHHHwHHHHHHHHHHHHw#.RR.TTTTTTTTTV..",
			".wwwwwwwwwwwwwwwwwwwwwwwwww#................",
			"###p####ddd##p#########p#######p########p###",
			"..........V......................V..........",
			"pppppppppppppppppppppppppppppppppppppppppppp",
			"rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr",
			"pppppppppppppppppppppppppppppppppppppppppppp",
			".SSS.............TTTTTTTT...EE..............",
			".SSS...RRR.......TTTTTTTT...EE...LLLLwwwww..",
			".SSS...RRR.......TTTTTTTT...EE...LLBLLL.....",
	};

	/**
	 * The sectors, one to a stop on the lines, in the same legend as the halls
	 * and the works. Each is twenty by twelve inside with the track across its
	 * sixth row, which is the row the line arrives on and leaves by.
	 *
	 * <p>Lettered after a certain New Mexico facility's, and built on the same
	 * plan: a transit system strung between sectors that each do one thing.
	 * B the coolant reserve, C the test labs (the halls and the works
	 * themselves), D administration and the dormitories, E the biodome and its
	 * freight warehouse, F the Lambda complex at the end of the lower line. A
	 * training facility and a hydro plant are the two left for later.
	 */
	static final String[] COOLANT = {
			"PPPPPPPPPPPPPPPPPPPP",
			".CCCCCCC..ssssssss..",
			".CXXXXXC..s~~~~~~s..",
			".CXXXXXC..wwwwwwws..",
			".CCCCCCC..ssssssss..",
			"rrrrrrrrrrrrrrrrrrrr",
			"pppppppppppppppppppp",
			".SS..tt..VV...cc....",
			".SS..tt.......cc....",
			"...LLLL...ssssssss..",
			"...LLLL...w~~~~~~s..",
			"..........ssssssss..",
	};
	static final String[] ADMIN = {
			"kk.kk.kk.G.uu.uu.uu.",
			"kk.kk.kk.G.uu.uu.uu.",
			".........G..........",
			"GGGGGGGGGG.GGGGGGGGG",
			"..cc.........EE.....",
			"rrrrrrrrrrrrrrrrrrrr",
			"pppppppppppppppppppp",
			".kk.kk..GG..kk.kk...",
			".kk.kk..GG..kk.kk...",
			"........GG..........",
			".uu.uu.uu...EE..cc..",
			".uu.uu.uu...........",
	};
	static final String[] BIODOME = {
			"GGGGGGGGGGGGGGGGGGGG",
			"GFFFFGQQQQQG~~~~YYYG",
			"GFFFFGQQQQQG~~~~YYYG",
			"GFFFFGQQQQQGMM~~YYYG",
			"G....G.....G.......G",
			"rrrrrrrrrrrrrrrrrrrr",
			"pppppppppppppppppppp",
			"wwwwwwwwwwwwwwwwwwww",
			"GFFFFGYYYYYG.cc....G",
			"GFFFFGYYYYYGMMMM...G",
			"GFFFFGYYMMYG~~~~...G",
			"GGGGGGGGGGGGGGGGGGGG",
	};
	static final String[] WAREHOUSE = {
			"TTTTTTTTTTTTTTTTTTTT",
			"...vvvvvvvvvvvv...E.",
			"...............vv.E.",
			"..EE.EE.EE.....vv...",
			"..EE.EE.EE.....vv...",
			"rrrrrrrrrrrrrrrrrrrr",
			"pppppppppppppppppppp",
			".vvvvvvvvvvvvvv.....",
			"....................",
			".TTTTTTTT..cc..WW...",
			".TTTTTTTT.....SS....",
			".TTTTTTTT.....SS....",
	};
	/** The mine head: the small stop on the lower line before Lambda, where
	 *  the shards the teleport chamber runs on were cut. */
	static final String[] MINEHEAD = {
			"LLLL.BBBB.FF",
			".BB..BKKB.FF",
			"LLLL.BBBB.FF",
			"rrrrrrrrrrrr",
			"pppppppppppp",
			"pppppppppppp",
			"bbbb.LLLL.KK",
			"bbbb.LBBL.KK",
	};

	/**
	 * Sector F, the Lambda complex: two floors on one origin at the end of the
	 * lower line, the way the halls stand over the works. Below, the reactor
	 * core -- generator sets inside their own coolant loop -- and the teleport
	 * chamber, a shard floor under lit grating. Above, a control room behind
	 * glass, and an opening ringed by catwalk directly over the chamber with a
	 * lift shaft down its middle: the way into the chamber that is not the
	 * stairs.
	 */
	static final String[] LAMBDA_LOWER = {
			"PPPPPPPPPPPPPPPPPPPPPPPP",
			".CCCCCCCCCC.LLLLLLLLLLL.",
			".CttttttttC.LLLLLLLLLLL.",
			".CttttttttC.LLLLBBBLLLL.",
			".CCCCCCCCCC.LLLLBBBLLLL.",
			".cc......cc.LLLLLLLLLLL.",
			"............LLLLLLLLLLL.",
			"........................",
			"rrrrrrrrrrrrrrrrrrrrrrrr",
			"pppppppppppppppppppppppp",
			".ttt.ttt.ttt.ccc........",
			".ttt.ttt.ttt............",
			".SSS....VV...LLLLLLL....",
			".SSS.........LLLLLLL....",
			"..........EE.LLLLLLL....",
			"..........EE............",
	};
	static final String[] LAMBDA_UPPER = {
			"........................",
			".cccccccc...wwwwwwwwwww.",
			".kk.kk.kk...wHHHHHHHHHw.",
			".kk.kk.kk...wHHHllHHHHw.",
			".GGGGGGGG...wHHHllHHHHw.",
			".cccccccc...wHHHHHHHHHw.",
			"............wwwwwwwwwww.",
			"........................",
			"........................",
			"..EE..EE..EE............",
			"..EE..EE..EE..cc..cc....",
			"........................",
			".ttt.ttt................",
			".ttt.ttt....LLLLLLL.....",
			"............LLLLLLL.....",
			"........................",
	};

	/** The halls' plan, for the scenarios. Copied, like the works'. */
	public static String[] hallsPlan() {
		return HALLS.clone();
	}

	/** The works' plan, for the scenarios: the drawing the deep level is
	 *  supposed to be, so a scenario can ask whether it still is one. Copied,
	 *  so nobody can edit the rooms in place. */
	public static String[] worksPlan() {
		return WORKS.clone();
	}

	/**
	 * Cuts the works into the virgin rock under a station shell of W x H at
	 * (x0, y0), centred on it, and joins the two floors with stairwells.
	 *
	 * <p>Returns whether the works survived. It is un-carved back to rock when
	 * no stairwell could be cut: a floor with no stairs is a thousand walkable
	 * tiles nothing in the world can reach, and the connectivity audit is right
	 * to call that a broken world rather than an empty room.
	 *
	 * <p>The works is far bigger than any station above it — that is the whole
	 * design. The caves never leave a rock pocket larger than about 26x17, so
	 * the building up there cannot grow; measured across eight seeds at the
	 * doubled map size, not one offered a 30x20. Under it there is no pocket to
	 * find and nothing to displace, so the rooms that would not fit are down
	 * here and the station above is their entrance rather than their whole.
	 */
	private static boolean sinkWorks(World w, int cols, int rows, int x0, int y0, int W, int H) {
		int px0 = x0, py0 = y0; // the same origin as the halls above: see HALLS
		stampPlan(w, DEEP_Z, px0, py0, WORKS, true);
		if (stairsIntoTheWorks(w, x0, y0, W, H) > 0) {
			return true;
		}
		for (int x = px0; x < px0 + WORKS_W; x++) {
			for (int y = py0; y < py0 + WORKS_H; y++) {
				setBare(w, x, y, DEEP_Z, Tile.TileType.TYPE_WALL);
			}
		}
		return false;
	}

	/**
	 * Cuts every stairwell the station and the works can agree on, and returns
	 * how many landed.
	 *
	 * <p>Searched rather than written down, which is the change that let the
	 * works grow. The stair sites used to be two constants per station plan,
	 * chosen by eye against a plant floor that was centred under the shell and
	 * barely larger than it — so which room a stair came up in, and which deck
	 * plate it came down on, were facts about two drawings that had to be kept
	 * in step by hand. They were not, twice. A search asks the two floors
	 * instead: open station deck above, blank works plate below, four tiles of
	 * each in a row. Either drawing can then be redrawn freely, and the stairs
	 * move to wherever the buildings still agree.
	 */
	private static int stairsIntoTheWorks(World w, int x0, int y0, int W, int H) {
		return stairsBetween(w, x0, y0, W, H, CAVE_Z, DEEP_Z);
	}

	/** As above, between any two adjacent floors: {@code upper} is the floor
	 *  the stairs come down from, {@code lower} the one they land on. */
	private static int stairsBetween(World w, int x0, int y0, int W, int H, int upper, int lower) {
		java.util.ArrayList<int[]> cut = new java.util.ArrayList<int[]>();
		for (int hy = y0 + 1; hy < y0 + H - 1 && cut.size() < STAIRS_PER_STATION; hy++) {
			for (int hx = x0 + 1; hx < x0 + W - 4 && cut.size() < STAIRS_PER_STATION; hx++) {
				if (!stationStairFits(w, hx, hy, upper, lower)) {
					continue;
				}
				boolean crowded = false;
				for (int[] q : cut) {
					crowded |= Math.abs(q[0] - hx) < MIN_STATION_STAIR_GAP
							&& Math.abs(q[1] - hy) < MIN_STATION_STAIR_GAP;
				}
				if (crowded) {
					continue;
				}
				// Two lanes wherever the row below fits as well.
				sinkStairwell(w, hx, hy, stationStairFits(w, hx, hy + 1, upper, lower) ? 2 : 1,
						upper, lower);
				cut.add(new int[] { hx, hy });
			}
		}
		return cut.size();
	}

	/** How many ways down the station offers into the works. One was the old
	 *  number and it was the number for a room a tenth this size; the works is
	 *  three floors' worth of building reached through one small station, and a
	 *  single stair into it is a queue. */
	private static final int STAIRS_PER_STATION = 4;

	/** How far apart two of those stairs must stand, so four ways down are four
	 *  places rather than one wide one. */
	private static final int MIN_STATION_STAIR_GAP = 4;

	/** Whether {@link #sinkStairwell} can cut one lane from the halls at
	 *  (hx, hy) down into the works: four tiles of blank deck plate on each
	 *  floor for the landing, the climb and its housing to occupy. */
	private static boolean stationStairFits(World w, int hx, int hy, int upper, int lower) {
		for (int k = 0; k <= 3; k++) {
			// Blank deck plate on BOTH floors: the halls are a drawing too now,
			// and a stair cut through a drawn tile is a drawn tile that is not
			// there any more.
			if (w.getTile(hx + k, hy, upper).getType() != Tile.TileType.TYPE_PLATE) {
				return false;
			}
			if (w.getTile(hx + k, hy, lower).getType() != Tile.TileType.TYPE_PLATE) {
				return false;
			}
		}
		return true;
	}

	/** Keeps a coordinate inside {@code [lo, hi]}. */
	private static int clampTo(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static Tile.TileType plantTile(char ch) {
		switch (ch) {
		case 'P':
			return Tile.TileType.TYPE_PIPES;
		case 'C':
			return Tile.TileType.TYPE_COOLANT;
		case 'X':
			return Tile.TileType.TYPE_EXCHANGER;
		case 'w':
			return Tile.TileType.TYPE_CATWALK;
		case 's':
			return Tile.TileType.TYPE_WALL_STEEL;
		case 'T':
			return Tile.TileType.TYPE_TREADPLATE;
		case 'L':
			return Tile.TileType.TYPE_LIGHTGRATE;
		case 'B':
			return Tile.TileType.TYPE_CRYSTAL_BED;
		case 'S':
			return Tile.TileType.TYPE_SLUDGE;
		case 'R':
			return Tile.TileType.TYPE_COLLAPSE;
		case 'V':
			return Tile.TileType.TYPE_AIRVENT;
		case '#':
			return Tile.TileType.TYPE_WALL_CONCRETE;
		case 'p':
			return Tile.TileType.TYPE_PAVED;
		case 'r':
			return Tile.TileType.TYPE_RAIL;
		case 'd':
			return Tile.TileType.TYPE_DUCT;
		case 'D':
			return Tile.TileType.TYPE_DOCK;
		case 'E':
			return Tile.TileType.TYPE_SERVER;
		case 'H':
			return Tile.TileType.TYPE_SHAFT;
		case 'F':
			return Tile.TileType.TYPE_FUNGUS;
		case 'b':
			return Tile.TileType.TYPE_CRYSTAL_SPARSE;
		case 'K':
			return Tile.TileType.TYPE_CRYSTAL;
		case 't':
			return Tile.TileType.TYPE_TURBINE;
		case 'c':
			return Tile.TileType.TYPE_CONSOLE;
		case 'l':
			return Tile.TileType.TYPE_LIFT;
		case 'G':
			return Tile.TileType.TYPE_WINDOW;
		case 'k':
			return Tile.TileType.TYPE_DESK;
		case 'u':
			return Tile.TileType.TYPE_BUNK;
		case 'z':
			return Tile.TileType.TYPE_HAZARD;
		case 'v':
			return Tile.TileType.TYPE_CONVEYOR;
		case '~':
			return Tile.TileType.TYPE_WATER;
		case 'M':
			return Tile.TileType.TYPE_MUD;
		case 'Y':
			return Tile.TileType.TYPE_REEDS;
		case 'Q':
			return Tile.TileType.TYPE_COVER;
		case 'W':
			return Tile.TileType.TYPE_WRECK;
		case '.':
			return null; // the shell pass already laid deck plate
		default:
			throw new IllegalArgumentException("no such works tile: " + ch);
		}
	}

	/**
	 * The stairwell from the cave level down to the plant floor, {@code lanes}
	 * of it side by side running south from (hx, hy).
	 *
	 * <p>The link copies the surface-to-cave pattern exactly, one level down: a
	 * hole to fall through, a descending ramp beside it so the fall is not the
	 * only way, a landing below, and a climbing ramp back up. Written out
	 * rather than reusing {@code linkStation} because that carves cave and
	 * surface by name, and this joins the two floors underneath them — the
	 * geometry is the same, the levels are not.
	 *
	 * <p>Two lanes where the room above allows it, so a body coming down does
	 * not have to wait for one going up; one where it does not.
	 *
	 * <p>The hole beside each ramp is scenery for connectivity purposes: a pit
	 * is not walkable, so nothing routes through one and the flood never
	 * enters it.
	 */
	private static void sinkStairwell(World w, int hx, int hy, int lanes) {
		sinkStairwell(w, hx, hy, lanes, CAVE_Z, DEEP_Z);
	}

	/** As above, between any two adjacent floors. */
	private static void sinkStairwell(World w, int hx, int hy, int lanes, int upper, int lower) {
		// u = 1 is east in Tile's direction order, so the slope climbs east and
		// a body steps off its west foot to come down.
		final int u = 1;
		int ax = Tile.dirDx(u), ay = Tile.dirDy(u);
		for (int r = 0; r < lanes; r++) {
			int bx = hx, by = hy + r;
			int dx1 = bx + ax, dy1 = by + ay;            // the descending ramp
			int ux = bx + 2 * ax, uy = by + 2 * ay;      // the climbing ramp
			int lx = bx + 3 * ax, ly = by + 3 * ay;      // the upper landing

			setBare(w, bx, by, upper, Tile.TileType.TYPE_HOLE);
			setBare(w, dx1, dy1, upper, Tile.TileType.TYPE_RAMPDOWN);
			w.getTile(dx1, dy1, upper).setRampUphill(u);
			setBare(w, bx, by, lower, Tile.TileType.TYPE_PLATE); // landing below

			setBare(w, ux, uy, lower, Tile.TileType.TYPE_RAMPUP);
			w.getTile(ux, uy, lower).setRampUphill(u);
			// Under the upper landing stands the mass the climb rises into.
			// This has flipped twice, so the reasoning in full: the first
			// version walled it as "rock under", the second removed it as "a
			// lone concrete block holding up a deck plate that did not need
			// holding up" — and bare, the ramp read as attached to nothing, a
			// bright band ending in open floor (an up ramp climbs into
			// something; that is the difference between a stair and a plank).
			// The block is not scenery under the landing, it is the stair's
			// own housing: in the deep station it merges into the east shell
			// it abuts, and mid-room it reads as the masonry core the stair
			// wraps, which is what real stairwells have. The engine never
			// walks it — the climb exits one level up, on the landing.
			setBare(w, lx, ly, lower, Tile.TileType.TYPE_WALL_CONCRETE);
			setBare(w, lx, ly, upper, Tile.TileType.TYPE_PLATE); // landing above
		}
	}

	/**
	 * As above, with the mouth row given rather than assumed.
	 *
	 * <p>The assumed row is the shell's midpoint, which is the spine's middle
	 * in a plan whose spine is in the middle. It is not in every plan: put four
	 * bands under one shell and the midpoint lands on a partition wall, and the
	 * mouth is cut through the one row that was holding two rooms apart.
	 *
	 * <p>Returns whether the base survived. A site with no way out through the
	 * rock is un-carved back to wall here, and a caller that has built anything
	 * else — another floor, say — has to know to take it back down too.
	 */
	private static boolean finishBase(World w, int cols, int rows, int x0, int y0,
			int W, int H, int vx, int vy, int vh, int my) {
		setBare(w, x0, my, CAVE_Z, Tile.TileType.TYPE_PAVED);
		setBare(w, x0, my + 1, CAVE_Z, Tile.TileType.TYPE_PAVED);
		java.util.List<int[]> gallery = carveGallery(w, cols, rows, x0 - 1, my,
				new int[][] { { x0, my }, { x0, my + 1 } });
		if (gallery == null) {
			// No way out through the rock (a sealed map corner): un-carve, a
			// walled-off installation would fail the connectivity audit.
			for (int x = x0; x < x0 + W; x++) {
				for (int y = y0; y < y0 + H; y++) {
					setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_WALL);
				}
			}
			return false;
		}
		// The doors themselves: a two-tile blast door across the mouth and a
		// grate on the vault. Doors are ordinary non-living entities (they
		// ride the entity stream to the web client, which draws their sliding
		// leaves) -- with the vault duct as the constant small-body way in.
		net.hedinger.prototype.entities.Door blast = new net.hedinger.prototype.entities.Door(
				x0, my, CAVE_Z, 1, net.hedinger.prototype.entities.Door.BLAST, 2);
		net.hedinger.prototype.entities.Door grate = new net.hedinger.prototype.entities.Door(
				vx, vy + vh / 2, CAVE_Z, 1, net.hedinger.prototype.entities.Door.GRATE);
		w.addDoor(blast);
		w.addDoor(grate);

		// Switches on both sides of each door, wired to it, so a body is
		// never trapped on either side -- and deliberately NOT beside their
		// doors: the indicator trail from switch to door is the thing that
		// says what operates what, so give it distance to say it. The blast
		// door runs on weight-driven pressure plates -- anything crossing
		// them parts the leaves. The vault runs on intent-driven buttons: a
		// body must deliberately press (the A_INTERACT actuator), so only a mind
		// that has learned to use them opens the grate -- everything else
		// takes the crawl duct. Wiring also stops the doors' idle cycling.
		int[] outer = gallery.get(Math.min(3, gallery.size() - 1));
		wireSwitch(w, outer[0], outer[1], blast,
				net.hedinger.prototype.entities.Switch.PLATE); // down the gallery
		wireSwitch(w, x0 + 3, my, blast,
				net.hedinger.prototype.entities.Switch.PLATE); // out on the hall deck
		wireSwitch(w, vx - 3, vy + vh / 2, grate,
				net.hedinger.prototype.entities.Switch.BUTTON); // mid-hall, facing the vault
		wireSwitch(w, vx + 2, vy + vh - 2, grate,
				net.hedinger.prototype.entities.Switch.BUTTON); // the vault's far corner
		return true;
	}

	/**
	 * A ventilation shaft dropping from the surface into the base: the third
	 * way in -- one-way, by gravity, hazard-striped up top and open to the
	 * base's lights below. Scans the given window (base-interior tiles, in
	 * both levels' shared coordinates) for a surface tile that is plain open
	 * ground with all eight neighbours walkable -- removing such an interior
	 * tile cannot sever a surface path, and bodies can actually reach the
	 * lip -- and converts the first fit. Skips quietly when the surface
	 * overhead refuses (water, rock, or a link station's ramps).
	 */
	private static void dropShaft(World w, int lx0, int lx1, int ly0, int ly1) {
		for (int x = lx0; x <= lx1; x++) {
			for (int y = ly0; y <= ly1; y++) {
				Tile s = w.getTile(x, y, SURFACE_Z);
				if (!s.isWalkable() || s.getType() == Tile.TileType.TYPE_RAMPUP
						|| s.getType() == Tile.TileType.TYPE_RAMPDOWN) {
					continue;
				}
				boolean interior = true;
				for (int dx = -1; dx <= 1 && interior; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						if (!w.getTile(x + dx, y + dy, SURFACE_Z).isWalkable()) {
							interior = false;
							break;
						}
					}
				}
				if (interior) {
					w.setTile(x, y, SURFACE_Z, Tile.TileType.TYPE_SHAFT);
					w.getTile(x, y, SURFACE_Z).setFertility(0);
					return;
				}
			}
		}
	}

	/** One switch: the floor tile with the baked pedestal base, plus the
	 *  Switch entity that senses (and wires) its door. */
	private static void wireSwitch(World w, int x, int y,
			net.hedinger.prototype.entities.Door door, int mode) {
		setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_SWITCH);
		w.spawnEntity(new net.hedinger.prototype.entities.Switch(x, y, CAVE_Z, door, mode));
	}

	/**
	 * Tunnel a 2-wide paved entrance gallery from an installation mouth to
	 * the nearest already-walkable cave tile, breadth-first through solid
	 * rock only -- so the gallery meets the world exactly once, at its far
	 * end, and cannot nick a pool or cavern on the way. {@code startX,startY}
	 * is the first rock tile beyond the mouth; {@code mouths} are the mouth
	 * tiles themselves (marked visited, so the search cannot turn around and
	 * call its own doorway daylight), with the first one also setting the
	 * preferred initial digging direction. Deterministic: derived neighbour
	 * order, no RNG.
	 */
	private static java.util.List<int[]> carveGallery(World w, int cols, int rows,
			int startX, int startY, int[][] mouths) {
		if (startX < 1 || startY < 1 || startX >= cols - 1 || startY >= rows - 1) {
			return null;
		}
		int[][] prev = new int[cols][rows];
		for (int[] c : prev) {
			java.util.Arrays.fill(c, -1);
		}
		java.util.Deque<int[]> q = new java.util.ArrayDeque<int[]>();
		prev[startX][startY] = startX * rows + startY; // start marks itself
		// The mouth tiles are walkable but they're where we CAME from -- mark
		// them visited so the search can't turn around and call them daylight.
		for (int[] m : mouths) {
			prev[m[0]][m[1]] = startX * rows + startY;
		}
		q.add(new int[] { startX, startY });
		// Prefer digging straight out from the mouth before wandering.
		int[] away = { Integer.signum(startX - mouths[0][0]),
				Integer.signum(startY - mouths[0][1]) };
		int[][] dirs = { away, { away[1], away[0] }, { -away[1], -away[0] },
				{ -away[0], -away[1] } };
		while (!q.isEmpty()) {
			int[] p = q.poll();
			for (int[] d : dirs) {
				int nx = p[0] + d[0], ny = p[1] + d[1];
				if (nx < 1 || ny < 1 || nx >= cols - 1 || ny >= rows - 1
						|| prev[nx][ny] != -1) {
					continue;
				}
				Tile t = w.getTile(nx, ny, CAVE_Z);
				if (t.isWalkable()) {
					// Found daylight: pave the path back to the mouth, and
					// hand the caller the mouth-first path (real paved tiles,
					// where a switch can safely stand).
					java.util.List<int[]> path = new java.util.ArrayList<int[]>();
					int cx = p[0], cy = p[1];
					while (!(cx == startX && cy == startY)) {
						paveGalleryTile(w, cx, cy, cols, rows);
						path.add(new int[] { cx, cy });
						int code = prev[cx][cy];
						cx = code / rows;
						cy = code % rows;
					}
					paveGalleryTile(w, startX, startY, cols, rows);
					path.add(new int[] { startX, startY });
					java.util.Collections.reverse(path);
					return path;
				}
				if (t.getType() == Tile.TileType.TYPE_WALL) {
					prev[nx][ny] = p[0] * rows + p[1];
					q.add(new int[] { nx, ny });
				}
			}
		}
		return null;
	}

	/** One gallery step: a 2x2 brush of paved floor through rock only (built
	 *  walls and open ground stand), clamped inside the rim -- so the gallery
	 *  runs body-wide like the cave backbone's corridors. */
	private static void paveGalleryTile(World w, int x, int y, int cols, int rows) {
		for (int dx = 0; dx <= 1; dx++) {
			for (int dy = 0; dy <= 1; dy++) {
				int cx = x + dx, cy = y + dy;
				if (cx < 1 || cy < 1 || cx >= cols - 1 || cy >= rows - 1) {
					continue;
				}
				if (w.getTile(cx, cy, CAVE_Z).getType() == Tile.TileType.TYPE_WALL) {
					setBare(w, cx, cy, CAVE_Z, Tile.TileType.TYPE_PAVED);
				}
			}
		}
	}

	/** Sets a tile with zero fertility: nothing grows on built ground. */
	private static void setBare(World w, int x, int y, int z, Tile.TileType t) {
		w.setTile(x, y, z, t);
		w.getTile(x, y, z).setFertility(0);
	}

	/**
	 * The ravine: one long gorge torn through the surface, dozens of hole
	 * tiles in a wandering band two to three wide, with the cave level
	 * reading through the pit veil down its whole length. The stations'
	 * one-tile pits prove there is a world below; the ravine is where that
	 * fact becomes geography — a thing you walk along, plan around, and see
	 * the caves slide beneath as you pan.
	 *
	 * <p>Two causeways of untouched ground cross it at the third points, so
	 * the banks stay one walkable surface: a gorge with no crossing would cut
	 * the world in half AFTER {@link #connectLevels} certified it whole, and
	 * whatever lived on the smaller side would starve against an audit that
	 * no longer runs. The hole art rims each causeway's flanks by itself —
	 * a pit rims every side it meets ground.
	 *
	 * <p>Sited like the rivers: probe candidate spans from the seeded RNG and
	 * take the first that fits. A span fits only on natural open ground —
	 * margin from the map rim, nothing man-made or already sunken within
	 * three tiles (station ramps, aprons' pits, the drop shaft), and no water
	 * within one (a gorge swallowing a river's middle would leave its lower
	 * half flowing from nowhere). A world whose surface never offers such a
	 * span simply goes without; nothing downstream depends on one existing.
	 */
	private static void carveRavine(World w, int cols, int rows) {
		for (int attempt = 0; attempt < 60; attempt++) {
			boolean horizontal = (attempt & 1) == 0;
			int len = 26 + Utils.random(8);
			int along = horizontal ? cols : rows;
			int across = horizontal ? rows : cols;
			if (along < len + 12 || across < 20) {
				return; // a map too small for a gorge
			}
			int s0 = 6 + Utils.random(along - len - 12);
			int c0 = 8 + Utils.random(across - 16);

			// The band, precomputed so fitting and carving see the same tiles.
			// The drift is a clamped random walk — one tile of sideways wander
			// per step at most — so the gorge meanders instead of staggering,
			// and the band stays 4-connected at every width: a jumpier drift
			// left runs touching only at corners, which reads as separate pits
			// rather than one cut.
			int[][] band = new int[len][2]; // {edge offset, width} per step
			int off = 0;
			for (int t = 0; t < len; t++) {
				double turn = Utils.noise2(s0 * 3 + t * 2, c0 * 5 + 900, 0.16);
				off += turn > 0.6 ? 1 : turn < 0.4 ? -1 : 0;
				off = Math.max(-4, Math.min(4, off));
				int width = Utils.noise2(s0 + t, c0 + 1700, 0.15) > 0.62 ? 3 : 2;
				band[t][0] = c0 + off;
				band[t][1] = width;
			}
			boolean fits = true;
			for (int t = 0; t < len && fits; t++) {
				for (int d = 0; d < band[t][1] && fits; d++) {
					int x = horizontal ? s0 + t : band[t][0] + d;
					int y = horizontal ? band[t][0] + d : s0 + t;
					fits = ravineCarvable(w, cols, rows, x, y);
				}
			}
			if (!fits) {
				continue;
			}
			int c1 = len / 3, c2 = 2 * len / 3; // the causeways, two tiles each
			for (int t = 0; t < len; t++) {
				if (t == c1 || t == c1 + 1 || t == c2 || t == c2 + 1) {
					continue; // the untouched ground the banks cross on
				}
				for (int d = 0; d < band[t][1]; d++) {
					int x = horizontal ? s0 + t : band[t][0] + d;
					int y = horizontal ? band[t][0] + d : s0 + t;
					setBare(w, x, y, SURFACE_Z, Tile.TileType.TYPE_HOLE);
					// Where the cave under the cut is solid rock, the gorge
					// keeps going: a second hole below the first, so the eye
					// (and a falling body) carries on to the underdark. Where
					// the cave is open — a cavern, a pool — that floor is the
					// gorge's bed and the cut stops on it.
					if (w.getTile(x, y, CAVE_Z).getType() == Tile.TileType.TYPE_WALL) {
						setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_HOLE);
					}
				}
			}
			return;
		}
	}

	/**
	 * Natural caverns for the bottom level, from the same kind of coordinate
	 * noise as the caves above but a different fold of it — sparser, with
	 * pools, fungus beds and crystal, and no pits: the bottom of the world
	 * has nothing to open onto. Only virgin rock is carved, so the plant
	 * floor, its shell and its stairwell come through untouched. No polish
	 * pass: the underdark is allowed to be rougher than the caves.
	 */
	private static void carveDeepCaverns(World w, int cols, int rows) {
		for (int x = 1; x < cols - 1; x++) {
			for (int y = 1; y < rows - 1; y++) {
				if (w.getTile(x, y, DEEP_Z).getType() != Tile.TileType.TYPE_WALL) {
					continue; // someone built here; the rock stops being ours
				}
				double cave = Utils.noise2(x + 910, y + 2400, 0.11);
				if (cave <= 0.42 || cave >= 0.62) {
					continue;
				}
				double pool = Utils.noise2(x + 2100, y + 610, 0.13);
				Tile.TileType t;
				if (pool > 0.74) {
					t = Tile.TileType.TYPE_WATER;
				} else if (pool > 0.64) {
					t = Tile.TileType.TYPE_FUNGUS;
				} else if (pool < 0.15) {
					t = pool < 0.06 ? Tile.TileType.TYPE_CRYSTAL
							: pool < 0.11 ? Tile.TileType.TYPE_CRYSTAL_BED
							: Tile.TileType.TYPE_CRYSTAL_SPARSE;
				} else {
					t = Tile.TileType.TYPE_STONE;
				}
				w.setTile(x, y, DEEP_Z, t);
				w.getTile(x, y, DEEP_Z).setFertility(
						t == Tile.TileType.TYPE_FUNGUS ? 0.6 : 0);
			}
		}
	}

	/**
	 * Stairwells from the cave level down into the underdark: the same cut
	 * {@link #sinkStairwell} the plant floor uses, one per cavern. Without
	 * these every cavern is sealed again by {@link #resealFromSurface} —
	 * falling down the gorge reaches the underdark, but a way IN that is not
	 * a way BACK leaves whatever took it starving in the dark, so the flood
	 * only counts what the stairs tie in properly.
	 *
	 * <p>This used to throw two darts: up to two hundred attempts at a random
	 * pair of coordinates, keeping the first two that happened to fit, thirty
	 * tiles apart. Two was the right number for one cavern and the wrong
	 * number for what is actually down there. The carve leaves 3894 tiles, of
	 * which 742 are water — and the underdark never gets the shallows pass
	 * that softens the lakes above (it runs {@code CAVE_Z..SURFACE_Z}), so
	 * that water is hard edge. It cuts the level into THIRTY-ONE separate
	 * caverns, seventeen of them twenty tiles or more, and those seventeen
	 * hold 96% of the walkable rock. Two darts can reach two of seventeen.
	 * The reseal then deleted the rest, which cost 44% of the underdark on
	 * average across twelve seeds and 84% at the worst of them — carved,
	 * populated with pools and fungus and crystal, and then quietly turned
	 * back into rock for want of a way in.
	 *
	 * <p>So: enumerate the caverns and give each one a stair, instead of
	 * guessing at coordinates and hoping. A cavern under twenty tiles is left
	 * alone deliberately — a pocket that small is not worth cutting a
	 * stairwell into, and the reseal is welcome to it.
	 *
	 * <p>Placement inside a cavern is the fitting site nearest its centroid,
	 * which is a tie-break with a reason: scanning order alone would put every
	 * stair at its cavern's north-west edge, a bias you would see on the map
	 * as soon as you looked. Nothing here draws from the RNG — the caverns are
	 * where they are, and the choice is fully determined by them.
	 */
	private static void linkDeepCaverns(World w, int cols, int rows) {
		boolean[][] seen = new boolean[cols][rows];
		for (int sx = 0; sx < cols; sx++) {
			for (int sy = 0; sy < rows; sy++) {
				if (seen[sx][sy] || !w.getTile(sx, sy, DEEP_Z).isWalkable()) {
					continue;
				}
				java.util.ArrayList<int[]> cavern = new java.util.ArrayList<int[]>();
				boolean alreadyLinked = floodCavern(w, seen, cols, rows, sx, sy, cavern);
				if (alreadyLinked || cavern.size() < MIN_CAVERN_FOR_STAIRS) {
					continue;
				}
				stairCavern(w, cols, rows, cavern);
			}
		}
	}

	/**
	 * Cuts one cavern's share of stairwells: enough of them for its size, and
	 * spread across it rather than clustered.
	 *
	 * <p>The first stair goes at the fitting site nearest the centroid, which
	 * is where a single stair belongs and is exactly what a small cavern still
	 * gets. Every stair after that goes at the fitting site FARTHEST from the
	 * stairs already cut — farthest-point sampling, which spreads a handful of
	 * points over an arbitrary shape without needing to know the shape. Both
	 * choices are fully determined by the cavern; nothing here draws from the
	 * RNG.
	 *
	 * <p>Sites already cut exclude themselves on the next pass without any
	 * bookkeeping: {@link #stairwellFits} demands unbroken stone above, and a
	 * sunk stairwell leaves a hole and a ramp there. {@link #MIN_STAIR_GAP}
	 * does the rest, keeping two stairs from landing a few tiles apart in a
	 * long cavern where the farthest free site happens to be next door.
	 */
	private static void stairCavern(World w, int cols, int rows,
			java.util.List<int[]> cavern) {
		long cx = 0, cy = 0;
		for (int[] p : cavern) {
			cx += p[0];
			cy += p[1];
		}
		cx /= cavern.size();
		cy /= cavern.size();
		int want = Math.min(MAX_STAIRS_PER_CAVERN,
				Math.max(1, cavern.size() / TILES_PER_STAIRWELL));
		java.util.ArrayList<int[]> cut = new java.util.ArrayList<int[]>();
		while (cut.size() < want) {
			int bestX = -1, bestY = -1;
			long best = cut.isEmpty() ? Long.MAX_VALUE : Long.MIN_VALUE;
			for (int[] p : cavern) {
				if (!stairwellFits(w, cols, rows, p[0], p[1])) {
					continue;
				}
				if (cut.isEmpty()) {
					// The first: nearest the centroid, so a cavern with one
					// stair has it in the middle rather than at its north-west
					// edge, which is all scanning order would ever give.
					long d = (p[0] - cx) * (p[0] - cx) + (p[1] - cy) * (p[1] - cy);
					if (d < best) {
						best = d;
						bestX = p[0];
						bestY = p[1];
					}
					continue;
				}
				long near = Long.MAX_VALUE;
				for (int[] q : cut) {
					near = Math.min(near,
							(long) (p[0] - q[0]) * (p[0] - q[0]) + (long) (p[1] - q[1]) * (p[1] - q[1]));
				}
				if (near >= (long) MIN_STAIR_GAP * MIN_STAIR_GAP && near > best) {
					best = near;
					bestX = p[0];
					bestY = p[1];
				}
			}
			if (bestX < 0) {
				break; // the cavern has no room left that fits
			}
			// Two lanes wherever the tile south of the head also fits, so a
			// body coming down does not have to wait for one going up. This is
			// the common case in a roomy cavern and the reason the old
			// one-lane-everywhere stairs felt like bottlenecks.
			int lanes = stairwellFits(w, cols, rows, bestX, bestY + 1) ? 2 : 1;
			sinkStairwell(w, bestX, bestY, lanes);
			cut.add(new int[] { bestX, bestY });
		}
	}

	/** How much cavern buys another stairwell down from the caves.
	 *
	 *  <p>One per cavern was enough to stop the reseal deleting the underdark,
	 *  which is what that rule was for, but it is not enough to make the
	 *  underdark somewhere bodies move THROUGH: a single stair in a
	 *  three-hundred-tile cavern is a single door, and everything crossing
	 *  between the two floors queues at it. Sixty tiles is roughly an
	 *  eight-by-eight room per stair, near enough that a body in the dark is
	 *  usually within sight of a way up. */
	private static final int TILES_PER_STAIRWELL = 60;

	/** A ceiling on stairs per cavern, so one enormous connected underdark on
	 *  an unlucky seed does not turn into a colander. */
	private static final int MAX_STAIRS_PER_CAVERN = 12;

	/** The closest two stairwells in the same cavern may stand. Below this they
	 *  read as one wide stair rather than two ways up, which is not what the
	 *  count was spent on. */
	private static final int MIN_STAIR_GAP = 9;

	/** The smallest cavern worth cutting a stairwell into. Twenty tiles is
	 *  where the measured size distribution turns: caverns at or above it hold
	 *  96% of the underdark's walkable rock between them, and everything below
	 *  is a pocket of a dozen tiles that the reseal can have. */
	private static final int MIN_CAVERN_FOR_STAIRS = 20;

	/**
	 * Floods one cavern on the bottom level, collecting its tiles into
	 * {@code out}. Returns whether it already has a way in — the plant floor
	 * arrives here with its own two-lane stairwell already sunk, and cutting a
	 * second one into the same space would be a stair to nowhere new.
	 */
	private static boolean floodCavern(World w, boolean[][] seen, int cols, int rows,
			int sx, int sy, java.util.ArrayList<int[]> out) {
		boolean linked = false;
		java.util.Deque<int[]> q = new java.util.ArrayDeque<int[]>();
		q.add(new int[] { sx, sy });
		seen[sx][sy] = true;
		while (!q.isEmpty()) {
			int[] p = q.poll();
			out.add(p);
			Tile.TileType t = w.getTile(p[0], p[1], DEEP_Z).getType();
			if (t == Tile.TileType.TYPE_RAMPUP || t == Tile.TileType.TYPE_RAMPDOWN) {
				linked = true;
			}
			int[][] card = { { p[0] + 1, p[1] }, { p[0] - 1, p[1] },
					{ p[0], p[1] + 1 }, { p[0], p[1] - 1 } };
			for (int[] n : card) {
				if (n[0] >= 0 && n[1] >= 0 && n[0] < cols && n[1] < rows
						&& !seen[n[0]][n[1]]
						&& w.getTile(n[0], n[1], DEEP_Z).isWalkable()) {
					seen[n[0]][n[1]] = true;
					q.add(n);
				}
			}
		}
		return linked;
	}

	/**
	 * Whether a stairwell cut at {@code (bx, by)} lands in clean ground on both
	 * levels: plain cave floor above with a one-tile skirt, so the head does
	 * not open through a wall or into a lake, and carved cavern floor below for
	 * the landing and the climb.
	 */
	private static boolean stairwellFits(World w, int cols, int rows, int bx, int by) {
		// The world's own size, not COLS/ROWS: demoTerrain builds 72x44 and
		// 96x120 too, and the constants would wave the scan off the east edge
		// of the small one and refuse the bottom third of the tall one.
		if (bx < 4 || by < 4 || bx >= cols - 8 || by >= rows - 4) {
			return false;
		}
		for (int dx = -1; dx <= 4; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				if (w.getTile(bx + dx, by + dy, CAVE_Z).getType()
						!= Tile.TileType.TYPE_STONE) {
					return false;
				}
			}
		}
		for (int dx = 0; dx <= 2; dx++) {
			Tile.TileType d = w.getTile(bx + dx, by, DEEP_Z).getType();
			if (d != Tile.TileType.TYPE_STONE && d != Tile.TileType.TYPE_CRYSTAL_SPARSE
					&& d != Tile.TileType.TYPE_FUNGUS) {
				return false;
			}
		}
		return true;
	}

	/** One more {@link #sealUnreachable} flood, seeded from any mainland
	 *  surface tile — after the first seal the surface's walkable space IS the
	 *  mainland, so any walkable tile serves. */
	private static void resealFromSurface(World w, int cols, int rows) {
		for (int x = 2; x < cols - 2; x++) {
			for (int y = 2; y < rows - 2; y++) {
				if (w.getTile(x, y, SURFACE_Z).isWalkable()) {
					sealUnreachable(w, cols, rows, new int[] { x, y });
					return;
				}
			}
		}
	}

	/** Whether the surface at (x, y) may become ravine: natural open ground,
	 *  well inside the rim, nothing man-made or already sunken within three
	 *  tiles, no water within one. */
	private static boolean ravineCarvable(World w, int cols, int rows, int x, int y) {
		if (x < 4 || y < 4 || x >= cols - 4 || y >= rows - 4) {
			return false;
		}
		switch (w.getTile(x, y, SURFACE_Z).getType()) {
		case TYPE_FLOOR:
		case TYPE_STONE:
		case TYPE_ROCKY:
		case TYPE_SAND:
		case TYPE_MUD:
		case TYPE_COVER:
		case TYPE_REEDS:
		case TYPE_TALLGRASS:
		case TYPE_SCRUB:
			break;
		default:
			return false;
		}
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				Tile.TileType n = w.getTile(x + dx, y + dy, SURFACE_Z).getType();
				boolean near = Math.abs(dx) <= 1 && Math.abs(dy) <= 1;
				if (n == Tile.TileType.TYPE_RAMPUP || n == Tile.TileType.TYPE_RAMPDOWN
						|| n == Tile.TileType.TYPE_HOLE || n == Tile.TileType.TYPE_SHAFT
						|| n == Tile.TileType.TYPE_PAVED || n == Tile.TileType.TYPE_PLATE
						|| n == Tile.TileType.TYPE_DOCK || n == Tile.TileType.TYPE_RAIL) {
					return false;
				}
				if (near && (n == Tile.TileType.TYPE_WATER
						|| n == Tile.TileType.TYPE_SHALLOWS)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Wire the surface and cave into a single connected space, at any size.
	 *
	 * <ol>
	 *   <li>Label the surface's walkable regions. Tiny walled-off nooks (fewer
	 *       than {@code MIN_REGION} tiles) are sealed to rock — they'd be
	 *       unreachable dead space otherwise.</li>
	 *   <li>Place a spread of underground link stations (grid-sampled, scaled to
	 *       the map's area), guaranteeing at least one inside every surviving
	 *       surface region — so no region is stranded on the surface.</li>
	 *   <li>Carve a cave backbone: connect the station landings with corridors,
	 *       so the underground is one traversable tunnel network linking the
	 *       regions.</li>
	 *   <li>Seal any cave pocket the backbone doesn't reach, so there are no
	 *       isolated underground areas.</li>
	 * </ol>
	 *
	 * The result is a world where any walkable tile can be reached from any other
	 * by land or by tunnel — which {@link WorldAudit#connectivity} verifies.
	 */
	private static void connectLevels(World w, int cols, int rows) {
		// 1. Label surface walkable regions (4-neighbour flood fill).
		int[][] label = new int[cols][rows];
		for (int[] c : label) {
			java.util.Arrays.fill(c, -1);
		}
		java.util.List<java.util.List<int[]>> regions = new java.util.ArrayList<java.util.List<int[]>>();
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				if (label[x][y] != -1 || !w.getTile(x, y, SURFACE_Z).isWalkable()) {
					continue;
				}
				java.util.List<int[]> members = new java.util.ArrayList<int[]>();
				int id = regions.size();
				java.util.Deque<int[]> q = new java.util.ArrayDeque<int[]>();
				label[x][y] = id;
				q.add(new int[] { x, y });
				while (!q.isEmpty()) {
					int[] p = q.poll();
					members.add(p);
					int[][] card = { { p[0] + 1, p[1] }, { p[0] - 1, p[1] },
							{ p[0], p[1] + 1 }, { p[0], p[1] - 1 } };
					for (int[] n : card) {
						if (n[0] >= 0 && n[1] >= 0 && n[0] < cols && n[1] < rows
								&& label[n[0]][n[1]] == -1 && w.getTile(n[0], n[1], SURFACE_Z).isWalkable()) {
							label[n[0]][n[1]] = id;
							q.add(n);
						}
					}
				}
				regions.add(members);
			}
		}

		// The largest surface region is the world's mainland: the connectivity
		// repair floods from here, so the main surface is never what gets sealed.
		int[] mainSeed = null;
		int mainSize = -1;
		for (java.util.List<int[]> members : regions) {
			if (members.size() > mainSize) {
				mainSize = members.size();
				mainSeed = members.get(0);
			}
		}

		// 2 + 3. For each region: seal the tiny nooks, and give every substantial
		// region at least one link station (a grid spread scaled to area gives big
		// regions several). A region that can host no station at all — too cramped,
		// or hard against a border — is itself isolated, so it is sealed too rather
		// than left stranded with no way in or out.
		final int MIN_REGION = 12;
		int target = Math.max(1, (int) Math.round(cols * (double) rows / 1600.0));
		int step = Math.max(16, (int) Math.round(Math.sqrt(cols * (double) rows / target)));
		java.util.List<int[]> sites = new java.util.ArrayList<int[]>();
		for (java.util.List<int[]> members : regions) {
			boolean seal = members.size() < MIN_REGION;
			if (!seal) {
				int before = sites.size();
				for (int gx = step / 2; gx < cols; gx += step) {
					for (int gy = step / 2; gy < rows; gy += step) {
						int[] s = nearestFittingSite(members, gx, gy, cols, rows, sites);
						if (s != null) {
							sites.add(s);
						}
					}
				}
				if (sites.size() == before) {
					int[] s = bestFittingSite(members, cols, rows, sites); // grid missed it
					if (s != null) {
						sites.add(s);
					} else {
						seal = true; // no station fits anywhere: this region is unlinkable
					}
				}
			}
			if (seal) {
				for (int[] p : members) {
					w.setTile(p[0], p[1], SURFACE_Z, Tile.TileType.TYPE_WALL);
					w.getTile(p[0], p[1], SURFACE_Z).setFertility(0);
				}
			}
		}
		if (sites.isEmpty()) {
			sites.add(new int[] { Math.min(cols - 5, Math.max(3, cols / 2)),
					Math.min(rows - 4, Math.max(2, rows / 2)) });
		}

		// 4. Carve a station at each site, then link the cave landings. Stations
		//    take their direction in turn rather than by lot: a world with four
		//    of them has one facing each way by construction. Left to a hash the
		//    four are only likely, and "likely" over the handful of stations a
		//    map holds means a world that quietly has no north ramp in it — the
		//    freedom would be in the engine and invisible in the world, which is
		//    the state this whole change existed to leave. The starting cardinal
		//    is hashed from the first site so the cycle does not always open the
		//    same way.
		int dirOffset = sites.isEmpty() ? 0
				: (int) (net.hedinger.prototype.engine.GroundTextures.hash01(
						sites.get(0)[0], sites.get(0)[1], 61) * 4) & 3;
		for (int i = 0; i < sites.size(); i++) {
			int[] s = sites.get(i);
			linkStation(w, s[0], s[1], (i + dirOffset) & 3);
		}
		for (int i = 1; i < sites.size(); i++) {
			carveCaveCorridor(w, sites.get(i - 1), sites.get(i), cols, rows);
		}

		// 5. Connectivity repair: flood the whole walkable space from the mainland
		//    (crossing levels exactly as a body does), and seal anything it can't
		//    reach — on either level. Whatever survives is, by construction, a
		//    single connected region: mainland + tunnel network + every surface
		//    region and cavern the tunnels tie in. Isolated pockets become rock.
		sealUnreachable(w, cols, rows, mainSeed != null ? mainSeed : sites.get(0));
	}

	/** The station-fitting region tile nearest {@code (gx,gy)} that isn't already
	 *  crowded by an existing site; null if the region has none near this point. */
	private static int[] nearestFittingSite(java.util.List<int[]> members, int gx, int gy,
			int cols, int rows, java.util.List<int[]> taken) {
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		for (int[] p : members) {
			int sx = p[0], sy = p[1];
			// The station's footprint turns with the direction this site faces,
			// so the room it needs is asked for in its own frame rather than as
			// a fixed east-west box.
			if (!stationFits(sx, sy, cols, rows)) {
				continue;
			}
			double d = (sx - gx) * (double) (sx - gx) + (sy - gy) * (double) (sy - gy);
			if (d >= bestD || d > (double) (cols + rows)) {
				continue; // only snap a grid point to a site reasonably near it
			}
			boolean crowded = false;
			for (int[] t : taken) {
				if (Math.abs(t[0] - sx) < 6 && Math.abs(t[1] - sy) < 6) {
					crowded = true;
					break;
				}
			}
			if (!crowded) {
				bestD = d;
				best = new int[] { sx, sy };
			}
		}
		return best;
	}

	/** Any station-fitting tile in the region nearest its centroid (no distance
	 *  cap), avoiding crowding — the fallback when the grid snapped nothing. Null
	 *  only when the region can host no station at all. */
	private static int[] bestFittingSite(java.util.List<int[]> members, int cols, int rows,
			java.util.List<int[]> taken) {
		double cx = 0, cy = 0;
		for (int[] p : members) {
			cx += p[0];
			cy += p[1];
		}
		cx /= members.size();
		cy /= members.size();
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		for (int[] p : members) {
			int sx = p[0], sy = p[1];
			if (sx < 3 || sx > cols - 5 || sy < 2 || sy > rows - 4) {
				continue;
			}
			boolean crowded = false;
			for (int[] t : taken) {
				if (Math.abs(t[0] - sx) < 6 && Math.abs(t[1] - sy) < 6) {
					crowded = true;
					break;
				}
			}
			if (crowded) {
				continue;
			}
			double d = (sx - cx) * (sx - cx) + (sy - cy) * (sy - cy);
			if (d < bestD) {
				bestD = d;
				best = new int[] { sx, sy };
			}
		}
		return best;
	}

	/**
	 * Rivers as seeded droplet walks (WORLDGEN-RESEARCH.md #2): each starts on
	 * high, damp ground and follows the elevation field downhill — the SAME
	 * noise the biome classifier reads, so rivers run where the terrain says
	 * they should — carving a channel that widens as it runs, until it reaches
	 * standing water, a basin with no way down (where it ponds), or the rim.
	 *
	 * <p>Crossable by construction: every few steps the whole cross-section is
	 * laid as SHALLOWS (a ford), and the later shore pass fringes the rest of
	 * the channel — so rivers structure the map without severing the walkable
	 * region (the audit's connectivity gate holds).
	 */
	private static void carveRivers(World w, int cols, int rows) {
		int n = Math.max(2, (int) Math.round(cols * (double) rows / 3000.0));
		for (int r = 0; r < n; r++) {
			// Source: the highest of a handful of probes on damp, open ground --
			// ridge springs, not desert trickles (and never inside the rock line).
			int sx = -1, sy = -1;
			double best = 0.56;
			for (int t = 0; t < 30; t++) {
				int px = 4 + Utils.random(cols - 8), py = 4 + Utils.random(rows - 8);
				double e = Utils.noise2(px, py, 0.055);
				double m = Utils.noise2(px + 500, py + 300, 0.075);
				if (e > best && e < 0.80 && m > 0.35) {
					best = e;
					sx = px;
					sy = py;
				}
			}
			if (sx >= 0) {
				runRiver(w, cols, rows, sx, sy);
			}
		}
	}

	/** One river: walk downhill with a light meander, carving as it goes. */
	private static void runRiver(World w, int cols, int rows, int x, int y) {
		int prevDx = 0, prevDy = 0, sinceFord = 0;
		for (int steps = 0; steps < cols + rows; steps++) {
			if (x < 3 || y < 3 || x >= cols - 3 || y >= rows - 3) {
				return; // reached the rim
			}
			if (steps > 4 && w.getTile(x, y, SURFACE_Z).getType() == Tile.TileType.TYPE_WATER) {
				return; // joined a lake (or an earlier river)
			}
			// The channel: a brook near the spring, two tiles wide lower down.
			boolean ford = ++sinceFord >= 9;
			int width = steps < 12 ? 1 : 2;
			for (int dx = 0; dx < width; dx++) {
				for (int dy = 0; dy < width; dy++) {
					int cx = x + dx, cy = y + dy;
					if (cx < 2 || cy < 2 || cx >= cols - 2 || cy >= rows - 2) {
						continue;
					}
					w.setTile(cx, cy, SURFACE_Z, ford
							? Tile.TileType.TYPE_SHALLOWS : Tile.TileType.TYPE_WATER);
					w.getTile(cx, cy, SURFACE_Z).setFertility(0);
				}
			}
			if (ford) {
				sinceFord = 0;
			}
			// Descend: the lowest of the four neighbours, with a whisper of
			// deterministic jitter so parallel rivers don't run identical rails,
			// and never straight back uphill the way we came.
			int bx = 0, by = 0;
			double bestE = Double.MAX_VALUE;
			for (int k = 0; k < 4; k++) {
				int dx = k == 0 ? 1 : k == 1 ? -1 : 0;
				int dy = k == 2 ? 1 : k == 3 ? -1 : 0;
				if (dx == -prevDx && dy == -prevDy && (dx != 0 || dy != 0)) {
					continue; // no immediate backtrack
				}
				double e = Utils.noise2(x + dx, y + dy, 0.055)
						+ 0.06 * GroundTextures.hash01(x + dx, y + dy, 71);
				if (e < bestE) {
					bestE = e;
					bx = dx;
					by = dy;
				}
			}
			double here = Utils.noise2(x, y, 0.055);
			if (bestE >= here + 0.045) {
				// A true basin with no lake: the river ponds and ends here.
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						int cx = x + dx, cy = y + dy;
						if (cx >= 2 && cy >= 2 && cx < cols - 2 && cy < rows - 2) {
							w.setTile(cx, cy, SURFACE_Z, Tile.TileType.TYPE_WATER);
							w.getTile(cx, cy, SURFACE_Z).setFertility(0);
						}
					}
				}
				return;
			}
			prevDx = bx;
			prevDy = by;
			x += bx;
			y += by;
		}
	}

	/**
	 * Cellular-automata smoothing over the cave's rock/floor boundary
	 * (WORLDGEN-RESEARCH.md #3): a wall spur with almost no rock around it
	 * opens, a floor sliver walled nearly all round closes. Special ground
	 * (pools, fungus, crystals, vents, pits) is fixed truth — it neither
	 * flips nor counts as more than what it is (crystal counts as rock, all
	 * else as open), so the pass only rounds edges, never rewrites features.
	 */
	private static void smoothCave(World w, int cols, int rows) {
		for (int round = 0; round < 2; round++) {
			Tile.TileType[][] snap = new Tile.TileType[cols][rows];
			for (int x = 0; x < cols; x++) {
				for (int y = 0; y < rows; y++) {
					snap[x][y] = w.getTile(x, y, CAVE_Z).getType();
				}
			}
			for (int x = 1; x < cols - 1; x++) {
				for (int y = 1; y < rows - 1; y++) {
					Tile.TileType t = snap[x][y];
					if (t != Tile.TileType.TYPE_WALL && t != Tile.TileType.TYPE_STONE) {
						continue;
					}
					int rock = 0;
					for (int dx = -1; dx <= 1; dx++) {
						for (int dy = -1; dy <= 1; dy++) {
							if (dx == 0 && dy == 0) {
								continue;
							}
							Tile.TileType nt = snap[x + dx][y + dy];
							if (nt == Tile.TileType.TYPE_WALL
									|| nt == Tile.TileType.TYPE_CRYSTAL) {
								rock++;
							}
						}
					}
					if (t == Tile.TileType.TYPE_WALL && rock <= 3) {
						w.setTile(x, y, CAVE_Z, Tile.TileType.TYPE_STONE);
						w.getTile(x, y, CAVE_Z).setFertility(0);
					} else if (t == Tile.TileType.TYPE_STONE && rock >= 6) {
						w.setTile(x, y, CAVE_Z, Tile.TileType.TYPE_WALL);
						w.getTile(x, y, CAVE_Z).setFertility(0);
					}
				}
			}
		}
	}

	/**
	 * Carve a wandering 2-wide tunnel between two station landings: a directed
	 * random walker with heading persistence, so the backbone meanders like a
	 * worm-bore instead of an L of hallways (WORLDGEN-RESEARCH.md #4). The odd
	 * bulge opens a small chamber. The walk is biased toward the target and
	 * hard-capped; whatever distance remains when the cap hits is closed with
	 * the old straight carve, so the corridor contract — the two landings end
	 * up connected — is unconditional.
	 */
	private static void carveCaveCorridor(World w, int[] a, int[] b, int cols, int rows) {
		int x = a[0], y = a[1];
		int dx = Integer.signum(b[0] - a[0]), dy = 0;
		if (dx == 0) {
			dy = Integer.signum(b[1] - a[1]) == 0 ? 1 : Integer.signum(b[1] - a[1]);
		}
		int cap = 3 * (Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1])) + 40;
		for (int s = 0; s < cap && (x != b[0] || y != b[1]); s++) {
			carveCaveTile(w, x, y, cols, rows);
			if (Utils.random() < 0.05) {
				// A bulge: the tunnel balloons into a small chamber.
				carveCaveTile(w, x - 1, y - 1, cols, rows);
				carveCaveTile(w, x + 1, y + 1, cols, rows);
			}
			int tx = Integer.signum(b[0] - x), ty = Integer.signum(b[1] - y);
			boolean wayward = (dx != 0 && tx != 0 && dx != tx) || (dy != 0 && ty != 0 && dy != ty);
			if (wayward || Utils.random() < 0.35) {
				// Re-aim at the target: pick the axis weighted by remaining
				// distance, so long legs still meander but converge.
				int rx = Math.abs(b[0] - x), ry = Math.abs(b[1] - y);
				if (rx + ry > 0 && Utils.random(rx + ry) < rx) {
					dx = tx;
					dy = 0;
				} else {
					dx = 0;
					dy = ty;
				}
				if (dx == 0 && dy == 0) {
					dx = tx != 0 ? tx : 1; // degenerate: already on the target axis
				}
			}
			x = Math.max(1, Math.min(cols - 2, x + dx));
			y = Math.max(1, Math.min(rows - 2, y + dy));
		}
		// Close any remainder (the cap hit): the old deterministic straight carve.
		while (x != b[0]) {
			carveCaveTile(w, x, y, cols, rows);
			x += Integer.signum(b[0] - x);
		}
		while (y != b[1]) {
			carveCaveTile(w, x, y, cols, rows);
			y += Integer.signum(b[1] - y);
		}
		carveCaveTile(w, b[0], b[1], cols, rows);
	}

	/** A 2x2 brush of cave floor (clamped inside the sealed rim), so corridors are
	 *  wide enough for a body to pass. */
	private static void carveCaveTile(World w, int x, int y, int cols, int rows) {
		for (int dx = 0; dx <= 1; dx++) {
			for (int dy = 0; dy <= 1; dy++) {
				int cx = x + dx, cy = y + dy;
				if (cx < 1 || cy < 1 || cx >= cols - 1 || cy >= rows - 1) {
					continue;
				}
				Tile.TileType at = w.getTile(cx, cy, CAVE_Z).getType();
				if (at == Tile.TileType.TYPE_RAMPUP || at == Tile.TileType.TYPE_RAMPDOWN) {
					// A corridor that meets a ramp JOINS it — a ramp is walkable
					// floor already. Paving over it would leave the station's two
					// levels tied together by nothing but the pit beside it, and
					// silently: the corridor is stone either way, so the map looks
					// right and only the route up is gone. That stayed hidden
					// while every ramp pointed east and the corridors mostly ran
					// the other way; now that a station faces the next station,
					// the tunnel arrives along the slope every time.
					continue;
				}
				if (rampLanding(w, cx, cy)) {
					// Nor the rock at the head of the cut. An up ramp that runs
					// out into open floor climbs to nothing: the slope is a
					// staircase into a ceiling, and the surface tile it should
					// have arrived at is left resting on air. Measured before
					// this guard: three of eight stations had lost their landing
					// rock to a passing corridor.
					continue;
				}
				w.setTile(cx, cy, CAVE_Z, Tile.TileType.TYPE_STONE);
				w.getTile(cx, cy, CAVE_Z).setFertility(0);
			}
		}
	}

	/** Flood the whole walkable space from a surface seed, crossing levels the way
	 *  a land body does (walk onto a HOLE to fall; walk off a ramp's far edge to
	 *  change level), then seal every walkable tile the flood never reaches — on
	 *  either level. What remains is a single connected region. Mirrors
	 *  {@link WorldAudit#connectivity}'s traversal, so the audit agrees by
	 *  construction. */
	private static void sealUnreachable(World w, int cols, int rows, int[] surfaceSeed) {
		boolean[][][] seen = new boolean[w.getLevels()][cols][rows];
		java.util.Deque<int[]> q = new java.util.ArrayDeque<int[]>();
		if (w.getTile(surfaceSeed[0], surfaceSeed[1], SURFACE_Z).isWalkable()) {
			seen[SURFACE_Z][surfaceSeed[0]][surfaceSeed[1]] = true;
			q.add(new int[] { surfaceSeed[0], surfaceSeed[1], SURFACE_Z });
		}
		while (!q.isEmpty()) {
			int[] p = q.poll();
			int x = p[0], y = p[1], z = p[2];
			int[][] card = { { x + 1, y }, { x - 1, y }, { x, y + 1 }, { x, y - 1 } };
			for (int[] n : card) {
				floodVisit(w, seen, q, n[0], n[1], z, cols, rows); // land
				// Descend: a cardinal HOLE drops to the tile directly below it.
				if (n[0] >= 0 && n[1] >= 0 && n[0] < cols && n[1] < rows && z - 1 >= 0
						&& w.getTile(n[0], n[1], z).isDrop()) {
					floodVisit(w, seen, q, n[0], n[1], z - 1, cols, rows);
				}
			}
			// Ramps: stepping off the slope's own side lands a level up (RAMPUP)
			// or down (RAMPDOWN). Which side that is comes from the tile, so
			// this follows a ramp facing any cardinal — the same rule
			// Tile.isConnected enforces for a body actually walking it.
			Tile.TileType rt = w.getTile(x, y, z).getType();
			if (rt == Tile.TileType.TYPE_RAMPUP || rt == Tile.TileType.TYPE_RAMPDOWN) {
				int exit = w.getTile(x, y, z).rampExit();
				int nx = x + Tile.dirDx(exit), ny = y + Tile.dirDy(exit);
				int nz = rt == Tile.TileType.TYPE_RAMPUP ? z + 1 : z - 1;
				if (nz >= 0 && nz < w.getLevels() && nx >= 0 && ny >= 0 && nx < cols && ny < rows) {
					floodVisit(w, seen, q, nx, ny, nz, cols, rows);
				}
			}
		}
		for (int z = 0; z < w.getLevels(); z++) {
			for (int x = 0; x < cols; x++) {
				for (int y = 0; y < rows; y++) {
					if (w.getTile(x, y, z).isWalkable() && !seen[z][x][y]) {
						w.setTile(x, y, z, Tile.TileType.TYPE_WALL);
						w.getTile(x, y, z).setFertility(0);
					}
				}
			}
		}
	}

	private static void floodVisit(World w, boolean[][][] seen, java.util.Deque<int[]> q,
			int x, int y, int z, int cols, int rows) {
		if (x >= 0 && y >= 0 && x < cols && y < rows && !seen[z][x][y]
				&& w.getTile(x, y, z).isWalkable()) {
			seen[z][x][y] = true;
			q.add(new int[] { x, y, z });
		}
	}

	/**
	 * Whether a station fits inside the map at {@code (sx, sy)}. A station's
	 * footprint reaches 3 tiles along its slope and 2 across, so whichever way
	 * it is turned it stays inside a 7x7 box centred on the site — ask for that
	 * box and the site is safe to build in any of the four directions, which is
	 * what lets the direction be chosen freely afterwards.
	 */
	private static boolean stationFits(int sx, int sy, int cols, int rows) {
		return sx >= 3 && sy >= 3 && sx < cols - 3 && sy < rows - 3;
	}

	/**
	 * A working two-way link between the surface and the cave, built to match the
	 * engine's movement rules: a ramp is floor spanning two levels, so stepping
	 * off a RAMPUP's high side lands a level up and off a RAMPDOWN's foot a level
	 * down. A HOLE is not a route at all, just a pit that drops whatever stands on
	 * it — kept here because a second way down costs nothing.
	 *
	 * <p>The whole station is laid out in the RAMP'S OWN FRAME: {@code t} runs
	 * up the slope and {@code r} across it, and both are mapped to the map's
	 * axes through the direction this site faces. So the same station is built
	 * whichever way it points, and there is one description of it rather than
	 * four. The run is two tiles wide ({@code r} of 0 and 1) so bodies pass each
	 * other on it instead of queueing on a one-tile thread. Per row:
	 * <ul>
	 *   <li><b>Down</b> — on the surface at {@code t=1}, a RAMPDOWN whose foot
	 *       faces back down-slope onto the cave floor carved at {@code t=0},
	 *       with a HOLE on that same tile that falls to the same landing.</li>
	 *   <li><b>Up</b> — in the cave at {@code t=2}, a RAMPUP whose top faces up
	 *       the slope onto the surface floor carved at {@code t=3}. The WALL
	 *       below that landing is only the rock the surface tile rests on.</li>
	 * </ul>
	 */
	private static void linkStation(World w, int sx, int sy, int u) {
		int ax = Tile.dirDx(u), ay = Tile.dirDy(u);          // up the slope
		int cx = Tile.dirDx((u + 1) & 3), cy = Tile.dirDy((u + 1) & 3); // across it

		// A small open patch on both levels so creatures can reach the link,
		// carved in the station's frame so it covers the run whichever way the
		// slope points.
		for (int t = -2; t <= 2; t++) {
			for (int r = -1; r <= 2; r++) {
				int x = sx + t * ax + r * cx, y = sy + t * ay + r * cy;
				carveTile(w, x, y, SURFACE_Z);
				carveTile(w, x, y, CAVE_Z);
			}
		}

		for (int r = 0; r <= 1; r++) {
			int bx = sx + r * cx, by = sy + r * cy;
			int x0 = bx, y0 = by;                       // t = 0: the cave landing
			int x1 = bx + ax, y1 = by + ay;             // t = 1: the descending ramp
			int x2 = bx + 2 * ax, y2 = by + 2 * ay;     // t = 2: the climbing ramp
			int x3 = bx + 3 * ax, y3 = by + 3 * ay;     // t = 3: the surface landing

			// Down: a pit, and beside it a ramp whose foot faces back down-slope;
			// the cave floor at t=0 is what both routes land on.
			w.setTile(x0, y0, SURFACE_Z, Tile.TileType.TYPE_HOLE);
			w.setTile(x1, y1, SURFACE_Z, Tile.TileType.TYPE_RAMPDOWN);
			w.getTile(x1, y1, SURFACE_Z).setRampUphill(u);
			w.setTile(x0, y0, CAVE_Z, Tile.TileType.TYPE_STONE); // landing
			w.getTile(x0, y0, CAVE_Z).setFertility(0);

			// Up: a ramp in the cave climbing onto the stone landing carved above.
			w.setTile(x2, y2, CAVE_Z, Tile.TileType.TYPE_RAMPUP);
			w.getTile(x2, y2, CAVE_Z).setRampUphill(u);
			w.setTile(x3, y3, CAVE_Z, Tile.TileType.TYPE_WALL); // rock under the landing
			w.setTile(x3, y3, SURFACE_Z, Tile.TileType.TYPE_STONE); // landing above
			w.getTile(x3, y3, SURFACE_Z).setFertility(0);
		}
	}

	/** Whether the cave tile at {@code (x, y)} is the rock an up ramp climbs
	 *  into — the head of the cut, which holds up the surface tile the climb
	 *  arrives at and which the wall art merges the ramp's top into. */
	private static boolean rampLanding(World w, int x, int y) {
		for (int d = 0; d < 4; d++) {
			int rx = x - Tile.dirDx(d), ry = y - Tile.dirDy(d);
			if (rx < 0 || ry < 0 || rx >= w.getColums() || ry >= w.getRows()) {
				continue;
			}
			Tile n = w.getTile(rx, ry, CAVE_Z);
			if (n.getType() == Tile.TileType.TYPE_RAMPUP && n.getRampUphill() == d) {
				return true;
			}
		}
		return false;
	}

	/** One tile of station apron — bare stone, on both levels.
	 *
	 *  <p>The surface apron used to be meadow, which put grass growing to the
	 *  very lip of a pit and up to the edge of a cut stone ramp. A station is
	 *  where the bedrock opens: the rock the ramp is cut into should reach the
	 *  surface around it, and the meadow should stop where the rock starts.
	 *  Stone outranks earth in the autotiling, so the apron laps out into the
	 *  grass with the same scalloped edge every other terrain boundary gets. */
	private static void carveTile(World w, int x, int y, int z) {
		if (x < 1 || y < 1 || x >= w.getColums() - 1 || y >= w.getRows() - 1) {
			return;
		}
		w.setTile(x, y, z, Tile.TileType.TYPE_STONE);
		w.getTile(x, y, z).setFertility(0);
	}

	/** A random open cave tile, for seeding the underground cohort onto stone or
	 *  fungus — never into rock, and never onto a pit or shaft (a drop on the
	 *  lowest level is bottomless, and a founder should not spawn into the void). */
	/**
	 * A founder's place in its cluster: the first member under {@code key} picks
	 * open ground and becomes the anchor, every later member lands within
	 * {@link #SEED_CLUSTER_RADIUS} of it -- or, if nothing walkable is that close,
	 * wherever it can. Keyed by species template for herds and packs, by a single
	 * key for a cohort that should arrive together.
	 */
	private static double[] clusterSpot(World w, java.util.Map<Integer, double[]> anchors,
		int key, int z, boolean avoidDrops) {
		return clusterSpot(w, anchors, key, z, avoidDrops, false);
	}

	/**
	 * As above, with the anchor on PASTURE when asked: a grazing herd's first
	 * member picks rich ground, and the herd arrives around it.
	 *
	 * <p>Since seeding landed in clusters this has been the difference between
	 * a world that holds and one that does not. With one blend of terrain
	 * everywhere, any anchor had meadow within reach; with regions, an anchor
	 * drawn from all walkable ground lands in the steppe or the badlands as
	 * readily as anywhere, and a herd does not migrate. Measured on seed 42: the
	 * herd anchored in the steppe, its mean energy fell for twelve thousand
	 * ticks, and then forty-six of it were eaten inside two thousand — while
	 * the thirty that had wandered into the wetland grew to forty-five. Founders
	 * are placed by the world, and the world knows where the grass is.
	 */
	private static double[] clusterSpot(World w, java.util.Map<Integer, double[]> anchors,
		int key, int z, boolean avoidDrops, boolean onPasture) {
		double[] anchor = anchors.get(key);
		if (anchor != null) {
			double[] near = spotNear(w, anchor[0], anchor[1], z, avoidDrops);
			if (near != null) {
				return near;
			}
		}
		double[] p = avoidDrops ? caveSpot(w) : onPasture ? pastureSpot(w) : openSpot(w);
		anchors.putIfAbsent(key, p);
		return p;
	}

	/** Grazing worth arriving on: the richest of a scatter of probes across
	 *  the surface, so a herd's anchor is the best pasture of a handful and not
	 *  merely a tile with grass on it. Falls back to any open ground on a map
	 *  with none. */
	private static double[] pastureSpot(World w) {
		double[] best = null;
		double bestFert = 0;
		for (int tries = 0; tries < 60; tries++) {
			double x = 2 + Utils.random() * (w.getColums() - 4);
			double y = 2 + Utils.random() * (w.getRows() - 4);
			Tile t = w.getTile(x, y, SURFACE_Z);
			if (t.getType() == Tile.TileType.TYPE_FLOOR && t.getFertility() > bestFert) {
				bestFert = t.getFertility();
				best = new double[] { x, y };
			}
		}
		return best != null && bestFert >= PASTURE_FERTILITY ? best : openSpot(w);
	}

	/** What counts as pasture for a founding herd: well into the green half of
	 *  the meadow's fertility band. */
	private static final double PASTURE_FERTILITY = 0.5;

	private static double[] caveSpot(World w) {
		for (int tries = 0; tries < 60; tries++) {
			double x = 2 + Utils.random() * (w.getColums() - 4);
			double y = 2 + Utils.random() * (w.getRows() - 4);
			Tile t = w.getTile(x, y, CAVE_Z);
			if (t.isWalkable() && !t.isDrop()) {
				return new double[] { x, y };
			}
		}
		return new double[] { w.getColums() / 2.0, w.getRows() / 2.0 };
	}

	/** A random open (walkable) surface tile, for scattering founders and items
	 *  onto meadow rather than into water or rock. */
	/**
	 * How far from its anchor a seeded body lands. Seeding happens in clusters,
	 * not a scatter: founders of one species arrive as a herd, a pack or a brood,
	 * and a steward reseed lands beside the oldest living body of its own clade --
	 * a reseed is a birth the steward performs, so it lands where a birth would.
	 *
	 * <p>This is what gives a sexual lineage anyone to breed with. Most reseeds are
	 * mutated children of the clade's champion, and a child of a small mutation
	 * sits within a few hundredths of its parent on every marker -- a compatible
	 * mate by any threshold a lineage is likely to carry. Scattered across a
	 * 144x88 world, that pair never met; measured on the live world, no hunter
	 * reached a second generation in 2.3 million ticks.
	 */
	@net.hedinger.prototype.engine.Unit("tiles")
	public static final double SEED_CLUSTER_RADIUS = 4.0;

	/**
	 * A walkable spot within {@link #SEED_CLUSTER_RADIUS} of {@code (ax, ay)} on
	 * level {@code z}, or null when a fair number of tries finds none -- the caller
	 * then falls back to scattering. Underground, never onto a drop: pits on the
	 * lowest level are bottomless and a body seeded into one is wasted.
	 */
	public static double[] spotNear(World w, double ax, double ay, int z, boolean avoidDrops) {
		for (int tries = 0; tries < 40; tries++) {
			double x = ax + (Utils.random() * 2 - 1) * SEED_CLUSTER_RADIUS;
			double y = ay + (Utils.random() * 2 - 1) * SEED_CLUSTER_RADIUS;
			if (x < 2 || y < 2 || x >= w.getColums() - 2 || y >= w.getRows() - 2) {
				continue;
			}
			Tile t = w.getTile(x, y, z);
			if (t.isWalkable() && !(avoidDrops && t.isDrop())) {
				return new double[] { x, y };
			}
		}
		return null;
	}

	private static double[] openSpot(World w) {
		for (int tries = 0; tries < 60; tries++) {
			double x = 2 + Utils.random() * (w.getColums() - 4);
			double y = 2 + Utils.random() * (w.getRows() - 4);
			if (w.getTile(x, y, SURFACE_Z).isWalkable()) {
				return new double[] { x, y };
			}
		}
		return new double[] { w.getColums() / 2.0, w.getRows() / 2.0 };
	}

	/**
	 * A living, evolving arena, fully determined by the seed: patchy fertile
	 * grassland grazed by breeding herbivores, hunted by predators, with a
	 * scatter of items — and a {@link WorldSteward} that keeps both populations
	 * inside sane bounds so the public world never dies out or swarms. The
	 * herbivores graze/breed/starve and the predators hunt/breed/starve, so
	 * births, kills, deaths and evolution all play out on their own; the steward
	 * only catches the extremes. The returned world has ticked once, so the
	 * snapshot stream starts fully populated.
	 */
	public static World demo(long seed) {
		return demo(seed, COLS, ROWS);
	}

	/**
	 * The demo world at an arbitrary size. Populations and population bounds scale
	 * with the map's area, so a bigger world keeps roughly the same density (and
	 * the same feel) rather than becoming an empty plain — which also keeps the
	 * performance measurement honest.
	 */
	public static World demo(long seed, int cols, int rows) {
		World w = demoTerrain(seed, cols, rows);
		double scale = cols * (double) rows / DENSITY_AREA;
		net.hedinger.prototype.entities.Genome[] herb = herbivoreSpecies();
		net.hedinger.prototype.entities.Genome[] pred = predSpecies();

		// Founder herbivores: metabolic grazers that breed and evolve, scattered
		// onto open meadow (never into water or rock).
		//
		// No nesters. A quarter of the founders used to home on their pheromone
		// peak to breed and leave a Nest fixture there, and measured over 40k ticks
		// that lineage drove the plain breeders extinct by tick 20k -- a decisive
		// outcome for a mechanic whose fixture does nothing at all (no shelter, no
		// safety, no bonus; the minded cohort can neither build one nor perceive
		// one). A strategy that wins that hard while meaning that little is shaping
		// the ecosystem for no reason anyone chose, so it is out of the seeded world
		// until it earns its place. The behaviour and the fixture both still exist
		// and stay covered by the scenario suite.
		// The herd is minded. It keeps the species pool's BODY — the four warm
		// barcodes, the sizes, the grazer's slow speed and neutral metabolism, all
		// of which are what makes a herd read as a herd — and takes its behaviour
		// from a brain instead of thinkBreeder. Nothing in the seeded world is
		// scripted any more; the scripted behaviours stay in TestNPC for the
		// scenario suite, which is the one place a fixed, known-good animal is
		// worth more than an evolving one.
		//
		// The genome is copied per body because the pool is a shared array and
		// mindedForager, unlike the other three minded builders, keeps the instance
		// it is handed: writing a brain into the pool entry would hand the same
		// mind to every founder drawn from it.
		//
		// No withHerding(): vigilance is read only by thinkBreeder, so on a minded
		// body it is a flag nothing consults. Whether to flee a hunter or close up
		// with kin is now the brain's to work out, which is the point.
		// Founders arrive as herds, packs and broods: the first of a species picks the
		// ground, the rest of that species land beside it (see SEED_CLUSTER_RADIUS).
		java.util.Map<Integer, double[]> herds = new java.util.HashMap<>();
		for (int i = 0; i < sc(26, scale); i++) {
			double[] p = clusterSpot(w, herds, i % herb.length, SURFACE_Z, false, true);
			net.hedinger.prototype.entities.Genome g = herb[i % herb.length].copy();
			g.brain = (i % 3 == 2) ? hitchhikerBrain() : starterBrain();
			w.spawnEntity(TestNPC.mindedForager(p[0], p[1], SURFACE_Z, g));
		}
		// Founder hunters (few: predation should track the prey, not cap it), on
		// the predator species' big fast bodies. Always the forager seed and never
		// the hitch-hiker: the hitch-hiker closes on what is BIGGER than it, which
		// for a hunter is the wrong end of every encounter, while the forager seed
		// is a working hunt once the forage channel means prey.
		java.util.Map<Integer, double[]> packs = new java.util.HashMap<>();
		for (int i = 0; i < sc(4, scale); i++) {
			double[] p = clusterSpot(w, packs, i % pred.length, SURFACE_Z, false);
			net.hedinger.prototype.entities.Genome g = pred[i % pred.length].copy();
			g.brain = starterBrain();
			w.spawnEntity(TestNPC.mindedPredator(p[0], p[1], SURFACE_Z, g));
		}
		// A small parallel cohort of minded creatures (fully-random brains) that
		// competes inside the same world as the scripted species — the A/B seam
		// where evolvable behaviour proves itself (or doesn't) against the hardcoded
		// baseline. The steward keeps this cohort topped up as it dies off.
		int nMinded = Math.max(5, sc(5, scale));
		net.hedinger.prototype.entities.Genome[] minded = mindedSpecies(nMinded);
		java.util.Map<Integer, double[]> brood = new java.util.HashMap<>();
		for (int i = 0; i < nMinded; i++) {
			double[] p = clusterSpot(w, brood, 0, SURFACE_Z, false, true);
			w.spawnEntity(TestNPC.mindedForager(p[0], p[1], SURFACE_Z, minded[i]));
		}
		// The underground gets its own minded seed group — separate founder
		// lineages, so cave life starts as its own experiment. Fungus beds feed
		// them, and the cave's fixtures (the buried base's plates and buttons)
		// are theirs to discover.
		int nCaveMinded = Math.max(3, sc(3, scale));
		net.hedinger.prototype.entities.Genome[] caveMinded = mindedSpecies(nCaveMinded);
		java.util.Map<Integer, double[]> caveBrood = new java.util.HashMap<>();
		for (int i = 0; i < nCaveMinded; i++) {
			double[] p = clusterSpot(w, caveBrood, 0, CAVE_Z, true);
			w.spawnEntity(TestNPC.mindedForager(p[0], p[1], CAVE_Z, caveMinded[i]));
		}

		// Founder scavengers: minded, like the cohort above and running the same
		// brains, but eating carrion instead of grass. A third trophic level rather
		// than a third species -- nothing dies for them, they live on what the other
		// two leave behind, and by eating it they are the world's decomposition.
		// Their supply is mortality itself, which is finite and self-consuming, so
		// the cohort is small by nature: a handful is a niche and a crowd is a famine.
		// Seeded as TWO lineages of several individuals each, not one founder per
		// species. Three founders of three species is not a population: with diet a
		// reproductive barrier, a sexual scavenger among them has no compatible
		// partner anywhere in the world and its line ends with it, whatever it eats.
		// Kin it can actually breed with is the difference between a cohort and three
		// animals that happen to share a diet.
		int nScavLines = 2;
		int nScavPerLine = Math.max(3, sc(3, scale));
		net.hedinger.prototype.entities.Genome[] scavengers = mindedSpecies(nScavLines);
		for (int line = 0; line < nScavLines; line++) {
			for (int i = 0; i < nScavPerLine; i++) {
				double[] p = openSpot(w);
				// Siblings, not clones: enough drift for selection to have something to
				// work on, well inside the genome's own similarity threshold.
				net.hedinger.prototype.entities.Genome g =
						net.hedinger.prototype.entities.Genome.child(scavengers[line], 0.03);
				w.spawnEntity(TestNPC.mindedScavenger(p[0], p[1], SURFACE_Z, g));
			}
		}

		// Founder parasites: the fourth trophic level, and the strangest living —
		// they eat the herd without hunting it, a bite at a time from on top of
		// it. Small by nature (a parasite must be smaller than its host to latch,
		// and small is what clings too tight to buck off), ignored by predators,
		// unable to graze: their supply is the standing crop of big warm bodies,
		// which is abundant but fights back one buck at a time. Seeded as two
		// lineages of siblings for the same reason the scavengers are — diet is a
		// mate barrier, and a lone founder of a sexual line dies single.
		int nParaLines = 2;
		int nParaPerLine = Math.max(3, sc(3, scale));
		net.hedinger.prototype.entities.Genome[] parasites = mindedSpecies(nParaLines);
		for (int line = 0; line < nParaLines; line++) {
			for (int i = 0; i < nParaPerLine; i++) {
				double[] p = openSpot(w);
				net.hedinger.prototype.entities.Genome g =
						net.hedinger.prototype.entities.Genome.child(parasites[line], 0.03);
				w.spawnEntity(TestNPC.mindedParasite(p[0], p[1], SURFACE_Z, g));
			}
		}

		// Founder minded hunters, so the predator clade has an evolving line of its
		// own rather than only the scripted loop. It was the one clade left without
		// one: herbivores, scavengers and parasites all had minded cohorts and
		// predators had thinkPredator and nothing else, which meant the role could
		// not be learned, only executed. Seeded as two lineages of siblings for the
		// reason the other two are — a clade is a hard mate barrier, so a lone
		// founder of a sexual line dies single.
		int nHuntLines = 2;
		int nHuntPerLine = Math.max(2, sc(2, scale));
		net.hedinger.prototype.entities.Genome[] hunters = mindedSpecies(nHuntLines);
		for (int line = 0; line < nHuntLines; line++) {
			for (int i = 0; i < nHuntPerLine; i++) {
				double[] p = openSpot(w);
				net.hedinger.prototype.entities.Genome g =
						net.hedinger.prototype.entities.Genome.child(hunters[line], 0.03);
				w.spawnEntity(TestNPC.mindedPredator(p[0], p[1], SURFACE_Z, g));
			}
		}

		// A sprinkle of the inanimate world: food, crates, hazards.
		for (int i = 0; i < sc(10, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.food(p[0], p[1], SURFACE_Z));
		}
		for (int i = 0; i < sc(5, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.crate(p[0], p[1], SURFACE_Z));
		}
		for (int i = 0; i < sc(3, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.hazard(p[0], p[1], SURFACE_Z));
		}

		// The warden, with fixed {min,max} bounds that scale with the map's area.
		// These are guardrails, not the population control: grass, predation and
		// starvation decide the actual headcount, and the ceilings sit well above
		// where those forces settle so the steward rarely has to fire at all.
		//
		// That last sentence is not true of any of them, at any value tried.
		// Sampled every 2000 ticks of the settled world, every cohort sat ON its
		// backstop -- herbivores 449 against a ceiling of 410, predators 12-13
		// against 12, scavengers 63-66 against 60, parasites 31-33 against 30.
		// Raising three of them to 100 moved those three to 108-110 and left the
		// herd exactly where it was. So these numbers are the population control
		// rather than a guardrail around one, and a bigger number is a bigger
		// world rather than a freer one. Worth knowing before the next person
		// reads the paragraph above and believes it. The
		// minded cap in particular is generous — predators hunt minded creatures
		// like any other body their size or smaller, so that cohort is now held in
		// check ecologically rather than by deletion.
		WorldSteward steward = new WorldSteward(w, SURFACE_Z, CAVE_Z,
				// Every herbivore, scripted or minded, under one bound. The ceiling is
				// the sum of the two it replaces (160 plain + 250 minded), measured at
				// the settled world, so the merge changes WHO is counted rather than
				// how many the world carries.
				new int[] { sc(CLADE_FLOOR, scale), sc(410, scale) }, // prey  [floor, ceiling]
				// Predators. Ceiling 12 -> 100. At 12 the cohort sat AT the line,
				// sampled 12 or 13 every time it was looked at, which meant the
				// warden was setting the predator population and grass, prey and
				// starvation were not.
				//
				// It still is. Run sixty thousand ticks and predators climb
				// 11 -> 26 -> 52 and then sit on 109 from tick 20000 on, which is
				// this ceiling's backstop and not an equilibrium. The same is true
				// of the two below. Nothing in this world limits any of these
				// cohorts below a hundred, so raising the number raises the
				// population one for one and the ceiling remains the control. That
				// is a fact about the ecology, and the honest place to record it is
				// beside the constant that is standing in for it.
				new int[] { Math.max(2, sc(CLADE_FLOOR, scale)), sc(100, scale) },
				// Minded ceiling raised 80 -> 250. At 80 the cohort sat AT its cap for
				// long stretches, which meant the warden -- not grass, not predators --
				// was setting the population, and a ceiling that binds is a governor
				// rather than the backstop this is meant to be. 250 is well clear of
				// anything the ecosystem reaches unaided, so what the headcount settles
				// at is now a fact about the world instead of about this constant.
				// The real limit is the deploy's heap and the 10 Hz broadcast, neither
				// of which is measured yet; /api/health now reports tick cost so it can
				// be watched. (Sim CPU is not the binding constraint: the world audit
				// measures thousands of ticks/s against a 33 t/s requirement.)
				Math.max(6, sc(6, scale)), // the minded LINEAGE floor -- not a population bound
				// Scavengers. A floor so the niche is never simply empty, and a
				// ceiling well above it -- the binding control is meant to be the
				// carrion supply, which is finite and self-limiting in a way grass is
				// not: eating a body destroys it, so a scavenger bloom consumes its
				// own larder and starves back without the steward touching it.
				//
				// The floor sits above the seeded cohort, not under it. At three, a
				// cohort held AT the floor was three animals scattered across the
				// map, which with diet as a mate barrier is not a breeding
				// population -- the warden was keeping the niche occupied and extinct
				// at the same time.
				new int[] { Math.max(6, sc(CLADE_FLOOR, scale)), Math.max(100, sc(100, scale)) },
				// Parasites. A floor so the niche survives its own learning curve
				// (a mindless parasite that never latches starves), and a ceiling
				// raised 30 -> 100 for the same reason as the other two.
				//
				// This one gives up something real, and it is worth writing down
				// rather than discovering later. The old number was argued FOR
				// rather than defaulted to: their supply is the standing herd, and
				// a bloom bleeding every big body at once is a plague rather than
				// an ecosystem. At 30 the cohort sat on 31-33 every time it was
				// sampled, so that argument was being enforced by deletion --
				// which is the governor this whole set of bounds is not supposed
				// to be. If a plague is what the herd actually produces, the herd
				// should be what stops it; if nothing stops it, that is a finding
				// about the ecology rather than a reason to hide it behind a cap.
				new int[] { Math.max(6, sc(CLADE_FLOOR, scale)), Math.max(100, sc(100, scale)) });
		w.spawnEntity(steward);

		// The warden's one machine, berthed in the buried base. It takes its
		// orders from the steward and does the killing the steward used to do
		// by deletion -- so a world with no base carved into it (a map too
		// small for either plan) simply has no drone, and the steward's own
		// backstop keeps the ceilings on its own, exactly as before.
		// One drone per pad, all reading the same standing order. They do not
		// divide the cohorts between them and do not need to: the steward keeps
		// the only scoreboard, recounts every tick and drops the order the
		// moment the target is met, so a rank of four converges on it faster
		// and stops together. The drone was always written not to count.
		for (int[] dock : findDocks(w)) {
			w.spawnEntity(new StewardDrone(dock[0] + 0.5, dock[1] + 0.5, CAVE_Z, steward));
		}

		// The building's other machine. It marshals loose crates back onto the
		// stack on the marshalling deck -- which is where the stack already is, so
		// the drop point locates itself the way the dock does: by looking at
		// what is on the map rather than by threading a coordinate out of
		// whichever base plan ran. A world with nothing stacked in it has
		// nothing to marshal and simply gets no loader.
		w.think(); // admit every spawn: the crates below must be findable

		// Sited after the first tick on purpose. A spawn is pending until the
		// world admits it, so a stack looked for before that tick is a stack of
		// nothing -- which is precisely what happened, and it reads as the
		// loader simply not existing rather than as an ordering bug.
		double[] stack = findStack(w);
		if (stack != null) {
			w.spawnEntity(new FacilityLoader(stack[0], stack[1], CAVE_Z,
					stack[0], stack[1], CAVE_Z));
		}

		w.think(); // and admit the loader
		return w;
	}

	/**
	 * The facility's INTERIOR north-west corner on the cave level -- the tile
	 * the plans' (0, 0) is stamped on, one inside the shell -- or null if this
	 * map got none. Found as the drone rank on the map, offset by where the
	 * rank is drawn in the plan.
	 *
	 * <p>Located by the rank because the rank is unique -- one building in the
	 * world berths drones. The obvious landmark, the pipe run, stopped being
	 * one the day the sectors arrived: Lambda draws a pipe run too, and it
	 * stands west of the halls, so "the first pipe tile on the deep level" was
	 * suddenly Lambda's and every coordinate derived from it was wrong by
	 * ninety tiles. Derived from the drawing rather than written down beside
	 * it, so moving the rank in the plan moves this with it.
	 */
	public static int[] facilityOrigin(World w) {
		int px = -1, py = -1;
		for (int j = 0; j < HALLS.length && px < 0; j++) {
			int i = HALLS[j].indexOf('D');
			if (i >= 0) {
				px = i;
				py = j;
			}
		}
		if (px < 0) {
			throw new IllegalStateException("the halls draw no drone rank");
		}
		for (int y = 0; y < w.getRows(); y++) {
			for (int x = 0; x < w.getColums(); x++) {
				if (w.getTile(x, y, CAVE_Z).getType() == Tile.TileType.TYPE_DOCK) {
					return new int[] { x - px, y - py };
				}
			}
		}
		return null;
	}

	/**
	 * The charge docks the world generator laid into the buried base, as
	 * {@code {col, row}} pairs on the cave level, in reading order. Empty if
	 * this map got no base.
	 *
	 * <p>Found by looking rather than remembered: the rank is wherever the
	 * halls' drawing put it, and a coordinate threaded back out through
	 * {@code buryInstallation} would be one more thing for the drawing to keep
	 * in step with. The dock tiles are the record: they are on the map,
	 * and anything that needs to know where the drones live asks the same
	 * question this does. One pass over one level at world creation.
	 *
	 * <p>Returns every pad rather than the first. A world that berthed one drone
	 * on the first pad it found and left three cut into the deck would be a
	 * world where the map says four and the population says one — and the map is
	 * the thing a viewer can see.
	 */
	public static java.util.List<int[]> findDocks(World w) {
		java.util.List<int[]> out = new java.util.ArrayList<int[]>();
		for (int y = 0; y < w.getRows(); y++) {
			for (int x = 0; x < w.getColums(); x++) {
				if (w.getTile(x, y, CAVE_Z).getType() == Tile.TileType.TYPE_DOCK) {
					out.add(new int[] { x, y });
				}
			}
		}
		return out;
	}

	/**
	 * Where the crates are stacked: the mean position of the crates already in
	 * the base, or null if there are none.
	 *
	 * <p>The loader's berth and its drop point are the same place, and that is
	 * the whole design rather than a shortcut. A machine that fetches strays
	 * back to the pile it is standing next to needs no depot, no schedule and
	 * no second coordinate; the pile is the instruction. Averaging rather than
	 * taking the first crate matters only for where it stands, and standing in
	 * the middle of the stack is what makes "bring it back here" legible.
	 */
	static double[] findStack(World w) {
		double sx = 0, sy = 0;
		int n = 0;
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e instanceof net.hedinger.prototype.entities.Item it && !it.isRemoved()
					&& it.getKind() == net.hedinger.prototype.entities.Item.Kind.CRATE
					&& it.getZ() == CAVE_Z) {
				sx += it.getX();
				sy += it.getY();
				n++;
			}
		}
		return n == 0 ? null : new double[] { sx / n, sy / n };
	}

	/** Scale a base count by the map's area ratio, never below 1. */
	private static int sc(int base, double scale) {
		return Math.max(1, (int) Math.round(base * scale));
	}
}
