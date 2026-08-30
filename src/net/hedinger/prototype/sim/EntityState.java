package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.PheromoneCloud;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.entities.NPC;

/**
 * One entity's state as seen through the observation seam: everything a viewer
 * needs to draw and inspect it, nothing it could use to mutate the world. This
 * is the wire-facing shape — flat, primitive, and stable — so the transport
 * encoding (JSON today, binary later) can change without touching the engine.
 *
 * <p>{@code kind} is a dotted lowercase tag ("npc.testnpc", "item.crate",
 * "phero") chosen for the client's benefit: it selects the glyph, not the Java
 * type. {@code aux} is the kind's one interesting scalar — a creature's energy,
 * a cloud's strength, an item's durability.
 */
public record EntityState(
		int id,
		String kind,
		double x,
		double y,
		double z,
		double dir,
		float size,
		/** Adult body this creature is still growing toward at the fixed
		 *  {@link NPC#GROWTH_RATE}, or 0 when it is not growing (fully grown,
		 *  dead, or a kind that never grows). Growth is deterministic, so a
		 *  viewer given (size, sizeMax, rate) at one tick can extrapolate the
		 *  body itself — the binary stream ships size only on birth records. */
		float sizeMax,
		int rgb,
		int flags,
		int attachedTo,
		double aux,
		long pheno) {

	public static final int F_DEAD = 1;
	public static final int F_FLYING = 2;
	public static final int F_GRABBED = 4;
	public static final int F_CARRYING = 8;
	/** Driven by an evolvable mind (the hybrid cohort), not a hardcoded behaviour --
	 *  the viewer marks these apart so they can be watched competing. */
	public static final int F_MINDED = 16;

	// What the creature is visibly doing, packed into the spare high bits of
	// `flags` rather than added as a field: the viewer draws a small badge above
	// the body, and riding along in an int already on the wire costs the delta
	// stream nothing. 0 means "nothing worth showing" -- most creatures, most of
	// the time, are just walking around.
	public static final int ACTION_SHIFT = 5;
	public static final int ACTION_MASK = 0xF << ACTION_SHIFT;
	public static final int ACT_NONE = 0, ACT_ATTACK = 1, ACT_MATE = 2, ACT_FLEE = 3,
			ACT_GRAZE = 4, ACT_HUNT = 5, ACT_CARRY = 6, ACT_NEST = 7, ACT_AFFILIATE = 8,
			ACT_RIDE = 9;
	/** The highest defined action code. A viewer has a glyph for every code up to
	 *  this and nothing beyond it, so it is what "is this drawable" is checked
	 *  against — naming the last constant instead meant the check had to be edited
	 *  every time an act was added, which is the moment it is least likely to be. */
	public static final int ACT_MAX = ACT_RIDE;

	/** Wire code for a glyph key from {@code TestNPC.actionKey()}. */
	private static int actionCode(String key) {
		if (key == null) {
			return ACT_NONE;
		}
		switch (key) {
		case "attack":
			return ACT_ATTACK;
		case "mate":
			return ACT_MATE;
		case "flee":
			return ACT_FLEE;
		case "graze":
			return ACT_GRAZE;
		case "hunt":
			return ACT_HUNT;
		case "carry":
			return ACT_CARRY;
		case "ride":
			return ACT_RIDE;
		case "nest":
			return ACT_NEST;
		case "affiliate":
			return ACT_AFFILIATE;
		default:
			return ACT_NONE;
		}
	}

	/** Captures one entity. Read-only: must never mutate {@code e}. */
	static EntityState of(Entity e) {
		String kind;
		int rgb = 0xFFFFFF;
		double aux = 0;
		long pheno = 0;
		if (e instanceof PheromoneCloud p) {
			kind = "phero";
			rgb = 0xE628BE;
			aux = p.getStrength();
		} else if (e instanceof Item it) {
			kind = "item." + it.getKind().name().toLowerCase();
			rgb = it.getColor().getRGB() & 0xFFFFFF;
			aux = it.getHealth();
		} else if (e instanceof net.hedinger.prototype.entities.Door dr) {
			// A door is furniture, not fauna: the tag picks the material glyph,
			// aux carries how far the leaves reach (1 sealed .. 0.15 open), and
			// `size` below ships the doorway span in tiles.
			kind = "door." + dr.flavorName();
			rgb = dr.wireColor();
			aux = dr.extension();
		} else if (e instanceof net.hedinger.prototype.entities.Switch sw) {
			// A switch: the tag names its mode (weight plate vs intent button),
			// aux is its pressed state, and pheno (unused by furniture) carries
			// the wired door's entity id, so the viewer can draw the indicator
			// trail from switch to door.
			kind = sw.getMode() == net.hedinger.prototype.entities.Switch.BUTTON
					? "switch.button" : "switch.plate";
			rgb = 0xE0455F;
			aux = sw.isPressed() ? 1 : 0;
			pheno = sw.getDoor().getID();
		} else if (e instanceof net.hedinger.prototype.entities.Nest nest) {
			// A nest is a place, not a body: aux carries how many broods this
			// site has raised, so the viewer can size its ring by history.
			kind = "nest";
			rgb = 0x8a6a3c;
			aux = nest.getBroods();
		} else if (e instanceof net.hedinger.prototype.entities.Sound snd) {
			// A sound in flight is an event, not a body: the tag lets the
			// viewer's sense overlay draw the wavefront, aux is how far along
			// the travel it is (0 just made .. 1 reaching its listeners), and
			// `size` below carries its earshot in tiles.
			kind = "sound";
			rgb = 0xF2B84B;
			aux = snd.travelProgress();
		} else if (e instanceof NPC n) {
			kind = "npc." + n.getNpcTypeName().toLowerCase();
			rgb = n.getColor().getRGB() & 0xFFFFFF;
			// Energy is the live body's one interesting scalar; a corpse has no use
			// for it, so once dead the same slot carries how far it has rotted
			// (0 just died .. 1 gone). The viewer needs that to draw decay at all --
			// it renders creatures from an atlas of idle frames and cannot play the
			// engine's death action. F_DEAD says which reading applies.
			aux = e.isDead() ? n.decayProgress() : n.getEnergy();
			if (n.getGenome() != null) {
				pheno = PhenoRegistry.register(n.getGenome()); // procedural body: name its atlas
			}
		} else {
			kind = "entity";
		}
		int flags = 0;
		if (e.isDead()) {
			flags |= F_DEAD;
		}
		if (e.isFlying()) {
			flags |= F_FLYING;
		}
		if (e.isGrabbed()) {
			flags |= F_GRABBED;
		}
		if (e.getCarriedLoad() > 0) {
			flags |= F_CARRYING;
		}
		if (e instanceof net.hedinger.prototype.simtest.TestNPC t) {
			if (t.isMinded()) {
				flags |= F_MINDED;
			}
			flags |= actionCode(t.actionKey()) << ACTION_SHIFT;
		}
		int attachedTo = e.getAttachTarget() == null ? -1 : e.getAttachTarget().getID();
		// For a door, `size` is its doorway span in tiles; the raw entity size
		// field stays zero so nothing in the sim mistakes a door for a body.
		float size = e instanceof net.hedinger.prototype.entities.Door dr
				? dr.getSpan()
				: e instanceof net.hedinger.prototype.entities.Sound snd
				? (float) snd.getRadius() : e.getSize();
		float sizeMax = !e.isDead() && e instanceof NPC np ? (float) np.getGrowthTarget() : 0;
		return new EntityState(e.getID(), kind, e.getX(), e.getY(), e.getZ(),
				e.getDirection(), size, sizeMax, rgb, flags, attachedTo, aux, pheno);
	}

	/** Order-sensitive bit-exact fold of every field, for determinism tests. */
	long checksum() {
		long h = id;
		h = h * 31 + kind.hashCode();
		h = h * 31 + Double.doubleToLongBits(x);
		h = h * 31 + Double.doubleToLongBits(y);
		h = h * 31 + Double.doubleToLongBits(z);
		h = h * 31 + Double.doubleToLongBits(dir);
		h = h * 31 + Float.floatToIntBits(size);
		h = h * 31 + Float.floatToIntBits(sizeMax);
		h = h * 31 + rgb;
		h = h * 31 + flags;
		h = h * 31 + attachedTo;
		h = h * 31 + Double.doubleToLongBits(aux);
		h = h * 31 + pheno;
		return h;
	}
}
