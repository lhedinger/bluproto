package net.hedinger.prototype.entities;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

import net.hedinger.prototype.engine.Utils;

/**
 * The genome's numeric genes, declared once.
 *
 * <p>Every heritable magnitude a lineage carries is one {@link Gene} in this
 * list: a key, a unit, the bounds it lives inside, how it drifts under
 * mutation, and an accessor pair onto the {@link Genome}'s field. The point is
 * that a gene exists in exactly ONE place. Before this registry the same gene
 * appeared five times — in {@code copy}, in {@code mutate}, in each
 * {@code child} overload, and in the codec — kept in step by hand, so a gene
 * could drift out of one of them (perception did: {@code losFov} mutated but
 * was never expressed) and nothing noticed. Now {@code copy}, {@code mutate},
 * crossover, serialization and enumeration are each a single loop over this
 * list, and a gene that is added here is added to all of them at once.
 *
 * <p>The declaration order IS the mutation draw order, and mutation draws
 * exactly one seeded random per {@link Drift#MULT}/{@link Drift#ADD} gene in
 * that order — so the order is load-bearing for determinism and genes are only
 * ever appended, never reordered. {@link Drift#NONE} genes (locomotion mode)
 * draw nothing, matching a trait that is inherited but never jittered.
 *
 * <p>A flat, bounded, typed vector is also what the gradient-optimisation half
 * of the design wants: {@link #values(Genome)} and {@link #apply(Genome,
 * double[])} turn a genome into a sweepable array and back, so a genome can be
 * perturbed and probed mechanically without any gene being named in the caller.
 */
public final class GeneSchema {

	/** How a gene moves under mutation. */
	public enum Drift {
		/** Multiplicative: {@code v *= 1 + jitter}. For magnitudes, so the step
		 *  is proportional to size and a big value is not nudged by a small
		 *  lineage's-worth of noise. */
		MULT,
		/** Additive: {@code v += jitter}. For bounded traits and anything that
		 *  can legitimately sit at zero — a multiplicative step could never
		 *  carry a gene back off a bound it had reached. */
		ADD,
		/** No drift and no draw: inherited exactly, jittered never. */
		NONE,
	}

	/** One heritable magnitude: its identity, its bounds, its drift, and the
	 *  window onto the genome field that stores it. */
	public static final class Gene {
		public final String key;
		public final String unit;
		public final Drift drift;
		public final double lo, hi;
		public final boolean integral;
		private final ToDoubleFunction<Genome> get;
		private final ObjDoubleConsumer<Genome> set;

		Gene(String key, String unit, Drift drift, double lo, double hi,
				boolean integral, ToDoubleFunction<Genome> get, ObjDoubleConsumer<Genome> set) {
			this.key = key;
			this.unit = unit;
			this.drift = drift;
			this.lo = lo;
			this.hi = hi;
			this.integral = integral;
			this.get = get;
			this.set = set;
		}

		public double get(Genome g) {
			return get.applyAsDouble(g);
		}

		/** Writes a value onto the genome, rounding an integral gene and clamping
		 *  into the gene's bounds — so nothing can set a gene outside the range the
		 *  schema declares for it, whoever the caller is. */
		public void set(Genome g, double v) {
			if (integral) {
				v = Math.round(v); // half-up, matching the hand-written mutate this replaced
			}
			set.accept(g, clamp(v));
		}

		/** Writes a value verbatim, without the biology clamp or rounding —
		 *  serialization's path, so a savefile is a lossless transport that
		 *  reproduces exactly what was stored rather than re-imposing the living
		 *  bounds on a value that is only passing through. */
		public void setRaw(Genome g, double v) {
			set.accept(g, v);
		}

		private double clamp(double v) {
			return v < lo ? lo : (v > hi ? hi : v);
		}
	}

	private static final double INF = Double.POSITIVE_INFINITY;
	private static final List<Gene> GENES = new ArrayList<>();

	private static Gene add(String key, String unit, Drift drift, double lo, double hi,
			boolean integral, ToDoubleFunction<Genome> get, ObjDoubleConsumer<Genome> set) {
		Gene gene = new Gene(key, unit, drift, lo, hi, integral, get, set);
		GENES.add(gene);
		return gene;
	}

