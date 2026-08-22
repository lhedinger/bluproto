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
 * Bakes a world's <em>static</em> visuals — tiles, pixel ground, shrubs — into
 * one PNG per level, using the existing Java renderer against an
 * entity-free terrain twin of the live world (see {@code Worlds.demoTerrain}).
 * This is the MODERNIZATION.md render split in action: the style-defining art
 * is never ported, it is served; the client only composites entities on top.
 *
 * <p>Baked once per world. The bake is a pure function of the terrain: ground
 * pixels read tile type plus the static fertility potential (grassland's
 * sward), and the live tall-grass tufts are disabled here (see
 * {@link #loadResourcesOnce}), so nothing state-dependent is frozen into
 * the chunks — growth and depletion are the client's vegetation sprite layer.
 */
final class LayerBaker {

	/** Pixels per tile in the ENCODED chunks: the art-pixel grid (Grid draws
	 *  12 art-px to a tile). Announced to the client in hello as chunkPx so
	 *  its chunk-space maths match the served resolution. */
	static final int CHUNK_PX = 12;

	private static boolean resourcesLoaded = false;

	/** One-time render setup for every server-side bake: load the shared sprite
	 *  resources, and disable the live tall-grass tufts — they follow the
	 *  vegetation at render time, and a bake frozen at one arbitrary regrowth
	 *  moment would contradict the sprite vegetation layer drawn on top. The
	 *  server renders nothing but bakes, so the flag is simply left off. */
	private static void loadResourcesOnce() {
		synchronized (LayerBaker.class) {
			if (!resourcesLoaded) {
				ResourceManager.loadResources();
				net.hedinger.prototype.engine.RenderFx.grassTufts = false;
				resourcesLoaded = true;
			}
		}
	}

	/** Renders level {@code z} of the terrain world to one full-size image
	 *  (1 tile = tileSize px). The caller slices it into chunks; the image is
	 *  transient and freed once sliced. */
	static BufferedImage renderLevelImage(World terrain, int z) {
		loadResourcesOnce();
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

	/**
	 * Encodes a sub-rectangle of {@code full} (a map chunk) to PNG bytes.
	 *
	 * <p>Why PNG stays the wire format (a decision, not an accident): the
	 * payload is lossless flat-colour pixel art, which PNG's palette+filter
	 * pipeline compresses to ~19 KB a chunk at art resolution; the browser
	 * decodes it natively off the main thread and the client copies it into a
	 * canvas ONCE, so no PNG is ever touched again at render time — GPU
	 * textures upload from those canvases. Raw RGBA would be ~4x the bytes,
	 * and WebP-lossless's ~25% would cost a Java-side encoder dependency for
	 * a payload that is already far below the page's other traffic.
	 */
	static byte[] chunkPng(BufferedImage full, int x0, int y0, int w, int h) {
		// Encode at ART resolution, not render resolution: the bake draws
		// art-pixels as (tileSize/CHUNK_PX)-square blocks, so the full-res
		// slice carries 16x more pixels than the art holds — the whole level's
		// chunks were ~18 MB of PNG, alone putting the first paint tens of
		// seconds out on an ordinary connection. Nearest sampling keeps exactly
		// one sample per art-pixel (lossless for everything on the art grid;
		// the decorative shrubs get snapped onto it). The client
		// upscales nearest-neighbour, so the screen shows the same art.
		int ts = ResourceManager.tileSize;
		int aw = w / ts * CHUNK_PX, ah = h / ts * CHUNK_PX;
		BufferedImage sub = new BufferedImage(aw, ah, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sub.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(full, 0, 0, aw, ah, x0, y0, x0 + w, y0 + h, null);
		g.dispose();
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
			ImageIO.write(sub, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("chunk encode failed", e);
		}
	}

	/** Builds a chunked-bake renderer: per-tile base sprites only, no whole-level
	 *  image. Built once and reused across every chunk of every level. */
	static net.hedinger.prototype.engine.LayerRenderer chunkRenderer(World terrain) {
		loadResourcesOnce();
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
		// Encode at ART resolution, not render resolution. The bake draws
		// art-pixels as (tileSize/12)-square blocks, so the full-res image
		// carries 16x more pixels than the art actually holds — and the whole
		// level's chunks were ~18 MB of PNG, which alone put the first paint
		// tens of seconds out on an ordinary connection. A nearest downscale
		// keeps exactly one sample per art-pixel (lossless for everything on
		// the art grid; the decorative shrubs get snapped onto it, which
		// the design system counts as a feature). The client upscales
		// nearest-neighbour, so what reaches the screen is the same art.
		int aw = cw * CHUNK_PX, ah = ch * CHUNK_PX;
		BufferedImage art = new BufferedImage(aw, ah, BufferedImage.TYPE_INT_RGB);
		Graphics2D ag = art.createGraphics();
		ag.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		ag.drawImage(img, 0, 0, aw, ah, null);
		ag.dispose();
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
			ImageIO.write(art, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("chunk encode failed", e);
		}
	}

	private LayerBaker() {
	}
}
