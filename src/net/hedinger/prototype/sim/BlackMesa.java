package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.World;

/**
 * A research campus buried in a desert mesa — an homage to a certain New
 * Mexico facility, drawn from what is publicly documented about its shape:
 * lettered sectors strung on a transit loop, an anomalous-materials lab whose
 * test chamber bores vertically through three floors, an office complex, a
 * decommissioned missile silo, residue processing, and a reactor complex at
 * the far end of the line.
 *
 * <p>This is an authored world, not a generated one: the terrain noise only
 * dresses the desert. Nothing lives here — no creatures, no items, no steward
 * — so the map can be judged purely as a place. The geometry is original;
 * accuracy here means hitting the canonical beats and their relationships
 * (the tram connects everything; the chamber is deeper than the labs; the
 * silo runs from surface doors to an engine bay), not copying anyone's floor
 * plan tile for tile.
 *
 * <p>Four levels, top-down:
 * <pre>
 *   z=3  SURFACE   desert with arroyo, oasis, quicksand and hardpan;
 *                  topside compound, silo doors, the launch pad's rocket
 *                  tip, tram — and the sinkhole
 *   z=2  LABS      Sector C labs + test chamber top, office complex, the
 *                  records maze, dorm station, Sector E specimen labs
 *   z=1  WORKS     the transit loop and the caves it tunnels through, silo
 *                  mid, the old freight line, residue processing, warehouse
 *                  + cold storage, reactor complex entry
 *   z=0  DEEP      the OLD LABS — a derelict complex the facility grew out
 *                  of — plus the chamber floor, engine bay, flame trench,
 *                  ice cellar, waste sump, reactor core + teleport chamber
 *
 * <p>Every subterranean floor is riddled with natural cave systems, carved
 * before the buildings so the facility reads as built INTO living rock. On
 * top of that, three big cavities span multiple floors: where a cave is
 * bigger than a room, its ceiling is a correspondingly big opening on the
 * floor above — the size of the hole tells you the size of what is under
 * it. A cavity carves after the buildings, because a cave-in does not ask.
 *
 * <p>A labyrinth this dense cannot be hand-guaranteed whole, so it is not:
 * after everything is carved, a driller pass floods the world from the open
 * desert and bores a straight service drift to every walkable pocket the
 * flood cannot reach, repeating until the campus is one connected space.
 * The scenario asserts that as an equality, not a hope.
 * </pre>
 *
 * <p>Determinism: {@link World}'s constructor draws RNG, so the seed is set
 * first and every tile on every level is then overwritten; everything after
 * construction is a pure function of coordinates ({@link Utils#noise2} draws
 * no RNG). Two builds from the same seed are tile-identical.
 */
public final class BlackMesa {

	private BlackMesa() {
	}

	public static final int COLS = 176, ROWS = 112, LVLS = 4;
	public static final int DEEP = 0, WORKS = 1, LABS = 2, SURFACE = 3;

	/** The test chamber's bore: one column of coordinates shared by three
	 *  floors, which is the point of it. */
	static final double CHAMBER_X = 100.0, CHAMBER_Y = 52.0;

	/** The blast-pit silo's centre: the same round bore on every floor, down
	 *  to the sludge pool at its base. */
	static final double SILO_X = 122.5, SILO_Y = 74.5;

	public static World build(long seed) {
		Utils.seed(seed);
		World w = new World(COLS, ROWS, LVLS);

		// Underground floors start as solid rock; the surface as desert.
		for (int z = DEEP; z <= LABS; z++) {
			fill(w, z, 0, 0, COLS - 1, ROWS - 1, Tile.TileType.TYPE_WALL);
		}
		surface(w);

		caves(w);      // the rock was never solid: winding systems, every floor
		labsFloor(w);  // the buildings win where they stand...
		worksFloor(w);
		deepFloor(w);
		cavities(w);   // ...and the cave-ins win over the buildings
		gorge(w);
		stairs(w);
		dressing(w);   // the details that say people worked here, and left
		intricacy(w);  // the second pass: the details the first pass exposed
		furniture(w);  // doors, their switches, and the warehouse's crates
		connectAll(w); // drill drifts until the labyrinth is one space

		shallowsFringe(w);
		w.think(); // one tick flushes the spawn queue: the doors, switches
		           // and crates are in the world, not waiting at its edge
		return w;
	}

	/**
	 * The cave systems: banded noise carves winding passages through every
	 * subterranean floor, each floor offset so no two levels share a layout.
	 * Cave floors are bare stone with fungus beds where a second noise says
	 * life took hold — fertile and regrowing, so the caves glow — and rubble
	 * where the ceiling has been coming down for a while.
	 */
	private static void caves(World w) {
		for (int z = DEEP; z <= LABS; z++) {
			for (int x = 3; x < COLS - 3; x++) {
				for (int y = 3; y < ROWS - 3; y++) {
					double n = Utils.noise2(x + z * 997, y + z * 571, 0.085);
					if (n <= 0.60 || n >= 0.75) {
						continue; // solid rock stays solid
					}
					double life = Utils.noise2(x + z * 313 + 50, y + z * 127 + 80, 0.2);
					if (Utils.noise2(x + z * 77 + 900, y + z * 41 + 300, 0.7) > 0.975) {
						set(w, x, y, z, Tile.TileType.TYPE_STALAGMITE);
					} else if (life > 0.82) {
						set(w, x, y, z, Tile.TileType.TYPE_FUNGUS);
						w.getTile(x, y, z).setFertility(0.5);
						w.getTile(x, y, z).setRegrowRate(0.002);
					} else if (life < 0.12) {
						set(w, x, y, z, Tile.TileType.TYPE_RUBBLE);
					} else {
						set(w, x, y, z, Tile.TileType.TYPE_STONE);
					}
				}
			}
		}
	}

	/**
	 * The three big cavities — caves too big for a floor to contain. Each
	 * spans several levels: its bottom floor is the cavern, and every floor
	 * of rock that used to be its ceiling is an opening whose SIZE matches
	 * the void below, so a big hole read from above means a big cave under
	 * it. Carved after the buildings: a cave-in does not ask what it takes.
	 */
	private static void cavities(World w) {
		// The grand cavern: three floors of void, a pool at the bottom. Its
		// collapse bit the old labs' east rooms and nicked Sector C's shell.
		diskAt(w, LABS, 86.0, 30.0, 6.0, Tile.TileType.TYPE_HOLE);
		diskAt(w, WORKS, 86.0, 30.0, 5.0, Tile.TileType.TYPE_HOLE);
		diskAt(w, DEEP, 86.0, 30.0, 8.0, Tile.TileType.TYPE_STONE);
		diskAt(w, DEEP, 86.0, 30.0, 5.0, Tile.TileType.TYPE_FUNGUS);
		for (int x = 79; x <= 93; x++) {
			for (int y = 23; y <= 37; y++) {
				if (w.getTile(x, y, DEEP).getType() == Tile.TileType.TYPE_FUNGUS) {
					w.getTile(x, y, DEEP).setFertility(0.5);
					w.getTile(x, y, DEEP).setRegrowRate(0.002);
				}
			}
		}
		diskAt(w, DEEP, 86.0, 30.0, 2.5, Tile.TileType.TYPE_WATER);
		for (int[] sp : new int[][] { { 81, 25 }, { 91, 26 }, { 80, 34 },
				{ 90, 35 }, { 86, 23 } }) {
			set(w, sp[0], sp[1], DEEP, Tile.TileType.TYPE_STALAGMITE);
		}

		// The sinkhole: the desert floor gave way over a labs-level cave.
		diskAt(w, SURFACE, 30.0, 60.0, 4.5, Tile.TileType.TYPE_HOLE);
		diskAt(w, LABS, 30.0, 60.0, 6.0, Tile.TileType.TYPE_STONE);
		diskAt(w, LABS, 30.0, 60.0, 3.5, Tile.TileType.TYPE_FUNGUS);
		for (int x = 24; x <= 36; x++) {
			for (int y = 54; y <= 66; y++) {
				if (w.getTile(x, y, LABS).getType() == Tile.TileType.TYPE_FUNGUS) {
					w.getTile(x, y, LABS).setFertility(0.5);
					w.getTile(x, y, LABS).setRegrowRate(0.002);
				}
			}
		}

		// The undercroft: a works-floor opening over a deep cavern that the
		// waste sump has been quietly draining into.
		diskAt(w, WORKS, 60.0, 88.0, 4.0, Tile.TileType.TYPE_HOLE);
		diskAt(w, DEEP, 60.0, 88.0, 6.0, Tile.TileType.TYPE_STONE);
		diskAt(w, DEEP, 60.0, 88.0, 2.5, Tile.TileType.TYPE_SLUDGE);
		set(w, 57, 85, DEEP, Tile.TileType.TYPE_STALAGMITE);
		set(w, 63, 91, DEEP, Tile.TileType.TYPE_STALAGMITE);
	}

	// ---- z=3: the surface --------------------------------------------------

	private static void surface(World w) {
		// Desert under everything: sand with rocky stretches, rubble washes,
		// mesa outcrops, and the odd stand of scrub. The rim is canyon wall.
		for (int x = 0; x < COLS; x++) {
			for (int y = 0; y < ROWS; y++) {
				boolean rim = x < 2 || y < 2 || x >= COLS - 2 || y >= ROWS - 2;
				double elev = Utils.noise2(x, y, 0.05);
				double detail = Utils.noise2(x + 700, y + 200, 0.13);
				Tile.TileType t;
				if (rim || elev > 0.88) {
					t = Tile.TileType.TYPE_MESA; // rim + buttes: red desert rock
				} else if (elev > 0.82) {
					t = Tile.TileType.TYPE_ROCKY;
				} else if (detail < 0.26) {
					t = Tile.TileType.TYPE_RUBBLE;
				} else if (detail >= 0.26 && detail < 0.272) {
					t = Tile.TileType.TYPE_BONES; // something died out here
				} else if (detail > 0.955) {
					t = Tile.TileType.TYPE_CACTUS;
				} else if (detail > 0.93) {
					t = Tile.TileType.TYPE_COVER; // desert scrub
				} else {
					t = Tile.TileType.TYPE_SAND;
				}
				set(w, x, y, SURFACE, t);
			}
		}

		desertVariety(w);
		topsideCompound(w);
		dormitories(w);
		sectorCHead(w);
		siloDoors(w);
		launchDoors(w);
		topsideTram(w);
	}

