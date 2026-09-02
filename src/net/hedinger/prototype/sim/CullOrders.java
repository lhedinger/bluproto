package net.hedinger.prototype.sim;

/**
 * The standing order the {@link WorldSteward} publishes when a population has
 * outgrown its ceiling, and the whole of what the {@link StewardDrone} is told.
 *
 * <p>The steward used to do the thinning itself — {@code trim()} deleted the
 * excess where it stood, which is correct and invisible, and invisible is the
 * problem: the one moment the warden actually intervenes in the world looked
 * exactly like nothing happening. Splitting the decision from the act gives the
 * intervention a body that has to fly there, and it costs the steward nothing:
 * it still counts every tick and still decides, it simply issues the order
 * instead of carrying it out.
 *
 * <p>Deliberately two scalars and no more. The order names a cohort and the
 * headcount the world should be left with — not which animals, not in what
 * sequence, not by when. Choosing the individuals is the drone's business
 * (it kills what it can reach), and keeping score is the steward's (it
 * recounts every tick and drops the order the moment the target is met). Any
 * richer contract would be one of them doing the other's job.
 */
public interface CullOrders {

	/** The cohort to thin — a clade's wire name (see
	 *  {@link net.hedinger.prototype.entities.Genome.Clade#wireName}; "minded"
	 *  is long gone as a cohort of its own) — or null when nothing
	 *  is flagged and the drone should be on standby. */
	String cullRole();

	/** How many of that cohort the world should be left with. Meaningless
	 *  while {@link #cullRole()} is null. */
	int cullTarget();
}
