package net.hedinger.prototype.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.hedinger.prototype.engine.PheromoneCloud;
import net.hedinger.prototype.entities.AgentIO;
import net.hedinger.prototype.entities.Brain;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.simtest.TestNPC;

/**
 * The world's rules, written down by reading the rules themselves.
 *
 * <p>Every number this class publishes is either a live constant of the running
 * simulation or a figure <em>computed</em> from those constants by the same
 * arithmetic the simulation uses. Nothing here is transcribed. That is the whole
 * point: a hand-written page saying "a reference creature lasts six minutes"
 * becomes a lie the moment somebody tunes {@link NPC#BASE_METABOLISM}, and
 * nothing fails when it does. A page that divides the tank by the burn rate
 * cannot be wrong, only out of date by one deploy.
 *
 * <p>The prose is the exception — it explains <em>why</em> a rule is shaped the
 * way it is, which no constant can be read off — so it is deliberately written
 * to describe relationships ("burn grows with mass^0.75") rather than values
 * ("a size-12 creature burns 0.0007"). Values live in the tables.
 *
 * <p>Served as JSON at {@code /help/mechanics.json} and rendered by the help
 * page.
 */
public final class Mechanics {

	private Mechanics() {
	}

	/** Ticks the world runs per wall-clock second. */
	private static final double TPS = SimulationRunner.TICKS_PER_SECOND;

	/** Body sizes the worked tables step through: the genome's floor, the model's
	 *  reference body, and the genome's ceiling, with two stops between. */
	private static final double[] SAMPLE_SIZES = {
		Genome.SIZE_MIN, NPC.REF_SIZE, 12, 16, Genome.SIZE_MAX,
	};

	/** Speeds the movement table steps through, in tiles/tick. The founder speed
	 *  (0.05) is the anchor the movement price was set against. */
	private static final double[] SAMPLE_SPEEDS = {0.01, 0.025, 0.05, 0.1, 0.2, Genome.SPEED_MAX};

	/** The documented sections, in reading order. */
	public static List<Map<String, Object>> sections() {
		List<Map<String, Object>> out = new ArrayList<>();
		out.add(time());
		out.add(mass());
		out.add(tank());
		out.add(resting());
		out.add(moving());
		out.add(food());
		out.add(growth());
		out.add(breeding());
		out.add(thirst());
		out.add(carrying());
		out.add(senses());
		out.add(acts());
		out.add(intents());
		out.add(pheromones());
		out.add(minds());
		return out;
	}

	// --- the sections ---------------------------------------------------------

	private static Map<String, Object> time() {
		Map<String, Object> s = section("time", "Time",
				"The world runs on ticks, not seconds. Every rate below is per tick, because a "
				+ "tick is the unit the simulation actually steps in — seconds are a convenience "
				+ "for the reader and are derived, never stored. The sim is deterministic: the "
				+ "same seed and the same command log replay the same world, tick for tick, "
				+ "which is only true because nothing in it is priced in wall-clock time.");
		rows(s,
				row("Tick rate", num(TPS), "ticks/s", "The simulation's step."),
				row("A second", num(TPS), "ticks", ""),
				row("A minute", num(TPS * 60), "ticks", ""),
				row("Tick budget", round(1000.0 / TPS, 1), "ms", "What one step has to fit in."));
		return s;
	}

	private static Map<String, Object> mass() {
		Map<String, Object> s = section("mass", "Body size, and the mass it implies",
				"One number drives every energy scale: body mass, expressed as a factor "
				+ "normalised so a reference-size body is exactly 1.0. A genome carries a size; "
				+ "mass is that size over the reference. Tank, burn, movement price, what a "
				+ "carcass is worth and how long it takes to rot are all this factor raised to "
				+ "some power, which is why tuning one constant moves the whole economy "
				+ "coherently instead of leaving parts of it behind.");
		rows(s,
				row("Reference size", num(NPC.REF_SIZE), "", "Mass factor 1.0 here."),
				row("Smallest genome", num(Genome.SIZE_MIN), "",
						"mass " + round(Genome.SIZE_MIN / NPC.REF_SIZE, 2)),
				row("Largest genome", num(Genome.SIZE_MAX), "",
						"mass " + round(Genome.SIZE_MAX / NPC.REF_SIZE, 2)),
				row("Mass", "size ÷ " + num(NPC.REF_SIZE), "", "Linear in size."));
		return s;
	}

	private static Map<String, Object> tank() {
		Map<String, Object> s = section("tank", "The energy tank",
				"Energy is the ACTION budget — what a body can currently do, not how hungry it "
				+ "is (hunger is its own book; see the needs). The tank's ceiling grows in "
				+ "proportion to mass, anchored on the body a creature is growing INTO, so a "
				+ "juvenile is not economically punished for being young. Food never fills the "
				+ "tank directly: eating fills the stomach, and the body converts satiation "
				+ "into energy over time, scaled by how healthy it is — an unhealthy body is "
				+ "also a listless one. An empty tank is COLLAPSE, never death: below the crawl "
				+ "reserve a body can only crawl — no biting, grabbing, breeding, or holding a "
				+ "captive — and it recovers the moment food and water let regeneration run. "
				+ "Only health decides death.");
		rows(s,
				row("Full tank", num(NPC.BASE_CAPACITY) + " × mass", "energy",
						"Reference body: " + round(NPC.BASE_CAPACITY, 2)),
				row("Born holding", pct(TestNPC.BORN_FRACTION), "of the tank",
						"Fed, but under the breeding line."),
				row("Regenerates", num(NPC.REGEN_RATE)
						+ " × mass^0.75 × efficiency × satiation × vigor", "energy/tick",
						"satiation = 1 − the worse of hunger and thirst; vigor = health/100."),
				row("Crawl reserve", pct(NPC.CRAWL_RESERVE), "of the tank",
						"Below it: collapse. A crawl at " + pct(NPC.CRAWL_SPEED)
						+ " of top speed, and nothing else."),
				row("Empty tank", "collapse, not death", "",
						"Recoverable; health is the only gate to dying."));
		return s;
	}