	/**
	 * The desert stops being one texture. Four features, all natural, all
	 * placed clear of the compounds so the buildings still win their ground:
	 *
	 * <ul>
	 *   <li><b>The arroyo</b> — a dry creek wandering down the east side
	 *       where a river once ran: a mud bed between rocky banks, with
	 *       remnant waterholes ringed in reeds where the water table still
	 *       shows.</li>
	 *   <li><b>The oasis</b> — one pond in the south-west with a reed collar
	 *       and a ring of green, the only fertile ground on the surface.</li>
	 *   <li><b>The quicksand basin</b> — a low stretch of treacherous ground
	 *       east of the sector head, quicksand pooled in mud.</li>
	 *   <li><b>The hardpan</b> — pale weathered rock showing through the
	 *       sand on the west flats.</li>
	 * </ul>
	 */
	private static void desertVariety(World w) {
		// The arroyo.
		for (int y = 3; y < ROWS - 3; y++) {
			int xc = 152 + (int) Math.round((Utils.noise2(0, y, 0.045) - 0.5) * 24);
			xc = Math.max(8, Math.min(COLS - 9, xc));
			boolean pool = Utils.noise2(7, y, 0.15) > 0.8;
			for (int dx = -1; dx <= 1; dx++) {
				set(w, xc + dx, y, SURFACE, pool
						? Tile.TileType.TYPE_SHALLOWS : Tile.TileType.TYPE_MUD);
			}
			for (int dx : new int[] { -2, 2 }) {
				if (pool) {
					set(w, xc + dx, y, SURFACE, Tile.TileType.TYPE_REEDS);
				} else if (w.getTile(xc + dx, y, SURFACE).getType() == Tile.TileType.TYPE_SAND) {
					set(w, xc + dx, y, SURFACE, Tile.TileType.TYPE_ROCKY);
				}
			}
		}

		// The oasis.
		for (int x = 46; x <= 58; x++) {
			for (int y = 86; y <= 98; y++) {
				double d = Math.hypot(x + 0.5 - 52.5, y + 0.5 - 92.5);
				if (d > 5.0) {
					continue;
				}
				if (d <= 2.5) {
					set(w, x, y, SURFACE, Tile.TileType.TYPE_WATER);
				} else if (d <= 3.5) {
					set(w, x, y, SURFACE, Tile.TileType.TYPE_REEDS);
				} else {
					w.setTile(x, y, SURFACE, Tile.TileType.TYPE_FLOOR);
					w.getTile(x, y, SURFACE).setFertility(0.45);
				}
			}
		}

		// The quicksand basin.
		for (int x = 96; x <= 110; x++) {
			for (int y = 34; y <= 50; y++) {
				double n = Utils.noise2(x + 333, y + 888, 0.12);
				if (n > 0.72) {
					set(w, x, y, SURFACE, Tile.TileType.TYPE_QUICKSAND);
				} else if (n > 0.6) {
					set(w, x, y, SURFACE, Tile.TileType.TYPE_MUD);
				}
			}
		}

		// The hardpan.
		for (int x = 8; x <= 26; x++) {
			for (int y = 36; y <= 70; y++) {
				if (Utils.noise2(x + 555, y + 222, 0.1) > 0.66
						&& w.getTile(x, y, SURFACE).getType() == Tile.TileType.TYPE_SAND) {
					set(w, x, y, SURFACE, Tile.TileType.TYPE_STONE);
				}
			}
		}
	}

	/** The topside motorpool: a fenced yard of barracks, garage, armory and a
	 *  helipad — the surface compound everything else is signed from. */
	private static void topsideCompound(World w) {
		shell(w, SURFACE, 18, 12, 48, 34,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PAVED);
		fill(w, SURFACE, 32, 34, 34, 34, Tile.TileType.TYPE_PAVED); // the gate
		// Barracks, with its rank of bunks.
		shell(w, SURFACE, 20, 14, 30, 20,
				Tile.TileType.TYPE_WALL_BUILT, Tile.TileType.TYPE_PLATE);
		set(w, 25, 20, SURFACE, Tile.TileType.TYPE_PLATE);
		for (int x : new int[] { 22, 24, 26, 28 }) {
			set(w, x, 16, SURFACE, Tile.TileType.TYPE_BUNK);
		}
		// Garage.
		shell(w, SURFACE, 36, 14, 46, 20,
				Tile.TileType.TYPE_WALL_BUILT, Tile.TileType.TYPE_TREADPLATE);
		set(w, 41, 20, SURFACE, Tile.TileType.TYPE_TREADPLATE);
		// Armory: the one steel box on the surface.
		shell(w, SURFACE, 20, 26, 25, 31,
				Tile.TileType.TYPE_WALL_STEEL, Tile.TileType.TYPE_PLATE);
		set(w, 25, 28, SURFACE, Tile.TileType.TYPE_PLATE);
		// Helipad, ringed in its painted border.
		fill(w, SURFACE, 36, 26, 42, 32, Tile.TileType.TYPE_HAZARD);
		fill(w, SURFACE, 37, 27, 41, 31, Tile.TileType.TYPE_TREADPLATE);

		// The paved road from the gate to the sector head.
		fill(w, SURFACE, 33, 35, 33, 52, Tile.TileType.TYPE_PAVED);
		fill(w, SURFACE, 33, 52, 66, 52, Tile.TileType.TYPE_PAVED);
		fill(w, SURFACE, 66, 49, 66, 52, Tile.TileType.TYPE_PAVED);
	}

	/** Dormitories with their court and the dish array. */
	private static void dormitories(World w) {
		shell(w, SURFACE, 58, 10, 64, 15,
				Tile.TileType.TYPE_WALL_BUILT, Tile.TileType.TYPE_PLATE);
		set(w, 61, 15, SURFACE, Tile.TileType.TYPE_PLATE);
		shell(w, SURFACE, 58, 17, 64, 22,
				Tile.TileType.TYPE_WALL_BUILT, Tile.TileType.TYPE_PLATE);
		set(w, 61, 22, SURFACE, Tile.TileType.TYPE_PLATE);
		for (int[] b : new int[][] { { 60, 12 }, { 62, 12 }, { 60, 19 }, { 62, 19 } }) {
			set(w, b[0], b[1], SURFACE, Tile.TileType.TYPE_BUNK);
		}
		fill(w, SURFACE, 67, 12, 72, 17, Tile.TileType.TYPE_PAVED); // the court
		// The dish: server-rack tiles standing in for the antenna framework —
		// solid to a body, open to the eye, which is what a dish is.
		fill(w, SURFACE, 71, 7, 75, 11, Tile.TileType.TYPE_PLATE);
		fill(w, SURFACE, 72, 8, 74, 10, Tile.TileType.TYPE_SERVER);
	}

