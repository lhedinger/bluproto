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

	HashMap<Integer, Sector> sectors;

	int counter = 0;

	Grid(World w, int c, int r, int l) {
		world = w;
		if (c < 1 || r < 1) {
			return;
		}

		tiles = new Tile[c][r];

		level = l;

		sectors = new HashMap<Integer, Sector>();
	}

	boolean aligned = false;

	public void think(World w) {
		// Doors used to think here; they are ordinary entities now and tick
		// with everything else in World.think.
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
			MapLayer ml0 = lr.mapLayers[level];
			if (ml0.image_layer != null) {
				g2.drawImage(ml0.image_layer, ox, oy, null);
			} else {
				drawBaseTiles(g2, ml0, ox, oy);
			}
			renderGround(g2, ox, oy);
			if (RenderFx.foliage) {
				if (RenderFx.grassTufts) {
					renderTallGrass(g2, ox, oy); // under the creatures, so they walk over it
				}
				renderShrubs(g2, ox, oy); // decorative bushes on the lushest tiles
			}
		} else {
			if (lr.mapLayers[level].image_layer_downsized != null) {
				g2.drawImage(lr.mapLayers[level].image_layer_downsized[camDepth - 1], ox, oy, null);
			}
		}

		// Structural furniture first, under the haze and the bodies: switch
		// wiring lowest (a door leaf may slide over its conduit), then doors.
		for (Entity e : world.entities.values()) {
			if ((e instanceof net.hedinger.prototype.entities.Switch
					|| e instanceof net.hedinger.prototype.entities.Nest) && e.getLvl() == level) {
				net.hedinger.prototype.render.EntityPainters.render(e, g, v);
			}
		}
		for (Entity e : world.entities.values()) {
			if (e instanceof net.hedinger.prototype.entities.Door && e.getLvl() == level) {
				net.hedinger.prototype.render.EntityPainters.render(e, g, v);
			}
		}
		// Pheromone clouds next, so the haze sits under the creatures.
		for (Entity e : world.entities.values()) {
			if (e instanceof PheromoneCloud && e.getLvl() == level) {
				net.hedinger.prototype.render.EntityPainters.render(e, g, v);
			}
		}
		for (Entity e : world.entities.values()) {
			if (e != null && !(e instanceof PheromoneCloud)
					&& !(e instanceof net.hedinger.prototype.entities.Door)
					&& !(e instanceof net.hedinger.prototype.entities.Switch)
				&& !(e instanceof net.hedinger.prototype.entities.Nest)
					&& e.getLvl() == level) {
				net.hedinger.prototype.render.EntityPainters.render(e, g, v);
			}
		}
		// What perception cannot see, the eye should only half see: re-stamp
		// foliage over creatures standing in walkable sight-blockers.
		if (camDepth == 0 && RenderFx.pixelGround && RenderFx.concealFoliage) {
			renderConcealment(g2, ox, oy);
		}
	}

	/**
	 * Concealment overlay, matching the web client's canopy pass: wherever a
	 * living creature stands in (or overlaps) a walkable sight-blocking tile
	 * -- thicket cover or a reed bed -- part of that tile's own foliage is
	 * re-stamped over the entity layer. Reeds redraw exactly their stalk
	 * pixels, so a body shows between the stalks; the closed canopy redraws
	 * clustered 2x2 blocks at roughly half coverage, so a body reads through
	 * gaps in the leaves rather than vanishing. Pixels regenerate from the
	 * same pure texture functions as the ground, so the overlay is invisible
	 * where it lands on identical ground pixels.
	 */
	private void renderConcealment(Graphics2D g2, int ox, int oy) {
		java.util.HashSet<Integer> veiled = new java.util.HashSet<Integer>();
		for (Entity e : world.entities.values()) {
			if (e == null || e instanceof PheromoneCloud || e.getLvl() != level || e.isDead()) {
				continue;
			}
			int ex = (int) e.getX(), ey = (int) e.getY();
			for (int dy = -1; dy <= 1; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					int tx = ex + dx, ty = ey + dy;
					if (tx < 0 || ty < 0 || tx >= world.cols || ty >= world.rows) {
						continue;
					}
					Tile t = tiles[tx][ty];
					if (t.blocksSight() && !t.isSolid()) {
						veiled.add(ty * world.cols + tx);
					}
				}
			}
		}
		int ts = ResourceManager.tileSize;
		int A = 12; // must match renderGroundPixel's art-pixel grid
		int reedGap = GroundTextures.rampColor(GroundTextures.CLS_REEDS, 0);
		// The design system's cover translucency: the whole veil draws at
		// VEIL_ALPHA, so the body underneath always half-reads through it.
		java.awt.Composite oldComposite = g2.getComposite();
		g2.setComposite(java.awt.AlphaComposite.getInstance(
				java.awt.AlphaComposite.SRC_OVER, GroundTextures.VEIL_ALPHA));
		for (int key : veiled) {
			int x = key % world.cols, y = key / world.cols;
			boolean reedBed = tiles[x][y].getType() == Tile.TileType.TYPE_REEDS;
			boolean duct = tiles[x][y].getType() == Tile.TileType.TYPE_DUCT;
			boolean ductVert = duct && (isType(x, y - 1, Tile.TileType.TYPE_DUCT)
					|| isType(x, y + 1, Tile.TileType.TYPE_DUCT));
			int sx = ox + x * ts, sy = oy + y * ts;
			for (int aj = 0; aj < A; aj++) {
				for (int ai = 0; ai < A; ai++) {
					int gx = x * A + ai, gy = y * A + aj;
					int col;
					if (duct) {
						// The duct's ribbed lid: the crawler shows in the slots.
						Integer lid = ductVert
								? GroundTextures.ductLid(gy, ai, gx, gy)
								: GroundTextures.ductLid(gx, aj, gx, gy);
						if (lid == null) {
							continue;
						}
						col = lid;
					} else if (reedBed) {
						col = GroundTextures.reeds(gx, gy);
						if (col == reedGap) {
							continue; // the body shows between the stalks
						}
					} else {
						if (GroundTextures.hash01(gx >> 1, gy >> 1, 61) > 0.55) {
							continue; // clustered gaps in the leaf veil
						}
						col = GroundTextures.canopy(x + (ai + 0.5) / A, y + (aj + 0.5) / A, gx, gy);
					}
					g2.setColor(new Color(col));
					g2.fillRect(sx + ai * ts / A, sy + aj * ts / A,
							(ai + 1) * ts / A - ai * ts / A, (aj + 1) * ts / A - aj * ts / A);
				}
			}
		}
		g2.setComposite(oldComposite);
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
		// Tight shadow tucked under the base so the shrub doesn't float:
		// a blocky translucent oval -- art-pixel steps on the outline, but
		// each block tints the ground instead of covering it.
		g2.setColor(new java.awt.Color(0, 0, 0, 110));
		double shRx = R * 1.05, shRy = R * 0.275, shCy = cy + R * 0.455;
		for (int by = (int) (-shRy / pix) - 1; by <= shRy / pix + 1; by++) {
			for (int bx = (int) (-shRx / pix) - 1; bx <= shRx / pix + 1; bx++) {
				double ex = bx * pix / shRx, ey = by * pix / shRy;
				if (ex * ex + ey * ey > 1) {
					continue;
				}
				g2.fillRect(cx + bx * pix, (int) shCy + by * pix, pix, pix);
			}
		}

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
	/** Draws the base floor+wall sprites tile-by-tile (the chunked-bake
	 *  substitute for blitting a precompiled whole-level image). Identical
	 *  placement to LayerRenderer.compileLayer, so output matches. */
	private void drawBaseTiles(Graphics2D g2, MapLayer ml, int ox, int oy) {
		int ts = ResourceManager.tileSize;
		int pad = ResourceManager.tilePadding;
		int sz = ts + pad * 2;
		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				int px = ox + x * ts - pad, py = oy + y * ts - pad;
				if (ml.floorTiles[x][y] != null) {
					g2.drawImage(ml.floorTiles[x][y], px, py, sz, sz, null);
				}
				if (ml.wallTiles[x][y] != null) {
					g2.drawImage(ml.wallTiles[x][y], px, py, sz, sz, null);
				}
			}
		}
	}

	/** Bakes the procedural ground into a level image whose pixel (0,0) is
	 *  tile (0,0) -- so a level seen from above, through pits, shafts and
	 *  catwalk grating, shows its real ground art rather than the bare base
	 *  sprites. */
	void bakeGround(Graphics2D g2) {
		renderGround(g2, 0, 0);
	}

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
	 * coloured from its terrain-class ramp by a world-space shade noise pushed
	 * through an ordered (Bayer) dither -- shade transitions are checkerboard
	 * mixes of adjacent ramp colours, never blends. The terrain lookup is
	 * jittered by noise so class boundaries wander and dither across tile edges
	 * instead of snapping to the grid. Open ground (soil, water, mud, cover)
	 * jitters hard for organic coastlines; water is a calm top-down
	 * surface of shadow/base patches with sparse glints, dry soil is plated by
	 * a dark Voronoi-ridge crack network (mud cracks finer, and turns into a
	 * darker crack-free wet band beside water). Walls and holes jitter only
	 * slightly (a couple of pixels) so they stay solid and never bleed out
	 * onto open ground; a wall shows a calm cross-section top with a lit north
	 * edge, a band of carved vertical face dashes where it fronts open ground
	 * to the south, and a cast shadow on the ground below.
	 *
	 * <p>Every pixel here is a pure function of tile type, position and the
	 * tile's static fertility potential — never of live state like standing
	 * vegetation — so the ground can be baked once: grassland shows how green
	 * it CAN be, and how much crop is actually on it stays the sprite layer's
	 * story. (Nutrient closure drifts fertility up over a world's life; a bake
	 * snapshots genesis, which is the potential the world was born with.)
	 */
	private void renderGroundPixel(Graphics2D g2, int ox, int oy) {
		int ts = ResourceManager.tileSize;
		int A = 12; // art-pixels per tile
		int[][] wdist = null; // tile distance-to-shore, built on first water pixel
		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				Tile t = tiles[x][y];
				int cls = GroundTextures.groundClass(t);
				if (cls < 0) {
					// Ramps: the baked tile sprite shows through, but the cut
					// still needs seating — see seatRamp.
					if (t.getType() == Tile.TileType.TYPE_RAMPUP) {
						seatRamp(g2, ox + x * ts, oy + y * ts, x, y, t.getRampUphill());
					}
					continue;
				}
				boolean ownTight = GroundTextures.isStructure(cls) || cls == GroundTextures.CLS_HOLE
						|| cls == GroundTextures.CLS_SHAFT || cls == GroundTextures.CLS_CATWALK;
				boolean wallN = wallSideFor(x, y - 1, x, y);
				boolean wallS = wallSideFor(x, y + 1, x, y);
				boolean wallE = wallSideFor(x + 1, y, x, y);
				boolean wallW = wallSideFor(x - 1, y, x, y);
				// A pit rims every side it meets ground — except where a DOWN ramp
				// pours in: the ramp's own bands fade to the pit's dark, so a lip
				// between them would cut the descent with an earth line.
				boolean holeN = isType(x, y - 1, Tile.TileType.TYPE_HOLE)
						|| isType(x, y - 1, Tile.TileType.TYPE_RAMPDOWN);
				boolean holeS = isType(x, y + 1, Tile.TileType.TYPE_HOLE)
						|| isType(x, y + 1, Tile.TileType.TYPE_RAMPDOWN);
				boolean holeW = isType(x - 1, y, Tile.TileType.TYPE_HOLE)
						|| isType(x - 1, y, Tile.TileType.TYPE_RAMPDOWN);
				boolean holeE = isType(x + 1, y, Tile.TileType.TYPE_HOLE)
						|| isType(x + 1, y, Tile.TileType.TYPE_RAMPDOWN);
				int sx = ox + x * ts, sy = oy + y * ts;
				for (int aj = 0; aj < A; aj++) {
					int by0 = aj * ts / A, by1 = (aj + 1) * ts / A;
					for (int ai = 0; ai < A; ai++) {
						int bx0 = ai * ts / A, bx1 = (ai + 1) * ts / A;
						double wx = x + (ai + 0.5) / A, wy = y + (aj + 0.5) / A;
						int cl;
						if (ownTight) {
							if (cls == GroundTextures.CLS_SHAFT || cls == GroundTextures.CLS_CATWALK) {
								cl = cls; // facility geometry: dead straight, no jitter
							} else {
								// Structures keep a whisper of jitter so rock edges
								// aren't laser-cut; they never trade pixels with
								// open ground either way.
								double jx = wx + (Utils.noise2(wx + 3.1, wy, 2.8) - 0.5) * 0.2;
								double jy = wy + (Utils.noise2(wx, wy + 5.7, 2.8) - 0.5) * 0.2;
								cl = groundClassAt((int) Math.floor(jx), (int) Math.floor(jy));
								if (cl < 0 || !(GroundTextures.isStructure(cl)
										|| cl == GroundTextures.CLS_HOLE)) {
									cl = cls;
								}
							}
						} else {
							// Open ground: autotiled edges. Boundaries are drawn
							// shapes, not noise -- the higher-priority terrain laps
							// into its lower neighbour with rounded corners and
							// short hand-scallop runs (see resolveEdge).
							cl = resolveEdge(x, y, ai, aj, cls);
						}
						int gx = x * A + ai, gy = y * A + aj; // world-absolute art-pixel
						int col;
						boolean wallCls = cl == GroundTextures.CLS_WALL
								|| cl == GroundTextures.CLS_WALL_BUILT
								|| cl == GroundTextures.CLS_CONCRETE
								|| cl == GroundTextures.CLS_STEELWALL;
						if (wallCls) {
							// Every wall material is two surfaces from above: the flat
							// cross-section top, and a face band where the mass fronts
							// open ground to the south; wallDepth then adds the raised
							// read -- cornice, base shadow, silhouette rims.
							boolean face = !wallS && aj >= A * 0.55;
							boolean lit = !wallN && aj < A * 0.28;
							if (cl == GroundTextures.CLS_WALL) {
								col = face ? GroundTextures.wallFace(gx, gy)
										: GroundTextures.wallTop(gx, gy, lit);
							} else if (cl == GroundTextures.CLS_WALL_BUILT) {
								col = face ? GroundTextures.wallFaceBuilt(gx, gy)
										: GroundTextures.wallTopBuilt(gx, gy, lit);
							} else if (cl == GroundTextures.CLS_CONCRETE) {
								col = face ? GroundTextures.concreteFace(gx, gy)
										: GroundTextures.concreteTop(gx, gy, lit);
							} else {
								col = face ? GroundTextures.steelFace(gx, gy)
										: GroundTextures.steelTop(gx, gy, lit);
							}
							col = wallDepth(col, ai, aj, A, wallS, wallE, wallW);
						} else if (cl == GroundTextures.CLS_SERVER) {
							// A rack is a solid and takes the raised read like any
							// mass, but it is a cabinet rather than a poured wall:
							// one cross-section face, no separate south face band,
							// because the thing is the same all the way down.
							boolean litRack = aj < A * 0.28;
							col = GroundTextures.serverRack(ai, aj, gx, gy, litRack);
							col = wallDepth(col, ai, aj, A, wallS, wallE, wallW);
						} else if (cl == GroundTextures.CLS_CRYSTAL) {
							// A dense formation is a thicket of standing prisms on
							// the cave floor -- each prism self-shaded with its own
							// contact shadow, no wall grammar.
							col = GroundTextures.crystal(wx, wy, gx, gy);
						} else if (cl == GroundTextures.CLS_HOLE) {
							// Rim on every side the pit meets ground, as pixel-art: the
							// lit north lip is a broken run of dashes whose depth varies
							// per column (a crumbling cut edge, not a solid band); the
							// other lips are a thin dimmer edge with dithered dropouts.
							int nDepth = 2 + (int) (GroundTextures.hash01(gx, 0, 20) * 2.99);
							boolean nRim = !holeN && aj < nDepth;
							boolean sRim = !holeS && aj >= A - 2;
							boolean wRim = !holeW && ai < 2;
							boolean eRim = !holeE && ai >= A - 2;
							if (nRim) {
								col = GroundTextures.rampColor(cl,
										GroundTextures.bayer(gx, gy) < 0.75 ? 2 : 1);
							} else if (sRim || wRim || eRim) {
								col = GroundTextures.bayer(gx, gy) < 0.35
										? GroundTextures.rampColor(cl, 0)
										: darken(GroundTextures.rampColor(cl, 2), 0.6);
							} else if (cls != GroundTextures.CLS_HOLE) {
								// A BORROWED hole pixel: the jitter above lets a
								// structure's edge nibble into a pit it touches, so
								// rock does not meet a hole in a laser-cut line. It
								// may take the pit's colour; it may not take the pit's
								// transparency. Since a pit's open pixels became real
								// alpha, punching one here cut a window clean through
								// solid rock -- eight of them in the demo world's
								// caves, each a hole in a wall you could see the floor
								// below through. The nibble is a shape, not an opening.
								col = GroundTextures.rampColor(cl, 0);
							} else {
								Integer pit = pitFloor(x, y, gx, gy, cl);
								if (pit == null) {
									openPixel(g2, sx + bx0, sy + by0, bx1 - bx0, by1 - by0);
									continue;
								}
								col = pit;
							}
						} else if (cl == GroundTextures.CLS_SHAFT) {
							// Vertical shaft: a hazard-striped lip on every side that
							// meets standing ground, then the industrial void -- read
							// the same way as a natural pit, so a drop-shaft shows the
							// base floor it drops onto.
							boolean vN = isVoidish(x, y - 1), vS = isVoidish(x, y + 1);
							boolean vW = isVoidish(x - 1, y), vE = isVoidish(x + 1, y);
							int band = 2;
							if ((!vN && aj < band) || (!vS && aj >= A - band)
									|| (!vW && ai < band) || (!vE && ai >= A - band)) {
								col = GroundTextures.hazardStripe(gx, gy);
							} else {
								Integer pit = pitFloor(x, y, gx, gy, cl);
								if (pit == null) {
									openPixel(g2, sx + bx0, sy + by0, bx1 - bx0, by1 - by0);
									continue;
								}
								col = pit;
							}
						} else if (cl == GroundTextures.CLS_CATWALK) {
							// Grated walkway: rails and cross-treads, with the void
							// (and whatever lies below) showing through the gaps.
							boolean ns = isType(x, y - 1, Tile.TileType.TYPE_CATWALK)
									|| isType(x, y + 1, Tile.TileType.TYPE_CATWALK);
							boolean ew = isType(x - 1, y, Tile.TileType.TYPE_CATWALK)
									|| isType(x + 1, y, Tile.TileType.TYPE_CATWALK);
							Integer cw = ns && !ew
									? GroundTextures.catwalk(gy, ai, gx, gy)
									: GroundTextures.catwalk(gx, aj, gx, gy);
							// A grate gap is a small pit, and takes a pit's treatment:
							// the void shade, scattered open onto whatever lies below.
							if (cw == null) {
								cw = pitFloor(x, y, gx, gy, GroundTextures.CLS_HOLE);
								if (cw == null) {
									openPixel(g2, sx + bx0, sy + by0, bx1 - bx0, by1 - by0);
									continue;
								}
							}
							col = cw;
						} else if (cl == GroundTextures.CLS_WATER
								|| cl == GroundTextures.CLS_SHALLOWS) {
							// One water surface from wading fringe to abyss: both
							// classes render the same shore-distance continuum, so
							// the walkable/unwalkable line is invisible in the
							// picture -- the same technique as the deep gradient.
							if (wdist == null) {
								wdist = waterDist();
							}
							col = GroundTextures.waterSurface(wx, wy, gx, gy, depthAt(wdist, wx, wy));
							if (wallN) {
								col = groundShadow(col, aj); // graded shadow from the wall north
							}
						} else {
							if (cl == GroundTextures.CLS_PLATE) {
								col = GroundTextures.plate(gx, gy);
							} else if (cl == GroundTextures.CLS_DUCT) {
								// The duct's open channel, oriented along its run; the
								// lid re-stamps over crawlers in the concealment pass.
								boolean vert = isType(x, y - 1, Tile.TileType.TYPE_DUCT)
										|| isType(x, y + 1, Tile.TileType.TYPE_DUCT);
								col = vert
										? GroundTextures.ductChannel(gy, ai, gx, gy)
										: GroundTextures.ductChannel(gx, aj, gx, gy);
							} else if (cl == GroundTextures.CLS_PIPES) {
								// Autotiled like the rails: the generator lays tiles and
								// the gallery works out its own elbows, tees and caps.
								int pipeMask =
										(isType(x, y - 1, Tile.TileType.TYPE_PIPES) ? GroundTextures.RAIL_N : 0)
										| (isType(x + 1, y, Tile.TileType.TYPE_PIPES) ? GroundTextures.RAIL_E : 0)
										| (isType(x, y + 1, Tile.TileType.TYPE_PIPES) ? GroundTextures.RAIL_S : 0)
										| (isType(x - 1, y, Tile.TileType.TYPE_PIPES) ? GroundTextures.RAIL_W : 0);
								Integer p = GroundTextures.pipes(pipeMask, ai, aj, gx, gy);
								col = p != null ? p : GroundTextures.plate(gx, gy);
							} else if (cl == GroundTextures.CLS_AIRVENT) {
								col = GroundTextures.airVent(ai, aj, gx, gy);
							} else if (cl == GroundTextures.CLS_SWITCH) {
								col = GroundTextures.switchPlate(ai, aj, gx, gy);
							} else if (cl == GroundTextures.CLS_DOCK) {
								col = GroundTextures.chargeDock(ai, aj, gx, gy);
							} else if (cl == GroundTextures.CLS_TREADPLATE) {
								col = GroundTextures.treadPlate(gx, gy);
							} else if (cl == GroundTextures.CLS_LIGHTGRATE) {
								col = GroundTextures.litGrate(ai, aj, gx, gy);
							} else if (cl == GroundTextures.CLS_COLLAPSE) {
								col = GroundTextures.collapsedDeck(wx, wy, gx, gy);
							} else if (cl == GroundTextures.CLS_COOLANT) {
								// Lagged pipework runs along its own connectivity, the
								// same way the working pipes and the ducts do.
								boolean vertCool = isType(x, y - 1, Tile.TileType.TYPE_COOLANT)
										|| isType(x, y + 1, Tile.TileType.TYPE_COOLANT);
								col = vertCool ? GroundTextures.coolantRun(gy, ai, gx, gy)
										: GroundTextures.coolantRun(gx, aj, gx, gy);
							} else if (cl == GroundTextures.CLS_EXCHANGER) {
								col = GroundTextures.heatExchanger(ai, aj, gx, gy);
							} else if (cl == GroundTextures.CLS_SLUDGE) {
								col = GroundTextures.sludge(wx, wy, gx, gy);
							} else if (cl == GroundTextures.CLS_RAIL) {
								// Autotiled from the sides the run continues into, so
								// the track works out its own geometry: straights,
								// curves, points and crossings all fall out of the
								// same four bits. The generator lays tiles; the shape
								// is the track's business.
								int railMask =
										(isType(x, y - 1, Tile.TileType.TYPE_RAIL) ? GroundTextures.RAIL_N : 0)
										| (isType(x + 1, y, Tile.TileType.TYPE_RAIL) ? GroundTextures.RAIL_E : 0)
										| (isType(x, y + 1, Tile.TileType.TYPE_RAIL) ? GroundTextures.RAIL_S : 0)
										| (isType(x - 1, y, Tile.TileType.TYPE_RAIL) ? GroundTextures.RAIL_W : 0);
								col = GroundTextures.rail(railMask, ai, aj, gx, gy);
							} else if (cl == GroundTextures.CLS_QUICKSAND) {
								col = GroundTextures.quicksand(wx, wy, gx, gy);
							} else if (cl == GroundTextures.CLS_VENT) {
								col = GroundTextures.vent(ai, aj, gx, gy);
							} else if (cl == GroundTextures.CLS_PAVED) {
								col = GroundTextures.paved(gx, gy);
							} else if (cl == GroundTextures.CLS_FUNGUS) {
								// Bioluminescent beds at their full ideal density: the
								// ground shows what the tile IS; how much is left to
								// graze is the vegetation sprite layer's job.
								col = GroundTextures.fungus(wx, wy, gx, gy, 1);
							} else if (cl == GroundTextures.CLS_CRYSTAL_BED) {
								col = GroundTextures.crystalBed(wx, wy, gx, gy);
							} else if (cl == GroundTextures.CLS_CRYSTAL_SPARSE) {
								col = GroundTextures.crystalSparse(wx, wy, gx, gy);
							} else if (cl == GroundTextures.CLS_RUBBLE) {
								col = GroundTextures.rubble(gx, gy);
							} else if (cl == GroundTextures.CLS_SAND) {
								col = GroundTextures.sand(gx, gy);
							} else if (cl == GroundTextures.CLS_REEDS) {
								col = GroundTextures.reeds(gx, gy);
							} else if (cl == GroundTextures.CLS_COVER) {
								// Thicket: a canopy of self-shaded leaf clumps whose
								// character varies stand by stand.
								col = GroundTextures.canopy(wx, wy, gx, gy);
							} else if (cl == GroundTextures.CLS_ROCKY) {
								// Rocky grassland: stone slabs bedded in grit, with
								// what sward the thin ground can keep. Reads the same
								// fertility potential the meadow does.
								col = GroundTextures.rockyGround(fertAt(wx, wy), wx, wy, gx, gy);
							} else if (cl == GroundTextures.CLS_SOIL) {
								// Grassland: the sward drawn at the tile's fertility
								// potential — dry cracked clay where nothing can grow,
								// closed green where the ground is rich. Sampled
								// bilinearly so rich and poor blend mid-tile instead
								// of stepping at the grid.
								col = GroundTextures.sward(fertAt(wx, wy), wx, wy, gx, gy);
							} else {
								// Mineral ground (mud, stone): quiet plate interiors
								// under the crack-network seams.
								col = GroundTextures.quietGround(cl, wx, wy, gx, gy);
								if (cl == GroundTextures.CLS_STONE
										&& GroundTextures.crack(wx, wy, 0.8, 0.06)) {
									// Stone floor: fitted flagstone slabs, shadow-shade joints.
									col = GroundTextures.rampColor(cl, 0);
								} else if (cl == GroundTextures.CLS_MUD) {
									if (nearWater(x, y)) {
										// Wet shore band: darker, crack-free mud melting into
										// the water's shadow shade at the jittered boundary.
										col = darken(col, 0.74);
									} else if (GroundTextures.crack(wx, wy, 0.35, 0.05)) {
										// Drier mud crumbles into fine pebble-sized plates.
										col = GroundTextures.rampColor(cl, 0);
									}
								}
							}
							if (wallN) {
								col = groundShadow(col, aj); // graded shadow from the wall north
							}
						}
						g2.setColor(new Color(col));
						g2.fillRect(sx + bx0, sy + by0, bx1 - bx0, by1 - by0);
					}
				}
			}
		}
	}

	/**
	 * Depth cues that make a wall read as a raised mass, applied on top of
	 * any wall material's texture: a bright cornice line where the cap rolls
	 * over into the face, the face darkening toward its base (its contact
	 * shadow with the ground), and a dark silhouette rim on cap edges that
	 * meet open ground east or west.
	 */
	private static int wallDepth(int col, int ai, int aj, int A,
			boolean wallS, boolean wallE, boolean wallW) {
		int faceTop = (int) (A * 0.55);
		if (!wallS && aj >= faceTop) {
			if (aj == faceTop) {
				return lighten(col, 1.35); // the cornice catches the light
			}
			double f = 1.0 - 0.45 * (aj - faceTop) / (double) (A - 1 - faceTop);
			return darken(col, f); // the face sinks into its own base shadow
		}
		if ((!wallW && ai == 0) || (!wallE && ai == A - 1)) {
			return darken(col, 0.6); // crisp cap silhouette against open ground
		}
		return col;
	}

	/** The graded shadow a wall casts on standing ground to its south:
	 *  deepest at the foot, fading out over five art-pixels. */
	private static int groundShadow(int col, int aj) {
		if (aj < 2) {
			return darken(col, 0.5);
		}
		if (aj < 4) {
			return darken(col, 0.68);
		}
		if (aj < 5) {
			return darken(col, 0.84);
		}
		return col;
	}

	private static int lighten(int rgb, double f) {
		int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
		int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
		int b = Math.min(255, (int) ((rgb & 255) * f));
		return (r << 16) | (g << 8) | b;
	}

	/** Punches an art-pixel clear through everything already drawn there — the
	 *  tile sprite included. A pit's open pixels have to reach the client as
	 *  REAL transparency, not as the hole sprite's 59%-black veil: that veil is
	 *  a picture of depth painted for the desktop compositor, and leaving it on
	 *  brought the floor below through at 41% brightness, which read as black.
	 *  The darkness a pit needs is already carried by the void-shade scatter
	 *  around these pixels (see pitFloor); what shows between them should be
	 *  the floor exactly as bright as it is. */
	private static void openPixel(Graphics2D g2, int x, int y, int w, int h) {
		java.awt.Composite prev = g2.getComposite();
		g2.setComposite(java.awt.AlphaComposite.Clear);
		g2.fillRect(x, y, w, h);
		g2.setComposite(prev);
	}

	/**
	 * Seats an up ramp into the rock it climbs into. The ramp sprite alone
	 * reads as a bright band floating in front of flat ground: the rock casts
	 * its base shadow on every ground pixel at its foot EXCEPT the ramp,
	 * because ramps skip the ground pass — so the one tile that actually
	 * touches the rock was the one tile with no contact shadow. This paints
	 * the recess back in: a graded band along the uphill edge (the gloom just
	 * inside the cut, under the rock's brow — a recess cue, not a sun shadow,
	 * so it holds for all four headings) and a thin rim along each flank a
	 * wall runs beside, the cheeks of the cut. Translucent black over the
	 * sprite, §4's sanctioned contact shadow.
	 */
	private void seatRamp(Graphics2D g2, int sx, int sy, int x, int y, int up) {
		int A = 12, ts = ResourceManager.tileSize;
		int[] alpha = { 110, 70, 40 }; // grade out from the rock
		java.awt.Color[] shade = new java.awt.Color[alpha.length];
		for (int i = 0; i < alpha.length; i++) {
			shade[i] = new java.awt.Color(0, 0, 0, alpha[i]);
		}
		if (isWallish(x + Tile.dirDx(up), y + Tile.dirDy(up))) {
			for (int d = 0; d < alpha.length; d++) {
				g2.setColor(shade[d]);
				// The art-pixel row (or column) d steps in from the uphill edge.
				int lo = d * ts / A, hi = (d + 1) * ts / A;
				switch (up) {
				case Tile.DIR_N -> g2.fillRect(sx, sy + lo, ts, hi - lo);
				case Tile.DIR_S -> g2.fillRect(sx, sy + ts - hi, ts, hi - lo);
				case Tile.DIR_W -> g2.fillRect(sx + lo, sy, hi - lo, ts);
				default -> g2.fillRect(sx + ts - hi, sy, hi - lo, ts);
				}
			}
		}
		// The cut's cheeks: a 1-art-px rim on each side a wall runs along.
		int px = ts / A;
		g2.setColor(new java.awt.Color(0, 0, 0, 90));
		boolean vertical = up == Tile.DIR_N || up == Tile.DIR_S;
		if (vertical) {
			if (isWallish(x - 1, y)) {
				g2.fillRect(sx, sy, px, ts);
			}
			if (isWallish(x + 1, y)) {
				g2.fillRect(sx + ts - px, sy, px, ts);
			}
		} else {
			if (isWallish(x, y - 1)) {
				g2.fillRect(sx, sy, ts, px);
			}
			if (isWallish(x, y + 1)) {
				g2.fillRect(sx, sy + ts - px, ts, px);
			}
		}
	}

	private static int darken(int rgb, double f) {
		int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
		return (r << 16) | (g << 8) | b;
	}

	/**
	 * The inside of a pit, below its rim. A hole with a floor under it is not a
	 * void: a little light reaches down, so a scatter of the material below
	 * shows through — its own texture in its own palette, over the pit's dark,
	 * at {@code 1 - holeDepth} of the art-pixels. A pit with nothing under it
	 * stays dark all the way.
	 *
	 * <p>The scatter is HASHED, not Bayer-thresholded: an ordered matrix at a
	 * fixed threshold lays a regular halftone over the hole, which reads as a
	 * mesh stretched across it. This is not a gradient between two shades, it is
	 * broken sight of something far away — the guide's rule for that is a hash.
	 *
	 * <p>The open pixels are left UNPAINTED — {@code null}, and the caller skips
	 * them — so the bake carries a real alpha hole rather than a picture of one.
	 * Both renderers then show the actual floor below through it, and both move
	 * it with the parallax of its own depth. A painted stand-in could only ever
	 * show the material, never the place: the same rock in the same spot however
	 * the camera moved, which is a texture of a pit and not a view down one.
	 */
	private Integer pitFloor(int x, int y, int gx, int gy, int cl) {
		if (!RenderFx.holeTranslucent) {
			return GroundTextures.rampColor(cl, 1); // opaque style: a shallow dark floor
		}
		int dark = GroundTextures.rampColor(cl, 0);
		if (level - 1 < 0) {
			return dark; // the lowest floor: its pits are bottomless
		}
		if (GroundTextures.groundClass(world.getTile(x, y, level - 1)) < 0) {
			return dark; // a ramp down there: nothing flat to look down onto
		}
		// 2-px opening clusters (gx >> 1), the grain rule from quietGround: a
		// lone open art-pixel is sub-pixel at the fit zoom the world opens at,
		// and a reveal built from them was invisible right where people pan.
		return GroundTextures.hash01(gx >> 1, gy, 71) < RenderFx.holeDepth ? (Integer) dark : null;
	}

	private int groundClassAt(int cx, int cy) {
		if (cx < 0 || cy < 0 || cx >= world.cols || cy >= world.rows) {
			return -1;
		}
		return GroundTextures.groundClass(tiles[cx][cy]);
	}

	/** Fertility potential at a world position: bilinear over tile centres, so
	 *  the sward's richness glides across tile edges instead of stepping at
	 *  the grid. Every tile carries a fertility (world-gen zeroes rock and
	 *  water), so the field is defined everywhere — the ground class decides
	 *  what actually shows, this only decides how green a sward pixel is. */
	private double fertAt(double wx, double wy) {
		double fx = wx - 0.5, fy = wy - 0.5;
		int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
		double tx = fx - x0, ty = fy - y0;
		double f00 = fertTile(x0, y0), f10 = fertTile(x0 + 1, y0);
		double f01 = fertTile(x0, y0 + 1), f11 = fertTile(x0 + 1, y0 + 1);
		return (f00 * (1 - tx) + f10 * tx) * (1 - ty)
				+ (f01 * (1 - tx) + f11 * tx) * ty;
	}

	private double fertTile(int x, int y) {
		x = x < 0 ? 0 : (x >= world.cols ? world.cols - 1 : x);
		y = y < 0 ? 0 : (y >= world.rows ? world.rows - 1 : y);
		return tiles[x][y].getFertility();
	}

	/** Draw-order rank for open-ground autotiling: the higher terrain laps
	 *  over the lower at their shared edge (grass overhangs soil, fungus laps
	 *  onto stone, everything laps over water). Structures, holes and paving
	 *  do not participate: their edges are their own. */
	private static int edgeRank(int cls) {
		switch (cls) {
		case GroundTextures.CLS_WATER: return 0;
		case GroundTextures.CLS_SHALLOWS: return 1;
		case GroundTextures.CLS_MUD: return 2;
		case GroundTextures.CLS_QUICKSAND: return 3;
		case GroundTextures.CLS_SAND: return 4;
		case GroundTextures.CLS_SOIL: return 5;
		// Rocky ground laps over meadow and bare rock laps over rocky, so the
		// slope from pasture to outcrop always reads in that order.
		case GroundTextures.CLS_ROCKY: return 6;
		case GroundTextures.CLS_STONE: return 7;
		case GroundTextures.CLS_CRYSTAL_SPARSE: return 8;
		case GroundTextures.CLS_CRYSTAL_BED: return 9;
		case GroundTextures.CLS_RUBBLE: return 10;
		case GroundTextures.CLS_VENT: return 11;
		case GroundTextures.CLS_FUNGUS: return 12;
		case GroundTextures.CLS_REEDS: return 13;
		case GroundTextures.CLS_COVER: return 14;
		default: return -1; // structures, holes, paving: no lapping
		}
	}

	/** Scalloped lap depth (art-px) at position {@code t} along an edge: short
	 *  hash-picked runs of depth 1-3, the hand-drawn scallop of a tileset. */
	private static int lapDepth(int t, int salt) {
		int seg = Math.floorDiv(t, 4); // 4-px scallop runs
		return 1 + (int) (GroundTextures.hash01(seg, salt, 75) * 2.99);
	}

	/**
	 * Autotiled open-ground boundary: returns the class that owns art-pixel
	 * (ai,aj) of tile (x,y). A higher-ranked cardinal neighbour laps into
	 * this tile by a scalloped 1-3 px band along the shared edge; where two
	 * lapping edges meet, and at outer corners against a higher diagonal
	 * neighbour, the lap rounds into a quarter-circle -- so borders are drawn
	 * shapes with rounded corners, deterministic and tile-anchored, never
	 * noise. Ties (equal rank) keep the straight tile edge.
	 */
	private int resolveEdge(int x, int y, int ai, int aj, int cls) {
		int A = 12;
		int own = edgeRank(cls);
		if (own < 0) {
			return cls;
		}
		int gx = x * A + ai, gy = y * A + aj;
		// Cardinal laps, deepest wins so corner pockets fill naturally.
		int best = cls, bestDepth = 0;
		int n = lapClassAt(x, y - 1, own);
		if (n >= 0 && aj < lapDepth(gx, n * 4 + 0)) {
			int d = lapDepth(gx, n * 4 + 0) - aj;
			if (d > bestDepth) { best = n; bestDepth = d; }
		}
		int s = lapClassAt(x, y + 1, own);
		if (s >= 0 && aj >= A - lapDepth(gx, s * 4 + 1)) {
			int d = aj - (A - lapDepth(gx, s * 4 + 1)) + 1;
			if (d > bestDepth) { best = s; bestDepth = d; }
		}
		int w = lapClassAt(x - 1, y, own);
		if (w >= 0 && ai < lapDepth(gy, w * 4 + 2)) {
			int d = lapDepth(gy, w * 4 + 2) - ai;
			if (d > bestDepth) { best = w; bestDepth = d; }
		}
		int e = lapClassAt(x + 1, y, own);
		if (e >= 0 && ai >= A - lapDepth(gy, e * 4 + 3)) {
			int d = ai - (A - lapDepth(gy, e * 4 + 3)) + 1;
			if (d > bestDepth) { best = e; bestDepth = d; }
		}
		if (bestDepth > 0) {
			return best;
		}
		// Rounded outer corners: where two cardinals share a higher diagonal
		// neighbour, the diagonal's lap rounds the corner with a quarter-arc.
		int R = 4; // corner radius, art-px
		int nw = lapClassAt(x - 1, y - 1, own);
		if (nw >= 0 && ai < R && aj < R && dist2(ai, aj, R, R) > R * R) {
			return nw;
		}
		int ne = lapClassAt(x + 1, y - 1, own);
		if (ne >= 0 && ai >= A - R && aj < R && dist2(ai, aj, A - 1 - R, R) > R * R) {
			return ne;
		}
		int sw = lapClassAt(x - 1, y + 1, own);
		if (sw >= 0 && ai < R && aj >= A - R && dist2(ai, aj, R, A - 1 - R) > R * R) {
			return sw;
		}
		int se = lapClassAt(x + 1, y + 1, own);
		if (se >= 0 && ai >= A - R && aj >= A - R && dist2(ai, aj, A - 1 - R, A - 1 - R) > R * R) {
			return se;
		}
		return cls;
	}

	/** The neighbour's class if it out-ranks {@code ownRank} (so it laps into
	 *  this tile), else -1. */
	private int lapClassAt(int cx, int cy, int ownRank) {
		int c = groundClassAt(cx, cy);
		if (c < 0) {
			return -1;
		}
		int r = edgeRank(c);
		return r > ownRank ? c : -1;
	}

	private static int dist2(int ax, int ay, int bx, int by) {
		int dx = ax - bx, dy = ay - by;
		return dx * dx + dy * dy;
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

	/** A wall for lighting purposes: natural rock, masonry, concrete, or a
	 *  steel bulkhead. A crystal formation is deliberately NOT wallish: it is
	 *  a thicket of prisms, and each prism carries its own depth cues. */
	private boolean isWallish(int nx, int ny) {
		return isType(nx, ny, Tile.TileType.TYPE_WALL)
				|| isType(nx, ny, Tile.TileType.TYPE_WALL_BUILT)
				|| isType(nx, ny, Tile.TileType.TYPE_WALL_CONCRETE)
				|| isType(nx, ny, Tile.TileType.TYPE_WALL_STEEL);
	}

	/**
	 * As above, but asked on behalf of the tile at {@code (tx, ty)}: an up ramp
	 * that climbs into THAT tile counts as wall on that side.
	 *
	 * <p>Because it is not open ground. A wall raises a cliff face wherever its
	 * mass fronts something a body could stand on lower down — but the head of a
	 * ramp is where the climb ARRIVES, level with the rock, and a cliff face
	 * drawn across it says the opposite of what the ramp means: it walls off the
	 * exact tile the slope exists to reach. The face survived the autotiling fix
	 * because it is not autotiled — the wall's mass comes from the tile sprite,
	 * while its lighting is decided here in the ground pass, and only the first
	 * of the two had been taught about ramps.
	 */
	private boolean wallSideFor(int nx, int ny, int tx, int ty) {
		if (isWallish(nx, ny)) {
			return true;
		}
		if (!isType(nx, ny, Tile.TileType.TYPE_RAMPUP)) {
			return false;
		}
		int up = tiles[nx][ny].getRampUphill();
		return nx + Tile.dirDx(up) == tx && ny + Tile.dirDy(up) == ty;
	}

	/** Open vertical space, for shaft rims: the drop continues into another
	 *  shaft, a natural hole, or under a catwalk. */
	private boolean isVoidish(int nx, int ny) {
		return isType(nx, ny, Tile.TileType.TYPE_SHAFT)
				|| isType(nx, ny, Tile.TileType.TYPE_HOLE)
				|| isType(nx, ny, Tile.TileType.TYPE_CATWALK);
	}

	/**
	 * Per-tile distance to the nearest true-land tile, in tiles (0 on land,
	 * ~1 on the walkable shallows band, ~2 at the first open-water tile,
	 * rising inward) -- a multi-source BFS over the level, built once per
	 * ground render. Feeds the continuous shallows-to-abyss water surface.
	 */
	private int[][] waterDist() {
		int cols = world.cols, rows = world.rows;
		int[][] d = new int[cols][rows];
		java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<Integer>();
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				Tile.TileType tt = tiles[x][y].getType();
				if (tt == Tile.TileType.TYPE_WATER || tt == Tile.TileType.TYPE_SHALLOWS) {
					d[x][y] = Integer.MAX_VALUE;
				} else {
					q.add(x * rows + y);
				}
			}
		}
		while (!q.isEmpty()) {
			int i = q.poll();
			int x = i / rows, y = i % rows, nd = d[x][y] + 1;
			for (int k = 0; k < 4; k++) {
				int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
				int ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (nx >= 0 && ny >= 0 && nx < cols && ny < rows && d[nx][ny] > nd) {
					d[nx][ny] = nd;
					q.add(nx * rows + ny);
				}
			}
		}
		return d;
	}

	/**
	 * The raw shore-distance for a water-surface pixel: the field sampled
	 * bilinearly between tile centres (so contours curve instead of stepping
	 * at tile edges). An all-water map has no shore seeds; the clamp treats
	 * its unreached tiles as deep.
	 */
	private static double depthAt(int[][] dist, double wx, double wy) {
		double fx = wx - 0.5, fy = wy - 0.5;
		int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
		double tx = fx - x0, ty = fy - y0;
		double top = distClamped(dist, x0, y0) * (1 - tx) + distClamped(dist, x0 + 1, y0) * tx;
		double bot = distClamped(dist, x0, y0 + 1) * (1 - tx) + distClamped(dist, x0 + 1, y0 + 1) * tx;
		return top * (1 - ty) + bot * ty;
	}

	private static double distClamped(int[][] dist, int x, int y) {
		if (x < 0 || y < 0 || x >= dist.length || y >= dist[0].length) {
			return 0;
		}
		int v = dist[x][y];
		return v > 8 ? 8 : v;
	}

	/** Whether any of the eight neighbours (or the tile itself) is water --
	 *  open water or its walkable shallows fringe. */
	private boolean nearWater(int x, int y) {
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				if (isType(x + dx, y + dy, Tile.TileType.TYPE_WATER)
						|| isType(x + dx, y + dy, Tile.TileType.TYPE_SHALLOWS)) {
					return true;
				}
			}
		}
		return false;
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

		// Trace the sightline tile by tile, requiring every step to be an open,
		// connected transition. The previous version swept the x-crossings and the
		// y-crossings in two independent passes; for a line that is more horizontal
		// than vertical (or vice versa) the second pass jumped several columns (or
		// rows) between samples, handing isLosConnected two non-adjacent tiles
		// (|dx| > 1), which it rejects -- so ANY diagonal sightline failed even over
		// perfectly clear floor. Instead, sample the segment finely (>= 8 samples per
		// tile of length) so every tile the ray enters is visited in order and each
		// consecutive pair is genuinely adjacent: an orthogonal step, or a diagonal
		// one whose corner-cut isLosConnected still checks.
		double sdx = x2 - x1, sdy = y2 - y1;
		double slen = Math.sqrt(sdx * sdx + sdy * sdy);
		int steps = (int) Math.ceil(slen * 8);
		int pc = (int) x1, pr = (int) y1;
		for (int i = 1; i <= steps; i++) {
			double f = (double) i / steps;
			int c = (int) (x1 + sdx * f);
			int r = (int) (y1 + sdy * f);
			if (c == pc && r == pr) {
				continue; // still inside the same tile
			}
			if (!isLosConnected(pc, pr, c, r)) {
				return false;
			}
			pc = c;
			pr = r;
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