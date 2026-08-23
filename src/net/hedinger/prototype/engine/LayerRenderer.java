package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.ResourceManager.tilePadding;
import static net.hedinger.prototype.engine.ResourceManager.tileSize;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public class LayerRenderer {

	World world;

	MapLayer mapLayers[];

	public LayerRenderer(World world) {
		this.world = world;
		mapLayers = new MapLayer[world.lvls];
	}

	public void build(World w) {

		for (int z = 0; z < world.lvls; z++) {
			mapLayers[z] = new MapLayer(z);
			BufferedImage[][] floorTiles = new BufferedImage[world.cols][world.rows];
			BufferedImage[][] wallTiles = new BufferedImage[world.cols][world.rows];

			for (int x = 0; x < world.cols; x++) {
				for (int y = 0; y < world.rows; y++) {
					floorTiles[x][y] = getFloorTile(world, world.levels[z].getTile(x, y), x, y, z);
					wallTiles[x][y] = getWallTile(world, world.levels[z].getTile(x, y));
				}
			}

			mapLayers[z].image_layer = compileLayer(wallTiles, floorTiles);

			// Bake the procedural ground over the base sprites, so this level
			// shows its true art when seen from the level above (through open
			// pits, shafts and catwalk grating) -- the downsized pyramid below
			// inherits it.
			Graphics2D lg = mapLayers[z].image_layer.createGraphics();
			lg.setClip(0, 0, world.cols * tileSize, world.rows * tileSize);
			world.levels[z].bakeGround(lg);
			lg.dispose();

			mapLayers[z].image_layer_downsized = new BufferedImage[world.max_view_depth];
			for (int i = 0; i < world.max_view_depth; i++) {
				mapLayers[z].image_layer_downsized[i] = Utils.resize(
						mapLayers[z].image_layer,
						Math.round(Utils.toPixel(world.cols, -i, 1)),
						Math.round(Utils.toPixel(world.rows, -i, 1)));
			}

		}

	}

	/** Chunked-bake mode: fill only the per-tile base sprites (cheap
	 *  references) into each MapLayer, leaving image_layer null so no
	 *  whole-level buffer is allocated -- Grid.render then draws the base
	 *  per tile. Lets a level be baked in bounded (chunk-sized) memory. */
	public void buildTilesOnly(World w) {
		for (int z = 0; z < world.lvls; z++) {
			mapLayers[z] = new MapLayer(z);
			mapLayers[z].floorTiles = new BufferedImage[world.cols][world.rows];
			mapLayers[z].wallTiles = new BufferedImage[world.cols][world.rows];
			for (int x = 0; x < world.cols; x++) {
				for (int y = 0; y < world.rows; y++) {
					Tile t = world.levels[z].getTile(x, y);
					mapLayers[z].floorTiles[x][y] = getFloorTile(world, t, x, y, z);
					mapLayers[z].wallTiles[x][y] = getWallTile(world, t);
				}
			}
		}
	}

	private BufferedImage getFloorTile(World world, Tile tile, int x, int y, int z) {
		String tilecode = tile.getTileCode();
		Tile.TileType type = tile.getType();

		switch (type) {
		case TYPE_FLOOR:
		case TYPE_WALL:
		case TYPE_WATER:
		case TYPE_MUD:
		case TYPE_COVER:
		case TYPE_STONE:
		case TYPE_FUNGUS:
		case TYPE_RUBBLE:
		case TYPE_SAND:
		case TYPE_ROCKY:
		case TYPE_REEDS:
		case TYPE_SHALLOWS:
		case TYPE_QUICKSAND:
		case TYPE_CRYSTAL:
		case TYPE_CRYSTAL_BED:
		case TYPE_CRYSTAL_SPARSE:
		case TYPE_SWITCH:
		case TYPE_DOCK:
		case TYPE_SLUDGE:
		case TYPE_RAIL:
		case TYPE_SERVER:
		case TYPE_VENT:
		case TYPE_WALL_BUILT:
		case TYPE_PAVED:
		case TYPE_PLATE:
		case TYPE_PIPES:
		case TYPE_AIRVENT:
		case TYPE_WALL_CONCRETE:
		case TYPE_WALL_STEEL:
		case TYPE_DUCT:
			return ResourceManager.getFloorTile(tilecode);
		// TYPE_SHAFT and TYPE_CATWALK are intentionally omitted, like
		// TYPE_HOLE: they bake to nothing, so the level below shows (and
		// parallaxes) through the shaft void and the catwalk grating.
		// TYPE_HOLE is intentionally omitted: a hole bakes to nothing so it is a
		// see-through cut-out, letting the level below show (and parallax) through.
		// The pit shade and lip are drawn live by Grid.renderGroundPixel instead.
		case TYPE_RAMPUP:
			return ResourceManager.getRamptile(tilecode, true, rampDownhill(x, y, z, true));
		case TYPE_RAMPDOWN:
			return ResourceManager.getRamptile(tilecode, false, rampDownhill(x, y, z, false));
		default:
			return null;
		}
	}

	/**
	 * The cardinal a ramp descends toward (0 N, 1 E, 2 S, 3 W), read from the
	 * layout so the slope art can face any direction: a DOWN ramp pours into
	 * the pit beside it, so downhill is toward the adjacent {@code TYPE_HOLE};
	 * an UP ramp climbs onto the rock its landing rests on, so downhill is
	 * away from the adjacent solid mass. When no neighbour decides (nothing
	 * built by the generators today), fall back to the movement convention —
	 * ramps run east-high to west-low. Neighbour scan order is biased toward
	 * that same convention so a ramp boxed in on several sides stays true to
	 * how it actually walks.
	 */
	private int rampDownhill(int x, int y, int z, boolean up) {
		int[] dx = { 0, 1, 0, -1 }, dy = { -1, 0, 1, 0 }; // N E S W
		int[] order = up ? new int[] { 1, 3, 2, 0 } : new int[] { 3, 1, 0, 2 };
		for (int d : order) {
			int nx = x + dx[d], ny = y + dy[d];
			if (nx < 0 || ny < 0 || nx >= world.cols || ny >= world.rows) {
				continue;
			}
			Tile n = world.levels[z].getTile(nx, ny);
			if (up ? n.isSolid() : n.getType() == Tile.TileType.TYPE_HOLE) {
				return up ? (d + 2) % 4 : d; // up: high side found, low is opposite
			}
		}
		return 3; // west-low, the engine's movement convention
	}

	private BufferedImage getWallTile(World world, Tile tile) {
		String tilecode = tile.getTileCode();
		Tile.TileType type = tile.getType();
		int variant = tile.getVariant();

		switch (type) {
		case TYPE_WALL:
			return ResourceManager.getWallTile(tilecode, variant);
		// TYPE_HOLE omitted: see getFloorTile -- the pit is a see-through cut-out.
		default:
			return null;
		}
	}

	private BufferedImage compileLayer(BufferedImage[][] top, BufferedImage[][] bottom) {
		int width = tileSize * world.cols;
		int height = tileSize * world.rows;
		BufferedImage imgTop = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		BufferedImage imgBottom = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

		Graphics2D gTop = imgTop.createGraphics();
		Graphics2D gBottom = imgBottom.createGraphics();
		gTop.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		gBottom.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		for (int x = 0; x < world.cols; x++) {
			for (int y = 0; y < world.rows; y++) {
				gTop.drawImage(top[x][y],
						tileSize * x - tilePadding,
						tileSize * y - tilePadding,
						tileSize + tilePadding * 2,
						tileSize + tilePadding * 2,
						null);
				gBottom.drawImage(bottom[x][y],
						tileSize * x - tilePadding,
						tileSize * y - tilePadding,
						tileSize + tilePadding * 2,
						tileSize + tilePadding * 2,
						null);
			}
		}
		gTop.dispose();

		BufferedImage finalImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D finalG = finalImg.createGraphics();

		// finalG.drawImage(new GaussianFilter(11).filter(dimg, null), 0, 0,
		// width, height, null);
		// finalG.drawImage(new GaussianFilter(5).filter(dimg, null), 0, 0,
		// width, height, null);
		// ResourceManager.mask(dimg);
		finalG.drawImage(imgBottom, 0, 0, width, height, null);
		finalG.drawImage(imgTop, 0, 0, width, height, null);

		finalG.dispose();
		return finalImg;
	}

}
