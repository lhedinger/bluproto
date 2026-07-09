package net.hedinger.prototype.entities.npcs;

import java.util.TreeMap;

import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.NPC;

public class DummyChaser extends NPC {
	private static final int HUMAN_SF = 50;

	private TreeMap<Double, NPC> targets = null;

	public DummyChaser(double x, double y, double z) {
		super(x, y, z);
		applyGenome(Genome.phenotype(5, 0.03, 5, 10, Math.PI * 0.5, 3000));
		hostile = 1;
		health = 100;
		deathspan = 1000;
		SEARCH_FREQ = HUMAN_SF;
	}

	@Override
	protected void think() {

		targets = getTargets(targets, "", false);

		NPC target = getClosestNPC(targets);

		lockTarget(target);

		chase(speed, turnRate);
	}

	@Override
	public String getNpcTypeName() {
		return "DummyChaser";
	}

}
