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
		int rgb,
		int flags,
		int attachedTo,
		double aux,
		long pheno) {

	public static final int F_DEAD = 1;
	public static final int F_FLYING = 2;
	public static final int F_GRABBED = 4;
	public static final int F_CARRYING = 8;

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
		} else if (e instanceof NPC n) {
			kind = "npc." + n.getNpcTypeName().toLowerCase();
			rgb = n.getColor().getRGB() & 0xFFFFFF;
			aux = n.getEnergy();
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
		int attachedTo = e.getAttachTarget() == null ? -1 : e.getAttachTarget().getID();
		return new EntityState(e.getID(), kind, e.getX(), e.getY(), e.getZ(),
				e.getDirection(), e.getSize(), rgb, flags, attachedTo, aux, pheno);
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
		h = h * 31 + rgb;
		h = h * 31 + flags;
		h = h * 31 + attachedTo;
		h = h * 31 + Double.doubleToLongBits(aux);
		h = h * 31 + pheno;
		return h;
	}
}
