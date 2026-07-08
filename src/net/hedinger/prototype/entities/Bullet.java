package net.hedinger.prototype.entities;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.View;

public class Bullet extends Entity {

	private double velocity = 0; // determines damage done and appearance
	private double accuracy = 1; // 0 = worst, 1 = perfect

	private TreeMap<Double, Entity> entities = new TreeMap<Double, Entity>();
	private String[] ignoreTypes = { getEntityTypeName(), "Entity.Weapon", "Entity.Grenade",
	"Entity.Explosion", "Entity.Sound" };
	private int length = 10;

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
						if (Math.random() < accuracy) {
							e.kill();
						} else {
							e.damage((int) variation(50, 25));
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
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;

		g2.setColor(Color.WHITE);
		g2.setStroke(new BasicStroke(1));
		g2.drawLine(
				pixelX(v, 0),
				pixelY(v, 0),
				pixelX(v, length * Math.cos(D)),
				pixelY(v, length * Math.sin(D)));
	}

	@Override
	public String getEntityTypeName() {
		return "Bullet";
	}

	@Override
	public void kill() {
		age = -1;
		remove = true;
	}

}
