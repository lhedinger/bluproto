package net.hedinger.prototype.entities;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.View;

public class Sound extends Entity {
	private double radius = 5;

	private TreeMap<Double, Entity> entities = new TreeMap<Double, Entity>();
	private String[] ignoreTypes = { getEntityTypeName(), "Entity.Bullet", "Entity.Grenade" };

	private int code = 0;

	public Sound(double x, double y, double z) {

		D = Math.random() * 2 * Math.PI;
		X = x;
		Y = y;
		Z = z;

		lifespan = 20;
		deathspan = 2048;
	}

	/**
	 * @param radius
	 *            how far the sound carries (tiles); default is 5
	 */
	public Sound(double x, double y, double z, double radius) {
		this(x, y, z);
		this.radius = radius;
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

	@Override
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;

		double temp = age;
		temp = temp / lifespan;

		temp = temp * radius;

		g2.setColor(Color.GRAY);
		g2.setStroke(new BasicStroke(2));
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
		return "Sound";
	}

}
