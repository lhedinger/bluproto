package net.hedinger.prototype.entities;

import java.util.Locale;

/**
 * A species: a named region of marker space within a clade.
 *
 * <p><b>Species are emergent here, and named lazily.</b> Nothing in the genome
 * stores one. Markers drift continuously under mutation and
 * {@link Genome#mateThreshold} turns that drift into real reproductive
 * isolation, so speciation genuinely happens on its own — a discrete tag would
 * have had to be assigned by something, which would make speciation an authored
 * event rather than an evolved one.
 *
 * <p>What this class adds is a <em>label</em>, derived at the observation layer
 * only: the nearest of a fixed set of centroids in marker space. The simulation
 * never reads it and no decision is taken on it. That keeps the ecology
 * continuous while giving a viewer, a legend and a census something discrete to
 * count and name.
 *
 * <p>The centroids are fixed rather than fitted, deliberately. A clustering that
 * moved with the population would rename species as the world evolved, so the
 * same creature would change species without changing at all, and two runs of
 * one seed could disagree. Fixed centroids make the label a pure function of
 * (clade, markers) — deterministic, replay-safe, and stable across restarts.
 *
 * <p>The tint here is the species' own, for legends and labels. It is <b>not</b>
 * what a body is drawn in: a creature renders in its individual marker colour so
 * that drift stays visible in the world, and a species reads as a family of
 * neighbouring shades rather than one flat swatch.
 */
public final class Species {

	/** Species per clade. Six is enough to name the space without the labels
	 *  becoming finer than a viewer can tell apart. */
	public static final int PER_CLADE = 6;

	/**
	 * Centroids in marker space, one row per species, shared by every clade.
	 *
	 * <p>Spread across the unit cube so the nearest-centroid boundaries fall as
	 * far as possible from where lineages actually sit: a creature has to drift a
	 * long way to be relabelled, which is what makes the label steady enough to
	 * put in a legend.
	 */
	private static final double[][] CENTROIDS = {
		{ 0.15, 0.15, 0.15 },
		{ 0.85, 0.20, 0.20 },
		{ 0.20, 0.85, 0.25 },
		{ 0.25, 0.30, 0.85 },
		{ 0.80, 0.80, 0.35 },
		{ 0.50, 0.55, 0.60 },
	};

	/** Names, indexed to match {@link #CENTROIDS}. Deliberately plain: a species
	 *  name is a handle for a viewer to say "the ochre ones are gone", not a
	 *  taxonomy. */
	private static final String[] NAMES = {
		"umbral", "vermil", "verdant", "cobalt", "ochre", "dusken",
	};

	private final Genome.Clade clade;
	private final int index;

	private Species(Genome.Clade clade, int index) {
		this.clade = clade;
		this.index = index;
	}

	/** The species this genome falls in: nearest centroid, within its clade. */
	public static Species of(Genome g) {
		if (g == null) {
			return new Species(Genome.Clade.HERBIVORE, 0);
		}
		int best = 0;
		double bestD = Double.MAX_VALUE;
		for (int i = 0; i < CENTROIDS.length; i++) {
			double d = 0;
			for (int k = 0; k < Genome.MARKER_DIMS && k < CENTROIDS[i].length; k++) {
				double delta = g.markers[k] - CENTROIDS[i][k];
				d += delta * delta;
			}
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return new Species(g.clade, best);
	}

	public Genome.Clade clade() {
		return clade;
	}

	/** Index within the clade, 0..{@link #PER_CLADE}-1. */
	public int index() {
		return index;
	}

	/** Short name, unique within the clade ("ochre"). */
	public String name() {
		return NAMES[index];
	}

	/** Full name, unique in the world ("ochre herbivore") — what a legend shows. */
	public String fullName() {
		return NAMES[index] + " " + clade.wireName();
	}

	/** A stable key for grouping and counting ("herbivore/ochre"). */
	public String key() {
		return clade.wireName() + "/" + NAMES[index];
	}

	/** The species' own tint, as 0xRRGGBB — its centroid rendered as a colour, so
	 *  a legend swatch sits in the middle of the shades its members wear. For
	 *  labels and legends only; a body is drawn in its individual marker colour. */
	public int rgb() {
		double[] c = CENTROIDS[index];
		int r = (int) Math.round(clamp(c[0]) * 255);
		int g = (int) Math.round(clamp(c[1]) * 255);
		int b = (int) Math.round(clamp(c.length > 2 ? c[2] : 0.5) * 255);
		return (r << 16) | (g << 8) | b;
	}

	private static double clamp(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	@Override
	public String toString() {
		return fullName().toLowerCase(Locale.ROOT);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Species s && s.clade == clade && s.index == index;
	}

	@Override
	public int hashCode() {
		return clade.hashCode() * 31 + index;
	}
}
