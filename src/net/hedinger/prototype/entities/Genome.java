package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Unit;
import java.awt.Color;

import net.hedinger.prototype.engine.Utils;

/**
 * An entity's heritable trait vector. Three layers:
 *
 * <ul>
 *   <li><b>Phenotype</b> -- the body: size, speed, turn, perception, metabolism,
 *       max age. These drive physics and behaviour.</li>
 *   <li><b>Markers</b> -- a neutral "barcode" ({@link #markers}) used only for
 *       recognition. They have no physical effect; two entities are "similar"
 *       when their markers are close. Kept separate from behaviour genes so
 *       recognition and disposition evolve independently (a green-beard split),
 *       and mapped to a display colour so similarity is visible.</li>
 *   <li><b>Dispositions</b> -- interpretable response weights that turn a
 *       perceived neighbour into a behavioural drive (see {@link #react}).</li>
 * </ul>
 *
 * Relationships are not hardcoded: an entity's reaction to another emerges from
 * how similar they are, their relative size, and this genome's dispositions.
 * Predation is asymmetric because it keys on the size ratio; species emerge as
 * clusters in marker space. Offspring inherit a mutated copy ({@link #child}),
 * so the dispositions -- the behaviour itself -- are subject to selection.
 */
public class Genome {

	@Unit("marker genes")
	public static final int MARKER_DIMS = 3; // mapped to RGB for the debug view

	// --- phenotype (the body) ---
	public double size = 6; // pixel radius (getSize() divides by tileSize)
	public double speed = 0.04;
	public int turnRate = 5;
	public double losRange = 10;
	public double losFov = Math.PI * 0.5;
	public double metabolism = 0.02;
	public int maxAge = 3000;
	public boolean flying = false; // locomotion: airborne (a detached shadow) vs ground

	// --- mind (optional evolvable behaviour; null = no brain) ---
	/** The creature's decision program. Heritable alongside the body: copied and
	 * mutated for asexual young, crossed over for sexual young. Kept null on the
	 * genomes that don't use it, so adding it draws no RNG for brain-less lineages
	 * and the deterministic sim stream is unchanged. */
	public Brain brain = null;

	// --- markers (neutral recognition barcode, each in [0,1]) ---
	public double[] markers = new double[MARKER_DIMS];

	// --- clade (the class of creature this is) ---
	/**
	 * The class of creature this is — one of four, and the most consequential
	 * thing about a body.
	 *
	 * <p>A clade fixes three separate things at once, which is why it is a concept
	 * rather than a loose collection of flags. It decides <b>what a creature can
	 * digest</b>, so it fixes which supply its numbers are coupled to; it is a
	 * <b>hard mate barrier</b>, so clades cannot interbreed and each is a closed
	 * lineage; and it is <b>what the phenotype reads</b>, so each clade wears its
	 * own body plan and is recognisable on sight.
	 *
	 * <p>This used to be spelled five different ways — an int here, a parallel enum
	 * on the creature, a role String, a body-plan index in yet another order, and a
	 * duplicate role accessor — with nothing checking they agreed. Adding a clade
	 * meant editing five places in three orders. Now the enum owns all of it and
	 * everything else is derived.
	 *
	 * <p><b>The codes are frozen.</b> Saved {@code .genome} files carry this as an
	 * integer, and a viewer can re-inject one, so the numbers are a wire format and
	 * not an implementation detail. They are declared explicitly rather than taken
	 * from {@code ordinal()} so that reordering this enum can never silently
	 * reinterpret every genome anyone has saved.
	 */
	public enum Clade {
		/** Grazes the living substrate. */
		HERBIVORE(0, 0),
		/** Kills and eats what it kills. */
		PREDATOR(1, 2),
		/** Eats what is already dead. */
		SCAVENGER(2, 1),
		/** Rides a bigger living body and eats it slowly, from on top of it. */
		PARASITE(3, 3);

		private final int code;
		private final int bodyPlan;

		Clade(int code, int bodyPlan) {
			this.code = code;
			this.bodyPlan = bodyPlan;
		}

		/** The frozen wire code, as written into saved genomes. */
		public int code() {
			return code;
		}

		/** The name this clade goes by everywhere outside the engine: the entity
		 *  JSON, the population graph, the inspector. Lower-cased enum name, so the
		 *  two can never drift apart. */
		public String wireName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		/** Index of this clade's body plan, for the organism renderer. Owned here so
		 *  a clade's silhouette is a property OF the clade rather than a switch
		 *  somewhere else that has to be remembered. */
		public int bodyPlan() {
			return bodyPlan;
		}