	private static Map<String, Object> resting() {
		Map<String, Object> s = section("metabolism", "Resting metabolism",
				"Standing perfectly still is not free. A body burns energy every tick just by "
				+ "being a body, and that burn follows Kleiber's law — it grows with mass to the "
				+ "power 0.75, not with mass itself. A big animal therefore burns more in "
				+ "absolute terms but less per unit of mass, which is why its proportionally "
				+ "larger tank outlasts a small animal's: large bodies fast longer. On top of "
				+ "the size term sits a heritable efficiency multiplier from the genome, "
				+ "normalised so an average genome burns exactly the size-based rate and "
				+ "mutation nudges it either way.");
		rows(s,
				row("Resting burn", num(NPC.BASE_METABOLISM) + " × mass^0.75 × efficiency",
						"energy/tick", ""),
				row("Reference burn", sci(NPC.BASE_METABOLISM), "energy/tick",
						"At mass 1.0, average genome."),
				row("Efficiency", "genome metabolism ÷ " + num(NPC.META_REF), "",
						"A multiplier: 1.0 for an average burner, and heritable."));
		List<List<String>> t = new ArrayList<>();
		for (double size : SAMPLE_SIZES) {
			double m = size / NPC.REF_SIZE;
			double cap = NPC.BASE_CAPACITY * m;
			double burn = NPC.BASE_METABOLISM * Math.pow(m, 0.75);
			double ticks = cap / burn;
			t.add(List.of(num(size), round(m, 2), round(cap, 2), sci(burn),
					num(Math.round(ticks)), round(ticks / TPS / 60, 1)));
		}
		table(s, "What a full tank buys a motionless creature — the tank grows linearly with "
				+ "mass while the burn grows with mass^0.75, so the fasting window widens as "
				+ "bodies get bigger.",
				List.of("Size", "Mass", "Full tank", "Burn/tick", "Ticks unfed", "Minutes unfed"),
				t);
		return s;
	}

	private static Map<String, Object> moving() {
		Map<String, Object> s = section("movement", "The price of moving",
				"Movement is priced kinetically: a body pays the SQUARE of the ground it "
				+ "covered in a tick. That makes speed genuinely expensive rather than merely "
				+ "proportional — doubling pace quadruples the bill — so being fast is a real "
				+ "commitment a lineage has to earn its living to afford, and a lineage that "
				+ "makes its living by travelling can instead evolve to be cheap over distance "
				+ "without being quick. The two are different adaptations and the pricing keeps "
				+ "them different.\n\n"
				+ "It is charged on ground actually covered, not on intent: a step cancelled by "
				+ "a wall moved nothing and costs nothing. And it bills the whole load, because "
				+ "anything a creature is hauling is simply extra mass.");
		rows(s,
				row("Movement cost", num(NPC.MOVE_ENERGY)
						+ " × efficiency × (mass + load) × step²", "energy/tick", ""),
				row("Fastest genome", num(Genome.SPEED_MAX), "tiles/tick",
						round(Genome.SPEED_MAX * TPS, 1) + " tiles/s"),
				row("Standing still", "0", "energy/tick",
						"Even under a load — weight is billed by the step."));
		List<List<String>> t = new ArrayList<>();
		double rest = NPC.BASE_METABOLISM;
		for (double v : SAMPLE_SPEEDS) {
			double cost = NPC.MOVE_ENERGY * 1.0 * v * v;
			t.add(List.of(round(v, 3), round(v * TPS, 2), sci(cost), round(cost / rest, 2) + "×",
					round(NPC.BASE_CAPACITY / (rest + cost) / TPS / 60, 1)));
		}
		table(s, "A reference-mass creature carrying nothing, at each pace. The break-even — "
				+ "where moving costs as much again as merely existing — sits around the founder "
				+ "speed of 0.05; past that, travel dominates the budget entirely.",
				List.of("Speed (tiles/tick)", "Tiles/s", "Move cost/tick", "× resting burn",
						"Minutes on a full tank"),
				t);
		return s;
	}

