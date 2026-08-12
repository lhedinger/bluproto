package net.hedinger.prototype.render;

import static net.hedinger.prototype.render.EntityPainters.px;
import static net.hedinger.prototype.render.EntityPainters.py;
import static net.hedinger.prototype.render.EntityPainters.toPixel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Bullet;
import net.hedinger.prototype.entities.Explosion;
import net.hedinger.prototype.entities.Grenade;
import net.hedinger.prototype.entities.Sound;

/**
 * Paints the transient combat effects: a bullet's tracer line, a grenade dot,
 * an explosion's expanding ring, a sound's spreading circle. Verbatim ports of
 * the old entity {@code draw} methods.
 */
final class EffectPainters {

	/** Tracer length in pixels (the old {@code Bullet.length}). */
	private static final int BULLET_LENGTH = 10;

	static void drawBullet(Bullet b, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double D = b.getDirection();
		g2.setColor(Color.WHITE);
		g2.setStroke(new BasicStroke(1));
		g2.drawLine(
				px(b, v, 0),
				py(b, v, 0),
				px(b, v, BULLET_LENGTH * Math.cos(D)),
				py(b, v, BULLET_LENGTH * Math.sin(D)));
	}

	static void drawGrenade(Grenade gr, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		int size = gr.getPixelSize();
		g2.setColor(Color.WHITE);
		g2.fillOval(px(gr, v, 0), py(gr, v, 0), size, size);
	}

	static void drawExplosion(Explosion ex, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double temp = ex.getAge();
		temp = temp / ex.getLifespan();
		temp = temp * Explosion.EXPLOSION_RADIUS;
		g2.setColor(Color.YELLOW);
		g2.setStroke(new BasicStroke(4));
		g2.drawOval(
				px(ex, v, toPixel(ex, v, temp) * 0.5),
				py(ex, v, toPixel(ex, v, temp) * 0.5),
				toPixel(ex, v, temp),
				toPixel(ex, v, temp));
	}

	static void drawSound(Sound s, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double temp = s.getAge();
		temp = temp / s.getLifespan();
		temp = temp * s.getRadius();
		g2.setColor(Color.GRAY);
		g2.setStroke(new BasicStroke(2));
		g2.drawOval(
				px(s, v, toPixel(s, v, temp) * 0.5),
				py(s, v, toPixel(s, v, temp) * 0.5),
				toPixel(s, v, temp),
				toPixel(s, v, temp));
	}

	private EffectPainters() {
	}
}