		/** The clade with this wire code, or {@link #HERBIVORE} for anything
		 *  unrecognised — an old or damaged genome loads as a grazer rather than
		 *  failing, which is the safe direction to be wrong in. */
		public static Clade ofCode(int code) {
			for (Clade c : values()) {
				if (c.code == code) {
					return c;
				}
			}
			return HERBIVORE;
		}
	}

	/**
	 * What this lineage is.
	 *
	 * <p>Inherited but never mutated. A lineage does not drift across clades one
	 * jitter at a time; a grazer whose grandchildren eat carrion is not evolution
	 * in this world, it is a bug that would quietly dissolve the food chain. Sexual
	 * crossover takes it from either parent, which is unambiguous because the clade
	 * is a mate barrier — a pair always agrees.
	 *
	 * <p>So clades are authored and species are emergent: nothing in the world can
	 * create a new clade, while marker drift under {@link #mateThreshold} produces
	 * real reproductive isolation within one.
	 */
	public Clade clade = Genome.Clade.HERBIVORE;

	// --- dispositions (interpretable response weights, >= 0) ---
	public double predatory = 0; // attack smaller & dissimilar
	public double xenophobia = 0; // flee bigger & dissimilar
	public double gregariousness = 0; // approach the similar
	public double boldness = 0; // reduces flight
	public double mateThreshold = 0.85; // similarity above which mating is sought

	/**
	 * How much this creature weighs the SIZE of a prize against the work of
	 * getting it -- an exponent, so at 1 a thing twice as valuable is worth twice
	 * the effort, below 1 it is an opportunist that takes whatever is handy, and
	 * above 1 a specialist that holds out for something worth the trouble.
	 *
	 * <p>Named for the appetite rather than for the one thing that reads it. A
	 * hunter's standards are the first caller and today the only one, but "how
	 * much is bigger worth to me" is not a question about prey: a forage patch
	 * and a carcass ask it too, and both are already scored value over distance.
	 *
	 * <p>A gene rather than a constant because it is not knowable in advance.
	 * Whether it pays to cross a field for a big animal depends on how thick the
	 * herd is, how fast it runs and how hard it is to bring down -- facts about a
	 * world, and different in every one. Choosing a number here would be deciding
	 * on selection's behalf what it is for selection to find out.
	 *
	 * @see net.hedinger.prototype.entities.AgentIO#A_PREY
	 */
	public double greed = 1.0;

	/**
	 * How much better an alternative must be before this creature gives up the
	 * goal it is already pursuing -- the multiplier a rival has to clear to take
	 * over. 1 is no determination at all: decide again every tick, ties to
	 * whatever is already held.
	 *
	 * <p>Temperament rather than tactics. Dogged against opportunistic is a fact
	 * about a lineage, not a choice made afresh each tick, which is why it lives
	 * here and not on an actuator. Named for the trait rather than for prey
	 * because the trait is general: a carcass, a patch of grass and a fleeing
	 * animal are all things a creature can either finish or think better of.
	 *
	 * <p><b>Two readers, not four.</b> Every clade answers "when do I
	 * reconsider?", but only the hunter and the scavenger answer it with a bar --
	 * they re-score their choice every tick, and this is what damps the switch.
	 * The other two are not uncommitted; they are committed by another mechanism
	 * entirely. A grazer answers with a clock: between forage scans it does not
	 * reconsider AT ALL, which is total commitment for the window, and then
	 * reconsiders with no loyalty whatever (see {@code FORAGE_SCAN_PERIOD}). A
	 * parasite answers with its grip, which is commitment made physical. Putting
	 * a bar on either would compound two answers to one question rather than
	 * generalise one, so reaching them means replacing what they have, not adding
	 * to it -- a redesign with its own measurement, and not this gene's business.
	 *
	 * <p>It defaults to 2, which is not a number chosen here: it is the value the
	 * scavenger's carrion path was measured into. Re-deciding every tick left a
	 * scavenger with a carcass in reach on 0.87% of its ticks -- it walked between
	 * bodies and arrived at none -- and 2 is what fixed that. No other reader has
	 * evidence either way, so the one place that does gets to set the default, and
	 * a lineage is free to drift off it.
	 */
	public double determination = 2.0;
	/**
	 * Which way this lineage reproduces, 0..1, sexual at or above 0.5. A creature is
	 * one or the other and never both: a sexual body courts a partner and waits if
	 * there is none, an asexual one buds alone and never courts.
	 *
	 * <p>Continuous rather than a flag on purpose — mutation can drift a lineage
	 * across the boundary a little at a time, so the strategy is something selection
	 * can move toward instead of a coin that flips whole.
	 */
	public double sexuality = 0.5;

