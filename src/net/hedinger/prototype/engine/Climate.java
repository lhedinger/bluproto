package net.hedinger.prototype.engine;

import net.hedinger.prototype.engine.Tile.TileType;

/**
 * Three slow fields over every tile -- light, temperature and humidity -- read
 * off the finished ground and nothing else.
 *
 * <p>Each is a raster per level, the way the census holds the pheromone field,
 * and each is a pure function of the tiles: which are open to the sky, where the
 * water is, where the rock is, how deep a floor lies. No second opinion is
 * introduced beside the terrain -- no noise layer, no band across the map --
 * because the sky taught that lesson: two descriptions of one landscape
 * disagree somewhere, and every disagreement needs a tile to paper over. A
 * field derived from the tiles cannot disagree with them.
 *
 * <p>Nothing in the world moves yet that would move these -- there is no day
 * and no season -- so they are computed once a terrain is built and again only
 * when a tile is written ({@link World#climate}). The cadence a clock would
 * need is a refresh of the same computation, not a different one. And nothing
 * reads them yet but the viewer and the scenario suite: what they will do to a
 * body -- heat on the thirst clock, cold on the hunger clock, dark on a
 * forager's intake -- is a multiplier at the seam VITALS.md 8 names, and lands
 * after a body can sense them, because a pressure a creature cannot perceive
 * only kills it at random.
 *
 * <p>Deterministic throughout: fixed raster order, no RNG. Replay is untouched.
 */
public final class Climate {

	// --- light ---------------------------------------------------------------
	/** What open ground under the sky reads. Light is a fraction of this. */
	@Unit("of full sun")
	public static final double LIGHT_SKY = 1.0;
	/** What survives one step of open air underground: light enters through a
	 *  hole and fades along the passage. 0.8 to the tile is about a third left
	 *  five tiles in and a twentieth at fifteen. */
	@Unit("per tile")
	public static final double LIGHT_FALLOFF = 0.8;
	/** The extra toll for stepping into cover -- thicket, reeds, tall grass --
	 *  which hides a body and dims the light without stopping it. */
	@Unit("per cover tile")
	public static final double LIGHT_THROUGH_COVER = 0.5;
	/** A lit grating is lit: the facility's own small source. */
	@Unit("of full sun")
	public static final double LIGHT_GRATE = 0.4;

	// --- temperature -----------------------------------------------------------
	/** The ground, everywhere on it. A Mediterranean annual mean, and with no
	 *  season and no sun it IS the ground's temperature. No band across the map:
	 *  at this size the whole world sits in one latitude. */
	@Unit("degrees C")
	public static final double TEMP_SURFACE = 17.0;
	/** Per storey above the ground. A mesa top is a little cooler. */
	@Unit("degrees C per level")
	public static final double TEMP_LAPSE = -2.0;
	/** Below the ground the rock holds the region's mean, which is why a cave is
	 *  the one place that is neither hot nor cold. */
	@Unit("degrees C")
	public static final double TEMP_UNDERGROUND = 16.0;
	/** Per storey below the cave: deeper is a touch warmer. */
	@Unit("degrees C per level")
	public static final double TEMP_DEPTH = 1.0;
	/** A geothermal vent, at its mouth. */
	@Unit("degrees C")
	public static final double VENT_HEAT = 25.0;
	/** The facility's heat exchanger -- "where the heat goes" -- at the grille. */
	@Unit("degrees C")
	public static final double EXCHANGER_HEAT = 12.0;
	/** The coolant run, the plant's cold side, at the pipe. */
	@Unit("degrees C")
	public static final double COOLANT_CHILL = 10.0;
	/** How much of an anomaly carries to the next tile of air. Cover does not
	 *  slow it: warmth moves through a thicket as freely as through a passage. */
	@Unit("per tile")
	public static final double TEMP_FALLOFF = 0.75;

	// --- humidity --------------------------------------------------------------
	/** Dry ground far from water, on the surface: a Mediterranean summer's air. */
	@Unit("relative")
	public static final double HUMID_SURFACE = 0.35;
	/** The same, in a cave: sealed rock holds its moisture. */
	@Unit("relative")
	public static final double HUMID_CAVE = 0.8;
	/** Per storey below the cave. */
	@Unit("relative per level")
	public static final double HUMID_DEPTH = 0.05;
	/** The air over open water, and the fringes that stand in it. */
	@Unit("relative")
	public static final double HUMID_WATER = 1.0, HUMID_SHALLOWS = 0.9, HUMID_REEDS = 0.85,
			HUMID_MUD = 0.75, HUMID_QUICKSAND = 0.7;
	/** How much of a water source's air carries to the next tile. Cover does
	 *  not slow it either. */
	@Unit("per tile")
	public static final double HUMID_FALLOFF = 0.88;
	/** What full sun takes off the air: shade is moister than open ground at the
	 *  same distance from water, which is the first place the three fields
	 *  visibly meet. */
	@Unit("of humidity at full sun")
	public static final double HUMID_SUN_DRY = 0.3;
	/** What each degree above the ground's own temperature takes off the air --
	 *  and each degree below gives back, since cooling air carries less and
	 *  so reads more full. A vent at +25 halves the humidity at its mouth. */
	@Unit("of humidity per degree C")
	public static final double HUMID_HEAT_DRY = 0.02;