	private static Map<String, Object> food() {
		double refBody = TestNPC.MEAT_ENERGY;
		Map<String, Object> s = section("food", "What food is worth",
				"Three ways to make a living, priced very differently. Grass is abundant, "
				+ "everywhere, and poor. Meat is concentrated and scarce, and a carcass is worth "
				+ "exactly what the living body was worth — a scavenger that finds a corpse gets "
				+ "the same energy from it a hunter would have got from the kill, because the "
				+ "meal is the same meal; only the way of getting it differs, and the world "
				+ "should not quietly tax one strategy for being patient rather than violent.\n\n"
				+ "A meal's worth scales with the eaten body's mass, not the eater's, so a big "
				+ "carcass is a big meal for whoever finds it. Corpses are eaten in bites over "
				+ "many ticks rather than swallowed whole, which is what makes a carcass a place "
				+ "creatures gather at instead of an instant.");
		rows(s,
				row("Grass", num(NPC.GRASS_ENERGY), "energy per unit grazed",
						"Poor, but it does not run away."),
				row("A whole carcass", num(TestNPC.MEAT_ENERGY) + " × the dead body's mass",
						"energy", "Reference-mass body: " + round(refBody, 2)),
				row("Carrion", TestNPC.CARRION_ENERGY == TestNPC.MEAT_ENERGY
						? "the same as a kill" : num(TestNPC.CARRION_ENERGY), "energy",
						"Found or killed, a body is worth what it is worth."),
				row("A bite of carrion", pct(TestNPC.CARRION_BITE), "of the body per tick",
						"~" + Math.round(1 / TestNPC.CARRION_BITE) + " ticks ("
						+ round(1 / TestNPC.CARRION_BITE / TPS, 1) + " s) to strip it clean."),
				row("Scent range for corpses", num(TestNPC.CARRION_SCENT_R), "tiles",
						"Smelled, not seen — it reaches through walls that sight does not."),
				row("Hunt given up after", num(TestNPC.HUNT_GIVEUP_TICKS), "ticks",
						"A chase that is going nowhere is abandoned."));
		List<List<String>> t = new ArrayList<>();
		for (double size : SAMPLE_SIZES) {
			double m = size / NPC.REF_SIZE;
			double meal = TestNPC.MEAT_ENERGY * m;
			t.add(List.of(num(size), round(m, 2), round(meal, 2),
					round(meal / NPC.GRASS_ENERGY, 1),
					num(NPC.growthTicks(size)), round(NPC.growthTicks(size) / TPS, 1)));
		}
		table(s, "What a body is worth once it stops moving, and how long it stays worth it — "
				+ "a carcass takes as long to return to the world as the body took to build.",
				List.of("Size", "Mass", "Whole carcass", "Grazes' worth", "Rots in (ticks)",
						"Rots in (s)"),
				t);
		return s;
	}

	private static Map<String, Object> growth() {
		Map<String, Object> s = section("growth", "Growing up",
				"Creatures are born a fraction of their adult body and grow into it at a FIXED "
				+ "rate of radius per tick. Because the rate is fixed and the distance is not, "
				+ "childhood length scales with how big the adult body is: a small grazer is "
				+ "grown in seconds, the largest body the genome can express takes the longest "
				+ "childhood the world can produce. Rot is pinned to the same figure, so a body "
				+ "takes as long to return to the world as it took to build — one constant, not "
				+ "two that can drift apart.");
		rows(s,
				row("Born at", pct(NPC.BIRTH_SIZE_FRACTION), "of the adult body", ""),
				row("Growth", num(NPC.GROWTH_RATE), "size/tick", "Fixed, whatever the body."),
				row("Childhood", "(1 − " + num(NPC.BIRTH_SIZE_FRACTION) + ") × adult size ÷ "
						+ num(NPC.GROWTH_RATE), "ticks", "Also how long the corpse lasts."));
		List<List<String>> t = new ArrayList<>();
		for (double size : SAMPLE_SIZES) {
			int ticks = NPC.growthTicks(size);
			t.add(List.of(num(size), round(size * NPC.BIRTH_SIZE_FRACTION, 1), num(ticks),
					round(ticks / TPS, 1)));
		}
		table(s, "How long it takes to become an adult, by the adult you are becoming.",
				List.of("Adult size", "Born at", "Childhood (ticks)", "Childhood (s)"), t);
		return s;
	}

	private static Map<String, Object> breeding() {
		Map<String, Object> s = section("breeding", "Breeding",
				"Reproduction is a surplus signal across all four books, not an energy "
				+ "checkout. It needs energy banked past a threshold that scales with mass — a "
				+ "big creature must eat MORE than a small one before it can breed — AND both "
				+ "needs low AND sound health: a parched, starving or badly wounded body does "
				+ "not court. Budding is a held act: the commitment must be sustained for "
				+ "seconds before the child arrives, and breaking off (fleeing, doing anything "
				+ "else) resets it — the cost is paid on completion, not intent. The cooldown "
				+ "scales with the childhood the offspring itself will spend growing, so big "
				+ "slow-growing bodies are also slow breeders.");
		rows(s,
				row("Breeds above", pct(TestNPC.REPRO_FRACTION), "of the tank",
						"Reference body: " + round(TestNPC.REPRO_FRACTION * NPC.BASE_CAPACITY, 2)),
				row("And only while", "hunger and thirst are under " + pct(NPC.NEED_LOW)
						+ ", health at 60+", "", "Surplus across all four books."),
				row("Costs", pct(TestNPC.REPRO_COST_FRACTION), "of the tank, each parent",
						"Reference body: "
						+ round(TestNPC.REPRO_COST_FRACTION * NPC.BASE_CAPACITY, 2)),
				row("Offspring starts at", pct(TestNPC.BORN_FRACTION), "of ITS OWN tank",
						"Sized on the body it will grow into."),
				row("Budding takes", num(NPC.BREED_HOLD_TICKS), "held ticks",
						round(NPC.BREED_HOLD_TICKS / TPS, 1) + " s of commitment; interruptible."),
				row("Cooldown", "half the offspring's childhood, at least "
						+ num(NPC.REPRO_COOLDOWN) + " ticks", "",
						"Reference body: ~" + num(NPC.growthTicks(NPC.REF_SIZE) / 2) + " ticks ("
						+ round(NPC.growthTicks(NPC.REF_SIZE) / 2 / TPS, 1) + " s)."),
				row("Mating takes", num(TestNPC.MATING_TICKS), "ticks",
						round(TestNPC.MATING_TICKS / TPS, 1) + " s, both parents occupied."));
		return s;
	}

