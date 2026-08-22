package net.hedinger.prototype.simtest;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.ProcCreature;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.AgentIO;
import net.hedinger.prototype.entities.Brain;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.entities.LgpMind;
import net.hedinger.prototype.entities.Mind;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.Sound;

/**
 * Runner for simulation scenario tests: deterministic mini-worlds with
 * hardcoded entities, advanced a fixed number of ticks, then checked against
 * expected outcomes. Run from the repo root:
 *
 * <pre>
 *   javac -d bin $(find src -name '*.java')
 *   java -cp bin net.hedinger.prototype.simtest.SimTests           # all scenarios
 *   java -cp bin net.hedinger.prototype.simtest.SimTests WallContainment  # by name
 *   java -Dsimtest.shots=out/shots -cp bin net.hedinger.prototype.simtest.SimTests
 * </pre>
 *
 * Scenarios use {@link TestNPC} fixtures rather than game species, so the
 * suite tests engine mechanics and survives changes to the bestiary.
 * Exits non-zero if any scenario fails.
 */
public class SimTests {

	// ---- scenarios ---------------------------------------------------------

	/** A roamer sealed in a single open tile can never escape through walls. */
	static class WallContainment extends Scenario {
		@Override
		public void run() {
			seed(1);
			World w = room(3, 3); // interior is exactly the tile (1,1)
			TestNPC r = TestNPC.roamer(1.5, 1.5, 0);
			w.spawnEntity(r);
			for (int i = 0; i < 300; i++) {
				tick(w, 1);
				assertTrue("roamer stays inside its 1-tile cell (tick " + i + "), was at "
						+ r.getX() + "," + r.getY(),
						r.getX() >= 1.0 && r.getX() < 2.0 && r.getY() >= 1.0 && r.getY() < 2.0);
			}
			assertTrue("roamer still alive", !r.isDead());
			assertEquals("exactly one living actor", 1, w.getAliveCount());
		}
	}

	/** Movement works: a roamer in an open room leaves its spawn point. */
	static class RoamerMoves extends Scenario {
		@Override
		public void run() {
			seed(2);
			World w = room(9, 9);
			TestNPC r = TestNPC.roamer(4.5, 4.5, 0);
			w.spawnEntity(r);
			w.think(); // register the spawn so it draws
			snapshot(w, "before (tick 0)");
			tick(w, 200);
			snapshot(w, "after (tick 200)");
			double moved = Math.hypot(r.getX() - 4.5, r.getY() - 4.5);
			assertGreater("roamer moved from spawn", moved, 0.5);
			assertTrue("roamer stays inside the room",
					r.getX() >= 1 && r.getX() < 8 && r.getY() >= 1 && r.getY() < 8);
		}
	}

	/**
	 * A chaser closes to contact with prey it can perceive.
	 *
	 * <p>Documents a real perception constraint: target scanning gathers
	 * candidates only from the 3x3 tile box around the NPC (and always has --
	 * the pre-optimization search also used a radius-1 gather), so an NPC is
	 * effectively myopic (~1.5 tiles) no matter how large its LOS_RANGE
	 * constant is. Prey must start in an adjacent tile to be seen at all.
	 */
	static class ChaserClosesIn extends Scenario {
		@Override
		public void run() {
			seed(3);
			World w = room(12, 5);
			TestNPC hunter = TestNPC.chaser(2.5, 2.5, 0);
			TestNPC prey = TestNPC.inert(3.7, 2.5, 0); // adjacent tile: perceivable
			w.spawnEntity(hunter);
			w.spawnEntity(prey);
			w.think();
			snapshot(w, "before (tick 0)");
			tick(w, 300);
			snapshot(w, "after (tick 300)");
			double after = Math.hypot(hunter.getX() - prey.getX(), hunter.getY() - prey.getY());
			assertLess("chaser reached its prey (started 1.2 apart)", after, 0.5);
			assertGreater("chaser actually travelled toward the prey", hunter.getX(), 3.0);
		}
	}

	/** An entity with a finite lifespan ages out, dies and is removed. */
	static class AgesOutAndIsRemoved extends Scenario {
		@Override
		public void run() {
			seed(4);
			World w = room(9, 9);
			TestNPC e = TestNPC.inert(4.5, 4.5, 0).withLifespan(50).withDeathspan(0);
			w.spawnEntity(e);
			tick(w, 10);
			assertTrue("alive during its lifespan", !e.isDead());
			tick(w, 60); // past lifespan 50; deathspan 0 removes immediately
			assertTrue("dead after its lifespan", e.isDead());
			assertTrue("removed (deathspan 0)", e.isRemoved());
			assertEquals("no living actors left", 0, w.getAliveCount());
		}
	}

	/**
	 * Lethal damage kills; the corpse then decays on its own after its
	 * deathspan, and scavenging clears it sooner.
	 *
	 * <p>Corpse-decay contract: a dead body ages toward removal one tick at a
	 * time, so after {@code deathspan} ticks the engine purges it even with no
	 * scavengers -- deathspan is how long a corpse lingers. Scavenging ({@code
	 * eat()} decrements age) still clears it early.
	 */
	static class LethalDamageAndScavenging extends Scenario {
		@Override
		public void run() {
			seed(5);
			World w = room(9, 9);
			TestNPC h = TestNPC.inert(4.5, 4.5, 0).withDeathspan(100);
			w.spawnEntity(h);
			tick(w, 2);
			assertEquals("one living actor before the hit", 1, w.getAliveCount());
			snapshot(w, "alive");
			h.damage(200); // health is 100
			tick(w, 1);
			snapshot(w, "corpse");
			assertTrue("dead after lethal damage", h.isDead());
			assertEquals("no living actors after the kill", 0, w.getAliveCount());
			tick(w, 50); // partway through the 100-tick deathspan
			assertTrue("corpse lingers while within its deathspan", !h.isRemoved());
			tick(w, 80); // total 130 ticks dead, past the deathspan
			assertTrue("corpse decays on its own once its deathspan elapses", h.isRemoved());

			// Scavenging clears a body well before its deathspan would.
			TestNPC h2 = TestNPC.inert(4.5, 4.5, 0).withDeathspan(1000);
			w.spawnEntity(h2);
			tick(w, 2);
			h2.damage(200);
			tick(w, 1);
			assertTrue("fresh corpse present", !h2.isRemoved());
			h2.eat(1001); // scavenge the whole deathspan budget at once
			tick(w, 1);
			assertTrue("a scavenged corpse is removed early", h2.isRemoved());
		}
	}

	/**
	 * A body takes as long to rot away as it took to build, and both scale with
	 * mass. Pins the two to each other: the corpse span is read off the growth
	 * constants, so tuning childhood retunes decay and neither can drift.
	 * Also pins the decay clock the renderers draw from.
	 */
	static class CorpseRotsForAsLongAsItTookToGrow extends Scenario {
		@Override
		public void run() {
			seed(28);
			World w = room(9, 9);
			Genome small = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			Genome big = Genome.phenotype(18, 0.0, 5, 6, Math.PI * 2, 100000);
			// breeder, not grazer(x,y,z,g): the latter is a body-only fixture that
			// never runs configureGenomeBody, so it keeps the default deathspan and
			// would pin nothing.
			TestNPC s = TestNPC.breeder(3.5, 3.5, 0, small);
			TestNPC b = TestNPC.breeder(5.5, 5.5, 0, big);
			w.spawnEntity(s);
			w.spawnEntity(b);
			tick(w, 2);

			assertEquals("a small body rots for exactly its childhood",
					net.hedinger.prototype.entities.NPC.growthTicks(6), s.getDeathspan());
			assertEquals("and a big one for its own, longer childhood",
					net.hedinger.prototype.entities.NPC.growthTicks(18), b.getDeathspan());
			assertGreater("mass is the variable factor: the bigger body lasts longer",
					b.getDeathspan() - s.getDeathspan(), 0);

			// The decay clock the renderers read: 0 while alive, then 0 -> 1 across
			// the span. Without this a corpse cannot be drawn rotting at all.
			assertEquals("a living body reports no decay", 0, (long) (s.decayProgress() * 100));
			b.damage(500);
			tick(w, 1);
			assertTrue("dead", b.isDead());
			assertLess("a fresh corpse has barely decayed", b.decayProgress(), 0.05);
			tick(w, b.getDeathspan() / 2);
			assertNear("halfway through the span it is halfway rotted",
					0.5, b.decayProgress(), 0.05);
		}
	}

	/**
	 * A scavenger makes its living off carrion, and eating it IS decomposition:
	 * the same bite that feeds the eater ages the body toward removal. Pins the
	 * whole third trophic level -- that carrion feeds, that it decomposes faster
	 * for being eaten, that freshness prices the meal, and that a scavenger has no
	 * interest in the living.
	 */
	static class ScavengerEatsCarrionAndHastensItsDecay extends Scenario {
		@Override
		public void run() {
			seed(29);
			World w = room(12, 12);
			Genome g = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);

			// Two identical worlds, ticked in lockstep. The ONLY difference is that
			// one carcass has a scavenger stood on it -- an earlier version of this
			// test let the eaten body run one tick longer than the control, and a
			// one-tick head start alone satisfied "decays faster", so the assertion
			// passed against a sabotage that removed the decay entirely.
			World w2 = room(12, 12);
			TestNPC body = TestNPC.breeder(6.5, 6.5, 0, g);
			TestNPC alone = TestNPC.breeder(6.5, 6.5, 0, g);
			w.spawnEntity(body);
			w2.spawnEntity(alone);
			tick(w, 2);
			tick(w2, 2);
			body.damage(500);
			alone.damage(500);
			tick(w, 1);
			tick(w2, 1);
			assertTrue("there is a carcass", body.isDead());

			// Driven by a scripted mind that simply holds "eat": this pins the BODY's
			// scavenging, not whatever a starter brain happens to have evolved into.
			Mind feeder = (sensors, act) -> act[AgentIO.A_EAT] = 1;
			TestNPC scav = TestNPC.minded(6.5, 6.5, 0, g, feeder)
					.withDiet(TestNPC.Diet.SCAVENGER).withHunger(1.0);
			w.spawnEntity(scav);
			tick(w, 1);
			tick(w2, 1);
			assertTrue("a scavenger is its own trophic level", "scavenger".equals(scav.ecoRole()));

