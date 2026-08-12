package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Utils;

import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;

public class Sound extends Entity {
	private double radius = 5;

	private TreeMap<Double, Entity> entities = new TreeMap<Double, Entity>();
	private String[] ignoreTypes = { getEntityTypeName(), "Entity.Bullet", "Entity.Grenade" };

	private int code = 0;

	public Sound(double x, double y, double z) {

		D = Utils.random() * 2 * Math.PI;
		X = x;
		Y = y;
		Z = z;

		lifespan = 20;
		deathspan = 2048;
	}

	@Override
	protected void think() {
		if (age >= lifespan) {
			entities = getWorld().searchEntity(X, Y, Z, D, radius, Math.PI * 2,
					ignoreTypes, false, getID());

			if (entities != null) {
				if (!entities.isEmpty()) {
					for (Entity e : entities.values()) {
						if (e != null) {
							e.hear(this);
						}
					}
				}
			}
		}
	}

	/** Ring radius in tiles, for the painter's spreading-circle sweep. */
	public double getRadius() {
		return radius;
	}

	@Override
	public String getEntityTypeName() {
		return "Bullet";
	}

}