	private final int cols, rows, lvls;
	private final double[][] light, temp, humid;

	/** Builds all three fields for every level of {@code w}, from its tiles as
	 *  they stand. Light first, since the other two read it: the fields are
	 *  ordered by what they depend on. */
	public Climate(World w) {
		cols = w.getColums();
		rows = w.getRows();
		lvls = w.getLevels();
		light = new double[lvls][];
		temp = new double[lvls][];
		humid = new double[lvls][];
		for (int z = lvls - 1; z >= 0; z--) {
			light[z] = lightOf(w, z); // top down: light falls, so a floor reads the one above it
		}
		for (int z = 0; z < lvls; z++) {
			temp[z] = temperatureOf(w, z);
		}
		for (int z = 0; z < lvls; z++) {
			humid[z] = humidityOf(w, z);
		}
	}

	/** Light on the tile holding {@code (x, y)} of level {@code z}, 0..1; 0 off the map. */
	public double lightAt(double x, double y, int z) {
		return at(light, x, y, z, 0);
	}

	/** Temperature on the tile holding {@code (x, y)} of level {@code z}, in
	 *  degrees C; the ground's off the map. */
	public double temperatureAt(double x, double y, int z) {
		return at(temp, x, y, z, TEMP_SURFACE);
	}

	/** Humidity on the tile holding {@code (x, y)} of level {@code z}, 0..1;
	 *  the dry ground's off the map. */
	public double humidityAt(double x, double y, int z) {
		return at(humid, x, y, z, HUMID_SURFACE);
	}

	/** The light raster of level {@code z}, row-major {@code y * cols + x}. The
	 *  array itself, for a reader that quantises the whole of it: not to be
	 *  written. */
	public double[] light(int z) {
		return light[z];
	}

	/** The temperature raster of level {@code z}, as {@link #light(int)}. */
	public double[] temperature(int z) {
		return temp[z];
	}

	/** The humidity raster of level {@code z}, as {@link #light(int)}. */
	public double[] humidity(int z) {
		return humid[z];
	}

	private double at(double[][] f, double x, double y, int z, double off) {
		int tx = (int) Math.floor(x), ty = (int) Math.floor(y);
		if (z < 0 || z >= lvls || tx < 0 || ty < 0 || tx >= cols || ty >= rows) {
			return off;
		}
		return f[z][ty * cols + tx];
	}

	// --- the three computations -------------------------------------------------

