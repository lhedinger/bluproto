package net.hedinger.prototype.engine;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.TreeMap;

public class Grid {

	private World world;
	private Tile[][] tiles;
	private int level;

	LinkedHashSet<Entity> doors;
	HashMap<Integer, Sector> sectors;

	int counter = 0;

	Grid(World w, int c, int r, int l) {
		world = w;
		if (c < 1 || r < 1) {
			return;
		}

		tiles = new Tile[c][r];

		level = l;

		doors = new LinkedHashSet<Entity>();

		sectors = new HashMap<Integer, Sector>();
	}

	boolean aligned = false;

	public void think(World w) {

		for (Entity d : doors) {
			if (d != null) {
				d.run();
			}
		}

	}

	public void render(Graphics g, View v, LayerRenderer lr) {

		Graphics2D g2 = (Graphics2D) g;

		int camDepth = (v.getCamZ()) - level;

		if (camDepth < 0) {
			return;
		}

		if (camDepth > world.max_view_depth) {
			return;
		}

		int ox = v.pixelX(0, level, 0);
		int oy = v.pixelY(0, level, 0);
		if (camDepth == 0) {
			g2.drawImage(lr.mapLayers[level].image_layer, ox, oy, null);
			renderGround(g2, ox, oy);
			renderTallGrass(g2, ox, oy); // under the creatures, so they walk over it
			renderShrubs(g2, ox, oy);    // decorative bushes on the lushest tiles
		} else {
			g2.drawImage(lr.mapLayers[level].image_layer_downsized[camDepth - 1], ox, oy, null);
		}

		for (Entity d : doors) {
			if (d != null) {
				d.render(g, v);
			}
		}
		// Pheromone clouds first, so the haze sits under the creatures.
		for (Entity e : world.entities.values()) {
			if (e instanceof PheromoneCloud && e.getLvl() == level) {
				e.render(g, v);
			}
		}
		for (Entity e : world.entities.values()) {
			if (e != null && !(e instanceof PheromoneCloud) && e.getLvl() == level) {
				e.render(g, v);
			}
		}
	}

	/**
	 * Draws the cosmetic tall-grass overlay as a top-down field: on every
	 * {@link Tile#hasTallGrass() tall-grass} tile a scatter of little pixel tufts.
	 * A tuft slides aside from a nearby entity (the grass parts as a creature
	 * passes) and, when a creature is right on top of it, is pressed flat -- drawn
	 * as a smaller, darker mark -- so a walked-over trail of trampled grass is left
	 * behind. Purely visual: nothing here is read by the simulation.
	 */
	/** Fertility at/above which floor tiles grow tall grass on their own. */
	private static final double FERT_GRASS = 0.5;
	/** Tufts on a fully-vegetated grass tile (fewer as vegetation is grazed down). */
	private static final int MAX_TUFTS = 10;

