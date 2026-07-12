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

		if (camDepth == 0) {
			int ox = v.pixelX(0, level, 0);
			int oy = v.pixelY(0, level, 0);
			g2.drawImage(lr.mapLayers[level].image_layer, ox, oy, null);
			renderGround(g2, ox, oy);
		} else {
			g2.drawImage(
					lr.mapLayers[level].image_layer_downsized[camDepth - 1],
					v.pixelX(0, level, 0),
					v.pixelY(0, level, 0),
					null);
		}

		for (Entity d : doors) {
			if (d != null) {
				d.render(g, v);
			}
		}
		for (Entity e : world.entities.values()) {
			if (e != null && e.getLvl() == level) {
				e.render(g, v);
			}
		}

	}

	/**
	 * The ground layer (under doors and entities): each tile draws a monochrome
	 * terrain texture (grass blades, water ripples, mud speckle, tall-grass
	 * cover) rather than a flat colour -- grass density follows vegetation, so
	 * grazed patches thin to bare floor. Pheromone rides on top as a wash.
	 */
	private void renderGround(Graphics2D g2, int ox, int oy) {
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
					// grassy ground as green. The overlay density follows
					// vegetation; lush (mottle) tiles fade toward thinner grass.
					g2.setColor(GroundTextures.SOIL);
					g2.fillRect(sx, sy, ts, ts);
					int level = GroundTextures.grassLevel(t.getVegetation(now) / Tile.VEG_MAX);
					if (level >= 0) {
						g2.setColor(GroundTextures.GRASS_GREEN);
						g2.fillRect(sx, sy, ts, ts);
						if (GroundTextures.isMottle(level)) {
							// Sampled from one world-space field so it joins its
							// mottle neighbours; faded toward thinner grass.
							GroundTextures.drawMottle(g2, sx, sy, ts, level, x, y, mottleEdgeMask(x, y, now));
						} else {
							g2.drawImage(GroundTextures.stipplePattern(level, hash), sx, sy, ts, ts, null);
						}
					}
					// Wet margin where land meets water: softens the tile-step
					// shoreline from the land side, so it doesn't stair-step.
					drawWetMargin(g2, x, y, sx, sy, ts);
				} else if (t.getType() == Tile.TileType.TYPE_WATER) {
					// Opaque blue base + world-space ripples that flow across
					// water tiles, with a lighter shore rim where it meets land.
					g2.setColor(GroundTextures.WATER_BLUE);
					g2.fillRect(sx, sy, ts, ts);
					GroundTextures.drawWater(g2, sx, sy, ts, x, y);
					drawShoreRim(g2, x, y, sx, sy, ts);
				} else {
					java.awt.image.BufferedImage tex = GroundTextures.terrain(t, hash);
					if (tex != null) {
						g2.drawImage(tex, sx, sy, ts, ts, null);
					}
				}
				// Pheromone on top: bright blobs are nests, faint smears trails.
				double ph = t.getPheromone(now);
				if (ph > 0.05) {
					int a = (int) Math.min(220, ph * 90);
					g2.setColor(new Color(230, 40, 190, a));
					g2.fillRect(sx, sy, ts, ts);
				}
			}
		}
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
	 * Shore treatment on any water edge that meets non-water. Rather than a
	 * straight gradient band (too even), it scatters soft radial "shallow" blobs
	 * of varying reach along the waterline, clipped to the water tile -- so the
	 * inner edge of the shallows wanders organically. Foam flecks sit on top.
	 */
	private void drawShoreRim(Graphics2D g2, int x, int y, int sx, int sy, int ts) {
		boolean n = !neighbourWater(x, y - 1);
		boolean e = !neighbourWater(x + 1, y);
		boolean s = !neighbourWater(x, y + 1);
		boolean w = !neighbourWater(x - 1, y);
		if (!(n || e || s || w)) {
			return;
		}
		Color c = GroundTextures.SHORE;
		Color core = new Color(c.getRed(), c.getGreen(), c.getBlue(), 150);
		Color clear = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);

		Shape oldClip = g2.getClip();
		g2.clipRect(sx, sy, ts, ts); // keep shallows inside the water tile
		if (n) {
			shallowBlobs(g2, x, y, 0, sx, sy, 1, 0, 0, 1, ts, core, clear);
		}
		if (s) {
			shallowBlobs(g2, x, y, 1, sx, sy + ts, 1, 0, 0, -1, ts, core, clear);
		}
		if (w) {
			shallowBlobs(g2, x, y, 2, sx, sy, 0, 1, 1, 0, ts, core, clear);
		}
		if (e) {
			shallowBlobs(g2, x, y, 3, sx + ts, sy, 0, 1, -1, 0, ts, core, clear);
		}
		g2.setClip(oldClip);

		// Foam flecks along each waterline, jittered inward off the boundary.
		g2.setColor(new Color(215, 238, 250, 210));
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
	 * The land-side companion to {@link #drawShoreRim}: a subtle wet fringe on a
	 * land tile's edges that face water, reaching inward onto the land. Together
	 * the two make the shoreline a soft irregular band straddling the tile line
	 * instead of a hard tile step.
	 */
	private void drawWetMargin(Graphics2D g2, int x, int y, int sx, int sy, int ts) {
		boolean n = neighbourWater(x, y - 1);
		boolean e = neighbourWater(x + 1, y);
		boolean s = neighbourWater(x, y + 1);
		boolean w = neighbourWater(x - 1, y);
		if (!(n || e || s || w)) {
			return;
		}
		Color c = GroundTextures.SHORE;
		Color core = new Color(c.getRed(), c.getGreen(), c.getBlue(), 85);
		Color clear = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
		Shape oldClip = g2.getClip();
		g2.clipRect(sx, sy, ts, ts);
		if (n) {
			shallowBlobs(g2, x, y, 4, sx, sy, 1, 0, 0, 1, ts, core, clear);
		}
		if (s) {
			shallowBlobs(g2, x, y, 5, sx, sy + ts, 1, 0, 0, -1, ts, core, clear);
		}
		if (w) {
			shallowBlobs(g2, x, y, 6, sx, sy, 0, 1, 1, 0, ts, core, clear);
		}
		if (e) {
			shallowBlobs(g2, x, y, 7, sx + ts, sy, 0, 1, -1, 0, ts, core, clear);
		}
		g2.setClip(oldClip);
	}

	/**
	 * Soft radial shallows blobs along one waterline edge. Origin (bx,by) with an
	 * along-axis (dax,day) and an inward-axis (ix,iy); each blob's reach varies
	 * from the tile hash so the shallows edge is irregular.
	 */
	private void shallowBlobs(Graphics2D g2, int x, int y, int edge, int bx, int by,
			int dax, int day, int ix, int iy, int ts, Color core, Color clear) {
		for (int i = 0; i < 6; i++) {
			int h = ((x * 928371) ^ (y * 1299709) ^ (edge * 40503) ^ (i * 2654435)) & 0x7fffffff;
			int along = (h % 100) * ts / 100;
			int r = ts / 5 + (h / 100) % (ts / 2);
			int cx = bx + dax * along + ix * (r / 4);
			int cy = by + day * along + iy * (r / 4);
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