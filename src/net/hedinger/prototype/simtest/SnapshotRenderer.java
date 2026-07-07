package net.hedinger.prototype.simtest;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.LayerRenderer;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;

/**
 * Renders a scenario's {@link World} to an image and composes labelled
 * before/after strips. On top of the normal game render it draws a debug
 * overlay of state that is otherwise invisible:
 *
 * <ul>
 *   <li>a heading arrow on every living NPC,</li>
 *   <li>a state label under each NPC ({@link TestNPC#debugLabel()} for
 *       fixtures: behaviour, flying, heard, grabbing/carried, hp, dead),</li>
 *   <li>a link line between a carrier and its carried cargo,</li>
 *   <li>closed door edges as red bars on the tile border,</li>
 *   <li>every world level, side by side, so cross-level action (ramps,
 *       hole-falls) is visible.</li>
 * </ul>
 *
 * Used only when snapshot capture is enabled (see {@link Scenario}); it is
 * never on the hot path of a normal test run.
 */
final class SnapshotRenderer {

	private static final Color ARROW = new Color(0, 255, 255);
	private static final Color LABEL_BG = new Color(0, 0, 0, 170);
	private static final Color LABEL_FG = Color.WHITE;
	private static final Color CARRY_LINK = new Color(255, 0, 255);
	private static final Color DOOR_BAR = new Color(255, 60, 60);
	private static final Font LABEL_FONT = new Font("Arial", Font.PLAIN, 12);

	private static boolean resourcesLoaded = false;

	private SnapshotRenderer() {
	}

	/** Renders every level of the world side by side, with the debug overlay. */
	static BufferedImage render(World w) {
		List<BufferedImage> levels = new ArrayList<BufferedImage>();
		List<String> labels = new ArrayList<String>();
		for (int z = 0; z < w.getLevels(); z++) {
			levels.add(renderLevel(w, z));
			labels.add("level " + z);
		}
		if (levels.size() == 1) {
			return levels.get(0);
		}
		return strip(null, labels, levels);
	}

