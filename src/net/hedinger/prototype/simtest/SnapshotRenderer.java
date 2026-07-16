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
		// Recompute tile connectivity codes so wall/hole/ramp autotiling picks the
		// right connector sub-tiles. Scenario worlds are built with setTile+think,
		// and think() never aligns tiles (only real world-gen does), so without
		// this every wall renders as an isolated rounded blob.
		w.alignTiles();
		List<BufferedImage> levels = new ArrayList<BufferedImage>();
		List<String> labels = new ArrayList<String>();
		for (int z = 0; z < w.getLevels(); z++) {
			levels.add(renderLevel(w, z));
			labels.add("level " + z);
		}
		BufferedImage out = levels.size() == 1 ? levels.get(0) : strip(null, labels, levels);
		if (net.hedinger.prototype.engine.RenderFx.crt) {
			net.hedinger.prototype.engine.RenderFx.crt(out);
		}
		return out;
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
		if (net.hedinger.prototype.engine.RenderFx.debugOverlay) {
			drawDebugOverlay(img, w, level, ts * scale);
		}
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

			// Hovering action glyph: an icon floating above the entity, with a
			// ground shadow and a screen-position parallax so it reads as height.
			if (e instanceof TestNPC) {
				drawActionGlyph(g, ex, ey, px, img.getWidth() / 2, w.getTick(),
						((TestNPC) e).actionKey());
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

	/**
	 * A small action icon hovering above an entity. Sells "height" with three
	 * cues: a ground shadow at the entity's feet, a vertical lift (plus a gentle
	 * tick-driven bob), and a parallax x-shift proportional to distance from
	 * screen centre -- as if the glyph sat one notch nearer the camera. A faint
	 * leader ties the floating badge back to the body.
	 */
	private static void drawActionGlyph(Graphics2D g, int ex, int ey, double px, int centerX,
			long tick, String action) {
		if (action == null) {
			return;
		}
		int gx = ex + (int) ((ex - centerX) * 0.06); // parallax: higher = shifts more
		double bob = Math.sin(tick * 0.25) * (px * 0.03);
		int by = (int) (ey - px * 0.62 - bob); // badge centre, lifted above the body
		int rad = Math.max(7, (int) (px * 0.2));

		// Ground shadow at the entity's feet.
		g.setColor(new Color(0, 0, 0, 90));
		g.fillOval(ex - rad / 2, ey + (int) (px * 0.04), rad, Math.max(3, rad / 3));

		// Leader from the badge down to the body.
		g.setColor(new Color(255, 255, 255, 70));
		g.setStroke(new BasicStroke(1));
		g.drawLine(gx, by + rad, ex, ey);

		// Badge.
		g.setColor(new Color(0, 0, 0, 130));
		g.fillOval(gx - rad - 1, by - rad - 1, (rad + 1) * 2, (rad + 1) * 2);
		g.setColor(glyphColor(action));
		g.fillOval(gx - rad, by - rad, rad * 2, rad * 2);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(2));
		g.drawOval(gx - rad, by - rad, rad * 2, rad * 2);

		drawSymbol(g, action, gx, by, rad);
	}

	private static Color glyphColor(String a) {
		switch (a) {
		case "attack":
			return new Color(230, 60, 60);
		case "flee":
			return new Color(240, 190, 60);
		case "mate":
			return new Color(240, 90, 180);
		case "affiliate":
			return new Color(70, 200, 220);
		case "graze":
			return new Color(70, 200, 90);
		case "nest":
			return new Color(220, 60, 200);
		case "grab":
			return new Color(240, 150, 50);
		default:
			return new Color(160, 160, 160);
		}
	}

	/** Minimal procedural symbols (font-independent) inside the badge. */
	private static void drawSymbol(Graphics2D g, String a, int cx, int cy, int r) {
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(Math.max(2, r / 4)));
		int u = r;
		switch (a) {
		case "attack": // exclamation
			g.drawLine(cx, cy - u / 2, cx, cy + u / 6);
			g.fillOval(cx - 2, cy + u / 2 - 2, 4, 4);
			break;
		case "flee": // chevron pointing away
			g.drawLine(cx - u / 2, cy + u / 4, cx, cy - u / 3);
			g.drawLine(cx, cy - u / 3, cx + u / 2, cy + u / 4);
			break;
		case "affiliate": // plus
			g.drawLine(cx - u / 2, cy, cx + u / 2, cy);
			g.drawLine(cx, cy - u / 2, cx, cy + u / 2);
			break;
		case "graze": // asterisk
			for (int i = 0; i < 3; i++) {
				double ang = i * Math.PI / 3;
				int dx = (int) (Math.cos(ang) * u * 0.6), dy = (int) (Math.sin(ang) * u * 0.6);
				g.drawLine(cx - dx, cy - dy, cx + dx, cy + dy);
			}
			break;
		case "mate": // heart
			g.fillOval(cx - u / 2, cy - u / 2, u / 2 + 1, u / 2 + 1);
			g.fillOval(cx, cy - u / 2, u / 2 + 1, u / 2 + 1);
			g.fillPolygon(new int[] { cx - u / 2, cx + u / 2, cx },
					new int[] { cy - u / 8, cy - u / 8, cy + u / 2 }, 3);
			break;
		case "nest": // house
			g.drawRect(cx - u / 3, cy - u / 8, (2 * u) / 3, u / 2);
			g.drawPolygon(new int[] { cx - u / 2, cx + u / 2, cx },
					new int[] { cy - u / 8, cy - u / 8, cy - u / 2 }, 3);
			break;
		case "grab": // hook
			g.drawOval(cx - u / 2, cy - u / 2, u, u);
			g.drawLine(cx, cy, cx + u / 2, cy + u / 2);
			break;
		default:
			break;
		}
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