	/** The little concrete block that is all the labs show the sky: an
	 *  elevator head over the Sector C lobby. */
	private static void sectorCHead(World w) {
		shell(w, SURFACE, 62, 42, 70, 49,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		set(w, 66, 49, SURFACE, Tile.TileType.TYPE_PLATE); // doorway
	}

	/** The silo doors: a plate apron with the bore falling away in its middle
	 *  — straight down through every floor to the engine bay. */
	private static void siloDoors(World w) {
		fill(w, SURFACE, 117, 69, 128, 80, Tile.TileType.TYPE_PLATE);
		ringAt(w, SURFACE, SILO_X, SILO_Y, 3.5, 4.5, Tile.TileType.TYPE_HAZARD);
		diskAt(w, SURFACE, SILO_X, SILO_Y, 3.5, Tile.TileType.TYPE_SHAFT);
	}

	/** The launch pad from above: an apron around the flame-trench gap, and
	 *  the rocket's tip standing in the middle of it. The rocket is a steel
	 *  column through all four floors — the one thing on the map that is
	 *  taller than the mesa. */
	private static void launchDoors(World w) {
		fill(w, SURFACE, 113, 83, 126, 93, Tile.TileType.TYPE_PLATE);
		for (int x = 115; x <= 122; x++) {
			for (int y = 84; y <= 91; y++) {
				boolean rim = x == 115 || x == 122 || y == 84 || y == 91;
				if (rim) {
					set(w, x, y, SURFACE, Tile.TileType.TYPE_HAZARD);
				}
			}
		}
		launchThroat(w, SURFACE);
	}

	/** The topside stretch of the tram: out of one portal, across the desert,
	 *  into the other. The loop itself runs two floors down; the portals hold
	 *  the stairs that join them. */
	private static void topsideTram(World w) {
		// Portal A, by the motorpool.
		shell(w, SURFACE, 47, 21, 53, 27,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		// Portal B, out east by the silo, backing onto the loop's east run.
		shell(w, SURFACE, 137, 63, 143, 69,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		// The rail between them, through the portal walls — and through two
		// stretches of cut rock, because the inbound ride alternates built
		// halls with raw canyon the whole way down.
		fill(w, SURFACE, 51, 24, 140, 24, Tile.TileType.TYPE_RAIL);
		fill(w, SURFACE, 140, 24, 140, 64, Tile.TileType.TYPE_RAIL);
		fill(w, SURFACE, 86, 23, 110, 23, Tile.TileType.TYPE_MESA);
		fill(w, SURFACE, 86, 25, 110, 25, Tile.TileType.TYPE_MESA);
		fill(w, SURFACE, 139, 30, 139, 50, Tile.TileType.TYPE_MESA);
		fill(w, SURFACE, 141, 30, 141, 50, Tile.TileType.TYPE_MESA);
	}

	/**
	 * The gorge: a canyon torn east-west across the south desert, crossed by
	 * one catwalk bridge. The opening is real depth, not paint — the drop
	 * falls to a dry wash carved on the labs floor, which the pit rendering
	 * shows through the gap from above, and a body that goes over the edge
	 * lands down there and walks out through the office complex's east door.
	 * Carved after every floor has built, because it cuts whatever it finds.
	 */
	private static void gorge(World w) {
		fill(w, SURFACE, 54, 72, 114, 75, Tile.TileType.TYPE_SHAFT);
		fill(w, SURFACE, 82, 72, 83, 75, Tile.TileType.TYPE_CATWALK); // the bridge
		// The wash below, and the way out of it.
		for (int x = 54; x <= 114; x++) {
			for (int y = 72; y <= 75; y++) {
				set(w, x, y, LABS, (x + y) % 5 == 0
						? Tile.TileType.TYPE_RUBBLE : Tile.TileType.TYPE_STONE);
			}
		}
		fill(w, LABS, 49, 73, 53, 73, Tile.TileType.TYPE_PLATE);
	}

	// ---- z=2: the labs floor ----------------------------------------------

	private static void labsFloor(World w) {
		sectorC(w);
		officeComplex(w);
		siloBore(w, LABS);
		launchThroat(w, LABS); // the rocket passes this floor in rock

		// The dormitory station: the commute the whole facility is built
		// around starts here — down from the dorm court, onto the loop.
		shell(w, LABS, 60, 14, 70, 22,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);

		// Station rooms under the two portals.
		shell(w, LABS, 44, 20, 54, 28,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		shell(w, LABS, 136, 60, 144, 70,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);

		// Sector E carves last: its access corridor breaches the east
		// station's wall, and a shell stamped after the breach walls it back
		// up — the same lesson the stairs already paid for.
		sectorE(w);
		archive(w);
	}

	/**
	 * The records archive: the labyrinth wing proper. A grid of narrow
	 * stacks with the door gaps offset so no straight line crosses it — you
	 * thread it or you do not — and dead server rows for the records nobody
	 * will read again. Its corridor breaches Sector C's shell into the lobby.
	 */
	private static void archive(World w) {
		shell(w, LABS, 26, 40, 46, 52,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PAVED);
		// Stack walls, doors alternating high and low.
		for (int i = 0; i < 4; i++) {
			int x = 30 + i * 4;
			fill(w, LABS, x, 41, x, 51, Tile.TileType.TYPE_WALL_BUILT);
			set(w, x, (i % 2 == 0) ? 43 : 49, LABS, Tile.TileType.TYPE_PAVED);
		}
		// A cross wall with its own offset gaps.
		fill(w, LABS, 27, 46, 45, 46, Tile.TileType.TYPE_WALL_BUILT);
		for (int x : new int[] { 28, 36, 44 }) {
			set(w, x, 46, LABS, Tile.TileType.TYPE_PAVED);
		}
		// Dead records.
		set(w, 28, 42, LABS, Tile.TileType.TYPE_SERVER);
		set(w, 33, 50, LABS, Tile.TileType.TYPE_SERVER);
		set(w, 41, 42, LABS, Tile.TileType.TYPE_SERVER);
		// The corridor east into the lobby.
		fill(w, LABS, 46, 48, 53, 48, Tile.TileType.TYPE_PAVED);
	}

	/**
	 * Sector C: the anomalous-materials labs. A lobby off the surface
	 * elevator, a spine corridor, lab and office wings either side, a control
	 * room — and at the east end the test chamber, whose bore drops through
	 * this floor and the next before it has a floor of its own.
	 */
	private static void sectorC(World w) {
		// The block: concrete through and through; rooms are carved from it.
		fill(w, LABS, 52, 36, 108, 68, Tile.TileType.TYPE_WALL_CONCRETE);

		// Lobby.
		fill(w, LABS, 54, 42, 67, 62, Tile.TileType.TYPE_PAVED);
		fill(w, LABS, 58, 51, 60, 51, Tile.TileType.TYPE_DESK); // reception
		// The spine corridor.
		fill(w, LABS, 67, 51, 86, 53, Tile.TileType.TYPE_PAVED);

		// North wing: two labs, a locker room, an anteroom.
		fill(w, LABS, 69, 38, 76, 44, Tile.TileType.TYPE_LIGHTGRATE); // lab A
		fill(w, LABS, 70, 39, 75, 39, Tile.TileType.TYPE_SERVER);
		fill(w, LABS, 78, 38, 85, 44, Tile.TileType.TYPE_PLATE);      // lab B
		fill(w, LABS, 79, 39, 84, 39, Tile.TileType.TYPE_PIPES);
		fill(w, LABS, 69, 46, 76, 49, Tile.TileType.TYPE_PLATE);      // lockers
		fill(w, LABS, 69, 46, 76, 46, Tile.TileType.TYPE_WALL_BUILT);
		fill(w, LABS, 78, 46, 85, 49, Tile.TileType.TYPE_PAVED);      // anteroom
		set(w, 72, 50, LABS, Tile.TileType.TYPE_PAVED); // doors to the spine
		set(w, 81, 50, LABS, Tile.TileType.TYPE_PAVED);
		set(w, 72, 45, LABS, Tile.TileType.TYPE_PLATE); // lab A <-> lockers
		set(w, 72, 46, LABS, Tile.TileType.TYPE_PLATE); // gap in the locker row
		set(w, 81, 45, LABS, Tile.TileType.TYPE_PLATE); // lab B <-> anteroom

		// South wing: four offices.
		fill(w, LABS, 69, 55, 85, 66, Tile.TileType.TYPE_PAVED);
		fill(w, LABS, 77, 55, 77, 66, Tile.TileType.TYPE_WALL_BUILT);
		fill(w, LABS, 69, 61, 85, 61, Tile.TileType.TYPE_WALL_BUILT);
		set(w, 73, 61, LABS, Tile.TileType.TYPE_PAVED);
		set(w, 81, 61, LABS, Tile.TileType.TYPE_PAVED);
		set(w, 72, 54, LABS, Tile.TileType.TYPE_PAVED); // doors to the spine
		set(w, 81, 54, LABS, Tile.TileType.TYPE_PAVED);
		// A desk or two per office -- the rooms stop being empty floor.
		for (int[] d : new int[][] { { 71, 57 }, { 74, 58 }, { 80, 57 },
				{ 83, 58 }, { 71, 63 }, { 74, 64 }, { 80, 63 }, { 83, 64 } }) {
			set(w, d[0], d[1], LABS, Tile.TileType.TYPE_DESK);
		}

		// Control room, its instrument wall facing the chamber.
		fill(w, LABS, 87, 46, 92, 58, Tile.TileType.TYPE_PLATE);
		fill(w, LABS, 91, 48, 91, 56, Tile.TileType.TYPE_SERVER);
		set(w, 88, 48, LABS, Tile.TileType.TYPE_DESK);
		set(w, 88, 55, LABS, Tile.TileType.TYPE_DESK);
		set(w, 86, 52, LABS, Tile.TileType.TYPE_PAVED); // from the spine

		// The test chamber: shell, catwalk gallery, and the bore.
		chamberRing(w, LABS);
		// The instrument wall's view: four panes of the chamber shell become
		// glazing, so the control room watches the bore through glass.
		set(w, 92, 48, LABS, Tile.TileType.TYPE_WINDOW);
		set(w, 93, 47, LABS, Tile.TileType.TYPE_WINDOW);
		set(w, 92, 55, LABS, Tile.TileType.TYPE_WINDOW);
		set(w, 93, 56, LABS, Tile.TileType.TYPE_WINDOW);
		fill(w, LABS, 91, 52, 94, 52, Tile.TileType.TYPE_PLATE); // chamber door,
		// through a gap in the instrument wall: the shell's west arc crosses
		// the column east of the racks, so the door row is the only way past

		// The way to the office complex is not a corridor: it is the
		// maintenance route — pipe runs, then a stretch where the ceiling has
		// come down and the deck is climbed as much as walked. Back-of-house,
		// the way that path is in the story.
		fill(w, LABS, 40, 56, 52, 56, Tile.TileType.TYPE_PIPES);
		for (int y = 56; y <= 70; y++) {
			set(w, 40, y, LABS, (y % 2 == 0)
					? Tile.TileType.TYPE_COLLAPSE : Tile.TileType.TYPE_PLATE);
		}
		set(w, 53, 56, LABS, Tile.TileType.TYPE_PIPES); // through the shell
	}

	/**
	 * Sector E: the specimen labs. Three enclosure pens behind rack-glass —
	 * a fungus bed, a wetland of reeds, a dry scrub pen — on a corridor with
	 * an observation room and a surgery. The glass is server-rack tile:
	 * solid to a body, clear to the eye, which is what an enclosure wall is.
	 * The pens are the one part of the campus that is alive: the fungus bed
	 * is fertile and regrows, so the enclosures keep themselves stocked.
	 */
	private static void sectorE(World w) {
		fill(w, LABS, 112, 38, 138, 58, Tile.TileType.TYPE_WALL_CONCRETE);
		// Corridor.
		fill(w, LABS, 114, 47, 136, 49, Tile.TileType.TYPE_PAVED);
		// The pens.
		shell(w, LABS, 114, 40, 120, 46,
				Tile.TileType.TYPE_WINDOW, Tile.TileType.TYPE_FUNGUS);
		for (int x = 115; x <= 119; x++) {
			for (int y = 41; y <= 45; y++) {
				w.getTile(x, y, LABS).setFertility(0.5);
				w.getTile(x, y, LABS).setRegrowRate(0.002);
			}
		}
		shell(w, LABS, 122, 44 - 4, 128, 46,
				Tile.TileType.TYPE_WINDOW, Tile.TileType.TYPE_REEDS);
		set(w, 125, 42, LABS, Tile.TileType.TYPE_SHALLOWS);
		set(w, 125, 43, LABS, Tile.TileType.TYPE_SHALLOWS);
		shell(w, LABS, 130, 40, 136, 46,
				Tile.TileType.TYPE_WINDOW, Tile.TileType.TYPE_COVER);
		set(w, 132, 42, LABS, Tile.TileType.TYPE_ROCKY);
		set(w, 134, 44, LABS, Tile.TileType.TYPE_ROCKY);
		// Each pen's gate.
		set(w, 117, 46, LABS, Tile.TileType.TYPE_PLATE);
		set(w, 125, 46, LABS, Tile.TileType.TYPE_PLATE);
		set(w, 133, 46, LABS, Tile.TileType.TYPE_PLATE);
		// Observation room and surgery, south of the corridor.
		fill(w, LABS, 114, 51, 124, 56, Tile.TileType.TYPE_PLATE);
		fill(w, LABS, 115, 55, 123, 55, Tile.TileType.TYPE_SERVER);
		fill(w, LABS, 127, 51, 136, 56, Tile.TileType.TYPE_LIGHTGRATE);
		set(w, 128, 52, LABS, Tile.TileType.TYPE_SERVER);
		set(w, 135, 52, LABS, Tile.TileType.TYPE_SERVER);
		set(w, 118, 50, LABS, Tile.TileType.TYPE_PAVED); // doors off the corridor
		set(w, 130, 50, LABS, Tile.TileType.TYPE_PAVED);
		// The way in: a corridor from the east tram station, cut through the
		// block's south face and up into the surgery.
		fill(w, LABS, 134, 57, 134, 65, Tile.TileType.TYPE_PAVED);
		fill(w, LABS, 135, 65, 136, 65, Tile.TileType.TYPE_PAVED);
	}

	/** The office complex: a cubicle farm on a corridor, cafeteria, freezer,
	 *  break room — administration, one floor above the works. */
	private static void officeComplex(World w) {
		fill(w, LABS, 18, 70, 50, 94, Tile.TileType.TYPE_WALL_CONCRETE);
		// Corridor.
		fill(w, LABS, 20, 81, 48, 83, Tile.TileType.TYPE_PAVED);
		// Cubicles north of it.
		fill(w, LABS, 20, 72, 48, 79, Tile.TileType.TYPE_PAVED);
		for (int x : new int[] { 26, 32, 38, 44 }) {
			fill(w, LABS, x, 72, x, 78, Tile.TileType.TYPE_WALL_BUILT);
		}
		for (int x : new int[] { 23, 29, 35, 41, 47 }) {
			set(w, x, 80, LABS, Tile.TileType.TYPE_PAVED); // each opens south
			set(w, x, 74, LABS, Tile.TileType.TYPE_DESK);  // and works north
		}
		// Cafeteria with its serving line.
		fill(w, LABS, 20, 85, 34, 92, Tile.TileType.TYPE_PLATE);
		fill(w, LABS, 21, 86, 33, 86, Tile.TileType.TYPE_TREADPLATE);
		// Break room.
		fill(w, LABS, 37, 85, 42, 88, Tile.TileType.TYPE_PAVED);
		// The freezer: a steel box with a stone-cold floor, reached through a
		// vestibule off the corridor door — its first door was on the west
		// wall, opening into rock nobody had carved.
		shell(w, LABS, 44, 88, 48, 92,
				Tile.TileType.TYPE_WALL_STEEL, Tile.TileType.TYPE_STONE);
		fill(w, LABS, 45, 85, 47, 87, Tile.TileType.TYPE_PLATE);
		set(w, 46, 88, LABS, Tile.TileType.TYPE_STONE); // through the north wall
		// Doors from the corridor to the south rooms.
		set(w, 27, 84, LABS, Tile.TileType.TYPE_PAVED);
		set(w, 39, 84, LABS, Tile.TileType.TYPE_PAVED);
		set(w, 46, 84, LABS, Tile.TileType.TYPE_PAVED);
		// The corridor's own way in from the connector, and down to the loop.
		set(w, 40, 70, LABS, Tile.TileType.TYPE_PAVED);
		fill(w, LABS, 40, 71, 40, 81, Tile.TileType.TYPE_PAVED);
	}

	// ---- z=1: the works floor ----------------------------------------------

	private static void worksFloor(World w) {
		transitLoop(w);
		siloComplex(w);
		freightLine(w);
		residueProcessing(w);
		warehouse(w);
		coldStorage(w);
		lambdaEntry(w);

		// The test chamber again: gallery and bore, one floor further down.
		chamberRing(w, WORKS);
		fill(w, WORKS, 92, 52, 94, 52, Tile.TileType.TYPE_PLATE);
		// Its service tunnel west to the sector's tram spur.
		fill(w, WORKS, 59, 51, 92, 53, Tile.TileType.TYPE_STONE);
	}

	/**
	 * The transit loop: the ring of rail every sector hangs from, with spurs
	 * north to the labs and south to the offices, and plate platforms where
	 * the stairs come down.
	 */
	private static void transitLoop(World w) {
		// The ring tunnel: three tiles of carved stone with the rail down the
		// centreline.
		fill(w, WORKS, 28, 17, 148, 19, Tile.TileType.TYPE_STONE);   // north run
		fill(w, WORKS, 28, 95, 148, 97, Tile.TileType.TYPE_STONE);   // south run
		fill(w, WORKS, 28, 17, 30, 97, Tile.TileType.TYPE_STONE);    // west run
		fill(w, WORKS, 145, 17, 147, 97, Tile.TileType.TYPE_STONE);  // east run
		fill(w, WORKS, 29, 18, 146, 18, Tile.TileType.TYPE_RAIL);
		fill(w, WORKS, 29, 96, 146, 96, Tile.TileType.TYPE_RAIL);
		fill(w, WORKS, 29, 18, 29, 96, Tile.TileType.TYPE_RAIL);
		fill(w, WORKS, 146, 18, 146, 96, Tile.TileType.TYPE_RAIL);

		// Platform under portal A, straddling the north run — the painted
		// edge line is the platform's one warning.
		fill(w, WORKS, 42, 16, 54, 24, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 43, 19, 53, 19, Tile.TileType.TYPE_HAZARD);
		fill(w, WORKS, 43, 18, 53, 18, Tile.TileType.TYPE_RAIL);

		// Platform under the dormitories — the far end of the commute.
		fill(w, WORKS, 60, 16, 70, 24, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 60, 19, 70, 19, Tile.TileType.TYPE_HAZARD);
		fill(w, WORKS, 60, 18, 70, 18, Tile.TileType.TYPE_RAIL);

		// Platform against the east run, for the silo and portal B.
		fill(w, WORKS, 138, 62, 147, 70, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 146, 63, 146, 69, Tile.TileType.TYPE_RAIL);

		// The Sector C spur: south off the north run to a platform under the
		// lobby.
		fill(w, WORKS, 57, 20, 59, 64, Tile.TileType.TYPE_STONE);
		fill(w, WORKS, 58, 19, 58, 60, Tile.TileType.TYPE_RAIL);
		fill(w, WORKS, 52, 56, 64, 64, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 58, 56, 58, 60, Tile.TileType.TYPE_RAIL);

		// The office spur: north off the south run.
		fill(w, WORKS, 23, 78, 25, 95, Tile.TileType.TYPE_STONE);
		fill(w, WORKS, 24, 86, 24, 95, Tile.TileType.TYPE_RAIL);
		fill(w, WORKS, 18, 78, 30, 86, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 24, 79, 24, 86, Tile.TileType.TYPE_RAIL);
	}

	/** The silo, mid-bore: the shaft with its gallery, and the four service
	 *  rooms the old program left around it — fuel, oxygen, power, control. */
	private static void siloComplex(World w) {
		// The gallery: a catwalk ring around the round bore.
		ringAt(w, WORKS, SILO_X, SILO_Y, 3.5, 5.5, Tile.TileType.TYPE_CATWALK);
		siloBore(w, WORKS);
		// Corridor in from the east platform.
		fill(w, WORKS, 126, 65, 138, 67, Tile.TileType.TYPE_STONE);
		// Fuel.
		shell(w, WORKS, 112, 68, 118, 73,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PIPES);
		// Oxygen.
		shell(w, WORKS, 112, 75, 118, 80,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		set(w, 114, 77, WORKS, Tile.TileType.TYPE_AIRVENT);
		set(w, 116, 78, WORKS, Tile.TileType.TYPE_AIRVENT);
		// Power.
		shell(w, WORKS, 127, 68, 133, 73,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 128, 70, 132, 70, Tile.TileType.TYPE_EXCHANGER);
		// Control.
		shell(w, WORKS, 127, 75, 133, 80,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 128, 79, 132, 79, Tile.TileType.TYPE_SERVER);
		// Doorways: the corridor into power, power through to control, and
		// each room straight onto the gallery ring where it passes closest.
		set(w, 130, 68, WORKS, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 130, 73, 130, 75, Tile.TileType.TYPE_PLATE); // power <-> control
		set(w, 118, 71, WORKS, Tile.TileType.TYPE_PLATE); // fuel -> gallery
		// The ring pokes a stub between the two west rooms' walls; a door
		// from the fuel room turns it from a dead end into a bore balcony.
		set(w, 117, 73, WORKS, Tile.TileType.TYPE_PLATE);
		set(w, 118, 77, WORKS, Tile.TileType.TYPE_PLATE); // oxygen -> gallery
		set(w, 127, 72, WORKS, Tile.TileType.TYPE_PLATE); // power -> gallery
		set(w, 127, 76, WORKS, Tile.TileType.TYPE_PLATE); // control -> gallery
	}

	/**
	 * The old freight line: a second, older rail system, distinct from the
	 * transit loop on purpose — its tunnel is broken rubble where the loop's
	 * is dressed stone. It branches off past the silo complex and runs out to
	 * the launch gantry, where the rocket stands in its flame-trench gap with
	 * the gantry arms reaching it.
	 */
	private static void freightLine(World w) {
		// The branch south from the east platform, stepping west and south in
		// two bends: the line goes AROUND the silo complex, the way an old
		// track skirts the thing that was there first.
		fill(w, WORKS, 141, 71, 143, 81, Tile.TileType.TYPE_RUBBLE);
		fill(w, WORKS, 134, 79, 143, 81, Tile.TileType.TYPE_RUBBLE);
		fill(w, WORKS, 134, 80, 136, 88, Tile.TileType.TYPE_RUBBLE);
		fill(w, WORKS, 120, 86, 136, 88, Tile.TileType.TYPE_RUBBLE);
		// The gantry hall.
		shell(w, WORKS, 112, 82, 126, 94,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 115, 84, 122, 91, Tile.TileType.TYPE_CATWALK);
		launchThroat(w, WORKS);
		// The gantry arms, reaching the rocket across the gap.
		fill(w, WORKS, 116, 87, 117, 88, Tile.TileType.TYPE_CATWALK);
		fill(w, WORKS, 120, 87, 121, 88, Tile.TileType.TYPE_CATWALK);
		// The rail itself, laid last so it cuts through wall and rubble alike.
		fill(w, WORKS, 142, 66, 142, 80, Tile.TileType.TYPE_RAIL);
		fill(w, WORKS, 135, 80, 142, 80, Tile.TileType.TYPE_RAIL);
		fill(w, WORKS, 135, 80, 135, 87, Tile.TileType.TYPE_RAIL);
		fill(w, WORKS, 123, 87, 135, 87, Tile.TileType.TYPE_RAIL);
	}

	/** Residue processing: the sludge channels, catwalks over them, and the
	 *  conveyor line along the top. */
	private static void residueProcessing(World w) {
		fill(w, WORKS, 64, 83, 100, 94, Tile.TileType.TYPE_STONE);
		fill(w, WORKS, 66, 84, 98, 84, Tile.TileType.TYPE_CONVEYOR);
		for (int y : new int[] { 86, 89, 92 }) {
			fill(w, WORKS, 66, y, 98, y, Tile.TileType.TYPE_SLUDGE);
		}
		for (int x : new int[] { 72, 82, 92 }) {
			fill(w, WORKS, x, 85, x, 93, Tile.TileType.TYPE_CATWALK);
		}
		fill(w, WORKS, 82, 94, 82, 95, Tile.TileType.TYPE_STONE); // to the loop
	}

	/** The warehouse: rack rows off the north run. */
	private static void warehouse(World w) {
		shell(w, WORKS, 104, 22, 130, 38,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		for (int y : new int[] { 26, 30, 34 }) {
			fill(w, WORKS, 106, y, 128, y, Tile.TileType.TYPE_SERVER);
			set(w, 112, y, WORKS, Tile.TileType.TYPE_PLATE); // aisle gaps
			set(w, 120, y, WORKS, Tile.TileType.TYPE_PLATE);
		}
		fill(w, WORKS, 116, 19, 116, 22, Tile.TileType.TYPE_STONE); // loop door
		set(w, 127, 36, WORKS, Tile.TileType.TYPE_WRECK); // the forklift that died
	}

	/** Cold storage: a steel box off the warehouse, stone-cold floor, the
	 *  hanging rows in rack steel. */
	private static void coldStorage(World w) {
		shell(w, WORKS, 112, 42, 124, 52,
				Tile.TileType.TYPE_WALL_STEEL, Tile.TileType.TYPE_STONE);
		for (int y : new int[] { 45, 48 }) {
			fill(w, WORKS, 114, y, 122, y, Tile.TileType.TYPE_SERVER);
			set(w, 118, y, WORKS, Tile.TileType.TYPE_STONE);
		}
		fill(w, WORKS, 116, 38, 116, 42, Tile.TileType.TYPE_STONE); // from the warehouse
		// The drop to the deep cellar: the freezer's vertical is its drama.
		fill(w, WORKS, 117, 50, 118, 51, Tile.TileType.TYPE_SHAFT);
	}

	/** The reactor complex's entry floor: security, corridors, and the stair
	 *  down to the core. The end of the line, west of the loop. */
	private static void lambdaEntry(World w) {
		shell(w, WORKS, 8, 18, 26, 40,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PAVED);
		fill(w, WORKS, 14, 22, 14, 24, Tile.TileType.TYPE_SERVER); // security
		fill(w, WORKS, 26, 28, 28, 30, Tile.TileType.TYPE_STONE);  // to the loop
	}

	// ---- z=0: the deep floor ----------------------------------------------

	private static void deepFloor(World w) {
		testChamberFloor(w);
		oldLabs(w);
		engineBay(w);
		launchTrench(w);
		iceCellar(w);
		wasteSump(w);
		lambdaCore(w);
	}

	/**
	 * The old labs: the complex the facility grew out of, and then grew out
	 * of needing. A quartered block on two spine corridors, dressed the way
	 * thirty abandoned years dress a place — collapsed ceiling where the
	 * noise says so, rubble where it has been coming down longer, one lab
	 * still lit because nobody came back to turn it off, a sludge leak, and
	 * a crawl duct along the south wall. The grand cavern's collapse ate its
	 * east rooms; what the cave-in left is left as it fell.
	 */
	private static void oldLabs(World w) {
		shell(w, DEEP, 40, 16, 86, 48,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		// Quartering partitions, then the two spines carved through them.
		fill(w, DEEP, 52, 17, 52, 47, Tile.TileType.TYPE_WALL_CONCRETE);
		fill(w, DEEP, 72, 17, 72, 47, Tile.TileType.TYPE_WALL_CONCRETE);
		fill(w, DEEP, 41, 24, 85, 24, Tile.TileType.TYPE_WALL_CONCRETE);
		fill(w, DEEP, 41, 40, 85, 40, Tile.TileType.TYPE_WALL_CONCRETE);
		fill(w, DEEP, 41, 31, 85, 33, Tile.TileType.TYPE_PAVED);
		fill(w, DEEP, 62, 17, 64, 47, Tile.TileType.TYPE_PAVED);
		// Room doors off the spines.
		for (int x : new int[] { 46, 58, 68, 80 }) {
			set(w, x, 24, DEEP, Tile.TileType.TYPE_PLATE);
			set(w, x, 40, DEEP, Tile.TileType.TYPE_PLATE);
		}
		// Decay, drawn by noise so no two rooms rotted alike.
		for (int x = 41; x <= 85; x++) {
			for (int y = 17; y <= 47; y++) {
				if (w.getTile(x, y, DEEP).getType() != Tile.TileType.TYPE_PLATE) {
					continue;
				}
				double rot = Utils.noise2(x + 811, y + 457, 0.16);
				if (rot > 0.68) {
					set(w, x, y, DEEP, Tile.TileType.TYPE_COLLAPSE);
				} else if (rot < 0.12) {
					set(w, x, y, DEEP, Tile.TileType.TYPE_RUBBLE);
				}
			}
		}
		// The lab nobody turned off.
		fill(w, DEEP, 42, 18, 50, 22, Tile.TileType.TYPE_LIGHTGRATE);
		fill(w, DEEP, 43, 20, 49, 20, Tile.TileType.TYPE_SERVER);
		set(w, 46, 20, DEEP, Tile.TileType.TYPE_LIGHTGRATE);
		// The old test annex, shards still scattered where the work stopped.
		fill(w, DEEP, 74, 42, 84, 46, Tile.TileType.TYPE_LIGHTGRATE);
		set(w, 79, 44, DEEP, Tile.TileType.TYPE_CRYSTAL_SPARSE);
		set(w, 76, 43, DEEP, Tile.TileType.TYPE_CRYSTAL_SPARSE);
		// The machines that stopped where the work did.
		set(w, 75, 20, DEEP, Tile.TileType.TYPE_WRECK);
		set(w, 44, 36, DEEP, Tile.TileType.TYPE_WRECK);
		set(w, 57, 21, DEEP, Tile.TileType.TYPE_WRECK);
		// The leak, and the crawl duct along the south wall.
		fill(w, DEEP, 80, 18, 84, 20, Tile.TileType.TYPE_SLUDGE);
		fill(w, DEEP, 42, 47, 60, 47, Tile.TileType.TYPE_DUCT);
		// The way in from the chamber's corridor, and through to the reactor.
		set(w, 86, 44, DEEP, Tile.TileType.TYPE_PLATE);
		fill(w, DEEP, 87, 44, 87, 51, Tile.TileType.TYPE_PLATE);
		set(w, 88, 51, DEEP, Tile.TileType.TYPE_PLATE);
		fill(w, DEEP, 34, 32, 41, 32, Tile.TileType.TYPE_PLATE);
	}

	/** The bottom of the launch pad: the flame trench, with the rocket's base
	 *  standing on it and the deluge exchangers either side. */
	private static void launchTrench(World w) {
		shell(w, DEEP, 112, 84, 126, 94,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		fill(w, DEEP, 118, 87, 119, 88, Tile.TileType.TYPE_WALL_STEEL);
		set(w, 122, 92, DEEP, Tile.TileType.TYPE_EXCHANGER);
		set(w, 124, 92, DEEP, Tile.TileType.TYPE_EXCHANGER);
	}

	/** The bottom of the bore: the chamber floor, its emitters, and the
	 *  crystal the whole facility exists to point them at. */
	private static void testChamberFloor(World w) {
		ring(w, DEEP, 7.5, 8.5, Tile.TileType.TYPE_WALL_CONCRETE);
		disk(w, DEEP, 7.5, Tile.TileType.TYPE_LIGHTGRATE);
		fill(w, DEEP, 99, 51, 100, 52, Tile.TileType.TYPE_CRYSTAL);
		set(w, 100, 47, DEEP, Tile.TileType.TYPE_EXCHANGER);
		set(w, 100, 57, DEEP, Tile.TileType.TYPE_EXCHANGER);
		set(w, 95, 52, DEEP, Tile.TileType.TYPE_EXCHANGER);
		set(w, 105, 52, DEEP, Tile.TileType.TYPE_EXCHANGER);
		// Antechamber and the corridor from the stair.
		fill(w, DEEP, 88, 49, 93, 55, Tile.TileType.TYPE_PLATE);
		set(w, 94, 52, DEEP, Tile.TileType.TYPE_PLATE); // through the shell
		fill(w, DEEP, 84, 51, 88, 53, Tile.TileType.TYPE_PLATE);
	}

	/** The engine bay, at the very bottom of the silo bore. */
	private static void engineBay(World w) {
		shell(w, DEEP, 112, 66, 132, 84,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		fill(w, DEEP, 116, 68, 130, 69, Tile.TileType.TYPE_PIPES);
		set(w, 117, 80, DEEP, Tile.TileType.TYPE_EXCHANGER);
		set(w, 128, 80, DEEP, Tile.TileType.TYPE_EXCHANGER);
		// The pool under the bore, and the test engine standing in it. The
		// sludge is what every floor above sees down the shaft.
		diskAt(w, DEEP, SILO_X, SILO_Y, 4.5, Tile.TileType.TYPE_SLUDGE);
		fill(w, DEEP, 122, 74, 123, 75, Tile.TileType.TYPE_EXCHANGER);
	}

	/** The deep cellar under cold storage: further down, further from the
	 *  sun, racks in the dark. Reached by the freezer's drop shaft; the
	 *  stair back up is by the north wall. */
	private static void iceCellar(World w) {
		shell(w, DEEP, 112, 42, 124, 54,
				Tile.TileType.TYPE_WALL_STEEL, Tile.TileType.TYPE_STONE);
		for (int y : new int[] { 46, 52 }) {
			fill(w, DEEP, 115, y, 121, y, Tile.TileType.TYPE_SERVER);
			set(w, 118, y, DEEP, Tile.TileType.TYPE_STONE);
		}
	}

	/** The sump the residue channels drain into. */
	private static void wasteSump(World w) {
		fill(w, DEEP, 64, 83, 98, 95, Tile.TileType.TYPE_STONE);
		fill(w, DEEP, 68, 85, 96, 87, Tile.TileType.TYPE_SLUDGE);
		fill(w, DEEP, 68, 91, 96, 93, Tile.TileType.TYPE_SLUDGE);
		fill(w, DEEP, 68, 89, 96, 89, Tile.TileType.TYPE_CATWALK);
	}

	/**
	 * The reactor complex's core floor: the coolant loop around the exchanger
	 * block and its crystal heart, catwalk aisles in, and the round teleport
	 * chamber to the south.
	 */
	private static void lambdaCore(World w) {
		shell(w, DEEP, 8, 12, 34, 44,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		// The coolant loop is a circle around the core — the reactor reads as
		// a round machine in a square room, not a square in a square.
		ringAt(w, DEEP, 21.0, 28.0, 5.5, 6.5, Tile.TileType.TYPE_COOLANT);
		// The core.
		fill(w, DEEP, 19, 26, 23, 30, Tile.TileType.TYPE_EXCHANGER);
		set(w, 21, 28, DEEP, Tile.TileType.TYPE_CRYSTAL);
		// Catwalk aisles bridging the loop to the core, on both axes.
		fill(w, DEEP, 13, 28, 18, 28, Tile.TileType.TYPE_CATWALK);
		fill(w, DEEP, 24, 28, 29, 28, Tile.TileType.TYPE_CATWALK);
		fill(w, DEEP, 21, 19, 21, 25, Tile.TileType.TYPE_CATWALK);
		fill(w, DEEP, 21, 31, 21, 37, Tile.TileType.TYPE_CATWALK);

		// The teleport chamber, off the core's south wall. The corridor is
		// carved after the ring: it crosses the ring's wall, and carving it
		// first left the ring free to seal its own doorway back up.
		ringAt(w, DEEP, 21.0, 51.0, 4.5, 5.5, Tile.TileType.TYPE_WALL_CONCRETE);
		diskAt(w, DEEP, 21.0, 51.0, 4.5, Tile.TileType.TYPE_LIGHTGRATE);
		set(w, 21, 51, DEEP, Tile.TileType.TYPE_CRYSTAL);
		fill(w, DEEP, 21, 44, 21, 46, Tile.TileType.TYPE_PLATE); // the way in
	}

	/**
	 * Every stair in the facility, stamped in one pass AFTER all four floors
	 * have laid their rooms. Stamped any earlier, a stair's lower landing and
	 * climbing ramp sit on a floor that has not been carved yet — and the
	 * carve then paves straight over them, which quietly turns a two-way
	 * stairwell into a drop. Every stair here had exactly that bug when they
	 * were stamped inside their own rooms' builders.
	 */
	private static void stairs(World w) {
		// Surface down to the labs floor.
		stair(w, SURFACE, 64, 45, 1);  // sector head -> lobby
		stair(w, SURFACE, 48, 25, 1);  // portal A -> its station room
		stair(w, SURFACE, 140, 65, 2); // portal B -> its station room
		stair(w, SURFACE, 67, 15, 1); // dorm court -> the dormitory station
		// Labs floor down to the works floor.
		stair(w, LABS, 45, 22, 1);     // station A -> loop platform
		stair(w, LABS, 137, 68, 1);    // station B -> east platform
		stair(w, LABS, 55, 60, 1);     // lobby -> the sector's tram platform
		stair(w, LABS, 21, 82, 1);     // office corridor -> office platform
		stair(w, LABS, 62, 20, 1);     // dormitory station -> its platform
		// Works floor down to the deep floor.
		stair(w, WORKS, 84, 52, 1);    // service tunnel -> chamber antechamber
		stair(w, WORKS, 113, 79, 1);   // oxygen room -> engine bay
		stair(w, WORKS, 66, 88, 1);    // residue processing -> the sump
		stair(w, WORKS, 12, 36, 1);    // reactor entry -> the core
		stair(w, WORKS, 114, 92, 1);   // launch gantry -> the flame trench
		stair(w, WORKS, 114, 44, 1);   // cold storage -> the ice cellar
	}

	// ---- shared pieces ------------------------------------------------------

	/** One floor of the test chamber above its own floor: the concrete shell,
	 *  the catwalk gallery, and the bore falling through the middle. */
	private static void chamberRing(World w, int z) {
		ring(w, z, 7.5, 8.5, Tile.TileType.TYPE_WALL_CONCRETE);
		ring(w, z, 3.5, 7.5, Tile.TileType.TYPE_CATWALK);
		disk(w, z, 3.5, Tile.TileType.TYPE_SHAFT);
	}

	/** The launch pad's throat on one floor: the flame-trench gap as an
	 *  annulus of open shaft, and the rocket — a steel column — standing in
	 *  the middle of it. The same column on every floor is what makes it one
	 *  rocket rather than four drawings. */
	private static void launchThroat(World w, int z) {
		fill(w, z, 116, 85, 121, 90, Tile.TileType.TYPE_SHAFT);
		fill(w, z, 118, 87, 119, 88, Tile.TileType.TYPE_WALL_STEEL);
	}

	/** One floor of the silo bore: a round shaft falling straight through.
	 *  The green in it is real: the bore's pit rendering scatters the floor
	 *  below into the opening, and the floor at the bottom is the sludge
	 *  pool — so the shaft glows green from every level above, the way the
	 *  silo is remembered. */
	private static void siloBore(World w, int z) {
		diskAt(w, z, SILO_X, SILO_Y, 3.5, Tile.TileType.TYPE_SHAFT);
	}

	/** Every shore-touching water tile becomes a wading fringe, exactly as
	 *  the demo world does it, so the oasis has a soft edge. */
	private static void shallowsFringe(World w) {
		java.util.ArrayList<int[]> shore = new java.util.ArrayList<int[]>();
		for (int x = 1; x < COLS - 1; x++) {
			for (int y = 1; y < ROWS - 1; y++) {
				if (w.getTile(x, y, SURFACE).getType() != Tile.TileType.TYPE_WATER) {
					continue;
				}
				for (int k = 0; k < 4; k++) {
					int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
					int ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
					Tile.TileType n = w.getTile(nx, ny, SURFACE).getType();
					if (n != Tile.TileType.TYPE_WATER && n != Tile.TileType.TYPE_SHALLOWS
							&& w.getTile(nx, ny, SURFACE).isWalkable()) {
						shore.add(new int[] { x, y });
						break;
					}
				}
			}
		}
		for (int[] p : shore) {
			set(w, p[0], p[1], SURFACE, Tile.TileType.TYPE_SHALLOWS);
		}
	}

	/**
	 * The dressing pass: the details that turn architecture into evidence.
	 * Composed in clusters, not sprinkled — a spill next to rubble next to a
	 * dead berth reads as one event; the same tiles scattered read as noise.
	 * Runs after every structural pass so nothing paves over it, and before
	 * the driller so any pocket it creates still gets connected.
	 */
	private static void dressing(World w) {
		ductNetworks(w);
		caveinAprons(w);
		veinsAndPools(w);
		leadingLines(w);
		incidents(w);

		// The second bridge over the gorge, the one that did not hold: two
		// catwalk stubs reaching from either rim, and nothing between them.
		fill(w, SURFACE, 100, 72, 101, 72, Tile.TileType.TYPE_CATWALK);
		fill(w, SURFACE, 100, 75, 101, 75, Tile.TileType.TYPE_CATWALK);
	}

	/**
	 * The second detail pass — everything the first one exposed by contrast.
	 * Roads that actually link the surface compounds; a boneyard of dead
	 * vehicles outside the motorpool fence; a camp somebody kept at the oasis;
	 * lit sections pacing the loop tunnels; painted platform edges on every
	 * platform, not just two; consoles facing every pane of glass; keep-clear
	 * paint around both cores; windows where people lived. Clusters, always —
	 * the rule that made the first pass work.
	 */
	private static void intricacy(World w) {
		// ---- surface: the roads the compounds always implied ----
		fill(w, SURFACE, 34, 35, 48, 35, Tile.TileType.TYPE_PAVED); // gate -> portal A
		fill(w, SURFACE, 49, 28, 49, 35, Tile.TileType.TYPE_PAVED);
		set(w, 49, 27, SURFACE, Tile.TileType.TYPE_PLATE); // portal A's south door
		fill(w, SURFACE, 67, 53, 116, 53, Tile.TileType.TYPE_PAVED); // head -> silo
		fill(w, SURFACE, 116, 53, 116, 69, Tile.TileType.TYPE_PAVED);

		// The boneyard: the machines the motorpool gave up on, parked outside
		// its west fence in their own rubble.
		for (int[] r : new int[][] { { 13, 19 }, { 13, 23 }, { 13, 27 } }) {
			fill(w, SURFACE, r[0], r[1], r[0] + 4, r[1] + 1, Tile.TileType.TYPE_RUBBLE);
		}
		set(w, 14, 19, SURFACE, Tile.TileType.TYPE_WRECK);
		set(w, 16, 23, SURFACE, Tile.TileType.TYPE_WRECK);
		set(w, 14, 27, SURFACE, Tile.TileType.TYPE_WRECK);

		// The guard hut by the gate.
		shell(w, SURFACE, 28, 36, 30, 38,
				Tile.TileType.TYPE_WALL_BUILT, Tile.TileType.TYPE_PLATE);
		set(w, 29, 36, SURFACE, Tile.TileType.TYPE_PLATE);

		// The camp at the oasis: someone stayed a while, and stopped staying.
		set(w, 58, 90, SURFACE, Tile.TileType.TYPE_BONES);
		set(w, 57, 91, SURFACE, Tile.TileType.TYPE_RUBBLE);
		set(w, 59, 91, SURFACE, Tile.TileType.TYPE_COVER);
		set(w, 57, 93, SURFACE, Tile.TileType.TYPE_BONES);

		// Windows where people slept: the dorms and the barracks look south.
		set(w, 59, 15, SURFACE, Tile.TileType.TYPE_WINDOW);
		set(w, 63, 15, SURFACE, Tile.TileType.TYPE_WINDOW);
		set(w, 59, 22, SURFACE, Tile.TileType.TYPE_WINDOW);
		set(w, 63, 22, SURFACE, Tile.TileType.TYPE_WINDOW);
		set(w, 22, 20, SURFACE, Tile.TileType.TYPE_WINDOW);
		set(w, 27, 20, SURFACE, Tile.TileType.TYPE_WINDOW);

		// ---- labs floor ----
		// The spine's walk-line leads straight at the chamber.
		fill(w, LABS, 67, 52, 86, 52, Tile.TileType.TYPE_TREADPLATE);
		fill(w, LABS, 58, 52, 67, 52, Tile.TileType.TYPE_TREADPLATE);
		// The labs deepen: services in A, instruments in B.
		fill(w, LABS, 70, 43, 75, 43, Tile.TileType.TYPE_PIPES);
		set(w, 79, 43, LABS, Tile.TileType.TYPE_SERVER);
		set(w, 84, 43, LABS, Tile.TileType.TYPE_SERVER);
		// Cafeteria tables, and one for the break room.
		for (int x : new int[] { 23, 26, 29, 32 }) {
			set(w, x, 89, LABS, Tile.TileType.TYPE_DESK);
		}
		set(w, 41, 87, LABS, Tile.TileType.TYPE_DESK);
		// Ticket counters in the station rooms.
		set(w, 46, 26, LABS, Tile.TileType.TYPE_DESK);
		set(w, 67, 16, LABS, Tile.TileType.TYPE_DESK);
		set(w, 141, 62, LABS, Tile.TileType.TYPE_DESK);
		// The archive fills its stacks.
		set(w, 28, 48, LABS, Tile.TileType.TYPE_SERVER);
		set(w, 33, 44, LABS, Tile.TileType.TYPE_SERVER);
		set(w, 41, 50, LABS, Tile.TileType.TYPE_SERVER);
		set(w, 44, 42, LABS, Tile.TileType.TYPE_DESK); // the reading desk

		// ---- works floor ----
		// Lit sections pace the loop: a lamp in the floor every dozen tiles,
		// so the tunnels have rhythm instead of length.
		for (int x = 34; x <= 142; x += 12) {
			lampIfStone(w, x, 17);
			lampIfStone(w, x, 95);
		}
		for (int y = 24; y <= 84; y += 12) {
			lampIfStone(w, 28, y);
			lampIfStone(w, 147, y);
		}
		// Services along the Sector C spur.
		fill(w, WORKS, 57, 22, 57, 50, Tile.TileType.TYPE_PIPES);
		// Painted edges for the three platforms the first pass missed.
		fill(w, WORKS, 145, 63, 145, 69, Tile.TileType.TYPE_HAZARD);
		fill(w, WORKS, 57, 56, 57, 60, Tile.TileType.TYPE_HAZARD);
		fill(w, WORKS, 59, 56, 59, 60, Tile.TileType.TYPE_HAZARD);
		fill(w, WORKS, 23, 79, 23, 85, Tile.TileType.TYPE_HAZARD);
		fill(w, WORKS, 25, 79, 25, 85, Tile.TileType.TYPE_HAZARD);
		// The silo rooms look onto their own bore.
		set(w, 127, 70, WORKS, Tile.TileType.TYPE_WINDOW);
		set(w, 127, 78, WORKS, Tile.TileType.TYPE_WINDOW);
		set(w, 129, 77, WORKS, Tile.TileType.TYPE_DESK);
		set(w, 131, 77, WORKS, Tile.TileType.TYPE_DESK);
		// The derailed cart on the old freight line.
		set(w, 141, 76, WORKS, Tile.TileType.TYPE_WRECK);
		// Sludge seeping into the south run, this close to residue.
		set(w, 70, 95, WORKS, Tile.TileType.TYPE_SLUDGE);
		set(w, 71, 95, WORKS, Tile.TileType.TYPE_SLUDGE);
		// The reactor entry gets its working corner.
		fill(w, WORKS, 10, 20, 12, 22, Tile.TileType.TYPE_LIGHTGRATE);
		set(w, 17, 23, WORKS, Tile.TileType.TYPE_DESK);
		set(w, 17, 25, WORKS, Tile.TileType.TYPE_DESK);

		// ---- deep floor ----
		// Keep-clear paint around the chamber's crystal, and consoles in the
		// antechamber facing it.
		ringAt(w, DEEP, CHAMBER_X, CHAMBER_Y, 1.5, 2.5, Tile.TileType.TYPE_HAZARD);
		set(w, 89, 51, DEEP, Tile.TileType.TYPE_DESK);
		set(w, 89, 53, DEEP, Tile.TileType.TYPE_DESK);
		// The reactor's instrument wall, and paint around the core block.
		fill(w, DEEP, 16, 15, 20, 15, Tile.TileType.TYPE_SERVER);
		fill(w, DEEP, 22, 15, 26, 15, Tile.TileType.TYPE_SERVER);
		for (int x = 18; x <= 24; x++) {
			for (int y = 25; y <= 31; y++) {
				boolean rim = x == 18 || x == 24 || y == 25 || y == 31;
				if (rim && w.getTile(x, y, DEEP).getType() == Tile.TileType.TYPE_PLATE) {
					set(w, x, y, DEEP, Tile.TileType.TYPE_HAZARD);
				}
			}
		}
		// The teleport chamber's four field coils.
		set(w, 18, 48, DEEP, Tile.TileType.TYPE_EXCHANGER);
		set(w, 24, 48, DEEP, Tile.TileType.TYPE_EXCHANGER);
		set(w, 18, 54, DEEP, Tile.TileType.TYPE_EXCHANGER);
		set(w, 24, 54, DEEP, Tile.TileType.TYPE_EXCHANGER);
		// The old labs' spine junction, where the ceiling let go.
		set(w, 61, 30, DEEP, Tile.TileType.TYPE_RUBBLE);
		set(w, 63, 30, DEEP, Tile.TileType.TYPE_COLLAPSE);
		set(w, 65, 34, DEEP, Tile.TileType.TYPE_RUBBLE);
	}

	/** A lamp in the tunnel floor, only where the tunnel actually is. */
	private static void lampIfStone(World w, int x, int y) {
		if (w.getTile(x, y, WORKS).getType() == Tile.TileType.TYPE_STONE) {
			set(w, x, y, WORKS, Tile.TileType.TYPE_LIGHTGRATE);
		}
	}

	/** Crawl ducts inside the walls, with vent grilles where they open into
	 *  rooms — the facility's other circulation system, sized for what can
	 *  fit through a duct. */
	private static void ductNetworks(World w) {
		// Sector C: a run in the north wall linking both labs to the control
		// room's ceiling space.
		fill(w, LABS, 69, 37, 90, 37, Tile.TileType.TYPE_DUCT);
		fill(w, LABS, 90, 38, 90, 45, Tile.TileType.TYPE_DUCT);
		set(w, 70, 38, LABS, Tile.TileType.TYPE_AIRVENT); // lab A grille
		set(w, 80, 38, LABS, Tile.TileType.TYPE_AIRVENT); // lab B grille
		set(w, 90, 46, LABS, Tile.TileType.TYPE_AIRVENT); // control room grille
		// The office complex: a run over the cubicles.
		fill(w, LABS, 21, 71, 47, 71, Tile.TileType.TYPE_DUCT);
		set(w, 25, 72, LABS, Tile.TileType.TYPE_AIRVENT);
		set(w, 37, 72, LABS, Tile.TileType.TYPE_AIRVENT);
		// Sector E: a service duct behind the pens.
		fill(w, LABS, 114, 39, 136, 39, Tile.TileType.TYPE_DUCT);
		// The old labs' duct reaches the whole south wall now.
		fill(w, DEEP, 60, 47, 84, 47, Tile.TileType.TYPE_DUCT);
		set(w, 46, 46, DEEP, Tile.TileType.TYPE_AIRVENT);
		set(w, 78, 46, DEEP, Tile.TileType.TYPE_AIRVENT);
	}

	/** Rubble haloes around every cave-in: a collapse is not a clean circle,
	 *  it shatters its rim and buries what it lands on. Only rock and desert
	 *  take the rubble — rooms keep their floors. */
	private static void caveinAprons(World w) {
		rubbleHalo(w, LABS, 86.0, 30.0, 6.0, 7.5);
		rubbleHalo(w, WORKS, 86.0, 30.0, 5.0, 6.5);
		rubbleHalo(w, DEEP, 86.0, 30.0, 8.0, 9.5);
		rubbleHalo(w, SURFACE, 30.0, 60.0, 4.5, 6.0);
		rubbleHalo(w, LABS, 30.0, 60.0, 6.0, 7.5);
		rubbleHalo(w, WORKS, 60.0, 88.0, 4.0, 5.5);
		rubbleHalo(w, DEEP, 60.0, 88.0, 6.0, 7.5);
	}

	private static void rubbleHalo(World w, int z, double cx, double cy,
			double r0, double r1) {
		for (int x = (int) (cx - r1 - 1); x <= (int) (cx + r1 + 1); x++) {
			for (int y = (int) (cy - r1 - 1); y <= (int) (cy + r1 + 1); y++) {
				double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
				if (d <= r0 || d > r1) {
					continue;
				}
				Tile.TileType t = w.getTile(x, y, z).getType();
				if (t == Tile.TileType.TYPE_WALL || t == Tile.TileType.TYPE_SAND
						|| t == Tile.TileType.TYPE_ROCKY) {
					set(w, x, y, z, Tile.TileType.TYPE_RUBBLE);
				}
			}
		}
	}

	/** Shard veins glinting along the cave floors, and standing water where
	 *  the deep caves meet the water table. */
	private static void veinsAndPools(World w) {
		for (int z = DEEP; z <= LABS; z++) {
			for (int x = 3; x < COLS - 3; x++) {
				for (int y = 3; y < ROWS - 3; y++) {
					if (w.getTile(x, y, z).getType() != Tile.TileType.TYPE_STONE) {
						continue;
					}
					double vein = Utils.noise2(x + 91, y + 37, 0.15);
					if (vein > 0.845 && vein < 0.87) {
						set(w, x, y, z, Tile.TileType.TYPE_CRYSTAL_SPARSE);
					} else if (z == DEEP
							&& Utils.noise2(x + 64, y + 640, 0.11) > 0.93) {
						set(w, x, y, z, Tile.TileType.TYPE_SHALLOWS);
					}
				}
			}
		}
		// The grand cavern's floor gets a shard-bed collar under its walls.
		for (int x = 77; x <= 95; x++) {
			for (int y = 21; y <= 39; y++) {
				double d = Math.hypot(x + 0.5 - 86.0, y + 0.5 - 30.0);
				if (d > 6.5 && d <= 8.0 && (x + y) % 3 != 0
						&& w.getTile(x, y, DEEP).getType() == Tile.TileType.TYPE_STONE) {
					set(w, x, y, DEEP, Tile.TileType.TYPE_CRYSTAL_BED);
				}
			}
		}
	}

	/** The lines that aim the eye: a forklift lane straight through the
	 *  warehouse racks to the loop door, buffer stops where rails end, and
	 *  signal cabinets at the loop's corners. */
	private static void leadingLines(World w) {
		for (int y : new int[] { 26, 30, 34 }) {
			set(w, 116, y, WORKS, Tile.TileType.TYPE_PLATE);
		}
		fill(w, WORKS, 116, 23, 116, 37, Tile.TileType.TYPE_TREADPLATE);
		set(w, 58, 61, WORKS, Tile.TileType.TYPE_WALL_STEEL);  // spur buffer
		set(w, 24, 78, WORKS, Tile.TileType.TYPE_WALL_STEEL);  // spur buffer
		set(w, 31, 20, WORKS, Tile.TileType.TYPE_SERVER);      // signal cabinet
		set(w, 144, 94, WORKS, Tile.TileType.TYPE_SERVER);     // signal cabinet
		// The reactor's feed, up from the core to the north wall.
		fill(w, DEEP, 21, 13, 21, 17, Tile.TileType.TYPE_PIPES);
	}

	/** One incident per floor, clustered so it reads as an event: something
	 *  broke here, something spilled, nobody has been back. */
	private static void incidents(World w) {
		// Surface: a wreck out in the desert, debris thrown around it — and
		// the bones of whatever was aboard, or found it.
		fill(w, SURFACE, 118, 28, 122, 32, Tile.TileType.TYPE_RUBBLE);
		fill(w, SURFACE, 120, 29, 120, 31, Tile.TileType.TYPE_WRECK);
		fill(w, SURFACE, 123, 30, 124, 31, Tile.TileType.TYPE_BONES);
		set(w, 117, 27, SURFACE, Tile.TileType.TYPE_BONES);
		// Labs floor: a burst pipe in the office break room, still pooling.
		fill(w, LABS, 38, 86, 40, 86, Tile.TileType.TYPE_PIPES);
		set(w, 39, 87, LABS, Tile.TileType.TYPE_SHALLOWS);
		set(w, 38, 88, LABS, Tile.TileType.TYPE_RUBBLE);
		// Works floor: a spill at the warehouse tunnel mouth, with the berth
		// of whatever machine was abandoned mid-job.
		fill(w, WORKS, 110, 20, 112, 21, Tile.TileType.TYPE_SLUDGE);
		set(w, 111, 19, WORKS, Tile.TileType.TYPE_DOCK);
		set(w, 110, 19, WORKS, Tile.TileType.TYPE_COLLAPSE);
		// Deep: a dead berth in the old labs, half buried.
		set(w, 56, 44, DEEP, Tile.TileType.TYPE_DOCK);
		set(w, 55, 44, DEEP, Tile.TileType.TYPE_RUBBLE);
		set(w, 57, 45, DEEP, Tile.TileType.TYPE_RUBBLE);
	}

	/**
	 * The non-living furnishings: secured doors with the switches that work
	 * them, and the warehouse's crates. Doors and switches are ordinary
	 * non-living entities (they ride the entity stream and the client draws
	 * them); the world stays uninhabited — nothing here has a metabolism.
	 */
	private static void furniture(World w) {
		// The armory: a blast door on the only doorway, plates both sides.
		secure(w, 25, 28, SURFACE, 0, 27, 28, 23, 28);
		// The old labs' east door, where the way in from the chamber is.
		secure(w, 86, 44, DEEP, 0, 88, 44, 84, 44);
		// The reactor entry's corridor to the loop.
		secure(w, 27, 29, WORKS, 0, 29, 29, 25, 29);

		// Crates where crates live.
		for (int[] c : new int[][] { { 108, 25 }, { 109, 28 }, { 122, 25 },
				{ 126, 33 }, { 108, 36 }, { 110, 33 }, { 124, 28 },
				{ 114, 25 }, { 126, 25 } }) {
			w.spawnEntity(net.hedinger.prototype.entities.Item.crate(
					c[0] + 0.5, c[1] + 0.5, WORKS));
		}
	}

	/** A grate door at (x, y, z) with a weight plate either side of it. */
	private static void secure(World w, int x, int y, int z, int dir,
			int px1, int py1, int px2, int py2) {
		net.hedinger.prototype.entities.Door door =
				new net.hedinger.prototype.entities.Door(x, y, z, dir,
						net.hedinger.prototype.entities.Door.GRATE);
		w.addDoor(door);
		for (int[] pxy : new int[][] { { px1, py1 }, { px2, py2 } }) {
			set(w, pxy[0], pxy[1], z, Tile.TileType.TYPE_SWITCH);
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(
					pxy[0], pxy[1], z, door,
					net.hedinger.prototype.entities.Switch.PLATE));
		}
	}

	/**
	 * Drills the labyrinth whole. Floods the world from the open desert the
	 * way a body moves — same-floor steps, ramps both ways, drops one way —
	 * and for every walkable pocket the flood cannot reach, bores a straight
	 * L-shaped service drift on that pocket's own floor to the nearest tile
	 * the flood did reach. Repeats until nothing is left out. The drift only
	 * cuts what is solid (water it crosses becomes a ford); anything already
	 * walkable is left exactly as carved.
	 *
	 * <p>This is what buys the cave systems their freedom: the noise can cut
	 * whatever pockets it likes, the cave-ins can sever whatever they hit,
	 * and the campus still audits as ONE connected space — by construction,
	 * not by luck.
	 */
	private static void connectAll(World w) {
		for (int guard = 0; guard < 400; guard++) {
			boolean[][][] seen = flood(w);
			int[] pocket = null;
			outer:
			for (int z = 0; z < LVLS; z++) {
				for (int y = 0; y < ROWS; y++) {
					for (int x = 0; x < COLS; x++) {
						if (w.getTile(x, y, z).isWalkable() && !seen[z][x][y]) {
							pocket = new int[] { z, x, y };
							break outer;
						}
					}
				}
			}
			if (pocket == null) {
				return; // one space; done
			}
			int pz = pocket[0], px = pocket[1], py = pocket[2];
			int bx = -1, by = -1, best = Integer.MAX_VALUE;
			for (int x = 2; x < COLS - 2; x++) {
				for (int y = 2; y < ROWS - 2; y++) {
					if (!seen[pz][x][y]) {
						continue;
					}
					int d = Math.abs(x - px) + Math.abs(y - py);
					if (d < best) {
						best = d;
						bx = x;
						by = y;
					}
				}
			}
			if (bx < 0) {
				return; // no anchor on this floor: leave it to the audit to say so
			}
			drill(w, pz, px, py, bx, by);
		}
	}

	/** One straight L-drift: x-leg then y-leg, cutting only solid tiles. */
	private static void drill(World w, int z, int x0, int y0, int x1, int y1) {
		int x = x0, y = y0;
		while (x != x1) {
			x += x1 > x ? 1 : -1;
			drillTile(w, x, y, z);
		}
		while (y != y1) {
			y += y1 > y ? 1 : -1;
			drillTile(w, x, y, z);
		}
	}

	private static void drillTile(World w, int x, int y, int z) {
		Tile t = w.getTile(x, y, z);
		if (t.isWalkable()) {
			return;
		}
		set(w, x, y, z, t.getType() == Tile.TileType.TYPE_WATER
				? Tile.TileType.TYPE_SHALLOWS : Tile.TileType.TYPE_STONE);
	}

	/** The flood: how a body actually gets around, one boolean per tile. */
	private static boolean[][][] flood(World w) {
		boolean[][][] seen = new boolean[LVLS][COLS][ROWS];
		java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
		for (int x = 4; x < COLS; x++) {
			if (w.getTile(x, 60, SURFACE).isWalkable()) {
				seen[SURFACE][x][60] = true;
				q.add(new int[] { SURFACE, x, 60 });
				break;
			}
		}
		int[][] d4 = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		while (!q.isEmpty()) {
			int[] p = q.poll();
			int z = p[0], x = p[1], y = p[2];
			for (int[] d : d4) {
				int nx = x + d[0], ny = y + d[1];
				if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS) {
					continue;
				}
				Tile n = w.getTile(nx, ny, z);
				if (n.isWalkable() && !seen[z][nx][ny]) {
					seen[z][nx][ny] = true;
					q.add(new int[] { z, nx, ny });
				}
				// Stepping onto a drop: fall to the first walkable floor below.
				if (n.isDrop()) {
					int fz = z - 1;
					while (fz >= 0 && w.getTile(nx, ny, fz).isDrop()) {
						fz--;
					}
					if (fz >= 0 && w.getTile(nx, ny, fz).isWalkable() && !seen[fz][nx][ny]) {
						seen[fz][nx][ny] = true;
						q.add(new int[] { fz, nx, ny });
					}
				}
			}
			// Ramps join two floors at the tile edge, both directions.
			Tile here = w.getTile(x, y, z);
			if (here.getType() == Tile.TileType.TYPE_RAMPUP && z + 1 < LVLS) {
				int u = here.getRampUphill();
				int ex = x + Tile.dirDx(u), ey = y + Tile.dirDy(u);
				if (w.isValid(ex, ey, z + 1) && w.getTile(ex, ey, z + 1).isWalkable()
						&& !seen[z + 1][ex][ey]) {
					seen[z + 1][ex][ey] = true;
					q.add(new int[] { z + 1, ex, ey });
				}
			}
			if (here.getType() == Tile.TileType.TYPE_RAMPDOWN && z - 1 >= 0) {
				int u = here.getRampUphill();
				int ex = x - Tile.dirDx(u), ey = y - Tile.dirDy(u);
				if (w.isValid(ex, ey, z - 1) && w.getTile(ex, ey, z - 1).isWalkable()
						&& !seen[z - 1][ex][ey]) {
					seen[z - 1][ex][ey] = true;
					q.add(new int[] { z - 1, ex, ey });
				}
			}
		}
		return seen;
	}

	// ---- drawing helpers ----------------------------------------------------

	/** Set a tile bare: the type, and no fertility — nothing authored here
	 *  grows anything unless it says so itself. */
	private static void set(World w, int x, int y, int z, Tile.TileType t) {
		w.setTile(x, y, z, t);
		w.getTile(x, y, z).setFertility(0);
	}

	private static void fill(World w, int z, int x0, int y0, int x1, int y1,
			Tile.TileType t) {
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				set(w, x, y, z, t);
			}
		}
	}

	/** A walled room: floor inside, wall on the perimeter. */
	private static void shell(World w, int z, int x0, int y0, int x1, int y1,
			Tile.TileType wall, Tile.TileType floor) {
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				boolean rim = x == x0 || y == y0 || x == x1 || y == y1;
				set(w, x, y, z, rim ? wall : floor);
			}
		}
	}

	/** Annulus around the test chamber's centre. */
	private static void ring(World w, int z, double r0, double r1, Tile.TileType t) {
		ringAt(w, z, CHAMBER_X, CHAMBER_Y, r0, r1, t);
	}

	private static void disk(World w, int z, double r, Tile.TileType t) {
		diskAt(w, z, CHAMBER_X, CHAMBER_Y, r, t);
	}

	private static void ringAt(World w, int z, double cx, double cy,
			double r0, double r1, Tile.TileType t) {
		for (int x = (int) (cx - r1 - 1); x <= (int) (cx + r1 + 1); x++) {
			for (int y = (int) (cy - r1 - 1); y <= (int) (cy + r1 + 1); y++) {
				double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
				if (d > r0 && d <= r1) {
					set(w, x, y, z, t);
				}
			}
		}
	}

	private static void diskAt(World w, int z, double cx, double cy,
			double r, Tile.TileType t) {
		for (int x = (int) (cx - r - 1); x <= (int) (cx + r + 1); x++) {
			for (int y = (int) (cy - r - 1); y <= (int) (cy + r + 1); y++) {
				if (Math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= r) {
					set(w, x, y, z, t);
				}
			}
		}
	}

	/**
	 * A two-way stair from {@code zTop} to the floor below, four tiles in a
	 * row along direction {@code dir} starting at (x, y) — the same pattern
	 * the demo world's stairwells use: a hole to fall through, a descending
	 * ramp beside it so the fall is not the only way, a landing below, and a
	 * climbing ramp back up. Stamp it after the rooms it lives in, so the
	 * landings overwrite floor rather than rock.
	 */
	private static void stair(World w, int zTop, int x, int y, int dir) {
		int ax = Tile.dirDx(dir), ay = Tile.dirDy(dir);
		set(w, x, y, zTop, Tile.TileType.TYPE_HOLE);
		set(w, x + ax, y + ay, zTop, Tile.TileType.TYPE_RAMPDOWN);
		w.getTile(x + ax, y + ay, zTop).setRampUphill(dir);
		set(w, x + 3 * ax, y + 3 * ay, zTop, Tile.TileType.TYPE_PLATE);
		set(w, x, y, zTop - 1, Tile.TileType.TYPE_PLATE);
		set(w, x + 2 * ax, y + 2 * ay, zTop - 1, Tile.TileType.TYPE_RAMPUP);
		w.getTile(x + 2 * ax, y + 2 * ay, zTop - 1).setRampUphill(dir);
	}
}
