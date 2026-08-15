package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Utils;

import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;

public class Bullet extends Entity {

	private double velocity = 0; // determines damage done and appearance
	private double accuracy = 1; // 0 = worst, 1 = perfect

	private TreeMap<Double, Entity> entities = new TreeMap<Double, Entity>();
	private String[] ignoreTypes = { getEntityTypeName(), "Entity.Weapon", "Entity.Grenade",
	"Entity.Explosion" };

	public Bullet(double x, double y, double z, double d) {

		D = d;
		X = x;
		Y = y;
		Z = z;

		velocity = 0.2;
		accuracy = 0.1;
		lifespan = 128;
		deathspan = 0;
	}

	public Bullet(Weapon wpn) {
		if (wpn == null) {
			System.out.println("NULL WEAPON");
			return;
		}

		D = variation(wpn.getDirection(), wpn.getBulletSpread());
		X = wpn.getX() + wpn.getBarrelLength() * Math.cos(D);
		Y = wpn.getY() + wpn.getBarrelLength() * Math.sin(D);
		Z = wpn.getZ();

		velocity = wpn.getBulletVelocity();
		accuracy = wpn.getBulletAccuracy();
		lifespan = wpn.getBulletSpan();
		deathspan = 0;
	}

	@Override
	protected void think() {
		dX = velocity * Math.cos(D);
		dY = velocity * Math.sin(D);

		entities = getWorld().searchEntity(X, Y, Z, D, velocity, Math.PI * 0.1, ignoreTypes, false,
				getID());

		if (isColliding()) {
			kill();
		}

		if (entities != null) {
			if (!entities.isEmpty()) {
				for (Entity e : entities.values()) {
					if (e != null) {
						if (Utils.random() < accuracy) {
							e.recordDeath("shot");
							e.kill();
						} else {
							e.damage((int) variation(50, 25), "shot");
						}
					}
				}
				dX = 0;
				dY = 0;
				kill();
			}
		}
	}

	@Override
	public String getEntityTypeName() {
		return "Bullet";
	}

	@Override
	public void kill() {
		age = -1;
		markRemoved();
	}

}