	// --- life history (per-lineage reproductive strategy) ---------------------
	// The r/K axis, made heritable. These used to be global constants — one
	// reproductive strategy shared by every lineage in the world — but breeding
	// conserves energy now (a child is worth exactly what its parents paid), so
	// whatever values a lineage evolves, the books still balance and there is
	// nothing to exploit. So they become genes: breed-early-and-cheap versus
	// bank-and-invest is a strategy selection can explore, not a number chosen
	// once. The defaults are the reference lineage's, matching the constants they
	// replaced.
	/** Fraction of the (size-scaled) tank that must be full before this lineage
	 *  breeds. Low is an r-strategist — breed early, off a thin reserve; high is a
	 *  K-strategist that banks a buffer first. */
	public double reproFraction = 0.75;
	/** Fraction of the tank this lineage spends per offspring — how much it invests
	 *  in each child. Paired with {@link #reproFraction} this is the whole r/K
	 *  trade: cheap-and-many against dear-and-few. */
	public double reproCostFraction = 0.5;
	/** How hard this lineage mutates its own offspring, per gene at birth — meta-
	 *  evolution, bounded so a lineage can neither fossilise at zero nor dissolve
	 *  at a huge rate. */
	public double mutationRate = 0.1;
	/**
	 * A born-in drive to forage, 0..1: how hard a newborn seeks food before its
	 * mind has decided anything. Seeded into the body's actuators at birth (the
	 * forage intent at this throttle), the brain overrides it the moment it writes
	 * — so a random-brained founder that would otherwise never stumble onto
	 * feeding has a working instinct to build on, and an evolved mind is
	 * unaffected. Instinct in the genome, deliberation in the mind.
	 */
	public double instinct = 0.0;

	/** True if this genome reproduces sexually; false if it buds. */
	public boolean isSexual() {
		return sexuality >= 0.5;
	}

	public Genome() {
	}

	/**
	 * A founder genome carrying just the body stats. Markers and dispositions
	 * keep their defaults -- callers that need recognition or emergent
	 * relationships set those separately. Used to seed a species from the stats
	 * it historically hardcoded, so the phenotype now flows from the genome.
	 */
	public static Genome phenotype(double size, double speed, int turnRate,
			double losRange, double losFov, int maxAge) {
		Genome g = new Genome();
		g.size = size;
		g.speed = speed;
		g.turnRate = turnRate;
		g.losRange = losRange;
		g.losFov = losFov;
		g.maxAge = maxAge;
		return g;
	}

	/** A random genome (seeded RNG), useful for founding a population. */
	public static Genome random() {
		Genome g = new Genome();
		for (int i = 0; i < MARKER_DIMS; i++) {
			g.markers[i] = Utils.random();
		}
		g.predatory = Utils.random();
		g.xenophobia = Utils.random();
		g.gregariousness = Utils.random();
		g.boldness = Utils.random() * 0.3;
		g.mateThreshold = 0.7 + Utils.random() * 0.3;
		g.sexuality = Utils.random(); // an even split of strategies to start from
		// Founders differ in appetite from the start, so selection has something to
		// act on before mutation has had time to make any.
		g.greed = 0.5 + Utils.random() * 1.5;
		g.determination = 1.0 + Utils.random() * 2.0;
		// Life history spread across founders too, so the r/K axis, evolvability
		// and the foraging instinct all vary from the first generation.
		g.reproFraction = 0.6 + Utils.random() * 0.3;
		g.reproCostFraction = 0.35 + Utils.random() * 0.3;
		g.mutationRate = 0.05 + Utils.random() * 0.1;
		g.instinct = 0.5 + Utils.random() * 0.5;
		return g;
	}