	private static Map<String, Object> thirst() {
		Map<String, Object> s = section("needs", "The needs: hunger and thirst",
				"Two clocks that rise on their own and fall only by acting. Thirst runs at "
				+ "twice hunger's pace — the one rhythm anchor — so a body sated and slaked at "
				+ "the same instant wants water in half the time it takes to want food, and a "
				+ "sated hunter's appetite returns in twice the time its thirst does. Both "
				+ "clocks stretch with mass^0.25 (big bodies cycle slower, Kleiber again) and "
				+ "run faster for hot metabolisms.\n\n"
				+ "Eating and drinking are rates held over ticks, never instant refills: a "
				+ "drink is seconds of standing at a shore, a meal is longer, and walking away "
				+ "mid-act keeps exactly the partial refill so far. The needs are what drive "
				+ "behaviour — a predator hunts by appetite, not tank headroom — and what feed "
				+ "the other books: satiation powers energy regeneration, and a need pegged "
				+ "near its ceiling erodes health with its cause attached, so the corpse still "
				+ "says what killed it. Creatures route to water through passable ground (a "
				+ "flood that only spreads where they could walk), so a shore behind a wall is "
				+ "correctly understood as far away.");
		rows(s,
				row("Thirst, slaked → parched", num((long) NPC.THIRST_PERIOD), "ticks",
						round(NPC.THIRST_PERIOD / TPS / 60, 1) + " min at the reference body."),
				row("Hunger, sated → starving", num((long) NPC.HUNGER_PERIOD), "ticks",
						"Twice thirst's period — the rhythm anchor."),
				row("A full drink", num((long) NPC.DRINK_TICKS), "ticks at water",
						round(NPC.DRINK_TICKS / TPS, 1) + " s; any water/shallows in the 3×3."),
				row("The stomach", num(NPC.STOMACH) + " × mass", "food units",
						"A meal is what fits; a sated body strips no ground."),
				row("Hunter hunts above", num(TestNPC.PRED_HUNT_HUNGER), "hunger",
						"Appetite, not tank headroom, starts the chase."),
				row("Cannibalism above", num(TestNPC.STARVE_HUNGER), "hunger",
						"Desperation lifts the taboo, and only desperation."),
				row("A pegged need (" + pct(NPC.DEPRIVED) + "+)", "erodes health", "",
						"1 point per " + num(NPC.DEPRIVATION_PERIOD) + " ticks — minutes to "
						+ "kill, so rescue by a meal or a shore stays possible."),
				row("Both needs under " + pct(NPC.NEED_LOW), "mend health", "",
						"1 point per ~" + num(NPC.MEND_PERIOD) + " ticks at average "
						+ "metabolism; wounds close over minutes of fed, watered living."));
		return s;
	}

	private static Map<String, Object> carrying() {
		Map<String, Object> s = section("carrying", "Carrying, and being carried",
				"A creature on another creature's back is either a passenger or a prisoner, and "
				+ "the difference is paid for. A voluntary rider clings on by its own effort and "
				+ "costs its carrier nothing beyond the weight — and saves on its own burn, "
				+ "since it is being carried rather than walking. Restraining an unwilling "
				+ "captive is work in its own right, charged whether or not the captor moves, so "
				+ "holding somebody is an effort that has to keep being paid rather than a free "
				+ "permanent state: a captor must eventually either eat its captive or let go. "
				+ "A captive that actively fights costs more than one hanging limp.\n\n"
				+ "The weight itself is billed through movement, as extra mass, so hauling is "
				+ "expensive exactly in proportion to how far and how fast you haul.");
		rows(s,
				row("Grip on a captive", num(NPC.GRIP_ENERGY), "energy/tick per unit held",
						"Paid standing still; restraint is the cost, not the weight."),
				row("A struggling captive", num(NPC.STRUGGLE_CARRIER_COST), "energy/tick",
						"Fighting back costs the captor more than going limp."),
				row("A rider's own burn", pct(NPC.RIDER_METABOLISM), "of normal",
						"It is being carried, not walking."),
				row("Flying with a load", num(NPC.FLIER_CARRY_MULTIPLIER) + "×", "the weight",
						"Lift is dear; a flier feels every gram."),
				row("The load itself", "billed as extra mass through movement", "",
						"Standing still under a load is nearly free."));
		return s;
	}

	// --- what a creature can perceive and do ----------------------------------
	// These sections describe a LIST rather than a quantity, so they are kept
	// honest differently: the names come out of AgentIO's own arrays, and a test
	// demands every channel appear exactly once. Add a sensor without writing it
	// up and the page stops building a complete picture -- so the build stops too.

