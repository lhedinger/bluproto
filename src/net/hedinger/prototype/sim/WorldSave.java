package net.hedinger.prototype.sim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.GenomeCodec;
import net.hedinger.prototype.simtest.TestNPC;

/**
 * A living world written down so a deploy can carry it across: the state the
 * simulation changed, and nothing the seed can build again.
 *
 * <p>What is saved is what has meaning to the next build as well as this one.
 * The ground is regenerated from the seed, and only what living on it changed
 * is written: each tile's grass and fertility. Every creature, alive or dead,
 * is written as its genome ({@link GenomeCodec}) and its body ({@link
 * net.hedinger.prototype.entities.NPC#bodyState}) under the id it had, with the
 * birth registry, so its line and its lineage view continue. Its mind is not:
 * registers, intents and targets are a build's internals, and a restored body
 * wakes where it stood. Items, the steward, its drones, pheromone and sound
 * come back as the seed builds them.
 *
 * <p>A save is refused, never half-applied, when it cannot mean the same thing
 * here: a different world size, a terrain the generator no longer builds, or a
 * genome encoding this build cannot read. The caller then starts fresh from
 * the seed, which is what every deploy did before saves.
 *
 * <p>A restored world is not reproducible from its seed and command log; the
 * replay guarantee covers a world started fresh, and is given up here.
 */
public final class WorldSave {

	/** Bumped when the layout of a save changes in a way an old reader would
	 *  misread. A save of another format is refused. */
	public static final int FORMAT = 1;

	private static final String MAGIC = "bluproto-world-save";

	private WorldSave() {
	}

	/** Why a save cannot be put back on this build. */
	public static final class Incompatible extends Exception {
		private static final long serialVersionUID = 1L;

		public Incompatible(String reason) {
			super(reason);
		}
	}

	/** The facts a boot decides on before it builds anything. */
	public record Header(int format, long seed, int cols, int rows, int levels, long tick,
			int spawnCount, long terrain, String codec, String epoch, long savedAt) {
	}

	// ---- writing --------------------------------------------------------------

	/**
	 * Writes {@code w}, a world built from {@code seed}, to {@code out}. The
	 * caller holds the world still (the server writes between ticks).
	 * {@code epoch} names the reset this world descends from, so a boot can
	 * tell a requested reset from a restart.
	 */
	public static void write(World w, long seed, String epoch, long savedAt, OutputStream out)
			throws IOException {
		Writer o = new OutputStreamWriter(out, StandardCharsets.UTF_8);
		StringBuilder b = new StringBuilder(256);
		line(o, b, MAGIC, FORMAT);
		line(o, b, "seed", seed);
		line(o, b, "size", w.getColums(), w.getRows(), w.getLevels());
		line(o, b, "tick", w.getTick());
		line(o, b, "spawn", w.spawnCount());
		line(o, b, "terrain", Long.toHexString(terrainHash(w)));
		line(o, b, "codec", GenomeCodec.version());
		line(o, b, "epoch", clean(epoch));
		line(o, b, "saved", savedAt);

		int tiles = w.getColums() * w.getRows() * w.getLevels();
		line(o, b, "tiles", tiles);
		for (int z = 0; z < w.getLevels(); z++) {
			for (int y = 0; y < w.getRows(); y++) {
				for (int x = 0; x < w.getColums(); x++) {
					Tile t = w.getTile(x, y, z);
					line(o, b, t.vegetationStored(), t.vegetationTick(), t.getFertility());
				}
			}
		}

		List<World.Birth> births = w.births();
		line(o, b, "births", births.size());
		for (World.Birth r : births) {
			line(o, b, r.child(), r.parentA(), r.parentB(), r.tick(), r.generation(), clean(r.species()));
		}

		List<TestNPC> bodies = new ArrayList<TestNPC>();
		for (Entity e : w.getEntities()) {
			if (e instanceof TestNPC p && !p.isRemoved() && p.getGenome() != null) {
				bodies.add(p);
			}
		}
		line(o, b, "bodies", bodies.size());
		for (TestNPC p : bodies) {
			StringBuilder kv = new StringBuilder();
			for (Map.Entry<String, Double> en : p.bodyState().entrySet()) {
				if (kv.length() > 0) {
					kv.append(',');
				}
				kv.append(en.getKey()).append('=').append(en.getValue());
			}
			String cause = p.getDeathCause();
			line(o, b, p.getID(), p.generation(), cause == null ? "-" : clean(cause), kv,
					GenomeCodec.encode(p.getGenome()));
		}
		line(o, b, "end");
		o.flush();
	}

