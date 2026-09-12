package net.hedinger.prototype.simtest;

import net.hedinger.prototype.entities.Genome;

/**
 * A clade's ecosystem card: the data that makes a clade a way of making a
 * living rather than just a label.
 *
 * <p>{@link Genome.Clade} is the identity — the frozen wire code, the body
 * plan, the mate barrier — and it lives in the engine's {@code entities} layer
 * because a saved genome carries it. The <em>niche</em> is everything the
 * ecosystem does with that identity: what a body of this clade eats, how it
 * finds it, what it does on reaching it, how fast it ranges and what that
 * costs, and the size its living depends on. That is ecosystem knowledge, so it
 * lives here in {@code simtest} beside the bodies it governs, keyed off the
 * identity enum rather than mixed into it.
 *
 * <p>Before this, those facts were a scatter of {@code clade == PREDATOR}
 * branches through the body: the forage sense re-aimed in one place, the
 * terminal act chosen in another, the intake gated in a third, the stride
 * stamped in a builder, the size clamp in yet another. One card per clade puts
 * them together, so a body asks its niche a question instead of testing its
 * clade, and a fifth clade is a new card rather than a hunt through the source
 * for every place a clade is named.
 */
public enum Niche {

	/** Grazes the living substrate; no size constraint, ordinary locomotion. */
	HERBIVORE(Diet.GRAZE, 1.0, 1.0, 0, Double.POSITIVE_INFINITY, 1.0),
	/** Kills what it eats; floored large enough that the herd is on its menu, and
	 *  takes quarry up to {@link TestNPC#PRED_MAX_PREY_RATIO} times its own size. */
	PREDATOR(Diet.HUNT, 1.0, 1.0, TestNPC.PREDATOR_MIN_SIZE_PX, Double.POSITIVE_INFINITY,
			TestNPC.PRED_MAX_PREY_RATIO),
	/** Eats the dead; ranges far and cheap — the vulture's living is distance
	 *  covered, not speed, so a big stride bought at a discounted travel bill. */
	SCAVENGER(Diet.SCAVENGE, TestNPC.SCAVENGER_STRIDE, TestNPC.SCAVENGER_TRAVEL, 0,
			Double.POSITIVE_INFINITY, 1.0),
	/** Drinks a living body; capped small so it stays under its hosts and grips
	 *  them too tightly to buck. */
	PARASITE(Diet.DRAIN, 1.0, 1.0, 0, TestNPC.PARASITE_MAX_SIZE_PX, 1.0);

	/** What a clade turns into food, which decides how it forages and what it does
	 *  on arrival — the one axis the diet branches were really testing. */
	public enum Diet {
		/** Vegetation underfoot; arriving at a patch grazes it. */
		GRAZE,
		/** A smaller living body; arriving bites it. */
		HUNT,
		/** A carcass; arriving eats it. */
		SCAVENGE,
		/** A bigger living body; arriving latches on and drains it over time. */
		DRAIN,
	}

	private final Diet diet;
	private final double strideFactor;
	private final double travelFactor;
	private final double sizeFloorPx;
	private final double sizeCapPx;
	private final double preySizeRatio;

	Niche(Diet diet, double strideFactor, double travelFactor,
			double sizeFloorPx, double sizeCapPx, double preySizeRatio) {
		this.diet = diet;
		this.strideFactor = strideFactor;
		this.travelFactor = travelFactor;
		this.sizeFloorPx = sizeFloorPx;
		this.sizeCapPx = sizeCapPx;
		this.preySizeRatio = preySizeRatio;
	}

	/** The niche for a clade. A {@code null} clade (a roleless fixture) grazes,
	 *  the harmless default. Ordinals track {@link Genome.Clade}'s, but this reads
	 *  by name so a reorder of either enum cannot silently mismap. */
	public static Niche of(Genome.Clade clade) {
		if (clade == null) {
			return HERBIVORE;
		}
		return switch (clade) {
			case HERBIVORE -> HERBIVORE;
			case PREDATOR -> PREDATOR;
			case SCAVENGER -> SCAVENGER;
			case PARASITE -> PARASITE;
		};
	}

	public Diet diet() {
		return diet;
	}

	public boolean grazes() {
		return diet == Diet.GRAZE;
	}

	public boolean hunts() {
		return diet == Diet.HUNT;
	}

	public boolean scavenges() {
		return diet == Diet.SCAVENGE;
	}

	public boolean drains() {
		return diet == Diet.DRAIN;
	}

	/** Whether a body of this clade is valid quarry to a hunter. A parasite is
	 *  not — too small and too foul to be worth a bite — which is the one clade
	 *  nothing preys on. (A hunter sparing its own kind is a separate rule, the
	 *  cannibalism gate, because it lifts under starvation.) */
	public boolean isHuntable() {
		return diet != Diet.DRAIN;
	}

	/** How fast a body of this clade moves relative to its speed gene. */
	public double strideFactor() {
		return strideFactor;
	}

	/** What a body of this clade pays to cover ground, as a share of an ordinary
	 *  body's travel bill. */
	public double travelFactor() {
		return travelFactor;
	}

	/** How far above its own size this clade takes quarry: {@code 1} is nothing
	 *  bigger than itself, a hunter's is more. Multiplies the body's size to give
	 *  the ceiling on what counts as prey (and, above it, as a threat). */
	public double preySizeRatio() {
		return preySizeRatio;
	}

	/** The adult body size a body of this clade grows to for a given size gene:
	 *  the gene bounded by the clade's physical floor and cap. Imposed at every
	 *  expression, so the invariant holds for the whole lineage rather than only
	 *  the founder whose genome was once clamped. */
	public double expressedSize(double genomeSizePx) {
		double s = Math.max(genomeSizePx, sizeFloorPx);
		return Math.min(s, sizeCapPx);
	}
}
