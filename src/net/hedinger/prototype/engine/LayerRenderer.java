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
					floorTiles[x][y] = getFloorTile(world, world.levels[z].getTile(x, y));
					wallTiles[x][y] = getWallTile(world, world.levels[z].getTile(x, y));
				}
			}

			mapLayers[z].image_layer = compileLayer(wallTiles, floorTiles);
			double width = world.cols / View.minimap_scale;
			double height = world.rows / View.minimap_scale;

			mapLayers[z].image_layer_downsized = new BufferedImage[world.max_view_depth];
			for (int i = 0; i < world.max_view_depth; i++) {
				mapLayers[z].image_layer_downsized[i] = Utils.resize(
						mapLayers[z].image_layer,
						Math.round(Utils.toPixel(world.cols, -i, 1)),
						Math.round(Utils.toPixel(world.rows, -i, 1)));
			}

			mapLayers[z].image_layer_thumb = Utils.resize(mapLayers[z].image_layer, (int) Math.round(width),
					(int) Math.round(height));
		}

	}

	private BufferedImage getFloorTile(World world, Tile tile) {
		String tilecode = tile.getTileCode();
		Tile.TileType type = tile.getType();

		switch (type) {
		case TYPE_FLOOR:
		case TYPE_WALL:
			return ResourceManager.getFloorTile(tilecode);
		case TYPE_HOLE:
			return ResourceManager.getHoleFloorTile(tilecode);
		case TYPE_RAMPUP:
			return ResourceManager.getRamptile(tilecode, true);
		case TYPE_RAMPDOWN:
			return ResourceManager.getRamptile(tilecode, false);
		default:
			return null;
		}
	}

	private BufferedImage getWallTile(World world, Tile tile) {
		String tilecode = tile.getTileCode();
		Tile.TileType type = tile.getType();

		switch (type) {
		case TYPE_WALL:
			return ResourceManager.getWallTile(tilecode);
		case TYPE_HOLE:
			return ResourceManager.getHoleTile(tilecode);
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