	// ---- reading --------------------------------------------------------------

	/** Reads just the header of a save. */
	public static Header readHeader(InputStream in) throws IOException, Incompatible {
		return header(reader(in));
	}

	/**
	 * Puts the save in {@code in} back onto {@code ground}: a world built by
	 * {@link Worlds#demoUnpeopled} from the save's seed and size, not yet
	 * ticked by anyone else. Returns the same world, restored, at the saved
	 * tick, with every body admitted. Throws {@link Incompatible} -- with {@code ground} to be
	 * thrown away -- when the save cannot mean the same thing here.
	 */
	public static World restore(InputStream in, World ground) throws IOException, Incompatible {
		BufferedReader r = reader(in);
		Header h = header(r);
		if (h.cols() != ground.getColums() || h.rows() != ground.getRows()
				|| h.levels() != ground.getLevels()) {
			throw new Incompatible("the world is " + ground.getColums() + "x" + ground.getRows() + "x"
					+ ground.getLevels() + ", the save " + h.cols() + "x" + h.rows() + "x" + h.levels());
		}
		if (terrainHash(ground) != h.terrain()) {
			throw new Incompatible("the generator no longer builds the saved terrain");
		}
		if (!GenomeCodec.version().equals(h.codec())) {
			throw new Incompatible("genomes are " + GenomeCodec.version() + ", the save's " + h.codec());
		}

		int tiles = count(r, "tiles");
		if (tiles != h.cols() * h.rows() * h.levels()) {
			throw new Incompatible("the save has " + tiles + " tiles for a " + h.cols() + "x" + h.rows()
					+ "x" + h.levels() + " world");
		}
		// Whatever the empty ground grew while it was built -- the steward
		// restocks a world it finds without a herd -- is not the save's.
		ground.discard(e -> e instanceof TestNPC p && p.getGenome() != null);
		ground.resumeAt(h.tick());
		for (int z = 0; z < h.levels(); z++) {
			for (int y = 0; y < h.rows(); y++) {
				for (int x = 0; x < h.cols(); x++) {
					String[] f = fields(r);
					Tile t = ground.getTile(x, y, z);
					t.restoreVegetation(Double.parseDouble(f[0]), Long.parseLong(f[1]));
					t.setFertility(Double.parseDouble(f[2]));
				}
			}
		}

		int nb = count(r, "births");
		List<World.Birth> births = new ArrayList<World.Birth>(nb);
		for (int i = 0; i < nb; i++) {
			String[] f = fields(r);
			births.add(new World.Birth(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
					Integer.parseInt(f[2]), Long.parseLong(f[3]), Integer.parseInt(f[4]), f[5]));
		}

		int n = count(r, "bodies");
		// A saved id the ground already uses (a fixture spawned at the same spot
		// and index) gets a fresh one; the registry follows it.
		Map<Integer, Integer> moved = new HashMap<Integer, Integer>();
		for (int i = 0; i < n; i++) {
			String[] f = fields(r);
			int id = Integer.parseInt(f[0]);
			int generation = Integer.parseInt(f[1]);
			String cause = f[2].equals("-") ? null : f[2];
			Map<String, Double> body = new LinkedHashMap<String, Double>();
			for (String kv : f[3].split(",")) {
				int eq = kv.indexOf('=');
				if (eq > 0) {
					body.put(kv.substring(0, eq), Double.parseDouble(kv.substring(eq + 1)));
				}
			}
			Genome g;
			try {
				g = GenomeCodec.decode(f[4]);
			} catch (IllegalArgumentException e) {
				throw new Incompatible("body " + id + ": " + e.getMessage());
			}
			double x = body.getOrDefault("x", 0.0), y = body.getOrDefault("y", 0.0),
					z = body.getOrDefault("z", 0.0);
			TestNPC p = switch (g.clade) {
			case HERBIVORE -> TestNPC.mindedForager(x, y, z, g);
			case PREDATOR -> TestNPC.mindedPredator(x, y, z, g);
			case SCAVENGER -> TestNPC.mindedScavenger(x, y, z, g);
			case PARASITE -> TestNPC.mindedParasite(x, y, z, g);
			};
			p.withGeneration(generation);
			if (!ground.spawnEntityAs(p, id)) {
				if (!ground.spawnEntity(p)) {
					continue; // off this map: nowhere to put it
				}
				moved.put(id, p.getID());
			}
			p.restoreBody(body, cause);
		}
		if (!"end".equals(r.readLine())) {
			throw new Incompatible("the save is cut short");
		}
		if (!moved.isEmpty()) {
			List<World.Birth> renamed = new ArrayList<World.Birth>(births.size());
			for (World.Birth b : births) {
				renamed.add(new World.Birth(moved.getOrDefault(b.child(), b.child()),
						moved.getOrDefault(b.parentA(), b.parentA()),
						moved.getOrDefault(b.parentB(), b.parentB()), b.tick(), b.generation(), b.species()));
			}
			births = renamed;
		}
		ground.restoreBirths(births);
		ground.continueSpawnCount(h.spawnCount());
		ground.admitSpawns();
		return ground;
	}

