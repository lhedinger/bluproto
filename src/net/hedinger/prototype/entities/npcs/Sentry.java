package net.hedinger.prototype.entities.npcs;

import net.hedinger.prototype.entities.*;
import net.hedinger.prototype.entities.weapons.*;

import java.util.TreeMap;

public class Sentry extends NPC
{
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
		applyGenome(Genome.phenotype(4, 0, 10, 3, Math.PI, 3000));
		hostile = 0;
		health = 100;
		drawLOS = true;
		SEARCH_FREQ = SENTRY_SF;
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
			if (!seeTarget(enemy, LOS_RANGE, LOS_FOV))
				enemy = enemies.firstEntry().getValue();
			drawPing = false;
		}
		else
			drawPing = true;

		if (seeTarget(enemy, getType(), false))
		{
			lockTarget(enemy);
			chase(speed, turnRate);
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

	public String getNpcTypeName()
	{
		return "Sentry";
	}
}