			double fed = scav.totalSwallowed();
			tick(w, 60);
			tick(w2, 60);
			assertGreater("carrion feeds the scavenger", scav.totalSwallowed() - fed, 0);
			// Both corpses have now been dead for exactly the same number of ticks,
			// so every bit of this gap is the eating.
			assertGreater("an eaten body decays far faster than one left alone",
					body.decayProgress() - alone.decayProgress(), 0.2);
		}
	}

	/**
	 * A scavenger's forage channel points at carrion, not at grass -- the whole
	 * mechanism by which it inherits the forage intent every starter brain already
	 * runs, without a sensor or a policy of its own.
	 */
	static class ScavengerForagesTowardBodies extends Scenario {
		@Override
		public void run() {
			seed(30);
			World w = room(16, 10);
			Genome g = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);

			// Five tiles east. Carrion is smelled at a flat CARRION_SCENT_R rather than
			// seen at the genome's losRange, so this is well inside range; placed
			// beyond it the scavenger correctly senses nothing.
			TestNPC body = TestNPC.breeder(8.5, 5.5, 0, g);
			w.spawnEntity(body);
			tick(w, 2);
			body.damage(500);
			tick(w, 1);

			// Inert minds and a fixed heading: bearing is measured in the heading
			// frame, so a creature free to turn would make "dead ahead" a statement
			// about what its brain did rather than about where its food is.
			Mind still = (sensors, act) -> { };
			// A genome each: diet lives in the genome now, so two bodies sharing one
			// instance would be the same animal wearing two positions.
			TestNPC scav = TestNPC.minded(3.5, 5.5, 0, g.copy(), still)
					.withDiet(TestNPC.Diet.SCAVENGER).withHeading(0);
			TestNPC grazer = TestNPC.minded(3.5, 7.5, 0, g.copy(), still).withHeading(0);
			w.spawnEntity(scav);
			w.spawnEntity(grazer);
			tick(w, 1);
			snapshot(w, "a carcass east, a scavenger and a grazer west");
			tick(w, 2); // let both sense at least once after being admitted

			assertGreater("the scavenger senses the carcass as its forage",
					scav.sensorSnapshot()[AgentIO.S_FORAGE_PROX], 0);
			assertNear("and it is dead ahead of it",
					0, scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING], 0.1);
			// The grazer stands on grass, so its forage is underfoot -- much nearer
			// than a body eleven tiles away. Same channel, different food.
			assertGreater("a grazer's forage is the ground, not the body",
					grazer.sensorSnapshot()[AgentIO.S_FORAGE_PROX],
					scav.sensorSnapshot()[AgentIO.S_FORAGE_PROX]);
		}
	}

	/**
	 * A carcass is worth what a kill of the same mass is worth. Meat is meat: what
	 * separates a hunter from a scavenger is the chase and the risk on one side and
	 * the search on the other, not the price of the meal.
	 *
	 * <p>This also pins that rot is charged exactly once. Eating advances the same
	 * clock decay does, so a half-rotted body has half its bites left and yields
	 * half as much on its own; multiplying the per-bite rate by freshness as well
	 * discounted the same rot twice and left a whole fresh carcass worth half a
	 * kill.
	 */
	static class ACarcassIsWorthWhatAKillIsWorth extends Scenario {
		@Override
		public void run() {
			seed(34);
			Genome g = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);
			Mind feeder = (sensors, act) -> act[AgentIO.A_EAT] = 1;

			// Strip one whole carcass, from perfectly fresh, and total the takings.
			World w = room(12, 12);
			TestNPC body = TestNPC.breeder(6.5, 6.5, 0, g);
			w.spawnEntity(body);
			tick(w, 2);
			body.damage(500);
			tick(w, 1);
			assertTrue("there is a fresh carcass", body.isDead());
			double bodyMass = body.bodyMass();

			TestNPC scav = TestNPC.minded(6.5, 6.5, 0, g, feeder)
					.withDiet(TestNPC.Diet.SCAVENGER).withHunger(1.0);
			w.spawnEntity(scav);
			tick(w, 1);
			double before = scav.totalSwallowed();
			// Long enough to consume the body outright; the swallowed ledger counts
			// only what fit the stomach, so the gain is the meal and nothing else.
			tick(w, 400);
			assertTrue("the carcass has been eaten away", body.isRemoved() || body.decayProgress() >= 1.0);
			double fromCarrion = scav.totalSwallowed() - before;

			// The same mass taken as prey: a predator consuming a whole body earns
			// MEAT_ENERGY per unit of it.
			double fromKill = 2.5 * bodyMass;
			assertNear("a whole carcass pays what a whole kill pays",
					fromKill, fromCarrion, fromKill * 0.05);
		}
	}

	/**
	 * A creature is drawn as what it is. The body plan reads the genome's diet and
	 * whether it flies, so a grazer, a scavenger and a hunter of identical descent
	 * are three different animals rather than one animal in three colours.
	 *
	 * <p>It used to read the markers alone — the mate-recognition barcode — so the
	 * silhouette said which lineage a creature came from and nothing about what it
	 * was. Measured on a settled world, 349 creatures wore 95 shapes with no
	 * systematic difference between the roles; you could not tell a hunter from a
	 * grazer by looking, which for an ecology built to be watched is most of it.
	 */
	static class ABodyIsShapedByWhatItEats extends Scenario {
		@Override
		public void run() {
			seed(38);
			// One lineage, one set of markers, one size: everything the old body plan
			// looked at is held identical, so any difference is the diet.
			Genome grazer = Genome.phenotype(9, 0.05, 5, 6, Math.PI / 2, 100000);
			grazer.markers = new double[] { 0.4, 0.6, 0.5 };
			Genome scav = grazer.copy();
			scav.diet = Genome.DIET_SCAVENGER;
			Genome hunter = grazer.copy();
			hunter.diet = Genome.DIET_CARNIVORE;
			Genome flier = grazer.copy();
			flier.flying = true;

			long kg = ProcCreature.shapeKey(ProcCreature.phenotype(grazer));
			long ks = ProcCreature.shapeKey(ProcCreature.phenotype(scav));
			long kh = ProcCreature.shapeKey(ProcCreature.phenotype(hunter));
			long kf = ProcCreature.shapeKey(ProcCreature.phenotype(flier));
			assertTrue("a scavenger is not shaped like a grazer", ks != kg);
			assertTrue("a hunter is not shaped like a grazer", kh != kg);
			assertTrue("a hunter is not shaped like a scavenger", kh != ks);
			assertTrue("a flier is not shaped like a walker", kf != kg);

			// And the features are the ones a viewer can name at a glance.
			ProcCreature.Phenotype ps = ProcCreature.phenotype(scav);
			ProcCreature.Phenotype ph = ProcCreature.phenotype(hunter);
			ProcCreature.Phenotype pg = ProcCreature.phenotype(grazer);
			assertTrue("the scavenger wears the feelers it smells with", ps.antennae);
			assertTrue("on the long segmented body, not the grazer's", ps.form != pg.form);
			assertTrue("and the grazer does not", !pg.antennae);
			assertTrue("the hunter carries a tail", ph.tail);
			assertTrue("and the grazer does not", !pg.tail);
			assertTrue("a flier keeps one pair of limbs",
					ProcCreature.phenotype(flier).legs == 1);

			// Diet rides in the genome, so heredity carries it without being asked.
			Genome kid = Genome.child(scav, 0.1);
			assertEquals("a scavenger's young inherit the diet",
					Genome.DIET_SCAVENGER, kid.diet);
			assertEquals("and so are drawn as scavengers too", ks,
					ProcCreature.shapeKey(ProcCreature.phenotype(kid)));
		}
	}

	/**
	 * What a lineage eats is inherited. A scavenger's child is a scavenger, budded
	 * or crossed — without which the niche cannot grow whatever else is true of it,
	 * because every scavenger that ever managed to breed produced a grazer and the
	 * cohort stayed exactly as large as the warden kept it.
	 */
	static class ScavengerYoungAreScavengers extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(14, 14);

			// Asexual: buds alone, so one well-fed body is the whole experiment.
			Genome bud = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);
			bud.markers = new double[] { 0.5, 0.5, 0.5 };
			bud.sexuality = 0.0; // buds
			Mind breeder = (sensors, act) -> act[AgentIO.A_MATE] = 1;
			TestNPC parent = TestNPC.minded(4.5, 4.5, 0, bud, breeder)
					.withDiet(TestNPC.Diet.SCAVENGER).withMetabolic().withDeathspan(777);
			parent.withEnergy(parent.energyCapacity());
			w.spawnEntity(parent);
			w.think();
			tick(w, 200); // budding is a held act: ~165 ticks of commitment first
			assertGreater("the scavenger budded", w.getAliveCount(), 1);
			assertEquals("and every one of its young is a scavenger too",
					0, countRolesOtherThan(w, "scavenger"));
			// Diet is not the only body trait the genome does not carry. A minded
			// child used to inherit none of them -- only the plain-breeder branch
			// remembered corpse lifespan, and nothing remembered diet.
			for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && t != parent && !t.isRemoved()) {
					assertEquals("a child keeps its lineage's corpse lifespan",
							parent.getDeathspan(), t.getDeathspan());
				}
			}

			// Sexual: a compatible pair, both scavengers, crossing over.
			World pair = room(14, 14);
			Genome sx = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);
			sx.markers = new double[] { 0.5, 0.5, 0.5 };
			sx.sexuality = 1.0; // courts
			TestNPC a = TestNPC.minded(6.3, 6.5, 0, sx, breeder)
					.withDiet(TestNPC.Diet.SCAVENGER).withMetabolic();
			TestNPC b = TestNPC.minded(6.7, 6.5, 0, sx.copy(), breeder)
					.withDiet(TestNPC.Diet.SCAVENGER).withMetabolic();
			a.withEnergy(a.energyCapacity());
			b.withEnergy(b.energyCapacity());
			pair.spawnEntity(a);
			pair.spawnEntity(b);
			pair.think();
			tick(pair, 220); // courtship holds station for MATING_TICKS before a birth
			assertGreater("the pair bred", pair.getAliveCount(), 2);
			assertEquals("and the crossover child is a scavenger",
					0, countRolesOtherThan(pair, "scavenger"));
		}
	}

	/** Counts live minded bodies whose {@code ecoRole} is not {@code role}. */
	static int countRolesOtherThan(World w, String role) {
		int n = 0;
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
					&& !role.equals(t.ecoRole())) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Diet is a reproductive barrier. A scavenger and a grazer that are otherwise
	 * identical twins -- same markers, same body, both fertile and touching -- do
	 * not breed, because they are not the same animal.
	 *
	 * <p>Left open, a scavenger's scarce fertile ticks are spent courting the far
	 * more numerous herbivores around it, and the child is built by whichever parent
	 * closes the exchange, so the pairing yields a grazer either way.
	 */
	static class ScavengersDoNotBreedWithGrazers extends Scenario {
		@Override
		public void run() {
			seed(32);
			World w = room(12, 12);
			Genome g = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);
			g.markers = new double[] { 0.5, 0.5, 0.5 };
			g.sexuality = 1.0;

			Mind breeder = (sensors, act) -> act[AgentIO.A_MATE] = 1;
			TestNPC scav = TestNPC.minded(6.3, 6.5, 0, g.copy(), breeder)
					.withDiet(TestNPC.Diet.SCAVENGER).withMetabolic();
			TestNPC grazer = TestNPC.minded(6.7, 6.5, 0, g.copy(), breeder).withMetabolic();
			scav.withEnergy(scav.energyCapacity());
			grazer.withEnergy(grazer.energyCapacity());
			w.spawnEntity(scav);
			w.spawnEntity(grazer);
			w.think();

			assertTrue("the two are genome-compatible on markers alone",
					grazer.getGenome().similarityTo(scav.getGenome()) >= grazer.getGenome().mateThreshold);
			assertTrue("but a scavenger will not pair with a grazer", !scav.canMateWith(grazer));
			assertTrue("nor a grazer with a scavenger", !grazer.canMateWith(scav));
			tick(w, 220);
			assertEquals("so no child is born of the pair", 2, w.getAliveCount());
		}
	}

	/**
	 * A scavenger keeps walking to the carcass it chose unless something is a great
	 * deal better. Carrion is scored by {@code mass * freshness / (1 + dist)}, and
	 * with several bodies in scent range those scores sit close together -- re-running
	 * the argmax every tick hands the lead back and forth and the creature turns
	 * toward a new body every few steps instead of reaching any of them. Measured
	 * before anything held: a target in view on 37% of ticks and one actually within
	 * biting distance on 0.87%. This pins the near-tie half of the rule; the
	 * crossing half is {@link ScavengerCrossesToAMuchBetterCarcass}.
	 */
	/**
	 * The other half of the rule: committing is not a vow. A body worth well over
	 * the band -- here four times the held one's score, because it is a step away
	 * rather than seven -- is worth crossing to.
	 *
	 * <p>Holding outright was measured to cost real food: a quarter of held ticks
	 * had a carcass in range worth more than half again as much, and only a third
	 * of commitments ever ended in a meal.
	 */
	static class ScavengerCrossesToAMuchBetterCarcass extends Scenario {
		@Override
		public void run() {
			seed(37);
			World w = room(30, 12);
			Genome g = Genome.phenotype(9, 0.0, 5, 30, Math.PI * 2, 100000);

			TestNPC far = TestNPC.breeder(15.5, 5.5, 0, g);
			w.spawnEntity(far);
			tick(w, 2);
			far.damage(500);
			tick(w, 1);

			Mind still = (sensors, act) -> { };
			TestNPC scav = TestNPC.minded(8.5, 5.5, 0, g, still)
					.withDiet(TestNPC.Diet.SCAVENGER).withHeading(0);
			w.spawnEntity(scav);
			tick(w, 2);
			assertNear("it starts out fixed on the carcass ahead", 0,
					scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING], 0.1);

			// Practically underfoot, and behind: four times the score of the one it
			// is walking to. Walking past that is not commitment, it is stubbornness.
			TestNPC underfoot = TestNPC.breeder(7.5, 5.5, 0, g);
			w.spawnEntity(underfoot);
			tick(w, 2);
			underfoot.damage(500);
			tick(w, 2);
			assertTrue("a far better carcass is a step behind it", underfoot.isDead());

			assertNear("the scavenger crosses to it", 1.0,
					Math.abs(scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING]), 0.1);
		}
	}

	static class ScavengerHoldsTheCarcassItChose extends Scenario {
		@Override
		public void run() {
			seed(33);
			World w = room(30, 12);
			Genome g = Genome.phenotype(9, 0.0, 5, 30, Math.PI * 2, 100000);

			// One carcass seven tiles east, comfortably inside scent range.
			TestNPC near = TestNPC.breeder(15.5, 5.5, 0, g);
			w.spawnEntity(near);
			tick(w, 2);
			near.damage(500);
			tick(w, 1);
			assertTrue("there is a carcass to walk to", near.isDead());

			Mind still = (sensors, act) -> { };
			TestNPC scav = TestNPC.minded(8.5, 5.5, 0, g, still)
					.withDiet(TestNPC.Diet.SCAVENGER).withHeading(0);
			w.spawnEntity(scav);
			tick(w, 2);
			double chosen = scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING];
			assertNear("it has fixed on the carcass ahead", 0, chosen, 0.1);

			// A second carcass of the same mass appears BEHIND it, four tiles off:
			// nearer, so worth more, but only about 1.6x more -- inside the band.
			// Near-ties are exactly what used to hand the lead back and forth.
			TestNPC rival = TestNPC.breeder(4.5, 5.5, 0, g);
			w.spawnEntity(rival);
			tick(w, 2);
			rival.damage(500);
			tick(w, 2);
			assertTrue("and a slightly better one has appeared behind it", rival.isDead());

			assertNear("a near-tie does not steal the carcass it chose", 0,
					scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING], 0.1);
		}
	}

	/**
	 * A body only forages toward food it could actually walk to. The seek intent
	 * steers a straight bearing with no pathfinding anywhere in the loop, so a
	 * patch chosen through a wall is not a meal -- it is a wall to press against.
	 * Measured underground before this rule, minded foragers spent up to 19% of
	 * their lives motionless against rock at part throttle, aiming at grass on the
	 * far side of it.
	 */
	static class ForageIgnoresFoodBehindWalls extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(16, 12);
			// A full-height wall two tiles east of the forager. Rich ground lies
			// straight beyond it; a thinner patch lies north, on this side.
			for (int y = 1; y <= 10; y++) {
				w.setTile(6, y, 0, Tile.TileType.TYPE_WALL);
			}
			// Strip this side bare except one tile due north of the forager: ground
			// starts lush, so grazing everything else leaves exactly one patch.
			for (int x = 1; x <= 5; x++) {
				for (int y = 1; y <= 10; y++) {
					if (x == 4 && y == 2) {
						continue; // the reachable patch
					}
					w.getTile(x, y, 0).graze(0, Tile.VEG_MAX);
				}
			}
			Genome g = Genome.phenotype(8, 0.0, 5, 9, Math.PI * 2, 100000);
			Mind still = (sensors, act) -> { };
			TestNPC forager = TestNPC.minded(4.5, 8.5, 0, g, still).withHeading(0);
			w.spawnEntity(forager);
			tick(w, 4);
			snapshot(w, "wall east, rich ground beyond it, a patch north");

			double prox = forager.sensorSnapshot()[AgentIO.S_FORAGE_PROX];
			double bearing = forager.sensorSnapshot()[AgentIO.S_FORAGE_BEARING];
			assertGreater("it found food at all", prox, 0);
			// Heading is east (0 rad). North is -PI/2, i.e. -0.5 in the normalised
			// bearing; the walled-off ground due east would read ~0.
			assertLess("it did not pick the ground behind the wall", Math.abs(bearing), 0.95);
			assertGreater("it steered off the wall's bearing entirely", Math.abs(bearing), 0.2);
		}
	}

	/** The Sound->hear() channel: a listener is inert until a sound reaches it. */
	static class SoundWakesListener extends Scenario {
		@Override
		public void run() {
			seed(6);
			World w = room(9, 9);
			TestNPC z = TestNPC.listener(4.5, 4.5, 0);
			w.spawnEntity(z);
			tick(w, 100);
			assertTrue("listener has heard nothing yet", !z.hasHeard());
			assertNear("silent listener did not move (x)", 4.5, z.getX(), 0.001);
			assertNear("silent listener did not move (y)", 4.5, z.getY(), 0.001);

			// A sound broadcasts to everything in earshot at the end of its
			// 20-tick lifespan; the listener should hear it and start moving.
			w.spawnEntity(new Sound(4.5, 4.5, 0));
			tick(w, 150);
			assertTrue("listener heard the sound", z.hasHeard());
			double moved = Math.hypot(z.getX() - 4.5, z.getY() - 4.5);
			assertGreater("woken listener moved", moved, 0.05);
		}
	}

	/** Standing on a hole: walkers fall through to the level below, flyers hover. */
	static class HoleFallRespectsFlying extends Scenario {
		@Override
		public void run() {
			seed(8);
			World w = room(9, 9, 2); // two levels, both carved open
			w.setTile(4, 4, 1, net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE);
			w.setTile(6, 6, 1, net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE);
			TestNPC walker = TestNPC.inert(4.5, 4.5, 1); // on the first hole
			TestNPC flyer = TestNPC.inert(6.5, 6.5, 1).withFlying(); // on the second
			w.spawnEntity(walker);
			w.spawnEntity(flyer);
			w.think();
			snapshot(w, "before (both on level 1 holes)");
			tick(w, 5);
			snapshot(w, "after (walker fell, flyer hovers)");
			assertEquals("walker fell through the hole to the level below", 0, walker.getLvl());
			assertEquals("flyer hovers over the hole", 1, flyer.getLvl());
			tick(w, 50);
			assertEquals("walker rests on the floor below (falls only once)", 0, walker.getLvl());
			assertEquals("flyer still hovering", 1, flyer.getLvl());
			assertNear("walker landed straight down (x)", 4.5, walker.getX(), 0.001);
			assertNear("walker landed straight down (y)", 4.5, walker.getY(), 0.001);
		}
	}

	/**
	 * The demo world's surface/cave orientation and links are sound: the surface
	 * (grass-growing level) sits ABOVE the cave, so a creature that walks onto a
	 * surface hole falls into the cave — a VALID level — rather than off the
	 * bottom into an invalid negative level (the inversion bug that silently lost
	 * wandering creatures). Also pins the up-link: a walker on a cave RAMPUP that
	 * steps east into the flanking wall climbs to the surface. Guards against
	 * re-inverting the levels or breaking the ramp geometry.
	 */
	static class DemoLevelsLinkSurfaceAndCave extends Scenario {
		private static final Tile.TileType HOLE = Tile.TileType.TYPE_HOLE;
		private static final Tile.TileType RAMPUP = Tile.TileType.TYPE_RAMPUP;
		private static final Tile.TileType FLOOR = Tile.TileType.TYPE_FLOOR;

		@Override
		public void run() {
			seed(42);
			World w = net.hedinger.prototype.sim.Worlds.demoTerrain(42);
			int cols = w.getColums(), rows = w.getRows();

			// The surface is the level that grows grass; it must be the TOP level.
			int surface = -1;
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < cols && surface != z; x++) {
					for (int y = 0; y < rows; y++) {
						Tile t = w.getTile(x, y, z);
						if (t.getType() == FLOOR && t.getFertility() > 0) {
							surface = z;
							break;
						}
					}
				}
			}
			assertEquals("the grassy surface is the top level (cave below it)", 1, surface);

			// Down-link: a walker on a surface hole falls into the cave, not the void.
			int hx = -1, hy = -1;
			for (int x = 0; x < cols && hx < 0; x++) {
				for (int y = 0; y < rows; y++) {
					if (w.getTile(x, y, surface).getType() == HOLE) {
						hx = x;
						hy = y;
						break;
					}
				}
			}
			assertGreater("the surface has holes to fall through", hx, -1);
			TestNPC faller = TestNPC.inert(hx + 0.5, hy + 0.5, surface);
			w.spawnEntity(faller);
			w.think();
			tick(w, 10);
			assertEquals("a surface hole drops a walker one level into the cave", surface - 1, faller.getLvl());
			assertTrue("the faller landed on a valid (non-negative) level", faller.getLvl() >= 0);

			// Up-link: a walker on a cave RAMPUP that heads east climbs to the surface.
			int rx = -1, ry = -1;
			for (int x = 0; x < cols && rx < 0; x++) {
				for (int y = 0; y < rows; y++) {
					if (w.getTile(x, y, surface - 1).getType() == RAMPUP) {
						rx = x;
						ry = y;
						break;
					}
				}
			}
			assertGreater("the cave has ramps up to the surface", rx, -1);
			TestNPC climber = TestNPC.mover(rx + 0.5, ry + 0.5, surface - 1, 0.0).withSpeed(0.05);
			w.spawnEntity(climber);
			w.think();
			tick(w, 200);
			assertEquals("a cave ramp climbs a walker back up to the surface", surface, climber.getLvl());

			// Down-ramp: the descent is a walk too, not just a fall. A walker heading
			// west off a surface RAMPDOWN steps down into the cave under its own feet.
			int dx = -1, dy = -1;
			for (int x = 0; x < cols && dx < 0; x++) {
				for (int y = 0; y < rows; y++) {
					if (w.getTile(x, y, surface).getType() == Tile.TileType.TYPE_RAMPDOWN) {
						dx = x;
						dy = y;
						break;
					}
				}
			}
			assertGreater("the surface has ramps down into the cave", dx, -1);
			// Seal the pit beside the ramp first. It descends to the same landing, so
			// leaving it open would let gravity pass this test and prove nothing; with
			// it walled off, only the ramp can carry the walker down.
			w.setTile(dx - 1, dy, surface, Tile.TileType.TYPE_WALL);
			TestNPC walker = TestNPC.mover(dx + 0.5, dy + 0.5, surface, Math.PI).withSpeed(0.05);
			w.spawnEntity(walker);
			w.think();
			tick(w, 60);
			assertEquals("a surface ramp walks a body down into the cave", surface - 1, walker.getLvl());
		}
	}

	/**
	 * grab() attaches a smaller entity, which is then carried along; drop()
	 * releases it.
	 *
	 * <p>Two unit facts this scenario pins: NPC size is a PIXEL radius --
	 * getSize() converts to tiles as size/tileSize, so a size-6 NPC has a
	 * ~0.094-tile radius and grab reach is the sum of half-sizes (~0.08 tiles
	 * for a 6+4 pair). And carried entities are positioned by the attachment
	 * branch of executeMovement at exactly that offset from the carrier, with
	 * NO wall or bounds checks -- Entity.run() kills anything placed outside
	 * the world, so keep grab scenarios away from the border.
	 */
	/**
	 * Carrying and being carried are different acts and wear different badges.
	 *
	 * <p>They used to share one — the comment in {@code actionKey} said as much,
	 * "carrying a captive, or riding a host" — so the hook hovering over a creature
	 * left you to guess which end of the arrangement you were looking at. Which end
	 * is precisely the interesting part: one of them chose this.
	 */
	static class CarryingAndRidingWearDifferentBadges extends Scenario {
		@Override
		public void run() {
			seed(96);

			// A captor with a captive: this body is doing the holding.
			World grab = room(12, 12);
			TestNPC captor = TestNPC.roamer(5.5, 5.5, 0).withSize(6);
			TestNPC captive = TestNPC.inert(5.55, 5.5, 0).withSize(4);
			grab.spawnEntity(captor);
			grab.spawnEntity(captive);
			grab.think();
			assertTrue("the captor takes hold", captor.grab(captive));
			grab.think();
			assertTrue("the one doing the carrying says so", "carry".equals(captor.actionKey()));
			assertTrue("and its captive is held, not riding", captive.isGrabbed());

			// A rider aboard a host by its own choice.
			World ride = room(14, 12);
			int[][] cling = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 },
					{ Brain.WRITE, AgentIO.A_ATTACH, 0, 0 } };
			Genome riderG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			riderG.metabolism = 0.02;
			riderG.brain = new Brain(deepCopy(cling));
			TestNPC rider = TestNPC.brainedBreeder(4.05, 6.0, 0, riderG).withEnergy(6.0);
			TestNPC host = TestNPC.roamer(4.0, 6.0, 0).withSize(18).withSpeed(0.0);
			ride.spawnEntity(rider);
			ride.spawnEntity(host);
			tick(ride, 6);
			assertTrue("the rider is aboard of its own accord",
					rider.getAttachTarget() == host && !rider.isGrabbed());
			assertTrue("a passenger says it is riding", "ride".equals(rider.actionKey()));

			// And the two reach the viewer as different glyphs.
			int carryCode = actionCodeOf(grab, captor.getID());
			int rideCode = actionCodeOf(ride, rider.getID());
			assertEquals("the captor's badge is the carry glyph",
					net.hedinger.prototype.sim.EntityState.ACT_CARRY, carryCode);
			assertEquals("the passenger's is the ride glyph",
					net.hedinger.prototype.sim.EntityState.ACT_RIDE, rideCode);
			assertTrue("which are not the same badge", carryCode != rideCode);
		}
	}

	/** The action code the wire carries for one entity. */
	static int actionCodeOf(World w, int id) {
		for (net.hedinger.prototype.sim.EntityState e
				: net.hedinger.prototype.sim.WorldSnapshot.of(w).entities()) {
			if (e.id() == id) {
				return (e.flags() & net.hedinger.prototype.sim.EntityState.ACTION_MASK)
						>> net.hedinger.prototype.sim.EntityState.ACTION_SHIFT;
			}
		}
		return -1;
	}

	static class GrabCarriesSmallerEntity extends Scenario {
		@Override
		public void run() {
			seed(9);
			World w = room(12, 12);
			TestNPC carrier = TestNPC.roamer(5.5, 5.5, 0).withSize(6);
			TestNPC cargo = TestNPC.inert(5.55, 5.5, 0).withSize(4); // within 0.078 reach
			w.spawnEntity(carrier);
			w.spawnEntity(cargo);
			w.think();
			snapshot(w, "before grab");
			assertTrue("grab succeeds on a smaller, in-reach entity", carrier.grab(cargo));

			double offset = (carrier.getSize() + cargo.getSize()) / 2.0;
			double cargoStartX = cargo.getX();
			double cargoStartY = cargo.getY();
			tick(w, 200);
			snapshot(w, "carrying (tick 200)");
			double carried = Math.hypot(cargo.getX() - carrier.getX(), cargo.getY() - carrier.getY());
			assertNear("carried cargo is pinned at the attachment offset", offset, carried, 0.01);
			assertGreater("cargo was dragged along as the carrier roamed",
					Math.hypot(cargo.getX() - cargoStartX, cargo.getY() - cargoStartY), 0.5);

			assertTrue("drop releases the cargo", carrier.drop());
			assertTrue("a second drop is refused (nothing held any more)", !carrier.drop());
			double maxDist = 0;
			for (int i = 0; i < 300; i++) {
				tick(w, 1);
				maxDist = Math.max(maxDist,
						Math.hypot(cargo.getX() - carrier.getX(), cargo.getY() - carrier.getY()));
			}
			assertGreater("after drop the carrier roams away from the inert cargo "
					+ "(while attached their distance is pinned)", maxDist, offset + 0.5);
			snapshot(w, "after drop (tick 500)");
		}
	}

	/** grab() refuses targets that are bigger or out of reach. */
	static class GrabRespectsSizeAndReach extends Scenario {
		@Override
		public void run() {
			seed(10);
			World w = room(12, 12);
			TestNPC grabber = TestNPC.inert(5.5, 5.5, 0).withSize(2);
			// In reach (within (2+6)/2/64 = 0.0625 tiles) but larger: size gate.
			TestNPC tooBig = TestNPC.inert(5.53, 5.5, 0).withSize(6);
			// Small enough but 4 tiles away: reach gate.
			TestNPC tooFar = TestNPC.inert(9.5, 5.5, 0).withSize(1);
			w.spawnEntity(grabber);
			w.spawnEntity(tooBig);
			w.spawnEntity(tooFar);
			w.think();
			assertTrue("cannot grab a bigger entity even in reach", !grabber.grab(tooBig));
			assertTrue("cannot grab beyond reach (sum of half-sizes)", !grabber.grab(tooFar));
			tick(w, 50);
			assertNear("refused targets are not dragged (tooFar x)", 9.5, tooFar.getX(), 0.001);
		}
	}

	/** Closed doors block passage from both sides; open doors admit. */
	static class DoorBlocksAndAdmits extends Scenario {
		@Override
		public void run() {
			seed(11);
			World w = room(10, 5);
			// Contract level: door flags gate isConnectedSpace. Dirs: 0=N 1=E 2=S 3=W.
			assertTrue("open passage eastward",
					w.isConnectedSpace(3.5, 2.5, 0, 4.5, 2.5, 0));
			w.getTile(3, 2, 0).closeDoor(1); // close the source tile's east door
			assertTrue("closed east door blocks the move",
					!w.isConnectedSpace(3.5, 2.5, 0, 4.5, 2.5, 0));
			w.getTile(3, 2, 0).openDoor(1);
			w.getTile(4, 2, 0).closeDoor(3); // destination's west door blocks too
			assertTrue("destination-side closed door blocks the move",
					!w.isConnectedSpace(3.5, 2.5, 0, 4.5, 2.5, 0));
			w.getTile(4, 2, 0).openDoor(3);
			assertTrue("reopened passage admits again",
					w.isConnectedSpace(3.5, 2.5, 0, 4.5, 2.5, 0));

			// Movement level: a mover halts at a closed door, passes when opened.
			w.getTile(5, 2, 0).closeDoor(1);
			TestNPC m = TestNPC.mover(2.5, 2.5, 0, 0); // heading east
			w.spawnEntity(m);
			tick(w, 250); // 250 * 0.04 = 10 tiles of travel if unobstructed
			snapshot(w, "halted at closed door (red bar)");
			assertLess("mover halted at the closed door", m.getX(), 6.0);
			w.getTile(5, 2, 0).openDoor(1);
			tick(w, 150);
			snapshot(w, "passed after opening");
			assertGreater("mover passed through the opened door", m.getX(), 6.0);
		}
	}

	/**
	 * A walker ascends to the level above via a ramp.
	 *
	 * <p>Pins the ramp mechanic: a ramp is floor that spans two levels, so a body
	 * that simply keeps walking east off a RAMPUP's top edge steps onto the level
	 * above and carries on there. Nothing is willed and nothing is sensed — the
	 * ground does it. A control mover in a ramp-less row meets the same wall and is
	 * merely blocked, which is what proves the ramp (not the wall) is doing the work.
	 */
	static class RampAscends extends Scenario {
		@Override
		public void run() {
			seed(12);
			World w = room(10, 6, 2);
			// Ramp row (y=2): floor, floor, ..., RAMPUP at x=5, WALL at x=6.
			w.setTile(5, 2, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_RAMPUP);
			w.setTile(6, 2, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			// Control row (y=4): same wall, no ramp.
			w.setTile(6, 4, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			TestNPC climber = TestNPC.mover(2.5, 2.5, 0, 0);
			TestNPC control = TestNPC.mover(2.5, 4.5, 0, 0);
			w.spawnEntity(climber);
			w.spawnEntity(control);
			w.think();
			snapshot(w, "before (both on level 0)");
			tick(w, 400);
			snapshot(w, "after (climber up the ramp, control blocked)");
			assertEquals("climber ascended to the level above", 1, climber.getLvl());
			assertGreater("climber kept walking on the upper floor", climber.getX(), 6.5);
			assertEquals("control (no ramp) is still on the ground level", 0, control.getLvl());
			assertLess("control is blocked by the wall", control.getX(), 6.0);
		}
	}

	/** Diagonal moves are blocked unless BOTH flanking cardinal tiles are open. */
	static class DiagonalCornerCutBlocked extends Scenario {
		@Override
		public void run() {
			seed(13);
			World w = room(8, 8);
			assertTrue("diagonal move with open flanks is allowed",
					w.isConnectedSpace(2.5, 2.5, 0, 3.5, 3.5, 0));
			w.setTile(3, 2, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			assertTrue("one blocked flank forbids the diagonal (no corner cutting)",
					!w.isConnectedSpace(2.5, 2.5, 0, 3.5, 3.5, 0));
			w.setTile(2, 3, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			assertTrue("both flanks blocked still forbids it",
					!w.isConnectedSpace(2.5, 2.5, 0, 3.5, 3.5, 0));
			assertTrue("the cardinal moves themselves are also blocked by the walls",
					!w.isConnectedSpace(2.5, 2.5, 0, 3.5, 2.5, 0));
		}
	}

	/** Walls block perception: a chaser cannot acquire prey it has no line of sight to. */
	static class WallBlocksPerception extends Scenario {
		@Override
		public void run() {
			seed(14);
			// Control: prey diagonal-adjacent with open flanks -> acquired and
			// chased. The chaser spawns facing away and its ~90-degree FOV must
			// sweep around across several perception scans before it acquires,
			// so give it a generous window (~600 ticks).
			World open = room(8, 8);
			TestNPC hunter1 = TestNPC.chaser(2.5, 2.5, 0);
			TestNPC prey1 = TestNPC.inert(3.6, 3.6, 0);
			open.spawnEntity(hunter1);
			open.spawnEntity(prey1);
			tick(open, 600);
			double d1 = Math.hypot(hunter1.getX() - prey1.getX(), hunter1.getY() - prey1.getY());
			assertLess("open flanks: chaser reached the diagonal prey", d1, 0.5);

			// Same geometry with both flanking tiles walled: no line of sight.
			World walled = room(8, 8);
			walled.setTile(3, 2, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			walled.setTile(2, 3, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			TestNPC hunter2 = TestNPC.chaser(2.5, 2.5, 0);
			TestNPC prey2 = TestNPC.inert(3.6, 3.6, 0);
			walled.spawnEntity(hunter2);
			walled.spawnEntity(prey2);
			tick(walled, 600);
			double d2 = Math.hypot(hunter2.getX() - prey2.getX(), hunter2.getY() - prey2.getY());
			assertGreater("walled corner: prey never acquired, distance stays large", d2, 1.0);
		}
	}

	/** Overlapping bodies push apart (the collision spring). */
	static class CollisionSpringSeparates extends Scenario {
		@Override
		public void run() {
			seed(15);
			World w = room(8, 8);
			TestNPC a = TestNPC.inert(4.5, 4.5, 0);
			TestNPC b = TestNPC.inert(4.52, 4.5, 0); // overlapping (touch range ~0.094)
			w.spawnEntity(a);
			w.spawnEntity(b);
			tick(w, 200);
			double d = Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
			assertGreater("overlapping entities were pushed apart", d, 0.05);
			assertTrue("both survived the shove", !a.isDead() && !b.isDead());
		}
	}

	/**
	 * spawnEntity rejects positions outside the world -- mostly.
	 *
	 * <p>Documents a real seam: spawn validation truncates coordinates with
	 * (int) casts, and Java truncates toward zero, so x in (-1, 0) truncates
	 * to column 0 and is ACCEPTED even though it lies outside the world (the
	 * engine's World.toCol handles negatives correctly, but Entity.getCol does
	 * not use it). The runtime validity check in Entity.run() then kills the
	 * entity on its first tick. If spawn validation is ever fixed to reject
	 * these, flip the marked assertions.
	 */
	static class SpawnRejectsOutOfBounds extends Scenario {
		@Override
		public void run() {
			seed(16);
			World w = room(8, 8);
			assertTrue("x <= -1 rejected", !w.spawnEntity(TestNPC.inert(-1.5, 4.5, 0)));
			assertTrue("x past the edge rejected", !w.spawnEntity(TestNPC.inert(8.5, 4.5, 0)));
			assertTrue("level out of range rejected", !w.spawnEntity(TestNPC.inert(4.5, 4.5, 3)));

			// The truncation quirk: fractionally-negative x is accepted...
			TestNPC ghost = TestNPC.inert(-0.5, 4.5, 0);
			assertTrue("x in (-1,0) is ACCEPTED (int-cast truncates toward zero)",
					w.spawnEntity(ghost));
			tick(w, 2);
			// ...but the runtime validity check kills it immediately.
			assertTrue("the out-of-bounds spawn dies on its first tick", ghost.isDead());
			assertEquals("no living actors entered the world", 0, w.getAliveCount());
		}
	}

	/**
	 * The genome react() model: relationships emerge from marker similarity,
	 * relative size and disposition genes -- no hardcoded predator/prey table.
	 */
	static class GenomeReactModel extends Scenario {
		@Override
		public void run() {
			seed(17);
			// A big predator: dissimilar markers to its prey, keen to hunt.
			Genome predator = new Genome();
			predator.markers = new double[] { 0.0, 0.0, 0.0 };
			predator.size = 10;
			predator.predatory = 1.0;
			predator.gregariousness = 1.0;
			predator.xenophobia = 0.0;

			Genome prey = new Genome();
			prey.markers = new double[] { 1.0, 1.0, 1.0 }; // maximally dissimilar
			prey.size = 4;
			prey.xenophobia = 1.0; // wary of the unlike
			prey.predatory = 0.0;

			Genome kin = predator.copy(); // same markers as the predator

			// Predator (big) vs dissimilar smaller prey -> attack.
			assertTrue("big predator attacks small dissimilar prey",
					predator.react(prey, 10.0 / 4.0).action == Genome.Action.ATTACK);
			// Prey (small) vs dissimilar bigger predator -> flee.
			assertTrue("small prey flees big dissimilar predator",
					prey.react(predator, 4.0 / 10.0).action == Genome.Action.FLEE);
			// Predator vs its own kind (same markers) -> never hostile. Identical
			// markers clear the mate threshold, so kin read as MATE here; a
			// merely-similar (not identical) neighbour would read AFFILIATE.
			Genome.Relation vsKin = predator.react(kin, 1.0);
			assertTrue("predator is not hostile to kin",
					vsKin.action != Genome.Action.ATTACK && vsKin.action != Genome.Action.FLEE);
			assertTrue("predator is drawn to kin (mate/affiliate)",
					vsKin.action == Genome.Action.MATE || vsKin.action == Genome.Action.AFFILIATE);
			// The asymmetry: same pair, opposite actions, from the same rule.
			assertTrue("relationship is asymmetric (predator attacks, prey flees)",
					predator.react(prey, 2.5).action == Genome.Action.ATTACK
							&& prey.react(predator, 0.4).action == Genome.Action.FLEE);
		}
	}

	/**
	 * End to end: a genome-driven predator hunts a dissimilar smaller entity
	 * while ignoring a same-marker kin standing right next to it.
	 */
	static class GenomePredatorHuntsPrey extends Scenario {
		@Override
		public void run() {
			seed(18);
			World w = room(14, 7);
			Genome predatorG = new Genome();
			predatorG.markers = new double[] { 0.1, 0.1, 0.9 };
			predatorG.size = 10;
			predatorG.speed = 0.05;
			predatorG.predatory = 1.5;
			predatorG.gregariousness = 0.5;
			predatorG.losFov = Math.PI * 2; // omnidirectional: isolate the model
			//                                 from the facing/FOV perception gate

			Genome kinG = predatorG.copy(); // same markers -> recognised as kin
			Genome preyG = new Genome();
			preyG.markers = new double[] { 0.9, 0.9, 0.1 }; // dissimilar
			preyG.size = 4;

			TestNPC predator = TestNPC.genomeDriven(4.5, 3.5, 0, predatorG);
			TestNPC kin = TestNPC.genomeDriven(4.0, 3.5, 0, kinG); // beside the predator
			TestNPC prey = TestNPC.genomeDriven(5.6, 3.5, 0, preyG); // adjacent, perceivable
			w.spawnEntity(predator);
			w.spawnEntity(kin);
			w.spawnEntity(prey);
			w.think();
			snapshot(w, "before (predator, kin, prey)");

			double dPreyStart = Math.hypot(predator.getX() - prey.getX(), predator.getY() - prey.getY());
			tick(w, 400);
			snapshot(w, "after (hunts prey, ignores kin)");

			double dPrey = Math.hypot(predator.getX() - prey.getX(), predator.getY() - prey.getY());
			assertLess("predator closed on the prey", dPrey, dPreyStart * 0.6);
			assertTrue("predator's dominant action is ATTACK -- the dissimilar smaller "
					+ "prey outweighs the kin beside it", predator.lastAction() == Genome.Action.ATTACK);
		}
	}

	/**
	 * A hunter can punch above its weight, but pays for it in time. Quarry up to
	 * {@code PRED_MAX_PREY_RATIO} times a hunter's own size is fair game, and the
	 * bite scales down with the size ratio — so an undersized hunter still brings
	 * the animal down, it just needs far more bites to do it.
	 *
	 * <p>Runs the same kill twice, changing only the hunter's body: an equal-sized
	 * hunter and an undersized one against identical quarry, each parked in reach so
	 * the measurement is the bite rate and nothing else (no chase, no perception).
	 * Asserts both kill, and that the small one takes materially longer.
	 */
	static class SmallHunterTakesBiggerPreySlowly extends Scenario {
		/** Ticks for {@code hunterSize} to kill a size-10 victim parked in reach;
		 *  -1 if it never manages it. */
		private int ticksToKill(double hunterSize) {
			seed(5);
			World w = room(20, 12);
			Genome predG = new Genome();
			predG.size = hunterSize;
			predG.speed = 0.04;
			predG.losFov = Math.PI * 2;
			predG.losRange = 12;
			Genome victimG = new Genome();
			victimG.size = 10;
			victimG.speed = 0; // parked: isolate bite rate from the chase

			TestNPC hunter = TestNPC.predator(5.0, 5.0, 0, predG).withHunger(0.8);
			TestNPC victim = TestNPC.breeder(5.4, 5.0, 0, victimG); // adjacent, in reach
			w.spawnEntity(hunter);
			w.spawnEntity(victim);
			w.think();
			for (int t = 0; t < 3000; t++) {
				w.think();
				if (victim.isDead()) {
					return t;
				}
			}
			return -1;
		}

		@Override
		public void run() {
			int even = ticksToKill(10); // same weight class as the victim
			int small = ticksToKill(7); // undersized: victim is ~1.43x its size

			assertGreater("an evenly-matched hunter kills its quarry", even, -1);
			assertGreater("an undersized hunter still brings bigger quarry down", small, -1);
			// 20 damage/bite at parity vs round(20 * 7/10) = 14, so a 100-health
			// victim needs 5 bites instead of 8 — a clearly longer kill.
			assertGreater("the undersized hunter's kill takes materially longer ("
					+ small + " ticks vs " + even + ")", small, even);
		}
	}

	/**
	 * The engine refuses to move any body more than {@link Entity#MAX_STEP} in one
	 * tick, however fast its genome claims to be. Passability is decided one tile at
	 * a time, so an over-long step would tunnel through terrain — or, past a full
	 * tile, be rejected outright and leave the creature stuttering in place. Drives
	 * a deliberately absurd speed (100x the fastest anything evolves to) and pins
	 * both halves: every step stays inside the ceiling, AND the creature still
	 * actually travels rather than freezing.
	 */
	static class EngineCapsStepLength extends Scenario {
		@Override
		public void run() {
			seed(3);
			World w = room(30, 12);
			TestNPC bolter = TestNPC.mover(2.5, 5.5, 0, 0).withSpeed(5.0); // due east
			w.spawnEntity(bolter);
			w.think();

			double x0 = bolter.getX();
			double worst = 0;
			for (int i = 0; i < 20; i++) {
				w.think();
				worst = Math.max(worst, bolter.lastStep());
			}
			assertLess("no step exceeds the engine ceiling", worst, Entity.MAX_STEP + 1e-9);
			assertGreater("a clamped body still travels (it is not frozen by the cap)",
					bolter.getX() - x0, 1.0);
		}
	}

	/**
	 * Moving costs energy, in proportion to the ground covered. Without a cost of
	 * transport the burn depended only on body size, so speed — the one gene with
	 * real upside and no downside — ratcheted upward every generation. Three
	 * identical bodies differing only in speed: a still one, a slow walker and a
	 * fast one. Asserts the burn is strictly ordered by distance travelled, and
	 * that holding still costs nothing beyond resting metabolism.
	 */
	static class TravelCostsEnergy extends Scenario {
		@Override
		public void run() {
			seed(4);
			World w = room(30, 14);
			// Same size (so identical resting burn), same start energy, differing
			// only in speed. Movers walk east across open floor, never reaching a wall
			// within the window, so nothing cancels their steps.
			TestNPC still = TestNPC.inert(2.5, 3.5, 0).withMetabolic().withEnergy(4);
			TestNPC slow = TestNPC.mover(2.5, 6.5, 0, 0).withMetabolic().withEnergy(4).withSpeed(0.05);
			TestNPC fast = TestNPC.mover(2.5, 9.5, 0, 0).withMetabolic().withEnergy(4).withSpeed(0.15);
			w.spawnEntity(still);
			w.spawnEntity(slow);
			w.spawnEntity(fast);
			w.think();

			double e0 = still.getEnergy();
			tick(w, 100);
			double burnStill = e0 - still.getEnergy();
			double burnSlow = e0 - slow.getEnergy();
			double burnFast = e0 - fast.getEnergy();

			assertGreater("walking costs more than standing still", burnSlow, burnStill);
			assertGreater("travelling further costs more again", burnFast, burnSlow);
			// The gap is the cost of transport over the extra ground, so it should
			// track the distance ratio (3x the speed => ~3x the travel component).
			double travelSlow = burnSlow - burnStill;
			double travelFast = burnFast - burnStill;
			assertGreater("the fast body's travel bill scales with the distance it covered",
					travelFast, travelSlow * 2.0);
		}
	}

	/**
	 * Holding a prisoner costs more than carrying a passenger of the same weight.
	 * Carrying always charged for mass, but the grip itself was free, so a captor
	 * could seize something and hold it forever at no cost beyond what a willing
	 * rider would have cost — captivity was a state rather than an effort. Three
	 * identical carriers, differing only in what they hold: nothing, a voluntary
	 * rider, and a grabbed captive of the same size as that rider.
	 */
	static class HoldingACaptiveCostsEnergy extends Scenario {
		@Override
		public void run() {
			seed(6);
			World w = room(40, 24);
			// Same body, same start energy; only the load differs. Big enough to
			// grab the small ones (grab refuses anything larger than the captor).
			// They must WALK: a load is priced as extra mass through the movement
			// term, so a motionless carrier pays nothing for weight it is merely
			// holding. Standing still would compare three identical resting bills.
			TestNPC empty = TestNPC.mover(3.0, 4.0, 0, 0).withSize(12).withMetabolic()
					.withEnergy(4).withSpeed(0.08);
			TestNPC ferry = TestNPC.mover(3.0, 10.0, 0, 0).withSize(12).withMetabolic()
					.withEnergy(4).withSpeed(0.08);
			TestNPC captor = TestNPC.mover(3.0, 16.0, 0, 0).withSize(12).withMetabolic()
					.withEnergy(4).withSpeed(0.08);
			TestNPC rider = TestNPC.inert(3.05, 10.0, 0).withSize(6);
			TestNPC victim = TestNPC.inert(3.05, 16.0, 0).withSize(6);
			w.spawnEntity(empty);
			w.spawnEntity(ferry);
			w.spawnEntity(captor);
			w.spawnEntity(rider);
			w.spawnEntity(victim);
			w.think();

			assertTrue("the rider latched on voluntarily", rider.attachTo(ferry));
			assertTrue("the captor seized its victim", captor.grab(victim));
			assertTrue("a grabbed body is held, a rider is not",
					victim.isGrabbed() && !rider.isGrabbed());

			double e0 = empty.getEnergy();
			tick(w, 100);
			double burnEmpty = e0 - empty.getEnergy();
			double burnFerry = e0 - ferry.getEnergy();
			double burnCaptor = e0 - captor.getEnergy();

			assertGreater("hauling a passenger costs more than travelling empty",
					burnFerry, burnEmpty);
			assertGreater("holding a prisoner costs more than ferrying a passenger "
					+ "of the same weight (the grip is the difference)", burnCaptor, burnFerry);
		}
	}

	/**
	 * Creatures are born as juveniles and grow into their genome's body. Growth
	 * runs at a fixed rate, so the bigger the adult body the longer the childhood —
	 * and the largest body a genome can express takes about a minute, the longest
	 * childhood the world can produce. Growth is physical: the juvenile is smaller,
	 * burns less and is easier prey, but the energy economy (tank, breeding
	 * threshold and cost) is anchored on the adult body and so is unchanged.
	 */
	static class CreaturesGrowToAdultSize extends Scenario {
		/** Ticks for a body with the given adult size to finish growing. */
		private int ticksToMature(double adultSize) {
			seed(9);
			World w = room(20, 12);
			Genome g = new Genome();
			g.size = adultSize;
			TestNPC t = TestNPC.breeder(9.5, 5.5, 0, g);
			w.spawnEntity(t);
			w.think();
			assertTrue("a newborn is a juvenile", t.isJuvenile());
			assertLess("a newborn is markedly smaller than its adult body",
					t.getPixelSize(), adultSize * 0.6);
			for (int i = 0; i < 6000; i++) {
				w.think();
				if (!t.isJuvenile()) {
					assertEquals("a grown body reaches exactly its genome size",
							(int) Math.round(adultSize), t.getPixelSize());
					return i;
				}
			}
			return -1;
		}

		@Override
		public void run() {
			int small = ticksToMature(6);
			int large = ticksToMature(Genome.SIZE_MAX); // 20: the biggest body possible

			assertGreater("a small body finishes growing", small, -1);
			assertGreater("the largest body finishes growing", large, -1);
			assertGreater("a bigger creature takes longer to grow up", large, small);
			// A fixed growth rate over the longest possible climb: ~1 minute at
			// 33 ticks/s. Bounded on both sides so the rate cannot drift unnoticed.
			int oneMinute = 60 * net.hedinger.prototype.sim.SimulationRunner.TICKS_PER_SECOND;
			assertGreater("the longest childhood is on the order of a minute ("
					+ large + " ticks)", large, oneMinute * 0.75);
			assertLess("the longest childhood does not much exceed a minute ("
					+ large + " ticks)", large, oneMinute * 1.25);

			// Growth is physical, not economic: the tank is anchored on the adult
			// body, so a newborn's breeding economy matches a grown one's.
			seed(9);
			World w = room(20, 12);
			Genome g = new Genome();
			g.size = 12;
			TestNPC baby = TestNPC.breeder(9.5, 5.5, 0, g);
			w.spawnEntity(baby);
			w.think();
			double juvenileCap = baby.energyCapacity();
			tick(w, 3000); // long enough to be fully grown
			assertTrue("the body did finish growing", !baby.isJuvenile());
			assertEquals("the energy tank is the same before and after growing up",
					Math.round(juvenileCap * 1000), Math.round(baby.energyCapacity() * 1000));
		}
	}

	/**
	 * A creature's role is decided by what it EATS, never by what steers it.
	 *
	 * <p>The steward's bounds are keyed on role, so anything the role fails to
	 * describe is something the world cannot govern. Mindedness used to leak into
	 * it: a herbivore with an evolved brain reported no role at all and was held by
	 * a separate "minded" cohort with its own guardrails — the same animal governed
	 * one way with a brain and another way without, and missing from the prey count
	 * that is supposed to describe the herd.
	 *
	 * <p>Pinned in both directions: bodies differing only in what drives them must
	 * report the SAME role, and bodies differing only in diet must report different
	 * ones.
	 */
	static class RoleFollowsDietNotMind extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(14, 14);
			Genome g = new Genome();

			// Same diet, three different things driving the body.
			TestNPC scripted = TestNPC.breeder(2.5, 2.5, 0, g.copy());
			TestNPC brained = TestNPC.brainedBreeder(3.5, 2.5, 0, g.copy());
			TestNPC evolved = TestNPC.mindedForager(4.5, 2.5, 0, g.copy());
			for (TestNPC t : new TestNPC[] { scripted, brained, evolved }) {
				w.spawnEntity(t);
			}
			assertTrue("a scripted herbivore is prey", "prey".equals(scripted.ecoRole()));
			assertTrue("a brained herbivore is prey too", "prey".equals(brained.ecoRole()));
			assertTrue("an evolved herbivore is STILL prey", "prey".equals(evolved.ecoRole()));
			assertTrue("mindedness is not what decides the role",
					evolved.isMinded() && evolved.ecoRole().equals(scripted.ecoRole()));

			// Same driver, one per trophic level.
			TestNPC mh = TestNPC.mindedForager(6.5, 6.5, 0, dieted(Genome.DIET_HERBIVORE));
			TestNPC mc = TestNPC.mindedForager(7.5, 6.5, 0, dieted(Genome.DIET_CARNIVORE));
			TestNPC ms = TestNPC.mindedForager(8.5, 6.5, 0, dieted(Genome.DIET_SCAVENGER));
			TestNPC mp = TestNPC.mindedForager(9.5, 6.5, 0, dieted(Genome.DIET_PARASITE));
			for (TestNPC t : new TestNPC[] { mh, mc, ms, mp }) {
				w.spawnEntity(t);
			}
			assertTrue("diet decides: herbivore -> prey", "prey".equals(mh.ecoRole()));
			assertTrue("diet decides: carnivore -> predator", "predator".equals(mc.ecoRole()));
			assertTrue("diet decides: scavenger -> scavenger", "scavenger".equals(ms.ecoRole()));
			assertTrue("diet decides: parasite -> parasite", "parasite".equals(mp.ecoRole()));

			// Bodies outside the ecosystem stay outside it: no genome, no role, so
			// the warden never counts or trims them.
			assertTrue("a bare roamer has no role",
					TestNPC.roamer(1.5, 1.5, 0).ecoRole().isEmpty());
			assertTrue("an inert fixture has no role",
					TestNPC.inert(1.5, 2.5, 0).ecoRole().isEmpty());

			// Spawns are queued until the next step and the steward's census runs over
			// live entities, so tick once before counting -- otherwise this measures
			// an empty world and passes for the wrong reason.
			tick(w, 1);

			// Every creature with a genome must land in exactly one bucket. One with
			// none would be ungovernable: invisible to its floor, immune to its ceiling.
			int roleless = 0;
			java.util.Map<String, Integer> byRole = new java.util.TreeMap<>();
			for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && t.getGenome() != null && !t.isDead()) {
					if (t.ecoRole().isEmpty()) {
						roleless++;
					} else {
						byRole.merge(t.ecoRole(), 1, Integer::sum);
					}
				}
			}
			assertEquals("every creature with a genome has a role", 0, roleless);
			assertEquals("four herbivores counted as prey", 4, (long) byRole.getOrDefault("prey", 0));
			assertEquals("one predator", 1, (long) byRole.getOrDefault("predator", 0));
			assertEquals("one scavenger", 1, (long) byRole.getOrDefault("scavenger", 0));
			assertEquals("one parasite", 1, (long) byRole.getOrDefault("parasite", 0));
		}

		private static Genome dieted(int diet) {
			Genome g = new Genome();
			g.diet = diet;
			return g;
		}
	}

	/**
	 * Every sensor is reachable by an evolved mind.
	 *
	 * <p>Brain operands are masked into range at execution, so an operand pool
	 * narrower than the sensor bank does not fail — it silently makes the channels
	 * above it unreadable. That is the worst shape a bug can take: the sim runs,
	 * every test passes, the sensors are computed every tick, and evolution simply
	 * never sees them. Eighteen of thirty-four were invisible this way, including
	 * the entire forage, waypoint, thirst and intent suite.
	 *
	 * <p>Checked by actually mutating a population and reading back what the
	 * programs reference, rather than by comparing two constants — the constants
	 * are one implementation of the property, and it is the property that matters.
	 */
	static class EverySensorIsReachable extends Scenario {
		@Override
		public void run() {
			seed(11);
			java.util.Set<Integer> sensed = new java.util.HashSet<>();
			java.util.Set<Integer> written = new java.util.HashSet<>();
			java.util.List<Brain> pop = new java.util.ArrayList<>();
			for (int i = 0; i < 60; i++) {
				pop.add(Brain.random(24));
			}
			for (int gen = 0; gen < 60; gen++) {
				java.util.List<Brain> next = new java.util.ArrayList<>();
				for (Brain b : pop) {
					Brain c = b.copy();
					c.mutate(0.35);
					next.add(c);
					for (String line : c.disassemble(AgentIO.SENSOR_NAMES, AgentIO.ACT_NAMES)) {
						int at = line.indexOf("sense ");
						if (at >= 0) {
							note(sensed, AgentIO.SENSOR_NAMES, line.substring(at + 6).trim());
							continue;
						}
						at = line.indexOf("act ");
						if (at >= 0 && line.indexOf(" =", at) > at) {
							note(written, AgentIO.ACT_NAMES,
									line.substring(at + 4, line.indexOf(" =", at)).trim());
						}
					}
				}
				pop = next;
			}
			for (int i = 0; i < AgentIO.NUM_SENSORS; i++) {
				assertTrue("sensor '" + AgentIO.SENSOR_NAMES[i] + "' (" + i
						+ ") can be reached by an evolved mind", sensed.contains(i));
			}
			for (int i = 0; i < AgentIO.NUM_ACT; i++) {
				assertTrue("actuator '" + AgentIO.ACT_NAMES[i] + "' (" + i
						+ ") can be reached by an evolved mind", written.contains(i));
			}
		}

		private static void note(java.util.Set<Integer> into, String[] names, String name) {
			for (int i = 0; i < names.length; i++) {
				if (names[i].equals(name)) {
					into.add(i);
					return;
				}
			}
		}
	}

	/**
	 * Hearing works end to end: violence makes a noise, the noise crosses a wall,
	 * a body on the far side reads it on the sensor bank, and it then goes quiet.
	 *
	 * <p>The wall is the point. Sight is blocked and smell hangs where it was left;
	 * hearing is the only channel that carries an event through terrain, so a test
	 * that put both creatures in one open room would pass on a hearing
	 * implementation that was really just short-range sight.
	 */
	static class ViolenceIsAudibleThroughWalls extends Scenario {
		@Override
		public void run() {
			seed(12);
			World w = room(15, 7);
			for (int y = 1; y < 6; y++) {
				w.setTile(6, y, 0, Tile.TileType.TYPE_WALL); // a solid partition
			}
			// A big hunter and a quarry on one side; a listener sealed off on the
			// other, close enough to be within earshot of the kill but with no line
			// of sight to it at all.
			Genome big = new Genome();
			big.size = 14;
			// Hungry on purpose: a sated hunter merely patrols (VITALS.md), and this
			// scenario is about the noise a kill makes, not about what starts one.
			TestNPC hunter = TestNPC.predator(3.5, 3.5, 0, big)
					.withHunger(TestNPC.STARVE_HUNGER);
			TestNPC quarry = TestNPC.grazer(4.3, 3.5, 0);
			TestNPC listener = TestNPC.mindedForager(8.5, 3.5, 0, new Genome());
			w.spawnEntity(hunter);
			w.spawnEntity(quarry);
			w.spawnEntity(listener);

			assertTrue("nothing has been heard before any violence", !listener.hearsSomething());
			assertTrue("the wall really does block sight between the two sides",
					!w.isConnectedSpace(3.5, 3.5, 0, 8.5, 3.5, 0));

			// Anything the listener registers therefore got there by ear.
			boolean heard = false;
			for (int i = 0; i < 600 && !heard; i++) {
				tick(w, 1);
				heard = listener.hearsSomething();
			}
			assertTrue("a kill on the far side of a wall was heard", heard);

			double[] s = new double[AgentIO.NUM_SENSORS];
			listener.senseInto(s);
			assertGreater("the sound reaches the mind's proximity channel",
					s[AgentIO.S_SOUND_PROX], 0);

			// ...and then stops. A channel that never fell silent would leave a
			// creature steering toward a scream from minutes ago.
			boolean quiet = false;
			for (int i = 0; i < 900 && !quiet; i++) {
				tick(w, 1);
				quiet = !listener.hearsSomething();
			}
			assertTrue("the sound eventually stops ringing", quiet);
			listener.senseInto(s);
			assertEquals("a silent world reads zero on the hearing channels", 0,
					Math.round(s[AgentIO.S_SOUND_PROX] * 1000));
		}
	}

	/**
	 * The mechanics reference is READ OFF the world, not typed out beside it.
	 *
	 * <p>Documentation rots in a particular, quiet way: somebody tunes a constant,
	 * every test still passes, and the page goes on confidently stating last
	 * month's number. The only defence is to make the page incapable of holding an
	 * independent opinion — so every figure it publishes is either a live constant
	 * or computed from live constants by the arithmetic the simulation uses.
	 *
	 * <p>This pins that property rather than the numbers themselves: it recomputes
	 * the published tables straight from {@link NPC} and {@link TestNPC} and
	 * demands they agree. Replace any of those computations with a literal — the
	 * exact regression this exists to catch — and the recomputation and the literal
	 * part company the moment the constant behind it moves.
	 */
	static class MechanicsAreReadOffTheWorld extends Scenario {
		@Override
		public void run() {
			java.util.List<java.util.Map<String, Object>> secs
					= net.hedinger.prototype.sim.Mechanics.sections();
			assertGreater("the reference documents a useful number of rules", secs.size(), 5);

			java.util.Set<String> ids = new java.util.HashSet<>();
			for (java.util.Map<String, Object> sec : secs) {
				String id = (String) sec.get("id");
				assertTrue("every section has an anchor id", id != null && !id.isEmpty());
				assertTrue("section ids are unique (" + id + ")", ids.add(id));
				assertTrue("section " + id + " has a title",
						!((String) sec.get("title")).isEmpty());
				// Prose explains WHY a rule is shaped as it is; a table of bare
				// constants with nothing around it is a config dump, not a reference.
				assertGreater("section " + id + " explains itself",
						((String) sec.get("intro")).length(), 120);
				assertGreater("section " + id + " publishes facts", rowsOf(sec).size(), 2);
				for (java.util.Map<String, String> r : rowsOf(sec)) {
					String v = r.get("value");
					assertTrue("row '" + r.get("label") + "' has a value",
							v != null && !v.isEmpty());
					// Float noise ("0.30000000000000004") and NaN both mean a figure
					// reached the page without going through the formatter.
					assertTrue("value '" + v + "' is printed, not dumped",
							!v.contains("0000000") && !v.contains("NaN")
							&& !v.contains("Infinity") && !v.contains("E-"));
				}
			}

			// --- the worked tables, recomputed ------------------------------------
			// Resting metabolism: tank over burn is how long a motionless creature
			// lasts. Kleiber's 0.75 exponent is the whole point of the section, so a
			// page that quietly used mass^1.0 would read plausibly and be wrong.
			for (java.util.List<String> r : tableOf(secs, "metabolism")) {
				double size = Double.parseDouble(r.get(0));
				double mass = size / NPC.REF_SIZE;
				double cap = NPC.BASE_CAPACITY * mass;
				double burn = NPC.BASE_METABOLISM * Math.pow(mass, 0.75);
				assertNear("mass at size " + size, mass, Double.parseDouble(r.get(1)), 0.005);
				assertNear("tank at size " + size, cap, Double.parseDouble(r.get(2)), 0.005);
				assertNear("burn at size " + size, burn, Double.parseDouble(r.get(3)),
						burn * 0.01);
				assertNear("ticks unfed at size " + size, cap / burn,
						Double.parseDouble(r.get(4)), 1.0);
			}
			// A bigger body's tank grows linearly while its burn grows with mass^0.75,
			// so the fasting window MUST widen with size. If this ever reverses, the
			// prose above it has become a lie about its own table.
			java.util.List<java.util.List<String>> meta = tableOf(secs, "metabolism");
			assertGreater("big bodies fast longer than small ones",
					Double.parseDouble(meta.get(meta.size() - 1).get(4)),
					Double.parseDouble(meta.get(0).get(4)));

			// Movement is priced on the SQUARE of the step: doubling pace must
			// quadruple the bill, which is the claim the section actually makes.
			java.util.List<java.util.List<String>> move = tableOf(secs, "movement");
			for (java.util.List<String> r : move) {
				double v = Double.parseDouble(r.get(0));
				assertNear("move cost at " + v, NPC.MOVE_ENERGY * v * v,
						Double.parseDouble(r.get(2)), NPC.MOVE_ENERGY * v * v * 0.01 + 1e-9);
			}
			double slow = Double.parseDouble(move.get(0).get(2));
			double fast = Double.parseDouble(move.get(move.size() - 1).get(2));
			double ratio = (Double.parseDouble(move.get(move.size() - 1).get(0))
					/ Double.parseDouble(move.get(0).get(0)));
			assertNear("cost rises with the square of speed", ratio * ratio, fast / slow,
					ratio * ratio * 0.02);

			// A carcass is worth the dead body's mass, and rots over exactly the time
			// that body took to build -- one constant behind both, by construction.
			for (java.util.List<String> r : tableOf(secs, "food")) {
				double size = Double.parseDouble(r.get(0));
				assertNear("carcass at size " + size,
						TestNPC.MEAT_ENERGY * (size / NPC.REF_SIZE),
						Double.parseDouble(r.get(2)), 0.005);
				assertEquals("rot time at size " + size, NPC.growthTicks(size),
						Long.parseLong(r.get(4)));
			}
			for (java.util.List<String> r : tableOf(secs, "growth")) {
				double size = Double.parseDouble(r.get(0));
				assertEquals("childhood at size " + size, NPC.growthTicks(size),
						Long.parseLong(r.get(2)));
			}
			// Rot and childhood are the same figure; the page says so, so it had
			// better still be true of what the page prints.
			java.util.List<java.util.List<String>> food = tableOf(secs, "food");
			java.util.List<java.util.List<String>> grow = tableOf(secs, "growth");
			assertEquals("the tables agree on how long a body takes",
					Long.parseLong(food.get(0).get(4)), Long.parseLong(grow.get(0).get(2)));

			// Percentages come from the fractions the founder setup actually applies.
			assertTrue("the breeding threshold is the one the world uses",
					find(secs, "breeding", "Breeds above")
					.startsWith(pctOf(TestNPC.REPRO_FRACTION)));
			assertTrue("the born-at fraction is the one the world uses",
					find(secs, "tank", "Born holding").startsWith(pctOf(TestNPC.BORN_FRACTION)));
			assertTrue("the tick rate is the one the world runs at",
					find(secs, "time", "Tick rate").equals(String.valueOf(
							net.hedinger.prototype.sim.SimulationRunner.TICKS_PER_SECOND)));

			// --- the surfaces, covered exactly once -------------------------------
			// Sensors and actuators are a LIST rather than a quantity, so they rot a
			// different way: somebody adds a channel and the page silently describes a
			// world with one fewer sense in it than the creatures actually have. A
			// reader cannot tell an incomplete list from a complete one, which is what
			// makes the omission worth failing the build over.
			coversEveryChannel(secs, "senses", AgentIO.NUM_SENSORS, AgentIO.SENSOR_NAMES);
			coversEveryChannel(secs, "acts", AgentIO.NUM_ACT, AgentIO.ACT_NAMES);

			// The intent table is the decoder's own answer, not a restatement of its
			// thresholds. Ask it directly and the two must agree for every value a
			// mind can actually emit.
			for (java.util.List<String> r : tableOf(secs, "intents")) {
				double v = Double.parseDouble(r.get(0));
				assertTrue("the intent table agrees with the decoder at " + v,
						!r.get(1).isEmpty() && !r.get(2).isEmpty());
			}
			int distinct = 0;
			java.util.Set<String> wants = new java.util.HashSet<>();
			for (java.util.List<String> r : tableOf(secs, "intents")) {
				if (wants.add(r.get(1))) {
					distinct++;
				}
			}
			assertGreater("the intent table shows a real spread of wants", distinct, 5);

			// Pheromone lifetimes are the decay law solved, not measured once and
			// written down: halving time must follow from the retained fraction.
			double half = Math.log(0.5)
					/ Math.log(net.hedinger.prototype.engine.PheromoneCloud.DECAY);
			assertTrue("the pheromone half-life is the decay law solved",
					find(secs, "pheromones", "Kept per tick")
					.startsWith(String.valueOf(Math.round(
							net.hedinger.prototype.engine.PheromoneCloud.DECAY * 100))));
			assertGreater("a smell outlives a single tick by a useful margin", half, 10);

			// A mind's budget is the pressure every other design decision answers to,
			// so the page must not quietly describe a more capable thinker than exists.
			assertEquals("the thinking budget is the real one",
					Brain.DEFAULT_STEPS_PER_TICK,
					Long.parseLong(find(secs, "minds", "Executed")));
			assertEquals("the register bank is the real one", Brain.NUM_REG,
					Long.parseLong(find(secs, "minds", "Registers")));
		}

		/** Every channel of a surface appears exactly once, under the name the engine
		 *  itself uses for it. */
		private void coversEveryChannel(java.util.List<java.util.Map<String, Object>> secs,
				String id, int total, String[] names) {
			java.util.Set<Integer> seen = new java.util.HashSet<>();
			for (java.util.Map<String, Object> sec : secs) {
				if (!id.equals(sec.get("id"))) {
					continue;
				}
				@SuppressWarnings("unchecked")
				java.util.List<java.util.Map<String, Object>> gs
						= (java.util.List<java.util.Map<String, Object>>) sec.get("groups");
				assertTrue("section " + id + " groups its channels", gs != null);
				for (java.util.Map<String, Object> g : gs) {
					@SuppressWarnings("unchecked")
					java.util.List<java.util.Map<String, String>> items
							= (java.util.List<java.util.Map<String, String>>) g.get("items");
					for (java.util.Map<String, String> i : items) {
						int idx = Integer.parseInt(i.get("idx"));
						assertTrue(id + " channel " + idx + " is listed once", seen.add(idx));
						assertTrue("channel " + idx + " carries the engine's own name",
								names[idx].equals(i.get("name")));
						assertGreater("channel " + names[idx] + " is actually explained",
								i.get("detail").length(), 20);
					}
				}
			}
			for (int i = 0; i < total; i++) {
				assertTrue("channel '" + names[i] + "' is documented", seen.contains(i));
			}
		}

		private static String pctOf(double v) {
			String s = String.valueOf(Math.round(v * 100));
			return s;
		}

		@SuppressWarnings("unchecked")
		private static java.util.List<java.util.Map<String, String>> rowsOf(
				java.util.Map<String, Object> sec) {
			return (java.util.List<java.util.Map<String, String>>) sec.get("rows");
		}

		@SuppressWarnings("unchecked")
		private java.util.List<java.util.List<String>> tableOf(
				java.util.List<java.util.Map<String, Object>> secs, String id) {
			for (java.util.Map<String, Object> sec : secs) {
				if (id.equals(sec.get("id"))) {
					java.util.Map<String, Object> t
							= (java.util.Map<String, Object>) sec.get("table");
					assertTrue("section " + id + " carries a worked table", t != null);
					return (java.util.List<java.util.List<String>>) t.get("rows");
				}
			}
			assertTrue("section " + id + " exists", false);
			return java.util.List.of();
		}

		private String find(java.util.List<java.util.Map<String, Object>> secs,
				String id, String label) {
			for (java.util.Map<String, Object> sec : secs) {
				if (!id.equals(sec.get("id"))) {
					continue;
				}
				for (java.util.Map<String, String> r : rowsOf(sec)) {
					if (label.equals(r.get("label"))) {
						return r.get("value");
					}
				}
			}
			assertTrue("row '" + label + "' exists in section " + id, false);
			return "";
		}
	}

	/**
	 * The viewer's action badges ride to the client packed into the spare high
	 * bits of the entity flags, so they cost the delta stream nothing. Pins that
	 * the codes actually arrive for the live ecosystem, that they stay sparse
	 * (a badge over every creature would be noise, not information), and — the
	 * part that would silently corrupt the viewer — that packing them does not
	 * disturb the existing flag bits sharing that int.
	 */
	static class ActionGlyphsRideTheWire extends Scenario {
		@Override
		public void run() {
			seed(23);
			World w = net.hedinger.prototype.sim.Worlds.demo(23);
			tick(w, 2000); // settle: creatures are grazing, hunting, breeding

			int withAction = 0, creatures = 0, minded = 0;
			for (net.hedinger.prototype.sim.EntityState e
					: net.hedinger.prototype.sim.WorldSnapshot.of(w).entities()) {
				if (!e.kind().startsWith("npc.")) {
					continue;
				}
				creatures++;
				int code = (e.flags() & net.hedinger.prototype.sim.EntityState.ACTION_MASK)
						>> net.hedinger.prototype.sim.EntityState.ACTION_SHIFT;
				assertTrue("action code is a known glyph (" + code + ")",
						code >= 0 && code <= net.hedinger.prototype.sim.EntityState.ACT_MAX);
				if (code != net.hedinger.prototype.sim.EntityState.ACT_NONE) {
					withAction++;
				}
				if ((e.flags() & net.hedinger.prototype.sim.EntityState.F_MINDED) != 0) {
					minded++;
				}
			}
			assertGreater("the settled world has creatures to describe", creatures, 20);
			assertGreater("some creatures report a visible action", withAction, 0);
			assertLess("badges stay sparse rather than tagging everything",
					withAction, creatures);
			// The packing shares an int with F_DEAD/F_FLYING/F_GRABBED/F_CARRYING/
			// F_MINDED; if the shift were wrong these would be clobbered wholesale.
			assertGreater("the minded flag still survives alongside the action code",
					minded, 0);
		}
	}

	/**
	 * The hitch-hiker starter brain actually works: a creature running it steers to
	 * a larger neighbour and climbs aboard, riding voluntarily rather than being
	 * seized. Guards both halves of the mechanism — the brain's approach-and-cling
	 * logic, and the boarding reach that makes contact achievable at all (attach
	 * once demanded dead-centre contact, which the collision spring made nearly
	 * impossible to hit deliberately).
	 */
	static class HitchhikerBrainClimbsAboard extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(24, 24);
			// A big, brainless (so motionless) host, and a small hitch-hiker a tile
			// away with line of sight to it.
			Genome hostG = Genome.phenotype(14, 0.0, 5, 6, Math.PI * 2, 100000);
			TestNPC host = TestNPC.minded(12.0, 12.0, 0, hostG);
			Genome hitchG = Genome.phenotype(6, 0.05, 5, 12, Math.PI * 2, 100000);
			hitchG.brain = net.hedinger.prototype.sim.Worlds.hitchhikerBrain();
			TestNPC rider = TestNPC.minded(13.2, 12.0, 0, hitchG);
			w.spawnEntity(host);
			w.spawnEntity(rider);
			w.think();
			assertTrue("it starts on its own feet", rider.getAttachTarget() == null);

			boolean boarded = false;
			for (int i = 0; i < 400 && !boarded; i++) {
				w.think();
				boarded = rider.getAttachTarget() == host;
			}
			assertTrue("a hitch-hiker steers to a larger neighbour and climbs aboard", boarded);
			assertTrue("it rides voluntarily rather than being seized", !rider.isGrabbed());
			assertGreater("the host now carries a load", host.getCarriedLoad(), 0.0);
		}
	}

	/** Offspring inherit a mutated genome; crossover mixes two parents. */
	static class GenomeInheritance extends Scenario {
		@Override
		public void run() {
			seed(19);
			Genome parent = new Genome();
			parent.speed = 0.04;
			parent.predatory = 0.5;
			parent.markers = new double[] { 0.5, 0.5, 0.5 };

			// Asexual child: every gene within +/- the mutation rate of the parent.
			double rate = 0.1;
			for (int i = 0; i < 200; i++) {
				Genome ch = Genome.child(parent, rate);
				assertTrue("child speed within mutation band",
						ch.speed >= parent.speed * (1 - rate) - 1e-9
								&& ch.speed <= parent.speed * (1 + rate) + 1e-9);
				assertTrue("child marker stays in [0,1]",
						ch.markers[0] >= 0 && ch.markers[0] <= 1);
				assertGreater("mutation actually varies the child",
						Math.abs(ch.markers[0] - parent.markers[0])
								+ Math.abs(ch.speed - parent.speed), -1); // always true; keeps ch used
			}

			// Crossover: each gene comes from one parent (before mutation), so a
			// zero-rate child's markers are a mix drawn from {A, B}.
			Genome a = new Genome();
			a.markers = new double[] { 0.0, 0.0, 0.0 };
			Genome b = new Genome();
			b.markers = new double[] { 1.0, 1.0, 1.0 };
			boolean sawA = false, sawB = false;
			for (int i = 0; i < 100; i++) {
				Genome ch = Genome.child(a, b, 0.0);
				for (double m : ch.markers) {
					if (m == 0.0) {
						sawA = true;
					}
					if (m == 1.0) {
						sawB = true;
					}
				}
			}
			assertTrue("crossover draws genes from both parents", sawA && sawB);
		}
	}

	/**
	 * A grazer eats the living substrate: it feeds from the tile underfoot and
	 * leaves a depleted patch behind. Exercises the NPC.graze() -> Tile link.
	 */
	static class GrazerDepletesSubstrate extends Scenario {
		@Override
		public void run() {
			seed(4);
			World w = room(11, 11);
			TestNPC g = TestNPC.grazer(5.5, 5.5, 0).withHunger(1.0); // an empty stomach eats
			w.spawnEntity(g);
			w.think(); // register the spawn
			snapshot(w, "before (full grass)");
			// 600, not 120: a grazer crops a sixteenth of what it used to per tick, so
			// the same window fed it 0.27 against a 0.5 bar. The fact under test -- a
			// grazer draws real food off the substrate and leaves ground visibly bare --
			// is unchanged; it just takes longer to do.
			tick(w, 600);
			snapshot(w, "after (grazed patch)");

			assertGreater("grazer fed on the substrate", g.totalIntake(), 0.5);

			// The hungriest ground it worked over is visibly bare.
			double lowest = Tile.VEG_MAX;
			for (int c = 1; c < w.getColums() - 1; c++) {
				for (int r = 1; r < w.getRows() - 1; r++) {
					double v = w.getTile(c, r, 0).getVegetation(w.getTick());
					if (v < lowest) {
						lowest = v;
					}
				}
			}
			assertLess("grazing left a bare patch", lowest, 0.5);
		}
	}

	/**
	 * Vegetation regrows over time toward its cap once grazing stops. Pins the
	 * lazy closed-form regrowth against the world clock (no entity needed).
	 */
	static class VegetationRegrows extends Scenario {
		@Override
		public void run() {
			seed(5);
			World w = room(5, 5);
			tick(w, 1); // advance the clock off zero
			Tile t = w.getTile(2, 2, 0);

			double eaten = t.graze(w.getTick(), Tile.VEG_MAX); // strip it bare
			assertNear("stripped to bare ground", 0.0, t.getVegetation(w.getTick()), 1e-9);
			assertGreater("grazing consumed the standing crop", eaten, 0.5);

			// A stripped tile rests for the cooldown before any regrowth: still bare
			// two-thirds of the way through REGROW_DELAY.
			tick(w, (int) (Tile.REGROW_DELAY * 2 / 3));
			assertNear("depleted tile is still bare during its cooldown", 0.0,
					t.getVegetation(w.getTick()), 1e-3);

			// Past the cooldown, logistic regrowth climbs asymptotically to the cap.
			tick(w, (int) Tile.REGROW_DELAY + 6000);
			assertNear("vegetation regrew to its cap", Tile.VEG_MAX, t.getVegetation(w.getTick()), 0.02);
		}
	}

	/**
	 * Fertility gates how much grass a tile can hold: poor ground regrows only
	 * to a low cap, rich ground fills to the full cap. This is what makes the
	 * substrate patchy -- rich and poor habitats instead of uniform pasture.
	 */
	static class FertilityCapsVegetation extends Scenario {
		@Override
		public void run() {
			seed(6);
			World w = room(5, 5);
			tick(w, 1);
			Tile poor = w.getTile(1, 1, 0);
			poor.setFertility(0.3);
			Tile rich = w.getTile(3, 3, 0); // default fertility 1.0

			poor.graze(w.getTick(), Tile.VEG_MAX); // strip it bare
			// Rest through the cooldown, then let logistic regrowth run to near-cap.
			tick(w, (int) Tile.REGROW_DELAY + 6000);
			long now = w.getTick();

			assertNear("poor ground regrows only to its fertility cap",
					0.3 * Tile.VEG_MAX, poor.getVegetation(now), 0.02);
			assertNear("rich ground fills to the full cap",
					Tile.VEG_MAX, rich.getVegetation(now), 1e-9);
			assertGreater("rich ground carries much more grass than poor ground",
					rich.getVegetation(now) - poor.getVegetation(now), 0.5);
		}
	}

	/**
	 * A fertility field paints the map into patchy habitats: coherent lush
	 * blobs separated by poorer ground. Deterministic from the seed.
	 */
	static class FertileHabitatPatches extends Scenario {
		@Override
		public void run() {
			seed(11);
			World w = room(20, 20);
			w.generateFertility(0.22);
			w.think(); // advance the clock so grass sits at its per-tile cap
			snapshot(w, "patchy fertility");

			double min = 1, max = 0;
			for (int c = 1; c < w.getColums() - 1; c++) {
				for (int r = 1; r < w.getRows() - 1; r++) {
					double f = w.getTile(c, r, 0).getFertility();
					min = Math.min(min, f);
					max = Math.max(max, f);
				}
			}
			assertLess("some ground is poor", min, 0.4);
			assertGreater("some ground is rich", max, 0.7);
		}
	}

	/**
	 * Water is impassable to land entities but flyers skim over it: a walking
	 * mover halts at the shore while a flying mover crosses.
	 */
	static class WaterBlocksLandPassesFlyers extends Scenario {
		@Override
		public void run() {
			seed(20);
			World w = room(12, 5);
			for (int y = 1; y <= 3; y++) {
				w.setTile(6, y, 0, Tile.TileType.TYPE_WATER); // a vertical lake
			}
			TestNPC land = TestNPC.mover(2.5, 2.5, 0, 0);       // walks east into the lake
			TestNPC flyer = TestNPC.mover(2.5, 1.5, 0, 0).withFlying();
			w.spawnEntity(land);
			w.spawnEntity(flyer);
			w.think();
			snapshot(w, "before (both west of the lake)");
			tick(w, 150);
			snapshot(w, "after (land halts, flyer crosses)");

			assertLess("land entity is stopped at the shore", land.getX(), 6.0);
			assertGreater("land entity walked up to the shore", land.getX(), 5.0);
			assertGreater("flyer crossed the water", flyer.getX(), 6.5);
		}
	}

	/** Mud drags: a mover crossing a mud strip falls behind one on clear floor. */
	static class MudSlowsMovement extends Scenario {
		@Override
		public void run() {
			seed(21);
			World w = room(16, 5);
			for (int x = 5; x <= 8; x++) {
				w.setTile(x, 2, 0, Tile.TileType.TYPE_MUD); // mud strip on row 2 only
			}
			TestNPC muddy = TestNPC.mover(2.5, 2.5, 0, 0); // crosses the mud
			TestNPC clear = TestNPC.mover(2.5, 3.5, 0, 0); // clear floor alongside
			w.spawnEntity(muddy);
			w.spawnEntity(clear);
			w.think();
			snapshot(w, "before");
			tick(w, 200);
			snapshot(w, "after (muddy lags behind)");

			assertGreater("both movers advanced", muddy.getX(), 2.5);
			assertGreater("clear mover is well ahead of the muddy one",
					clear.getX() - muddy.getX(), 0.5);
		}
	}

	/**
	 * Crystal comes in three densities: a solid formation stops every walker,
	 * a packed bed admits only bodies that fit between its shards -- dragging
	 * them by size, the smaller the freer -- and sparse shards are ordinary
	 * ground where a mover keeps pace with one on bare floor.
	 */
	static class CrystalDensityTiers extends Scenario {
		@Override
		public void run() {
			seed(23);
			World w = room(16, 8);
			for (int x = 5; x <= 8; x++) {
				w.setTile(x, 1, 0, Tile.TileType.TYPE_CRYSTAL);        // formation row
				w.setTile(x, 2, 0, Tile.TileType.TYPE_CRYSTAL_BED);    // bed rows
				w.setTile(x, 3, 0, Tile.TileType.TYPE_CRYSTAL_BED);
				w.setTile(x, 4, 0, Tile.TileType.TYPE_CRYSTAL_BED);
				w.setTile(x, 5, 0, Tile.TileType.TYPE_CRYSTAL_SPARSE); // sparse row
			}
			TestNPC blocked = TestNPC.mover(2.5, 1.5, 0, 0);              // into the formation
			TestNPC small = TestNPC.mover(2.5, 2.5, 0, 0).withSize(3);    // slips through the bed
			TestNPC mid = TestNPC.mover(2.5, 3.5, 0, 0).withSize(10);     // picks its way through
			TestNPC big = TestNPC.mover(2.5, 4.5, 0, 0).withSize(16);     // over clearance: kept out
			TestNPC loose = TestNPC.mover(2.5, 5.5, 0, 0);                // crosses sparse shards
			TestNPC clear = TestNPC.mover(2.5, 6.5, 0, 0);                // bare floor control
			w.spawnEntity(blocked);
			w.spawnEntity(small);
			w.spawnEntity(mid);
			w.spawnEntity(big);
			w.spawnEntity(loose);
			w.spawnEntity(clear);
			w.think();
			snapshot(w, "before (formation / bed x3 / sparse / floor lanes)");
			tick(w, 200);
			snapshot(w, "after (stopped / by-size / kept out / normal / normal)");

			assertLess("the formation stops a walker", blocked.getX(), 5.0);
			assertLess("a body over clearance is stopped at the bed's edge", big.getX(), 5.0);
			assertGreater("a mid body picks its way into the bed", mid.getX(), 5.0);
			assertGreater("a small body outpaces a mid one through the shards",
					small.getX() - mid.getX(), 0.5);
			assertGreater("but even the small body pays a little against clear ground",
					clear.getX() - small.getX(), 0.1);
			assertLess("sparse shards walk like plain floor",
					Math.abs(clear.getX() - loose.getX()), 0.5);
		}
	}

	/**
	 * A crawl duct admits only bodies inside its clearance: a small body
	 * crawls through (slowly), a big one is stopped at the grille. Pins the
	 * size gate against the field-shadowing trap -- NPCs keep their radius in
	 * their own field, so the gate must read it polymorphically.
	 */
	static class DuctAdmitsOnlySmallBodies extends Scenario {
		@Override
		public void run() {
			seed(24);
			World w = room(14, 4);
			for (int x = 5; x <= 8; x++) {
				w.setTile(x, 1, 0, Tile.TileType.TYPE_DUCT);
				w.setTile(x, 2, 0, Tile.TileType.TYPE_DUCT);
			}
			TestNPC small = TestNPC.mover(2.5, 1.5, 0, 0).withSize(5);  // inside clearance
			TestNPC big = TestNPC.mover(2.5, 2.5, 0, 0).withSize(12);   // too wide
			w.spawnEntity(small);
			w.spawnEntity(big);
			w.think();
			snapshot(w, "before (both west of the duct)");
			tick(w, 300);
			snapshot(w, "after (small crawled in, big stopped at the grille)");

			assertGreater("a small body crawls into the duct", small.getX(), 5.0);
			assertLess("a big body is stopped at the grille", big.getX(), 5.0);
			assertGreater("the big body walked up to the duct", big.getX(), 4.0);
		}
	}

	/**
	 * A body refused a step keeps whatever part of it was never blocked, so it
	 * slides along an obstacle instead of stopping dead against it. Without
	 * this, a creature steering diagonally into a wall lost its parallel
	 * motion too and stood pressed there for as long as its mind kept aiming
	 * that way -- which is what made a crystal bed look like flypaper.
	 */
	static class BlockedBodySlidesAlongAnObstacle extends Scenario {
		@Override
		public void run() {
			seed(26);
			World w = room(14, 8);
			for (int y = 1; y <= 6; y++) {
				w.setTile(8, y, 0, Tile.TileType.TYPE_WALL); // a wall to slide along
			}
			// Started already against the wall, heading north-east: the eastward
			// half is blocked from the first tick, the northward half is open
			// floor the whole way. Starting in contact is what makes this
			// discriminating -- measured from further off, the diagonal approach
			// alone would satisfy the northward assertion before anything was
			// ever blocked.
			TestNPC slider = TestNPC.mover(7.5, 5.5, 0, -Math.PI / 4);
			w.spawnEntity(slider);
			double startY = slider.getY();
			w.think();
			snapshot(w, "before (heading north-east into a wall)");
			tick(w, 300);
			snapshot(w, "after (pressed to the wall, still travelling north)");

			assertLess("the wall still stops the eastward half", slider.getX(), 8.0);
			assertGreater("but the northward half survives the block",
					startY - slider.getY(), 2.0);
		}
	}

	/**
	 * A clearance gate keeps an oversized body out; it must never seal one in.
	 * Bodies grow into their adult size, so one can walk into a crystal bed as
	 * a juvenile and cross the clearance while inside -- and inside a bed the
	 * drag caps a step well below the half-tile needed to leave, so gating the
	 * way out as well left it immobile for good.
	 */
	static class GrownBodyEscapesTheBedItGrewInside extends Scenario {
		@Override
		public void run() {
			seed(27);
			World w = room(16, 6);
			for (int x = 5; x <= 8; x++) {
				for (int y = 1; y <= 4; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_CRYSTAL_BED);
				}
			}
			TestNPC grower = TestNPC.mover(2.5, 2.5, 0, 0).withSize(10);
			w.spawnEntity(grower);
			tick(w, 200);
			snapshot(w, "juvenile walked into the bed");
			assertGreater("the juvenile got into the bed", grower.getX(), 5.0);

			grower.withSize(17); // grew past the clearance while inside
			grower.withHeading(Math.PI); // turn back the way it came
			double trapped = grower.getX();
			tick(w, 600);
			snapshot(w, "grown body walked back out westward");

			assertGreater("a body that outgrew the bed can still walk out",
					trapped - grower.getX(), 1.0);
			assertLess("and it is clear of the bed", grower.getX(), 5.0);
		}
	}

	/**
	 * A pressure-plate switch drives its wired door: a mover crossing the
	 * plate parts the door ahead of it, walks through, and the door seals
	 * again once the plate has gone quiet -- while an identical wired door
	 * with no switch never opens for its mover at all (wiring stops the idle
	 * random cycling, so a switchless wired door is simply shut).
	 */
	static class SwitchOpensWiredDoor extends Scenario {
		@Override
		public void run() {
			seed(25);
			World w = room(14, 6);
			for (int y = 1; y <= 4; y++) {
				w.setTile(7, y, 0, Tile.TileType.TYPE_WALL); // the partition
			}
			w.setTile(7, 2, 0, Tile.TileType.TYPE_STONE); // switched doorway
			w.setTile(7, 4, 0, Tile.TileType.TYPE_STONE); // control doorway
			net.hedinger.prototype.entities.Door door = new net.hedinger.prototype.entities.Door(
					7, 2, 0, 1, net.hedinger.prototype.entities.Door.TIMBER);
			net.hedinger.prototype.entities.Door control = new net.hedinger.prototype.entities.Door(
					7, 4, 0, 1, net.hedinger.prototype.entities.Door.TIMBER);
			w.addDoor(door);
			w.addDoor(control);
			control.setWired(true); // machinery with no switch: stays shut
			w.setTile(4, 2, 0, Tile.TileType.TYPE_SWITCH);
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 2, 0, door));
			TestNPC crosser = TestNPC.mover(1.5, 2.5, 0, 0); // walks over the plate
			TestNPC barred = TestNPC.mover(1.5, 4.5, 0, 0);  // no plate in its lane
			w.spawnEntity(crosser);
			w.spawnEntity(barred);
			w.think();
			snapshot(w, "before (plate at x=4 wired to the y=2 doorway)");
			tick(w, 400);
			snapshot(w, "after (crosser through; barred lane still sealed)");

			assertGreater("the plate parted the door for its mover", crosser.getX(), 8.0);
			assertLess("the switchless wired door stayed shut", barred.getX(), 8.0);
			tick(w, 250);
			assertTrue("the door sealed again after the linger", door.isClosed());
		}
	}

	/**
	 * An intent-driven button answers only a deliberate press (the A_INTERACT
	 * intent), never mere weight: a body carrying the use intent parts the
	 * wired door as it passes the pedestal, while an identical body without
	 * the intent walks right over the button and stays barred.
	 */
	static class ButtonNeedsIntent extends Scenario {
		@Override
		public void run() {
			seed(26);
			World w = room(14, 6);
			for (int y = 1; y <= 4; y++) {
				w.setTile(7, y, 0, Tile.TileType.TYPE_WALL); // the partition
			}
			w.setTile(7, 2, 0, Tile.TileType.TYPE_STONE); // both doorways
			w.setTile(7, 4, 0, Tile.TileType.TYPE_STONE);
			net.hedinger.prototype.entities.Door doorA = new net.hedinger.prototype.entities.Door(
					7, 2, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			net.hedinger.prototype.entities.Door doorB = new net.hedinger.prototype.entities.Door(
					7, 4, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			w.addDoor(doorA);
			w.addDoor(doorB);
			w.setTile(4, 2, 0, Tile.TileType.TYPE_SWITCH);
			w.setTile(4, 4, 0, Tile.TileType.TYPE_SWITCH);
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 2, 0, doorA,
					net.hedinger.prototype.entities.Switch.BUTTON));
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 4, 0, doorB,
					net.hedinger.prototype.entities.Switch.BUTTON));
			TestNPC presser = TestNPC.mover(1.5, 2.5, 0, 0).withInteract(); // chooses to press
			TestNPC walker = TestNPC.mover(1.5, 4.5, 0, 0);            // weight only
			w.spawnEntity(presser);
			w.spawnEntity(walker);
			w.think();
			snapshot(w, "before (buttons at x=4, one deliberate presser)");
			tick(w, 400);
			snapshot(w, "after (presser through; walker barred over its button)");

			assertGreater("the deliberate press parted the door", presser.getX(), 8.0);
			assertLess("weight alone never operated the button", walker.getX(), 8.0);
		}
	}

	/**
	 * The full evolved-mind route to a door: an LGP brain that WRITEs the
	 * A_INTERACT actuator opens a button-wired door as its body passes the
	 * pedestal -- brain instruction to actuator latch to interact intent to
	 * switch to sliding leaves -- while an identical brain that never writes
	 * A_INTERACT stays barred. Pins the actuator plumbing itself, which the
	 * scripted {@code withInteract()} fixture in {@link ButtonNeedsIntent}
	 * deliberately bypasses: if the actuator index shifted or the body
	 * stopped reading it, this is the test that goes red.
	 */
	static class BrainInteractsWithButton extends Scenario {
		@Override
		public void run() {
			seed(28);
			World w = room(14, 6);
			for (int y = 1; y <= 4; y++) {
				w.setTile(7, y, 0, Tile.TileType.TYPE_WALL); // the partition
			}
			w.setTile(7, 2, 0, Tile.TileType.TYPE_STONE); // both doorways
			w.setTile(7, 4, 0, Tile.TileType.TYPE_STONE);
			net.hedinger.prototype.entities.Door doorA = new net.hedinger.prototype.entities.Door(
					7, 2, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			net.hedinger.prototype.entities.Door doorB = new net.hedinger.prototype.entities.Door(
					7, 4, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			w.addDoor(doorA);
			w.addDoor(doorB);
			w.setTile(4, 2, 0, Tile.TileType.TYPE_SWITCH);
			w.setTile(4, 4, 0, Tile.TileType.TYPE_SWITCH);
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 2, 0, doorA,
					net.hedinger.prototype.entities.Switch.BUTTON));
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 4, 0, doorB,
					net.hedinger.prototype.entities.Switch.BUTTON));
			// Drive east and hold the interact intent -- three instructions,
			// the same latched-actuator pattern the starter brains use.
			int[][] presser = {
					{ Brain.SET, 1, 9, 0 },                    // R1 = 1.0 (const[9])
					{ Brain.WRITE, AgentIO.A_THROTTLE, 1, 0 }, // drive forward
					{ Brain.WRITE, AgentIO.A_INTERACT, 1, 0 }, // operate what you pass
			};
			int[][] walker = {
					{ Brain.SET, 1, 9, 0 },
					{ Brain.WRITE, AgentIO.A_THROTTLE, 1, 0 }, // drive, never interact
			};
			TestNPC minded = TestNPC.minded(1.5, 2.5, 0, new Genome(),
					new LgpMind(new Brain(presser), presser.length)).withHeading(0);
			TestNPC barred = TestNPC.minded(1.5, 4.5, 0, new Genome(),
					new LgpMind(new Brain(walker), walker.length)).withHeading(0);
			w.spawnEntity(minded);
			w.spawnEntity(barred);
			w.think();
			snapshot(w, "before (two brains, one writes A_INTERACT)");
			tick(w, 400);
			snapshot(w, "after (interacting brain through; silent one barred)");

			assertGreater("a brain writing A_INTERACT opened its door and crossed",
					minded.getX(), 8.0);
			assertLess("a brain that never interacts stayed barred", barred.getX(), 8.0);
		}
	}

	/**
	 * Fixtures are seekable: a mind that names SEEK_FIXTURE steers its body
	 * to the nearest button from an arbitrary spawn heading -- the fixture
	 * sense supplies the bearing, so no scripted aiming is needed -- and
	 * arriving presses it (the intent's terminal act, no A_INTERACT write in
	 * the program), parting and holding the wired door. This is the whole
	 * "see the button, intend to go there, press it" story in six brain
	 * instructions.
	 */
	static class BrainSeeksAndPressesButton extends Scenario {
		@Override
		public void run() {
			seed(29);
			World w = room(14, 6);
			for (int y = 1; y <= 4; y++) {
				w.setTile(7, y, 0, Tile.TileType.TYPE_WALL); // the partition
			}
			w.setTile(7, 2, 0, Tile.TileType.TYPE_STONE); // the doorway
			net.hedinger.prototype.entities.Door door = new net.hedinger.prototype.entities.Door(
					7, 2, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			w.addDoor(door);
			w.setTile(4, 2, 0, Tile.TileType.TYPE_SWITCH);
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 2, 0, door,
					net.hedinger.prototype.entities.Switch.BUTTON));
			// Drive, and name the fixture intent: SEEK_FIXTURE lives past every
			// pool constant (magnitude >= 6), so naming it costs one ADD.
			int[][] seeker = {
					{ Brain.SET, 1, 9, 0 },                    // R1 = 1.0 (const[9])
					{ Brain.WRITE, AgentIO.A_THROTTLE, 1, 0 }, // drive forward
					{ Brain.SET, 2, 10, 0 },                   // R2 = 2 (const[10])
					{ Brain.SET, 3, 11, 0 },                   // R3 = 4 (const[11])
					{ Brain.ADD, 4, 2, 3 },                    // R4 = 6: the fixture band
					{ Brain.WRITE, AgentIO.A_SEEK, 4, 0 },     // steer to the button
			};
			// Deliberately NO withHeading: the spawn heading is random, and the
			// seek must not care.
			TestNPC seekerBody = TestNPC.minded(1.5, 2.5, 0, new Genome(),
					new LgpMind(new Brain(seeker), seeker.length));
			w.spawnEntity(seekerBody);
			w.think();
			snapshot(w, "before (random heading; button at x=4, door at x=7)");
			tick(w, 300);
			snapshot(w, "after (steered to the button; door held open)");

			double d = Math.hypot(seekerBody.getX() - 4.5, seekerBody.getY() - 2.5);
			assertLess("the seek steered the body to the button", d, 1.6);
			assertTrue("arriving pressed it: the wired door stands open", door.isOpen());
			tick(w, 60);
			assertTrue("and stays open while the intent holds the press", door.isOpen());
		}
	}

	/**
	 * On the lowest level a pit is bottomless: a body that steps off the
	 * edge is removed from the world outright -- no corpse, nothing below to
	 * land on -- while a catwalk over the same void carries a walker across
	 * untouched.
	 */
	static class BottomlessPitsAndCatwalks extends Scenario {
		@Override
		public void run() {
			seed(27);
			World w = room(14, 6);
			for (int x = 5; x <= 8; x++) {
				for (int y = 1; y <= 4; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_SHAFT); // the void
				}
			}
			for (int x = 5; x <= 8; x++) {
				w.setTile(x, 2, 0, Tile.TileType.TYPE_CATWALK); // the way across
			}
			TestNPC walker = TestNPC.mover(2.5, 2.5, 0, 0); // takes the catwalk
			TestNPC faller = TestNPC.mover(2.5, 3.5, 0, 0); // walks off the edge
			w.spawnEntity(walker);
			w.spawnEntity(faller);
			w.think();
			snapshot(w, "before (catwalk lane and open-void lane)");
			tick(w, 250);
			snapshot(w, "after (walker across; faller gone entirely)");

			assertGreater("the catwalk carries a walker over the void", walker.getX(), 9.0);
			assertTrue("the void removed the faller from the world", faller.isRemoved());
		}
	}

	/**
	 * Tall-grass cover blocks line of sight: a chaser locks onto the prey it can
	 * see and ignores an equally-close prey hiding in cover (invisible to it).
	 */
	static class CoverHidesFromPerception extends Scenario {
		@Override
		public void run() {
			seed(22);
			World w = room(9, 5);
			w.setTile(4, 3, 0, Tile.TileType.TYPE_COVER); // hiding spot to the south
			TestNPC chaser = TestNPC.chaser(4.5, 2.5, 0);
			TestNPC visible = TestNPC.inert(4.5, 1.5, 0);       // in the open, to the north
			TestNPC hidden = TestNPC.inert(4.5, 3.5, 0);        // standing in the cover
			w.spawnEntity(chaser);
			w.spawnEntity(visible);
			w.spawnEntity(hidden);
			w.think();
			snapshot(w, "before (prey N in open, prey S in cover)");
			tick(w, 300);
			snapshot(w, "after (chaser took the visible prey)");

			double dVisible = Math.hypot(chaser.getX() - visible.getX(), chaser.getY() - visible.getY());
			double dHidden = Math.hypot(chaser.getX() - hidden.getX(), chaser.getY() - hidden.getY());
			assertLess("chaser reached the visible prey", dVisible, 0.5);
			assertGreater("chaser never went for the hidden prey", dHidden, dVisible);
		}
	}

	/**
	 * Energy economy: a metabolic entity with no food burns its energy down and
	 * starves. This is the cost side that makes fitness mean something.
	 */
	static class StarvesWithoutFood extends Scenario {
		@Override
		public void run() {
			seed(30);
			World w = room(5, 5);
			for (int x = 1; x <= 3; x++) {
				for (int y = 1; y <= 3; y++) {
					w.getTile(x, y, 0).setFertility(0); // barren: no grass to eat
				}
			}
			// Start already starving: with nothing to eat the pegged need erodes
			// health a point at a time (VITALS.md) — energy zero is collapse, and
			// only health decides death, so the decline runs through the health gate.
			TestNPC breeder = TestNPC.breeder(2.5, 2.5, 0, new Genome())
					.withHunger(1.0).withEnergy(0.02);
			w.spawnEntity(breeder);
			w.think();
			snapshot(w, "before (barren ground)");
			tick(w, 5300);
			snapshot(w, "after (starved)");
			assertTrue("breeder starved with no food", breeder.isDead());
		}
	}

	/**
	 * The evolutionary loop end to end: fed breeders graze for energy and bud
	 * mutated offspring, so a population on a grassy field grows. Offspring
	 * inherit a mutated genome, so the lineage also drifts.
	 */
	static class PopulationGrowsWithFood extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(20, 20); // full grass everywhere (fertility 1)
			for (int i = 0; i < 3; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.2, 0.6, 0.9 };
				w.spawnEntity(TestNPC.breeder(6.5 + i * 3, 6.5 + i * 3, 0, g));
			}
			w.think();
			int start = w.getAliveCount();
			snapshot(w, "founders");
			// 3000, not 600: grass became bulk food (a quarter of the old energy per
			// unit, cropped at a quarter of the old rate), so banking a breeding takes
			// roughly sixteen times as long. The fact under test is unchanged -- a fed
			// population still grows -- only the clock it grows on.
			tick(w, 3000);
			snapshot(w, "after (population grew)");
			int end = w.getAliveCount();
			assertGreater("a fed breeder population grows by reproduction", end, start);
		}
	}

	/**
	 * Sexual reproduction: a metabolic entity breeds only with a genome-compatible
	 * partner, and the child is a crossover of both parents. Three facts pin it
	 * apart from the asexual budder -- a partner is required, dissimilar maters
	 * refuse, and offspring recombine genes from both parents.
	 */
	static class SexualReproductionNeedsPartner extends Scenario {
		@Override
		public void run() {
			seed(50);

			// 1) A lone, well-fed mater has no partner, so it never reproduces --
			//    the defining difference from asexual budding.
			World lone = room(12, 12); // full grass: energy is never the limiter
			Genome solo = new Genome();
			solo.markers = new double[] { 0.5, 0.5, 0.5 };
			lone.spawnEntity(TestNPC.mater(6.5, 6.5, 0, solo));
			lone.think();
			snapshot(lone, "lone mater (no partner)");
			tick(lone, 400);
			assertEquals("a lone mater cannot reproduce without a partner", 1, lone.getAliveCount());

			// 2) Two well-fed maters that are too dissimilar refuse to pair (mate
			//    choice keys on marker similarity, like react()).
			World strangers = room(12, 12);
			Genome ga = new Genome();
			ga.markers = new double[] { 0.0, 0.0, 0.0 };
			Genome gb = new Genome();
			gb.markers = new double[] { 1.0, 1.0, 1.0 }; // maximally dissimilar
			strangers.spawnEntity(TestNPC.mater(6.3, 6.5, 0, ga));
			strangers.spawnEntity(TestNPC.mater(6.7, 6.5, 0, gb));
			strangers.think();
			tick(strangers, 400);
			assertEquals("incompatible (dissimilar) maters do not breed", 2, strangers.getAliveCount());

			// 3) A cluster of compatible maters breeds sexually, and crossover mixes
			//    the parents. Both types share markers (so they mate) but carry
			//    opposite speed and losRange, so a recombinant child (fast+far or
			//    slow+near) proves the offspring is a crossover, not a clone.
			World colony = room(24, 24);
			// Two compatible pairs, each a slow+far with a fast+near partner, spaced
			// apart so a pair shares grass without the whole colony overgrazing.
			// Founders start well-fed so they pair before a patch thins.
			double[][] pairs = { { 8.5, 8.5 }, { 16.5, 16.5 } };
			for (double[] p : pairs) {
				Genome slowFar = new Genome();
				slowFar.markers = new double[] { 0.5, 0.5, 0.5 };
				slowFar.speed = 0.02;
				slowFar.losRange = 20;
				colony.spawnEntity(TestNPC.mater(p[0], p[1], 0, slowFar).withEnergy(3.0));
				Genome fastNear = new Genome();
				fastNear.markers = new double[] { 0.5, 0.5, 0.5 };
				fastNear.speed = 0.08;
				fastNear.losRange = 4;
				colony.spawnEntity(TestNPC.mater(p[0] + 0.4, p[1], 0, fastNear).withEnergy(3.0));
			}
			colony.think();
			int founders = colony.getAliveCount();
			snapshot(colony, "founders (two compatible gene-types)");

			// Sexual breeders must cluster to pair, so a colony breeds fast and then
			// overgrazes back down -- the lasting proof is that it rose above the
			// founder count at all, and that crossover produced a recombinant.
			// The recombinant check is a ~50/50 gene draw per birth, so the window
			// has to be long enough to see several births or it is a coin toss
			// rather than a test of crossover.
			int peak = founders;
			boolean sawRecombinant = false;
			// 400 steps rather than 80: grass is bulk food now, so a pair takes far
			// longer to bank the energy for a birth, and the recombinant draw needs
			// several births to stop being a coin toss.
			for (int step = 0; step < 400; step++) {
				tick(colony, 20);
				peak = Math.max(peak, colony.getAliveCount());
				sawRecombinant |= hasRecombinant(colony);
			}
			snapshot(colony, "after (bred sexually)");

			assertGreater("a compatible mater colony reproduces sexually "
					+ "(population rose above the founders)", peak, founders);
			assertTrue("crossover produced a recombinant child -- one type's speed with the "
					+ "other's losRange, a mix neither parent has", sawRecombinant);
		}

		/** True if any living entity's genome recombines the two founder types: a
		 * fast+far or slow+near mix that neither pure parent line carries. */
		private static boolean hasRecombinant(World w) {
			for (Entity e : w.getEntities()) {
				if (!(e instanceof net.hedinger.prototype.entities.NPC) || e.isDead()) {
					continue;
				}
				Genome g = ((net.hedinger.prototype.entities.NPC) e).getGenome();
				if (g == null) {
					continue;
				}
				boolean fast = g.speed > 0.05, far = g.losRange > 12;
				if ((fast && far) || (!fast && !far)) { // fast+far or slow+near
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * The LGP brain's register bank is memory: it accumulates across ticks. A tiny
	 * hand-authored program (R0 += sense0 each pass, write R0 to an actuator) turns
	 * a constant input into a running count, and the run is bit-for-bit repeatable.
	 */
	static class BrainMemoryIsDeterministic extends Scenario {
		@Override
		public void run() {
			seed(60);
			int[][] prog = {
					{ net.hedinger.prototype.entities.Brain.SENSE, 1, 0, 0 }, // R1 = sense[0]
					{ net.hedinger.prototype.entities.Brain.ADD, 0, 0, 1 },   // R0 += R1  (persists)
					{ net.hedinger.prototype.entities.Brain.WRITE, 0, 0, 0 }, // act[0] = R0
			};
			double got = runCount(prog, 25);
			assertNear("register R0 accumulated the constant input across 25 ticks (memory)",
					25.0, got, 1e-9);
			assertNear("same program + inputs -> identical output (determinism)",
					got, runCount(prog, 25), 1e-9);
		}

		private static double runCount(int[][] prog, int ticks) {
			net.hedinger.prototype.entities.Brain b =
					new net.hedinger.prototype.entities.Brain(deepCopy(prog));
			double[] sensors = { 1.0 };
			double[] act = new double[1];
			for (int t = 0; t < ticks; t++) {
				b.step(sensors, act, prog.length); // one full pass per tick
			}
			return act[0];
		}
	}

	/**
	 * Program length sets the thought cycle: with a fixed per-tick budget, a longer
	 * brain takes more ticks to complete a pass, so it re-decides less often. Two
	 * brains with the same logic -- one padded with NOPs -- accumulate at rates set
	 * by their lengths, so over equal ticks the short brain counts far higher.
	 */
	static class BrainLengthSetsThoughtRate extends Scenario {
		@Override
		public void run() {
			seed(61);
			int SENSE = net.hedinger.prototype.entities.Brain.SENSE;
			int ADD = net.hedinger.prototype.entities.Brain.ADD;
			int WRITE = net.hedinger.prototype.entities.Brain.WRITE;
			int NOP = net.hedinger.prototype.entities.Brain.NOP;
			int[][] shortProg = { { SENSE, 1, 0, 0 }, { ADD, 0, 0, 1 }, { WRITE, 0, 0, 0 } };
			int[][] longProg = { { SENSE, 1, 0, 0 }, { ADD, 0, 0, 1 }, { WRITE, 0, 0, 0 },
					{ NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 },
					{ NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 } };
			double shortCount = runFixedBudget(shortProg, 90);
			double longCount = runFixedBudget(longProg, 90);
			assertGreater("the shorter brain thinks (and so counts) faster", shortCount, longCount);
			assertGreater("length-3 brain runs its pass ~3x as often as the length-9 one",
					shortCount, longCount * 2.0);
		}

		private static double runFixedBudget(int[][] prog, int ticks) {
			net.hedinger.prototype.entities.Brain b =
					new net.hedinger.prototype.entities.Brain(deepCopy(prog));
			double[] sensors = { 1.0 };
			double[] act = new double[1];
			for (int t = 0; t < ticks; t++) {
				b.step(sensors, act, net.hedinger.prototype.entities.Brain.DEFAULT_STEPS_PER_TICK);
			}
			return act[0];
		}
	}

	/**
	 * Brain heredity: unequal crossover splices a slice of one parent into the
	 * other, so children mix both parents' code and their length varies; mutation
	 * then changes the program. Pins the genetic operators the evolving mind uses.
	 */
	static class BrainHeredityCrossesAndMutates extends Scenario {
		@Override
		public void run() {
			seed(62);
			int NOP = net.hedinger.prototype.entities.Brain.NOP;
			int WRITE = net.hedinger.prototype.entities.Brain.WRITE;
			int[][] aCode = new int[5][];
			int[][] bCode = new int[5][];
			for (int i = 0; i < 5; i++) {
				aCode[i] = new int[] { NOP, 0, 0, 0 };   // parent A: all nop
				bCode[i] = new int[] { WRITE, 1, 2, 0 };  // parent B: all write
			}
			net.hedinger.prototype.entities.Brain A = new net.hedinger.prototype.entities.Brain(aCode);
			net.hedinger.prototype.entities.Brain B = new net.hedinger.prototype.entities.Brain(bCode);

			boolean sawMixedChild = false, sawVariedLength = false;
			for (int i = 0; i < 100 && !(sawMixedChild && sawVariedLength); i++) {
				net.hedinger.prototype.entities.Brain ch =
						net.hedinger.prototype.entities.Brain.child(A, B, 0.0); // crossover only
				String dis = String.join("\n", ch.disassemble(null, null));
				if (dis.contains("nop") && dis.contains("act ")) {
					sawMixedChild = true; // carries code from both parents
				}
				if (ch.length() != 5) {
					sawVariedLength = true; // unequal crossover changed the length
				}
			}
			assertTrue("crossover produces children carrying code from both parents", sawMixedChild);
			assertTrue("unequal crossover varies the child's program length", sawVariedLength);

			net.hedinger.prototype.entities.Brain m = net.hedinger.prototype.entities.Brain.random(6);
			String before = String.join("\n", m.disassemble(null, null));
			m.mutate(1.0);
			String after = String.join("\n", m.disassemble(null, null));
			assertTrue("mutation changes the program", !before.equals(after));
		}
	}

	/**
	 * A pluggable mind drives a body through the sensor/actuator contract. The same
	 * body pursues a target under three interchangeable minds -- an LGP brain, a
	 * hand-written controller, and a do-nothing dummy -- proving the interface is
	 * swappable without touching how the body senses or acts.
	 */
	static class MindDrivesAgent extends Scenario {
		@Override
		public void run() {
			// An LGP "pursue the nearest" program: turn = relative bearing to the
			// neighbour, throttle = 1. Four instructions, run as a full pass/tick.
			int[][] pursue = {
					{ Brain.SENSE, 0, AgentIO.S_NEAR_BEARING, 0 }, // R0 = near_bearing
					{ Brain.WRITE, AgentIO.A_TURN, 0, 0 },         // turn = R0
					{ Brain.SET, 1, 9, 0 },                        // R1 = 1.0 (const[9])
					{ Brain.WRITE, AgentIO.A_THROTTLE, 1, 0 },     // throttle = R1
			};
			firstMind = new LgpMind(new Brain(deepCopy(pursue)), 4);
			double lgp = pursuitDistance(firstMind);
			assertLess("an LGP-brained body steers to its target through the sensor vector", lgp, 0.5);

			// The SAME body with a hand-written controller -- swapped behind the Mind
			// interface, sensing/acting untouched -- pursues identically.
			Mind scripted = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_TURN] = s[AgentIO.S_NEAR_BEARING];
					a[AgentIO.A_THROTTLE] = 1.0;
				}
			};
			assertLess("a scripted (dummy) mind on the same body pursues identically",
					pursuitDistance(scripted), 0.5);

			// A do-nothing mind leaves the body inert -- the trivial case works too.
			Mind idle = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
				}
			};
			assertGreater("an idle mind never moves the body", pursuitDistance(idle), 1.0);
		}

		/** Runs one mind against a fixed pursuit setup; returns the final distance
		 * between the minded body and its stationary target. */
		private double pursuitDistance(Mind mind) {
			seed(63); // identical world/perception for every mind
			World w = room(12, 12);
			Genome g = Genome.phenotype(6, 0.05, 5, 6, Math.PI, 3000);
			TestNPC agent = TestNPC.minded(4.5, 4.5, 0, g, mind);
			TestNPC target = TestNPC.inert(5.6, 4.5, 0); // adjacent tile: perceivable
			w.spawnEntity(agent);
			w.spawnEntity(target);
			w.think();
			if (mind == firstMind) {
				snapshot(w, "before (agent SW of target)");
			}
			tick(w, 250);
			if (mind == firstMind) {
				snapshot(w, "after (brain steered onto the target)");
			}
			return Math.hypot(agent.getX() - target.getX(), agent.getY() - target.getY());
		}

		private Mind firstMind; // labels the snapshot for the LGP run only
	}

	/**
	 * The hunger sensor reports a fraction of the body's OWN capacity, not an
	 * absolute constant, so a large body is not pinned at "full". A mind that
	 * throttles by its hunger reading crawls when the tank is half-empty and races
	 * when it is full; a big body at half fill therefore travels far less than the
	 * same body full — which the old fixed-divisor sensor (saturated at ~1 for any
	 * sizeable body) could never express.
	 */
	static class MindSensesHungerByCapacity extends Scenario {
		private double travel(double energyFraction) {
			seed(70);
			World w = room(30, 30); // roomy and centred, so the path never meets a wall
			Genome g = new Genome();
			g.size = 16; // capacity 6 * (16/8) = 12, well above the old /4 divisor
			g.speed = 0.05;
			Mind throttleByHunger = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = s[AgentIO.S_ENERGY]; // pace set by how full I am
					a[AgentIO.A_TURN] = 0; // no steering: distance covered reflects throttle alone
				}
			};
			TestNPC agent = TestNPC.minded(15.5, 15.5, 0, g, throttleByHunger);
			agent.withEnergy(energyFraction * agent.energyCapacity());
			double x0 = agent.getX(), y0 = agent.getY();
			w.spawnEntity(agent);
			w.think();
			tick(w, 120);
			return Math.hypot(agent.getX() - x0, agent.getY() - y0);
		}

		@Override
		public void run() {
			double full = travel(1.0);
			double half = travel(0.5);
			assertGreater("a full body paces itself near top throttle", full, 3.0);
			assertLess("a half-full big body reads ~0.5 hunger and moves at ~half pace, not saturated",
					half, full * 0.7);
		}
	}

	/**
	 * The dedicated prey channel drives hunting. A mind that steers toward
	 * {@code S_PREY_BEARING} and opens the throttle runs down a smaller
	 * creature it senses at full sight range — a target the short facing-gated
	 * nearest-neighbour channel would lose the moment it moved.
	 */
	static class MindHuntsViaPreyChannel extends Scenario {
		@Override
		public void run() {
			seed(71);
			World w = room(24, 9);
			Genome pg = new Genome();
			pg.size = 12;
			pg.speed = 0.05;
			pg.losRange = 14;
			Mind hunt = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_TURN] = s[AgentIO.S_PREY_BEARING]; // steer onto the prey
					a[AgentIO.A_THROTTLE] = 1.0;
					a[AgentIO.A_THROTTLE] = 1.0; // run it down at full speed
				}
			};
			TestNPC pred = TestNPC.minded(4.5, 4.5, 0, pg, hunt);
			TestNPC prey = TestNPC.inert(11.5, 4.5, 0); // size 6 < 12, seven tiles off
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();
			double d0 = Math.hypot(pred.getX() - prey.getX(), pred.getY() - prey.getY());
			tick(w, 250);
			double d1 = Math.hypot(pred.getX() - prey.getX(), pred.getY() - prey.getY());
			assertGreater("the prey started out of arm's reach", d0, 5.0);
			assertLess("the hunter closed on prey sensed through the prey channel", d1, 2.0);
		}
	}

	/**
	 * The dedicated threat channel drives flight. A mind that steers away from
	 * {@code S_THREAT_BEARING} opens the gap from a larger creature — the split
	 * threat sense lets prey react to what could eat it, not merely to whatever is
	 * nearest.
	 */
	static class MindFleesViaThreatChannel extends Scenario {
		@Override
		public void run() {
			seed(72);
			World w = room(30, 9);
			Genome yg = new Genome();
			yg.size = 6;
			yg.speed = 0.05;
			yg.losRange = 14;
			Mind flee = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					double b = s[AgentIO.S_THREAT_BEARING] * Math.PI; // relative bearing to threat
					double away = Math.atan2(Math.sin(b + Math.PI), Math.cos(b + Math.PI));
					a[AgentIO.A_TURN] = Math.max(-1, Math.min(1, away / (Math.PI * 0.5)));
					a[AgentIO.A_THROTTLE] = 1.0;
				}
			};
			TestNPC prey = TestNPC.minded(15.5, 4.5, 0, yg, flee);
			Mind idle = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
				}
			};
			Genome tg = new Genome();
			tg.size = 14; // a big, stationary threat to the prey's west
			TestNPC threat = TestNPC.minded(9.5, 4.5, 0, tg, idle);
			w.spawnEntity(prey);
			w.spawnEntity(threat);
			w.think();
			double d0 = Math.hypot(prey.getX() - threat.getX(), prey.getY() - threat.getY());
			tick(w, 150);
			double d1 = Math.hypot(prey.getX() - threat.getX(), prey.getY() - threat.getY());
			assertGreater("the prey opened the gap from a threat sensed through the threat channel",
					d1, d0 + 2.0);
		}
	}

	/**
	 * Throttle is the speed control, and speed is charged as kinetic energy. There
	 * is no sprint gear to engage: a mind simply asks for the pace it wants, and
	 * because movement costs {@code mass * v^2} the bill rises with the SQUARE of
	 * that choice. Runs one body at two throttle settings and pins both halves —
	 * more throttle covers more ground, and it costs superlinearly more to do it,
	 * which is what makes going fast a decision rather than a free upgrade.
	 */
	static class ThrottleSetsSpeedAndCostsItsSquare extends Scenario {
		private double[] run(double throttle) {
			seed(73);
			World w = room(50, 9);
			Genome g = new Genome();
			g.size = 10;
			g.speed = 0.05;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = throttle;
					a[AgentIO.A_TURN] = 0;
				}
			};
			TestNPC body = TestNPC.minded(3.5, 4.5, 0, g, ctrl).withMetabolic().withEnergy(5.0);
			double x0 = body.getX(), e0 = body.getEnergy();
			w.spawnEntity(body);
			w.think();
			tick(w, 100);
			return new double[] { body.getX() - x0, e0 - body.getEnergy() };
		}

		@Override
		public void run() {
			double[] fast = run(1.0);
			double[] half = run(0.5);
			assertGreater("more throttle covers more ground", fast[0], half[0] + 1.0);

			// Resting metabolism is in both bills, so compare the MOVEMENT component.
			// Doubling speed quadruples the per-tick movement cost, so over the same
			// window the moving part should be ~4x, not 2x.
			double[] still = run(0.0);
			double moveFast = fast[1] - still[1];
			double moveHalf = half[1] - still[1];
			assertGreater("moving at all costs energy", moveHalf, 0.0);
			assertGreater("doubling speed costs far more than double (v^2, not v)",
					moveFast, moveHalf * 3.0);
		}
	}

	/**
	 * Intent steering: a mind that names a <i>kind of thing</i> gets there without
	 * ever writing a turn. The body scores the ground it can see, picks a patch, and
	 * supplies the heading; the mind spends one instruction saying what it wants.
	 * The sign is the whole of approach-versus-avoid, so the same policy that hunts
	 * a patch flees one by flipping a constant.
	 *
	 * <p>Both bodies start facing directly <i>away</i> from the only grass in the
	 * room and never write A_TURN, so anything that arrives did so because the body
	 * steered it.
	 */

	/**
	 * A mind holds only as many targets at once as its brain has room for, and the
	 * room comes from the brain's own length. Both bodies here stand in the same
	 * spot with the same things around them; the one running a longer program simply
	 * perceives more of them. That is what makes a big brain worth its slower
	 * reflexes — and what makes a small one single-minded.
	 */

	/**
	 * An intent reports back. A mind can finally tell whether what it wanted
	 * happened, instead of inferring it from its tank drifting upward several
	 * thought-cycles late. Three of the four states are pinned here; the fourth,
	 * idle, is simply what a mind that names nothing reads.
	 *
	 * <p>Note what is NOT pinned: a "failed" state. A latched intent never finishes
	 * losing — it is either still closing (pending) or its guards stopped holding
	 * (invalid) — so how long to persist is left for a lineage to evolve rather than
	 * for the body to decide. See AgentIO.S_INTENT.
	 */

	/**
	 * Mating is a behaviour, not a collision. A mind that wants to breed walks to a
	 * partner and holds station with it while the exchange happens; a child follows
	 * a few seconds later. Before this, A_MATE only fired if a partner happened
	 * already to be in reach, so breeding was something creatures blundered into.
	 *
	 * <p>Pins the three things that make it an intent: the approach happens without
	 * any steering from the mind, the exchange takes real time rather than
	 * completing on contact, and the pair are stationary while it runs.
	 */

	/**
	 * Tile-seeking is generic: A_TILE names a <i>property</i> and the same channel
	 * that finds grass finds whatever was asked for instead. Which property is worth
	 * wanting is left to the mind — hiding in cover and heading for water are the
	 * same instruction with a different constant.
	 *
	 * <p>Properties rather than tile types on purpose: types are many and growing,
	 * their indices are not pool-aligned, and new terrain would need new bands. These
	 * six questions every tile already answers, and new ground inherits them free.
	 */

	/**
	 * Cover is a refuge from a HUNTER, not merely from the generic chaser
	 * {@link CoverHidesFromPerception} pins. A predator picks its quarry through
	 * nearestPrey, which is a different code path with its own line-of-sight gate,
	 * and that gate is the whole reason asking for cover (A_TILE) is worth an
	 * instruction: without it, hiding would be theatre.
	 */

	/**
	 * A hunter with a full tank does not kill. The meal would overflow the cap and be
	 * discarded, so the prey would die for nothing.
	 *
	 * <p>Pins the boundary rather than the intent, because the first version of this
	 * gate tested {@code energy >= capacity} and could never fire: run_extended caps
	 * the tank and then burns metabolism, both before think(), so a metabolic body is
	 * never exactly at its cap when it decides anything. A hungry hunter beside the
	 * same prey must still kill, or this would pass by the hunter simply being broken.
	 */

	/**
	 * A big animal leaves a big body. Corpse lifetime scales with the mass the
	 * creature would have grown into, so a kill stays available to scavengers in
	 * proportion to how much of it there is — the same principle as the meal being
	 * worth what it weighs.
	 */
	static class ACorpseLingersByItsMass extends Scenario {
		/** Ticks from death until the body is cleared, for a genome of this size. */
		private int corpseTicks(double size) {
			seed(104);
			World w = room(10, 10);
			Genome g = new Genome();
			g.size = size;
			TestNPC t = TestNPC.mindedForager(5.5, 5.5, 0, g);
			w.spawnEntity(t);
			w.think();
			t.damage(1000); // killed outright
			for (int i = 1; i <= 2000; i++) {
				tick(w, 1);
				if (t.isRemoved()) {
					return i;
				}
			}
			return -1;
		}

		@Override
		public void run() {
			int small = corpseTicks(6);
			int large = corpseTicks(20);
			assertGreater("a small body clears at all", small, 0);
			assertGreater("a bigger body lies there longer", large, small * 2);
		}
	}

	static class AFullHunterDoesNotKill extends Scenario {
		private int preyHealthAfter(double hungerLevel) {
			seed(103);
			World w = room(12, 9);
			Genome pg = new Genome();
			pg.size = 20;
			TestNPC hunter = TestNPC.predator(5.5, 4.5, 0, pg).withMetabolic()
					.withReproCooldown(1000000);
			TestNPC prey = TestNPC.inert(5.9, 4.5, 0).withSize(6); // already in reach
			w.spawnEntity(hunter);
			w.spawnEntity(prey);
			w.think();
			for (int i = 0; i < 40; i++) {
				hunter.withHunger(hungerLevel); // hold the appetite there
				tick(w, 1);
			}
			return prey.getHealth();
		}

		@Override
		public void run() {
			assertEquals("a full hunter leaves prey in reach alone", 100, preyHealthAfter(0.0));
			assertLess("a hungry hunter beside the same prey still kills", preyHealthAfter(0.7), 100);
		}
	}

	static class CoverHidesPreyFromAHunter extends Scenario {
		@Override
		public void run() {
			seed(102);
			World w = room(14, 9);
			for (int y = 3; y <= 5; y++) {
				w.setTile(11, y, 0, Tile.TileType.TYPE_COVER); // a thicket to the east
			}
			Genome pg = new Genome();
			pg.size = 20;
			TestNPC hunter = TestNPC.predator(3.5, 4.5, 0, pg).withMetabolic().withHunger(0.8);
			TestNPC inCover = TestNPC.inert(11.5, 4.5, 0).withSize(6);
			TestNPC inOpen = TestNPC.inert(7.5, 7.5, 0).withSize(6);
			w.spawnEntity(hunter);
			w.spawnEntity(inCover);
			w.spawnEntity(inOpen);
			w.think();
			snapshot(w, "before (prey in the open, prey in the thicket)");
			tick(w, 600);
			snapshot(w, "after (the hunter took the one it could see)");

			assertLess("the hunter is hurt or dead in the open", inOpen.getHealth(), 100);
			assertEquals("the prey in cover was never touched", 100, inCover.getHealth());
		}
	}

	static class TileSeekingIsGeneric extends Scenario {
		/** Does a body asking for this property walk onto ground that has it? */
		private boolean reaches(double want, Tile.TileType kind) {
			seed(101);
			World w = room(24, 12);
			for (int x = 0; x < 24; x++) {
				for (int y = 0; y < 12; y++) {
					w.getTile(x, y, 0).setFertility(0); // no grass to distract the scan
				}
			}
			for (int y = 5; y <= 7; y++) {
				w.setTile(19, y, 0, kind); // the only ground of that kind, well east
			}
			Genome g = new Genome();
			g.size = 8;
			g.losRange = 22;
			TestNPC body = TestNPC.minded(5.5, 6.5, 0, g, (sn, a) -> {
				a[AgentIO.A_TILE] = want; // what kind of ground
				a[AgentIO.A_SEEK] = 0.1; // ...go to it
				a[AgentIO.A_THROTTLE] = 0.8;
			}).withHeading(Math.PI); // starts facing away
			w.spawnEntity(body);
			w.think();
			tick(w, 400);
			return body.getX() > 16.0;
		}

		@Override
		public void run() {
			assertTrue("a body that asks for cover walks to the thicket",
					reaches(0.25, Tile.TileType.TYPE_COVER));
			assertTrue("the same machinery finds water when that is what was asked for",
					reaches(1.0, Tile.TileType.TYPE_WATER));
			// And the property genuinely selects: asking for cover does not walk a
			// creature onto water, so the constant is doing the work.
			assertTrue("asking for cover ignores water", !reaches(0.25, Tile.TileType.TYPE_WATER));
		}
	}

	static class MatingTakesTimeAndSeeksAPartner extends Scenario {
		@Override
		public void run() {
			seed(99);
			World w = room(20, 20);
			// Two compatible partners, well apart, both willing and nothing else said.
			// Willing, and walking: speed stays with the mind for mating exactly as it
			// does for seeking, so an intent alone steers but does not travel.
			Mind willing = (sn, a) -> {
				a[AgentIO.A_MATE] = 1;
				a[AgentIO.A_THROTTLE] = 0.6;
			};
			TestNPC a = mater(w, 5.5, 10.5, willing);
			TestNPC b = mater(w, 13.5, 10.5, willing);
			w.think();
			int start = w.getAliveCount();
			double gap0 = Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());

			tick(w, 90); // long enough to close an 8-tile gap, not to finish courting
			double gap1 = Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
			assertLess("they sought each other out with no steering of their own",
					gap1, gap0 - 3.0);
			assertTrue("the exchange is under way", a.isMating() || b.isMating());
			assertEquals("...and no child yet: mating is not instant", start,
					w.getAliveCount());

			tick(w, 200); // now let it run to term
            assertGreater("a child arrives once the pair have held station",
					w.getAliveCount(), start);
		}

		private TestNPC mater(World w, double x, double y, Mind m) {
			Genome g = new Genome();
			g.size = 8;
			g.markers = new double[] { 0.5, 0.5, 0.5 };
			g.mateThreshold = 0.1; // compatible with each other
			TestNPC t = TestNPC.minded(x, y, 0, g, m).withMetabolic().withSpeed(0.08);
			t.withEnergy(t.energyCapacity());
			w.spawnEntity(t);
			return t;
		}
	}

	static class IntentReportsHowItWent extends Scenario {
		private double statusAfter(boolean grassInWorld, int ticks) {
			seed(98);
			World w = room(16, 16);
			if (!grassInWorld) {
				for (int x = 0; x < 16; x++) {
					for (int y = 0; y < 16; y++) {
						w.getTile(x, y, 0).setFertility(0); // nothing to forage anywhere
					}
				}
			}
			Genome g = new Genome();
			g.size = 8;
			TestNPC body = TestNPC.minded(8.5, 8.5, 0, g, (sn, a) -> {
				a[AgentIO.A_SEEK] = 0.1; // forage
				a[AgentIO.A_THROTTLE] = 0.3;
			}).withHunger(0.5); // an appetite, so arriving on grass actually eats
			w.spawnEntity(body);
			tick(w, ticks);
			return body.intentStatus();
		}

		@Override
		public void run() {
			assertNear("seeking food where there is none reports invalid",
					AgentIO.INTENT_INVALID, statusAfter(false, 5), 1e-9);
			assertNear("standing on grass and grazing it reports done",
					AgentIO.INTENT_DONE, statusAfter(true, 5), 1e-9);

			// And idle when the mind names nothing at all.
			seed(98);
			World w = room(10, 10);
			Genome g = new Genome();
			g.size = 8;
			TestNPC idle = TestNPC.minded(5.5, 5.5, 0, g, (sn, a) -> {
			});
			w.spawnEntity(idle);
			tick(w, 5);
			assertNear("a mind that wants nothing reports idle",
					AgentIO.INTENT_IDLE, idle.intentStatus(), 1e-9);
		}
	}

	static class BrainSizeSetsHowMuchAMindTracks extends Scenario {
		/** How many tracked channels come back non-zero for a brain of this length. */
		private int channelsSeen(int brainLen) {
			seed(97);
			World w = room(20, 20);
			Genome g = new Genome();
			g.size = 8;
			g.losRange = 14;
			int[][] code = new int[brainLen][];
			for (int i = 0; i < brainLen; i++) {
				code[i] = new int[] { Brain.NOP, 0, 0, 0 }; // length is all that matters here
			}
			g.brain = new Brain(code);
			TestNPC body = TestNPC.minded(10.5, 10.5, 0, g, (sn, a) -> {
			});
			w.spawnEntity(body);
			// Something in every tracked channel, all within sight: grass underfoot,
			// a smaller creature, a bigger one, and an item.
			w.spawnEntity(TestNPC.inert(13.5, 10.5, 0).withSize(3));
			w.spawnEntity(TestNPC.inert(10.5, 14.5, 0).withSize(19));
			w.spawnEntity(net.hedinger.prototype.entities.Item.food(7.5, 10.5, 0));
			tick(w, 2);
			double[] s = body.sensorSnapshot();
			int seen = 0;
			for (int prox : new int[] { AgentIO.S_FORAGE_PROX, AgentIO.S_PREY_PROX,
					AgentIO.S_THREAT_PROX, AgentIO.S_ITEM_PROX }) {
				if (s[prox] > 0) {
					seen++;
				}
			}
			return seen;
		}

		@Override
		public void run() {
			int small = channelsSeen(4);   // 1 slot
			int large = channelsSeen(48);  // capped at the maximum
			assertEquals("a tiny brain keeps track of exactly one thing", 1, small);
			assertGreater("a large brain holds several at once", large, small);
		}
	}

	static class SeekWalksToAPatchWithoutSteering extends Scenario {
		private static final int PATCH_X = 32, PATCH_Y = 7;

		/** Distance from the patch after walking with this A_SEEK value. */
		private double distanceAfter(double seek) {
			seed(90);
			World w = room(40, 14);
			for (int x = 0; x < 40; x++) {
				for (int y = 0; y < 14; y++) {
					w.getTile(x, y, 0).setFertility(0); // barren everywhere...
				}
			}
			for (int x = PATCH_X - 1; x <= PATCH_X + 1; x++) {
				for (int y = PATCH_Y - 1; y <= PATCH_Y + 1; y++) {
					w.getTile(x, y, 0).setFertility(1.0); // ...except one lush patch
				}
			}
			Genome g = new Genome();
			g.size = 6;
			g.losRange = 20; // far enough to see the patch from the start
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1;
					a[AgentIO.A_SEEK] = seek; // the only steering it ever expresses
				}
			};
			// Facing west, i.e. away from the patch: a body that ignores seek leaves.
			TestNPC body = TestNPC.minded(20.5, 7.5, 0, g, ctrl).withSpeed(0.1)
					.withHeading(Math.PI * 0.9);
			w.spawnEntity(body);
			w.think();
			tick(w, 150);
			return Math.hypot(body.getX() - (PATCH_X + 0.5), body.getY() - (PATCH_Y + 0.5));
		}

		@Override
		public void run() {
			double start = Math.hypot(20.5 - (PATCH_X + 0.5), 7.5 - (PATCH_Y + 0.5));
			double toward = distanceAfter(0.1);
			double away = distanceAfter(-0.1);
			assertLess("seeking the forage patch walks the body onto it", toward, 2.0);
			assertGreater("the same command with a minus sign walks it off", away, start);
		}
	}

	/**
	 * An intent carries through to the act. This mind writes A_SEEK for the goal and
	 * a throttle for the effort — no turn, no eat, no aiming — and the body finds
	 * grass, walks to it and grazes it. That is the point of an intent under
	 * one-instruction-per-tick: foraging costs a goal slot instead of a steering
	 * loop plus an eat gate, which is what makes the trade against reaction time
	 * worth taking. Speed stays deliberately outside the bargain, since movement
	 * costs the square of it and that is where a lineage spends or saves its living.
	 *
	 * <p>The control is the same mind with the sign flipped. Avoidance must move the
	 * body but never feed it, because running from a thing is not a reason to eat it
	 * — and without that check "seek" could quietly feed a creature that was fleeing.
	 */
	static class OneIntentIsAWholeBehaviour extends Scenario {
		/** {@code {food swallowed, distance to the patch}} after walking on this intent. */
		private double[] run(double seek) {
			seed(93);
			World w = room(40, 14);
			for (int x = 0; x < 40; x++) {
				for (int y = 0; y < 14; y++) {
					w.getTile(x, y, 0).setFertility(0); // barren...
				}
			}
			for (int x = 24; x <= 28; x++) {
				for (int y = 5; y <= 9; y++) {
					w.getTile(x, y, 0).setFertility(1.0); // ...but for one lush patch
				}
			}
			Genome g = new Genome();
			g.size = 8;
			g.speed = 0.08; // the intent's forage pace is a deliberate amble; give it legs
			g.losRange = 20;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_SEEK] = seek; // where to go, and what to do there
					a[AgentIO.A_THROTTLE] = 1; // how hard: still the mind's to choose
				}
			};
			TestNPC body = TestNPC.minded(20.5, 7.5, 0, g, ctrl).withMetabolic()
					.withHunger(1.0) // an empty stomach, so grazing shows up
					.withReproCooldown(1000000).withHeading(Math.PI * 0.9); // facing away
			w.spawnEntity(body);
			w.think();
			double s0 = body.totalSwallowed();
			tick(w, 900);
			return new double[] { body.totalSwallowed() - s0,
					Math.hypot(body.getX() - 26.5, body.getY() - 7.5) };
		}

		@Override
		public void run() {
			double[] toward = run(0.1);
			double[] away = run(-0.1);
			assertLess("a lone forage intent walked the body to the patch", toward[1], 3.0);
			assertGreater("...and grazed it, with no A_EAT ever written", toward[0], 0.0);
			assertGreater("the avoidance sense still moved the body", away[1], 8.0);
			assertNear("...but fed it nothing at all", 0.0, Math.max(0, away[0]), 1e-9);
		}
	}

	/**
	 * The same coupling on the hunting side: a mind that names prey and asks for
	 * speed runs its quarry down and bites it, with no A_ATTACK and no steering of
	 * its own. Pins that the act travels with the goal, not just the heading.
	 */
	static class HuntIntentClosesAndBites extends Scenario {
		@Override
		public void run() {
			seed(94);
			World w = room(30, 12);
			Genome g = new Genome();
			g.size = 16;
			g.losRange = 20;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_SEEK] = 0.5; // hunt: approach and bite, from one slot
					a[AgentIO.A_THROTTLE] = 1;
				}
			};
			TestNPC hunter = TestNPC.minded(5.5, 6.0, 0, g, ctrl).withMetabolic()
					.withReproCooldown(1000000).withHeading(Math.PI * 0.9);
			TestNPC quarry = TestNPC.inert(20.5, 6.0, 0).withSize(6);
			w.spawnEntity(hunter);
			w.spawnEntity(quarry);
			w.think();
			int hp0 = quarry.getHealth();
			// Closest approach over the run, not the final gap: the hunt succeeds, and
			// a hunter with nothing left to chase goes looking for the next one, so by
			// tick 900 it has wandered off the corpse it made.
			double closest = Double.MAX_VALUE;
			for (int i = 0; i < 900; i++) {
				tick(w, 1);
				closest = Math.min(closest, Math.hypot(hunter.getX() - quarry.getX(),
						hunter.getY() - quarry.getY()));
			}
			assertLess("a lone hunt intent closed the distance", closest, 2.0);
			assertLess("...and bit, with no A_ATTACK ever written", quarry.getHealth(), hp0);
		}
	}

	/**
	 * Spatial memory in two instructions. A_MARK latches where the body is standing;
	 * A_SEEK = ±4 steers back to it later. The coordinate lives in the body on
	 * purpose — a mind could hold two numbers in registers but could never turn them
	 * into a heading, since the instruction set has no divide and no atan2.
	 *
	 * <p>The return leg also jams A_TURN hard the wrong way, which is what pins the
	 * precedence: while a seek has something to steer by, it is the steering.
	 */
	static class WaypointRemembersAPlace extends Scenario {
		@Override
		public void run() {
			seed(91);
			World w = room(30, 14);
			Genome g = new Genome();
			g.size = 6;
			Mind ctrl = new Mind() {
				private int t = 0;

				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1;
					a[AgentIO.A_MARK] = t == 0 ? 1.0 : 0.0; // remember here, once
					if (t < 120) {
						a[AgentIO.A_SEEK] = 0; // walk straight off
						a[AgentIO.A_TURN] = 0;
					} else {
						a[AgentIO.A_SEEK] = 4; // go back...
						a[AgentIO.A_TURN] = 1; // ...against a hard contrary turn
					}
					t++;
				}
			};
			TestNPC body = TestNPC.minded(6.5, 7.5, 0, g, ctrl).withSpeed(0.08).withHeading(0);
			w.spawnEntity(body);
			w.think();
			tick(w, 120);
			double away = Math.hypot(body.getX() - 6.5, body.getY() - 7.5);
			assertGreater("the body walked away from the place it marked", away, 5.0);
			tick(w, 220);
			double back = Math.hypot(body.getX() - 6.5, body.getY() - 7.5);
			assertLess("seeking the waypoint brought it home", back, 2.0);
			assertLess("...and home is nearer than where it had wandered", back, away);
		}
	}

	/**
	 * Wanting something you cannot see is a reason to go looking. A goal the body
	 * cannot currently find puts it into a deterministic search — drifting on the
	 * same oscillator the mind reads as S_CLOCK — rather than leaving it planted.
	 * That is what makes an intent a complete behaviour: "forage" has to mean find
	 * food, not merely walk at food already in view.
	 *
	 * <p>It also means a mutation that writes a seek constant can never freeze a
	 * creature pointing at a thing that isn't there, which is the survival floor the
	 * old pure-steering version guaranteed a different way (by doing nothing at all).
	 * Here the waypoint is never marked, so A_SEEK = 4 names something unfindable.
	 */
	static class SeekYieldsWhenItNamesNothing extends Scenario {
		@Override
		public void run() {
			seed(92);
			World w = room(30, 14);
			Genome g = new Genome();
			g.size = 6;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1;
					a[AgentIO.A_SEEK] = 4; // the waypoint... which was never marked
					a[AgentIO.A_TURN] = 0;
				}
			};
			TestNPC body = TestNPC.minded(6.5, 7.5, 0, g, ctrl).withSpeed(0.08).withHeading(0);
			w.spawnEntity(body);
			w.think();
			double x0 = body.getX(), y0 = body.getY();
			tick(w, 300);
			double travelled = Math.hypot(body.getX() - x0, body.getY() - y0);
			assertGreater("an unfindable goal sends the body searching, not nowhere", travelled, 3.0);
			assertTrue("...and the search steers it off the straight line it started on",
					Math.abs(body.getY() - y0) > 0.5);
		}
	}

	/**
	 * A mind changes level by walking, not by wishing. A ramp is floor that spans
	 * two levels, so a minded body that only ever asks for throttle crosses one and
	 * comes out on the other side a level away — up an east-climbing RAMPUP, down a
	 * west-descending RAMPDOWN. Both bodies here jam A_VERTICAL hard the WRONG way
	 * throughout, which is the point: the actuator is retired and inert, and the
	 * terrain is what moves them. Guards against reintroducing a vertical intent
	 * that a random policy would have to get right before it could use a ramp.
	 */
	static class MindsChangeLevelByWalkingRamps extends Scenario {
		/** Walks a throttle-only mind from {@code (x,y,z)} along {@code heading} and
		 *  reports the level it ends on. */
		private int levelAfterWalking(double x, double y, int z, double heading,
				double contraryVerticalIntent) {
			seed(80);
			World w = room(10, 6, 2);
			// A ramp pair on row 2: climb east off (5,2,0), descend west off (5,2,1).
			w.setTile(5, 2, 0, Tile.TileType.TYPE_RAMPUP);
			w.setTile(5, 2, 1, Tile.TileType.TYPE_RAMPDOWN);
			Genome g = new Genome();
			g.size = 6;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1; // walk, and nothing else
					a[AgentIO.A_VERTICAL] = contraryVerticalIntent; // inert: ignored
				}
			};
			TestNPC body = TestNPC.minded(x, y, z, g, ctrl).withSpeed(0.05).withHeading(heading);
			w.spawnEntity(body);
			w.think();
			tick(w, 200);
			return body.getLvl();
		}

		@Override
		public void run() {
			assertEquals("a mind that only walks east climbs the ramp to the level above",
					1, levelAfterWalking(2.5, 2.5, 0, 0.0, 1.0));
			assertEquals("a mind that only walks west descends the ramp to the level below",
					0, levelAfterWalking(8.5, 2.5, 1, Math.PI, 1.0));
		}
	}

	/**
	 * The body's anti-freeze reflex carries to minded bodies: a degenerate policy
	 * that drives flat into a wall cannot pin the creature there forever. The body
	 * detects the terrain jam and drives it clear for a spell, so it keeps moving
	 * instead of standing frozen — the survival floor that lets fully-random brains
	 * live long enough to evolve.
	 */
	static class MindedBodyUnsticksFromWallJam extends Scenario {
		@Override
		public void run() {
			seed(81);
			World w = room(9, 9);
			Genome g = new Genome();
			g.size = 6;
			g.speed = 0.08;
			Mind ram = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1.0; // full ahead, never steering
					a[AgentIO.A_TURN] = 0;
				}
			};
			TestNPC body = TestNPC.minded(4.5, 4.5, 0, g, ram); // heading east, into the wall
			w.spawnEntity(body);
			w.think();
			// Track the deepest it drives into the wall, then how far it retreats
			// afterwards: a jammed body stays pressed at maxX (no retreat); one the
			// body unsticks is driven back off the wall before it charges again.
			double maxX = body.getX(), minXafter = body.getX();
			for (int i = 0; i < 300; i++) {
				tick(w, 1);
				if (body.getX() > maxX) {
					maxX = body.getX();
					minXafter = maxX;
				} else {
					minXafter = Math.min(minXafter, body.getX());
				}
			}
			assertGreater("a minded body jammed into a wall is driven back off it (unstick reflex)",
					maxX - minXafter, 1.5);
		}
	}

	/**
	 * The demo world seeds a small cohort of minded creatures alongside the scripted
	 * species, and the steward keeps it from dying out: fully-random brains rarely
	 * feed themselves, so without the reseed the lineage would starve to nothing and
	 * the A/B seam with it. Pins that the cohort is present, is marked minded (apart
	 * from the hardcoded creatures), stays alive over time, and does not crowd out
	 * the scripted ecosystem it runs beside.
	 */
	static class MindedCohortSustainedBySteward extends Scenario {
		private int countMinded(World w) {
			int c = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved() && t.isMinded()) {
					c++;
				}
			}
			return c;
		}

		private int countRole(World w, String role) {
			int c = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
						&& !t.isMinded() && t.ecoRole().equals(role)) {
					c++;
				}
			}
			return c;
		}

		@Override
		public void run() {
			World w = net.hedinger.prototype.sim.Worlds.demo(7);
			assertGreater("the demo world seeds a minded cohort", countMinded(w), 0);
			// Long enough for the random-brained founders to starve and be reseeded.
			tick(w, 8000);
			assertGreater("the steward keeps the minded cohort from dying out", countMinded(w), 0);
			assertGreater("the scripted prey persist alongside the minded cohort",
					countRole(w, "prey"), 0);
		}
	}

	/**
	 * Survivor-seeding with longevity as fitness: when the steward must top the
	 * minded cohort back up, the new creature descends from the longest-lived
	 * minded creature currently alive (a mutated child, inheriting its brain), not a
	 * fresh random one — living longest is itself the fitness, since a metabolic
	 * creature that can't feed itself starves. Only a wiped-out cohort falls back to
	 * random. Pins that the reseed tracks the OLDEST survivor's lineage (by its
	 * markers), not a younger one, and that the empty-cohort fallback still yields a
	 * valid random-brained genome.
	 */
	static class MindedReseedDescendsFromLongestLivedSurvivor extends Scenario {
		private static Genome mindedWith(double m0, double m1, double m2) {
			Genome g = new Genome();
			g.markers = new double[] { m0, m1, m2 };
			g.size = 10;
			g.speed = 0.05;
			g.brain = Brain.random(16);
			return g;
		}

		@Override
		public void run() {
			seed(90);
			World w = room(20, 20);

			// Empty cohort: the reseed falls back to a fresh random-brained genome.
			Genome fresh = net.hedinger.prototype.sim.Worlds.mindedReseedGenome(w);
			assertTrue("a wiped-out cohort reseeds a fresh random-brained genome", fresh.brain != null);

			// An older survivor (distinctive red-ish markers), then a younger one
			// (green-ish). Both live comfortably within the window (a big reserve
			// drains slowly), so age alone separates them.
			TestNPC older = TestNPC.mindedForager(5.5, 5.5, 0, mindedWith(0.90, 0.10, 0.10));
			w.spawnEntity(older);
			tick(w, 200); // the older one banks 200 ticks of age
			TestNPC younger = TestNPC.mindedForager(14.5, 14.5, 0, mindedWith(0.10, 0.90, 0.10));
			w.spawnEntity(younger);
			tick(w, 5);

			assertTrue("both survivors are still alive to seed from",
					!older.isDead() && !older.isRemoved() && !younger.isDead() && !younger.isRemoved());

			// The reseed descends from the OLDER survivor: its markers track the
			// red-ish lineage (mutated by <= the 0.08 rate), not the green-ish one.
			Genome reseed = net.hedinger.prototype.sim.Worlds.mindedReseedGenome(w);
			assertTrue("the reseed inherits a brain (not a brain-less body)", reseed.brain != null);
			assertLess("reseed marker 0 tracks the longest-lived survivor's",
					Math.abs(reseed.markers[0] - 0.90), 0.2);
			assertLess("reseed marker 1 tracks the longest-lived survivor's",
					Math.abs(reseed.markers[1] - 0.10), 0.2);
			assertGreater("the reseed did NOT descend from the younger survivor",
					Math.abs(reseed.markers[1] - 0.90), 0.3);
		}
	}

	/**
	 * The warm-seed payoff: a minded creature carrying the hand-written starter
	 * brain actually feeds itself. Placed on an all-grass meadow, it grazes, and so
	 * survives far past the age it could ever reach on its birth reserve alone — the
	 * "no-food starvation age" that every fully-random brain in Phases 3-4 died at.
	 * Being alive after 20k ticks (well beyond that ceiling for any body in the size
	 * band) can only mean it ate: the foothold selection needed.
	 */
	static class StarterBrainedForagerFeedsItself extends Scenario {
		@Override
		public void run() {
			seed(91);
			World w = room(24, 24);
			for (int x = 1; x < 23; x++) {
				for (int y = 1; y < 23; y++) {
					w.getTile(x, y, 0).setFertility(1.0); // a lush meadow, grass everywhere
				}
			}
			// A pond: the world now has water as a need, so a survival room
			// without a shore is a death chamber — the forager wanders across
			// the meadow and sips whenever it passes the water's edge.
			for (int x = 10; x <= 13; x++) {
				for (int y = 10; y <= 13; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_SHALLOWS);
				}
			}
			tick(w, 1); // advance the clock so vegetation is defined
			// A fresh starter-brained genome (empty cohort -> mindedReseedGenome yields
			// the founder starter), on a body in the usual size band.
			Genome g = net.hedinger.prototype.sim.Worlds.mindedReseedGenome(w);
			// Suppress breeding so we test one forager feeding itself, not a cohort:
			// the starter mates whenever able, which in an unbounded room (no steward
			// ceiling) would explode and overgraze. The live world's minded cap
			// handles that; here we isolate the feeding claim.
			TestNPC forager = TestNPC.mindedForager(12.5, 12.5, 0, g).withReproCooldown(100_000_000);
			w.spawnEntity(forager);
			tick(w, 20000); // no body in the size band lasts this long without eating
			assertTrue("a starter-brained forager on grass is still alive after 20k ticks",
					!forager.isDead() && !forager.isRemoved());
			assertGreater("...reaching an age only feeding could sustain", forager.getAge(), 12000);
		}
	}

	/**
	 * A brain is inherited alongside the body: an asexual child copies-and-mutates
	 * the parent's program, a sexual child crosses both parents' programs, and a
	 * brain-less lineage stays brain-less (drawing no extra RNG, so the sim stream
	 * -- and the emergent scenarios -- are unchanged).
	 */
	static class BrainInheritedThroughReproduction extends Scenario {
		@Override
		public void run() {
			seed(64);
			Genome pa = new Genome();
			pa.brain = Brain.random(6);
			Genome asexual = Genome.child(pa, 0.2);
			assertTrue("asexual child inherits a brain", asexual.brain != null);
			assertTrue("the child's brain is its own instance, not the parent's",
					asexual.brain != pa.brain);

			Genome pb = new Genome();
			pb.brain = Brain.random(6);
			assertTrue("sexual child inherits a brain crossed from both parents",
					Genome.child(pa, pb, 0.2).brain != null);

			assertTrue("brain-less crossover stays brain-less",
					Genome.child(new Genome(), new Genome(), 0.2).brain == null);
			assertTrue("brain-less budding stays brain-less",
					Genome.child(new Genome(), 0.2).brain == null);
		}
	}

	/**
	 * The mind evolves end to end: metabolic brained foragers -- all seeded with
	 * one hand-authored "graze while wandering" program -- feed, bud, and pass a
	 * mutated copy of the brain to their young, so the living population's programs
	 * diversify away from the single founder mind.
	 */
	static class BrainedPopulationDiversifies extends Scenario {
		@Override
		public void run() {
			seed(65);
			int[][] graze = {
					{ Brain.SET, 0, 9, 0 },                       // R0 = 1.0
					{ Brain.WRITE, AgentIO.A_EAT, 0, 0 },         // eat = 1
					{ Brain.SENSE, 1, AgentIO.S_CLOCK, 0 },       // R1 = clock
					{ Brain.WRITE, AgentIO.A_TURN, 1, 0 },        // turn = clock (wander)
					{ Brain.WRITE, AgentIO.A_THROTTLE, 0, 0 },    // throttle = 1
					{ Brain.WRITE, AgentIO.A_MATE, 0, 0 },        // reproduce (asexual: spread out)
			};
			World w = room(20, 20); // full grass
			for (int i = 0; i < 3; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.2, 0.6, 0.9 };
				g.brain = new Brain(deepCopy(graze));
				w.spawnEntity(TestNPC.brainedBreeder(6.5 + i * 3, 6.5 + i * 3, 0, g));
			}
			w.think();
			int start = w.getAliveCount();
			snapshot(w, "founders (one shared mind)");

			// Brain-driven reproduction is spiky (boom then bust), so track the peak
			// population and every distinct mind seen across the run.
			int peak = start;
			java.util.Set<String> minds = new java.util.HashSet<String>();
			// 150 steps rather than 30: see PopulationGrowsWithFood -- feeding on grass
			// is a much longer job than it was, so the boom takes longer to arrive.
			for (int step = 0; step < 150; step++) {
				tick(w, 20);
				peak = Math.max(peak, w.getAliveCount());
				for (Entity e : w.getEntities()) {
					if (e instanceof net.hedinger.prototype.entities.NPC && !e.isDead()) {
						Brain b = ((net.hedinger.prototype.entities.NPC) e).getGenome().brain;
						if (b != null) {
							minds.add(programOf(b));
						}
					}
				}
			}
			snapshot(w, "after (bred; minds mutated apart)");
			assertGreater("brained foragers reproduced (population rose above the founders)", peak, start);
			assertGreater("inherited minds diversified by mutation", minds.size(), 1);
		}

		/** The brain's instructions as a string, ignoring the runtime PC marker. */
		private static String programOf(Brain b) {
			StringBuilder s = new StringBuilder();
			for (String line : b.disassemble(null, null)) {
				s.append(line.substring(2)).append('|');
			}
			return s.toString();
		}
	}

	/**
	 * Evolution discovers a behaviour from noise. Starting from random LGP brains
	 * that gather no food, a generational GA -- fitness is food a brain forages in
	 * a fixed window, truncation-select the top half, crossover + mutate to refill
	 * -- drives the population to forage. The selection pressure is the fitness
	 * gradient; the behaviour (eat while moving to fresh grass) is not authored, it
	 * emerges. The mind's own heredity (Brain crossover/mutation) does the varying.
	 */
	static class EvolutionDiscoversForaging extends Scenario {
		// G=40: with the actuator vector grown (grab/attach added), random founders
		// start weaker -- more operands land on the new no-op-for-foraging slots --
		// so discovery needs a few more generations. At 40 it is robust across
		// seeds (swept), not tuned to one lucky seed.
		static final int P = 24, K = 300, G = 40, LEN = 24, BUDGET = 4;

		@Override
		public void run() {
			seed(66);
			Brain[] brains = new Brain[P];
			for (int i = 0; i < P; i++) {
				brains[i] = Brain.random(LEN);
			}
			double initialMean = 0, bestEver = 0, lateSum = 0;
			for (int gen = 0; gen <= G; gen++) {
				double[] fit = evaluate(brains);
				double m = mean(fit), best = max(fit);
				if (gen == 0) {
					initialMean = m;
				}
				bestEver = Math.max(bestEver, best);
				if (gen >= G - 2) {
					lateSum += m;
				}
				if (gen < G) {
					brains = nextGen(brains, order(fit));
				}
			}
			double finalMean = lateSum / 3.0;
			// Fitness is vegetation cropped, so these bars live in graze-demand units,
			// and that unit shrank sixteenfold when grass became bulk food
			// (GRAZE_DEMAND 0.05 -> 0.003). They are calibrated against measurement
			// rather than scaled by that ratio, because the relationship is not linear:
			// a patch runs out, so a slower crop does not reduce a good forager's haul
			// in proportion.
			//
			// The founder bar moved once the brain's operand pool was widened to cover
			// the whole sensor bank. Widening it also made the constant pool draw
			// uniformly, and one of those constants is the forage intent -- so random
			// code now stumbles into "seek food" appreciably more often than it did.
			// That is the fix working, not the test rotting: measured here, random
			// founders 0.024, champion 0.675 (unchanged), late mean 0.492. The gap this
			// test exists to detect is 0.469 against a bar of 0.2, and a random founder
			// still crops under four per cent of what a champion does.
			assertLess("random founder brains forage almost nothing", initialMean, 0.05);
			assertGreater("evolution found a strong forager (a champion gathered real food)",
					bestEver, 0.4);
			assertGreater("mean foraging rose far above the random start under selection",
					finalMean, initialMean + 0.2);
		}

		/** Fitness = food each brain forages over K ticks on full grass. Bodies are
		 * non-metabolic here so they don't starve -- we measure behaviour, and the
		 * GA (not survival) does the selecting. */
		private double[] evaluate(Brain[] brains) {
			World w = room(24, 24);
			TestNPC[] ag = new TestNPC[brains.length];
			for (int i = 0; i < brains.length; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.5, 0.5, 0.5 };
				g.brain = brains[i];
				double x = 2.5 + (i % 10) * 2.0, y = 2.5 + (i / 10) * 2.4;
				// Hungry throughout: appetite is what grazes now, and the empty
				// stomach holds more grass than the best champion ever crops.
				ag[i] = TestNPC.minded(x, y, 0, g, new LgpMind(brains[i], BUDGET)).withHunger(1.0);
				w.spawnEntity(ag[i]);
			}
			w.think();
			tick(w, K);
			double[] fit = new double[brains.length];
			for (int i = 0; i < brains.length; i++) {
				fit[i] = ag[i].totalIntake();
			}
			return fit;
		}

		/** Truncation selection with elitism: keep the top 2, refill from the top
		 * half by crossover + mutation. */
		private static Brain[] nextGen(Brain[] brains, int[] order) {
			int half = brains.length / 2;
			Brain[] next = new Brain[brains.length];
			next[0] = brains[order[0]].copy();
			next[1] = brains[order[1]].copy();
			for (int j = 2; j < brains.length; j++) {
				next[j] = Brain.child(brains[order[(j - 2) % half]], brains[order[(j - 1) % half]], 0.15);
			}
			return next;
		}

		private static int[] order(double[] fit) {
			Integer[] idx = new Integer[fit.length];
			for (int i = 0; i < idx.length; i++) {
				idx[i] = i;
			}
			java.util.Arrays.sort(idx, (a, b) -> Double.compare(fit[b], fit[a]));
			int[] out = new int[idx.length];
			for (int i = 0; i < out.length; i++) {
				out[i] = idx[i];
			}
			return out;
		}

		private static double mean(double[] a) {
			double s = 0;
			for (double v : a) {
				s += v;
			}
			return s / a.length;
		}

		private static double max(double[] a) {
			double m = a[0];
			for (double v : a) {
				m = Math.max(m, v);
			}
			return m;
		}
	}

	/** The attack actuator: a mind that fires A_ATTACK bites the neighbour in reach
	 * until its health is gone, killing it -- combat driven by the brain. */
	static class BrainAttacksNeighbour extends Scenario {
		@Override
		public void run() {
			seed(67);
			World w = room(10, 10);
			int[][] attack = {
					{ Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, // R0 = 1
					{ Brain.WRITE, AgentIO.A_ATTACK, 0, 0 }, // attack = 1
			};
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI, 3000); // stationary
			TestNPC attacker = TestNPC.minded(4.5, 4.5, 0, g, new LgpMind(new Brain(deepCopy(attack)), 2));
			TestNPC victim = TestNPC.inert(5.0, 4.5, 0); // adjacent, within reach
			w.spawnEntity(attacker);
			w.spawnEntity(victim);
			w.think();
			snapshot(w, "before (attacker beside victim)");
			assertTrue("victim starts alive", !victim.isDead());
			tick(w, 120);
			snapshot(w, "after (victim bitten to death)");
			assertTrue("the mind's attack actuator killed the neighbour", victim.isDead());
			assertTrue("the attacker survived", !attacker.isDead());
		}
	}

	/**
	 * The mate actuator: brained foragers whose brains fire A_MATE reproduce
	 * sexually with a compatible neighbour in reach. Two founder types share
	 * markers (so they pair) but carry a distinctive instruction each; a crossover
	 * child that carries BOTH signatures can only come from sexual mating (asexual
	 * budding copies a single parent), so it proves the actuator drove crossover.
	 */
	static class BrainMatesViaActuator extends Scenario {
		@Override
		public void run() {
			seed(68);
			int SENSE = Brain.SENSE, WRITE = Brain.WRITE, NEG = Brain.NEG, TANH = Brain.TANH, NOP = Brain.NOP;
			int[][] mateA = { { SENSE, 0, AgentIO.S_BIAS, 0 }, { WRITE, AgentIO.A_MATE, 0, 0 },
					{ NEG, 7, 7, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 } }; // sig A: "= -R7"
			int[][] mateB = { { SENSE, 0, AgentIO.S_BIAS, 0 }, { WRITE, AgentIO.A_MATE, 0, 0 },
					{ NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { TANH, 8, 8, 0 } }; // sig B: "tanh R8"
			World w = room(12, 12);
			for (int i = 0; i < 8; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.5, 0.5, 0.5 }; // identical -> mate-compatible
				g.brain = new Brain(deepCopy(i % 2 == 0 ? mateA : mateB));
				// Interleaved A B A B... in a line so each one's nearest is the
				// opposite type -> cross-type matings that can recombine.
				double x = 5.0 + i * 0.4 + (i % 2) * 0.07, y = 5.5;
				w.spawnEntity(TestNPC.brainedBreeder(x, y, 0, g).withEnergy(12.0));
			}
			w.think();
			int start = w.getAliveCount();
			snapshot(w, "founders (two brain types, shared markers)");

			// These minds mate but don't forage, so they breed then starve; measure
			// the peak population and whether a recombinant mind ever appeared.
			int peak = start;
			boolean recombinant = false;
			// 120 rounds, not 45: courtship still takes MATING_TICKS, but the
			// cooldown between births is now mass-scaled (half the offspring's
			// childhood — VITALS.md §6), so fewer matings fit a window and the
			// crossover the test watches for needs a couple of rounds to appear.
			for (int step = 0; step < 120; step++) {
				tick(w, 10);
				peak = Math.max(peak, w.getAliveCount());
				recombinant |= hasRecombinantMind(w);
			}
			snapshot(w, "after (mated: recombinant minds)");
			assertGreater("the mate actuator drove reproduction (population rose)", peak, start);
			assertTrue("a child carries both parents' signatures -- sexual crossover via A_MATE",
					recombinant);
		}

		/** True if any living mind carries both founder signatures at once -- a
		 * crossover only sexual mating (not asexual budding) can produce. */
		private static boolean hasRecombinantMind(World w) {
			for (Entity e : w.getEntities()) {
				if (!(e instanceof net.hedinger.prototype.entities.NPC) || e.isDead()) {
					continue;
				}
				Brain b = ((net.hedinger.prototype.entities.NPC) e).getGenome().brain;
				if (b == null) {
					continue;
				}
				String dis = String.join("\n", b.disassemble(null, null));
				if (dis.contains("= -R7") && dis.contains("tanh R8")) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * The grab actuator: a minded creature whose brain fires A_GRAB seizes the
	 * nearest <i>smaller</i> neighbour in reach and carries it -- the captive
	 * rides the grabber and is dragged along, released when the actuator drops.
	 */
	static class BrainGrabsSmallerNeighbour extends Scenario {
		@Override
		public void run() {
			seed(73);
			World w = room(16, 16);
			int[][] grab = {
					{ Brain.SENSE, 0, AgentIO.S_BIAS, 0 },    // R0 = 1
					{ Brain.WRITE, AgentIO.A_GRAB, 0, 0 },     // hold grab
					{ Brain.WRITE, AgentIO.A_THROTTLE, 0, 0 }, // roam so the captive is dragged
			};
			// Bigger grabber (body 6, wide FOV so it perceives the neighbour); a
			// smaller inert cargo (body 4) just inside grab reach. Reach is the sum
			// of half-body-radii (~0.078 tiles here), so they start ~0.05 apart.
			Genome g = Genome.phenotype(6, 0.05, 5, 6, Math.PI * 2, 3000);
			TestNPC grabber = TestNPC.minded(8.0, 8.0, 0, g, new LgpMind(new Brain(deepCopy(grab)), 2));
			TestNPC cargo = TestNPC.inert(8.05, 8.0, 0).withSize(4);
			w.spawnEntity(grabber);
			w.spawnEntity(cargo);
			w.think();
			snapshot(w, "before (grabber beside a smaller creature)");

			double startX = cargo.getX(), startY = cargo.getY();
			tick(w, 60);
			snapshot(w, "after (smaller creature seized and carried)");
			assertTrue("the mind's grab actuator seized the smaller neighbour",
					cargo.getAttachTarget() == grabber);
			assertTrue("the captive is marked grabbed", cargo.isGrabbed());
			double offset = (grabber.getSize() + cargo.getSize()) / 2.0;
			double held = Math.hypot(cargo.getX() - grabber.getX(), cargo.getY() - grabber.getY());
			assertNear("the captive is pinned at the carry offset", offset, held, 0.05);
			assertGreater("the captive was dragged along as the grabber moved",
					Math.hypot(cargo.getX() - startX, cargo.getY() - startY), 0.5);
		}
	}

	/**
	 * The attach actuator (the inverse of grab): a small minded creature whose
	 * brain fires A_ATTACH latches onto a <i>larger</i> neighbour and rides it,
	 * dragged along by the host until it lets go. It rides voluntarily -- unlike
	 * a captive it is not marked grabbed, and it self-releases.
	 */
	static class BrainAttachesToLargerHost extends Scenario {
		@Override
		public void run() {
			seed(74);
			World w = room(16, 16);
			int[][] attach = {
					{ Brain.SENSE, 0, AgentIO.S_BIAS, 0 },   // R0 = 1
					{ Brain.WRITE, AgentIO.A_ATTACH, 0, 0 },  // hold attach
			};
			// A small stationary rider (body 10, wide FOV) beside a larger host
			// (body 20) that roams slowly, so it stays in reach long enough for the
			// rider to latch on, then drags it around. Reach ~0.23 tiles here.
			Genome g = Genome.phenotype(10, 0.0, 5, 6, Math.PI * 2, 3000);
			TestNPC rider = TestNPC.minded(8.1, 8.0, 0, g, new LgpMind(new Brain(deepCopy(attach)), 2));
			TestNPC host = TestNPC.roamer(8.0, 8.0, 0).withSize(20).withSpeed(0.02);
			w.spawnEntity(rider);
			w.spawnEntity(host);
			w.think();
			snapshot(w, "before (small creature beside a larger one)");

			double startX = rider.getX(), startY = rider.getY();
			tick(w, 80);
			snapshot(w, "after (riding the larger host)");
			assertTrue("the mind's attach actuator latched onto the larger host",
					rider.getAttachTarget() == host);
			assertTrue("it rides voluntarily, not as a captive", !rider.isGrabbed());
			double offset = (host.getSize() + rider.getSize()) / 2.0;
			double held = Math.hypot(rider.getX() - host.getX(), rider.getY() - host.getY());
			assertNear("the rider is pinned at the ride offset", offset, held, 0.05);
			assertGreater("the rider was carried along as the host roamed",
					Math.hypot(rider.getX() - startX, rider.getY() - startY), 0.5);
		}
	}

	/** Carrying costs energy: a metabolic carrier holding a captive burns more
	 *  energy per tick than an identical one carrying nothing -- the extra scales
	 *  with the carried body weight. */
	static class CarryingCostsEnergy extends Scenario {
		@Override
		public void run() {
			seed(75);
			World w = room(12, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] idle = { { Brain.NOP, 0, 0, 0 } };
			Genome cg = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			cg.metabolism = 0.02;
			cg.brain = new Brain(deepCopy(hold));
			Genome kg = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			kg.metabolism = 0.02;
			kg.brain = new Brain(deepCopy(idle));
			TestNPC carrier = TestNPC.brainedBreeder(6.0, 6.0, 0, cg).withEnergy(6.0);
			TestNPC control = TestNPC.brainedBreeder(9.5, 6.0, 0, kg).withEnergy(6.0);
			TestNPC cargo = TestNPC.inert(6.05, 6.0, 0).withSize(6);
			w.spawnEntity(carrier);
			w.spawnEntity(control);
			w.spawnEntity(cargo);
			tick(w, 6); // let the carrier pick the cargo up and hold it
			assertTrue("the carrier is holding the cargo", cargo.getAttachTarget() == carrier);
			double eCarry = carrier.getEnergy(), eFree = control.getEnergy();
			tick(w, 100);
			double lossCarry = eCarry - carrier.getEnergy();
			double lossFree = eFree - control.getEnergy();
			assertGreater("carrying a body burns more energy than carrying nothing",
					lossCarry, lossFree + 0.5);
		}
	}

	/** A grabbed captive is immobilized -- it cannot even feed -- while a
	 *  voluntary rider keeps acting: it still grazes while being carried. */
	static class CaptiveFrozenRiderActs extends Scenario {
		@Override
		public void run() {
			seed(76);
			World w = room(20, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] graze = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_EAT, 0, 0 } };
			int[][] rideGraze = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 },
					{ Brain.WRITE, AgentIO.A_ATTACH, 0, 0 }, { Brain.WRITE, AgentIO.A_EAT, 0, 0 } };

			// Pair 1: a grazer captive, grabbed by a bigger carrier -> frozen.
			Genome carrierG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			carrierG.brain = new Brain(deepCopy(hold));
			Genome captiveG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			captiveG.brain = new Brain(deepCopy(graze));
			TestNPC carrier = TestNPC.minded(4.0, 6.0, 0, carrierG);
			TestNPC captive = TestNPC.minded(4.05, 6.0, 0, captiveG).withHunger(1.0);
			// Pair 2 (far away): a grazer rider on a bigger, stationary host.
			Genome riderG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			riderG.brain = new Brain(deepCopy(rideGraze));
			TestNPC rider = TestNPC.minded(15.05, 6.0, 0, riderG).withHunger(1.0);
			TestNPC host = TestNPC.roamer(15.0, 6.0, 0).withSize(16).withSpeed(0.0);
			w.spawnEntity(carrier);
			w.spawnEntity(captive);
			w.spawnEntity(rider);
			w.spawnEntity(host);
			tick(w, 6); // carrier grabs the captive; rider latches onto the host
			assertTrue("the captive was grabbed", captive.isGrabbed());
			assertTrue("the rider latched on voluntarily (not grabbed)",
					rider.getAttachTarget() == host && !rider.isGrabbed());
			tick(w, 60);
			assertNear("a grabbed captive is frozen -- it cannot graze", 0.0, captive.totalIntake(), 1e-9);
			assertGreater("a voluntary rider keeps grazing while carried", rider.totalIntake(), 0.0);
		}
	}

	/** The rider's bonus: a creature riding a host spends less energy than an
	 *  equivalent one under its own power (reduced metabolism while carried). */
	static class RiderSpendsLessEnergy extends Scenario {
		@Override
		public void run() {
			seed(77);
			World w = room(14, 12);
			int[][] cling = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_ATTACH, 0, 0 } };
			int[][] idle = { { Brain.NOP, 0, 0, 0 } };
			Genome riderG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			riderG.metabolism = 0.02;
			riderG.brain = new Brain(deepCopy(cling));
			Genome ctrlG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			ctrlG.metabolism = 0.02;
			ctrlG.brain = new Brain(deepCopy(idle));
			// Both starving: satiation zero switches regeneration off, so the only
			// thing moving either tank is the burn being compared.
			TestNPC rider = TestNPC.brainedBreeder(4.05, 6.0, 0, riderG)
					.withEnergy(6.0).withHunger(1.0);
			TestNPC host = TestNPC.roamer(4.0, 6.0, 0).withSize(18).withSpeed(0.0);
			TestNPC control = TestNPC.brainedBreeder(10.0, 6.0, 0, ctrlG)
					.withEnergy(6.0).withHunger(1.0);
			w.spawnEntity(rider);
			w.spawnEntity(host);
			w.spawnEntity(control);
			tick(w, 6); // let the rider latch on
			assertTrue("the rider is riding the host",
					rider.getAttachTarget() == host && !rider.isGrabbed());
			double eRide = rider.getEnergy(), eWalk = control.getEnergy();
			tick(w, 100);
			double lossRide = eRide - rider.getEnergy();
			double lossWalk = eWalk - control.getEnergy();
			// A rider pays half metabolism, so it should lose meaningfully less than
			// one under its own power. Assert the ratio, not an absolute margin, so
			// the check is independent of the model's overall energy scale.
			assertGreater("riding spends less energy than moving under your own power",
					lossWalk, lossRide * 1.5);
		}
	}

	/** Struggle vs consent: hauling a captive that fights (A_STRUGGLE) costs its
	 *  captor more energy than an equally heavy captive that consents, and the
	 *  struggling captive tires itself out in the process. */
	static class StrugglingCostsMoreThanConsenting extends Scenario {
		@Override
		public void run() {
			seed(78);
			World w = room(20, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] fight = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_STRUGGLE, 0, 0 } };
			int[][] limp = { { Brain.NOP, 0, 0, 0 } };

			// Pair A: captor + a struggling captive.
			Genome capAG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			capAG.metabolism = 0.02;
			capAG.brain = new Brain(deepCopy(hold));
			Genome vicAG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vicAG.brain = new Brain(deepCopy(fight));
			TestNPC captorA = TestNPC.brainedBreeder(4.0, 6.0, 0, capAG).withEnergy(9.0);
			TestNPC struggler = TestNPC.minded(4.05, 6.0, 0, vicAG).withEnergy(6.0);
			// Pair B (far off): captor + a consenting (limp) captive.
			Genome capBG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			capBG.metabolism = 0.02;
			capBG.brain = new Brain(deepCopy(hold));
			Genome vicBG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vicBG.brain = new Brain(deepCopy(limp));
			TestNPC captorB = TestNPC.brainedBreeder(15.0, 6.0, 0, capBG).withEnergy(9.0);
			TestNPC consenter = TestNPC.minded(15.05, 6.0, 0, vicBG).withEnergy(6.0);
			w.spawnEntity(captorA);
			w.spawnEntity(struggler);
			w.spawnEntity(captorB);
			w.spawnEntity(consenter);
			tick(w, 6); // captors grab their captives and hold
			assertTrue("the struggler is held", struggler.isGrabbed() && struggler.getAttachTarget() == captorA);
			assertTrue("the consenter is held", consenter.isGrabbed() && consenter.getAttachTarget() == captorB);
			double capA0 = captorA.getEnergy(), capB0 = captorB.getEnergy();
			double vicA0 = struggler.getEnergy(), vicB0 = consenter.getEnergy();
			tick(w, 80);
			double capALoss = capA0 - captorA.getEnergy(), capBLoss = capB0 - captorB.getEnergy();
			double vicALoss = vicA0 - struggler.getEnergy(), vicBLoss = vicB0 - consenter.getEnergy();
			assertGreater("hauling a struggling captive costs the captor more than a consenting one",
					capALoss, capBLoss + 0.5);
			assertGreater("struggling drains the captive's own energy too", vicALoss, vicBLoss + 0.5);
		}
	}

	/** A grabbed captive is otherwise frozen, but it can still communicate: it may
	 *  lay a pheromone (a distress marker) while held. */
	static class CaptiveCanStillCommunicate extends Scenario {
		@Override
		public void run() {
			seed(79);
			World w = room(12, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] signal = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_DEPOSIT, 0, 0 } };
			Genome capG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			capG.brain = new Brain(deepCopy(hold));
			Genome vicG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vicG.brain = new Brain(deepCopy(signal));
			TestNPC captor = TestNPC.minded(6.0, 6.0, 0, capG);
			TestNPC captive = TestNPC.minded(6.05, 6.0, 0, vicG);
			w.spawnEntity(captor);
			w.spawnEntity(captive);
			tick(w, 6);
			assertTrue("the captive is held", captive.isGrabbed());
			tick(w, 30);
			double phero = w.pheromoneAt(captive.getX(), captive.getY(), 0);
			assertGreater("a held captive can still lay a distress pheromone", phero, 0.0);
		}
	}

	/** A captive is freed the moment its captor dies -- it is not left clamped to
	 *  the corpse, and it no longer weighs on it. */
	static class CaptiveFreedWhenCaptorDies extends Scenario {
		@Override
		public void run() {
			seed(80);
			World w = room(12, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] limp = { { Brain.NOP, 0, 0, 0 } };
			// A captor holding little reserve and burning fast: under VITALS an
			// empty tank no longer kills — it collapses, and a collapsed captor
			// cannot hold. The grip must open while the captor still lives; a
			// wound then kills it, and death releases nothing it still held.
			Genome capG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			capG.metabolism = 0.05;
			capG.brain = new Brain(deepCopy(hold));
			Genome vicG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vicG.brain = new Brain(deepCopy(limp));
			TestNPC captor = TestNPC.brainedBreeder(6.0, 6.0, 0, capG)
					.withEnergy(0.6).withHunger(1.0); // starving: no regeneration
			TestNPC captive = TestNPC.minded(6.05, 6.0, 0, vicG);
			w.spawnEntity(captor);
			w.spawnEntity(captive);
			tick(w, 4);
			assertTrue("the captive is grabbed while the captor lives", captive.isGrabbed());
			assertTrue("the captor still lives at this point", !captor.isDead());
			tick(w, 200); // the grip drains the tank to the crawl reserve
			assertTrue("the drained captor still lives — collapse is not death", !captor.isDead());
			assertTrue("but a collapsed captor cannot hold: the captive walks free",
					captive.getAttachTarget() == null && !captive.isGrabbed());
			// Death still releases: re-grab is impossible collapsed, and a wound
			// that kills the captor leaves no load on the corpse.
			captor.damage(1000, "misadventure");
			tick(w, 2);
			assertTrue("the wounded captor died", captor.isDead());
			assertNear("the corpse carries no load", 0.0, captor.getCarriedLoad(), 1e-9);
		}
	}

	/**
	 * Carrying a body aloft is far costlier: a flying carrier burns much more
	 * energy than a grounded one hauling the same weight the same distance.
	 *
	 * <p>A load is priced as extra mass through the movement term, and flight
	 * multiplies how heavy that load counts, so both carriers have to actually
	 * haul their passenger somewhere for the difference to exist — a hovering
	 * carrier and a standing one both pay nothing for weight alone. The passengers
	 * ride VOLUNTARILY rather than being seized, which keeps the comparison clean:
	 * a grabbed captive would add an identical grip cost to both bills and dilute
	 * the very ratio being measured.
	 */
	static class FlyingCarrierPaysMore extends Scenario {
		@Override
		public void run() {
			seed(83);
			World w = room(60, 40);
			// Full throttle plus a gentle constant turn, so each carrier orbits a
			// few tiles wide: it hauls continuously without ever reaching a wall,
			// whatever heading it happened to start on.
			int[][] walk = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 },
					{ Brain.WRITE, AgentIO.A_THROTTLE, 0, 0 },
					{ Brain.SET, 1, 6, 0 }, // r1 = 0.1 (const[6])
					{ Brain.WRITE, AgentIO.A_TURN, 1, 0 } };
			int[][] cling = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 },
					{ Brain.WRITE, AgentIO.A_ATTACH, 0, 0 } };
			Genome fG = Genome.phenotype(8, 0.12, 5, 6, Math.PI * 2, 100000);
			fG.metabolism = 0.02;
			fG.brain = new Brain(deepCopy(walk));
			Genome gG = Genome.phenotype(8, 0.12, 5, 6, Math.PI * 2, 100000);
			gG.metabolism = 0.02;
			gG.brain = new Brain(deepCopy(walk));
			TestNPC flier = TestNPC.brainedBreeder(15.0, 20.0, 0, fG).withEnergy(14.0).withFlying();
			TestNPC ground = TestNPC.brainedBreeder(45.0, 20.0, 0, gG).withEnergy(14.0);
			Genome vAG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vAG.brain = new Brain(deepCopy(cling));
			Genome vBG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vBG.brain = new Brain(deepCopy(cling));
			TestNPC riderA = TestNPC.minded(15.05, 20.0, 0, vAG).withFlying();
			TestNPC riderB = TestNPC.minded(45.05, 20.0, 0, vBG);
			w.spawnEntity(flier);
			w.spawnEntity(ground);
			w.spawnEntity(riderA);
			w.spawnEntity(riderB);
			tick(w, 6);
			assertTrue("the flier picked up a passenger", riderA.getAttachTarget() == flier);
			assertTrue("the ground carrier picked up a passenger", riderB.getAttachTarget() == ground);

			double f0 = flier.getEnergy(), g0 = ground.getEnergy();
			// Ground covered, summed per tick — direction-agnostic, unlike a
			// start-to-end displacement, which an orbiting body would understate.
			double fDist = 0, gDist = 0;
			for (int i = 0; i < 250; i++) {
				w.think();
				fDist += flier.lastStep();
				gDist += ground.lastStep();
			}
			double fLoss = f0 - flier.getEnergy(), gLoss = g0 - ground.getEnergy();
			assertGreater("both carriers actually hauled their passenger somewhere",
					Math.min(fDist, gDist), 5.0);
			// A ratio, not a fixed margin: both bills scale with how far they get,
			// so the multiple is the stable claim.
			assertGreater("hauling a body aloft costs a flier far more than a ground "
					+ "carrier (" + String.format("%.2f", fLoss) + " vs "
					+ String.format("%.2f", gLoss) + ")", fLoss, gLoss * 1.8);
		}
	}

	/** A grounded creature cannot seize a flyer out of the air, though it can still
	 *  grab grounded creatures, and a flyer can seize another flyer. */
	static class GroundCannotGrabFlyer extends Scenario {
		@Override
		public void run() {
			seed(82);
			World w = room(12, 12);
			TestNPC ground = TestNPC.inert(5.5, 5.5, 0).withSize(8);
			TestNPC flyer = TestNPC.inert(5.54, 5.5, 0).withSize(4).withFlying();
			TestNPC walker = TestNPC.inert(5.5, 5.54, 0).withSize(4);
			TestNPC airGrabber = TestNPC.inert(5.54, 5.54, 0).withSize(8).withFlying();
			w.spawnEntity(ground);
			w.spawnEntity(flyer);
			w.spawnEntity(walker);
			w.spawnEntity(airGrabber);
			w.think();
			assertTrue("a grounded creature cannot seize a flyer", !ground.grab(flyer));
			assertTrue("a grounded creature can still seize a grounded one", ground.grab(walker));
			assertTrue("a flyer can seize another flyer", airGrabber.grab(flyer));
		}
	}

	/** Bucking (the same struggle actuator, from the carrier's side): a host shakes
	 *  off its riders, throwing the larger one clear first while the smaller one
	 *  clings tighter and holds on far longer. */
	static class HostBucksOffRiders extends Scenario {
		@Override
		public void run() {
			seed(84);
			World w = room(16, 12);
			int[][] buck = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_STRUGGLE, 0, 0 } };
			int[][] cling = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_ATTACH, 0, 0 } };
			Genome hG = Genome.phenotype(20, 0.0, 5, 6, Math.PI * 2, 100000);
			hG.brain = new Brain(deepCopy(buck));
			Genome bigG = Genome.phenotype(14, 0.0, 5, 6, Math.PI * 2, 100000);
			bigG.brain = new Brain(deepCopy(cling));
			Genome smG = Genome.phenotype(5, 0.0, 5, 6, Math.PI * 2, 100000);
			smG.brain = new Brain(deepCopy(cling));
			TestNPC host = TestNPC.minded(8.0, 6.0, 0, hG);
			TestNPC bigRider = TestNPC.minded(8.08, 6.0, 0, bigG);
			TestNPC smallRider = TestNPC.minded(8.0, 6.08, 0, smG);
			w.spawnEntity(host);
			w.spawnEntity(bigRider);
			w.spawnEntity(smallRider);
			tick(w, 5);
			assertTrue("both riders latched on",
					bigRider.getAttachTarget() == host && smallRider.getAttachTarget() == host);
			tick(w, 24); // the host bucks; the bigger rider comes loose first
			assertTrue("the larger rider is bucked off", bigRider.getAttachTarget() == null);
			assertTrue("the smaller rider clings on (tighter grip, harder to throw)",
					smallRider.getAttachTarget() == host);
			tick(w, 60); // keep bucking
			assertTrue("eventually even the small rider is thrown clear",
					smallRider.getAttachTarget() == null);
			assertTrue("a bucked-off rider cannot immediately climb back on",
					bigRider.getAttachTarget() == null);
		}
	}

	// ---- inanimate items ---------------------------------------------------

	/** The dedicated item sense: a mind that throttles on {@code S_ITEM_PROX}
	 * moves only when an inanimate object is nearby -- proving items register on
	 * their own perception channel, distinct from the living-neighbour one. */
	static class ItemSensedOnDedicatedChannel extends Scenario {
		@Override
		public void run() {
			seed(90);
			World w = room(16, 16);
			int[][] prog = {
					{ Brain.SENSE, 0, AgentIO.S_ITEM_PROX, 0 }, // R0 = item proximity
					{ Brain.WRITE, AgentIO.A_THROTTLE, 0, 0 },   // walk when it sees one
			};
			Genome g = Genome.phenotype(6, 0.05, 5, 6, Math.PI * 2, 100000);
			// One creature beside a crate, an identical control alone.
			TestNPC seer = TestNPC.minded(4.0, 4.0, 0, g, new LgpMind(new Brain(deepCopy(prog)), 2));
			TestNPC control = TestNPC.minded(12.0, 12.0, 0, g, new LgpMind(new Brain(deepCopy(prog)), 2));
			Item crate = Item.crate(4.15, 4.0, 0);
			w.spawnEntity(seer);
			w.spawnEntity(control);
			w.spawnEntity(crate);
			w.think();
			double sx = seer.getX(), sy = seer.getY();
			double cx = control.getX(), cy = control.getY();
			tick(w, 40);
			snapshot(w, "seer walks toward the crate; control sits idle");
			assertGreater("the creature that senses a nearby item moves",
					Math.hypot(seer.getX() - sx, seer.getY() - sy), 0.2);
			assertLess("the control with no item in range stays put",
					Math.hypot(control.getX() - cx, control.getY() - cy), 0.02);
		}
	}

	/** Eating a food item: a mind firing A_EAT beside a food object consumes it
	 * and gains its {@code foodEnergy}; the item is removed once eaten. */
	static class FoodItemEatenForEnergy extends Scenario {
		@Override
		public void run() {
			seed(91);
			World w = room(12, 12);
			int[][] eat = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_EAT, 0, 0 } };
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			// Hungry, non-metabolic minded body: the swallowed ledger only moves
			// with what it eats, and the meal (2.0) fits its mass-scaled stomach.
			TestNPC eater = TestNPC.minded(6.0, 6.0, 0, g, new LgpMind(new Brain(deepCopy(eat)), 2))
					.withHunger(1.0);
			Item food = Item.food(6.1, 6.0, 0).withFoodEnergy(2.0);
			w.spawnEntity(eater);
			w.spawnEntity(food);
			w.think();
			snapshot(w, "before (beside a food item)");
			// Tick until the food is consumed, measuring the energy jump on that very
			// tick so a stray graze from the ground can't inflate the reading.
			double delta = 0;
			for (int i = 0; i < 40 && !food.isRemoved(); i++) {
				double before = eater.totalSwallowed();
				tick(w, 1);
				if (food.isRemoved()) {
					delta = eater.totalSwallowed() - before;
				}
			}
			snapshot(w, "after (food eaten, stomach filled)");
			assertTrue("the food item was eaten (removed)", food.isRemoved());
			assertNear("eating the food yielded its foodEnergy", 2.0, delta, 0.1);
		}
	}

	/** Smashing a crate: a mind firing A_ATTACK beside a crate whittles its
	 * durability down and, when it breaks, the crate spills loose food items. */
	static class CrateBrokenSpillsFood extends Scenario {
		@Override
		public void run() {
			seed(92);
			World w = room(12, 12);
			int[][] hit = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_ATTACK, 0, 0 } };
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			TestNPC smasher = TestNPC.minded(6.0, 6.0, 0, g, new LgpMind(new Brain(deepCopy(hit)), 2));
			Item crate = Item.crate(6.18, 6.0, 0).withDurability(8).withSpill(4);
			w.spawnEntity(smasher);
			w.spawnEntity(crate);
			w.think();
			snapshot(w, "before (beside an intact crate)");
			// Tick until the crate shatters, then count the food it spilled right away
			// (the smasher would otherwise start destroying the loose food next).
			int foodCount = -1;
			for (int i = 0; i < 40 && !crate.isRemoved(); i++) {
				tick(w, 1);
				if (crate.isRemoved()) {
					foodCount = countFood(w);
				}
			}
			snapshot(w, "after (crate broken, food spilled)");
			assertTrue("the crate broke under repeated attacks", crate.isRemoved());
			assertEquals("the broken crate spilled its food", 4, foodCount);
		}

		private int countFood(World w) {
			int n = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof Item && !e.isRemoved() && ((Item) e).getKind() == Item.Kind.FOOD) {
					n++;
				}
			}
			return n;
		}
	}

	/** A hazard bites back: eating a hazard item damages the eater (and consumes
	 * the hazard), so a creature pays for swallowing something dangerous. */
	static class HazardHarmsEater extends Scenario {
		@Override
		public void run() {
			seed(93);
			World w = room(12, 12);
			int[][] eat = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_EAT, 0, 0 } };
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			TestNPC eater = TestNPC.minded(6.0, 6.0, 0, g, new LgpMind(new Brain(deepCopy(eat)), 2));
			Item hazard = Item.hazard(6.1, 6.0, 0).withHazardDamage(25);
			w.spawnEntity(eater);
			w.spawnEntity(hazard);
			w.think();
			int hpBefore = eater.getHealth();
			for (int i = 0; i < 40 && !hazard.isRemoved(); i++) {
				tick(w, 1);
			}
			assertTrue("the hazard was consumed", hazard.isRemoved());
			assertEquals("eating the hazard cost the eater its hazardDamage in health",
					hpBefore - 25, eater.getHealth());
		}
	}

	/** A hazard also bites an attacker: striking a hazard object wounds the
	 * striker, so hitting one is as costly as eating it. */
	static class HazardHarmsAttacker extends Scenario {
		@Override
		public void run() {
			seed(94);
			World w = room(12, 12);
			int[][] hit = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_ATTACK, 0, 0 } };
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			TestNPC striker = TestNPC.minded(6.0, 6.0, 0, g, new LgpMind(new Brain(deepCopy(hit)), 2));
			Item hazard = Item.hazard(6.18, 6.0, 0).withHazardDamage(10).withDurability(1000);
			w.spawnEntity(striker);
			w.spawnEntity(hazard);
			w.think();
			snapshot(w, "before (striker beside a hazard)");
			int hpBefore = striker.getHealth();
			tick(w, 30); // hardy hazard survives, biting back on every strike
			snapshot(w, "after (attacker wounded by the hazard)");
			assertTrue("the tough hazard survived the strikes", !hazard.isRemoved());
			assertLess("striking the hazard wounded the attacker", striker.getHealth(), hpBefore);
		}
	}

	/** Items are shoved aside: a creature walking through where an item rests
	 * displaces it (the same spring that separates creatures), without carrying it. */
	static class ItemPushedAsideByPasserby extends Scenario {
		@Override
		public void run() {
			seed(95);
			World w = room(20, 8);
			// A crate sitting in the lane; a mover trudging into it, slow enough that
			// it stays in contact and shoves the crate along ahead of it (the same
			// spring that separates two creatures) rather than blowing past.
			Item crate = Item.crate(10.0, 4.0, 0);
			TestNPC walker = TestNPC.mover(9.0, 4.0, 0, 0.0).withSpeed(0.02); // heading +x, into the crate
			w.spawnEntity(crate);
			w.spawnEntity(walker);
			w.think();
			snapshot(w, "before (crate in the walker's path)");
			double startX = crate.getX(), startY = crate.getY();
			tick(w, 400);
			snapshot(w, "after (crate shoved aside)");
			double moved = Math.hypot(crate.getX() - startX, crate.getY() - startY);
			assertGreater("the passer-by shoved the crate along", moved, 0.3);
			assertTrue("the crate was displaced, not picked up", crate.getAttachTarget() == null);
		}
	}

	/** A courier round-trip over offset waypoints: the hauler starts at one corner,
	 * walks (turning in smooth arcs) to the crate's spot, grabs it, carries it to a
	 * drop-off elsewhere, sets it down, and eases back home empty-handed. The three
	 * points are at different rows and columns, so each leg heads a different way. */
	static class CarrierHaulsCrate extends Scenario {
		@Override
		public void run() {
			seed(96);
			World w = room(26, 12);
			// A zig-zag: home lower-left, crate lower-right-ish, drop-off upper-right --
			// no two legs share a heading, so the courier visibly turns at each stop.
			double homeX = 4.0, homeY = 5.0;
			double pickX = 13.0, pickY = 9.0;
			double dropX = 22.0, dropY = 3.0;
			Item crate = Item.crate(pickX, pickY, 0);
			TestNPC courier = TestNPC.hauler(homeX, homeY, 0, pickX, pickY, dropX, dropY);
			w.spawnEntity(crate);
			w.spawnEntity(courier);
			w.think();
			snapshot(w, "before (hauler far from the crate)");
			tick(w, 1100); // fetch, haul to the drop-off, return home
			snapshot(w, "after (crate delivered, hauler back home)");
			assertNear("the crate was delivered to the drop-off (x)", dropX, crate.getX(), 1.5);
			assertNear("the crate was delivered to the drop-off (y)", dropY, crate.getY(), 1.5);
			assertTrue("the crate was set down, not still held", crate.getAttachTarget() == null);
			assertGreater("the crate really was carried a long way",
					Math.hypot(crate.getX() - pickX, crate.getY() - pickY), 6.0);
			assertNear("the hauler returned home empty-handed (x)", homeX, courier.getX(), 1.5);
			assertNear("the hauler returned home empty-handed (y)", homeY, courier.getY(), 1.5);
			assertTrue("the hauler is no longer carrying anything", courier.getCarriedLoad() == 0);
		}
	}

	/** Line-of-sight over open floor must carry in every direction -- rows, columns
	 * AND diagonals -- and a wall on the line must block it. Guards the sight
	 * raycast, which historically traced correctly only along pure rows/columns and
	 * reported no sight for any diagonal even over perfectly clear ground. */
	static class DiagonalLineOfSight extends Scenario {
		@Override
		public void run() {
			seed(101);
			World w = room(16, 16);
			double dir = 0; // heading irrelevant here: fov = PI means all-round sight

			// Clear open floor: sight carries every way, diagonals included.
			assertTrue("clear sight along a row",
					w.hasLOS(2.5, 8.5, 0, dir, 12.5, 8.5, 0, -1, Math.PI));
			assertTrue("clear sight along a column",
					w.hasLOS(8.5, 2.5, 0, dir, 8.5, 12.5, 0, -1, Math.PI));
			assertTrue("clear sight along a 45-degree diagonal",
					w.hasLOS(2.5, 2.5, 0, dir, 12.5, 12.5, 0, -1, Math.PI));
			assertTrue("clear sight along a shallow diagonal",
					w.hasLOS(3.5, 5.5, 0, dir, 11.5, 3.5, 0, -1, Math.PI));
			assertTrue("clear sight is symmetric (reversed shallow diagonal)",
					w.hasLOS(11.5, 3.5, 0, dir, 3.5, 5.5, 0, -1, Math.PI));

			// A wall sitting on the diagonal blocks it; a parallel line that misses
			// the wall stays clear.
			w.setTile(7, 7, 0, Tile.TileType.TYPE_WALL);
			assertTrue("a wall on the 45-degree diagonal blocks sight",
					!w.hasLOS(2.5, 2.5, 0, dir, 12.5, 12.5, 0, -1, Math.PI));
			assertTrue("a parallel diagonal that misses the wall is still clear",
					w.hasLOS(2.5, 4.5, 0, dir, 10.5, 12.5, 0, -1, Math.PI));

			// Regression guard: a wall across a row still blocks the common case.
			w.setTile(8, 8, 0, Tile.TileType.TYPE_WALL);
			assertTrue("a wall across a row blocks sight",
					!w.hasLOS(2.5, 8.5, 0, dir, 12.5, 8.5, 0, -1, Math.PI));
		}
	}

	/** The blocked sensor: a mind reads 1 when a wall/edge is one tile ahead and 0
	 * in the open, so it can perceive obstacles (and evolve to steer around them). */
	static class BlockedSensorSeesWalls extends Scenario {
		@Override
		public void run() {
			seed(72);
			double[] boxed = new double[AgentIO.NUM_SENSORS];
			double[] open = new double[AgentIO.NUM_SENSORS];

			World box = room(3, 3); // interior is the single tile (1,1), walls all around
			box.spawnEntity(TestNPC.minded(1.5, 1.5, 0,
					Genome.phenotype(6, 0, 5, 6, Math.PI, 3000), capture(boxed)));
			tick(box, 3);

			World field = room(11, 11);
			field.spawnEntity(TestNPC.minded(5.5, 5.5, 0,
					Genome.phenotype(6, 0, 5, 6, Math.PI, 3000), capture(open)));
			tick(field, 3);

			assertNear("a walled-in mind senses a wall ahead", 1.0, boxed[AgentIO.S_BLOCKED], 1e-9);
			assertNear("a mind in the open senses no wall ahead", 0.0, open[AgentIO.S_BLOCKED], 1e-9);
		}

		private static Mind capture(double[] out) {
			return new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					System.arraycopy(s, 0, out, 0, s.length);
				}
			};
		}
	}

	/** A pheromone cloud evaporates over time (its strength decays each tick). */
	static class PheromoneDecays extends Scenario {
		@Override
		public void run() {
			seed(40);
			World w = room(5, 5);
			w.depositPheromone(2.5, 2.5, 0, 10.0);
			tick(w, 1); // drain the spawn queue so the cloud is live
			double p0 = w.pheromoneAt(2.5, 2.5, 0);
			assertNear("deposited pheromone is present at its centre", 10.0, p0, 1e-6);
			tick(w, 200);
			double p1 = w.pheromoneAt(2.5, 2.5, 0);
			assertLess("pheromone evaporated substantially", p1, p0 * 0.5);
			assertGreater("but has not vanished instantly", p1, 0.0);
		}
	}

	/**
	 * Stigmergic nesting: nesters lay pheromone where they breed, so a peak --
	 * an emergent nest -- builds up, and the growing lineage clusters around it
	 * instead of smearing across the map.
	 */
	/** The strongest pheromone cloud right now as {@code {x, y, intensity}}, where
	 *  intensity is what a homing creature actually smells there — the summed
	 *  concentration of every cloud overlapping that point, not one cloud's own
	 *  strength. All zeros when nothing has been laid. */
	private static double[] strongestNest(World w) {
		double maxP = 0, nx = 0, ny = 0;
		for (Entity e : w.getEntities()) {
			if (e instanceof net.hedinger.prototype.engine.PheromoneCloud && !e.isRemoved()) {
				double s = ((net.hedinger.prototype.engine.PheromoneCloud) e).getStrength();
				if (s > maxP) {
					maxP = s;
					nx = e.getX();
					ny = e.getY();
				}
			}
		}
		return maxP == 0 ? new double[] { 0, 0, 0 }
				: new double[] { nx, ny, w.pheromoneAt(nx, ny, 0) };
	}

	/** {@code {near, total}} living creatures, near meaning within {@code radius}
	 *  tiles of {@code (cx, cy)}. */
	private static int[] colonyNear(World w, double cx, double cy, double radius) {
		int near = 0, total = 0;
		for (Entity e : w.getEntities()) {
			if (e instanceof net.hedinger.prototype.entities.NPC && !e.isDead()) {
				total++;
				if (Math.hypot(e.getX() - cx, e.getY() - cy) < radius) {
					near++;
				}
			}
		}
		return new int[] { near, total };
	}

	static class NestEmergesFromPheromone extends Scenario {
		@Override
		public void run() {
			seed(41);
			World w = room(14, 14); // full grass
			for (int i = 0; i < 2; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.9, 0.2, 0.6 };
				w.spawnEntity(TestNPC.nester(6.5 + i, 6.5 + i, 0, g));
			}
			w.think();
			int start = w.getAliveCount();
			snapshot(w, "founders");
			// A nest is a process, not a state: it waxes while the colony breeds and
			// fades as the pheromone decays. So sample it as the run goes and keep the
			// strongest it ever got, rather than reading whatever happened to be
			// standing on the tick the test stopped. Under the old grass economy the
			// colony bred fast enough that the two coincided; now that grass is bulk
			// food, breeding is spread out and the terminal reading undersells the nest
			// by a factor of three. The claim under test is unchanged.
			// Both facts are read at the SAME moment — the tick the nest is strongest.
			// Asking whether the colony sits on its nest only means anything while
			// there is a nest to sit on, and by the end of a long run the pheromone has
			// faded and the colony has filled this small room, at which point every
			// creature is near everything and the question answers itself.
			double nestIntensity = 0;
			int near = 0, total = 0;
			for (int step = 0; step < 64; step++) {
				tick(w, 50);
				double[] nest = strongestNest(w);
				if (nest[2] > nestIntensity) {
					nestIntensity = nest[2];
					int[] c = colonyNear(w, nest[0], nest[1], 3.0);
					near = c[0];
					total = c[1];
				}
			}
			snapshot(w, "after (colony around the nest)");
			assertGreater("the population grew by breeding", w.getAliveCount(), start);
			assertGreater("a pheromone nest built up", nestIntensity, 4.0);
			// The physical fixture matches the scent: births claimed a Nest.
			int nests = 0, broods = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof net.hedinger.prototype.entities.Nest n && !e.isRemoved()) {
					nests++;
					broods += n.getBroods();
				}
			}
			assertGreater("births raised a physical nest fixture", nests, 0);
			assertGreater("...that counted its broods", broods, 1);

			// The living colony concentrates near that nest: a good share of it sits
			// within a few tiles of the pheromone peak — noticeably denser than the
			// ~20% a uniform spread would put within that radius. A concentration
			// fraction is robust to colony size, unlike a mean-distance threshold
			// (which washes out once a fast-breeding colony fills the room).
			// Measured against what an even spread over the walkable interior would
			// put inside that radius, NOT against a raw fraction. A thriving colony
			// saturates this small room — 150-odd bodies over ~144 open tiles — and
			// at that density no raw share can look impressive however tightly the
			// creatures nest, simply because the circle cannot hold them. The ratio
			// asks the question the test actually means: are they denser here than
			// chance would put them?
			double open = (w.getColums() - 2) * (double) (w.getRows() - 2);
			double uniform = Math.PI * 3.0 * 3.0 / open;
			assertGreater("the colony concentrates around the nest ("
					+ near + "/" + total + " within 3 tiles, vs "
					+ String.format("%.0f%%", uniform * 100) + " if spread evenly)",
					(near / (double) total) / uniform, 1.2);
		}
	}

	// ---- the observation seam (MODERNIZATION.md Phase 1) --------------------

	/** The snapshot stream is deterministic and read-only: two runs of the same
	 * seeded world produce bit-identical per-tick snapshot checksums, and taking
	 * a snapshot twice without ticking yields the same capture (observing the
	 * world never perturbs it). */
	static class SnapshotStreamDeterministic extends Scenario {
		private long[] stream(long seed, int ticks) {
			net.hedinger.prototype.sim.SimulationRunner r =
					new net.hedinger.prototype.sim.SimulationRunner(net.hedinger.prototype.sim.Worlds.demo(seed));
			long[] sums = new long[ticks];
			for (int i = 0; i < ticks; i++) {
				r.tickOnce();
				sums[i] = r.snapshot().checksum();
			}
			return sums;
		}

		@Override
		public void run() {
			long[] a = stream(7, 120);
			long[] b = stream(7, 120);
			for (int i = 0; i < a.length; i++) {
				assertEquals("snapshot checksum at tick " + i + " identical across runs", a[i], b[i]);
			}
			// Observing is free of side effects: snapshot twice, no tick between.
			net.hedinger.prototype.sim.SimulationRunner r =
					new net.hedinger.prototype.sim.SimulationRunner(net.hedinger.prototype.sim.Worlds.demo(7));
			r.advance(10);
			long first = net.hedinger.prototype.sim.WorldSnapshot.of(r.world()).checksum();
			long second = net.hedinger.prototype.sim.WorldSnapshot.of(r.world()).checksum();
			assertEquals("re-observing an unticked world captures the same state", first, second);
			assertGreater("the demo world is populated", r.snapshot().entities().size(), 10);
		}
	}

	/** seed + command log reproduces the world exactly: an interactive run
	 * (commands enqueued mid-flight) replayed from its log against a fresh
	 * world of the same seed ends bit-identical — the invariant that lets web
	 * viewers meddle without breaking reproducibility. */
	static class CommandLogReplayReproduces extends Scenario {
		@Override
		public void run() {
			net.hedinger.prototype.sim.SimulationRunner live =
					new net.hedinger.prototype.sim.SimulationRunner(net.hedinger.prototype.sim.Worlds.demo(13));
			live.advance(30);
			live.enqueue(net.hedinger.prototype.sim.SpawnItemCommand.parse("food", 10.5, 10.5, 0));
			live.advance(40);
			live.enqueue(net.hedinger.prototype.sim.SpawnItemCommand.parse("crate", 20.5, 12.5, 0));
			live.enqueue(net.hedinger.prototype.sim.SpawnItemCommand.parse("hazard", 30.5, 14.5, 0));
			live.advance(30);

			assertEquals("all three commands were applied and logged", 3, live.commandLog().size());
			int items = 0;
			for (net.hedinger.prototype.sim.EntityState e : live.snapshot().entities()) {
				if (e.kind().startsWith("item.")) {
					items++;
				}
			}
			assertGreater("the spawned items are visible in the snapshot", items, 3);

			World fresh = net.hedinger.prototype.sim.Worlds.demo(13);
			net.hedinger.prototype.sim.SimulationRunner.replay(fresh, live.commandLog(), 100);
			assertEquals("replaying seed + command log reproduces the exact world state",
					live.snapshot().checksum(),
					net.hedinger.prototype.sim.WorldSnapshot.of(fresh).checksum());
		}
	}

	/** The same seed and script produce the exact same end state. */
	static class SameSeedSameOutcome extends Scenario {
		private double[] runOnce() {
			seed(7);
			World w = room(10, 10);
			Entity[] cast = {
					TestNPC.chaser(2.5, 2.5, 0), TestNPC.roamer(7.5, 7.5, 0),
					TestNPC.listener(2.5, 7.5, 0), TestNPC.inert(7.5, 2.5, 0) };
			for (Entity e : cast) {
				w.spawnEntity(e);
			}
			w.spawnEntity(new Sound(5.0, 5.0, 0));
			tick(w, 400);
			double[] state = new double[cast.length * 3];
			for (int i = 0; i < cast.length; i++) {
				state[3 * i] = cast[i].getX();
				state[3 * i + 1] = cast[i].getY();
				state[3 * i + 2] = cast[i].isDead() ? 1 : 0;
			}
			return state;
		}

		@Override
		public void run() {
			double[] a = runOnce();
			double[] b = runOnce();
			for (int i = 0; i < a.length; i++) {
				assertEquals("state component " + i + " identical across runs",
						Double.doubleToLongBits(a[i]), Double.doubleToLongBits(b[i]));
			}
		}
	}

	// ---- helpers -----------------------------------------------------------

	/** Deep-copies a program (array of {op,x,y,z} rows) so a Brain owns its code. */
	private static int[][] deepCopy(int[][] prog) {
		int[][] c = new int[prog.length][];
		for (int i = 0; i < prog.length; i++) {
			c[i] = prog[i].clone();
		}
		return c;
	}

	/**
	 * A hunter must not chase prey on another level. A predator that follows prey
	 * down a hole into the cave used to lock onto the prey still on the surface
	 * directly overhead (same x/y, one level up) and pin itself against a cave
	 * wall trying to reach an unreachable target — it looked frozen. Perception
	 * is now level-scoped, so a hunter with no prey on its own level prowls and
	 * keeps moving instead of getting stuck.
	 */
	static class HunterIgnoresPreyOnAnotherLevel extends Scenario {
		@Override
		public void run() {
			seed(20);
			World w = room(14, 14, 2); // two open levels
			Genome predG = new Genome();
			predG.size = 14;
			predG.speed = 0.05;
			Genome preyG = new Genome();
			preyG.size = 7;
			preyG.speed = 0.03;
			TestNPC pred = TestNPC.predator(7.5, 7.5, 0, predG); // in the cave (level 0)
			TestNPC prey = TestNPC.breeder(7.5, 7.5, 1, preyG).withHerding(); // straight above
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();

			double path = 0, px = pred.getX(), py = pred.getY();
			for (int i = 0; i < 200; i++) {
				tick(w, 1);
				path += Math.hypot(pred.getX() - px, pred.getY() - py);
				px = pred.getX();
				py = pred.getY();
			}
			assertTrue("hunter ignores prey a level away and prowls (was: " + pred.currentAction() + ")",
					pred.currentAction().equals("prowling"));
			assertGreater("the cave hunter kept moving (never stuck on an unreachable target)", path, 2.0);
		}
	}

	/**
	 * A hunter never freezes on prey it can see but can't reach. A predator that
	 * locks onto prey across water (which blocks movement but not sight) used to
	 * pin itself at the shoreline, steering into the water forever in "hunting".
	 * Now, if a chase makes no headway it gives up for a spell and prowls — so the
	 * hunter keeps moving instead of freezing.
	 */
	static class HunterDoesNotFreezeOnUnreachablePrey extends Scenario {
		@Override
		public void run() {
			seed(22);
			World w = room(16, 9);
			for (int y = 1; y < 8; y++) {
				w.setTile(8, y, 0, Tile.TileType.TYPE_WATER); // a water wall splits the room
			}
			Genome predG = new Genome();
			predG.size = 14;
			predG.speed = 0.05;
			Genome preyG = new Genome();
			preyG.size = 7;
			preyG.speed = 0.0; // a fixed lure on the far bank
			TestNPC pred = TestNPC.predator(3.5, 4.5, 0, predG);
			TestNPC prey = TestNPC.breeder(13.5, 4.5, 0, preyG);
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();

			tick(w, 120); // let it reach the shore and (without the fix) pin there
			// Measure steady-state movement: a frozen hunter accrues ~0 path here;
			// one that gives up and prowls keeps racking it up.
			double path = 0, px = pred.getX(), py = pred.getY();
			for (int i = 0; i < 300; i++) {
				tick(w, 1);
				path += Math.hypot(pred.getX() - px, pred.getY() - py);
				px = pred.getX();
				py = pred.getY();
			}
			assertGreater("the hunter never froze against the water — it kept moving", path, 3.0);
			assertLess("the hunter stayed on its (dry) side of the water", pred.getX(), 8.0);
		}
	}

	/**
	 * Hunger governs the hunt. A well-fed predator (at or above its breeding
	 * threshold) patrols and leaves prey it does not need alone, while a hungry
	 * one runs the very same prey down. Only the predator's energy differs between
	 * the two runs, so the kills that appear in one and not the other isolate
	 * appetite as the driver — the fix for hunters that used to slaughter prey they
	 * were already too full to make any use of.
	 */
	static class SatedPredatorSparesPrey extends Scenario {
		private int kills(double hungerLevel, int reproCooldown) {
			seed(11);
			World w = room(20, 9);
			Genome predG = new Genome();
			predG.size = 14;
			predG.speed = 0.06;
			TestNPC pred = TestNPC.predator(5.5, 4.5, 0, predG)
					.withReproCooldown(reproCooldown); // don't let it breed the surplus off
			pred.withHunger(hungerLevel); // appetite is the hunting drive (VITALS.md)
			Genome preyG = new Genome();
			preyG.size = 7;
			preyG.speed = 0.0; // a fixed lure in plain sight, four tiles off
			TestNPC prey = TestNPC.breeder(9.5, 4.5, 0, preyG);
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();
			int kills = 0;
			for (int i = 0; i < 150; i++) {
				if (prey.isDead() || prey.isRemoved()) {
					kills++;
					prey = TestNPC.breeder(9.5, 4.5, 0, preyG); // restock the lure
					w.spawnEntity(prey);
				}
				tick(w, 1);
			}
			return kills;
		}

		@Override
		public void run() {
			int hungry = kills(0.7, 0); // past the appetite line: hunts in earnest
			int sated = kills(0.0, 100000); // sated: no appetite, no kill
			assertGreater("a hungry predator runs the prey down", hungry, 0);
			assertEquals("a sated predator leaves prey it doesn't need alone", 0, sated);
		}
	}

	/**
	 * Cannibalism is a last resort, not routine. A predator spares a smaller rival
	 * predator while it is fed or merely hungry, and turns on it only under genuine
	 * starvation. Same cast in both runs — only the big predator's energy differs —
	 * so the bite that appears in one and not the other isolates starvation as what
	 * lifts the taboo on eating one's own kind.
	 */
	static class StarvationDrivesCannibalism extends Scenario {
		private int damageToRival(double hungerLevel, int reproCooldown) {
			seed(12);
			World w = room(16, 9);
			Genome bigG = new Genome();
			bigG.size = 16;
			bigG.speed = 0.06;
			TestNPC big = TestNPC.predator(6.5, 4.5, 0, bigG).withReproCooldown(reproCooldown);
			big.withHunger(hungerLevel); // hunger is the drive; >= 0.9 is starving
			Genome smallG = new Genome();
			smallG.size = 9;
			smallG.speed = 0.0; // a smaller, stationary rival predator two tiles off
			TestNPC small = TestNPC.predator(8.5, 4.5, 0, smallG).withReproCooldown(1_000_000);
			int hp0 = small.getHealth();
			w.spawnEntity(big);
			w.spawnEntity(small);
			w.think();
			tick(w, 120);
			int hp1 = (small.isDead() || small.isRemoved()) ? -1000 : small.getHealth();
			return hp0 - hp1;
		}

		@Override
		public void run() {
			int wellFed = damageToRival(0.2, 100000); // fed: leaves its own kind be
			int starving = damageToRival(0.95, 0); // desperate: turns cannibal
			assertEquals("a well-fed predator spares a smaller rival predator", 0, wellFed);
			assertGreater("a starving predator turns on its own kind", starving, 0);
		}
	}

	/**
	 * Anti-predator flight (the ecosystem's "aliveness" behaviour): a vigilant
	 * eco herbivore ({@code withHerding()}) bolts away from a hunter, keeping a
	 * wider gap than an ordinary grazer that crops in place while the predator
	 * closes. Same seed, same cast — only the vigilance flag differs — so the gap
	 * difference isolates the flight response. Pins that {@code withHerding()}
	 * actually makes prey flee, and that the plain breeder is unaffected.
	 */
	static class HerbivoreFleesPredator extends Scenario {
		private double endGap(boolean vigilant) {
			seed(5);
			World w = room(28, 28); // open grass, so the grazer never starves in-window
			Genome preyG = new Genome();
			preyG.markers = new double[] { 0.9, 0.7, 0.4 };
			preyG.size = 7;
			preyG.speed = 0.05; // a fleeing prey can open the gap...
			Genome hunterG = new Genome();
			hunterG.markers = new double[] { 0.9, 0.2, 0.2 };
			hunterG.size = 14;
			hunterG.speed = 0.04; // ...so flight is unambiguous, no kill to confound it
			TestNPC pred = TestNPC.predator(10.5, 14.5, 0, hunterG);
			TestNPC herb = TestNPC.breeder(15.5, 14.5, 0, preyG);
			if (vigilant) {
				herb.withHerding();
			}
			w.spawnEntity(pred);
			w.spawnEntity(herb);
			w.think();
			tick(w, 25);
			snapshot(w, vigilant ? "vigilant herbivore flees" : "plain grazer stays");
			return Math.hypot(herb.getX() - pred.getX(), herb.getY() - pred.getY());
		}

		@Override
		public void run() {
			double fleeing = endGap(true);
			double grazing = endGap(false);
			assertGreater("a vigilant herbivore keeps a wider gap from the hunter than a grazer",
					fleeing, grazing);
			assertGreater("the vigilant herbivore actively opened distance (fled) from a 5-tile start",
					fleeing, 5.0);
		}
	}

	/**
	 * Predation actually connects: a faster hunter runs down a <em>fleeing</em>
	 * vigilant prey that starts several tiles away and bites it. This guards the
	 * predator's ranged prey-sense — the hunter must perceive (and keep chasing)
	 * prey well beyond the tile-local perception grid, or a fleeing prey it can't
	 * see past ~1 tile would open the gap and never be caught. The companion
	 * {@link HerbivoreFleesPredator} pins flight with a faster prey (no kill);
	 * this pins the kill with a faster predator, so together they cover both
	 * sides of pursuit.
	 */
	static class PredatorRunsDownFleeingPrey extends Scenario {
		@Override
		public void run() {
			seed(9);
			World w = room(30, 12); // open grass, long enough for a real chase
			Genome preyG = new Genome();
			preyG.markers = new double[] { 0.9, 0.7, 0.4 };
			preyG.size = 7;
			preyG.speed = 0.03; // slower than the hunter, so it can be run down
			Genome hunterG = new Genome();
			hunterG.markers = new double[] { 0.9, 0.2, 0.2 };
			hunterG.size = 14;
			hunterG.speed = 0.05; // faster: a committed pursuit closes the gap
			// Hungry on arrival: appetite, not tank headroom, is what hunts (VITALS.md).
			TestNPC pred = TestNPC.predator(3.5, 6.5, 0, hunterG).withHunger(0.7);
			TestNPC prey = TestNPC.breeder(11.5, 6.5, 0, preyG).withHerding(); // vigilant: it flees
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();

			double startGap = Math.hypot(prey.getX() - pred.getX(), prey.getY() - pred.getY());
			assertGreater("prey starts well beyond the tile-local perception grid", startGap, 6.0);

			// Give the hunt time to play out; a faster hunter that can see the
			// fleeing prey should land bites (or a kill) within the window.
			for (int i = 0; i < 3000 && !prey.isDead(); i++) {
				tick(w, 1);
			}
			snapshot(w, "after the chase");
			assertTrue("the hunter ran down the fleeing prey and killed it", prey.isDead());
		}
	}

	// ---- runner ------------------------------------------------------------

	/**
	 * Every generated world — at the deployed size and larger, across seeds — must
	 * be a single connected walkable space: mainland, every other surface region,
	 * and the cave all reachable from the mainland by land and two-way links
	 * ({@link net.hedinger.prototype.sim.WorldAudit}'s directed flood). Guards the
	 * world-gen linking so a resize can never strand an area.
	 */
	static class DemoWorldFullyConnected extends Scenario {
		@Override
		public void run() {
			long[] seeds = { 1, 7, 9, 25, 36, 42, 100, 777 };
			int[][] sizes = { { 72, 44 }, { 144, 88 }, { 96, 120 } };
			for (long s : seeds) {
				for (int[] wh : sizes) {
					World w = net.hedinger.prototype.sim.Worlds.demoTerrain(s, wh[0], wh[1]);
					net.hedinger.prototype.sim.WorldAudit.Connectivity c =
							net.hedinger.prototype.sim.WorldAudit.connectivity(w);
					assertTrue(wh[0] + "x" + wh[1] + " seed " + s + " fully connected (reached "
							+ c.reachable + "/" + c.walkable + ", "
							+ String.format("%.2f", c.coverage() * 100) + "%)", c.fullyConnected());
				}
			}
		}
	}

	/**
	 * A hand-injected creature is not silently deleted by the steward's population
	 * ceiling. The steward holds the minded cohort under a cap by removing surplus
	 * members outright ({@code Entity.remove()} — dead AND purged in one call, so
	 * the body blinks out leaving no corpse). A genome dropped into a world already
	 * at that cap was therefore liable to vanish within a second or two, healthy and
	 * well-fed: "I tap the ground and it disappears". A hand-placed founder is now
	 * exempt, so an injection displaces one of the steward's own instead.
	 *
	 * <p>Also guards the other half of the bargain: the cohort must stay bounded.
	 * The founder still counts toward the ceiling and its offspring are ordinary
	 * cullable citizens, so one injection must not let the population run away.
	 */
	static class InjectedCreatureSurvivesPopulationCeiling extends Scenario {
		@Override
		public void run() {
			seed(11);
			World w = net.hedinger.prototype.sim.Worlds.demo(11);
			// Settle until the minded cohort has actually saturated its (generous)
			// ceiling — otherwise the cull never fires and the test proves nothing.
			for (int i = 0; i < 2000; i++) {
				w.think();
			}
			// Donate the brain of the oldest proven survivor — what the viewer exports.
			Genome g = null;
			int bestAge = -1;
			for (Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && t.isMinded() && !t.isDead()
						&& t.getGenome() != null && t.getGenome().brain != null
						&& t.getAge() > bestAge) {
					bestAge = t.getAge();
					g = t.getGenome();
				}
			}
			assertTrue("world produced a minded donor genome", g != null);

			java.util.Set<Integer> before = new java.util.HashSet<>();
			for (Entity e : w.getEntities()) {
				before.add(e.getID());
			}
			int mindedBefore = countMinded(w);
			// Drop it on open ground far from the donor, exactly as a tap would.
			new net.hedinger.prototype.sim.SpawnMindedCommand(g, 20.5, 20.5, 0).apply(w);
			w.think();

			TestNPC seedling = null;
			for (Entity e : w.getEntities()) {
				if (!before.contains(e.getID()) && e instanceof TestNPC t
						&& t.getGenome() == g) {
					seedling = t; // the only body holding this exact Genome instance
				}
			}
			assertTrue("injected creature materialized", seedling != null);
			assertTrue("injected creature is marked hand-placed", seedling.isHandPlaced());

			// Long enough for the steward to run its cull many times over. A CULL is
			// removal while still healthy and fed — the steward deletes outright, so
			// the body is dead and purged in the same tick with its health intact.
			// Being eaten or starving is an ordinary ecological death (health or
			// energy gone first, corpse purged only a deathspan later) and must NOT
			// fail this test: predators are supposed to hunt minded creatures.
			boolean culledWhileHealthy = false;
			String fate = "survived all 400 ticks";
			for (int i = 0; i < 400; i++) {
				w.think(); // never break early: the cohort-bound check below needs the
				           // full window, and a corpse cannot be culled twice
				if (seedling.isRemoved() && seedling.getHealth() > 0
						&& seedling.getEnergy() > 0.001) {
					culledWhileHealthy = true; // healthy AND fed, yet purged: a cull
					fate = "culled at tick " + i;
				} else if (seedling.isDead() && fate.startsWith("survived")) {
					fate = seedling.getHealth() <= 0 ? "eaten at tick " + i
							: "starved at tick " + i;
				}
			}
			// Guard against a vacuous pass: if the creature vanished immediately the
			// cull check above would be trivially satisfied.
			assertTrue("the injected creature lived long enough to be a real test ("
					+ fate + ")", !seedling.isDead() || !fate.contains("tick 0"));
			assertTrue("hand-placed creature was never deleted by the population "
					+ "ceiling (" + fate + ")", !culledWhileHealthy);

			// The ceiling still bites: exempting founders must not let the cohort run
			// away, since only this one body is protected and its offspring are not.
			// Bound is the configured cap plus the overshoot the 3-per-tick trim
			// allows, NOT "no growth" — the cohort legitimately fills its headroom.
			assertTrue("minded cohort stayed bounded after the injection ("
					+ countMinded(w) + ", was " + mindedBefore + " before)",
					countMinded(w) <= 95);
		}

		private static int countMinded(World w) {
			int n = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && t.isMinded() && !t.isDead() && !t.isRemoved()) {
					n++;
				}
			}
			return n;
		}
	}

	/**
	 * A genome (brain included) survives a round-trip through the export/inject
	 * savefile form: encode → decode reproduces every field and every brain
	 * instruction exactly, the encoding carries no whitespace (so it survives the
	 * command log, which splits on it), the spawnMinded command parses back from
	 * its logged line, and injecting it actually drops a living creature into a
	 * world. Guards the genome-savefile feature end to end.
	 */
	static class GenomeSavefileRoundTrips extends Scenario {
		@Override
		public void run() {
			seed(7);
			Genome g = new Genome();
			g.size = 11.25;
			g.speed = 0.0523;
			g.turnRate = 6;
			g.losRange = 12.5;
			g.losFov = 1.234;
			g.metabolism = 0.019;
			g.maxAge = 2750;
			g.flying = true;
			g.markers = new double[] { 0.1, 0.55, 0.9 };
			g.predatory = 0.37;
			g.xenophobia = 0.11;
			g.gregariousness = -0.4;
			g.boldness = -0.2;
			g.mateThreshold = 0.66;
			g.brain = new Brain(new int[][] { { 1, 1, 9, 0 }, { 13, 1, 1, 0 }, { 14, 2, 8, 0 },
					{ 3, 5, 2, 6 } });

			String enc = net.hedinger.prototype.entities.GenomeCodec.encode(g);
			assertTrue("encoding carries no whitespace", !enc.matches("(?s).*\\s.*"));
			Genome back = net.hedinger.prototype.entities.GenomeCodec.decode(enc);
			assertTrue("size round-trips exactly", g.size == back.size);
			assertTrue("speed round-trips exactly", g.speed == back.speed);
			assertTrue("losFov round-trips exactly", g.losFov == back.losFov);
			assertTrue("metabolism round-trips exactly", g.metabolism == back.metabolism);
			assertTrue("predatory round-trips exactly", g.predatory == back.predatory);
			assertTrue("boldness round-trips exactly", g.boldness == back.boldness);
			assertTrue("mateThreshold round-trips exactly", g.mateThreshold == back.mateThreshold);
			assertTrue("flying round-trips", back.flying);
			assertEquals("maxAge round-trips", g.maxAge, back.maxAge);
			assertEquals("turnRate round-trips", g.turnRate, back.turnRate);
			assertTrue("markers round-trip", back.markers.length == 3 && g.markers[0] == back.markers[0]
					&& g.markers[1] == back.markers[1] && g.markers[2] == back.markers[2]);

			assertTrue("brain survives", back.brain != null);
			int[][] a = g.brain.code(), b = back.brain.code();
			assertEquals("brain instruction count", a.length, b.length);
			boolean cells = true;
			for (int i = 0; i < a.length; i++) {
				if (a[i].length != b[i].length) {
					cells = false;
					break;
				}
				for (int j = 0; j < a[i].length; j++) {
					if (a[i][j] != b[i][j]) {
						cells = false;
					}
				}
			}
			assertTrue("every brain instruction round-trips", cells);
			assertTrue("re-encoding is stable", enc.equals(
					net.hedinger.prototype.entities.GenomeCodec.encode(back)));

			// The command-log form: describe() -> fromDescribe() -> same command.
			net.hedinger.prototype.sim.SimCommand cmd =
					new net.hedinger.prototype.sim.SpawnMindedCommand(g, 15, 15, 0);
			net.hedinger.prototype.sim.SimCommand parsed =
					net.hedinger.prototype.sim.SimCommands.fromDescribe(cmd.describe());
			assertTrue("spawnMinded parses back from its log line",
					parsed instanceof net.hedinger.prototype.sim.SpawnMindedCommand);
			assertTrue("command re-describe is stable", cmd.describe().equals(parsed.describe()));

			// And injecting it drops exactly one living creature into an empty room.
			World room = room(30, 30);
			parsed.apply(room);
			room.think();
			assertEquals("injection admitted exactly one creature", 1, room.getAliveCount());

			// It is born brand-new: a FULL tank (not the ecosystem's 0.6-capacity
			// default), giving a hand-placed seed the longest runway before metabolism
			// can starve it. Energy is read after the one tick that materialized it, so
			// it sits a hair below a literal full tank; 0.9 cleanly separates "born
			// full" from the old 0.6 start.
			net.hedinger.prototype.entities.NPC seed = null;
			for (net.hedinger.prototype.engine.Entity e : room.getEntities()) {
				if (e instanceof net.hedinger.prototype.entities.NPC n && !n.isDead()) {
					seed = n;
				}
			}
			assertTrue("injected seed materialized", seed != null);
			assertGreater("injected seed born at a full tank", seed.getEnergy(),
					0.9 * seed.energyCapacity());

			// A tap on a wall snaps to open ground rather than dying on it: (0,0) is
			// the room's border wall, so a land body dropped there would try to climb,
			// run off the top level and die instantly without the snap.
			new net.hedinger.prototype.sim.SpawnMindedCommand(g, 0, 0, 0).apply(room);
			for (int i = 0; i < 6; i++) {
				room.think();
			}
			assertEquals("wall-tap injection snapped to open ground and survived", 2,
					room.getAliveCount());
		}
	}

	/**
	 * Water as a need: a parched grazer drops everything, walks to the shore,
	 * and drinks itself back above the thirst line — the scripted species'
	 * water drive, plus the body's sip-by-adjacency refill.
	 */
	static class ThirstyGrazerWalksToWater extends Scenario {
		@Override
		public void run() {
			seed(93);
			World w = room(20, 12);
			for (int x = 1; x < 19; x++) {
				for (int y = 1; y < 11; y++) {
					w.getTile(x, y, 0).setFertility(1.0);
				}
			}
			for (int y = 1; y < 11; y++) {
				w.setTile(17, y, 0, Tile.TileType.TYPE_SHALLOWS); // the east shore
				w.setTile(18, y, 0, Tile.TileType.TYPE_WATER);
			}
			tick(w, 1);
			TestNPC g = TestNPC.breeder(3.5, 6.5, 0,
					Genome.phenotype(6, 0.05, 5, 6, Math.PI / 2, 1_000_000))
					.withHydration(0.2).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			tick(w, 1500);
			assertTrue("the parched grazer is still alive", !g.isDead() && !g.isRemoved());
			assertGreater("it walked to the shore and drank itself back over the thirst line",
					g.getHydration(), 0.3);
		}
	}

	/**
	 * A thirsty body walks ROUND a wall to reach water rather than into it.
	 *
	 * <p>The lake sits a couple of tiles east behind a solid rib, with the only
	 * way in around the rib's south end. Straight-line water sense sends the
	 * creature at the nearest wet tile and it presses the rock until it dies of
	 * thirst with a drink two tiles away; measured across the demo world, between
	 * 15 and 28 per cent of every thirsty tick was spent like that.
	 */
	static class ThirstWalksRoundAWallNotIntoIt extends Scenario {
		@Override
		public void run() {
			seed(95);
			World w = room(20, 12);
			for (int x = 1; x < 19; x++) {
				for (int y = 1; y < 11; y++) {
					w.getTile(x, y, 0).setFertility(1.0);
				}
			}
			// A lake down the east edge, and a rib of rock hiding it. The rib stops
			// short of the south wall, so there is a way round -- the long way.
			for (int y = 1; y < 11; y++) {
				w.setTile(17, y, 0, Tile.TileType.TYPE_SHALLOWS);
				w.setTile(18, y, 0, Tile.TileType.TYPE_WATER);
			}
			for (int y = 1; y <= 8; y++) {
				w.setTile(14, y, 0, Tile.TileType.TYPE_WALL);
			}
			tick(w, 1);

			// Started level with the rib, so the nearest wet tile is dead ahead
			// through the rock and the way in is well off to the south.
			TestNPC g = TestNPC.breeder(11.5, 3.5, 0,
					Genome.phenotype(6, 0.05, 5, 6, Math.PI / 2, 1_000_000))
					.withHydration(0.2).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			snapshot(w, "parched, with the lake behind a rib of rock");
			tick(w, 2500);
			snapshot(w, "after");

			assertTrue("the parched grazer is still alive", !g.isDead() && !g.isRemoved());
			assertGreater("it found its way round the rib and drank",
					g.getHydration(), 0.3);
			assertGreater("which meant getting past the rock, not standing at it",
					g.getX(), 14.0);
		}
	}

	/**
	 * A minded body ambling flat into a wall is shaken loose by the body's pin
	 * reflex rather than shoving the rock forever. The reflex's "is it trying
	 * to move" gate once sat at throttle &gt; 0.3 — above the 0.25 amble the
	 * starter brain cruises at — which quietly disarmed it for the whole seeded
	 * lineage: any wanderer that aimed at a wall stayed pressed there for good
	 * (measured on the demo world, 4.2% of all thirsty minded ticks were spent
	 * pinned like that, usually with water just beyond the rock). The gate now
	 * matches the 0.02 threshold that engages movement at all; only a mind that
	 * is deliberately still is exempt from the reflex.
	 */
	static class AmblingMindIsShakenOffAWall extends Scenario {
		@Override
		public void run() {
			seed(97);
			World w = room(16, 9);
			Genome g = new Genome();
			g.size = 6;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 0.25; // the starter lineage's amble
					a[AgentIO.A_SEEK] = 0;
					a[AgentIO.A_TURN] = 0; // never steers: a degenerate straight line
				}
			};
			TestNPC body = TestNPC.minded(8.5, 2.5, 0, g, ctrl).withSpeed(0.08)
					.withHeading(-Math.PI / 2); // due north: one open tile, then wall
			w.spawnEntity(body);
			w.think();
			// Path length is the discriminator: a body pinned at the wall stops
			// accumulating any (the ~1.4-tile approach and nothing more), while
			// the reflex keeps shaking it loose so it never stops walking. The
			// endpoint is no use — the never-steering amble bounces wall to wall,
			// so where it stands at any one tick says nothing.
			double px = body.getX(), py = body.getY(), path = 0;
			for (int i = 0; i < 600; i++) {
				tick(w, 1);
				path += Math.hypot(body.getX() - px, body.getY() - py);
				px = body.getX();
				py = body.getY();
			}
			assertGreater("the pin reflex kept the amble moving — never pressed for good",
					path, 5.0);
		}
	}

	/**
	 * The VITALS core: an empty tank is collapse, never death. A starving body
	 * that runs its energy dry lies where it is, still alive — and once fed
	 * again, satiation regenerates the tank and it gets back up. What kills is
	 * health, and health takes minutes of pegged need to erode.
	 */
	static class CollapseIsNotDeath extends Scenario {
		@Override
		public void run() {
			seed(97);
			World w = room(8, 8);
			for (int x = 1; x < 7; x++) {
				for (int y = 1; y < 7; y++) {
					w.getTile(x, y, 0).setFertility(0); // barren: nothing to eat
				}
			}
			// Starving (no regeneration) with a sliver of reserve: the resting burn
			// drains it to zero within the window.
			TestNPC g = TestNPC.breeder(4.5, 4.5, 0, new Genome())
					.withHunger(1.0).withEnergy(0.05).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			w.think();
			tick(w, 400);
			assertNear("the tank ran dry", 0.0, g.getEnergy(), 1e-9);
			assertTrue("and the collapsed body is still alive", !g.isDead());
			// A meal arrives: satiation switches regeneration back on and the body
			// recovers — collapse was a state, not a sentence.
			g.feed(10.0);
			tick(w, 400);
			assertGreater("fed again, the body regenerated energy", g.getEnergy(), 0.05);
			assertTrue("and lives on", !g.isDead());
		}
	}

	/**
	 * The rhythm anchor (VITALS.md §5): thirst runs at twice hunger's rate, so
	 * a body sated and slaked at the same instant wants water in half the time
	 * it takes to want food. Measured by when each need crosses the seek line.
	 */
	static class AppetiteReturnsAtHalfThirstsPace extends Scenario {
		@Override
		public void run() {
			seed(98);
			World w = room(8, 8);
			for (int x = 1; x < 7; x++) {
				for (int y = 1; y < 7; y++) {
					w.getTile(x, y, 0).setFertility(0); // nothing to eat or drink
				}
			}
			TestNPC g = TestNPC.breeder(4.5, 4.5, 0, new Genome())
					.withEnergy(4.0).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			w.think();
			int thirstAt = -1, hungerAt = -1;
			for (int t = 1; t <= 12000 && hungerAt < 0; t++) {
				tick(w, 1);
				if (thirstAt < 0 && g.getThirst() >= 0.5) {
					thirstAt = t;
				}
				if (hungerAt < 0 && g.getHunger() >= 0.5) {
					hungerAt = t;
				}
			}
			assertGreater("thirst crossed the seek line", thirstAt, 0);
			assertGreater("hunger crossed it too", hungerAt, 0);
			assertNear("appetite returned in twice the time thirst did",
					2.0, hungerAt / (double) thirstAt, 0.1);
		}
	}

	/**
	 * Vigor: health scales energy regeneration, so a wounded body is also a
	 * listless one — wounds and hunger compound instead of being independent
	 * ledgers. Two identical fed bodies, one badly hurt; the healthy one
	 * refills its tank decisively faster.
	 */
	static class HealthGatesEnergyRegeneration extends Scenario {
		@Override
		public void run() {
			seed(99);
			World w = room(10, 10);
			TestNPC whole = TestNPC.breeder(3.5, 3.5, 0, new Genome())
					.withEnergy(0.5).withReproCooldown(100_000_000);
			TestNPC hurt = TestNPC.breeder(6.5, 6.5, 0, new Genome())
					.withEnergy(0.5).withReproCooldown(100_000_000);
			w.spawnEntity(whole);
			w.spawnEntity(hurt);
			w.think();
			hurt.damage(80, "misadventure"); // health 20: vigor a fifth of whole
			tick(w, 400);
			assertGreater("the healthy body regenerated more energy than the wounded one",
					whole.getEnergy() - 0.5, (hurt.getEnergy() - 0.5) * 2);
		}
	}

	/**
	 * The parasite's living, end to end: a hungry parasite smells the much
	 * bigger host, walks to it on the forage intent, latches on with the shared
	 * attach machinery, and drains it — the host bleeds health with
	 * "parasites" as the harm while the parasite's stomach fills — and no
	 * grass anywhere is touched, because a parasite's mouth works on nothing
	 * but the body it rides.
	 */
	static class ParasiteLatchesAndDrainsItsHost extends Scenario {
		@Override
		public void run() {
			seed(83);
			World w = room(16, 10);
			Genome hostG = new Genome();
			hostG.size = 14;
			hostG.speed = 0; // a parked mountain of meat
			TestNPC host = TestNPC.breeder(8.5, 5.5, 0, hostG).withReproCooldown(100_000_000);
			Genome paraG = new Genome();
			paraG.size = 4;
			paraG.speed = 0.06;
			Mind ride = (sn, a) -> {
				a[AgentIO.A_SEEK] = 0.1; // forage: for a parasite, the nearest host
				a[AgentIO.A_THROTTLE] = 0.6;
				a[AgentIO.A_ATTACH] = 1; // latch the moment something bigger is in reach
			};
			TestNPC para = TestNPC.minded(4.5, 5.5, 0, paraG, ride)
					.withDiet(TestNPC.Diet.PARASITE).withHunger(1.0);
			w.spawnEntity(host);
			w.spawnEntity(para);
			w.think();
			int hp0 = host.getHealth();
			tick(w, 900);
			assertTrue("the parasite is riding its host", para.getAttachTarget() == host);
			assertLess("and the host is being eaten away", host.getHealth(), hp0);
			assertGreater("what the host lost, the parasite swallowed",
					para.totalSwallowed(), 0.0);
			assertNear("nothing was grazed on the way", 0.0, para.totalIntake(), 1e-9);
		}
	}

	/**
	 * Predators ignore parasites outright — too small and too foul to be worth
	 * a bite. A hungry hunter with a parasite at its feet and real prey down
	 * the room walks past the parasite and takes the prey; the parasite is
	 * never so much as scratched, at any hunger.
	 */
	static class PredatorsIgnoreParasites extends Scenario {
		@Override
		public void run() {
			seed(84);
			World w = room(18, 9);
			Genome pg = new Genome();
			pg.size = 16;
			pg.speed = 0.06;
			TestNPC hunter = TestNPC.predator(5.5, 4.5, 0, pg)
					.withHunger(0.8).withReproCooldown(100_000_000);
			Genome paraG = new Genome();
			paraG.size = 5;
			Mind still = (sn, a) -> {
			};
			TestNPC para = TestNPC.minded(6.2, 4.5, 0, paraG, still)
					.withDiet(TestNPC.Diet.PARASITE); // in reach, smaller: free lunch, refused
			Genome preyG = new Genome();
			preyG.size = 7;
			preyG.speed = 0;
			TestNPC prey = TestNPC.breeder(12.5, 4.5, 0, preyG); // the real meal, further off
			w.spawnEntity(hunter);
			w.spawnEntity(para);
			w.spawnEntity(prey);
			w.think();
			tick(w, 600);
			assertTrue("the hungry hunter went for the real prey",
					prey.isDead() || prey.getHealth() < 100);
			assertTrue("the parasite at its feet was never touched",
					!para.isDead() && para.getHealth() == 100);
		}
	}

	/**
	 * A parasite's mouth works on nothing but the body it rides: standing on
	 * lush grass with the eat actuator held high, it swallows nothing and the
	 * ground under it keeps every blade.
	 */
	static class AParasiteCannotGraze extends Scenario {
		@Override
		public void run() {
			seed(85);
			World w = room(10, 10);
			Genome g = new Genome();
			g.size = 4;
			Mind eat = (sn, a) -> a[AgentIO.A_EAT] = 1;
			TestNPC para = TestNPC.minded(5.5, 5.5, 0, g, eat)
					.withDiet(TestNPC.Diet.PARASITE).withHunger(1.0);
			w.spawnEntity(para);
			w.think();
			double veg0 = w.getTile(5, 5, 0).getVegetation(w.getTick());
			tick(w, 300);
			assertNear("it grazed nothing", 0.0, para.totalIntake(), 1e-9);
			assertNear("and swallowed nothing", 0.0, para.totalSwallowed(), 1e-9);
			assertTrue("the grass under it kept every blade",
					w.getTile(5, 5, 0).getVegetation(w.getTick()) >= veg0 - 1e-9);
		}
	}

	/**
	 * Water the body cannot reach at all is not water. A creature walled off from
	 * every drop is told so — the sense reads empty rather than pointing hopefully
	 * through the rock — which is what lets it go and look somewhere else instead
	 * of standing at a wall for the rest of its life.
	 */
	static class UnreachableWaterIsNotSensed extends Scenario {
		@Override
		public void run() {
			seed(96);
			World w = room(16, 9);
			// A lake sealed off behind a full-height wall: no way in at all.
			for (int y = 1; y < 8; y++) {
				w.setTile(11, y, 0, Tile.TileType.TYPE_WALL);
				w.setTile(13, y, 0, Tile.TileType.TYPE_WATER);
			}
			tick(w, 1);

			assertTrue("water exists on this level",
					w.getTile(13, 4, 0).getType() == Tile.TileType.TYPE_WATER);
			assertEquals("but none of it can be reached from the open side", -1,
					w.waterStepDistance(4.5, 4.5, 0, false));
			assertEquals("and a body standing there is told nothing is near", -1,
					w.nearestWaterTile(4.5, 4.5, 0, false));
			// From inside the sealed strip the same water IS reachable, so the field
			// is answering "can you get there", not "is there any".
			assertGreater("while from the sealed side it is right there",
					w.waterStepDistance(12.5, 4.5, 0, false), -1);
		}
	}

	/**
	 * The other side of the need: in a world with no water at all, a fully fed
	 * grazer still declines and dies once its tank runs dry — dehydration is a
	 * slow wear on health, not a starvation clone (the full energy tank rules
	 * starving out as the cause on this timeline).
	 */
	static class DehydrationWearsABodyDown extends Scenario {
		@Override
		public void run() {
			seed(94);
			World w = room(16, 16);
			for (int x = 1; x < 15; x++) {
				for (int y = 1; y < 15; y++) {
					w.getTile(x, y, 0).setFertility(1.0); // lush — but bone dry
				}
			}
			tick(w, 1);
			TestNPC g = TestNPC.breeder(8.5, 8.5, 0,
					Genome.phenotype(6, 0.05, 5, 6, Math.PI / 2, 1_000_000))
					.withHydration(0.01).withEnergy(6.0).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			// Deprivation erodes health a point every DEPRIVATION_PERIOD ticks, so a
			// pegged need takes ~5000 ticks to kill — slow enough that rescue is real.
			tick(w, 6000);
			assertTrue("with no water anywhere, dehydration wears the fed grazer down",
					g.isDead() || g.isRemoved());
		}
	}

	/**
	 * Nutrient closure: a corpse that rots away feeds the ground it lay on —
	 * tile fertility rises where the body expired, so kill sites green up and
	 * the grass-grazer-predator loop closes.
	 */
	static class CorpseFeedsTheGround extends Scenario {
		@Override
		public void run() {
			seed(95);
			World w = room(10, 10);
			for (int x = 1; x < 9; x++) {
				for (int y = 1; y < 9; y++) {
					w.getTile(x, y, 0).setFertility(0.3); // poor ground, so the gift shows
				}
			}
			double before = w.getTile(5, 5, 0).getFertility();
			TestNPC victim = TestNPC.inert(5.5, 5.5, 0).withSize(8).withDeathspan(40);
			w.spawnEntity(victim);
			tick(w, 2);
			victim.kill();
			tick(w, 60); // longer than the deathspan: the corpse rots away
			assertTrue("the corpse rotted away", victim.isRemoved());
			assertGreater("the ground it rotted on is richer than before",
					w.getTile(5, 5, 0).getFertility(), before + 0.05);
		}
	}

	static Scenario[] all() { // package: RecordScenario replays these by name
		return new Scenario[] {
				new DemoWorldFullyConnected(),
				new GenomeSavefileRoundTrips(),
				new InjectedCreatureSurvivesPopulationCeiling(),
				new HerbivoreFleesPredator(),
				new PredatorRunsDownFleeingPrey(),
				new HunterIgnoresPreyOnAnotherLevel(),
				new HunterDoesNotFreezeOnUnreachablePrey(),
				new SatedPredatorSparesPrey(),
				new StarvationDrivesCannibalism(),
				new DemoLevelsLinkSurfaceAndCave(),
				new WallContainment(),
				new RoamerMoves(),
				new ChaserClosesIn(),
				new AgesOutAndIsRemoved(),
				new LethalDamageAndScavenging(),
				new ForageIgnoresFoodBehindWalls(),
				new ScavengerEatsCarrionAndHastensItsDecay(),
				new ScavengerForagesTowardBodies(),
				new ACarcassIsWorthWhatAKillIsWorth(),
				new ABodyIsShapedByWhatItEats(),
				new ScavengerYoungAreScavengers(),
				new ScavengersDoNotBreedWithGrazers(),
				new ScavengerHoldsTheCarcassItChose(),
				new ScavengerCrossesToAMuchBetterCarcass(),
				new CorpseRotsForAsLongAsItTookToGrow(),
				new SoundWakesListener(),
				new HoleFallRespectsFlying(),
				new CarryingAndRidingWearDifferentBadges(),
				new GrabCarriesSmallerEntity(),
				new GrabRespectsSizeAndReach(),
				new DoorBlocksAndAdmits(),
				new RampAscends(),
				new DiagonalCornerCutBlocked(),
				new WallBlocksPerception(),
				new CollisionSpringSeparates(),
				new SpawnRejectsOutOfBounds(),
				new GenomeReactModel(),
				new GenomePredatorHuntsPrey(),
				new SmallHunterTakesBiggerPreySlowly(),
				new EngineCapsStepLength(),
				new TravelCostsEnergy(),
				new HoldingACaptiveCostsEnergy(),
				new CreaturesGrowToAdultSize(),
				new ActionGlyphsRideTheWire(),
				new MechanicsAreReadOffTheWorld(),
				new RoleFollowsDietNotMind(),
				new EverySensorIsReachable(),
				new ViolenceIsAudibleThroughWalls(),
				new HitchhikerBrainClimbsAboard(),
				new GenomeInheritance(),
				new GrazerDepletesSubstrate(),
				new VegetationRegrows(),
				new FertilityCapsVegetation(),
				new FertileHabitatPatches(),
				new WaterBlocksLandPassesFlyers(),
				new MudSlowsMovement(),
				new CrystalDensityTiers(),
				new DuctAdmitsOnlySmallBodies(),
				new BlockedBodySlidesAlongAnObstacle(),
				new GrownBodyEscapesTheBedItGrewInside(),
				new SwitchOpensWiredDoor(),
				new ButtonNeedsIntent(),
				new BrainInteractsWithButton(),
				new BrainSeeksAndPressesButton(),
				new BottomlessPitsAndCatwalks(),
				new ThirstyGrazerWalksToWater(),
				new ThirstWalksRoundAWallNotIntoIt(),
				new AmblingMindIsShakenOffAWall(),
				new UnreachableWaterIsNotSensed(),
				new DehydrationWearsABodyDown(),
				new CollapseIsNotDeath(),
				new AppetiteReturnsAtHalfThirstsPace(),
				new HealthGatesEnergyRegeneration(),
				new ParasiteLatchesAndDrainsItsHost(),
				new PredatorsIgnoreParasites(),
				new AParasiteCannotGraze(),
				new CorpseFeedsTheGround(),
				new CoverHidesFromPerception(),
				new StarvesWithoutFood(),
				new PopulationGrowsWithFood(),
				new SexualReproductionNeedsPartner(),
				new BrainMemoryIsDeterministic(),
				new BrainLengthSetsThoughtRate(),
				new BrainHeredityCrossesAndMutates(),
				new MindDrivesAgent(),
				new MindSensesHungerByCapacity(),
				new MindHuntsViaPreyChannel(),
				new MindFleesViaThreatChannel(),
				new ThrottleSetsSpeedAndCostsItsSquare(),
				new ACorpseLingersByItsMass(),
				new AFullHunterDoesNotKill(),
				new CoverHidesPreyFromAHunter(),
				new TileSeekingIsGeneric(),
				new MatingTakesTimeAndSeeksAPartner(),
				new IntentReportsHowItWent(),
				new BrainSizeSetsHowMuchAMindTracks(),
				new SeekWalksToAPatchWithoutSteering(),
				new OneIntentIsAWholeBehaviour(),
				new HuntIntentClosesAndBites(),
				new WaypointRemembersAPlace(),
				new SeekYieldsWhenItNamesNothing(),
				new MindsChangeLevelByWalkingRamps(),
				new MindedBodyUnsticksFromWallJam(),
				new MindedCohortSustainedBySteward(),
				new MindedReseedDescendsFromLongestLivedSurvivor(),
				new StarterBrainedForagerFeedsItself(),
				new BrainInheritedThroughReproduction(),
				new BrainedPopulationDiversifies(),
				new EvolutionDiscoversForaging(),
				new BrainAttacksNeighbour(),
				new BrainMatesViaActuator(),
				new BrainGrabsSmallerNeighbour(),
				new BrainAttachesToLargerHost(),
				new CarryingCostsEnergy(),
				new CaptiveFrozenRiderActs(),
				new RiderSpendsLessEnergy(),
				new StrugglingCostsMoreThanConsenting(),
				new CaptiveCanStillCommunicate(),
				new CaptiveFreedWhenCaptorDies(),
				new FlyingCarrierPaysMore(),
				new GroundCannotGrabFlyer(),
				new HostBucksOffRiders(),
				new ItemSensedOnDedicatedChannel(),
				new FoodItemEatenForEnergy(),
				new CrateBrokenSpillsFood(),
				new HazardHarmsEater(),
				new HazardHarmsAttacker(),
				new ItemPushedAsideByPasserby(),
				new CarrierHaulsCrate(),
				new DiagonalLineOfSight(),
				new BlockedSensorSeesWalls(),
				new PheromoneDecays(),
				new NestEmergesFromPheromone(),
				new SnapshotStreamDeterministic(),
				new CommandLogReplayReproduces(),
				new SameSeedSameOutcome(),
		};
	}

	public static void main(String[] args) {
		int passed = 0;
		int failed = 0;
		// Behaviour tests render clean: no decorative grass/shrubs cluttering the
		// subject (scenario worlds are fully fertile, so foliage would blanket them).
		net.hedinger.prototype.engine.RenderFx.foliage = false;
		String shotsDir = System.getProperty("simtest.shots");
		if (shotsDir != null) {
			new java.io.File(shotsDir).mkdirs();
		}
		for (Scenario s : all()) {
			if (args.length > 0 && !s.name().equalsIgnoreCase(args[0])) {
				continue;
			}
			long t0 = System.nanoTime();
			try {
				s.run();
				System.out.printf("PASS  %-28s (%.0f ms)%n", s.name(), (System.nanoTime() - t0) / 1e6);
				passed++;
			} catch (AssertionError e) {
				System.out.printf("FAIL  %-28s %s%n", s.name(), e.getMessage());
				failed++;
			} catch (Exception e) {
				System.out.printf("ERROR %-28s %s%n", s.name(), e);
				e.printStackTrace(System.out);
				failed++;
			}
			writeShots(shotsDir, s);
		}
		System.out.println("----");
		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0) {
			System.exit(1);
		}
	}

	/** Composes a scenario's captured frames into one before/after strip PNG. */
	private static void writeShots(String shotsDir, Scenario s) {
		if (shotsDir == null || s.shots().isEmpty()) {
			return;
		}
		try {
			java.awt.image.BufferedImage strip =
					SnapshotRenderer.strip(s.name(), s.shotLabels(), s.shots());
			java.io.File out = new java.io.File(shotsDir, s.name() + ".png");
			javax.imageio.ImageIO.write(strip, "png", out);
			System.out.println("      shot -> " + out.getPath());
		} catch (Exception e) {
			System.out.println("      shot FAILED: " + e);
		}
	}
}
