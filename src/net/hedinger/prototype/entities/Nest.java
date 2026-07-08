package net.hedinger.prototype.entities;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.Scent;
import net.hedinger.prototype.engine.View;

/**
 * A home site built by an animal: sleeping spot, birthing site, and food
 * cache. Cached food rots slowly, and a well-stocked nest smells of food —
 * which is exactly what makes it worth raiding.
 */
public class Nest extends Entity {

	private double food = 0;
	private static final double FOOD_MAX = 120;
	private static final double ROT = 0.9997;

	public Nest(double x, double y, double z) {
		super(x, y, z);
		lifespan = -1;
		deathspan = 0;
	}

	@Override
	protected void think() {
		food *= ROT;

		if (food > 5 && getWorld() != null && getWorld().isValid(X, Y, Z)) {
			getWorld().getTile(X, Y, Z).addScent(Scent.FOOD, (float) (food * 0.005));
		}
	}

	public void deposit(double amount) {
		food = Math.min(FOOD_MAX, food + amount);
	}

	/** @return how much was actually taken */
	public double withdraw(double amount) {
		double taken = Math.min(amount, food);
		food -= taken;
		return taken;
	}

	public double getFood() {
		return food;
	}

	@Override
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		int r = 8 + (int) (food / 20);

		g2.setColor(new Color(110, 75, 40, 190));
		g2.fillOval(pixelX(v, r), pixelY(v, r), r * 2, r * 2);
		g2.setStroke(new BasicStroke(2));
		g2.setColor(new Color(70, 45, 20, 220));
		g2.drawOval(pixelX(v, r), pixelY(v, r), r * 2, r * 2);
	}

	@Override
	public String getEntityTypeName() {
		return "Nest";
	}
}