	private static Map<String, Object> senses() {
		Map<String, Object> s = section("senses", "What a creature can sense",
				"A mind never touches the world. Between the two sits a fixed contract: the "
				+ "body fills a vector of numbers from what it can perceive, the mind reads "
				+ "that and writes back a vector of intent, and nothing else passes between "
				+ "them. That seam is why any decision method at all — an evolved program, a "
				+ "neural net, a hand-written controller — is interchangeable, and why a policy "
				+ "that works in one body works in another.\n\n"
				+ "Every channel is egocentric and bounded. Bearings are relative to the way "
				+ "the creature is facing, distances arrive as proximity (1 when you are on top "
				+ "of it, falling toward 0 as it recedes) rather than raw tiles, and magnitudes "
				+ "are squashed into roughly −1..1. A mind therefore reads \"something big, "
				+ "slightly to my left, close\" in exactly the same numbers whether it is a "
				+ "sprat or a leviathan — which is what lets one evolved policy survive a "
				+ "lineage growing.");
		rows(s,
				row("Channels", num(AgentIO.NUM_SENSORS), "", "All filled every tick."),
				row("Sight range", num(new Genome().losRange), "tiles",
						"A gene; it mutates, so lineages evolve keener or dimmer eyes."),
				row("Field of view", round(Math.toDegrees(new Genome().losFov), 0) + "°", "",
						"Also a gene. Sight is facing-gated; smell is not."),
				row("Kin recognition", num(Genome.MARKER_DIMS) + " heritable markers", "",
						"Similarity, not species: 1 is identical, 0 maximally distant."),
				row("Bearings", "−1..1", "of π",
						"Relative to the heading, so 0 is dead ahead."),
				row("Distances", "1 ÷ (1 + tiles)", "",
						"Proximity, not range: near things are loud, far ones fade."));
		groups(s,
				group("Its own condition",
						sense(AgentIO.S_ENERGY, "How full the action budget is, against THIS "
								+ "body's own capacity — what it can currently DO, which is a "
								+ "different fact from how hungry it is."),
						sense(AgentIO.S_HEALTH, "How hurt it is. A wounded creature can behave "
								+ "differently from a whole one."),
						sense(AgentIO.S_HUNGER, "How empty it is, 0 sated to 1 starving — the "
								+ "need for food, sibling of thirst and distinct from energy."),
						sense(AgentIO.S_THIRST, "How dry it is, 0 slaked to 1 parched."),
						sense(AgentIO.S_CARRIED, "+1 held captive, −1 riding willingly, 0 free."),
						sense(AgentIO.S_CLOCK, "A slow oscillator from tick and identity. The "
								+ "world is deterministic and a mind draws no dice, so this is "
								+ "where rhythm and wandering have to come from."),
						sense(AgentIO.S_BIAS, "A constant 1.0, so a policy can build its own "
								+ "thresholds out of arithmetic.")),
				group("The ground underfoot",
						sense(AgentIO.S_FOOD, "Vegetation on the tile it is standing on."),
						sense(AgentIO.S_PHERO, "Pheromone concentration where it stands — see "
								+ "the section below."),
						sense(AgentIO.S_BLOCKED, "Whether the tile straight ahead is solid."),
						sense(AgentIO.S_WHISKER_L, "Whether the tile 45° to the left is solid."),
						sense(AgentIO.S_WHISKER_R, "The same to the right. The pair is what lets "
								+ "a mind tell which way AROUND an obstacle is clear, rather "
								+ "than merely that it is stuck."),
						sense(AgentIO.S_HAZARD_AHEAD, "Whether stepping forward means drowning "
								+ "or falling — for anything that cannot fly.")),
				group("Other creatures",
						sense(AgentIO.S_NEAR_PROX, "How close the nearest perceived neighbour "
								+ "is."),
						sense(AgentIO.S_NEAR_BEARING, "Which way that neighbour lies."),
						sense(AgentIO.S_NEAR_SIM, "How like itself that neighbour is — kin or "
								+ "stranger."),
						sense(AgentIO.S_NEAR_SIZEADV, "Whether it outweighs that neighbour, and "
								+ "by how much."),
						sense(AgentIO.S_PREY_PROX, "The nearest SMALLER creature, at full sight "
								+ "range rather than the short facing-gated neighbour set — a "
								+ "hunter has to be able to lock on from far enough off to run "
								+ "its quarry down."),
						sense(AgentIO.S_PREY_BEARING, "Which way that quarry lies."),
						sense(AgentIO.S_THREAT_PROX, "The nearest LARGER creature, likewise at "
								+ "full range. The flee channel."),
						sense(AgentIO.S_THREAT_BEARING, "Which way the threat lies."),
						sense(AgentIO.S_KIN_PROX, "How close the weighted centre of nearby kin "
								+ "is — the difference between \"my kind are off that way\" and "
								+ "\"I am in the middle of them\"."),
						sense(AgentIO.S_KIN_BEARING, "Which way that centre lies. Herding, "
								+ "flocking and packing all emerge from this one gradient rather "
								+ "than from any rule about groups.")),
				group("Places worth going",
						sense(AgentIO.S_FORAGE_PROX, "The best patch of ground in sight, scored "
								+ "by how rich it is against how far off. Unlike the tile "
								+ "underfoot this is a PLACE, so it is a gradient to climb "
								+ "rather than a wall to walk into."),
						sense(AgentIO.S_FORAGE_BEARING, "Which way that patch lies."),
						sense(AgentIO.S_WATER_PROX, "The nearest drinkable shore. Terrain rather "
								+ "than an entity: the body reads the map, the mind reads this."),
						sense(AgentIO.S_WATER_BEARING, "Which way the shore lies."),
						sense(AgentIO.S_WAYPOINT_PROX, "How far off the one remembered place "
								+ "is."),
						sense(AgentIO.S_WAYPOINT_BEARING, "Which way it lies. This pair plus one "
								+ "act is the whole of spatial memory."),
						sense(AgentIO.S_ITEM_PROX, "The nearest inanimate object — a separate "
								+ "sense from the living-neighbour channels."),
						sense(AgentIO.S_ITEM_BEARING, "Which way that object lies."),
						sense(AgentIO.S_ITEM_KIND, "What it is: +1 edible, −1 dangerous, 0 "
								+ "neither."),
						sense(AgentIO.S_FIXTURE_PROX, "The nearest thing that can be operated — "
								+ "a button on its pedestal. Furniture never enters the creature "
								+ "channels, so this is its one window into a mind."),
						sense(AgentIO.S_FIXTURE_BEARING, "Which way the fixture lies.")),
				group("Whether it is working",
						sense(AgentIO.S_INTENT, "How the standing intent is going: idle, "
								+ "impossible, under way, or a pulse the tick the act actually "
								+ "landed. The only channel that tells a mind whether what it "
								+ "wanted happened — without it, a creature can infer success "
								+ "only from its tank drifting upward, several thoughts too "
								+ "late.")));
		return s;
	}