	private void renderTallGrass(Graphics2D g2, int ox, int oy) {
		int ts = ResourceManager.tileSize;
		long now = world.getTick();
		final double R = 0.9;          // tiles: how close an entity parts a tuft aside
		final double MAX_SHIFT = 0.32; // tiles: how far a tuft can slide
		final double FOOT = 0.45;      // tiles: an entity within this flattens the grass
		int dot = Math.max(4, ts / 8);

		java.util.ArrayList<double[]> feet = new java.util.ArrayList<double[]>();
		for (Entity e : world.entities.values()) {
			if (e != null && e.getLvl() == level && !e.isRemoved()) {
				feet.add(new double[] { e.getX(), e.getY() });
			}
		}

		java.util.ArrayList<double[]> near = new java.util.ArrayList<double[]>();
		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				Tile tile = tiles[x][y];
				if (tile.getType() != Tile.TileType.TYPE_FLOOR) {
					continue;
				}
				// Fertile ground (or an explicitly-flagged tile) grows tall grass;
				// its density follows current vegetation, so grazing thins it out and
				// it fills back in as the grass regrows.
				if (!tile.hasTallGrass() && tile.getFertility() < FERT_GRASS) {
					continue;
				}
				double vegFrac = tile.getVegetation(now) / Tile.VEG_MAX;
				int count = (int) Math.round(MAX_TUFTS * vegFrac);
				if (count <= 0) {
					continue;
				}
				// Only entities that could reach into this tile matter for the bend.
				near.clear();
				double cxT = x + 0.5, cyT = y + 0.5;
				for (double[] f : feet) {
					if (Math.abs(f[0] - cxT) < R + 0.7 && Math.abs(f[1] - cyT) < R + 0.7) {
						near.add(f);
					}
				}
				for (int t = 0; t < count; t++) {
					int h = ((x * 73856093) ^ (y * 19349663) ^ (t * 83492791));
					double wx = x + 0.12 + 0.76 * frac(h * 0.001);
					double wy = y + 0.12 + 0.76 * frac(h * 0.00037);

					double sx = 0, sy = 0, flat = 0;
					for (double[] f : near) {
						double dx = wx - f[0], dy = wy - f[1];
						double d = Math.hypot(dx, dy);
						if (d < R && d > 1e-4) {
							double infl = (1 - d / R);
							infl *= infl;
							sx += (dx / d) * infl; // slide away from the entity
							sy += (dy / d) * infl;
						}
						if (d < FOOT) {
							flat = Math.max(flat, 1 - d / FOOT); // pressed down underfoot
						}
					}
					double m = Math.hypot(sx, sy);
					if (m > 1) {
						sx /= m;
						sy /= m;
					}
					double wind = Math.sin(now * 0.07 + wx * 0.9 + wy * 0.6) * 0.05;
					double px = wx + sx * MAX_SHIFT + wind, py = wy + sy * MAX_SHIFT;
					int cx = ox + (int) (px * ts), cy = oy + (int) (py * ts);
					drawTuft(g2, cx, cy, dot, flat, h);
				}
			}
		}
	}

	private void drawTuft(Graphics2D g2, int cx, int cy, int dot, double flat, int hash) {
		int tint = (hash & 15) - 7;
		if (flat > 0.15) {
			// Trampled: a small dark mark pressed flat against the ground.
			int s = Math.max(2, (int) (dot * (1.0 - 0.5 * flat)));
			g2.setColor(new java.awt.Color(clampc(28 + tint), clampc(54 + tint), clampc(28 + tint)));
			g2.fillRect(cx - s / 2, cy, s, Math.max(2, s / 3));
			return;
		}
		int s = dot;
		// A drop shadow just below sells the tuft as standing above the ground.
		g2.setColor(new java.awt.Color(18, 36, 20, 150));
		g2.fillRect(cx - s / 2, cy + s / 4, s, Math.max(2, s / 3));
		// Body + a bright, sunlit top so tall grass reads distinctly against the
		// finer ground texture.
		g2.setColor(new java.awt.Color(clampc(70 + tint), clampc(138 + tint), clampc(64 + tint)));
		g2.fillRect(cx - s / 2, cy - s / 2, s, s);
		g2.setColor(new java.awt.Color(clampc(150 + tint), clampc(206 + tint), clampc(112 + tint)));
		g2.fillRect(cx - s / 2, cy - s / 2, s, Math.max(1, s / 2));
	}

	private static double frac(double v) {
		v = Math.abs(v);
		return v - Math.floor(v);
	}

	private static int clampc(int c) {
		return c < 0 ? 0 : (c > 255 ? 255 : c);
	}

	// ---- decorative shrubs (lush tiles) -----------------------------------
	/** Fertility at/above which a floor tile may grow a shrub. */
	private static final double SHRUB_FERT = 0.72;
	/** Fraction of eligible tiles that actually carry a shrub (scattered, not a hedge). */
	private static final double SHRUB_DENSITY = 0.13;
	private static final int SH_BASE = 0x2f6b28, SH_DARK = 0x123a0f, SH_LIGHT = 0x82c268;
	private static final int SH_BERRY = 0xE0455F, SH_FLOWER = 0xF0E8C6;
	// Wide, low lobe layout: {dx, dy, rx, ry, rot} in units of the shrub radius.
	private static final double[][] SH_LOBES = {
			{ 0, 0, 1.05, 0.9, 0 }, { -0.72, 0.12, 0.78, 0.66, 0.2 }, { 0.72, 0.08, 0.82, 0.62, -0.3 },
			{ -0.32, -0.28, 0.62, 0.6, 0.5 }, { 0.36, -0.22, 0.66, 0.58, -0.6 } };

	/**
	 * Decorative shrubs on the lushest tiles: a scattered, deterministic mix of
	 * three organic shapes (soft mound / leafy / irregular), wide and low with a
	 * tight ground shadow and the odd berry or flower. Purely cosmetic -- placed
	 * from the tile hash, read by nothing in the simulation -- and drawn under the
	 * creatures (shrubs are low; trees, later, will occlude instead).
	 */
	private void renderShrubs(Graphics2D g2, int ox, int oy) {
		int ts = ResourceManager.tileSize;
		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				Tile tile = tiles[x][y];
				if (tile.getType() != Tile.TileType.TYPE_FLOOR || tile.getFertility() < SHRUB_FERT) {
					continue;
				}
				int h = (x * 73856093) ^ (y * 19349663);
				if (frac(h * 0.00061) > SHRUB_DENSITY) {
					continue; // scatter: not every lush tile
				}
				double jx = x + 0.32 + 0.36 * frac(h * 0.0013);
				double jy = y + 0.40 + 0.34 * frac(h * 0.0021);
				int R = (int) (ts * (0.29 + 0.2 * frac(h * 0.0009)));
				drawShrub(g2, ox + (int) (jx * ts), oy + (int) (jy * ts), R, ts, h);
			}
		}
	}

	private void drawShrub(Graphics2D g2, int cx, int cy, int R, int ts, int hash) {
		int pix = Math.max(4, ts / 11);
		int style = Math.floorMod(hash, 3); // 0 soft mound, 1 leafy, 2 irregular
		// Tight shadow tucked under the base so the shrub doesn't float.
		g2.setColor(new java.awt.Color(0, 0, 0, 120));
		g2.fillOval(cx - (int) (R * 1.05), cy + (int) (R * 0.18), (int) (R * 2.1), (int) (R * 0.55));

		double[][] lobe = new double[SH_LOBES.length][];
		for (int i = 0; i < lobe.length; i++) {
			lobe[i] = SH_LOBES[i];
		}
		java.util.Arrays.sort(lobe, (a, b) -> Double.compare(a[1], b[1])); // back-to-front
		double wobble = style == 1 ? 0.55 : (style == 2 ? 0.38 : 0.22);
		for (int i = 0; i < lobe.length; i++) {
			double lx = cx + lobe[i][0] * R, ly = cy + lobe[i][1] * R;
			double rx = lobe[i][2] * R, ry = lobe[i][3] * R;
			if (style == 1) {
				ry *= 0.9;
			}
			orgLobe(g2, (int) lx, (int) ly, (int) rx, (int) ry, lobe[i][4], wobble, style, pix, hash + i * 9);
		}
		// Berries (with the odd flower) on most shrubs -- deterministic per tile.
		if (frac(hash * 0.00037) < 0.62) {
			int[] cols = { SH_BERRY, SH_BERRY, SH_BERRY, SH_FLOWER, SH_BERRY, SH_BERRY, SH_BERRY, SH_FLOWER };
			for (int k = 0; k < cols.length; k++) {
				double a = k * 2.399963 + hash;
				double rr = 0.35 + 0.5 * frac(hash * 0.0007 + k * 0.31);
				int ax = cx + (int) (Math.cos(a) * R * rr);
				int ay = cy + (int) (Math.sin(a) * R * rr * 0.6) - R / 8;
				g2.setColor(new java.awt.Color(cols[k]));
				g2.fillRect(ax, ay, pix, pix);
			}
		}
	}

	/** An organic pixel lobe: an ellipse (rx,ry) rotated by {@code rot}, its rim
	 *  wobbled by angular noise so the outline is leafy rather than a clean circle.
	 *  A thin, soft rim outlines the mass against the ground. */
	private void orgLobe(Graphics2D g2, int cx, int cy, int rx, int ry, double rot, double wobble, int style,
			int pix, int salt) {
		int rmax = (int) (Math.max(rx, ry) * 1.35);
		double cr = Math.cos(rot), sr = Math.sin(rot);
		for (int py = -rmax; py <= rmax; py += pix) {
			for (int px = -rmax; px <= rmax; px += pix) {
				double lx = px * cr + py * sr, ly = -px * sr + py * cr;
				double ang = Math.atan2(ly, lx);
				double rad = Math.hypot(lx, ly);
				double ca = Math.cos(ang), sa = Math.sin(ang);
				double rell = 1.0 / Math.sqrt((ca / rx) * (ca / rx) + (sa / ry) * (sa / ry));
				double boundary = rell * (1 + wobble * (shAngNoise(ang, salt, style) - 0.5));
				if (rad > boundary) {
					continue;
				}
				double d = rad / boundary;
				double t = -0.4 * d * d;
				if (d > 0.9) {
					t -= 0.42; // thin, soft outline (not a heavy border)
				}
				double lit = (-px - py) / (2.0 * Math.max(rx, ry));
				if (lit > 0) {
					t += 0.7 * lit;
				}
				int hh = (px * 928371) ^ (py * 12377) ^ salt;
				t += ((hh & 7) - 3) / 90.0;
				int col = t < 0 ? shMix(SH_BASE, SH_DARK, Math.min(1, -t)) : shMix(SH_BASE, SH_LIGHT, Math.min(1, t));
				g2.setColor(new java.awt.Color(col));
				g2.fillRect(cx + px, cy + py, pix, pix);
			}
		}
	}

	private static double shAngNoise(double ang, double salt, int style) {
		double n = 0.5 + 0.28 * Math.sin(ang * 3 + salt) + 0.16 * Math.sin(ang * 5 - salt * 1.7 + 1.3);
		if (style == 1) {
			n += 0.22 * Math.sin(ang * 9 + salt * 2.1); // extra spikes -> leafy
		}
		return n;
	}

	private static int shMix(int a, int b, double t) {
		int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
		int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
		return ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
	}

	/**
	 * The ground layer (under doors and entities): each tile draws a monochrome
	 * terrain texture (grass blades, water ripples, mud speckle, tall-grass
	 * cover) rather than a flat colour -- grass density follows vegetation, so
	 * grazed patches thin to bare floor. Pheromone rides on top as a wash.
	 */
	private void renderGround(Graphics2D g2, int ox, int oy) {
		if (RenderFx.pixelGround) {
			renderGroundPixel(g2, ox, oy);
			return;
		}
		int ts = ResourceManager.tileSize;
		long now = world.getTick();
		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				Tile t = tiles[x][y];
				int hash = (x * 73856093) ^ (y * 19349663);
				int sx = ox + x * ts, sy = oy + y * ts;
				if (t.getType() == Tile.TileType.TYPE_FLOOR) {
					// Soil base over the blue floor sprite, then opaque grass
					// where vegetation grows: bare/grazed ground reads as earth,
					// grassy ground as green. The green base is feathered where it
					// meets non-green ground (bare soil, mud, water) so it melts
					// into the soil instead of ending in a hard tile seam; the
					// overlay density follows vegetation, lush (mottle) tiles fade
					// toward thinner grass.
					g2.setColor(GroundTextures.SOIL);
					g2.fillRect(sx, sy, ts, ts);
					int level = GroundTextures.grassLevel(t.getVegetation(now) / Tile.VEG_MAX);
					if (level >= 0) {
						GroundTextures.drawFeathered(g2, sx, sy, ts, GroundTextures.GRASS_GREEN, null,
								greenEdgeMask(x, y, now));
						if (GroundTextures.isMottle(level)) {
							// Sampled from one world-space field so it joins its
							// mottle neighbours; faded toward thinner grass.
							GroundTextures.drawMottle(g2, sx, sy, ts, level, x, y, mottleEdgeMask(x, y, now));
						} else {
							g2.drawImage(GroundTextures.stipplePattern(level, hash), sx, sy, ts, ts, null);
						}
					}
				} else if (t.getType() == Tile.TileType.TYPE_WATER) {
					// Opaque blue base + world-space ripples, with convex corners
					// rounded off so the outline curves at the tile grid. The
					// shoreline band is a separate pass below.
					g2.setColor(GroundTextures.WATER_BLUE);
					g2.fillRect(sx, sy, ts, ts);
					GroundTextures.drawWater(g2, sx, sy, ts, x, y);
					roundWaterCorners(g2, x, y, sx, sy, ts);
				} else if (t.getType() == Tile.TileType.TYPE_MUD) {
					// Soil substrate, then the mud texture feathered where it meets
					// non-mud so the patch melts into the earth at its border.
					g2.setColor(GroundTextures.SOIL);
					g2.fillRect(sx, sy, ts, ts);
					GroundTextures.drawFeathered(g2, sx, sy, ts, null, GroundTextures.terrain(t, hash),
							typeEdgeMask(x, y, Tile.TileType.TYPE_MUD));
				} else if (t.getType() == Tile.TileType.TYPE_COVER) {
					// Grass substrate (cover is lush), then the cover texture
					// feathered where it meets non-cover so the tall grass melts
					// into the surrounding sward instead of a hard square.
					g2.setColor(GroundTextures.GRASS_GREEN);
					g2.fillRect(sx, sy, ts, ts);
					GroundTextures.drawFeathered(g2, sx, sy, ts, null, GroundTextures.terrain(t, hash),
							typeEdgeMask(x, y, Tile.TileType.TYPE_COVER));
				} else {
					java.awt.image.BufferedImage tex = GroundTextures.terrain(t, hash);
					if (tex != null) {
						g2.drawImage(tex, sx, sy, ts, ts, null);
					}
				}
			}
		}

		// Second pass: the shoreline band, drawn over the finished ground so it
		// is one continuous organic band straddling the water/land boundary --
		// not clipped per tile, which is what made it look tile-aligned.
		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				if (tiles[x][y].getType() == Tile.TileType.TYPE_WATER) {
					drawShore(g2, x, y, ox + x * ts, oy + y * ts, ts);
				}
			}
		}
	}

	/**
	 * Low-res pixel ground: each tile is drawn as A×A chunky art-pixels, each
	 * coloured from its terrain-class ramp by a world-space shade noise. The
	 * terrain lookup is jittered by noise so class boundaries wander and dither
	 * across tile edges instead of snapping to the grid. Open ground (grass, soil,
	 * water, mud, cover) jitters hard for organic coastlines; walls and holes
	 * jitter only slightly (a couple of pixels) so they stay solid, never bleed
	 * out onto open ground, and get a simple top-lit bevel / rim for depth, plus a
	 * cast shadow on the ground just south of a wall.
	 */
	private void renderGroundPixel(Graphics2D g2, int ox, int oy) {
		int ts = ResourceManager.tileSize;
		long now = world.getTick();
		int A = 12; // art-pixels per tile
		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				Tile t = tiles[x][y];
				int cls = GroundTextures.groundClass(t, now);
				if (cls < 0) {
					continue; // ramps: baked layer shows through
				}
				boolean ownTight = cls == GroundTextures.CLS_WALL || cls == GroundTextures.CLS_HOLE;
				boolean wallN = isType(x, y - 1, Tile.TileType.TYPE_WALL);
				boolean wallS = isType(x, y + 1, Tile.TileType.TYPE_WALL);
				boolean holeN = isType(x, y - 1, Tile.TileType.TYPE_HOLE);
				boolean holeS = isType(x, y + 1, Tile.TileType.TYPE_HOLE);
				boolean holeW = isType(x - 1, y, Tile.TileType.TYPE_HOLE);
				boolean holeE = isType(x + 1, y, Tile.TileType.TYPE_HOLE);
				int sx = ox + x * ts, sy = oy + y * ts;
				for (int aj = 0; aj < A; aj++) {
					int by0 = aj * ts / A, by1 = (aj + 1) * ts / A;
					for (int ai = 0; ai < A; ai++) {
						int bx0 = ai * ts / A, bx1 = (ai + 1) * ts / A;
						double wx = x + (ai + 0.5) / A, wy = y + (aj + 0.5) / A;
						double amp = ownTight ? 0.22 : 0.9;
						double jx = wx + (Utils.noise2(wx + 3.1, wy, 1.1) - 0.5) * amp;
						double jy = wy + (Utils.noise2(wx, wy + 5.7, 1.1) - 0.5) * amp;
						int cl = groundClassAt((int) Math.floor(jx), (int) Math.floor(jy), now);
						if (cl < 0) {
							cl = cls;
						}
						if (!ownTight && (cl == GroundTextures.CLS_WALL || cl == GroundTextures.CLS_HOLE)) {
							cl = cls; // structures don't bleed out onto open ground
						}
						int col;
						int alpha = 255;
						if (cl == GroundTextures.CLS_WALL) {
							// Flat stone (no ground blobs) + a top-lit bevel, so it reads
							// as a solid raised mass rather than mottled camouflage.
							col = GroundTextures.rampColor(cl,
									(!wallN && aj < A * 0.28) ? 2 : (!wallS && aj >= A * 0.72) ? 0 : 1);
						} else if (cl == GroundTextures.CLS_HOLE) {
							// Rim on every side the pit meets ground: the north lip catches
							// the screen-north light brightest, the other three lips are the
							// same stone lip a touch dimmer, so the pit edge reads all round.
							int band = 3; // art-px rim thickness
							boolean nRim = !holeN && aj < band;
							boolean sRim = !holeS && aj >= A - band;
							boolean wRim = !holeW && ai < band;
							boolean eRim = !holeE && ai >= A - band;
							if (nRim) {
								col = GroundTextures.rampColor(cl, 2); // bright lit top lip
							} else if (sRim || wRim || eRim) {
								col = darken(GroundTextures.rampColor(cl, 2), 0.6); // dimmer side lips
							} else if (RenderFx.holeTranslucent) {
								// Translucent pit: a semi-transparent shade over the real level
								// below (composited underneath, and left unoccluded because the
								// baked hole tile is a cut-out). Its parallax comes for free from
								// the engine's per-level projection (Utils.scaleZ).
								col = GroundTextures.rampColor(cl, 0);
								alpha = (int) Math.round(RenderFx.holeDepth * 255);
							} else {
								col = GroundTextures.rampColor(cl, 1);
							}
						} else {
							col = GroundTextures.groundColor(cl, wx, wy); // organic blobs for open ground
							if (wallN && aj < A * 0.32) {
								col = darken(col, 0.62); // shadow cast by the wall to the north
							}
						}
						g2.setColor(alpha == 255 ? new Color(col)
								: new Color((col >> 16) & 255, (col >> 8) & 255, col & 255, alpha));
						g2.fillRect(sx + bx0, sy + by0, bx1 - bx0, by1 - by0);
					}
				}
			}
		}
	}

	private static int darken(int rgb, double f) {
		int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
		return (r << 16) | (g << 8) | b;
	}

	private int groundClassAt(int cx, int cy, long now) {
		if (cx < 0 || cy < 0 || cx >= world.cols || cy >= world.rows) {
			return -1;
		}
		return GroundTextures.groundClass(tiles[cx][cy], now);
	}

	/** Edge-fade bits (N=1, E=2, S=4, W=8) for edges whose neighbour isn't mottle. */
	private int mottleEdgeMask(int x, int y, long now) {
		int mask = 0;
		if (!neighbourMottle(x, y - 1, now)) {
			mask |= 1;
		}
		if (!neighbourMottle(x + 1, y, now)) {
			mask |= 2;
		}
		if (!neighbourMottle(x, y + 1, now)) {
			mask |= 4;
		}
		if (!neighbourMottle(x - 1, y, now)) {
			mask |= 8;
		}
		return mask;
	}

	/** Edge-fade bits for edges whose neighbour isn't green ground (grass or cover). */
	private int greenEdgeMask(int x, int y, long now) {
		int mask = 0;
		if (!greenNeighbour(x, y - 1, now)) {
			mask |= 1;
		}
		if (!greenNeighbour(x + 1, y, now)) {
			mask |= 2;
		}
		if (!greenNeighbour(x, y + 1, now)) {
			mask |= 4;
		}
		if (!greenNeighbour(x - 1, y, now)) {
			mask |= 8;
		}
		return mask;
	}

	/** A neighbour reads as green if it is tall-grass cover or grassed floor. */
	private boolean greenNeighbour(int nx, int ny, long now) {
		if (nx < 0 || ny < 0 || nx >= world.cols || ny >= world.rows) {
			return false;
		}
		Tile n = tiles[nx][ny];
		if (n.getType() == Tile.TileType.TYPE_COVER) {
			return true;
		}
		if (n.getType() != Tile.TileType.TYPE_FLOOR) {
			return false;
		}
		return GroundTextures.grassLevel(n.getVegetation(now) / Tile.VEG_MAX) >= 0;
	}

	/** Edge-fade bits for edges whose neighbour isn't the given terrain type. */
	private int typeEdgeMask(int x, int y, Tile.TileType type) {
		int mask = 0;
		if (!isType(x, y - 1, type)) {
			mask |= 1;
		}
		if (!isType(x + 1, y, type)) {
			mask |= 2;
		}
		if (!isType(x, y + 1, type)) {
			mask |= 4;
		}
		if (!isType(x - 1, y, type)) {
			mask |= 8;
		}
		return mask;
	}

	private boolean isType(int nx, int ny, Tile.TileType type) {
		if (nx < 0 || ny < 0 || nx >= world.cols || ny >= world.rows) {
			return false;
		}
		return tiles[nx][ny].getType() == type;
	}

	private boolean neighbourMottle(int nx, int ny, long now) {
		if (nx < 0 || ny < 0 || nx >= world.cols || ny >= world.rows) {
			return false;
		}
		Tile n = tiles[nx][ny];
		if (n.getType() != Tile.TileType.TYPE_FLOOR) {
			return false;
		}
		return GroundTextures.isMottle(GroundTextures.grassLevel(n.getVegetation(now) / Tile.VEG_MAX));
	}

	/**
	 * Rounds a water tile's convex corners -- those where both orthogonal
	 * neighbours are land -- by carving a quarter-circle of land out of the
	 * corner, so the water outline curves instead of stepping at the tile grid.
	 */
	private void roundWaterCorners(Graphics2D g2, int x, int y, int sx, int sy, int ts) {
		int r = ts / 2;
		boolean n = !neighbourWater(x, y - 1);
		boolean s = !neighbourWater(x, y + 1);
		boolean e = !neighbourWater(x + 1, y);
		boolean w = !neighbourWater(x - 1, y);
		if (n && w) {
			carveCorner(g2, sx, sy, sx + r, sy + r, r, 90); // NW
		}
		if (n && e) {
			carveCorner(g2, sx + ts - r, sy, sx + ts - r, sy + r, r, 0); // NE
		}
		if (s && w) {
			carveCorner(g2, sx, sy + ts - r, sx + r, sy + ts - r, r, 180); // SW
		}
		if (s && e) {
			carveCorner(g2, sx + ts - r, sy + ts - r, sx + ts - r, sy + ts - r, r, 270); // SE
		}
	}

	/** Fills the corner square with land, then restores a water quarter-disc. */
	private void carveCorner(Graphics2D g2, int squareX, int squareY, int cx, int cy, int r, int startAngle) {
		g2.setColor(GroundTextures.GRASS_GREEN);
		g2.fillRect(squareX, squareY, r, r);
		g2.setColor(GroundTextures.WATER_BLUE);
		g2.fillArc(cx - r, cy - r, 2 * r, 2 * r, startAngle, 90);
	}

	/**
	 * The shoreline band along a water tile's land-facing edges. Soft radial
	 * "shallows" blobs of varying reach are centred *on* the boundary so the band
	 * straddles the water/land line -- covering the tile step -- and, because this
	 * runs as a second pass over finished ground with no per-tile clip, it flows
	 * as one continuous organic band rather than tile-aligned rectangles. Foam
	 * flecks sit on top.
	 */
	private void drawShore(Graphics2D g2, int x, int y, int sx, int sy, int ts) {
		boolean n = !neighbourWater(x, y - 1);
		boolean e = !neighbourWater(x + 1, y);
		boolean s = !neighbourWater(x, y + 1);
		boolean w = !neighbourWater(x - 1, y);
		if (!(n || e || s || w)) {
			return;
		}
		Color c = GroundTextures.SHORE;
		Color core = new Color(c.getRed(), c.getGreen(), c.getBlue(), 130);
		Color clear = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
		if (n) {
			shallowBlobs(g2, x, y, 0, sx, sy, 1, 0, ts, core, clear);
		}
		if (s) {
			shallowBlobs(g2, x, y, 1, sx, sy + ts, 1, 0, ts, core, clear);
		}
		if (w) {
			shallowBlobs(g2, x, y, 2, sx, sy, 0, 1, ts, core, clear);
		}
		if (e) {
			shallowBlobs(g2, x, y, 3, sx + ts, sy, 0, 1, ts, core, clear);
		}

		// Foam flecks right at the waterline, jittered to either side.
		g2.setColor(new Color(215, 238, 250, 205));
		if (n) {
			foam(g2, x, y, 0, sx, sy, ts, true, +1);
		}
		if (s) {
			foam(g2, x, y, 1, sx, sy + ts, ts, true, -1);
		}
		if (w) {
			foam(g2, x, y, 2, sx, sy, ts, false, +1);
		}
		if (e) {
			foam(g2, x, y, 3, sx + ts, sy, ts, false, -1);
		}
	}

	/**
	 * Soft radial shallows blobs centred on one boundary edge: origin (bx,by)
	 * with an along-axis (dax,day). Each blob straddles the line and its radius
	 * varies from the tile hash, so the band's edges wander organically.
	 */
	private void shallowBlobs(Graphics2D g2, int x, int y, int edge, int bx, int by,
			int dax, int day, int ts, Color core, Color clear) {
		for (int i = 0; i < 8; i++) {
			int h = ((x * 928371) ^ (y * 1299709) ^ (edge * 40503) ^ (i * 2654435)) & 0x7fffffff;
			int along = (h % 100) * ts / 100;
			int r = ts / 6 + (h / 100) % (ts / 3);
			int cx = bx + dax * along;
			int cy = by + day * along;
			g2.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), Math.max(2, r),
					new float[] { 0f, 1f }, new Color[] { core, clear }));
			g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);
		}
	}

	/** Small light dots scattered along one waterline edge (deterministic). */
	private void foam(Graphics2D g2, int x, int y, int edge, int ex, int ey, int ts, boolean horizontal, int inward) {
		for (int i = 0; i < 5; i++) {
			int h = (x * 928371) ^ (y * 1299709) ^ (edge * 40503) ^ (i * 2654435);
			h &= 0x7fffffff;
			int along = (h % 100) * ts / 100;
			int off = inward * ((h / 100) % 5); // small inward jitter off the boundary
			int r = 2 + (h / 500) % 2;
			int cx = horizontal ? ex + along : ex + off;
			int cy = horizontal ? ey + off : ey + along;
			g2.fillOval(cx - r / 2, cy - r / 2, r, r);
		}
	}

	private boolean neighbourWater(int nx, int ny) {
		if (nx < 0 || ny < 0 || nx >= world.cols || ny >= world.rows) {
			return false;
		}
		return tiles[nx][ny].getType() == Tile.TileType.TYPE_WATER;
	}

	public void alignTiles() {

		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				Tile tile = tiles[x][y];
				tile.updateTilecode(world);
			}
		}

	}

	TreeMap<Double, Entity> searchEntity(double x, double y, double dir, double radius, double fov,
			String[] types, boolean include, int ID) {
		TreeMap<Double, Entity> result = new TreeMap<Double, Entity>();
		for (Entity e : world.entities.values()) {
			// TODO consider entities from different levels
			if (e != null && e.getLvl() == level) {
				if (!e.isDead()) {
					if ((World.includesType(e.getEntityTypeName(), types) && include)
							|| (World.excludesType(e.getEntityTypeName(), types) && !include)) {
						double dist = world.distance(x, y, level, e.getX(), e.getY(), e.getZ());
						if (hasLOS(x, y, dir, e.getX(), e.getY(), radius, fov)) {
							if (ID != e.getID()) {
								result.put(dist, e);
							}
						}
					}
				}
			}
		}
		return result;
	}

	private boolean isValid(int c, int r) {
		if (c < 0 || c >= world.cols) {
			return false;
		}
		if (r < 0 || r >= world.rows) {
			return false;
		}
		if (level < 0 || level >= world.lvls) {
			return false;
		}
		if (tiles[c][r] == null) {
			return false;
		}
		return true;
	}

	private boolean isValid(double x, double y) {
		if (x < 0 || x >= world.cols) {
			return false;
		}
		if (y < 0 || y >= world.rows) {
			return false;
		}
		if (level < 0 || level >= world.lvls) {
			return false;
		}
		if (tiles[(int) (x)][(int) (y)] == null) {
			return false;
		}
		return true;
	}

	boolean hasLOS(double x1, double y1, double dir, double x2, double y2, double dist, double fov) {
		// check to see if point is in range (squared compare: equivalent for
		// non-negative values, avoids a sqrt per candidate)
		double rdx = x2 - x1;
		double rdy = y2 - y1;
		if (dist >= 0 && rdx * rdx + rdy * rdy > dist * dist) {
			return false;
		}

		// angles and terrain make no difference
		if (fov == -1) {
			return true;
		}

		// check to see if point is in correct angle
		if (fov < Math.PI) {
			double angle = Math.atan2(y1 - y2, x1 - x2) + Math.PI;
			double d = dir;

			if (d >= 2 * Math.PI) {
				d -= 2 * Math.PI;
			}
			if (d < 0) {
				d += 2 * Math.PI;
			}

			if (angle > 2 * Math.PI) {
				angle -= 2 * Math.PI;
			}
			if (angle < 0) {
				angle += 2 * Math.PI;
			}

			double dA = angle - d;

			if (dA > Math.PI) {
				dA = -2 * Math.PI + dA;
			}
			if (dA < -Math.PI) {
				dA = 2 * Math.PI + dA;
			}

			if (Math.abs(dA) > fov) {
				return false;
			}

		}

		// tracing vector

		int xtiles = Math.abs((int) x2 - (int) x1);
		int ytiles = Math.abs((int) y2 - (int) y1);
		double tan = (y2 - y1) / (x2 - x1);
		double cot = (x2 - x1) / (y2 - y1);

		double px = x1, py = y1;
		for (int x = 1; x <= xtiles; x++) {
			double tx;
			if (x1 < x2) {
				tx = x - ((x1) - (int) x1);
			} else {
				// x1>x2
				tx = -((x - 1) + ((x1) - (int) x1));
			}
			double ty = (tan * tx) + y1;

			if (x1 < x2) {
				tx = (x1 + x);
			} else {
				tx = (x1 - x);
			}

			if (!isLosConnected((int) px, (int) py, (int) tx, (int) ty)) {
				return false;
			}
			px = tx;
			py = ty;
		}

		px = x1;
		py = y1;
		for (int y = 1; y <= ytiles; y++) {
			double ty;
			if (y1 < y2) {
				ty = y - ((y1) - (int) y1);
			} else {
				// y1>y2
				ty = -((y - 1) + ((y1) - (int) y1));
			}
			double tx = (cot * ty) + x1;

			if (y1 < y2) {
				ty = (y1 + y);
			} else {
				ty = (y1 - y);
			}

			if (!isLosConnected((int) px, (int) py, (int) tx, (int) ty)) {
				return false;
			}
			px = tx;
			py = ty;
		}

		return true;
	}

	private boolean isLosConnected(int c, int r, int c2, int r2) {
		if (!isValid(c, r)) {
			return false;
		}
		if (!isValid(c2, r2)) {
			return false;
		}
		// Opaque terrain (walls, tall-grass cover) blocks the sightline.
		if (tiles[c2][r2].blocksSight()) {
			return false;
		}
		return tiles[c][r].isConnected(world, c2, r2, level, false, false);
	}

	boolean setTile(int c, int r, int l, Tile.TileType t) {
		if (c < 1 || r < 1 || c >= world.cols - 1 || r >= world.rows - 1) {
			tiles[c][r] = new Tile(c, r, l, Tile.TileType.TYPE_WALL);
			return false;
		}
		tiles[c][r] = new Tile(c, r, l, t);
		return true;
	}

	void setTile(int c, int r, int z) {
		tiles[c][r] = new Tile(world, c, r, z);
	}

	Tile getTile(int c, int r) {
		if (r < 0 || c < 0) {
			return null;
		}
		return tiles[c][r];
	}

}