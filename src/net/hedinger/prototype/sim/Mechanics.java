package net.hedinger.prototype.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
				"A creature holds energy up to a ceiling that grows in proportion to its mass, "
				+ "so a big body banks more and can go longer between meals — but needs bigger "
				+ "meals to fill up, and grazing past full is simply wasted. The ceiling is "
				+ "anchored on the body a creature is growing INTO, not the body it currently "
				+ "has, so a juvenile is not economically punished for being young: growth is a "
				+ "physical change, not a demotion.");
		rows(s,
				row("Full tank", num(NPC.BASE_CAPACITY) + " × mass", "energy",
						"Reference body: " + round(NPC.BASE_CAPACITY, 2)),
				row("Born holding", pct(TestNPC.BORN_FRACTION), "of the tank",
						"Fed, but under the breeding line."),
				row("Hunter counts as full at", pct(TestNPC.PRED_FULL_FRACTION), "of the tank",
						"A sated hunter stops hunting."),
				row("Counts as starving under", pct(TestNPC.STARVE_FRACTION), "of the tank",
						"Below this a creature takes risks it otherwise would not."));
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
				"Reproduction is gated on the tank, and the tank scales with mass, so a big "
				+ "creature must eat MORE than a small one before it can breed — not merely as "
				+ "much. The cost sits below the threshold, so breeding leaves a parent alive "
				+ "and fed rather than emptied, and a cooldown stops a well-fed body from "
				+ "spending its whole surplus in one tick. Sexual reproduction spends both "
				+ "parents and takes a stretch of ticks during which neither is doing anything "
				+ "else, which is the real cost of it.");
		rows(s,
				row("Breeds above", pct(TestNPC.REPRO_FRACTION), "of the tank",
						"Reference body: " + round(TestNPC.REPRO_FRACTION * NPC.BASE_CAPACITY, 2)),
				row("Costs", pct(TestNPC.REPRO_COST_FRACTION), "of the tank, each parent",
						"Reference body: "
						+ round(TestNPC.REPRO_COST_FRACTION * NPC.BASE_CAPACITY, 2)),
				row("Offspring starts at", pct(TestNPC.BORN_FRACTION), "of ITS OWN tank",
						"Sized on the body it will grow into."),
				row("Cooldown", num(NPC.REPRO_COOLDOWN), "ticks",
						round(NPC.REPRO_COOLDOWN / TPS, 1) + " s between offspring."),
				row("Mating takes", num(TestNPC.MATING_TICKS), "ticks",
						round(TestNPC.MATING_TICKS / TPS, 1) + " s, both parents occupied."));
		return s;
	}

	private static Map<String, Object> thirst() {
		double dryTicks = 1 / NPC.HYDRATION_DRAIN;
		double fillTicks = 1 / NPC.DRINK_RATE;
		Map<String, Object> s = section("thirst", "Thirst",
				"Water is a second, slower clock. A full hydration tank lasts noticeably longer "
				+ "than a full energy tank, so drinking is a rhythm a creature fits around its "
				+ "living rather than a treadmill it runs on. Running dry does not kill "
				+ "outright — it wears health down — so a parched creature has a real, closing "
				+ "window to reach a shore. Topping up needs no dedicated act: a body standing "
				+ "at or beside water sips as it goes about its business.\n\n"
				+ "Creatures route to water through passable ground, following a flood that only "
				+ "spreads through tiles they could actually walk (or fly) across, so a shore "
				+ "on the far side of a wall is correctly understood as far away rather than as "
				+ "ten tiles through solid rock.");
		rows(s,
				row("Drains", sci(NPC.HYDRATION_DRAIN), "of the tank/tick",
						num(Math.round(dryTicks)) + " ticks — "
						+ round(dryTicks / TPS / 60, 1) + " min — from full to dry."),
				row("Refills", pct(NPC.DRINK_RATE), "of the tank/tick",
						num(Math.round(fillTicks)) + " ticks ("
						+ round(fillTicks / TPS, 1) + " s) for a full drink."),
				row("Drinkable", "any water or shallows in the 3×3 underfoot", "",
						"No dedicated act; a body sips while doing something else."),
				row("Running dry", "wears health down", "",
						"A window to reach a shore, not an instant death."));
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
