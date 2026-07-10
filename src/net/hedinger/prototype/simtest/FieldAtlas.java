package net.hedinger.prototype.simtest;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.NPC;

/**
 * Analysis view: renders a world as a row of clean, single-field schematic
 * panels -- one each for terrain, vegetation, fertility and pheromone -- instead
 * of blending every field into one tile colour.
 *
 * <p>The core rendering problem is that a tile's fill is one channel, but the
 * simulation grows many independent scalar fields. A blended wash collides
 * (green grass vs green cover vs green fertility) and cannot be decomposed. The
 * atlas separates them: each field gets its own panel, its own colour ramp and
 * its own legend, so it stays legible no matter how many facets we add -- to add
 * a field, add a {@link Field} entry.
 */
public class FieldAtlas {

	private static final int CELL = 16; // px per tile
	private static final int TITLE_H = 22;
	private static final int LEGEND_H = 26;
	private static final int PAD = 10;
	private static final int GAP = 14;
	private static final Color BG = new Color(18, 18, 24);
	private static final Color WALL = new Color(70, 72, 84);
	private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 13);
	private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 10);

	/** The fields the atlas draws. Add an entry to surface a new facet. */
	private enum Field {
		TERRAIN("terrain", null),
		VEGETATION("vegetation", new Color(60, 210, 90)),
		FERTILITY("fertility", new Color(210, 180, 60)),
		PHEROMONE("pheromone", new Color(230, 60, 190));

		final String name;
		final Color hue; // ramp target; null = categorical (terrain)

		Field(String name, Color hue) {
			this.name = name;
			this.hue = hue;
		}
	}

	/** Renders level 0 of the world as a labelled row of single-field panels. */
	public static BufferedImage render(World w) {
		List<BufferedImage> panels = new ArrayList<BufferedImage>();
		for (Field f : Field.values()) {
			panels.add(panel(w, 0, f));
		}
		int h = 0, totalW = PAD;
		for (BufferedImage p : panels) {
			h = Math.max(h, p.getHeight());
			totalW += p.getWidth() + GAP;
		}
		BufferedImage out = new BufferedImage(totalW, h + PAD * 2, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(BG);
		g.fillRect(0, 0, out.getWidth(), out.getHeight());
		int x = PAD;
		for (BufferedImage p : panels) {
			g.drawImage(p, x, PAD, null);
			x += p.getWidth() + GAP;
		}
		g.dispose();
		return out;
	}

	private static BufferedImage panel(World w, int level, Field f) {
		int cols = w.getColums(), rows = w.getRows();
		int gw = cols * CELL, gh = rows * CELL;
		boolean legend = f.hue != null;
		int h = TITLE_H + gh + (legend ? LEGEND_H : 6);
		BufferedImage img = new BufferedImage(gw, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(BG);
		g.fillRect(0, 0, gw, h);

		g.setFont(TITLE_FONT);
		g.setColor(Color.WHITE);
		g.drawString(f.name, 2, 15);

		long now = w.getTick();
		double max = fieldMax(w, level, f, now);

		for (int c = 0; c < cols; c++) {
			for (int r = 0; r < rows; r++) {
				Tile t = w.getTile(c, r, level);
				int px = c * CELL, py = TITLE_H + r * CELL;
				g.setColor(baseColor(t, f));
				g.fillRect(px, py, CELL, CELL);
				if (f.hue != null && !t.isSolid()) {
					double v = fieldValue(t, f, now);
					double norm = max > 0 ? v / max : 0;
					if (norm > 0.02) {
						g.setColor(ramp(f.hue, Math.min(1, norm)));
						g.fillRect(px, py, CELL, CELL);
					}
				}
			}
		}

		// Entities as genome-coloured dots on every panel, for cross-reference.
		for (Entity e : w.getEntities()) {
			if (!(e instanceof NPC) || e.isDead() || e.getLvl() != level) {
				continue;
			}
			int px = (int) (e.getX() * CELL);
			int py = TITLE_H + (int) (e.getY() * CELL);
			Color dot = ((NPC) e).getGenome() != null ? ((NPC) e).getGenome().toColor() : Color.WHITE;
			g.setColor(Color.WHITE);
			g.fillOval(px - 4, py - 4, 8, 8);
			g.setColor(dot);
			g.fillOval(px - 3, py - 3, 6, 6);
		}

		if (legend) {
			drawLegend(g, f, 0, TITLE_H + gh + 4, Math.min(gw, 140), max);
		}
		g.dispose();
		return img;
	}

	/** Faint structural context on field panels; bright categorical on terrain. */
	private static Color baseColor(Tile t, Field f) {
		if (t.isSolid()) {
			return WALL;
		}
		switch (t.getType()) {
		case TYPE_WATER:
			return f == Field.TERRAIN ? new Color(45, 95, 205) : new Color(28, 40, 70);
		case TYPE_MUD:
			return f == Field.TERRAIN ? new Color(120, 85, 50) : new Color(45, 38, 28);
		case TYPE_COVER:
			return f == Field.TERRAIN ? new Color(30, 120, 45) : new Color(26, 46, 30);
		default:
			return f == Field.TERRAIN ? new Color(38, 42, 50) : new Color(14, 14, 18);
		}
	}

	private static double fieldValue(Tile t, Field f, long now) {
		switch (f) {
		case VEGETATION:
			return t.getVegetation(now);
		case FERTILITY:
			return t.getFertility();
		case PHEROMONE:
			return t.getPheromone(now);
		default:
			return 0;
		}
	}

	private static double fieldMax(World w, int level, Field f, long now) {
		if (f == Field.VEGETATION) {
			return Tile.VEG_MAX;
		}
		if (f == Field.FERTILITY) {
			return 1.0;
		}
		if (f == Field.PHEROMONE) {
			double m = 0;
			for (int c = 0; c < w.getColums(); c++) {
				for (int r = 0; r < w.getRows(); r++) {
					m = Math.max(m, w.getTile(c, r, level).getPheromone(now));
				}
			}
			return m;
		}
		return 1.0;
	}

	/** Dark-to-hue-to-white ramp: legible low values, bright peaks. */
	private static Color ramp(Color hue, double t) {
		if (t < 0.5) {
			double k = t / 0.5;
			return lerp(new Color(20, 20, 28), hue, k);
		}
		double k = (t - 0.5) / 0.5;
		return lerp(hue, Color.WHITE, k * 0.7);
	}

	private static Color lerp(Color a, Color b, double t) {
		return new Color(
				(int) (a.getRed() + (b.getRed() - a.getRed()) * t),
				(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	private static void drawLegend(Graphics2D g, Field f, int x, int y, int width, double max) {
		int bar = 10;
		for (int i = 0; i < width; i++) {
			g.setColor(ramp(f.hue, (double) i / width));
			g.fillRect(x + i, y, 1, bar);
		}
		g.setFont(SMALL_FONT);
		g.setColor(Color.WHITE);
		g.drawString("0", x, y + bar + 11);
		String hi = max >= 10 ? String.valueOf((int) max) : String.format("%.2f", max);
		int hw = g.getFontMetrics().stringWidth(hi);
		g.drawString(hi, x + width - hw, y + bar + 11);
	}
}
