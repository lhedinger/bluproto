package net.hedinger.prototype.render;

import static net.hedinger.prototype.render.EntityPainters.px;
import static net.hedinger.prototype.render.EntityPainters.py;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Item;

/**
 * Paints an item by kind: a round fruit for food, a braced wooden crate, a
 * spiky red-tipped burr for a hazard. Verbatim port of the old
 * {@code Item.draw*} methods.
 */
final class ItemPainter {

	static void draw(Item item, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		int cx = px(item, v, 0), cy = py(item, v, 0);
		int r = Math.max(2, Entity.round(
				Utils.scaleZ2((int) item.getZ(), v.getCamZ(), item.getPixelSize()) * 2));
		switch (item.getKind()) {
		case FOOD:
			drawFood(g2, cx, cy, r);
			break;
		case CRATE:
			drawCrate(g2, cx, cy, r);
			break;
		case HAZARD:
			drawHazard(g2, cx, cy, r);
			break;
		}
	}

	/** A round fruit: red body, a soft highlight and a little green leaf. */
	private static void drawFood(Graphics2D g2, int cx, int cy, int r) {
		g2.setColor(new Color(0x30, 0x18, 0x10, 90));
		g2.fillOval(cx - r + 1, cy - r + 2, r * 2, r * 2); // shadow
		g2.setColor(new Color(0xC8, 0x40, 0x2E));
		g2.fillOval(cx - r, cy - r, r * 2, r * 2);
		g2.setColor(new Color(0xF2, 0x8A, 0x6A, 180));
		g2.fillOval(cx - r + r / 3, cy - r + r / 4, Math.max(1, r), Math.max(1, r)); // sheen
		g2.setColor(new Color(0x4C, 0x8A, 0x33));
		g2.fillOval(cx, cy - r - r / 2, Math.max(2, r), Math.max(2, r / 2)); // leaf
	}

	/** A wooden crate: brown box, plank seams, a darker rim. */
	private static void drawCrate(Graphics2D g2, int cx, int cy, int r) {
		int s = r * 2;
		g2.setColor(new Color(0x2a, 0x1c, 0x10, 90));
		g2.fillRect(cx - r + 1, cy - r + 2, s, s); // shadow
		g2.setColor(new Color(0x9C, 0x6B, 0x3C));
		g2.fillRect(cx - r, cy - r, s, s);
		g2.setColor(new Color(0x6E, 0x48, 0x24));
		g2.setStroke(new BasicStroke(Math.max(1, r / 4f)));
		g2.drawRect(cx - r, cy - r, s, s); // frame
		g2.drawLine(cx - r, cy - r, cx + r, cy + r); // diagonal braces
		g2.drawLine(cx + r, cy - r, cx - r, cy + r);
		g2.setColor(new Color(0xC2, 0x92, 0x5C, 160));
		g2.drawLine(cx - r + 1, cy - r + 1, cx + r - 1, cy - r + 1); // top plank sheen
	}

	/** A hazard: a dark spiky burr with red-tipped points -- reads as "don't". */
	private static void drawHazard(Graphics2D g2, int cx, int cy, int r) {
		int spikes = 8;
		int[] xs = new int[spikes * 2];
		int[] ys = new int[spikes * 2];
		for (int i = 0; i < spikes * 2; i++) {
			double a = Math.PI * i / spikes;
			double rad = (i % 2 == 0) ? r : r * 0.45;
			xs[i] = cx + (int) Math.round(rad * Math.cos(a));
			ys[i] = cy + (int) Math.round(rad * Math.sin(a));
		}
		g2.setColor(new Color(0x2a, 0x0f, 0x2f, 90));
		g2.fillOval(cx - r + 1, cy - r + 2, r * 2, r * 2); // shadow
		g2.setColor(new Color(0x4A, 0x1C, 0x55));
		g2.fillPolygon(xs, ys, xs.length);
		g2.setColor(new Color(0xD8, 0x3A, 0x4A));
		g2.setStroke(new BasicStroke(Math.max(1, r / 5f)));
		g2.drawPolygon(xs, ys, xs.length); // red-tipped spikes
	}

	private ItemPainter() {
	}
}