	private static Map<String, Object> acts() {
		Map<String, Object> s = section("acts", "What a creature can do",
				"The other half of the contract, and much the shorter half. Everything a "
				+ "creature does in the world it does by writing one of these numbers.\n\n"
				+ "They are <em>intent</em>, not commands: the body still has final say. Asking "
				+ "to walk into a wall moves nothing, and asking to bite something out of reach "
				+ "bites nothing. Most of them are gates rather than dials — anything above 0.5 "
				+ "means yes — which keeps them reachable by a mutation that merely nudges a "
				+ "number.\n\n"
				+ "Two slots are retired and inert, and deliberately not deleted. A mind stores "
				+ "raw slot numbers, so removing one would renumber the rest and silently change "
				+ "the meaning of every genome ever saved. One of the two was quietly given a "
				+ "second job instead.");
		rows(s,
				row("Slots", num(AgentIO.NUM_ACT), "", "Read back every tick."),
				row("Gates", "anything above 0.5", "",
						"Eat, attack, mate, grab, ride, mark, interact."),
				row("Dials", "−1..1 or 0..1", "", "Turn and throttle."),
				row("Retired", "2", "slots",
						"Kept so old genomes keep meaning what they meant."));
		groups(s,
				group("Moving",
						act(AgentIO.A_TURN, "Steer, as a fraction of this body's turn rate."),
						act(AgentIO.A_THROTTLE, "How hard to push, as a fraction of top speed. "
								+ "Movement costs the square of pace, so this one slot is where "
								+ "a lineage spends or saves its living."),
						act(AgentIO.A_SEEK, "Name a KIND OF THING to head for and the body "
								+ "steers there by itself — see the next section."),
						act(AgentIO.A_TILE, "Name a PROPERTY of ground to look for: edible, "
								+ "hiding, slow going, water, solid, dangerous. Which of those "
								+ "is worth wanting is left for selection to discover.")),
				group("Feeding",
						act(AgentIO.A_EAT, "Graze the tile underfoot. Meat is not taken this "
								+ "way — a carcass is bitten, over many ticks.")),
				group("Fighting, and hanging on",
						act(AgentIO.A_ATTACK, "Bite the nearest neighbour."),
						act(AgentIO.A_GRAB, "Seize a smaller neighbour and haul it. Holding "
								+ "costs energy every tick, so a captor must eventually eat its "
								+ "captive or let go."),
						act(AgentIO.A_ATTACH, "Latch onto a LARGER neighbour and ride it — "
								+ "voluntary, and cheaper than walking."),
						act(AgentIO.A_STRUGGLE, "Resist. Which resistance depends on which end "
								+ "you are: a captive makes itself costlier to carry, a carrier "
								+ "tries to buck its riders off.")),
				group("Breeding and signalling",
						act(AgentIO.A_MATE, "Pair with a compatible neighbour."),
						act(AgentIO.A_DEPOSIT, "Lay pheromone where it stands — the world's one "
								+ "form of writing, and the only thing one creature leaves "
								+ "behind for another to read.")),
				group("Memory, and things",
						act(AgentIO.A_MARK, "Remember this spot, or forget it. One waypoint, "
								+ "held in the body — a mind could not do arithmetic on a "
								+ "coordinate anyway, having neither divide nor atan2."),
						act(AgentIO.A_INTERACT, "Operate whatever fixture it is standing at. "
								+ "Standing on a button does nothing by itself; pressing it is a "
								+ "choice, which is the entire point.")),
				group("Retired, and inert",
						act(AgentIO.A_VERTICAL, "There is no vertical intent to express: a ramp "
								+ "is floor that spans two levels, so you change level by "
								+ "walking, and a hole is gravity rather than a route.")));
		return s;
	}

	private static Map<String, Object> intents() {
		Map<String, Object> s = section("intents", "Wanting things",
				"A mind gets one instruction per tick, so steering to something by hand — read "
				+ "the bearing, compare, turn, repeat — costs more thought than a creature has. "
				+ "An intent collapses that into a single write: name a kind of thing, and while "
				+ "it is in sight the body steers there itself. The sign flips it, so fleeing "
				+ "and chasing cost exactly the same one instruction.\n\n"
				+ "Where arriving has one obvious thing to do, the body does it: heading for a "
				+ "patch grazes, heading for prey bites what comes into reach, heading for an "
				+ "object takes it. Goals with no unambiguous act — kin, a threat, a remembered "
				+ "place — stay pure steering, and running away never attacks: fleeing something "
				+ "is not a reason to bite it.\n\n"
				+ "Naming something the body cannot currently find does not leave it planted; it "
				+ "starts searching. Wanting what you cannot see is a reason to go looking. And "
				+ "the body supplies a direction, never a route — walls remain the mind's "
				+ "problem, which is what the whiskers are for.");
		rows(s,
				row("Cost of an intent", "1", "instruction",
						"Against a budget of one per tick."),
				row("Sign", "toward, or directly away", "",
						"One slot buys both the chase and the flight."),
				row("Speed", "still the mind's to choose", "",
						"An intent says where, never how hard."),
				row("Bands", "centred on the constant pool", "",
						"So a mutation that nudges a value usually keeps the same want."));
		// Derived by asking the real decoder what each value a mind can actually
		// emit would mean. A table that merely restated the thresholds could drift
		// from them; this one cannot, because it IS them.
		List<List<String>> t = new ArrayList<>();
		for (double v : new double[] {0, 0.1, 0.25, 0.5, 1, 2, 4, 6, 9}) {
			t.add(List.of(num(v), SEEK_NAMES[AgentIO.seekTarget(v)],
					TILE_NAMES[AgentIO.tileWanted(v)],
					ARRIVAL[AgentIO.seekTarget(v)]));
		}
		table(s, "What each value a mind can emit actually means, asked of the decoder itself. "
				+ "The two columns read the same number differently — one names what to head "
				+ "for, the other what ground to look for — and the rarest want sits past every "
				+ "value in the pool, so naming it costs an extra step of arithmetic.",
				List.of("Value", "Head for", "Ground wanted", "On arrival"), t);
		groups(s,
				group("How it is going (the feedback channel)",
						item("idle", "No intent set; the mind is steering by hand."),
						item("impossible", "The guards failed — nothing of that kind is in "
								+ "reach of the senses."),
						item("under way", "In sight, and closing."),
						item("landed", "The act fired THIS tick. A pulse, not a "
								+ "completion flag: grazing succeeds on every tick spent on "
								+ "grass. \"The bite landed\" is reportable; \"the kill "
								+ "finished\" is not.")));
		return s;
	}

