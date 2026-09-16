package net.hedinger.prototype.entities;

import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;

public class Sound extends Entity {
	/**
	 * What kind of event this sound is — the one fact a listener gets beyond
	 * where and when. The codes are frozen wire values (they ride the entity
	 * stream and reach saved-brain sensors, so renumbering would reinterpret
	 * both): {@link #PLAIN} says nothing, {@link #FIGHT} is a landed bite the
	 * quarry survived — violence in progress, a warning — and {@link #KILL}
	 * is a death, which means a carcass now exists where the scream was.
	 */
	public static final int PLAIN = 0, FIGHT = 1, KILL = 2;

	/** Earshot of a plain sound, in tiles. */
	public static final double DEFAULT_RADIUS = 5;
	/** Ticks between a sound being made and it reaching its listeners. A sound is
	 *  broadcast ONCE, on the tick its age reaches this, and is then gone — so it
	 *  is an event with a moment attached, not a field that lingers like a smell. */
	public static final int TRAVEL_TICKS = 20;

	private double radius = DEFAULT_RADIUS;

	private TreeMap<Double, Entity> entities = new TreeMap<Double, Entity>();

	private int code = 0;

	public Sound(double x, double y, double z) {
		this(x, y, z, DEFAULT_RADIUS);
	}

	/**
	 * A sound with a chosen earshot, so a loud event carries further than a quiet
	 * one.
	 *
	 * <p>Draws no RNG. A sound has no heading worth randomising, and the eco
	 * simulation makes one every time a bite lands — drawing per scream would put
	 * the deterministic actor stream at the mercy of how much violence happened
	 * this tick, which is exactly the coupling the seed-plus-command-log replay
	 * depends on not existing.
	 */
	public Sound(double x, double y, double z, double radius) {
		this(x, y, z, radius, PLAIN);
	}

	/** A typed sound: the same event with {@link #PLAIN}/{@link #FIGHT}/
	 *  {@link #KILL} attached, so a listener knows WHAT it heard. */
	public Sound(double x, double y, double z, double radius, int code) {
		super(x, y, z, 0.0); // direction-taking ctor: no RNG draw
		this.radius = radius;
		this.code = code;
		lifespan = TRAVEL_TICKS;
		// Gone the moment it has been heard, like the other one-shot ephemeral:
		// Bullet sets this to 0 too. It used to be 2048 -- a hundred times the
		// twenty ticks a sound is useful for -- so a sound spent 0.6s travelling
		// and then sat in the world as a corpse for the next 62 seconds. Nothing
		// reads a spent sound: the broadcast below happens once, at age 20, and
		// the branch cannot run again because age goes negative at death; the
		// viewer's sense overlay draws live sounds only and keeps its own 2.2s
		// echo, keyed by id and decaying on its own clock, for exactly the case
		// where the source is already gone. What the deathspan bought was a
		// stream in which 99% of the sounds were spent ones -- around 40% of
		// every entity the viewer tracked -- each of them a dead entity that,
		// per Entity.think, had died of old age.
		deathspan = 0;
	}

	@Override
	protected void think() {
		if (age >= lifespan) {
			// Deliberately NOT searchEntity: that is a sight query and applies
			// line of sight, which would stop a scream at the first wall and
			// reduce hearing to short-range omnidirectional sight.
			entities = getWorld().entitiesWithin(X, Y, Z, radius, getID());

			if (entities != null) {
				for (Entity e : entities.values()) {
					// Everything in earshot is told, except other sounds: the radial
					// query is untyped (it has to be, so nothing is hidden by a wall)
					// and a sound has no use for what it can hear.
					if (e != null && !(e instanceof Sound)) {
						e.hear(this);
					}
				}
			}
		}
	}

	/** Ring radius in tiles, for the painter's spreading-circle sweep. */
	public double getRadius() {
		return radius;
	}

	/** The event code — {@link #PLAIN}, {@link #FIGHT} or {@link #KILL}. */
	public int getCode() {
		return code;
	}

	/** How far along its travel this sound is, 0 (just made) .. 1 (reaching
	 *  its listeners) — read by the observation seam so a viewer's overlay
	 *  can draw the wavefront spreading toward earshot. */
	public double travelProgress() {
		return Math.min(1.0, age / (double) TRAVEL_TICKS);
	}

	/**
	 * A sound is a Sound. It answered "Bullet" -- Sound was written from the
	 * projectile family and this came across with the rest of it -- and that
	 * string is what {@link net.hedinger.prototype.engine.World#filterType}
	 * dispatches on, so every type query in the world read a scream as a round
	 * of ammunition. It happened to be harmless, for one reason: the only typed
	 * searches are a bullet's and an explosion's, both of them EXCLUDE lists,
	 * and both already excluded bullets. Sounds were therefore skipped by
	 * accident, under the wrong name, and the ignore lists now say so on
	 * purpose.
	 */
	@Override
	public String getEntityTypeName() {
		return "Sound";
	}

}
