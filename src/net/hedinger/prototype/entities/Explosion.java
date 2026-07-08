package net.hedinger.prototype.entities;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.View;

public class Explosion extends Entity {
	private static double EXPLOSION_RADIUS = 2;
	private static int EXPLOSION_DMG = 200;

	private TreeMap<Double, Entity> entities = new TreeMap<Double, Entity>();
	private String[] ignoreTypes = { getEntityTypeName(), "Entity.Bullet", "Entity.Grenade",
			"Entity.Sound" };

	public Explosion(double x, double y, double z) {

		D = Math.random() * 2 * Math.PI;
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
							e.damage(round(EXPLOSION_DMG * ratioInv(d, EXPLOSION_RADIUS)));
						}
					}
				}
			}
		}
	}

	@Override
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;

		double temp = age;
		temp = temp / lifespan;

		temp = temp * EXPLOSION_RADIUS;

		g2.setColor(Color.YELLOW);
		g2.setStroke(new BasicStroke(4));
		g2.drawOval(
				pixelX(v, toPixel(v, temp) * 0.5),
				pixelY(v, toPixel(v, temp) * 0.5),
				toPixel(v, temp),
				toPixel(v, temp));
	}

	protected void draw_dead(Graphics g) {
		/*
		 * Graphics2D g2 = (Graphics2D) g;
		 *
		 * for (int i = 1; i <= 20; i++) { double s = ratio(i, 20) *
		 * EXPLOSION_RADIUS;
		 *
		 * s = Math.abs(s * ratioInv(age, deathspan));
		 *
		 * g2.setColor(new Color(0, 0, 0, 10)); g2.fillOval(pixelX(g, toPixel(g,
		 * s) * 0.5), pixelY(g, toPixel(g, s) * 0.5), toPixel(g, s), toPixel(g,
		 * s)); }
		 */
	}

	@Override
	public String getEntityTypeName() {
		return "Explosion";
	}

}
