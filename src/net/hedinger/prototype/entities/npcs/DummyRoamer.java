package net.hedinger.prototype.entities.npcs;

import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;

public class DummyRoamer extends NPC {
	private static final double HUMAN_RANGE = 10; // los range (pixels)
	private static final double HUMAN_FOV = Math.PI * 0.5;
	private static final double HUMAN_SPEED = 0.04; // max speed
	private static final int HUMAN_TURN = 5; // max turn speed
	private static final int HUMAN_SF = 50;

	private TreeMap<Double, NPC> enemies = null;

	public DummyRoamer(double x, double y, double z) {
		super(x, y, z);
		hostile = 1;
		size = 6;
		health = 100;
		deathspan = 1000;
		SEARCH_FREQ = HUMAN_SF;
		LOS_RANGE = HUMAN_RANGE;
		LOS_FOV = HUMAN_FOV;
	}

	@Override
	protected void think() {
		roam(HUMAN_SPEED, HUMAN_TURN);
	}

	@Override
	public String getNpcTypeName() {
		return "DummyRoamer";
	}

}
