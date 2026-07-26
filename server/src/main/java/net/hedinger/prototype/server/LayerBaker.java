package net.hedinger.prototype.server;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import net.hedinger.prototype.engine.LayerRenderer;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;

/**
 * Bakes a world's <em>static</em> visuals — tiles, pixel ground, tall grass,
 * shrubs — into one PNG per level, using the existing Java renderer against an
 * entity-free terrain twin of the live world (see {@code Worlds.demoTerrain}).
 * This is the MODERNIZATION.md render split in action: the style-defining art
 * is never ported, it is served; the client only composites entities on top.
 *
 * <p>Baked once per world (grass density in the bake is the untouched ideal;
 * live grazing thinning is a Phase 4 refinement).
 */
final class LayerBaker {

	private static boolean resourcesLoaded = false;

	/** Renders level {@code z} of the terrain world to PNG bytes (1 tile = tileSize px). */
	static byte[] bake(World terrain, int z) {
		synchronized (LayerBaker.class) {
			if (!resourcesLoaded) {
				ResourceManager.loadResources();
				resourcesLoaded = true;
			}
		}
		terrain.alignTiles();
		LayerRenderer lr = new LayerRenderer(terrain);
		lr.build(terrain);
		View view = new View(terrain, lr);

		int ts = ResourceManager.tileSize;
		int w = terrain.getColums() * ts;
		int h = terrain.getRows() * ts;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setClip(0, 0, w, h);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// Camera centred on the level: with the image sized to the world,
		// pixel = tile * tileSize, which is the mapping the client assumes.
		// Compose only the world passes — ground, foliage, the film-grain
		// effect — and skip View.render()'s HUD (minimap, entity counter),
		// which would be baked into the ground for every viewer forever.
		view.think(g, 0, 0, z - view.getCamZ(), 0, 0);
		view.clearScreen(g);
		view.renderWorld(g);
		view.renderEffects(g);
		g.dispose();

		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("layer bake failed", e);
		}
	}

	private LayerBaker() {
	}
}
