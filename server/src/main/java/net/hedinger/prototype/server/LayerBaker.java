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

	/** Renders level {@code z} of the terrain world to one full-size image
	 *  (1 tile = tileSize px). The caller slices it into chunks; the image is
	 *  transient and freed once sliced. */
	static BufferedImage renderLevelImage(World terrain, int z) {
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
		return img;
	}

	/**
	 * Renders a whole level once into a single image, using the cheap per-tile
	 * base renderer ({@link #chunkRenderer}) rather than {@code build()}'s
	 * full-level compositor + downsized pyramid. Peak memory is one level image
	 * plus the shared (cached) tile sprites — so a large map bakes fast (one render
	 * pass per level, not one per chunk) and within a small heap. The caller slices
	 * it into chunks with {@link #chunkPng} and drops it before the next level.
	 *
	 * <p>Same passes and order as {@link #renderChunk}, so each sliced chunk is
	 * pixel-identical to the per-chunk bake.
	 */
	static BufferedImage bakeLevelImage(World terrain,
			net.hedinger.prototype.engine.LayerRenderer lr, int z) {
		int ts = ResourceManager.tileSize;
		int w = terrain.getColums() * ts, h = terrain.getRows() * ts;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setClip(0, 0, w, h);
		View view = new View(terrain, lr);
		view.think(g, 0, 0, z - view.getCamZ(), 0, 0);
		view.clearScreen(g);
		view.renderWorld(g);
		view.renderEffects(g);
		g.dispose();
		return img;
	}

	/** Encodes a sub-rectangle of {@code full} (a map chunk) to PNG bytes. */
	static byte[] chunkPng(BufferedImage full, int x0, int y0, int w, int h) {
		BufferedImage sub = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sub.createGraphics();
		g.drawImage(full, 0, 0, w, h, x0, y0, x0 + w, y0 + h, null);
		g.dispose();
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 18);
			ImageIO.write(sub, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("chunk encode failed", e);
		}
	}

	/** Builds a chunked-bake renderer: per-tile base sprites only, no whole-level
	 *  image. Built once and reused across every chunk of every level. */
	static net.hedinger.prototype.engine.LayerRenderer chunkRenderer(World terrain) {
		synchronized (LayerBaker.class) {
			if (!resourcesLoaded) {
				ResourceManager.loadResources();
				resourcesLoaded = true;
			}
		}
		terrain.alignTiles();
		net.hedinger.prototype.engine.LayerRenderer lr = new net.hedinger.prototype.engine.LayerRenderer(terrain);
		lr.buildTilesOnly(terrain);
		return lr;
	}

	/**
	 * Renders one map chunk (level {@code z}, chunk {@code (cx,cy)} of
	 * {@code chunkTiles}-square tiles) to PNG bytes in <em>bounded</em> memory:
	 * only a chunk-sized image is ever allocated, regardless of world size. Each
	 * chunk renders the whole level clipped to its region — so overlays that
	 * bleed across tile boundaries (grass feathering, water shores) stay seamless
	 * — then translates so the chunk's top-left maps to pixel (0,0).
	 */
	static byte[] renderChunk(World terrain, net.hedinger.prototype.engine.LayerRenderer lr,
			int z, int cx, int cy, int chunkTiles) {
		int ts = ResourceManager.tileSize;
		int cols = terrain.getColums(), rows = terrain.getRows();
		int x0 = cx * chunkTiles, y0 = cy * chunkTiles;
		int cw = Math.min(chunkTiles, cols - x0), ch = Math.min(chunkTiles, rows - y0);
		int wpx = cw * ts, hpx = ch * ts;
		BufferedImage img = new BufferedImage(wpx, hpx, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// Full-level clip while the view sets up, so its camera aligns tile (0,0)
		// to pixel (0,0) exactly as the whole-level bake does (drawing outside the
		// chunk image is harmlessly discarded).
		g.setClip(0, 0, cols * ts, rows * ts);
		View view = new View(terrain, lr);
		view.think(g, 0, 0, z - view.getCamZ(), 0, 0);
		g.translate(-x0 * ts, -y0 * ts); // world tile (x0,y0) -> image pixel (0,0)
		g.setClip(x0 * ts, y0 * ts, wpx, hpx);
		// Same passes and order as the whole-level bake, so the chunk is
		// pixel-identical to the corresponding slice (grain/effects included).
		view.clearScreen(g);
		view.renderWorld(g);
		view.renderEffects(g);
		g.dispose();
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 18);
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("chunk encode failed", e);
		}
	}

	private LayerBaker() {
	}
}