	/**
	 * A fingerprint of the ground the generator builds: every tile's type,
	 * flora, tall grass, ramp and belt direction. Nothing the simulation
	 * changes is in it, so a world hashes the same at tick one and a month
	 * later -- and differently once the generator builds something else.
	 */
	public static long terrainHash(World w) {
		long h = 0xcbf29ce484222325L;
		for (int z = 0; z < w.getLevels(); z++) {
			for (int y = 0; y < w.getRows(); y++) {
				for (int x = 0; x < w.getColums(); x++) {
					Tile t = w.getTile(x, y, z);
					h = mix(h, t.getType().name().hashCode());
					h = mix(h, t.getFlora());
					h = mix(h, t.hasTallGrass() ? 1 : 0);
					h = mix(h, t.getRampUphill());
					h = mix(h, t.getBeltRun());
				}
			}
		}
		return h;
	}

	// ---- plumbing -------------------------------------------------------------

	private static long mix(long h, int v) {
		h ^= v;
		return h * 0x100000001b3L;
	}

	private static String clean(String s) {
		return s == null || s.isEmpty() ? "-" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}

	private static void line(Writer o, StringBuilder b, Object... parts) throws IOException {
		b.setLength(0);
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) {
				b.append('\t');
			}
			b.append(parts[i]);
		}
		b.append('\n');
		o.append(b);
	}

	private static BufferedReader reader(InputStream in) {
		return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
	}

	private static String[] fields(BufferedReader r) throws IOException, Incompatible {
		String s = r.readLine();
		if (s == null) {
			throw new Incompatible("the save is cut short");
		}
		return s.split("\t", -1);
	}

	private static String value(BufferedReader r, String key) throws IOException, Incompatible {
		String[] f = fields(r);
		if (!f[0].equals(key) || f.length < 2) {
			throw new Incompatible("expected '" + key + "', found '" + f[0] + "'");
		}
		return f[1];
	}

	private static int count(BufferedReader r, String key) throws IOException, Incompatible {
		return Integer.parseInt(value(r, key));
	}

	private static Header header(BufferedReader r) throws IOException, Incompatible {
		String[] m = fields(r);
		if (!m[0].equals(MAGIC)) {
			throw new Incompatible("not a world save");
		}
		int format = Integer.parseInt(m[1]);
		if (format != FORMAT) {
			throw new Incompatible("save format " + format + ", this build reads " + FORMAT);
		}
		long seed = Long.parseLong(value(r, "seed"));
		String[] size = fields(r);
		if (!size[0].equals("size")) {
			throw new Incompatible("expected 'size', found '" + size[0] + "'");
		}
		long tick = Long.parseLong(value(r, "tick"));
		int spawn = Integer.parseInt(value(r, "spawn"));
		long terrain = Long.parseUnsignedLong(value(r, "terrain"), 16);
		String codec = value(r, "codec");
		String epoch = value(r, "epoch");
		long saved = Long.parseLong(value(r, "saved"));
		return new Header(format, seed, Integer.parseInt(size[1]), Integer.parseInt(size[2]),
				Integer.parseInt(size[3]), tick, spawn, terrain, codec, epoch, saved);
	}
}
