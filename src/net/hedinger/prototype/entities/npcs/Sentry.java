package net.hedinger.prototype.entities.npcs;

import net.hedinger.prototype.entities.*;
import net.hedinger.prototype.entities.weapons.*;

import java.util.TreeMap;

public class Sentry extends NPC
{
	private static final double SENTRY_RANGE = 3; // los range (pixels)
	private static final double SENTRY_FOV = Math.PI; // los range
	// (pixels)
	private static final double SENTRY_SPEED = 0; // immobile
	private static final int SENTRY_TURN = 10; // max turn speed
	private static final int SENTRY_SF = 20;
	private Weapon weapon = null;
	private boolean malfunction = false;

	private String[] SENTRY_IGNORE = { "Entity.NPC.Drone", "Entity.NPC.Sentry",
			"Entity.NPC.Soldier", "Entity.NPC.Human", "Entity.NPC.Spore" };

	private TreeMap<Double, NPC> enemies = null;
	private NPC enemy = null;

	private double turnrate = -0.03;
	private double turncount = 0;

	public Sentry(double x, double y, double z)
	{
		super(x, y, z);
		hostile = 0;
		size = 4;
		health = 100;
		drawLOS = true;
		SEARCH_FREQ = SENTRY_SF;
		LOS_RANGE = SENTRY_RANGE;
		LOS_FOV = SENTRY_FOV;
		weapon = new Gattlingun(this);
	}

	public void think()
	{
		if (weapon != null)
			weapon.think();

		// if (health < 25)
		// malfunction = true;

		// gets all targets within range and los
		// return true if any are found

		if (malfunction)
			enemies = getTargets(enemies, getType(), false);
		else
			enemies = getTargets(enemies, SENTRY_IGNORE, false);

		if (!enemies.isEmpty())
		{
			say("[!]", 200);
			if (!seeTarget(enemy, SENTRY_RANGE, SENTRY_FOV))
				enemy = enemies.firstEntry().getValue();
			drawPing = false;
		}
		else
			drawPing = true;

		if (seeTarget(enemy, getType(), false))
		{
			lockTarget(enemy);
			chase(SENTRY_SPEED, SENTRY_TURN);
			if (isInLOS(-1, Math.PI * 0.1))
			{
				say(">!<", 15);
				if (weapon != null)
					weapon.use(distance());
			}
		}
		else
		{
			say("[?]", 10);
			idle();
		}
	}

	private void idle()
	{
		D += turnrate;

		turncount++;

		if (turncount > 50)
		{
			turncount = 0;
			turnrate = -turnrate;
		}

		tX = X;
		tY = Y;
		tZ = Z;
	}

	public String NPCType()
	{
		return "Sentry";
	}
}
