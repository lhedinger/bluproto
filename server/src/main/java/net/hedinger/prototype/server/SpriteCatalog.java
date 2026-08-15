package net.hedinger.prototype.server;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import net.hedinger.prototype.engine.LayerRenderer;
import net.hedinger.prototype.engine.PheromoneCloud;
import net.hedinger.prototype.engine.ProcCreature;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Door;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.entities.Switch;
import net.hedinger.prototype.simtest.TestNPC;

/**
 * The art-system catalog behind {@code /sprites}: a static reference of every
 * visual the renderer can produce — ground textures, furniture and items with
 * their state changes, and distinct samples of the procedural creature space —
 * rendered through the REAL pipeline (staged mini-worlds through the layer
 * bake and the entity painters; creatures through {@link ProcCreature}), so
 * the catalog can never drift from what the world actually draws.
 *
 * <p>Everything is pre-rendered ONCE at server startup, before the live
 * simulation thread exists: catalog worlds tick and draw from the shared
 * engine RNG, and rendering them mid-run would perturb the live world's
 * deterministic stream (seed + command log ⇒ same world). Rendered bytes are
 * served from memory ever after.
 */
final class SpriteCatalog {

	/** Fixed seed for the catalog's own staging, so its samples never vary. */
	private static final long CATALOG_SEED = 424242;

	private final Map<String, byte[]> assets = new LinkedHashMap<>();
	private final StringBuilder body = new StringBuilder();

	static SpriteCatalog render() {
		long t0 = System.currentTimeMillis();
		SpriteCatalog c = new SpriteCatalog();
		Utils.seed(CATALOG_SEED);
		c.groundSection();
		c.concealmentSection();
		c.furnitureSection();
		c.creatureSection();
		System.out.println("sprite catalog: " + c.assets.size() + " assets in "
				+ (System.currentTimeMillis() - t0) + " ms");
		return c;
	}

	/** Asset bytes by file name ("ground_grass.png"), or null. */
	byte[] asset(String file) {
		return assets.get(file);
	}

	static String contentType(String file) {
		return file.endsWith(".gif") ? "image/gif" : "image/png";
	}

	// ---- sections ----------------------------------------------------------

	private void groundSection() {
		section("Ground", "One swatch per tile class, straight from the layer bake. "
				+ "Grass and fungus regrow what grazing eats; the depletion strip below shows the dither.");
		ground("grass", Tile.TileType.TYPE_FLOOR);
		ground("tall_grass_cover", Tile.TileType.TYPE_COVER);
		ground("water", Tile.TileType.TYPE_WATER);
		ground("shallows", Tile.TileType.TYPE_SHALLOWS);
		ground("reeds", Tile.TileType.TYPE_REEDS);
		ground("mud", Tile.TileType.TYPE_MUD);
		ground("sand", Tile.TileType.TYPE_SAND);
		ground("quicksand", Tile.TileType.TYPE_QUICKSAND);
		ground("stone_floor", Tile.TileType.TYPE_STONE);
		ground("rubble", Tile.TileType.TYPE_RUBBLE);
		ground("fungus_bed", Tile.TileType.TYPE_FUNGUS);
		ground("vent", Tile.TileType.TYPE_VENT);
		ground("crystal_cluster", Tile.TileType.TYPE_CRYSTAL);
		ground("crystal_bed", Tile.TileType.TYPE_CRYSTAL_BED);
		ground("crystal_sparse", Tile.TileType.TYPE_CRYSTAL_SPARSE);
		ground("rock_wall", Tile.TileType.TYPE_WALL);
		ground("masonry_wall", Tile.TileType.TYPE_WALL_BUILT);
		ground("concrete_wall", Tile.TileType.TYPE_WALL_CONCRETE);
		ground("steel_wall", Tile.TileType.TYPE_WALL_STEEL);
		ground("pit", Tile.TileType.TYPE_HOLE);
		ground("shaft", Tile.TileType.TYPE_SHAFT);
		ground("paved", Tile.TileType.TYPE_PAVED);
		ground("deck_plate", Tile.TileType.TYPE_PLATE);
		ground("catwalk", Tile.TileType.TYPE_CATWALK);
		ground("pipes", Tile.TileType.TYPE_PIPES);
		ground("air_vent", Tile.TileType.TYPE_AIRVENT);
		ground("duct", Tile.TileType.TYPE_DUCT);
		ground("switch_seat", Tile.TileType.TYPE_SWITCH);
		grassDepletion();
	}

