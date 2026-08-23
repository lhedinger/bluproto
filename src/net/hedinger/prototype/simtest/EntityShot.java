package net.hedinger.prototype.simtest;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.LayerRenderer;
import net.hedinger.prototype.engine.RenderFx;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.sim.Worlds;

/**
 * Bakes a real scene through the REAL render pipeline and writes it to a PNG,
 * so entity art can be looked at rather than reasoned about.
 *
 * <p>ART-STYLE.md checklist 9 asks whether the actual scene has been baked and
 * looked at, on the grounds that a green suite is not evidence a render
 * changed. Until this existed there was no way to answer it for an entity.
 * {@link ParityShot} goes through {@link SnapshotRenderer}, which draws its own
 * diagnostic glyphs — heading arrows, carry links, state labels — rather than
 * calling the painters, so its output is a diagram of where things are and
 * never was a picture of what they look like. The drone spent weeks drawn only
 * by the web client, with the Java renderer falling through to a legacy sprite,
 * and nothing in the tree could have shown it.
 *
 * <p>This goes through {@code View.renderWorld}, which is the same call the
 * live application makes. That matters more than convenience: it exercises the
 * painter DISPATCH as well as the painter, and the dispatch is where the drone
 * was actually missing. A harness that called {@code DronePainter.draw}
 * directly would have drawn a perfect drone while the real renderer drew none.
 *
 * <p>Usage:
 *
 * <pre>
 *   EntityShot out.png                       the demo world at tick 400
 *   EntityShot out.png --focus StewardDrone  centred on that body, zoomed in
 *   EntityShot out.png --focus StewardDrone --sweep
 *                                            one tile per heading it is seen
 *                                            in, composed into a strip
 * </pre>
 *
 * <p>{@code --sweep} exists because a body with eight facings is eight pieces
 * of art, and a single frame shows one of them. It watches the focused entity
 * as the world runs and keeps the first frame of each heading bucket it sees,
 * so the strip is assembled out of real frames of a running world rather than
 * out of poses set by hand. What it cannot do is guarantee all eight: a drone
 * that never flies south-west is never drawn south-west, and the strip says so
 * by coming back short rather than by inventing the missing cell.
 */
final class EntityShot {

	/** Tile size the pipeline draws at, from {@code Utils.scaleZ(0, 0)}. */
	private static final int TILE = 64;

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		File out = new File(args.length > 0 ? args[0] : "entity.png");
		String focus = null;
		int ticks = 400, span = 2400, cell = 7;
		boolean sweep = false;
		for (int i = 1; i < args.length; i++) {
			switch (args[i]) {
			case "--focus":
				focus = args[++i];
				break;
			case "--ticks":
				ticks = Integer.parseInt(args[++i]);
				break;
			case "--span":
				span = Integer.parseInt(args[++i]);
				break;
			case "--cell":
				cell = Integer.parseInt(args[++i]); // crop size, in tiles
				break;
			case "--sweep":
				sweep = true;
				break;
			default:
				System.err.println("unknown option " + args[i]);
				System.exit(2);
			}
		}

		RenderFx.foliage = false; // deterministic: no wind phase
		World w = Worlds.demo(42);
		LayerRenderer lr = new LayerRenderer(w);
		lr.build(w);
		View v = new View(w, lr);

		if (sweep && focus != null) {
			ImageIO.write(sweep(w, v, focus, ticks, span, cell), "png", out);
		} else {
			for (int i = 0; i < ticks; i++) {
				w.think();
			}
			ImageIO.write(frame(w, v, focus, cell), "png", out);
		}
		System.out.println("wrote " + out + " (" + out.length() + " bytes)");
	}

	/**
	 * One frame. When a focus is named the camera is put on that body and the
	 * canvas cropped tight around it; otherwise the whole demo world is drawn.
	 */
	private static BufferedImage frame(World w, View v, String focus, int cell) {
		Entity e = focus == null ? null : find(w, focus);
		if (focus != null && e == null) {
			throw new IllegalStateException("no entity named " + focus + " in the world");
		}
		int px = e == null ? w.getColums() * TILE : cell * TILE;
		int py = e == null ? w.getRows() * TILE : cell * TILE;
		BufferedImage img = new BufferedImage(px, py, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		// View reads its window size off the Graphics CLIP, and a freshly
		// created Graphics2D has none until one is set — so set it, or the
		// first thing the pipeline does is dereference null.
		g.setClip(0, 0, px, py);
		v.think(g, 0, 0, 0, 0, 0);
		if (e != null) {
			// think() takes camera DELTAS, so aim by the remaining offset.
			v.think(g, (float) (e.getX() - v.getCamX()), (float) (e.getY() - v.getCamY()),
					(float) (e.getZ() - v.getCamZ()), 0, 0);
		}
		v.renderWorld(g);
		g.dispose();
		return img;
	}

	/** One frame per heading the focused body is seen in, tiled left to right. */
	private static BufferedImage sweep(World w, View v, String focus, int ticks, int span,
			int cell) {
		for (int i = 0; i < ticks; i++) {
			w.think();
		}
		List<BufferedImage> shots = new ArrayList<BufferedImage>();
		boolean[] seen = new boolean[8];
		for (int i = 0; i < span && shots.size() < 8; i++) {
			w.think();
			Entity e = find(w, focus);
			if (e == null) {
				continue;
			}
			int bucket = Math.floorMod(
					(int) Math.round(e.getDirection() / (Math.PI * 2 / 8)), 8);
			if (seen[bucket]) {
				continue;
			}
			seen[bucket] = true;
			shots.add(frame(w, v, focus, cell));
		}
		if (shots.isEmpty()) {
			throw new IllegalStateException("never saw " + focus + " over " + span + " ticks");
		}
		int cw = shots.get(0).getWidth(), chh = shots.get(0).getHeight();
		BufferedImage strip = new BufferedImage(cw * shots.size(), chh,
				BufferedImage.TYPE_INT_RGB);
		Graphics2D g = strip.createGraphics();
		for (int i = 0; i < shots.size(); i++) {
			g.drawImage(shots.get(i), i * cw, 0, null);
		}
		g.dispose();
		System.out.println("swept " + shots.size() + " of 8 headings");
		return strip;
	}

	private static Entity find(World w, String typeName) {
		for (Entity e : w.getEntities()) {
			if (e instanceof NPC && !e.isRemoved()
					&& typeName.equals(((NPC) e).getNpcTypeName())) {
				return e;
			}
		}
		return null;
	}

	private EntityShot() {
	}
}
