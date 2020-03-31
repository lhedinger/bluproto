package net.hedinger.prototype.entities.npcs;

import java.awt.*;
import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;

public class Human extends NPC
{
	private static final double HUMAN_RANGE = 10; // zombie los range (pixels)
	private static final double HUMAN_FOV = Math.PI * 0.5;
	private static final double HUMAN_SPEED = 0.04; // max speed
	private static final int HUMAN_TURN = 5; // max turn speed
	private static final int HUMAN_SF = 50;

	private static final String[] HUMAN_ENEMIES = { "Entity.NPC.Antlion", "Entity.NPC.Headcrab",
			"Entity.NPC.Houndeye", "Entity.NPC.Zombie" };

	private TreeMap<Double, NPC> enemies = null;
	private TreeMap<Double, NPC> soldiers = null;

	private NPC enemy = null;
	private NPC soldier = null;

	public Human(double x, double y, double z)
	{
		super(x, y, z);
		hostile = 1;
		size = 6;
		health = 100;
		deathspan = 1000;
		SEARCH_FREQ = HUMAN_SF;
		LOS_RANGE = HUMAN_RANGE;
		LOS_FOV = HUMAN_FOV;
	}

	protected void think()
	{
		enemies = getTargets(enemies, HUMAN_ENEMIES, true);

		if (!enemies.isEmpty())
		{
			enemy = enemies.firstEntry().getValue();
			status = NPC.STATUS_THREAT;

			if (Math.random() * 1000 < 1)
			{
				say(panic_phrases[(int) (Math.random() * panic_phrases.length)], 200);
			}
		}
		else
		{
			if (status >= NPC.STATUS_ALERT)
				status = NPC.STATUS_ALERT;
			else
				status = NPC.STATUS_IDLE;
		}

		if (status == NPC.STATUS_IDLE)
		{
			roam(HUMAN_SPEED * 0.5, HUMAN_TURN);
		}
		if (status == NPC.STATUS_ALERT)
		{
			roam(HUMAN_SPEED, HUMAN_TURN);
		}
		if (status == NPC.STATUS_THREAT)
		{
			soldiers = getTargets(enemies, "Entity.NPC.Soldier", true);

			if (!soldiers.isEmpty())
				soldier = soldiers.firstEntry().getValue();

			if (seeTarget(soldier, HUMAN_RANGE, Math.PI, "Entity.NPC.Soldier", true))
			{
				follow(HUMAN_SPEED, HUMAN_TURN, soldier, 1);
			}
			else
			{
				roam(HUMAN_SPEED * 2, HUMAN_TURN);
			}

		}

	}

	public static final String[] panic_phrases = { "They're everywhere!",
			"Game over man, game over!", "I hate my life!", "What the fuck man? What the fuck?",
			"Save us!", "I knew this was gonna happen!" };

	public String NPCType()
	{
		return "Human";
	}

}