	private void furnitureSection() {
		section("Furniture & items", "Doors cycle closed → open → closed (wired, as their "
				+ "switches drive them). The plate answers weight — a parked crate holds it; the "
				+ "button answers only deliberate interaction. Lamp trails show what drives what.");
		for (int flavor = 0; flavor <= Door.BLAST; flavor++) {
			doorCycle(flavor);
		}
		switchScene("switch_plate", Switch.PLATE);
		switchScene("switch_button", Switch.BUTTON);
		items();
		nest();
		pheromone();
	}

	private void creatureSection() {
		section("Creatures", "Distinct samples of the procedural organism space — each spins "
				+ "through its eight facings while the idle gait plays. Genomes are hand-picked "
				+ "points, not live phenotypes: the space itself is the reference.");
		creature("founder_grazer", sample(6, 0.05, false, 0, new double[] { 0.20, 0.50, 0.80 }));
		creature("tiny_darter", sample(3, 0.09, false, 0, new double[] { 0.90, 0.10, 0.40 }));
		creature("bulky_armored", sample(14, 0.02, false, 0, new double[] { 0.50, 0.90, 0.20 }));
		creature("apex_predator", sample(11, 0.06, false, 1, new double[] { 0.05, 0.05, 0.90 }));
		creature("flier", sample(5, 0.07, true, 0, new double[] { 0.60, 0.30, 0.10 }));
		creature("herd_mother", sample(8, 0.04, false, 0, new double[] { 0.35, 0.70, 0.55 }));

		section("Creature actions", "The action envelopes every phenotype shares — squash, "
				+ "stretch, offset, tint, dissolve — swept 0 → 1 on the founder-grazer body.");
		Genome g = sample(6, 0.05, false, 0, new double[] { 0.20, 0.50, 0.80 });
		action("lunge", g, ProcCreature.A_LUNGE);
		action("hurt", g, ProcCreature.A_HURT);
		action("eat", g, ProcCreature.A_EAT);
		action("court", g, ProcCreature.A_COURT);
		action("alarm", g, ProcCreature.A_ALARM);
		action("spawn", g, ProcCreature.A_SPAWN);
		action("death", g, ProcCreature.A_DEATH);
	}

	// ---- ground ------------------------------------------------------------

	/** An 8x8 stage whose interior is all {@code type}; the centre 4x4 tiles
	 *  become the swatch (border walls and their transitions cropped away). */
	private void ground(String name, Tile.TileType type) {
		World w = stage(8, 8);
		fill(w, type);
		w.alignTiles();
		BufferedImage img = frame(w, LayerBaker.chunkRenderer(w));
		int ts = ResourceManager.tileSize;
		add("ground_" + name + ".png", png(img.getSubimage(2 * ts, 2 * ts, 4 * ts, 4 * ts)),
				name.replace('_', ' '), 128);
	}

	/**
	 * Concealment: a body standing in a walkable sight-blocker is part-hidden
	 * by the tile's own re-stamped pixels ({@code Grid.renderConcealment}).
	 * Each scene is baked twice from the same staged world — empty ground,
	 * then with an inert occupant — so the web catalog can run ITS veil code
	 * over the identical ground image and sit the result beside this one.
	 */
	private void concealmentSection() {
		section("Concealment", "A body in cover is veiled by the tile's own re-stamped "
				+ "pixels — clustered canopy blocks over a thicket, stalk-exact reeds, the "
				+ "duct's ribbed lid. Left: empty ground. Right: an occupant, part-hidden.");
		conceal("canopy", Tile.TileType.TYPE_COVER);
		conceal("reeds", Tile.TileType.TYPE_REEDS);
		conceal("duct", Tile.TileType.TYPE_DUCT);
	}

	private void conceal(String name, Tile.TileType type) {
		World w = stage(7, 7);
		for (int x = 1; x <= 5; x++) {
			for (int y = 1; y <= 5; y++) {
				w.setTile(x, y, 0, type);
			}
		}
		w.alignTiles();
		LayerRenderer lr = LayerBaker.chunkRenderer(w);
		int ts = ResourceManager.tileSize;
		BufferedImage ground = frame(w, lr).getSubimage(ts, ts, 5 * ts, 5 * ts);
		add(name + "_ground.png", png(ground), name + ", empty", 160);
		w.spawnEntity(TestNPC.inert(3.5, 3.5, 0).withSize(8));
		w.think();
		BufferedImage veiled = frame(w, lr).getSubimage(ts, ts, 5 * ts, 5 * ts);
		add(name + ".png", png(veiled), "a body veiled in " + name, 160);
	}

