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

	// --- markers (neutral recognition barcode, each in [0,1]) ---
	public double[] markers = new double[MARKER_DIMS];

	// --- dispositions (interpretable response weights, >= 0) ---
	public double predatory = 0; // attack smaller & dissimilar
	public double xenophobia = 0; // flee bigger & dissimilar
	public double gregariousness = 0; // approach the similar
	public double boldness = 0; // reduces flight
	public double mateThreshold = 0.85; // similarity above which mating is sought

	public Genome() {
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
		g.markers = markers.clone();
		g.predatory = predatory;
		g.xenophobia = xenophobia;
		g.gregariousness = gregariousness;
		g.boldness = boldness;
		g.mateThreshold = mateThreshold;
		return g;
	}

	// ---- heredity ----------------------------------------------------------

	/** Asexual offspring: a copy with each gene mutated by up to +/- rate. */
	public static Genome child(Genome parent, double rate) {
		Genome g = parent.copy();
		g.mutate(rate);
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
		for (int i = 0; i < MARKER_DIMS; i++) {
			g.markers[i] = pick(a.markers[i], b.markers[i]);
		}
		g.predatory = pick(a.predatory, b.predatory);
		g.xenophobia = pick(a.xenophobia, b.xenophobia);
		g.gregariousness = pick(a.gregariousness, b.gregariousness);
		g.boldness = pick(a.boldness, b.boldness);
		g.mateThreshold = pick(a.mateThreshold, b.mateThreshold);
		g.mutate(rate);
		return g;
	}

	private static double pick(double a, double b) {
		return Utils.random() < 0.5 ? a : b;
	}

	/** Mutates every gene by up to +/- rate (relative for magnitudes). */
	public void mutate(double rate) {
		size = pos(size * (1 + jitter(rate)));
		speed = pos(speed * (1 + jitter(rate)));
		turnRate = Math.max(1, (int) Math.round(turnRate * (1 + jitter(rate))));
		losRange = pos(losRange * (1 + jitter(rate)));
		losFov = clamp(losFov * (1 + jitter(rate)), 0, 2 * Math.PI);
		metabolism = pos(metabolism * (1 + jitter(rate)));
		maxAge = Math.max(1, (int) Math.round(maxAge * (1 + jitter(rate))));
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

	/** Maps the first three markers to an RGB colour for the debug view. */
	public Color toColor() {
		int red = (int) (clamp(markers[0], 0, 1) * 255);
		int green = (int) (clamp(markers[1], 0, 1) * 255);
		int blue = (int) (clamp(markers.length > 2 ? markers[2] : 0.5, 0, 1) * 255);
		return new Color(red, green, blue);
	}
}
