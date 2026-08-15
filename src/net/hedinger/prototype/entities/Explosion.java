package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Utils;

import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;

public class Explosion extends Entity {
	/** Blast radius in tiles; the painter reads it for the ring sweep. */
	public static final double EXPLOSION_RADIUS = 2;
	private static int EXPLOSION_DMG = 200;

	private TreeMap<Double, Entity> entities = new TreeMap<Double, Entity>();
	private String[] ignoreTypes = { getEntityTypeName(), "Entity.Bullet", "Entity.Grenade" };

	public Explosion(double x, double y, double z) {

		D = Utils.random() * 2 * Math.PI;
		X = x;
		Y = y;
		Z = z;

		lifespan = 10;
		deathspan = 2048;
	}

	@Override
	protected void think() {
		if (age >= lifespan) {
			entities = getWorld().searchEntity(X, Y, Z, D, EXPLOSION_RADIUS, Math.PI * 2,
					ignoreTypes, false, getID());

			if (entities != null) {
				if (!entities.isEmpty()) {
					for (Entity e : entities.values()) {
						if (e != null) {
							double d = distance(e.getX(), e.getY(), e.getZ());
							e.damage(round(EXPLOSION_DMG * ratioInv(d, EXPLOSION_RADIUS)), "explosion");
						}
					}
				}
			}
		}
	}

	@Override
	public String getEntityTypeName() {
		return "Bullet";
	}

}