	/** Lush grass grazed down to bare soil, one bite per frame — the live
	 *  depletion dither stepping through its art-pixel stages. */
	private void grassDepletion() {
		World w = stage(8, 8);
		w.alignTiles();
		LayerRenderer lr = LayerBaker.chunkRenderer(w);
		int ts = ResourceManager.tileSize;
		List<BufferedImage> frames = new ArrayList<>();
		for (int f = 0; f < 14; f++) {
			frames.add(frame(w, lr).getSubimage(2 * ts, 2 * ts, 4 * ts, 4 * ts));
			for (int x = 1; x < 7; x++) {
				for (int y = 1; y < 7; y++) {
					Tile t = w.getTile(x, y, 0);
					t.graze(w.getTick(), t.vegetationCap() * 0.09);
				}
			}
		}
		add("ground_grass_depletion.gif", gif(frames, 25), "grass, grazed bare", 128);
		// The lush and fully-grazed endpoints as silent assets (no catalog
		// figure of their own): the web catalog fetches them and runs the
		// client's OWN dither compositing between the two, beside this gif.
		assets.put("depletion_lush.png", png(frames.get(0)));
		assets.put("depletion_bare.png", png(frames.get(frames.size() - 1)));
	}

	// ---- furniture ---------------------------------------------------------

	/** A doorway in a masonry wall; the wired door opens, holds, and reseals. */
	private void doorCycle(int flavor) {
		World w = stage(9, 7);
		for (int x = 1; x < 8; x++) {
			w.setTile(x, 3, 0, Tile.TileType.TYPE_WALL_BUILT);
		}
		w.setTile(4, 3, 0, Tile.TileType.TYPE_PAVED);
		w.alignTiles();
		Door door = new Door(4, 3, 0, 0, flavor, 1);
		w.spawnEntity(door);
		door.setWired(true);
		LayerRenderer lr = LayerBaker.chunkRenderer(w);
		int ts = ResourceManager.tileSize;
		List<BufferedImage> frames = new ArrayList<>();
		for (int t = 0; t < 240; t++) {
			if (t >= 15 && t < 60) {
				door.holdOpen(); // a switch holding the circuit closed
			}
			w.think();
			if (t % 4 == 0) {
				frames.add(frame(w, lr).getSubimage(ts, ts, 7 * ts, 5 * ts));
			}
		}
		add("door_" + door.flavorName() + ".gif", gif(frames, 8),
				door.flavorName() + " door", 192);
	}

	/** A switch wired to a grate door across the room. PLATE: a crate parked on
	 *  it presses it; BUTTON: a body beside it deliberately operates it. */
	private void switchScene(String name, int mode) {
		World w = stage(11, 7);
		for (int x = 1; x < 10; x++) {
			w.setTile(x, 2, 0, Tile.TileType.TYPE_WALL_BUILT);
		}
		w.setTile(7, 2, 0, Tile.TileType.TYPE_PAVED);
		w.setTile(3, 4, 0, Tile.TileType.TYPE_SWITCH);
		w.alignTiles();
		Door door = new Door(7, 2, 0, 0, Door.GRATE, 1);
		w.spawnEntity(door);
		w.spawnEntity(new Switch(3, 4, 0, door, mode));
		LayerRenderer lr = LayerBaker.chunkRenderer(w);
		int ts = ResourceManager.tileSize;
		List<BufferedImage> frames = new ArrayList<>();
		net.hedinger.prototype.engine.Entity presser = null;
		for (int t = 0; t < 360; t++) {
			if (t == 30) {
				presser = mode == Switch.PLATE
						? Item.crate(3.5, 4.5, 0)
						: TestNPC.inert(4.5, 4.5, 0).withInteract().withSize(5);
				w.spawnEntity(presser);
			}
			if (t == 150 && presser != null) {
				presser.remove();
			}
			w.think();
			if (t % 5 == 0) {
				frames.add(frame(w, lr).getSubimage(ts, ts, 9 * ts, 5 * ts));
			}
		}
		add(name + ".gif", gif(frames, 8),
				mode == Switch.PLATE ? "pressure plate (weight)" : "button (intent)", 224);
	}

	/** The three item kinds side by side on open ground. */
	private void items() {
		World w = stage(9, 5);
		w.alignTiles();
		w.spawnEntity(Item.food(2.5, 2.5, 0));
		w.spawnEntity(Item.crate(4.5, 2.5, 0));
		w.spawnEntity(Item.hazard(6.5, 2.5, 0));
		w.think();
		BufferedImage img = frame(w, LayerBaker.chunkRenderer(w));
		int ts = ResourceManager.tileSize;
		add("items.png", png(img.getSubimage(ts, ts, 7 * ts, 3 * ts)),
				"food · crate · hazard", 224);
	}

