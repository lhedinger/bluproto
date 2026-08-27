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
	// Captions and layout accumulated while baking. Nothing reads it any more:
	// this class had a page() that rendered a second, java-flavoured catalog, and
	// that page is gone -- /sprites is now the single catalog, drawn by the client
	// that draws the world. What survives here is the BAKING, because the client
	// page composites several of these images. Kept rather than unpicked so the
	// section()/add() helpers still read as a description of what is being baked.
	private final StringBuilder body = new StringBuilder();

	static SpriteCatalog render() {
		long t0 = System.currentTimeMillis();
		SpriteCatalog c = new SpriteCatalog();
		Utils.seed(CATALOG_SEED);
		c.groundSection();
		c.concealmentSection();
		c.furnitureSection();
		c.creatureSection();
		c.tileCatalog();
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
				+ "A tile type's look is fixed by its type and its fertility — the "
				+ "ground that grows things is drawn at the potential it has, rich or "
				+ "poor. What is actually standing there to be eaten (and its "
				+ "depletion) is the sprite layer stamped on top, not part of the "
				+ "ground.");
		ground("grassland_soil", Tile.TileType.TYPE_FLOOR);
		ground("grassland_poor", Tile.TileType.TYPE_FLOOR, 0.25);
		ground("rocky_grassland", Tile.TileType.TYPE_ROCKY, 0.30);
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
		ground("charge_dock", Tile.TileType.TYPE_DOCK);
		ground("waste_sludge", Tile.TileType.TYPE_SLUDGE);
		ground("mesa_rock", Tile.TileType.TYPE_MESA);
		ground("stalagmite", Tile.TileType.TYPE_STALAGMITE);
		ground("cactus", Tile.TileType.TYPE_CACTUS);
		ground("bone_field", Tile.TileType.TYPE_BONES);
		ground("hazard_striping", Tile.TileType.TYPE_HAZARD);
		ground("conveyor", Tile.TileType.TYPE_CONVEYOR);
		ground("window_wall", Tile.TileType.TYPE_WINDOW);
		ground("desk", Tile.TileType.TYPE_DESK);
		ground("bunk", Tile.TileType.TYPE_BUNK);
		ground("dead_machine", Tile.TileType.TYPE_WRECK);
		ground("tram_rail", Tile.TileType.TYPE_RAIL);
		ground("server_bank", Tile.TileType.TYPE_SERVER);
		ground("loading_deck", Tile.TileType.TYPE_TREADPLATE);
		ground("lit_grating", Tile.TileType.TYPE_LIGHTGRATE);
		ground("collapsed_deck", Tile.TileType.TYPE_COLLAPSE);
		ground("coolant_run", Tile.TileType.TYPE_COOLANT);
		ground("heat_exchanger", Tile.TileType.TYPE_EXCHANGER);
		ramps();
	}

	/** The level links, staged with the context that orients them: a DOWN ramp
	 *  fading into the pit it pours into (its dark bands meet the void with no
	 *  lip between), and an UP ramp brightening to a sunlit high end against
	 *  the rock its landing rests on. Both are staged facing east, the default
	 *  a ramp is born with; the slope art takes its direction from the tile
	 *  itself, so a ramp faces whichever way its world laid it. */
	private void ramps() {
		World w = stage(8, 8);
		for (int y = 3; y <= 4; y++) {
			w.setTile(2, y, 0, Tile.TileType.TYPE_HOLE);
			w.setTile(3, y, 0, Tile.TileType.TYPE_RAMPDOWN);
			w.setTile(5, y, 0, Tile.TileType.TYPE_RAMPUP);
			w.setTile(6, y, 0, Tile.TileType.TYPE_WALL);
		}
		w.alignTiles();
		BufferedImage img = frame(w, LayerBaker.chunkRenderer(w));
		int ts = ResourceManager.tileSize;
		add("ground_ramps.png", png(img.getSubimage(ts, 2 * ts, 6 * ts, 4 * ts)),
				"ramps: down into the pit, up into the light", 192);
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

	/**
	 * Every body plan the renderer can draw, whether or not anything in the world
	 * wears it. Three of the six are unselected: the plan follows the genome's clade
	 * and there are three trophic levels, so half the space sits idle. A reference
	 * that only showed what happened to be in use would quietly stop being a
	 * reference to the space and become a census of the population, and the plans
	 * nobody wears are exactly the ones worth being able to look at before deciding
	 * what to do with them.
	 *
	 * <p>Drawn from one genome with the trophic markings off, so what differs
	 * between these is the outline and nothing else. The animals that actually
	 * carry them — feelers, tail and all — are in Creatures above.
	 */
	private void creatureSection() {
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
		ground(name, type, 1.0);
	}

	/** As above, at a chosen fertility — the ground types whose art is drawn
	 *  from their growing potential (grassland's sward, rocky ground's thin
	 *  one) look different at each end of their range, so their swatch has to
	 *  say which end it is showing. */
	private void ground(String name, Tile.TileType type, double fertility) {
		World w = stage(8, 8);
		fill(w, type);
		for (int x = 0; x < w.getColums(); x++) {
			for (int y = 0; y < w.getRows(); y++) {
				w.getTile(x, y, 0).setFertility(fertility);
			}
		}
		w.alignTiles();
		// A reference body in every walkable swatch, so the texture reads at
		// creature scale (and cover types demonstrate their veil). Walls,
		// water and drops stay empty — nothing stands there.
		if (w.getTile(4, 4, 0).isWalkable()) {
			w.spawnEntity(TestNPC.inert(4.0, 4.0, 0).withGenome(referenceBody()));
			settle(w); // past the spawn queue AND the newborn dissolve-in
		}
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
		w.spawnEntity(TestNPC.inert(3.5, 3.5, 0).withGenome(referenceBody()));
		settle(w);
		BufferedImage veiled = frame(w, lr).getSubimage(ts, ts, 5 * ts, 5 * ts);
		add(name + ".png", png(veiled), "a body veiled in " + name, 160);
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
	/**
	 * The bodies the help page shows, as SHAPE KEYS rather than pictures.
	 *
	 * <p>The page used to be handed baked GIFs of these. It is the viewer's own
	 * renderer that decides what a creature looks like — it stamps the server's
	 * colour-neutral atlas and tints it — so a GIF baked down a second path was a
	 * picture of what the art ought to be rather than of what anyone sees. These
	 * are registered so an atlas is servable for each, and the page draws them the
	 * way the live world does.
	 *
	 * <p>Every plan appears, including the two no clade selects: a reference that
	 * shows only what happens to be alive is a census, not a reference.
	 */
	static java.util.List<java.util.Map<String, Object>> referenceBodies() {
		java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
		String[] planNames = { "grazer", "parasite", "wedge", "scavenger", "hunter", "ragged" };
		boolean[] worn = { true, true, false, true, true, false };
		for (int f = 0; f < planNames.length; f++) {
			Genome g = sample(11, 0.05, false, 0, new double[] { 0.5, 0.6, 0.4 });
			ProcCreature.Phenotype ph = ProcCreature.phenotype(g);
			ph.form = f; // reach the plans the clade mapping cannot express
			out.add(body("plan", "plan " + f + " — " + planNames[f], ph, worn[f], 0x9BB8A0));
		}
		addSample(out, "founder grazer", 6, false, Genome.Clade.HERBIVORE,
				new double[] { 0.20, 0.50, 0.80 }, 0x5A9BD8);
		addSample(out, "tiny darter", 3, false, Genome.Clade.HERBIVORE,
				new double[] { 0.90, 0.10, 0.40 }, 0xE0507A);
		addSample(out, "bulky armoured", 14, false, Genome.Clade.HERBIVORE,
				new double[] { 0.50, 0.90, 0.20 }, 0x8FD046);
		addSample(out, "herd mother", 8, false, Genome.Clade.HERBIVORE,
				new double[] { 0.35, 0.70, 0.55 }, 0x54C0A0);
		addSample(out, "carrion eater", 7, false, Genome.Clade.SCAVENGER,
				new double[] { 0.45, 0.25, 0.65 }, 0xA070D0);
		addSample(out, "parasite", 4, false, Genome.Clade.PARASITE,
				new double[] { 0.70, 0.15, 0.30 }, 0xB07FE0);
		addSample(out, "apex predator", 11, false, Genome.Clade.PREDATOR,
				new double[] { 0.05, 0.05, 0.90 }, 0x4050E0);
		addSample(out, "flier", 5, true, Genome.Clade.HERBIVORE,
				new double[] { 0.60, 0.30, 0.10 }, 0xC07830);
		addSample(out, "winged hunter", 9, true, Genome.Clade.PREDATOR,
				new double[] { 0.15, 0.60, 0.35 }, 0x30C060);
		return out;
	}

	private static void addSample(java.util.List<java.util.Map<String, Object>> out,
			String label, double size, boolean flying, Genome.Clade clade, double[] markers, int rgb) {
		Genome g = sample(size, 0.06, flying, clade == Genome.Clade.PREDATOR ? 0.9 : 0,
				clade, markers);
		out.add(body("creature", label, ProcCreature.phenotype(g), true, rgb));
	}

	private static java.util.Map<String, Object> body(String group, String label,
			ProcCreature.Phenotype ph, boolean worn, int rgb) {
		java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("group", group);
		m.put("label", label);
		m.put("pheno", net.hedinger.prototype.sim.PhenoRegistry.register(ph));
		m.put("worn", worn);
		m.put("rgb", rgb);
		return m;
	}

	private static Genome sample(double size, double speed, boolean flying,
			double predatory, double[] markers) {
		return sample(size, speed, flying, predatory, Genome.Clade.HERBIVORE, markers);
	}

	/** As above, for a sample whose trophic level is the point of it. Diet is what
	 *  the body plan is drawn from, so a predator sample has to say it is one --
	 *  passing a high {@code predatory} never did, and the "apex_predator" entry was
	 *  drawing a grazer under a hunter's name. */
	private static Genome sample(double size, double speed, boolean flying,
			double predatory, Genome.Clade clade, double[] markers) {
		Genome g = Genome.phenotype(size, speed, 5, 6, Math.PI / 2, 100000);
		g.flying = flying;
		g.predatory = predatory;
		g.clade = clade;
		for (int i = 0; i < g.markers.length && i < markers.length; i++) {
			g.markers[i] = markers[i];
		}
		return g;
	}

	/** One organism sample: a looping spin through all eight facings with the
	 *  idle gait running — the whole body readable in one loop. */
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
	/** Ticks a staged world past the spawn queue and the newborn's 24-tick
	 *  dissolve-in, so a reference occupant is baked fully materialised. */
	private static void settle(World w) {
		for (int i = 0; i < 30; i++) {
			w.think();
		}
	}

	/** The genome every staged reference occupant wears: the founder grazer's
	 *  markers (so it is the same creature the gallery leads with) at herd-
	 *  matriarch size, because a size-6 body at true world scale reads as a
	 *  dot in a 128-px swatch — the reference must read as a creature. */
	private static Genome referenceBody() {
		return sample(12, 0.05, false, 0, new double[] { 0.20, 0.50, 0.80 });
	}

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


	// ---- the /tiles catalog -------------------------------------------------

	/** The finished /tiles page, built once at boot beside the sprite bakes. */
	private String tilesHtml;

	String tilesPage() {
		return tilesHtml;
	}

	/**
	 * The functional grouping of every tile type, in page order. This is the
	 * catalog's table of contents AND its completeness gate: the method walks
	 * the whole enum and throws if any type is missing or listed twice, so a
	 * new tile cannot ship without deciding where it belongs — the same
	 * mechanism that keeps the ground painters from silently skipping one.
	 */
	static java.util.LinkedHashMap<String, Tile.TileType[]> tileGroups() {
		java.util.LinkedHashMap<String, Tile.TileType[]> g = new java.util.LinkedHashMap<>();
		g.put("Open country", new Tile.TileType[] {
				Tile.TileType.TYPE_FLOOR, Tile.TileType.TYPE_SAND, Tile.TileType.TYPE_ROCKY,
				Tile.TileType.TYPE_MUD, Tile.TileType.TYPE_RUBBLE, Tile.TileType.TYPE_BONES });
		g.put("Water", new Tile.TileType[] {
				Tile.TileType.TYPE_WATER, Tile.TileType.TYPE_SHALLOWS, Tile.TileType.TYPE_REEDS });
		g.put("Flora & cover", new Tile.TileType[] {
				Tile.TileType.TYPE_COVER, Tile.TileType.TYPE_CACTUS });
		g.put("Rock & caves", new Tile.TileType[] {
				Tile.TileType.TYPE_WALL, Tile.TileType.TYPE_MESA, Tile.TileType.TYPE_STONE,
				Tile.TileType.TYPE_FUNGUS, Tile.TileType.TYPE_CRYSTAL,
				Tile.TileType.TYPE_CRYSTAL_BED, Tile.TileType.TYPE_CRYSTAL_SPARSE,
				Tile.TileType.TYPE_STALAGMITE, Tile.TileType.TYPE_VENT });
		g.put("Hazardous ground", new Tile.TileType[] {
				Tile.TileType.TYPE_QUICKSAND, Tile.TileType.TYPE_SLUDGE });
		g.put("Vertical travel", new Tile.TileType[] {
				Tile.TileType.TYPE_RAMPUP, Tile.TileType.TYPE_RAMPDOWN,
				Tile.TileType.TYPE_HOLE, Tile.TileType.TYPE_SHAFT });
		g.put("Built walls & glazing", new Tile.TileType[] {
				Tile.TileType.TYPE_WALL_BUILT, Tile.TileType.TYPE_WALL_CONCRETE,
				Tile.TileType.TYPE_WALL_STEEL, Tile.TileType.TYPE_WINDOW });
		g.put("Facility floors", new Tile.TileType[] {
				Tile.TileType.TYPE_PAVED, Tile.TileType.TYPE_PLATE,
				Tile.TileType.TYPE_TREADPLATE, Tile.TileType.TYPE_LIGHTGRATE,
				Tile.TileType.TYPE_CATWALK, Tile.TileType.TYPE_COLLAPSE,
				Tile.TileType.TYPE_HAZARD });
		g.put("Runs & lines", new Tile.TileType[] {
				Tile.TileType.TYPE_RAIL, Tile.TileType.TYPE_PIPES, Tile.TileType.TYPE_COOLANT,
				Tile.TileType.TYPE_CONVEYOR, Tile.TileType.TYPE_DUCT,
				Tile.TileType.TYPE_AIRVENT });
		g.put("Machines & fixtures", new Tile.TileType[] {
				Tile.TileType.TYPE_SWITCH, Tile.TileType.TYPE_DOCK,
				Tile.TileType.TYPE_EXCHANGER, Tile.TileType.TYPE_SERVER });
		g.put("Furniture & remains", new Tile.TileType[] {
				Tile.TileType.TYPE_DESK, Tile.TileType.TYPE_BUNK, Tile.TileType.TYPE_WRECK });

		java.util.Set<Tile.TileType> seen = java.util.EnumSet.noneOf(Tile.TileType.class);
		for (Tile.TileType[] members : g.values()) {
			for (Tile.TileType t : members) {
				if (!seen.add(t)) {
					throw new IllegalStateException("/tiles lists " + t + " twice");
				}
			}
		}
		for (Tile.TileType t : Tile.TileType.values()) {
			if (!seen.contains(t)) {
				throw new IllegalStateException("/tiles is missing " + t
						+ " — every tile type must choose a group");
			}
		}
		return g;
	}

	private void tileCatalog() {
		StringBuilder nav = new StringBuilder();
		nav.append("<a href=\"#rules\">How tiles fit</a>");
		StringBuilder sections = new StringBuilder();
		for (java.util.Map.Entry<String, Tile.TileType[]> e : tileGroups().entrySet()) {
			String id = e.getKey().toLowerCase(java.util.Locale.ROOT)
					.replaceAll("[^a-z]+", "-");
			nav.append("<a href=\"#").append(id).append("\">")
					.append(e.getKey()).append("</a>");
			sections.append("<h2 id=\"").append(id).append("\">")
					.append(e.getKey()).append("</h2>\n<div class=tgrid>\n");
			for (Tile.TileType t : e.getValue()) {
				sections.append(tileCard(t));
			}
			sections.append("</div>\n");
		}
		tilesHtml = "<!doctype html><html><head><meta charset=utf-8>"
				+ "<meta name=viewport content=\"width=device-width, initial-scale=1\">"
				+ "<title>tiles</title><style>"
				+ "body{background:#14161a;color:#cfd3da;font:14px/1.5 system-ui,sans-serif;"
				+ "margin:0 auto;max-width:1100px;padding:16px}"
				+ "h1{font-size:22px}h2{margin-top:28px;border-bottom:1px solid #2a2e36;"
				+ "padding-bottom:4px}"
				+ "nav{position:sticky;top:0;background:#14161acc;backdrop-filter:blur(4px);"
				+ "padding:8px 0;display:flex;flex-wrap:wrap;gap:4px 12px;z-index:2}"
				+ "nav a{color:#8fb8e8;text-decoration:none;white-space:nowrap}"
				+ ".note{color:#8b909a;max-width:70ch}"
				+ ".tgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));"
				+ "gap:12px}"
				+ ".tile{background:#191c22;border:1px solid #262a32;border-radius:6px;"
				+ "padding:10px}"
				+ ".tile b{font-size:15px}"
				+ ".code{color:#7d828c;font:12px ui-monospace,monospace;margin:2px 0 6px}"
				+ ".flags{display:flex;flex-wrap:wrap;gap:4px;margin-bottom:8px}"
				+ ".flag{background:#232833;border-radius:3px;padding:1px 6px;font-size:11.5px;"
				+ "color:#aab2c0}"
				+ ".flag.warn{background:#3a2a1a;color:#d8b028}"
				+ ".vrow{display:flex;flex-wrap:wrap;gap:8px}"
				+ "figure{margin:0;text-align:center}"
				+ "figure img{width:96px;height:96px;image-rendering:pixelated;"
				+ "border-radius:3px;background:#0d0e11}"
				+ "figcaption{font-size:11px;color:#8b909a}"
				+ "</style></head><body>"
				+ "<h1>The tile catalog</h1>"
				+ "<p class=note>Part of the world's documentation: <a href=\"/help\">"
				+ "/help</a> covers the creatures, furniture and mechanics the web "
				+ "client draws live; this page covers the ground it all stands on.</p>"
				+ "<p class=note>Every ground type in the world, baked by the same layer "
				+ "renderer that bakes the map a viewer sees. Each entry names the tile "
				+ "(its display name, its code name, and its wire value), lists the flags "
				+ "the engine derives from it, and shows its variants — fertility ends "
				+ "for the ground that grows, the shapes an autotiled run can take, and "
				+ "a lone fixture on the floor it lives on. A see-through swatch means "
				+ "exactly what it means on the map: you are looking into an opening, "
				+ "and the live client shows the level below through it.</p>"
				+ "<nav>" + nav + "</nav>"
				+ "<p class=note><b>Flags.</b> <i>walkable</i>: a body can stand here. "
				+ "<i>solid</i>: it cannot. <i>see-through</i>: solid to a body, open to "
				+ "the eye. <i>blocks sight</i>: cover or mass in the line of sight. "
				+ "<i>drops</i>: an unsupported body falls to the level below. "
				+ "<i>water</i>: open to flyers, closed to walkers. <i>slows</i>: drag on "
				+ "whatever crosses (factor at reference body size). <i>wounds</i>: "
				+ "standing here costs health. <i>grows</i>: hosts regrowing vegetation. "
				+ "<i>small bodies only</i>: a clearance gate, not a wall.</p>"
				+ tileRules()
				+ sections
				+ "</body></html>";
	}

	private String tileCard(Tile.TileType t) {
		Tile probe = new Tile(0, 0, 0, t);
		StringBuilder flags = new StringBuilder();
		if (probe.isWalkable()) {
			flag(flags, "walkable", false);
		} else if (probe.isSolid()) {
			flag(flags, "solid", false);
		}
		if (probe.isSolid() && !probe.blocksSight()) {
			flag(flags, "see-through", false);
		} else if (probe.blocksSight() && !probe.isSolid()) {
			flag(flags, "blocks sight", false);
		}
		if (probe.isDrop()) {
			flag(flags, "drops", true);
		}
		if (probe.isWater()) {
			flag(flags, "water", false);
		}
		double drag = probe.speedFactorFor(8);
		if (drag < 1.0) {
			flag(flags, "slows \u00d7" + String.format(java.util.Locale.ROOT, "%.2f", drag),
					drag < 0.35);
		}
		if (probe.isCorrosive()) {
			flag(flags, "wounds", true);
		}
		if (probe.growsVegetation()) {
			flag(flags, "grows", false);
		}
		if (t == Tile.TileType.TYPE_DUCT || t == Tile.TileType.TYPE_CRYSTAL_BED) {
			flag(flags, "small bodies only", false);
		}
		if (t == Tile.TileType.TYPE_RAMPUP) {
			flag(flags, "climbs a level", false);
		}
		if (t == Tile.TileType.TYPE_RAMPDOWN) {
			flag(flags, "descends a level", false);
		}

		StringBuilder variants = new StringBuilder();
		tileVariants(t, variants);
		return "<div class=tile><b>" + t.label() + "</b>"
				+ "<div class=code>" + t.name() + " \u00b7 " + t.getValue() + "</div>"
				+ "<div class=flags>" + flags + "</div>"
				+ "<div class=vrow>" + variants + "</div></div>\n";
	}

	private static void flag(StringBuilder out, String label, boolean warn) {
		out.append("<span class=\"flag").append(warn ? " warn" : "").append("\">")
				.append(label).append("</span>");
	}


	/**
	 * Which variants a tile shows, and how each is staged. Fertility-driven
	 * ground shows both ends of its range; autotiled runs show the shapes the
	 * mask can take; fixtures stand alone on the floor they live on; walls
	 * stand as a mass over open ground so the face and the raised read show;
	 * everything else is a plain field swatch.
	 */
	private void tileVariants(Tile.TileType t, StringBuilder out) {
		switch (t) {
		case TYPE_FLOOR:
			fieldVariant(out, t, "fertile", 1.0);
			fieldVariant(out, t, "poor", 0.25);
			return;
		case TYPE_ROCKY:
			fieldVariant(out, t, "fertile", 0.9);
			fieldVariant(out, t, "poor", 0.3);
			return;
		case TYPE_FUNGUS:
			fieldVariant(out, t, "rich bed", 0.8);
			fieldVariant(out, t, "thin bed", 0.2);
			return;
		case TYPE_RAIL:
		case TYPE_PIPES:
		case TYPE_COOLANT:
			runVariant(out, t, "straight", RUN_STRAIGHT);
			runVariant(out, t, "elbow", RUN_ELBOW);
			runVariant(out, t, "tee", RUN_TEE);
			runVariant(out, t, "crossing", RUN_CROSS);
			return;
		case TYPE_CONVEYOR:
		case TYPE_DUCT:
			runVariant(out, t, "east-west", RUN_STRAIGHT);
			runVariant(out, t, "north-south", RUN_VERT);
			return;
		case TYPE_WALL:
			blockVariant(out, t, "mass over meadow", Tile.TileType.TYPE_FLOOR);
			return;
		case TYPE_MESA:
			blockVariant(out, t, "mass over sand", Tile.TileType.TYPE_SAND);
			return;
		case TYPE_WALL_BUILT:
		case TYPE_WALL_CONCRETE:
		case TYPE_WALL_STEEL:
		case TYPE_WINDOW:
			blockVariant(out, t, "run over paving", Tile.TileType.TYPE_PAVED);
			return;
		case TYPE_SERVER:
			blockVariant(out, t, "rack row on deck", Tile.TileType.TYPE_PLATE);
			return;
		case TYPE_STALAGMITE:
			fixtureVariant(out, t, "on cave stone", Tile.TileType.TYPE_STONE);
			return;
		case TYPE_CACTUS:
			fixtureVariant(out, t, "on sand", Tile.TileType.TYPE_SAND);
			return;
		case TYPE_SWITCH:
			fixtureVariant(out, t, "seat on deck", Tile.TileType.TYPE_PLATE);
			return;
		case TYPE_DOCK:
			fixtureVariant(out, t, "berth on deck", Tile.TileType.TYPE_PLATE);
			return;
		case TYPE_DESK:
			fixtureVariant(out, t, "on paving", Tile.TileType.TYPE_PAVED);
			return;
		case TYPE_BUNK:
			fixtureVariant(out, t, "on deck", Tile.TileType.TYPE_PLATE);
			return;
		case TYPE_WRECK:
			fixtureVariant(out, t, "in its debris", Tile.TileType.TYPE_RUBBLE);
			return;
		case TYPE_RAMPUP:
			rampVariant(out, t, "cut into rock, climbing west");
			return;
		case TYPE_RAMPDOWN:
			rampVariant(out, t, "cut into rock, descending east");
			return;
		case TYPE_HOLE:
			fieldVariant(out, t, "bottomless opening", 1.0);
			return;
		case TYPE_SHAFT:
			fieldVariant(out, t, "open shaft", 1.0);
			return;
		default:
			fieldVariant(out, t, t.label(), 1.0);
		}
	}

	private static final int RUN_STRAIGHT = 0, RUN_ELBOW = 1, RUN_TEE = 2, RUN_CROSS = 3,
			RUN_VERT = 4;

	private void fieldVariant(StringBuilder out, Tile.TileType t, String cap, double fert) {
		World w = stage(8, 8);
		fill(w, t);
		for (int x = 0; x < 8; x++) {
			for (int y = 0; y < 8; y++) {
				w.getTile(x, y, 0).setFertility(fert);
			}
		}
		bakeVariant(out, w, t, cap);
	}

	private void runVariant(StringBuilder out, Tile.TileType t, String cap, int shape) {
		World w = stage(8, 8);
		fill(w, Tile.TileType.TYPE_PLATE);
		if (shape == RUN_VERT) {
			for (int y = 1; y <= 6; y++) {
				w.setTile(3, y, 0, t);
			}
		} else {
			if (shape != RUN_ELBOW) {
				for (int x = 1; x <= 6; x++) {
					w.setTile(x, 3, 0, t); // the through run
				}
			} else {
				for (int x = 1; x <= 3; x++) {
					w.setTile(x, 3, 0, t); // the arm that turns
				}
			}
			if (shape != RUN_STRAIGHT) {
				int y0 = shape == RUN_CROSS ? 1 : 3;
				for (int y = y0; y <= 6; y++) {
					w.setTile(3, y, 0, t); // the branch
				}
			}
		}
		bakeVariant(out, w, t, cap);
	}

	private void blockVariant(StringBuilder out, Tile.TileType t, String cap,
			Tile.TileType base) {
		World w = stage(8, 8);
		fill(w, base);
		for (int x = 2; x <= 5; x++) {
			for (int y = 2; y <= 3; y++) {
				w.setTile(x, y, 0, t); // two courses, so the face fronts open ground
			}
		}
		bakeVariant(out, w, t, cap);
	}

	private void fixtureVariant(StringBuilder out, Tile.TileType t, String cap,
			Tile.TileType base) {
		World w = stage(8, 8);
		fill(w, base);
		w.setTile(3, 3, 0, t);
		bakeVariant(out, w, t, cap);
	}

	private void rampVariant(StringBuilder out, Tile.TileType t, String cap) {
		World w = stage(8, 8);
		fill(w, Tile.TileType.TYPE_FLOOR);
		for (int x = 1; x <= 3; x++) {
			for (int y = 1; y <= 6; y++) {
				w.setTile(x, y, 0, Tile.TileType.TYPE_WALL); // the mass the cut is in
			}
		}
		w.setTile(3, 3, 0, t);
		w.getTile(3, 3, 0).setRampUphill(3); // uphill west, into the rock
		bakeVariant(out, w, t, cap);
	}

	/** Bakes the staged world and appends the variant figure. Every variant
	 *  crops the same centre 4x4 so the swatches sit side by side honestly. */
	private void bakeVariant(StringBuilder out, World w, Tile.TileType t, String cap) {
		w.alignTiles();
		BufferedImage img = frame(w, LayerBaker.chunkRenderer(w));
		int ts = ResourceManager.tileSize;
		String file = "tile_" + t.name().toLowerCase(java.util.Locale.ROOT) + "_"
				+ cap.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
				+ ".png";
		assets.put(file, png(img.getSubimage(2 * ts, 2 * ts, 4 * ts, 4 * ts)));
		out.append("<figure><img src=\"/tiles/").append(file)
				.append("\" loading=lazy><figcaption>").append(cap)
				.append("</figcaption></figure>");
	}


	/**
	 * How tiles fit together: the general rules of the grid, each stated and
	 * then demonstrated with a staged bake. The prose is written against the
	 * engine's actual behaviour, and where a rule is a number or an ordering,
	 * the page reads it off the running code rather than transcribing it —
	 * the lapping order comes from {@code Grid.edgeRankOf}, the clearances
	 * from the tile's own constants.
	 */
	private String tileRules() {
		StringBuilder out = new StringBuilder();
		out.append("<h2 id=\"rules\">How tiles fit together</h2>");

		// ---- one mass, and the seam between materials ----
		out.append("<p class=note><b>Same type, one mass.</b> Wall tiles of one "
				+ "material autotile into a single mass: corners round off, rims "
				+ "silhouette the outline, and the lighting pass adds the raised "
				+ "read — a lit brow where the mass opens north, a carved face band "
				+ "where it fronts open ground to the south, a cornice and base "
				+ "shadow all round. Two different wall materials never merge: the "
				+ "boundary between them is a real seam, because a concrete pour "
				+ "against living rock IS a seam.</p><div class=vrow>");
		StringBuilder demo = new StringBuilder();
		{
			World w = stage(8, 8);
			for (int x = 2; x <= 5; x++) {
				w.setTile(x, 2, 0, Tile.TileType.TYPE_WALL);
			}
			for (int y = 2; y <= 5; y++) {
				w.setTile(2, y, 0, Tile.TileType.TYPE_WALL);
			}
			bakeRule(demo, w, 0, "rule_mass_corner", "one material, one mass");
		}
		{
			World w = stage(8, 8);
			for (int y = 2; y <= 4; y++) {
				w.setTile(2, y, 0, Tile.TileType.TYPE_WALL);
				w.setTile(3, y, 0, Tile.TileType.TYPE_WALL);
				w.setTile(4, y, 0, Tile.TileType.TYPE_WALL_CONCRETE);
				w.setTile(5, y, 0, Tile.TileType.TYPE_WALL_CONCRETE);
			}
			bakeRule(demo, w, 0, "rule_material_seam", "two materials, one seam");
		}
		out.append(demo).append("</div>");

		// ---- ramps ----
		out.append("<p class=note><b>A ramp is floor that spans two levels.</b> "
				+ "The tile carries which way its high side faces; a body that "
				+ "walks off the top comes out one level up, off the foot one level "
				+ "down, and the change lands at the tile edge — crossing the ramp "
				+ "itself is ordinary walking. An up ramp is ONE MASS with the rock "
				+ "it climbs into: no cliff face is drawn across its head, because "
				+ "the head is where the climb arrives, level with the rock. The "
				+ "worlds join their floors with a four-tile stairwell idiom: a "
				+ "hole to fall through, a descending ramp beside it so the fall "
				+ "is not the only way, a landing below, and a climbing ramp back "
				+ "up. Both floors of one stairwell, side by side:</p><div class=vrow>");
		demo = new StringBuilder();
		{
			World w = new World(8, 8, 2);
			for (int x = 1; x < 7; x++) {
				for (int y = 1; y < 7; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
					w.setTile(x, y, 1, Tile.TileType.TYPE_PLATE);
				}
			}
			w.setTile(2, 3, 1, Tile.TileType.TYPE_HOLE);
			w.setTile(3, 3, 1, Tile.TileType.TYPE_RAMPDOWN);
			w.getTile(3, 3, 1).setRampUphill(1);
			w.setTile(2, 3, 0, Tile.TileType.TYPE_PLATE);
			w.setTile(4, 3, 0, Tile.TileType.TYPE_RAMPUP);
			w.getTile(4, 3, 0).setRampUphill(1);
			bakeRule(demo, w, 1, "rule_stair_upper", "the floor above: hole + down ramp");
			bakeRule(demo, w, 0, "rule_stair_lower", "the floor below: landing + up ramp");
		}
		out.append(demo).append("</div>");

		// ---- edge lapping, ranked ----
		StringBuilder order = new StringBuilder();
		java.util.List<Tile.TileType> ranked = new java.util.ArrayList<>();
		for (Tile.TileType t : Tile.TileType.values()) {
			if (net.hedinger.prototype.engine.Grid.edgeRankOf(t) >= 0) {
				ranked.add(t);
			}
		}
		ranked.sort(java.util.Comparator.comparingInt(
				net.hedinger.prototype.engine.Grid::edgeRankOf));
		for (int i = 0; i < ranked.size(); i++) {
			if (i > 0) {
				order.append(" &lt; ");
			}
			order.append(ranked.get(i).label());
		}
		out.append("<p class=note><b>Open ground laps by rank.</b> Where two open "
				+ "terrains meet, the higher-ranked one laps one to three pixels "
				+ "into its neighbour in short hand-drawn scallops, so the slope "
				+ "from water to rock always reads in the same order. The ranking, "
				+ "read off the renderer itself, lowest first: <i>" + order
				+ "</i>. Everything not in that list — paving, deck, walls, "
				+ "openings — takes a hard edge and neither laps nor gets "
				+ "lapped.</p><div class=vrow>");
		demo = new StringBuilder();
		{
			World w = stage(8, 8);
			for (int y = 1; y < 7; y++) {
				w.setTile(1, y, 0, Tile.TileType.TYPE_WATER);
				w.setTile(2, y, 0, Tile.TileType.TYPE_WATER);
				w.setTile(3, y, 0, Tile.TileType.TYPE_SHALLOWS);
				w.setTile(4, y, 0, Tile.TileType.TYPE_SAND);
				w.setTile(5, y, 0, Tile.TileType.TYPE_ROCKY);
				w.setTile(6, y, 0, Tile.TileType.TYPE_STONE);
			}
			bakeRule(demo, w, 0, "rule_lapping", "water to rock, lapped in rank order");
		}
		out.append(demo).append("</div>");

		// ---- openings ----
		out.append("<p class=note><b>An opening is a hole, not a picture of one.</b> "
				+ "A pit or shaft bakes genuinely see-through inside its lip, and "
				+ "the live client shows the real level below through the gap, "
				+ "moving with its own parallax. A body over an opening falls to "
				+ "the first walkable floor beneath it; if what waits below is "
				+ "solid rock, or there is no floor at all, it falls out of the "
				+ "world. By the map's own convention, the size of an opening "
				+ "tells you the size of the void under it. The same stage, both "
				+ "floors:</p><div class=vrow>");
		demo = new StringBuilder();
		{
			World w = new World(8, 8, 2);
			for (int x = 1; x < 7; x++) {
				for (int y = 1; y < 7; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_FUNGUS);
					w.getTile(x, y, 0).setFertility(0.6);
					w.setTile(x, y, 1, Tile.TileType.TYPE_STONE);
				}
			}
			for (int x = 3; x <= 4; x++) {
				for (int y = 3; y <= 4; y++) {
					w.setTile(x, y, 1, Tile.TileType.TYPE_HOLE);
				}
			}
			bakeRule(demo, w, 1, "rule_opening_upper", "the opening, baked see-through");
			bakeRule(demo, w, 0, "rule_opening_lower", "the cavern floor a viewer sees in it");
		}
		out.append(demo).append("</div>");

		// ---- runs and clearances ----
		out.append("<p class=note><b>Runs work out their own shapes.</b> Rail, "
				+ "pipe and coolant tiles take the four neighbours of their own "
				+ "kind as a mask and draw the shape it implies — a lone arm is a "
				+ "capped stub, two opposite a straight, two adjacent an elbow, "
				+ "three a tee, four a crossing. The world generator lays tiles; "
				+ "the run works out its own geometry (see "
				+ "<a href=\"#runs-lines\">Runs &amp; lines</a> for every shape)."
				+ "</p>");
		out.append("<p class=note><b>Clearance gates.</b> Two grounds admit by "
				+ "body size rather than blocking outright: a crawl duct passes "
				+ "bodies up to " + (int) Tile.DUCT_CLEARANCE
				+ " px, a shard bed up to " + (int) Tile.CRYSTAL_CLEARANCE
				+ " px — read off the tile's own constants. To anything larger "
				+ "they are walls; to anything smaller, a road the large cannot "
				+ "follow.</p>");
		return out.toString();
	}

	/** Bakes one level of a staged world and appends the rule figure. */
	private void bakeRule(StringBuilder out, World w, int z, String file, String cap) {
		w.alignTiles();
		BufferedImage img = LayerBaker.bakeLevelImage(w, LayerBaker.chunkRenderer(w), z);
		int ts = ResourceManager.tileSize;
		assets.put(file + ".png", png(img.getSubimage(2 * ts, 2 * ts, 4 * ts, 4 * ts)));
		out.append("<figure><img src=\"/tiles/").append(file)
				.append(".png\" loading=lazy><figcaption>").append(cap)
				.append("</figcaption></figure>");
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

}
