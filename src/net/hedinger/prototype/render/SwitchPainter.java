package net.hedinger.prototype.render;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Door;
import net.hedinger.prototype.entities.Switch;

/**
 * Paints a switch test-chamber style: an indicator trail of dotted lamps
 * running the L-path from switch to its wired door — dim while idle, lit while
 * the circuit is closed — and the control itself: a broad red disc for a
 * pressure plate (sinking flush when weighted), a domed red cap on a dark
 * pedestal for an intent button. Verbatim port of the old {@code Switch.draw}.
 */
final class SwitchPainter {

	private static final Color LAMP_HOUSING = new Color(0x14161f);
	private static final Color TRAIL_DIM = new Color(0x6a7280);
	private static final Color TRAIL_LIT = new Color(0xD0ECFF);
	private static final Color BTN_RED = new Color(0xE0455F);
	private static final Color BTN_RED_DARK = new Color(0x7c2434);
	private static final Color BTN_RED_LIT = new Color(0xF0788C);
	private static final Color PEDESTAL = new Color(0x2c3037);

	static void draw(Switch sw, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		boolean pressed = sw.isPressed();
		double step = (v.pixelX((int) sw.getX() + 1, (int) sw.getZ(), 0)
				- v.pixelX((int) sw.getX(), (int) sw.getZ(), 0)) / 12.0;
		if (step <= 0) {
			return;
		}
		int box = (int) Math.ceil(step);
		double sx = sw.getX() + 0.5, sy = sw.getY() + 0.5;
		double[] dc = doorCentre(sw.getDoor());

		// The indicator trail: lamps every few art-pixels along the L-run
		// (x-leg first) from switch to door -- each a dark housing around a
		// lens, dim while idle, lit while the circuit is closed. The
		// test-chamber "what does this operate" line, readable on any floor.
		int n = 0;
		double px = sx, py = sy;
		while (Math.abs(dc[0] - px) > 1.0 / 24) {
			px += Math.signum(dc[0] - px) / 12.0;
			if (n++ % 4 == 0) {
				trailLamp(g2, sw, v, px, py, box);
			}
		}
		while (Math.abs(dc[1] - py) > 1.0 / 24) {
			py += Math.signum(dc[1] - py) / 12.0;
			if (n++ % 4 == 0) {
				trailLamp(g2, sw, v, px, py, box);
			}
		}

		int z = (int) sw.getZ();
		if (sw.getMode() == Switch.PLATE) {
			// The floor button: a broad red disc riding proud of its seat,
			// sinking flush (darker) while weighted.
			int bx = (int) Math.round(v.pixelX(sx - 3.0 / 12, z, 0));
			int by = (int) Math.round(v.pixelY(sy - 3.0 / 12, z, 0));
			int d = box * 6;
			g2.setColor(pressed ? BTN_RED_DARK : BTN_RED);
			g2.fillRect(bx, by + box / 2, d, d - box); // disc, squarish-round
			g2.fillRect(bx + box / 2, by, d - box, d);
			if (!pressed) {
				g2.setColor(BTN_RED_LIT);
				g2.fillRect(bx + box, by + box / 2, d - box * 2, box); // lit north arc
			}
		} else {
			// The pedestal button: a dark base with a small domed red cap --
			// pressed, the cap sinks dark and the pedestal rim lights.
			int bx = (int) Math.round(v.pixelX(sx - 2.0 / 12, z, 0));
			int by = (int) Math.round(v.pixelY(sy - 2.0 / 12, z, 0));
			int d = box * 4;
			g2.setColor(PEDESTAL);
			g2.fillRect(bx - box / 2, by - box / 2, d + box, d + box);
			g2.setColor(pressed ? BTN_RED_DARK : BTN_RED);
			g2.fillRect(bx + box / 2, by + box / 2, d - box, d - box);
			if (!pressed) {
				g2.setColor(BTN_RED_LIT);
				g2.fillRect(bx + box, by + box / 2, d - box * 2, box); // dome highlight
			} else {
				g2.setColor(TRAIL_LIT);
				g2.fillRect(bx - box / 2, by - box / 2, d + box, box / 2 + 1); // lit rim
			}
		}
	}

	/** The wired door's centre point, where the indicator trail runs to. */
	private static double[] doorCentre(Door door) {
		boolean lr = ((int) (door.getDirection() % (Math.PI / 2))) != 0;
		double half = door.getSpan() * 0.5;
		return lr ? new double[] { door.getX(), door.getY() + half }
				: new double[] { door.getX() + half, door.getY() };
	}

	/** One trail lamp: a dark housing with a centred lens. */
	private static void trailLamp(Graphics2D g2, Switch sw, View v, double wx, double wy, int box) {
		int lx = (int) Math.round(v.pixelX(wx, (int) sw.getZ(), 0));
		int ly = (int) Math.round(v.pixelY(wy, (int) sw.getZ(), 0));
		g2.setColor(LAMP_HOUSING);
		g2.fillRect(lx - box / 2, ly - box / 2, box * 2, box * 2);
		g2.setColor(sw.isPressed() ? TRAIL_LIT : TRAIL_DIM);
		g2.fillRect(lx, ly, box, box);
	}

	private SwitchPainter() {
	}
}
