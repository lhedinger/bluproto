package net.hedinger.prototype.render;

import static net.hedinger.prototype.render.EntityPainters.px;
import static net.hedinger.prototype.render.EntityPainters.py;
import static net.hedinger.prototype.render.EntityPainters.toPixel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.ProcCreature;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.View.ViewMode;
import net.hedinger.prototype.entities.NPC;

/**
 * Paints a creature: the genome-driven organism body (or the legacy sprite),
 * plus the debug overlays — LOS cone, perception ping, heading/trace lines,
 * target links — and the fading speech bubble. A verbatim port of the old
 * {@code NPC.draw}/{@code draw_dead}, reading state through accessors.
 */
final class NpcPainter {

	static void draw(NPC n, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		int width = (int) g.getClipBounds().getMaxX();
		int height = (int) g.getClipBounds().getMaxY();
		double D = n.getDirection();
		int size = n.getPixelSize();

		if (n.debugDrawLOS() && v.getViewMode() == ViewMode.ALL) {
			double fov = n.getLosFov();
			g2.setStroke(new BasicStroke(2));
			g2.setColor(new Color(250, 250, 250, 100));
			int x = px(n, v, 0);
			int y = py(n, v, 0);
			int r = toPixel(n, v, n.getLosRange());
			g2.drawOval(x - r, y - r, r * 2, r * 2);
			if (fov >= Math.PI - 0.0001) {
				g2.drawLine(round(x + 32 * Math.cos(D)), round(y + 32 * Math.sin(D)), round(x + r
						* Math.cos(D)), round(y + r * Math.sin(D)));
			} else {
				g2.drawLine(round(x + 32 * Math.cos(D - fov)), round(y + 32
						* Math.sin(D - fov)), round(x + r * Math.cos(D - fov)), round(y + r
								* Math.sin(D - fov)));
				g2.drawLine(round(x + 32 * Math.cos(D + fov)), round(y + 32
						* Math.sin(D + fov)), round(x + r * Math.cos(D + fov)), round(y + r
								* Math.sin(D + fov)));
			}
		}
		if (n.debugDrawPing()) {
			float ping = n.getPing();
			g2.setStroke(new BasicStroke(2));
			g2.setColor(new Color(250, 250, 250, 100));
			if (ping > 0) {
				for (int i = 0; i < 10; i++) {
					g2.setColor(new Color(0, 255, 0, 5 * i));
					g2.drawOval(px(n, v, toPixel(n, v, ping)) - i, py(n, v, toPixel(n, v, ping)) - i,
							toPixel(n, v, ping * 2) + i * 2, toPixel(n, v, ping * 2) + i * 2);
				}
			}
			n.advancePing();
		}

		if (v.getViewMode() == ViewMode.ALL && n.getNpcTypeName() == "Human") {
			g2.setColor(new Color(255, 255, 255, 50));
			for (NPC e : n.targetsView()) {
				if (e != null) {
					g2.drawLine(px(e, v, 0), py(e, v, 0), px(n, v, 0), py(n, v, 0));
				}
			}
			g2.setColor(new Color(255, 100, 100, 140));
			for (NPC e : n.focusTargetsView()) {
				if (e != null) {
					g2.drawLine(px(e, v, 0), py(e, v, 0), px(n, v, 0), py(n, v, 0));
				}
			}
		}

		Color col = n.getColor();
		g2.setColor(col);
		g2.setStroke(new BasicStroke(1));

		if (n.debugDrawLine()) {
			float ts = Utils.scaleZ((int) n.getZ(), v.getCamZ());
			g2.drawLine(round((n.getX() - v.getCamX()) * ts + width / 2), (int) Math
					.round((n.getY() - v.getCamY()) * ts + height / 2), round(size * Math.cos(D)
							+ round((n.getX() - v.getCamX()) * ts + width / 2)),
					round(size * Math.sin(D)
							+ Math.round((n.getY() - v.getCamY()) * ts + height / 2)));
		}
		g2.setStroke(new BasicStroke(1));
		g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 100));
		if (n.debugDrawTrace()) {
			float ts = Utils.scaleZ((int) n.getZ(), v.getCamZ());
			g2.drawLine(round((n.getX() - v.getCamX()) * ts + width / 2), (int) Math
					.round((n.getY() - v.getCamY()) * ts + height / 2), (int) Math
					.round((n.getTargetX() - v.getCamX()) * ts + width / 2),
					(int) Math
					.round((n.getTargetY() - v.getCamY()) * ts + height / 2));
		}

		float relativeSize = Utils.scaleZ2((int) n.getZ(), v.getCamZ(), size);
		int relativeSize2 = round(relativeSize * 2);

		if ("drone".equals(n.ecoRole())) {
			// Machinery, not an organism: an authored stamp rather than a
			// procedural body. It has no genome, and without this branch it
			// would fall through to the legacy hostility sprite below --
			// which is where it sat while the web client drew a sentinel the
			// Java renderer had never heard of.
			DronePainter.draw(n, g, v);
		} else if (n.getGenome() != null) {
			// Genome-driven top-down organism: oriented to the heading; animated
			// faster while moving, gently while idle (offset by id so they aren't
			// in lockstep). Newborns pop in with the generic spawn action.
			double spd = Math.hypot(n.getDX(), n.getDY());
			// A corpse holds one phase. The idle rate is deliberately slow rather
			// than zero, so a body that had stopped moving still breathed -- which
			// is right for something alive and standing still, and wrong for
			// something dead: it left corpses gently walking on the spot for their
			// whole decay.
			double phase = n.isDead() ? 0
					: n.getWorld().getTick() * (spd > 0.001 ? 0.5 : 0.14) + n.getID();
			ProcCreature.Phenotype ph = ProcCreature.phenotype(n.getGenome());
			int cx = px(n, v, 0), cy = py(n, v, 0);
			// A corpse plays the death action across its whole rot, so decay is one
			// continuous thing the eye can read rather than a body that vanishes on
			// a timer. A newborn pops in with the generic spawn action.
			boolean spawning = n.getAge() >= 0 && n.getAge() < 24;
			int action = n.isDead() ? ProcCreature.A_DEATH
					: spawning ? ProcCreature.A_SPAWN : ProcCreature.A_IDLE;
			double actionT = n.isDead() ? n.decayProgress()
					: spawning ? n.getAge() / 24.0 : 0;
			ProcCreature.drawCached(g2, cx, cy, relativeSize2, ph, D, phase, action, actionT);
		} else {
			g2.drawImage(ResourceManager.getNpcSprite(n.getHostility()), px(n, v, relativeSize2),
					py(n, v, relativeSize2), relativeSize2 * 2, relativeSize2 * 2, null);
		}

		if (n.getMessageFade() > 0) {
			g2.setFont(new Font("Arial", Font.BOLD, 10));
			g2.setColor(new Color(250, 250, 250));
			if (n.getMessageFade() < n.getMessageFadeMax()) {
				float alpha = n.getMessageFade();
				alpha = alpha / n.getMessageFadeMax();
				alpha = alpha * 250;
				g2.setColor(new Color(250, 250, 250, Math.round(alpha)));
			}

			FontMetrics fm = g.getFontMetrics();
			Rectangle2D textsize = fm.getStringBounds(n.getMessage(), g);

			float ts = Utils.scaleZ((int) n.getZ(), v.getCamZ());
			g.drawString(n.getMessage(), round((n.getX() - v.getCamX()) * ts + width / 2 - size * 0.5
					- textsize.getWidth() * 0.5), round((n.getY() - v.getCamY()) * ts + height
							/ 2 - size * 0.5 - 5));

			n.fadeMessage();
		}
	}

	static void drawDead(NPC n, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		int size = n.getPixelSize();
		g2.drawImage(ResourceManager.getCropseSprite(n.getHostility()),
				px(n, v, size * 2),
				py(n, v, size * 2),
				size * 4,
				size * 4, null);
	}

	private static int round(double value) {
		return Entity.round(value);
	}

	private NpcPainter() {
	}
}
