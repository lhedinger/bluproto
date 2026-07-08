package net.hedinger.prototype.entities;

/**
 * Heritable traits for ecosystem species. Offspring receive a per-gene random
 * mix of both parents, then mutate — so populations drift under selection
 * pressure over long runs (faster prey where predators are thick, thriftier
 * metabolisms where food is scarce).
 */
public class Genome {

	/** max movement speed (tiles per tick) */
	public double speed;
	/** turn divisor; lower turns faster */
	public double turn;
	/** how far the animal can see in full daylight (tiles) */
	public double losRange;
	/** baseline energy drain per tick */
	public double metabolism;
	/** relative body size; scales biomass, birth size and drawn size */
	public double sizeScale;
	/** pregnancy duration (ticks) */
	public double gestation;
	/** 0..1 night vision: 1 sees as well at night as by day */
	public double nocturnality;

	public Genome(double speed, double turn, double losRange, double metabolism,
			double sizeScale, double gestation, double nocturnality) {
		this.speed = speed;
		this.turn = turn;
		this.losRange = losRange;
		this.metabolism = metabolism;
		this.sizeScale = sizeScale;
		this.gestation = gestation;
		this.nocturnality = nocturnality;
	}

	public Genome(Genome g) {
		this(g.speed, g.turn, g.losRange, g.metabolism, g.sizeScale, g.gestation, g.nocturnality);
	}

	/**
	 * Sexual reproduction: each gene comes from a random parent, then drifts
	 * by up to +-rate.
	 */
	public Genome breed(Genome partner, double rate) {
		Genome p = (partner != null) ? partner : this;
		Genome child = new Genome(
				pick(speed, p.speed),
				pick(turn, p.turn),
				pick(losRange, p.losRange),
				pick(metabolism, p.metabolism),
				pick(sizeScale, p.sizeScale),
				pick(gestation, p.gestation),
				pick(nocturnality, p.nocturnality));
		child.mutate(rate);
		return child;
	}

	private static double pick(double a, double b) {
		return (Math.random() < 0.5) ? a : b;
	}

	private void mutate(double rate) {
		speed = drift(speed, rate);
		turn = drift(turn, rate);
		losRange = drift(losRange, rate);
		metabolism = drift(metabolism, rate);
		sizeScale = clamp(drift(sizeScale, rate), 0.4, 2.5);
		gestation = drift(gestation, rate);
		nocturnality = clamp(drift(nocturnality, rate), 0, 1);
	}

	private static double drift(double gene, double rate) {
		return gene * (1 + (Math.random() * 2 - 1) * rate);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
