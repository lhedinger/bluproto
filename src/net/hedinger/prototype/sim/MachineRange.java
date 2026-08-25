package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.Entity;

/**
 * How the facility's machines measure, as {@code MachineStamp} is how they are
 * drawn: one rule about floors, in one place, for the drone and the loader
 * both.
 *
 * <p>Extracted rather than copied for the reason the render side already
 * records — a rule that exists twice is a rule that will shortly exist in two
 * versions — and this one had already been got wrong twice independently.
 */
final class MachineRange {

	/**
	 * Distance for CHOOSING work: the plane, plus a whole world for every floor
	 * in between.
	 *
	 * <p>{@code World.distance} is a straight line through all three axes and
	 * counts one level as one tile. That is the right reading for a world where
	 * a level is a height and the wrong one for picking what to go and do: a
	 * body directly underneath scores one tile away, nearer than something four
	 * tiles off across the same room, while actually getting to it means finding
	 * the stairwell, crossing the storage hall and coming back. A machine
	 * choosing that way sets off after the near thing and spends its shift
	 * travelling.
	 *
	 * <p>The penalty is the world's own width plus its height, which is longer
	 * than any distance within a single floor. That is not a large number picked
	 * for being large: it makes "on my floor beats off my floor" true by
	 * construction rather than true for the map sizes somebody happened to try,
	 * and it still orders the off-floor candidates sensibly among themselves
	 * when another floor is all that is left.
	 */
	static double toChooseBy(Entity self, Entity other) {
		double dz = Math.abs(other.getZ() - self.getZ());
		double flat = Math.hypot(other.getX() - self.getX(), other.getY() - self.getY());
		return flat + dz * (self.getWorld().getColums() + self.getWorld().getRows());
	}

	private MachineRange() {
	}
}
