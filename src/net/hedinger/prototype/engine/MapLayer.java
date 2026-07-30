package net.hedinger.prototype.engine;

import java.awt.image.BufferedImage;

public class MapLayer {

	BufferedImage image_layer;
	BufferedImage[] image_layer_downsized;
	BufferedImage[][] floorTiles; // per-tile base (chunked bake: no whole-level image_layer)
	BufferedImage[][] wallTiles;
	int level;

	public MapLayer(int lvl) {
		this.level = lvl;
	}

}