	/** A brood nest on open grass — the woven twig ring births claim. */
	private void nest() {
		World w = stage(5, 5);
		w.alignTiles();
		for (int i = 0; i < 3; i++) {
			net.hedinger.prototype.entities.Nest.claimAt(w, 2.5, 2.5, 0);
		}
		w.think();
		BufferedImage img = frame(w, LayerBaker.chunkRenderer(w));
		int ts = ResourceManager.tileSize;
		add("nest.png", png(img.getSubimage(ts, ts, 3 * ts, 3 * ts)),
				"nest (brood site)", 160);
	}

	/** A pheromone deposit evaporating: the stipple haze thinning away. */
	private void pheromone() {
		World w = stage(7, 7);
		w.alignTiles();
		PheromoneCloud cloud = new PheromoneCloud(3.5, 3.5, 0, 8);
		w.spawnEntity(cloud);
		LayerRenderer lr = LayerBaker.chunkRenderer(w);
		int ts = ResourceManager.tileSize;
		List<BufferedImage> frames = new ArrayList<>();
		for (int t = 0; t < 240 && !cloud.isRemoved(); t++) {
			w.think();
			if (t % 8 == 0) {
				frames.add(frame(w, lr).getSubimage(ts, ts, 5 * ts, 5 * ts));
			}
		}
		add("pheromone.gif", gif(frames, 12), "pheromone cloud, evaporating", 160);
	}

	// ---- creatures ---------------------------------------------------------

	/** A deterministic genome at a chosen point of the phenotype space. */
	private static Genome sample(double size, double speed, boolean flying,
			double predatory, double[] markers) {
		Genome g = Genome.phenotype(size, speed, 5, 6, Math.PI / 2, 100000);
		g.flying = flying;
		g.predatory = predatory;
		for (int i = 0; i < g.markers.length && i < markers.length; i++) {
			g.markers[i] = markers[i];
		}
		return g;
	}

	/** One organism sample: a looping spin through all eight facings with the
	 *  idle gait running — the whole body readable in one loop. */
	private void creature(String name, Genome g) {
		ProcCreature.Phenotype ph = ProcCreature.phenotype(g);
		int radius = (int) Math.max(20, Math.min(70, g.size * 5));
		int side = radius * 2 + 60;
		List<BufferedImage> frames = new ArrayList<>();
		int N = 48;
		for (int f = 0; f < N; f++) {
			BufferedImage img = canvas(side);
			Graphics2D g2 = img.createGraphics();
			double heading = Math.PI * 2 * f / N;
			double phase = Math.PI * 2 * 6.0 * f / N; // six gait cycles per revolution
			ProcCreature.draw(g2, side / 2, side / 2 + (ph.flying ? 12 : 0), radius, ph,
					heading, phase);
			g2.dispose();
			frames.add(img);
		}
		add(name + ".gif", gif(frames, 8), name.replace('_', ' '), side);
	}

	/** One action envelope swept start-to-finish on a fixed body. */
	private void action(String name, Genome g, int act) {
		ProcCreature.Phenotype ph = ProcCreature.phenotype(g);
		int radius = 36, side = radius * 2 + 60;
		List<BufferedImage> frames = new ArrayList<>();
		int N = 24;
		for (int f = 0; f < N; f++) {
			BufferedImage img = canvas(side);
			Graphics2D g2 = img.createGraphics();
			double t = f / (double) (N - 1);
			ProcCreature.draw(g2, side / 2, side / 2, radius, ph, 0, 0,
					ProcCreature.actionMod(act, t, ph.color));
			g2.dispose();
			frames.add(img);
		}
		add("action_" + name + ".gif", gif(frames, 7), name, side);
	}

	// ---- staging & encoding ------------------------------------------------

	/** A one-level world whose interior is open grass floor (engine walls the
	 *  1-tile border on its own). */
	private static World stage(int cols, int rows) {
		World w = new World(cols, rows, 1);
		fill(w, Tile.TileType.TYPE_FLOOR);
		return w;
	}

	private static void fill(World w, Tile.TileType type) {
		for (int x = 1; x < w.getColums() - 1; x++) {
			for (int y = 1; y < w.getRows() - 1; y++) {
				w.setTile(x, y, 0, type);
			}
		}
	}