	// Declaration order == mutation draw order. Matches the hand-written mutate()
	// this replaced, tick for tick, so the deterministic stream is unchanged:
	// size, speed, turn, los, fov, sexuality, metab, maxAge, [flying: no draw],
	// markers x3, predatory, xenophobia, gregariousness, boldness, mate, greed,
	// determination. Append new genes; never reorder these.
	static {
		add("size", "px radius", Drift.MULT, Genome.SIZE_MIN, Genome.SIZE_MAX, false,
				g -> g.size, (g, v) -> g.size = v);
		add("speed", "tiles/tick", Drift.MULT, 0, Genome.SPEED_MAX, false,
				g -> g.speed, (g, v) -> g.speed = v);
		add("turn", "", Drift.MULT, 1, INF, true,
				g -> g.turnRate, (g, v) -> g.turnRate = (int) v);
		add("los", "tiles", Drift.MULT, 0, INF, false,
				g -> g.losRange, (g, v) -> g.losRange = v);
		add("fov", "radians", Drift.MULT, 0, 2 * Math.PI, false,
				g -> g.losFov, (g, v) -> g.losFov = v);
		add("sex", "", Drift.ADD, 0, 1, false,
				g -> g.sexuality, (g, v) -> g.sexuality = v);
		add("metab", "gene = pace 1", Drift.MULT, 0, INF, false,
				g -> g.metabolism, (g, v) -> g.metabolism = v);
		add("maxAge", "ticks", Drift.MULT, 1, INF, true,
				g -> g.maxAge, (g, v) -> g.maxAge = (int) v);
		add("flying", "", Drift.NONE, 0, 1, true,
				g -> g.flying ? 1 : 0, (g, v) -> g.flying = v != 0);
		for (int i = 0; i < Genome.MARKER_DIMS; i++) {
			final int idx = i;
			add("m" + i, "marker", Drift.ADD, 0, 1, false,
					g -> g.markers[idx], (g, v) -> g.markers[idx] = v);
		}
		add("pred", "", Drift.ADD, 0, INF, false,
				g -> g.predatory, (g, v) -> g.predatory = v);
		add("xeno", "", Drift.ADD, 0, INF, false,
				g -> g.xenophobia, (g, v) -> g.xenophobia = v);
		add("greg", "", Drift.ADD, 0, INF, false,
				g -> g.gregariousness, (g, v) -> g.gregariousness = v);
		add("bold", "", Drift.ADD, 0, INF, false,
				g -> g.boldness, (g, v) -> g.boldness = v);
		add("mate", "similarity", Drift.ADD, 0, 1, false,
				g -> g.mateThreshold, (g, v) -> g.mateThreshold = v);
		add("greed", "value exponent", Drift.ADD, 0, Genome.GREED_MAX, false,
				g -> g.greed, (g, v) -> g.greed = v);
		add("det", "x incumbent", Drift.ADD, 1, Genome.DETERMINATION_MAX, false,
				g -> g.determination, (g, v) -> g.determination = v);
	}

	/** The genes, in declaration (and mutation-draw) order. */
	public static List<Gene> genes() {
		return GENES;
	}

	/**
	 * Mutates every gene of {@code g} in place, drawing one seeded random per
	 * drifting gene in declaration order. The single point where a genome's
	 * magnitudes move; the drift mode and bounds come from the gene, never the
	 * call site.
	 */
	public static void mutate(Genome g, double rate) {
		for (Gene gene : GENES) {
			switch (gene.drift) {
			case MULT -> gene.set(g, gene.get(g) * (1 + jitter(rate)));
			case ADD -> gene.set(g, gene.get(g) + jitter(rate));
			case NONE -> { /* inherited exactly: no draw, no change */ }
			}
		}
	}

	/**
	 * Per-gene crossover of two parents into {@code child}, drawing one coin per
	 * drifting gene. A {@link Drift#NONE} gene is taken from {@code a} without a
	 * draw — locomotion is a fact about a lineage, and a pair shares it. Does not
	 * mutate; the caller does, so crossover-then-mutate stays one decision.
	 */
	public static void crossover(Genome child, Genome a, Genome b) {
		for (Gene gene : GENES) {
			if (gene.drift == Drift.NONE) {
				gene.set(child, gene.get(a));
			} else {
				gene.set(child, Utils.random() < 0.5 ? gene.get(a) : gene.get(b));
			}
		}
	}

	/** The genome as a flat vector in schema order — for gradient sweeps and
	 *  mechanical perturbation, where naming a gene is exactly what the caller
	 *  wants to avoid. */
	public static double[] values(Genome g) {
		double[] v = new double[GENES.size()];
		for (int i = 0; i < v.length; i++) {
			v[i] = GENES.get(i).get(g);
		}
		return v;
	}

	/** Writes a schema-order vector back onto a genome, clamping each gene into
	 *  its bounds. The inverse of {@link #values(Genome)}. */
	public static void apply(Genome g, double[] v) {
		int n = Math.min(v.length, GENES.size());
		for (int i = 0; i < n; i++) {
			GENES.get(i).set(g, v[i]);
		}
	}

	private static double jitter(double rate) {
		return (Utils.random() * 2 - 1) * rate;
	}

	private GeneSchema() {
	}
}