	public Genome copy() {
		Genome g = new Genome();
		// Every numeric gene, copied through the schema so a new gene is copied the
		// moment it is declared — no field can be forgotten here again.
		for (GeneSchema.Gene gene : GeneSchema.genes()) {
			gene.set(g, gene.get(this));
		}
		// The non-numeric heritables the schema does not carry: the clade (a code,
		// never mutated) and the mind (its own crossover machinery).
		g.clade = clade;
		g.brain = (brain == null) ? null : brain.copy();
		return g;
	}

	// ---- heredity ----------------------------------------------------------

	/** Asexual offspring: a copy with each gene mutated by up to +/- rate. */
	public static Genome child(Genome parent, double rate) {
		Genome g = parent.copy();
		GeneSchema.mutate(g, rate);
		if (g.brain != null) {
			g.brain.mutate(rate); // mutate the inherited program (guarded: no brain -> no RNG)
		}
		return g;
	}

	/**
	 * Sexual offspring: per-gene crossover of two parents, then mutation.
	 * Assortative mating over similar parents (see {@link #react}) keeps
	 * lineages together and is the driver of speciation.
	 */
	public static Genome child(Genome a, Genome b, double rate) {
		Genome g = new Genome();
		GeneSchema.crossover(g, a, b);
		// A pair only breeds inside its own clade, so both carry the same one and
		// there is nothing to choose — no draw, no pick.
		g.clade = a.clade;
		GeneSchema.mutate(g, rate);
		// Crossover the minds when both parents have one; otherwise inherit whichever
		// exists. Guarded so brain-less pairs draw no extra RNG.
		if (a.brain != null && b.brain != null) {
			g.brain = Brain.child(a.brain, b.brain, rate);
		} else if (a.brain != null) {
			g.brain = a.brain.copy();
		} else if (b.brain != null) {
			g.brain = b.brain.copy();
		}
		return g;
	}

	/** Body size stays inside a sane band under mutation: the drift is
	 *  multiplicative, so an unbounded gene random-walks to extremes (dust-sized
	 *  or giant) over a long-lived world. These keep every creature readable and
	 *  the predator/prey scale meaningful. */
	@Unit("px radius")
	public static final double SIZE_MIN = 4, SIZE_MAX = 20;
	/** Bounds on the two appetite genes. Greed reaching 0 is a real strategy --
	 *  take whatever is nearest and never mind what it is -- so its floor is 0.
	 *  Determination floors at 1 because below that a creature would abandon a
	 *  goal for a WORSE one, which is not a policy but a bug. */
	@Unit("value exponent / x incumbent score")
	public static final double GREED_MAX = 3.0, DETERMINATION_MAX = 8.0;

	/**
	 * Ceiling on the speed gene (tiles/tick). Speed was the one magnitude with no
	 * upper bound, so it random-walked upward under selection with nothing to stop
	 * it. {@link net.hedinger.prototype.engine.Entity#MAX_STEP} already refuses to
	 * move any body further than half a tile per tick, but clamping the gene too
	 * keeps a genome honest: without it a lineage would evolve speeds the engine
	 * silently throws away, and the inspector would advertise a number the creature
	 * cannot reach. Sits below MAX_STEP, so the fastest body a genome can express
	 * is still inside the engine's per-tick step limit on its own.
	 */
	@Unit("tiles/tick")
	public static final double SPEED_MAX = 0.3;