	private static BufferedImage renderLevel(World w, int level) {
		if (!resourcesLoaded) {
			ResourceManager.loadResources();
			resourcesLoaded = true;
		}
		LayerRenderer lr = new LayerRenderer(w);
		lr.build(w);
		View view = new View(w, lr);

		int ts = ResourceManager.tileSize;
		int worldW = Math.max(ts, w.getColums() * ts);
		int worldH = Math.max(ts, w.getRows() * ts);

		BufferedImage raw = new BufferedImage(worldW, worldH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = raw.createGraphics();
		g.setClip(0, 0, worldW, worldH);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// View defaults its camera to the world centre; steer to the level. With
		// the camera centred and the image sized to the world, pixel = tile * ts.
		view.think(g, 0, 0, level - view.getCamZ(), 0, 0);
		view.render(g);
		g.dispose();

		int scale = Math.max(1, (int) Math.ceil(480.0 / Math.max(worldW, worldH)));
		BufferedImage img = upscale(raw, scale);
		drawDebugOverlay(img, w, level, ts * scale);
		return img;
	}

	/** Debug overlay: doors, heading arrows, carry links, state labels. */
	private static void drawDebugOverlay(BufferedImage img, World w, int level, double px) {
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setFont(LABEL_FONT);
		// Placed label boxes, so overlapping labels stack downward instead of
		// printing over each other (entities interact at ~0.1-tile range, so
		// their labels usually collide).
		List<java.awt.Rectangle> placed = new ArrayList<java.awt.Rectangle>();

		// Closed-door edges as red bars on the tile border.
		g.setColor(DOOR_BAR);
		g.setStroke(new BasicStroke(4));
		for (int c = 0; c < w.getColums(); c++) {
			for (int r = 0; r < w.getRows(); r++) {
				Tile t = w.getTile(c, r, level);
				int x0 = (int) (c * px), y0 = (int) (r * px);
				int x1 = (int) ((c + 1) * px), y1 = (int) ((r + 1) * px);
				if (t.isDoorClosed(0)) { // N
					g.drawLine(x0 + 4, y0 + 2, x1 - 4, y0 + 2);
				}
				if (t.isDoorClosed(1)) { // E
					g.drawLine(x1 - 2, y0 + 4, x1 - 2, y1 - 4);
				}
				if (t.isDoorClosed(2)) { // S
					g.drawLine(x0 + 4, y1 - 2, x1 - 4, y1 - 2);
				}
				if (t.isDoorClosed(3)) { // W
					g.drawLine(x0 + 2, y0 + 4, x0 + 2, y1 - 4);
				}
			}
		}

		for (Entity e : w.getEntities()) {
			if (e == null || e.isRemoved() || e.getLvl() != level) {
				continue;
			}
			int ex = (int) (e.getX() * px);
			int ey = (int) (e.getY() * px);

			// Carry link from cargo to its carrier.
			Entity carrier = e.getAttachTarget();
			if (carrier != null) {
				g.setColor(CARRY_LINK);
				g.setStroke(new BasicStroke(2));
				int cx = (int) (carrier.getX() * px);
				int cy = (int) (carrier.getY() * px);
				g.drawLine(ex, ey, cx, cy);
				g.drawOval(ex - 6, ey - 6, 12, 12);
			}

			// Heading arrow for living NPCs.
			if (!e.isDead()) {
				g.setColor(ARROW);
				g.setStroke(new BasicStroke(2));
				double d = e.getDirection();
				int hx = ex + (int) (Math.cos(d) * px * 0.45);
				int hy = ey + (int) (Math.sin(d) * px * 0.45);
				g.drawLine(ex, ey, hx, hy);
				g.fillOval(hx - 3, hy - 3, 6, 6);
			}

			// State label under the entity.
			String label = null;
			if (e instanceof TestNPC) {
				label = ((TestNPC) e).debugLabel();
			} else if (e.isDead()) {
				label = e.getEntityTypeName() + " dead";
			}
			if (label != null) {
				int tw = g.getFontMetrics().stringWidth(label);
				int lx = ex - tw / 2;
				int ly = ey + (int) (px * 0.55) + 12;
				java.awt.Rectangle box = new java.awt.Rectangle(lx - 3, ly - 11, tw + 6, 14);
				boolean moved = true;
				while (moved) {
					moved = false;
					for (java.awt.Rectangle p : placed) {
						if (box.intersects(p)) {
							box.y += 16; // stack below the colliding label
							moved = true;
						}
					}
				}
				placed.add(box);
				g.setColor(LABEL_BG);
				g.fillRect(box.x, box.y, box.width, box.height);
				g.setColor(LABEL_FG);
				g.drawString(label, box.x + 3, box.y + 11);
			}
		}
		g.dispose();
	}

	private static BufferedImage upscale(BufferedImage src, int scale) {
		if (scale <= 1) {
			return src;
		}
		int w = src.getWidth() * scale;
		int h = src.getHeight() * scale;
		BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = dst.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return dst;
	}

	/**
	 * Composes labelled frames left-to-right into one image, optionally with a
	 * title bar -- used both for a scenario's before/after strip and for the
	 * side-by-side levels of a multi-level world.
	 */
	static BufferedImage strip(String title, List<String> labels, List<BufferedImage> frames) {
		int pad = 12;
		int labelH = 20;
		int titleH = title == null ? 0 : 26;
		int cellH = 0;
		int totalW = pad;
		for (BufferedImage f : frames) {
			cellH = Math.max(cellH, f.getHeight());
			totalW += f.getWidth() + pad;
		}
		int totalH = titleH + labelH + cellH + pad;

		BufferedImage out = new BufferedImage(Math.max(totalW, 200), totalH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(24, 24, 30));
		g.fillRect(0, 0, out.getWidth(), out.getHeight());

		if (title != null) {
			g.setColor(Color.white);
			g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 14f));
			g.drawString(title, pad, titleH - 8);
		}

		int x = pad;
		for (int i = 0; i < frames.size(); i++) {
			BufferedImage f = frames.get(i);
			int y = titleH + labelH;
			g.setColor(new Color(200, 200, 210));
			g.setFont(g.getFont().deriveFont(java.awt.Font.PLAIN, 12f));
			g.drawString(labels.get(i), x, titleH + 14);
			g.drawImage(f, x, y, null);
			x += f.getWidth() + pad;
		}
		g.dispose();
		return out;
	}
}
