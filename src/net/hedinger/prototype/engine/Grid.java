package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.ResourceManager.tileSize;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;

public class Grid {

	private World world;
	private Tile[][] tiles;
	private int level;
	private BufferedImage image_layer, image_layer_thumb;
	private BufferedImage[] image_layer_downsized;

	HashSet<Entity> doors;
	HashMap<Integer, Sector> sectors;

	int counter = 0;

	Grid(World w, int c, int r, int l) {
		world = w;
		if (c < 1 || r < 1) {
			return;
		}

		tiles = new Tile[c][r];

		level = l;

		doors = new HashSet<Entity>();

		sectors = new HashMap<Integer, Sector>();
	}

	private BufferedImage compileLayer(BufferedImage[][] img) {
		int width = tileSize * world.cols;
		int height = tileSize * world.rows;
		BufferedImage dimg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = dimg.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				g.drawImage(img[x][y], tileSize * x, tileSize * y, tileSize, tileSize, null);
			}
		}
		g.dispose();
		return dimg;
	}

	BufferedImage getMiniMap() {
		return image_layer_thumb;
	}

	boolean aligned = false;

	public void think(World w) {

		for (Entity d : doors) {
			if (d != null) {
				d.run();
			}
		}

	}

	public void render(Graphics g, View v, World w) {

		Graphics2D g2 = (Graphics2D) g;

		if (!aligned) {
			align(g, w);
			aligned = true;
		}

		int camDepth = (v.getCamZ()) - level;

		if (camDepth < 0) {
			return;
		}

		if (camDepth > world.max_view_depth) {
			return;
		}

		if (camDepth == 0) {
			g2.drawImage(image_layer, v.pixelX(0, level, 0), v.pixelY(0, level, 0), null);
		} else {
			g2.drawImage(image_layer_downsized[camDepth - 1], v.pixelX(0, level, 0), v.pixelY(0, level, 0), null);
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

		for (Entity e : world.entities.values()) {
			if (e instanceof NPC) {
				NPC n = (NPC) e;
				if (n != null && !n.isDead()) {
					if (n.isHostile() && n.isDetected()) {
						g2.setColor(Color.RED);
						g2.fillOval((int) (19 + Math.round(e.getX() / world.minimap_scale)),
								(int) (19 + Math.round(e.getY() / world.minimap_scale)), 2, 2);
						g2.setStroke(new BasicStroke(1));

						g2.drawOval((int) (19 - world.minimap_ping / 2 + Math.round(e.getX() / world.minimap_scale)),
								(int) (19 - world.minimap_ping / 2 + Math.round(e.getY() / world.minimap_scale)),
								world.minimap_ping,
								world.minimap_ping);
					} else {
						if (n.isFriendly()) {
							g2.setColor(Color.GREEN);
							g2.fillOval((int) (19 + Math.round(e.getX() / world.minimap_scale)),
									(int) (19 + Math.round(e.getY() / world.minimap_scale)), 2, 2);
						} else {
							g2.setColor(Color.WHITE);
							g2.fillOval((int) (19 + Math.round(e.getX() / world.minimap_scale)),
									(int) (19 + Math.round(e.getY() / world.minimap_scale)), 1, 1);
						}
					}
				}
			}
		}
	}

	private void align(Graphics g, World w) {
		BufferedImage[][] img_tiles = new BufferedImage[world.cols][world.rows];
		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				if (tiles[x][y] != null) {
					img_tiles[x][y] = tiles[x][y].buildMap(g, w);
				}
			}
		}

		image_layer = compileLayer(img_tiles);
		double width = world.cols / world.minimap_scale;
		double height = world.rows / world.minimap_scale;

		image_layer_downsized = new BufferedImage[world.max_view_depth];
		for (int i = 0; i < world.max_view_depth; i++) {
			image_layer_downsized[i] = Utils.resize(image_layer,
					Math.round(Utils.toPixel(world.cols, -i, 1)),
					Math.round(Utils.toPixel(world.rows, -i, 1)));
		}

		image_layer_thumb = Utils.resize(image_layer, (int) Math.round(width), (int) Math.round(height));
	}

	TreeMap<Double, Entity> searchEntity(double x, double y, double dir, double radius, double fov,
			String[] types, boolean include, int ID) {
		TreeMap<Double, Entity> result = new TreeMap<Double, Entity>();
		for (Entity e : world.entities.values()) {
			// TODO consider entities from different levels
			if (e != null && e.getLvl() == level) {
				if (!e.isDead()) {
					if ((World.includesType(e.EntityType(), types) && include)
							|| (World.excludesType(e.EntityType(), types) && !include)) {
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

	TreeMap<Double, NPC> searchNPC(double x, double y, double dir, double radius, double fov,
			String[] types, boolean include, int ID) {
		TreeMap<Double, NPC> result = new TreeMap<Double, NPC>();
		for (Entity e : world.entities.values()) {
			// TODO consider entities from different levels
			if (e != null && e.getLvl() == level) {
				if (e instanceof NPC) {
					NPC npc = (NPC) e;
					if (!e.isDead() && ID != e.getID()) {
						if (World.filterType(e.EntityType(), types, include)) {
							double dist = world.distance(x, y, level, e.getX(), e.getY(), e.getZ());
							if (hasLOS(x, y, dir, e.getX(), e.getY(), radius, fov)) {
								result.put(dist, npc);
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
		// check to see if point is in range
		if (dist >= 0 && world.distance(x1, y1, 0, x2, y2, 0) > dist) {
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

			if (!isConnected((int) px, (int) py, (int) tx, (int) ty)) {
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

			if (!isConnected((int) px, (int) py, (int) tx, (int) ty)) {
				return false;
			}
			px = tx;
			py = ty;
		}

		return true;
	}

	boolean isWalkable(int c, int r) {
		if (!isValid(c, r)) {
			return false;
		}
		return tiles[c][r].isWalkable();
	}

	private boolean isConnected(int c, int r, int c2, int r2) {
		if (!isValid(c, r)) {
			return false;
		}
		if (!isValid(c2, r2)) {
			return false;
		}
		return tiles[c][r].isConnected(world, c2, r2, level);
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