	/** Names for the seek targets, indexed by {@link AgentIO#seekTarget}'s return. */
	private static final String[] SEEK_NAMES = {
		"nothing", "a forage patch", "kin", "prey", "a threat", "an object",
		"the waypoint", "a fixture", "water",
	};
	/** What the body does on arriving, in the same order. */
	private static final String[] ARRIVAL = {
		"—", "grazes", "—", "bites", "— (never attacks)", "takes it",
		"—", "presses it", "— (drinking is adjacency)",
	};
	/** Names for the tile properties, indexed by {@link AgentIO#tileWanted}. */
	private static final String[] TILE_NAMES = {
		"edible", "blocks sight", "slow going", "water", "solid", "hazardous",
	};

	private static Map<String, Object> pheromones() {
		double halfLife = Math.log(0.5) / Math.log(PheromoneCloud.DECAY);
		double nestLife = Math.log(PheromoneCloud.MIN_STRENGTH / TestNPC.NEST_DEPOSIT)
				/ Math.log(PheromoneCloud.DECAY);
		double trailLife = Math.log(PheromoneCloud.MIN_STRENGTH / (TestNPC.NEST_DEPOSIT * 0.25))
				/ Math.log(PheromoneCloud.DECAY);
		Map<String, Object> s = section("pheromones", "Pheromones",
				"The world's one form of writing. A creature can lay a smell where it stands, "
				+ "and any creature standing there later can read how strong it is — that is "
				+ "the whole channel. It carries no message and names no author, so anything it "
				+ "comes to mean is a convention a population arrives at rather than a protocol "
				+ "anyone designed.\n\n"
				+ "A deposit is a soft cloud with a centre, not a marked tile: strength falls "
				+ "off smoothly to nothing at the edge, and the radius grows with the "
				+ "logarithm of strength, so piling more on a spot makes the smell sharper "
				+ "faster than it makes it wider. A deposit near an existing cloud reinforces "
				+ "it instead of starting a rival, which is what keeps a well-used nest one "
				+ "coherent smell rather than a scattered ring of them.\n\n"
				+ "It evaporates a fixed fraction every tick. That is the important part: a "
				+ "trail nobody walks fades, so what persists is only what is still being "
				+ "used. Memory the world keeps for you, on the condition that you keep paying "
				+ "for it.");
		rows(s,
				row("Kept per tick", pct(PheromoneCloud.DECAY), "",
						"Half gone in " + num(Math.round(halfLife)) + " ticks ("
						+ round(halfLife / TPS, 1) + " s)."),
				row("Gone below", num(PheromoneCloud.MIN_STRENGTH), "strength",
						"The cloud is removed entirely."),
				row("Merges within", num(PheromoneCloud.MERGE_RADIUS), "tiles",
						"Nearby deposits reinforce rather than multiply."),
				row("Radius", num(PheromoneCloud.BASE_RADIUS) + " → "
						+ num(PheromoneCloud.MAX_RADIUS), "tiles",
						"Grows with log strength, then stops."),
				row("A mind's deposit", num(TestNPC.NEST_DEPOSIT * 0.25), "strength",
						"Lasts ~" + num(Math.round(trailLife)) + " ticks ("
						+ round(trailLife / TPS, 1) + " s) unrefreshed."),
				row("A birth at the nest", num(TestNPC.NEST_DEPOSIT), "strength",
						"Lasts ~" + num(Math.round(nestLife)) + " ticks ("
						+ round(nestLife / TPS, 1) + " s) — a landmark, not a trail."),
				row("Smelled from", num(TestNPC.NEST_SENSE_R), "tiles",
						"When a nester homes to breed. Smell ignores facing."));
		return s;
	}