	/**
	 * Light enters a level from above -- through the sky if this is the top,
	 * through a drop (a hole, a shaft, open air) in the floor above otherwise,
	 * and at the foot of a ramp down from a lit floor -- and from a lit grating,
	 * and then spreads along the open tiles, fading a step at a time and losing
	 * more to cover. Rock takes none and passes none.
	 */
	private double[] lightOf(World w, int z) {
		double[] src = new double[cols * rows];
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				Tile t = w.getTile(x, y, z);
				if (t.isSolid()) {
					continue;
				}
				int i = y * cols + x;
				double s = 0;
				if (z == lvls - 1) {
					s = LIGHT_SKY; // the top floor is under the sky
				} else if (w.getTile(x, y, z + 1).isDrop()) {
					s = light[z + 1][i]; // light falls through a hole onto the floor below
				}
				if (t.getType() == TileType.TYPE_LIGHTGRATE) {
					s = Math.max(s, LIGHT_GRATE);
				}
				if (t.getType() == TileType.TYPE_RAMPUP && z + 1 < lvls) {
					// A ramp up: its own tile is the mouth, and it opens onto the
					// floor above at the end of its slope.
					int e = t.rampExit();
					int nx = x + Tile.dirDx(e), ny = y + Tile.dirDy(e);
					if (w.isValid(nx, ny, z + 1)) {
						s = Math.max(s, light[z + 1][ny * cols + nx]);
					}
				}
				src[i] = s;
			}
		}
		if (z + 1 < lvls) {
			// And the foot of every ramp DOWN from the floor above is a mouth too.
			for (int y = 0; y < rows; y++) {
				for (int x = 0; x < cols; x++) {
					Tile up = w.getTile(x, y, z + 1);
					if (up.getType() != TileType.TYPE_RAMPDOWN) {
						continue;
					}
					int e = up.rampExit();
					int nx = x + Tile.dirDx(e), ny = y + Tile.dirDy(e);
					if (w.isValid(nx, ny, z) && !w.getTile(nx, ny, z).isSolid()) {
						int j = ny * cols + nx;
						src[j] = Math.max(src[j], light[z + 1][y * cols + x]);
					}
				}
			}
		}
		double[] step = new double[cols * rows];
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				Tile t = w.getTile(x, y, z);
				step[y * cols + x] = t.isSolid() ? 0
						: t.blocksSight() ? LIGHT_FALLOFF * LIGHT_THROUGH_COVER : LIGHT_FALLOFF;
			}
		}
		spread(src, step);
		return src;
	}

	/**
	 * The ground's temperature by where the floor sits -- above it, on it, or
	 * below it -- plus what a vent or the facility's plant adds or takes away
	 * nearby. Heat and cold each spread as the strongest source reaching the
	 * tile, so two vents side by side are a vent, not a furnace.
	 */
	private double[] temperatureOf(World w, int z) {
		double base = baseTemperature(w, z);
		double[] hot = new double[cols * rows], cold = new double[cols * rows], step = new double[cols * rows];
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				Tile t = w.getTile(x, y, z);
				int i = y * cols + x;
				step[i] = t.isSolid() ? 0 : TEMP_FALLOFF;
				switch (t.getType()) {
				case TYPE_VENT -> hot[i] = VENT_HEAT;
				case TYPE_EXCHANGER -> hot[i] = EXCHANGER_HEAT;
				case TYPE_COOLANT -> cold[i] = COOLANT_CHILL;
				default -> { }
				}
			}
		}
		spread(hot, step);
		spread(cold, step);
		double[] out = new double[cols * rows];
		for (int i = 0; i < out.length; i++) {
			out[i] = base + hot[i] - cold[i];
		}
		return out;
	}

	/** The temperature a floor has before any anomaly: lapsing upward from the
	 *  ground, and held at the region's mean below it, warming a touch with depth. */
	public static double baseTemperature(World w, int z) {
		int d = w.depthOf(z);
		return d >= 0 ? TEMP_SURFACE + TEMP_LAPSE * d : TEMP_UNDERGROUND + TEMP_DEPTH * (-d - 1);
	}

	/** The humidity a floor has far from any water: dry on the surface, and the
	 *  sealed rock's own below it. */
	public static double baseHumidity(World w, int z) {
		int d = w.depthOf(z);
		return d >= 0 ? HUMID_SURFACE : Math.min(1.0, HUMID_CAVE + HUMID_DEPTH * (-d - 1));
	}

	/**
	 * Water wets the air around it, fading with distance over a floor's own dry
	 * baseline; then the sun and the heat take their share off every tile that
	 * is not itself water. Reads the light and the temperature already computed
	 * for this floor, which is why it is computed last.
	 */
	private double[] humidityOf(World w, int z) {
		double base = baseHumidity(w, z);
		double[] src = new double[cols * rows], step = new double[cols * rows];
		boolean[] water = new boolean[cols * rows];
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				Tile t = w.getTile(x, y, z);
				int i = y * cols + x;
				step[i] = t.isSolid() ? 0 : HUMID_FALLOFF;
				double s = switch (t.getType()) {
				case TYPE_WATER -> HUMID_WATER;
				case TYPE_SHALLOWS -> HUMID_SHALLOWS;
				case TYPE_REEDS -> HUMID_REEDS;
				case TYPE_MUD -> HUMID_MUD;
				case TYPE_QUICKSAND -> HUMID_QUICKSAND;
				default -> 0;
				};
				src[i] = s;
				water[i] = s > 0;
			}
		}
		spread(src, step);
		double[] out = new double[cols * rows];
		for (int i = 0; i < out.length; i++) {
			if (water[i]) {
				out[i] = src[i]; // the pond is as wet as the pond
				continue;
			}
			double h = Math.max(base, src[i]);
			h *= 1.0 - HUMID_SUN_DRY * light[z][i];
			h *= Math.max(0, 1.0 - HUMID_HEAT_DRY * (temp[z][i] - TEMP_SURFACE));
			out[i] = Math.max(0, Math.min(1, h));
		}
		return out;
	}

	/**
	 * Spreads a field from its sources: every tile takes the most any of its four
	 * neighbours offers it through {@code step} -- what is left of a value after
	 * entering this tile, 0 where nothing enters. Alternating forward and
	 * backward raster sweeps until nothing moves, which converges in about as
	 * many passes as the longest bend in the longest reach; capped at the map's
	 * perimeter so a pathological map ends. Fixed order, so the result is the
	 * same every time.
	 */
	private void spread(double[] f, double[] step) {
		int n = cols * rows;
		for (int pass = 0; pass < cols + rows; pass++) {
			boolean moved = false;
			for (int i = 0; i < n; i++) {
				moved |= relax(f, step, i);
			}
			for (int i = n - 1; i >= 0; i--) {
				moved |= relax(f, step, i);
			}
			if (!moved) {
				return;
			}
		}
	}

	private boolean relax(double[] f, double[] step, int i) {
		double s = step[i];
		if (s <= 0) {
			return false;
		}
		int x = i % cols, y = i / cols;
		double best = f[i];
		if (x > 0) {
			best = Math.max(best, f[i - 1] * s);
		}
		if (x + 1 < cols) {
			best = Math.max(best, f[i + 1] * s);
		}
		if (y > 0) {
			best = Math.max(best, f[i - cols] * s);
		}
		if (y + 1 < rows) {
			best = Math.max(best, f[i + cols] * s);
		}
		if (best > f[i]) {
			f[i] = best;
			return true;
		}
		return false;
	}
}