	private static BufferedImage frame(World w, LayerRenderer lr) {
		return LayerBaker.bakeLevelImage(w, lr, 0);
	}

	private static BufferedImage canvas(int side) {
		BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = img.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(new Color(0x14161a));
		g2.fillRect(0, 0, side, side);
		g2.dispose();
		return img;
	}

	private static byte[] png(BufferedImage img) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 14);
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		} catch (Exception e) {
			throw new IllegalStateException("catalog png encode failed", e);
		}
	}

	/** Encodes a looping GIF, {@code delayCs} centiseconds per frame. */
	private static byte[] gif(List<BufferedImage> frames, int delayCs) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
			ImageWriter wr = ImageIO.getImageWritersBySuffix("gif").next();
			MemoryCacheImageOutputStream os = new MemoryCacheImageOutputStream(bytes);
			wr.setOutput(os);
			wr.prepareWriteSequence(null);
			for (BufferedImage f : frames) {
				IIOMetadata md = wr.getDefaultImageMetadata(
						ImageTypeSpecifier.createFromRenderedImage(f), wr.getDefaultWriteParam());
				String fmt = md.getNativeMetadataFormatName();
				IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(fmt);
				IIOMetadataNode gce = child(root, "GraphicControlExtension");
				gce.setAttribute("disposalMethod", "none");
				gce.setAttribute("userInputFlag", "FALSE");
				gce.setAttribute("transparentColorFlag", "FALSE");
				gce.setAttribute("delayTime", Integer.toString(delayCs));
				gce.setAttribute("transparentColorIndex", "0");
				IIOMetadataNode apps = child(root, "ApplicationExtensions");
				IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
				app.setAttribute("applicationID", "NETSCAPE");
				app.setAttribute("authenticationCode", "2.0");
				app.setUserObject(new byte[] { 1, 0, 0 }); // loop forever
				apps.appendChild(app);
				md.setFromTree(fmt, root);
				wr.writeToSequence(new IIOImage(f, null, md), wr.getDefaultWriteParam());
			}
			wr.endWriteSequence();
			os.close();
			return bytes.toByteArray();
		} catch (Exception e) {
			throw new IllegalStateException("catalog gif encode failed", e);
		}
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name) {
		for (int i = 0; i < root.getLength(); i++) {
			if (root.item(i).getNodeName().equals(name)) {
				return (IIOMetadataNode) root.item(i);
			}
		}
		IIOMetadataNode n = new IIOMetadataNode(name);
		root.appendChild(n);
		return n;
	}

	// ---- page --------------------------------------------------------------

	private void section(String title, String note) {
		if (body.length() > 0) {
			body.append("</div>\n");
		}
		body.append("<h2>").append(title).append("</h2>\n<p class=note>").append(note)
				.append("</p>\n<div class=grid>\n");
	}

	private void add(String file, byte[] bytes, String label, int displayPx) {
		assets.put(file, bytes);
		body.append("<figure><img src=\"/sprites/").append(file)
				.append("\" style=\"width:").append(displayPx).append("px\" loading=lazy>")
				.append("<figcaption>").append(label).append("</figcaption></figure>\n");
	}

	/** The gallery page. */
	String page() {
		return "<!doctype html><meta charset=utf-8><title>bluproto — sprite catalog</title>"
				+ "<style>"
				+ "body{background:#0f1115;color:#cdd3de;font:14px/1.5 system-ui,sans-serif;"
				+ "margin:2rem auto;max-width:1200px;padding:0 1rem}"
				+ "h1{font-size:1.4rem}h2{margin-top:2.2rem;border-bottom:1px solid #262b36;"
				+ "padding-bottom:.3rem}"
				+ ".note{color:#8b93a3;max-width:70ch}"
				+ ".grid{display:flex;flex-wrap:wrap;gap:14px;align-items:flex-end}"
				+ "figure{margin:0;text-align:center}"
				+ "img{image-rendering:pixelated;background:#14161a;border:1px solid #262b36;"
				+ "border-radius:4px;display:block;margin:0 auto}"
				+ "figcaption{color:#8b93a3;font-size:12px;margin-top:4px}"
				+ "a{color:#7aa2f7}"
				+ "</style>"
				+ "<h1>sprite catalog</h1>"
				+ "<p class=note>The art system, rendered by the same Java pipeline that bakes "
				+ "the world: ground swatches from the layer bake, furniture and items played "
				+ "through their state changes, and hand-picked samples of the procedural "
				+ "creature space. Static per build — this page is a reference, not a census "
				+ "of what is alive right now.</p>"
				+ body + "</div>";
	}
}