	/**
	 * Mutates every gene by up to +/- rate. Delegates to {@link GeneSchema}, the
	 * one place a gene's drift mode and bounds are declared — so this method can
	 * no longer disagree with the schema about how a gene moves, and cannot forget
	 * a gene the schema knows about.
	 */
	public void mutate(double rate) {
		GeneSchema.mutate(this, rate);
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	// ---- recognition & reaction --------------------------------------------

	/** 1 (identical markers) .. 0 (maximally distant). */
	public double similarityTo(Genome other) {
		double sum = 0;
		for (int i = 0; i < MARKER_DIMS; i++) {
			double d = markers[i] - other.markers[i];
			sum += d * d;
		}
		double dist = Math.sqrt(sum);
		double maxDist = Math.sqrt(MARKER_DIMS); // markers in [0,1]
		return 1 - dist / maxDist;
	}

	/** What this genome wants to do about a perceived other. */
	public enum Action {
		IGNORE, ATTACK, FLEE, AFFILIATE, MATE
	}

	/** Drive strengths and the dominant action for one perceived neighbour. */
	public static final class Relation {
		public double attack, flee, affiliate, mate;
		public Action action = Action.IGNORE;

		/** Strength of the dominant drive (0 when ignoring). */
		public double strength() {
			switch (action) {
			case FLEE:
				return flee;
			case ATTACK:
				return attack;
			case MATE:
				return mate;
			case AFFILIATE:
				return affiliate;
			default:
				return 0;
			}
		}
	}

	/**
	 * Computes this genome's reaction to another it can perceive.
	 *
	 * @param other    the perceived neighbour's genome
	 * @param sizeAdv  this entity's size / the other's size (&gt;1 = self is
	 *                 bigger). Predation and flight key off this, which is what
	 *                 makes the relationship asymmetric.
	 */
	public Relation react(Genome other, double sizeAdv) {
		double s = similarityTo(other);
		double dissim = 1 - s;

		// Clade is recognised, and it is not the same axis as similarity. Markers
		// say how closely related two creatures are WITHIN a clade -- that is the
		// species axis, and it is continuous. Clade is categorical and absolute: a
		// grazer and a hunter that happen to share markers are not kin, and reading
		// them as kin is what let a predator drift into a herd and be flocked with.
		//
		// So the two sociable drives are gated on clade outright. Mating is already
		// impossible across clades (see NPC.canMateWith); wanting to is just wasted
		// courtship. Affiliation is blocked for the same reason it exists -- a herd
		// is a group of one's own kind, and a mixed one is not a herd.
		//
		// The two hostile drives are deliberately NOT gated. Predation and flight
		// are about size and disposition, not kinship: a hunter that would only
		// chase its own clade would never eat, and prey that only feared its own
		// would never run.
		boolean sameClade = other != null && other.clade == clade;

		Relation r = new Relation();
		r.attack = predatory * dissim * Math.max(0, sizeAdv - 1);
		r.flee = Math.max(0, xenophobia * dissim * Math.max(0, (1 / Math.max(1e-6, sizeAdv)) - 1) - boldness);
		r.affiliate = sameClade ? gregariousness * s : 0;
		r.mate = sameClade && s >= mateThreshold ? s : 0;

		// Dominant drive, survival-first on ties.
		double best = 1e-4; // ignore threshold
		if (r.flee > best) {
			best = r.flee;
			r.action = Action.FLEE;
		}
		if (r.attack > best) {
			best = r.attack;
			r.action = Action.ATTACK;
		}
		if (r.mate > best) {
			best = r.mate;
			r.action = Action.MATE;
		}
		if (r.affiliate > best) {
			best = r.affiliate;
			r.action = Action.AFFILIATE;
		}
		return r;
	}

	/** A compact one-line summary of the whole genome, for console logging. */
	@Override
	public String toString() {
		return String.format(
				"size %.1f spd %.3f turn %d los %.1f/%.0fdeg meta %.3f age %d%s "
						+ "m[%.2f %.2f %.2f] pred %.2f xeno %.2f greg %.2f bold %.2f mate %.2f",
				size, speed, turnRate, losRange, Math.toDegrees(losFov), metabolism, maxAge,
				flying ? " fly" : "", markers[0], markers[1], markers.length > 2 ? markers[2] : 0.5,
				predatory, xenophobia, gregariousness, boldness, mateThreshold);
	}

	/** Labelled lines for an on-screen inspector panel (one gene group per line). */
	public String[] describe() {
		return new String[] {
				String.format("size %.1f   speed %.3f   turn %d", size, speed, turnRate),
				String.format("los %.1f / %.0fdeg   meta %.3f", losRange, Math.toDegrees(losFov), metabolism),
				String.format("maxAge %d   %s", maxAge, flying ? "flying" : "ground"),
				String.format("markers  %.2f  %.2f  %.2f",
						markers[0], markers[1], markers.length > 2 ? markers[2] : 0.5),
				String.format("predatory %.2f   xeno %.2f", predatory, xenophobia),
				String.format("gregarious %.2f   bold %.2f", gregariousness, boldness),
				String.format("mateThreshold %.2f", mateThreshold),
		};
	}

	/** Maps the first three markers to an RGB colour for the debug view. */
	public Color toColor() {
		int red = (int) (clamp(markers[0], 0, 1) * 255);
		int green = (int) (clamp(markers[1], 0, 1) * 255);
		int blue = (int) (clamp(markers.length > 2 ? markers[2] : 0.5, 0, 1) * 255);
		return new Color(red, green, blue);
	}
}
