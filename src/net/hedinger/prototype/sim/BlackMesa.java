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
 *   z=3  SURFACE   desert, canyon + dam, topside compound, silo doors, the
 *                  launch pad's rocket tip, tram
 *   z=2  LABS      Sector C labs + test chamber top, office complex, dorm
 *                  station, Sector E specimen labs
 *   z=1  WORKS     the transit loop, silo mid, the old freight line out to
 *                  the launch gantry, residue processing, warehouse + cold
 *                  storage, reactor complex entry
 *   z=0  DEEP      test chamber floor, engine bay, launch flame trench,
 *                  waste sump, reactor core + teleport chamber
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

	public static World build(long seed) {
		Utils.seed(seed);
		World w = new World(COLS, ROWS, LVLS);

		// Underground floors start as solid rock; the surface as desert.
		for (int z = DEEP; z <= LABS; z++) {
			fill(w, z, 0, 0, COLS - 1, ROWS - 1, Tile.TileType.TYPE_WALL);
		}
		surface(w);

		labsFloor(w);
		worksFloor(w);
		deepFloor(w);
		stairs(w);

		shallowsFringe(w);
		return w;
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
					t = Tile.TileType.TYPE_WALL; // rim + buttes
				} else if (elev > 0.82) {
					t = Tile.TileType.TYPE_ROCKY;
				} else if (detail < 0.28) {
					t = Tile.TileType.TYPE_RUBBLE;
				} else if (detail > 0.93) {
					t = Tile.TileType.TYPE_COVER; // desert scrub
				} else {
					t = Tile.TileType.TYPE_SAND;
				}
				set(w, x, y, SURFACE, t);
			}
		}

		riverCanyonAndDam(w);
		topsideCompound(w);
		dormitories(w);
		sectorCHead(w);
		siloDoors(w);
		launchDoors(w);
		topsideTram(w);
	}

	/** The east canyon: reservoir at the top, the dam across it, the river
	 *  below — and the one green ribbon in the desert, on the wet banks. */
	private static void riverCanyonAndDam(World w) {
		// Canyon walls, full height. Gapped twice further south so the wet
		// valley floor is a place, not a diorama: once from the west desert,
		// once from the east strip.
		fill(w, SURFACE, 149, 2, 149, ROWS - 3, Tile.TileType.TYPE_WALL);
		fill(w, SURFACE, 161, 2, 161, ROWS - 3, Tile.TileType.TYPE_WALL);
		// Reservoir behind the dam.
		fill(w, SURFACE, 150, 2, 160, 20, Tile.TileType.TYPE_WATER);
		// The dam: a concrete wall with a paved crest walkway on the dry side.
		// The crest runs THROUGH both canyon walls: it is the one bridge over
		// the canyon, which is also the only way onto the east bank.
		fill(w, SURFACE, 150, 21, 160, 22, Tile.TileType.TYPE_WALL_CONCRETE);
		fill(w, SURFACE, 149, 23, 161, 23, Tile.TileType.TYPE_PAVED);
		// The river, and the banks it waters.
		fill(w, SURFACE, 154, 24, 156, ROWS - 3, Tile.TileType.TYPE_WATER);
		for (int y = 24; y <= ROWS - 3; y++) {
			for (int x : new int[] { 150, 151, 152, 153, 157, 158, 159, 160 }) {
				if (y >= 28 && y <= 100) {
					w.setTile(x, y, SURFACE, Tile.TileType.TYPE_FLOOR);
					w.getTile(x, y, SURFACE).setFertility(0.45);
				} else {
					set(w, x, y, SURFACE, Tile.TileType.TYPE_SAND);
				}
			}
		}
		// The ways down into the valley.
		w.setTile(149, 60, SURFACE, Tile.TileType.TYPE_FLOOR);
		w.getTile(149, 60, SURFACE).setFertility(0.45);
		w.setTile(161, 60, SURFACE, Tile.TileType.TYPE_FLOOR);
		w.getTile(161, 60, SURFACE).setFertility(0.45);
	}

	/** The topside motorpool: a fenced yard of barracks, garage, armory and a
	 *  helipad — the surface compound everything else is signed from. */
	private static void topsideCompound(World w) {
		shell(w, SURFACE, 18, 12, 48, 34,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PAVED);
		fill(w, SURFACE, 32, 34, 34, 34, Tile.TileType.TYPE_PAVED); // the gate
		// Barracks.
		shell(w, SURFACE, 20, 14, 30, 20,
				Tile.TileType.TYPE_WALL_BUILT, Tile.TileType.TYPE_PLATE);
		set(w, 25, 20, SURFACE, Tile.TileType.TYPE_PLATE);
		// Garage.
		shell(w, SURFACE, 36, 14, 46, 20,
				Tile.TileType.TYPE_WALL_BUILT, Tile.TileType.TYPE_TREADPLATE);
		set(w, 41, 20, SURFACE, Tile.TileType.TYPE_TREADPLATE);
		// Armory: the one steel box on the surface.
		shell(w, SURFACE, 20, 26, 25, 31,
				Tile.TileType.TYPE_WALL_STEEL, Tile.TileType.TYPE_PLATE);
		set(w, 25, 28, SURFACE, Tile.TileType.TYPE_PLATE);
		// Helipad.
		fill(w, SURFACE, 36, 26, 42, 32, Tile.TileType.TYPE_TREADPLATE);

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
		fill(w, SURFACE, 118, 70, 127, 79, Tile.TileType.TYPE_PLATE);
		fill(w, SURFACE, 121, 73, 124, 76, Tile.TileType.TYPE_SHAFT);
	}

	/** The launch pad from above: an apron around the flame-trench gap, and
	 *  the rocket's tip standing in the middle of it. The rocket is a steel
	 *  column through all four floors — the one thing on the map that is
	 *  taller than the mesa. */
	private static void launchDoors(World w) {
		fill(w, SURFACE, 113, 83, 126, 93, Tile.TileType.TYPE_PLATE);
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
		// The rail between them, through the portal walls.
		fill(w, SURFACE, 51, 24, 140, 24, Tile.TileType.TYPE_RAIL);
		fill(w, SURFACE, 140, 24, 140, 64, Tile.TileType.TYPE_RAIL);
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
		fill(w, LABS, 58, 51, 60, 51, Tile.TileType.TYPE_SERVER); // reception
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

		// Control room, its instrument wall facing the chamber.
		fill(w, LABS, 87, 46, 92, 58, Tile.TileType.TYPE_PLATE);
		fill(w, LABS, 91, 48, 91, 56, Tile.TileType.TYPE_SERVER);
		set(w, 86, 52, LABS, Tile.TileType.TYPE_PAVED); // from the spine

		// The test chamber: shell, catwalk gallery, and the bore.
		chamberRing(w, LABS);
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
				Tile.TileType.TYPE_SERVER, Tile.TileType.TYPE_FUNGUS);
		for (int x = 115; x <= 119; x++) {
			for (int y = 41; y <= 45; y++) {
				w.getTile(x, y, LABS).setFertility(0.5);
				w.getTile(x, y, LABS).setRegrowRate(0.002);
			}
		}
		shell(w, LABS, 122, 44 - 4, 128, 46,
				Tile.TileType.TYPE_SERVER, Tile.TileType.TYPE_REEDS);
		set(w, 125, 42, LABS, Tile.TileType.TYPE_SHALLOWS);
		set(w, 125, 43, LABS, Tile.TileType.TYPE_SHALLOWS);
		shell(w, LABS, 130, 40, 136, 46,
				Tile.TileType.TYPE_SERVER, Tile.TileType.TYPE_COVER);
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

		// Platform under portal A, straddling the north run.
		fill(w, WORKS, 42, 16, 54, 24, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 43, 18, 53, 18, Tile.TileType.TYPE_RAIL);

		// Platform under the dormitories — the far end of the commute.
		fill(w, WORKS, 60, 16, 70, 24, Tile.TileType.TYPE_PLATE);
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
		// Doorways: the corridor into power, and each room onto the gallery.
		set(w, 130, 68, WORKS, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 130, 73, 130, 75, Tile.TileType.TYPE_PLATE); // power <-> control
		fill(w, WORKS, 125, 71, 127, 71, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 118, 71, 120, 71, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 118, 77, 120, 77, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 119, 72, 119, 77, Tile.TileType.TYPE_PLATE);
	}

	/**
	 * The old freight line: a second, older rail system, distinct from the
	 * transit loop on purpose — its tunnel is broken rubble where the loop's
	 * is dressed stone. It branches off past the silo complex and runs out to
	 * the launch gantry, where the rocket stands in its flame-trench gap with
	 * the gantry arms reaching it.
	 */
	private static void freightLine(World w) {
		// The branch south from the east platform, then west to the gantry.
		fill(w, WORKS, 141, 71, 143, 88, Tile.TileType.TYPE_RUBBLE);
		fill(w, WORKS, 120, 86, 143, 88, Tile.TileType.TYPE_RUBBLE);
		// The gantry hall.
		shell(w, WORKS, 112, 82, 126, 94,
				Tile.TileType.TYPE_WALL_CONCRETE, Tile.TileType.TYPE_PLATE);
		fill(w, WORKS, 115, 84, 122, 91, Tile.TileType.TYPE_CATWALK);
		launchThroat(w, WORKS);
		// The gantry arms, reaching the rocket across the gap.
		fill(w, WORKS, 116, 87, 117, 88, Tile.TileType.TYPE_CATWALK);
		fill(w, WORKS, 120, 87, 121, 88, Tile.TileType.TYPE_CATWALK);
		// The rail itself, laid last so it cuts through wall and rubble alike.
		fill(w, WORKS, 142, 66, 142, 87, Tile.TileType.TYPE_RAIL);
		fill(w, WORKS, 123, 87, 142, 87, Tile.TileType.TYPE_RAIL);
	}

	/** Residue processing: the sludge channels, catwalks over them, and the
	 *  conveyor line along the top. */
	private static void residueProcessing(World w) {
		fill(w, WORKS, 64, 83, 100, 94, Tile.TileType.TYPE_STONE);
		fill(w, WORKS, 66, 84, 98, 84, Tile.TileType.TYPE_TREADPLATE);
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
		engineBay(w);
		launchTrench(w);
		wasteSump(w);
		lambdaCore(w);
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
		// The coolant loop: a closed ring, two orthogonal neighbours per tile.
		fill(w, DEEP, 14, 18, 28, 18, Tile.TileType.TYPE_COOLANT);
		fill(w, DEEP, 14, 38, 28, 38, Tile.TileType.TYPE_COOLANT);
		fill(w, DEEP, 14, 18, 14, 38, Tile.TileType.TYPE_COOLANT);
		fill(w, DEEP, 28, 18, 28, 38, Tile.TileType.TYPE_COOLANT);
		// The core.
		fill(w, DEEP, 19, 26, 23, 30, Tile.TileType.TYPE_EXCHANGER);
		set(w, 21, 28, DEEP, Tile.TileType.TYPE_CRYSTAL);
		// Catwalk aisles across the loop to the core.
		fill(w, DEEP, 15, 28, 18, 28, Tile.TileType.TYPE_CATWALK);
		fill(w, DEEP, 24, 28, 27, 28, Tile.TileType.TYPE_CATWALK);
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

	/** One floor of the silo bore: shaft all the way through, with a catwalk
	 *  rim to stand on. */
	private static void siloBore(World w, int z) {
		fill(w, z, 120, 72, 125, 77, Tile.TileType.TYPE_CATWALK);
		fill(w, z, 121, 73, 124, 76, Tile.TileType.TYPE_SHAFT);
	}

	/** Every shore-touching water tile becomes a wading fringe, exactly as the
	 *  demo world does it, so the reservoir and river have soft edges. */
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
