package net.hedinger.prototype.entities;

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

	// --- trophic level (what this lineage eats) ---
	/** Grazes. */
	public static final int DIET_HERBIVORE = 0;
	/** Kills and eats what it kills. */
	public static final int DIET_CARNIVORE = 1;
	/** Eats what is already dead. */
	public static final int DIET_SCAVENGER = 2;
	/** Rides a bigger living body and eats it slowly, from on top of it. */
	public static final int DIET_PARASITE = 3;

	/**
	 * What this lineage eats, as a {@code DIET_*} code.
	 *
	 * <p>It lives in the genome because it is the most consequential thing about a
	 * body and the least negotiable: it decides what the creature can digest, who
	 * it will breed with, and — since it is what the phenotype reads — what it
	 * looks like. It used to be a field on the creature that heredity had to
	 * remember to copy by hand, which is exactly the kind of thing heredity forgets:
	 * a scavenger's young were born grazers until that was fixed, and the body plan
	 * could never see the trait at all.
	 *
	 * <p>Inherited but never mutated. A lineage does not drift across trophic levels
	 * one jitter at a time; a grazer whose grandchildren eat carrion is not evolution
	 * in this world, it is a bug that would quietly dissolve the food chain. Sexual
	 * crossover takes it from either parent, which is unambiguous because diet is a
	 * mate barrier — a pair always agrees.
	 */
	public int diet = DIET_HERBIVORE;

	// --- dispositions (interpretable response weights, >= 0) ---
	public double predatory = 0; // attack smaller & dissimilar
	public double xenophobia = 0; // flee bigger & dissimilar
	public double gregariousness = 0; // approach the similar
	public double boldness = 0; // reduces flight
	public double mateThreshold = 0.85; // similarity above which mating is sought
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
		return g;
	}

	public Genome copy() {
		Genome g = new Genome();
		g.size = size;
		g.speed = speed;
		g.turnRate = turnRate;
		g.losRange = losRange;
		g.losFov = losFov;
		g.metabolism = metabolism;
		g.maxAge = maxAge;
		g.flying = flying;
		g.markers = markers.clone();
		g.diet = diet;
		g.predatory = predatory;
		g.xenophobia = xenophobia;
		g.gregariousness = gregariousness;
		g.boldness = boldness;
		g.mateThreshold = mateThreshold;
		g.sexuality = sexuality;
		g.brain = (brain == null) ? null : brain.copy();
		return g;
	}

	// ---- heredity ----------------------------------------------------------

	/** Asexual offspring: a copy with each gene mutated by up to +/- rate. */
	public static Genome child(Genome parent, double rate) {
		Genome g = parent.copy();
		g.mutate(rate);
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
		g.size = pick(a.size, b.size);
		g.speed = pick(a.speed, b.speed);
		g.turnRate = (int) pick(a.turnRate, b.turnRate);
		g.losRange = pick(a.losRange, b.losRange);
		g.losFov = pick(a.losFov, b.losFov);
		g.metabolism = pick(a.metabolism, b.metabolism);
		g.maxAge = (int) pick(a.maxAge, b.maxAge);
		g.flying = a.flying; // locomotion inherited (no RNG draw: keeps the sim stream stable)
		for (int i = 0; i < MARKER_DIMS; i++) {
			g.markers[i] = pick(a.markers[i], b.markers[i]);
		}
		// No draw and no pick: a pair only breeds inside its own diet, so both
		// parents carry the same one and there is nothing to choose between.
		g.diet = a.diet;
		g.predatory = pick(a.predatory, b.predatory);
		g.xenophobia = pick(a.xenophobia, b.xenophobia);
		g.gregariousness = pick(a.gregariousness, b.gregariousness);
		g.boldness = pick(a.boldness, b.boldness);
		g.mateThreshold = pick(a.mateThreshold, b.mateThreshold);
		g.sexuality = pick(a.sexuality, b.sexuality);
		g.mutate(rate);
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

	private static double pick(double a, double b) {
		return Utils.random() < 0.5 ? a : b;
	}

	/** Body size stays inside a sane band under mutation: the drift is
	 *  multiplicative, so an unbounded gene random-walks to extremes (dust-sized
	 *  or giant) over a long-lived world. These keep every creature readable and
	 *  the predator/prey scale meaningful. */
	public static final double SIZE_MIN = 4, SIZE_MAX = 20;

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
	public static final double SPEED_MAX = 0.3;

	/** Mutates every gene by up to +/- rate (relative for magnitudes). */
	public void mutate(double rate) {
		size = clamp(size * (1 + jitter(rate)), SIZE_MIN, SIZE_MAX);
		speed = clamp(speed * (1 + jitter(rate)), 0, SPEED_MAX);
		turnRate = Math.max(1, (int) Math.round(turnRate * (1 + jitter(rate))));
		losRange = pos(losRange * (1 + jitter(rate)));
		losFov = clamp(losFov * (1 + jitter(rate)), 0, 2 * Math.PI);
		// Additive, not multiplicative: a lineage sitting at 0 could never drift back
		// across the boundary if the step were proportional to where it already is.
		sexuality = clamp(sexuality + jitter(rate), 0, 1);
		metabolism = pos(metabolism * (1 + jitter(rate)));
		maxAge = Math.max(1, (int) Math.round(maxAge * (1 + jitter(rate))));
		// flying is inherited as-is (no RNG draw here) so adding it does not shift
		// the deterministic sim stream; evolvable flight can come later.
		for (int i = 0; i < MARKER_DIMS; i++) {
			markers[i] = clamp(markers[i] + jitter(rate), 0, 1);
		}
		predatory = pos(predatory + jitter(rate));
		xenophobia = pos(xenophobia + jitter(rate));
		gregariousness = pos(gregariousness + jitter(rate));
		boldness = pos(boldness + jitter(rate));
		mateThreshold = clamp(mateThreshold + jitter(rate), 0, 1);
	}

	private static double jitter(double rate) {
		return (Utils.random() * 2 - 1) * rate;
	}

	private static double pos(double v) {
		return v < 0 ? 0 : v;
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

		Relation r = new Relation();
		r.attack = predatory * dissim * Math.max(0, sizeAdv - 1);
		r.flee = Math.max(0, xenophobia * dissim * Math.max(0, (1 / Math.max(1e-6, sizeAdv)) - 1) - boldness);
		r.affiliate = gregariousness * s;
		r.mate = s >= mateThreshold ? s : 0;

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
