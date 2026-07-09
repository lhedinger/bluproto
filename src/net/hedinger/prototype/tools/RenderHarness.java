package net.hedinger.prototype.tools;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.LayerRenderer;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.engine.WorldGenerator;
import net.hedinger.prototype.entities.npcs.Drone;
import net.hedinger.prototype.entities.npcs.Elite;
import net.hedinger.prototype.entities.npcs.Human;
import net.hedinger.prototype.entities.npcs.Sentry;
import net.hedinger.prototype.entities.npcs.Soldier;
import net.hedinger.prototype.entities.npcs.Zombie;

/**
 * Headless render harness for diagnostics: builds a seeded world, populates it,
 * advances the simulation, and renders frames into {@link BufferedImage}s
 * through the real {@link View} pipeline -- no JFrame or display required.
 *
 * <p>Resources are loaded from the {@code res/} directory via relative paths,
 * so the JVM must run with the repository root as its working directory.
 *
 * <p>The same seed reproduces the same world and initial population, which is
 * what makes this useful for diagnosing a specific run: capture the seed, then
 * regenerate the exact scenario on demand.
 */
public class RenderHarness {

	public final World world;
	public final View view;
	private final LayerRenderer layerRenderer;

	private final int cols;
	private final int rows;
	private final int lvls;

	public RenderHarness(long seed, int cols, int rows, int lvls, int population) {
		this.cols = cols;
		this.rows = rows;
		this.lvls = lvls;

		Utils.seed(seed);
		// NPC.run_extended() profiles itself against this static stopwatch; the
		// GUI normally creates it in PrototypeWorld.initialize().
		net.hedinger.prototype.main.PrototypeWorld.stopwatch = new StopWatch();
		ResourceManager.loadResources();

		WorldGenerator generator = new WorldGenerator(cols, rows, lvls);
		generator.run();
		world = generator.getWorld();
		world.alignTiles();

		layerRenderer = new LayerRenderer(world);
		layerRenderer.build(world);
		view = new View(world, layerRenderer);

		populate(population);
	}

	/** Spawns a mixed population at random open tiles using the seeded RNG. */
	private void populate(int population) {
		int spawned = 0;
		int attempts = 0;
		int cap = Math.max(1000, population * 60);
		while (spawned < population && attempts < cap) {
			attempts++;
			float x = Utils.random(cols);
			float y = Utils.random(rows);
			int z = Utils.random(lvls);
			if (!world.isOpen(x, y, z)) {
				continue;
			}
			int t = Utils.random(10);
			Entity e;
			if (t < 2) {
				e = new Soldier(x, y, z);
			} else if (t < 3) {
				e = new Elite(x, y, z);
			} else if (t < 4) {
				e = new Sentry(x, y, z);
			} else if (t < 5) {
				e = new Drone(x, y, z);
			} else if (t < 8) {
				e = new Human(x, y, z);
			} else {
				e = new Zombie(x, y, z);
			}
			if (world.spawnEntity(e)) {
				spawned++;
			}
		}
	}

	/** Advances the simulation by {@code n} ticks. */
	public void tick(int n) {
		for (int i = 0; i < n; i++) {
			world.think();
		}
	}

	/**
	 * Renders the world to a fresh image, centered on (camCol, camRow) at the
	 * given level. The render scale is fixed at {@code tileSize} px/tile, so a
	 * larger window shows more of the level.
	 */
	public BufferedImage frame(int width, int height, float camCol, float camRow, int camLevel) {
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		// A BufferedImage's Graphics has no clip; View reads getClipBounds().
		g.setClip(0, 0, width, height);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// View only exposes camera movement via think() deltas; steer it from
		// its current position to the requested one.
		float dx = camCol - view.getCamX();
		float dy = camRow - view.getCamY();
		float dz = camLevel - view.getCamZ();
		view.think(g, dx, dy, dz, 0, 0);
		view.render(g);

		g.dispose();
		return img;
	}

	/** Renders centered on the level, sized to show the whole map. */
	public BufferedImage wholeLevel(int camLevel) {
		int w = cols * ResourceManager.tileSize;
		int h = rows * ResourceManager.tileSize;
		return frame(w, h, cols * 0.5f, rows * 0.5f, camLevel);
	}

	public int getCols() {
		return cols;
	}

	public int getRows() {
		return rows;
	}

	public int getLevels() {
		return lvls;
	}
}
