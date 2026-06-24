package net.hedinger.prototype.entities.npcs;

import net.hedinger.prototype.engine.Utils;

import net.hedinger.prototype.entities.*;
import net.hedinger.prototype.entities.weapons.*;

import java.awt.Color;
import java.util.TreeMap;


public class Elite extends NPC
{
	private static final double ELITE_RANGE = 15; // zombie los range (pixels)
	private static final double ELITE_FOV = Math.PI;
	private static final double ELITE_SPEED = 0.05; // max speed
	private static final int ELITE_TURN = 5; // max turn speed
	private static final int ELITE_SF = 20;
	private Weapon weapon;

	private String[] ELITE_IGNORE = { "Entity.NPC.Elite", "Entity.NPC.Sentry", "Entity.NPC.Soldier","Entity.NPC.Spore" };

	private TreeMap<Double, NPC> enemies = null;
	private TreeMap<Double, NPC> squad = null;

	private NPC enemy = null;

	public Elite(double x, double y, double z)
	{
		super(x, y, z);
		col = new Color(0, 0, 250);
		size = 6;
		health = 200;
		SEARCH_FREQ = ELITE_SF;
		LOS_RANGE = ELITE_RANGE;
		LOS_FOV = ELITE_FOV;
		int n = (int) (Utils.random() * 3);
		switch (n)
		{
		case (0):
			weapon = new Shotgun(this);
			break;
		case (1):
			weapon = new Rifle(this);
			break;
		case (2):
			weapon = new Machinegun(this);
			break;
		default:
			weapon = new Rifle(this);
			break;

		}

	}

	protected void think()
	{
		weapon.think();

		enemies = getTargets(enemies, ELITE_IGNORE, false);

		if (squad == null)
			squad = getTargets(0, squad, 2, Math.PI, "Entity.NPC.Elite", true);

		if (!enemies.isEmpty())
		{
			enemy = enemies.firstEntry().getValue();
			status = NPC.STATUS_THREAT;
		}

		if (seeTarget(enemy, ELITE_IGNORE, false))
		{
			lockTarget(enemy);

			if (distance() > 2.0)
			{
				chase(ELITE_SPEED, ELITE_TURN);
			}
			else
				chase(0, ELITE_TURN);

			if (isInLOS(ELITE_RANGE, Math.PI * 0.2))
			{
				if (distance() < 3.0)
				{
					if (weapon.use(distance()))
						move(0);
					if (distance() < 1.0)
						backup(ELITE_SPEED * 0.75);
				}
			}
		}
		else
		{
			roam(ELITE_SPEED * 0.7, ELITE_TURN);
		}
	}

	public String getNpcTypeName()
	{
		return "Elite";
	}
}