	private static Map<String, Object> minds() {
		Map<String, Object> s = section("minds", "Minds",
				"Behind the sensor and actuator vectors sits whatever decides. In this world "
				+ "that is usually a tiny evolved program: a list of instructions the genome "
				+ "carries, inherited and mutated exactly like body size.\n\n"
				+ "It has no invalid programs. Every operand is masked into range as it "
				+ "executes, so a mutation can rewrite any field of any instruction and the "
				+ "result still runs — which is what keeps the search landscape smooth enough "
				+ "for evolution to climb at all. A language that could crash would spend most "
				+ "of its mutations producing corpses.\n\n"
				+ "The budget is one instruction per tick. Thinking is therefore genuinely "
				+ "scarce, and a longer program is a slower thought rather than a cleverer "
				+ "one — which is the pressure that makes a single-instruction intent worth "
				+ "having, and the reason a reflex beats a deliberation. Registers persist "
				+ "between ticks, so they are the creature's memory; a newborn starts with an "
				+ "empty bank, so siblings with identical code still diverge as soon as their "
				+ "histories do.");
		rows(s,
				row("Program", "up to " + num(Brain.MAX_LEN), "instructions",
						"Heritable, and mutable in length as well as content."),
				row("Executed", num(Brain.DEFAULT_STEPS_PER_TICK), "instruction/tick",
						"Program length is thinking TIME, not thinking power."),
				row("Registers", num(Brain.NUM_REG), "",
						"Persist across ticks; this is the memory."),
				row("Opcodes", num(Brain.NUM_OPS), "", String.join(", ", Brain.OP_NAMES)),
				row("Constant pool", num(Brain.CONST.length), "values", pool()),
				row("Invalid programs", "none", "",
						"Operands are masked at execution, so every mutation runs."),
				row("Randomness", "none", "",
						"A mind draws no dice: the world is deterministic, and a seed plus a "
						+ "command log replays it exactly."));
		return s;
	}

	private static String pool() {
		StringBuilder b = new StringBuilder();
		for (double c : Brain.CONST) {
			b.append(b.length() == 0 ? "" : ", ").append(num(c));
		}
		return b.toString();
	}

	// --- shaping helpers ------------------------------------------------------

	private static Map<String, Object> section(String id, String title, String intro) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("id", id);
		s.put("title", title);
		s.put("intro", intro);
		s.put("rows", new ArrayList<Map<String, String>>());
		return s;
	}

	@SafeVarargs
	private static void rows(Map<String, Object> section, Map<String, String>... rs) {
		@SuppressWarnings("unchecked")
		List<Map<String, String>> list = (List<Map<String, String>>) section.get("rows");
		for (Map<String, String> r : rs) {
			list.add(r);
		}
	}

	/** A named list of channels, for the sections that describe a surface rather
	 *  than a quantity. Each group carries its items in reading order. */
	@SafeVarargs
	private static void groups(Map<String, Object> section, Map<String, Object>... gs) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (Map<String, Object> g : gs) {
			list.add(g);
		}
		section.put("groups", list);
	}

	@SafeVarargs
	private static Map<String, Object> group(String title, Map<String, String>... items) {
		Map<String, Object> g = new LinkedHashMap<>();
		g.put("title", title);
		List<Map<String, String>> list = new ArrayList<>();
		for (Map<String, String> i : items) {
			list.add(i);
		}
		g.put("items", list);
		return g;
	}

	private static Map<String, String> item(String name, String detail) {
		Map<String, String> i = new LinkedHashMap<>();
		i.put("name", name);
		i.put("detail", detail);
		return i;
	}

	/** One sensor, named by the wire name the engine itself uses. Taking the name
	 *  from {@link AgentIO#SENSOR_NAMES} rather than typing it means a channel
	 *  renamed in the contract is renamed here, and a channel documented under a
	 *  name that no longer exists cannot happen. The index is also what lets a test
	 *  demand that every channel is covered exactly once. */
	private static Map<String, String> sense(int idx, String detail) {
		Map<String, String> i = item(AgentIO.SENSOR_NAMES[idx], detail);
		i.put("idx", String.valueOf(idx));
		return i;
	}

	/** One actuator, likewise named by {@link AgentIO#ACT_NAMES}. */
	private static Map<String, String> act(int idx, String detail) {
		Map<String, String> i = item(AgentIO.ACT_NAMES[idx], detail);
		i.put("idx", String.valueOf(idx));
		return i;
	}

	private static Map<String, String> row(String label, String value, String unit, String note) {
		Map<String, String> r = new LinkedHashMap<>();
		r.put("label", label);
		r.put("value", value);
		r.put("unit", unit);
		r.put("note", note);
		return r;
	}

	private static void table(Map<String, Object> section, String caption,
			List<String> headers, List<List<String>> rows) {
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("caption", caption);
		t.put("headers", headers);
		t.put("rows", rows);
		section.put("table", t);
	}

	// --- number formatting ----------------------------------------------------
	// A reference page that prints 0.30000000000000004 is not a reference page.

	/** A value with its trailing zeros trimmed: 8.0 -> "8", 0.0005 -> "0.0005". */
	static String num(double v) {
		if (v == Math.rint(v) && Math.abs(v) < 1e15) {
			return String.valueOf((long) v);
		}
		return trim(String.format(java.util.Locale.ROOT, "%.6f", v));
	}

	/** Rounded to {@code dp} places, trailing zeros trimmed. */
	static String round(double v, int dp) {
		return trim(String.format(java.util.Locale.ROOT, "%." + dp + "f", v));
	}

	/** Small rates readably: three significant figures rather than a wall of zeros. */
	static String sci(double v) {
		if (v == 0) {
			return "0";
		}
		int dp = Math.max(0, 2 - (int) Math.floor(Math.log10(Math.abs(v))));
		return trim(String.format(java.util.Locale.ROOT, "%." + Math.min(dp, 12) + "f", v));
	}

	/** A fraction as a percentage: 0.75 -> "75%". */
	static String pct(double v) {
		return round(v * 100, 2) + "%";
	}

	private static String trim(String s) {
		if (s.indexOf('.') < 0) {
			return s;
		}
		s = s.replaceAll("0+$", "");
		return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
	}
}
