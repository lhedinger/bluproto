package net.hedinger.prototype.entities.npcs;

import net.hedinger.prototype.entities.NPC;

import java.awt.*;
import java.util.TreeMap;

public class Zombie extends NPC
{
	private static final Color ZOMBIE_COLOR = new Color(0, 150, 0);
	private static final double ZOMBIE_RANGE = 4; // zombie los range (pixels)
	private static final double ZOMBIE_FOV = Math.PI;
	private static final double ZOMBIE_SPEED = 0.05; // max speed
	private static final int ZOMBIE_TURN = 3; // max turn speed
	private static final int ZOMBIE_SF = 50;
	private static final int ZOMBIE_MAX_AGE = 10000;

	private static final String[] ZOMBIE_ENEMIES = { "Entity.NPC.Sentry", "Entity.NPC.Drone", "Entity.NPC.Soldier",
			"Entity.NPC.Human", "Entity.NPC.Elite" };

	private static final String[] ZOMBIE_ALLIES = { "Entity.NPC.Zombie",
			"Entity.NPC.Human_Infected" };

	private TreeMap<Double, NPC> enemies = null;
	private TreeMap<Double, NPC> allies = null;
	private NPC enemy = null;
	private NPC ally = null;

	public Zombie(double x, double y, double z)
	{
		super(x, y, z);
		col = ZOMBIE_COLOR;
		size = 6;
		health = 200;
		lifespan = ZOMBIE_MAX_AGE;
		deathspan = (int) (ZOMBIE_MAX_AGE * 0.5);
		age = (int) (ZOMBIE_MAX_AGE * 0.5);
		SEARCH_FREQ = ZOMBIE_SF;
		LOS_RANGE = ZOMBIE_RANGE;
		LOS_FOV = ZOMBIE_FOV;
	}

	public Zombie(double x, double y, double z, double d)
	{
		super(x, y, z, d);
		col = ZOMBIE_COLOR;
		size = 6;
		health = 200;
		lifespan = ZOMBIE_MAX_AGE;
		deathspan = (int) (ZOMBIE_MAX_AGE * 0.5);
		age = (int) (ZOMBIE_MAX_AGE * 0.5);
		SEARCH_FREQ = ZOMBIE_SF;
		LOS_RANGE = ZOMBIE_RANGE;
		LOS_FOV = ZOMBIE_FOV;
	}

	protected void think()
	{
		if (age < ZOMBIE_MAX_AGE * 0.1)
		{
			if (age > ZOMBIE_MAX_AGE * 0.07)
			{
				move(0);
			}
			else
			{
				double ratio = 1 - ratio(age, ZOMBIE_MAX_AGE * 0.07);

				roam(ZOMBIE_SPEED * ratio * 2, ZOMBIE_TURN);
			}

			return;
		}
		else if (age > ZOMBIE_MAX_AGE)
		{
			// kill();
		}

		if (allies != null)
			if (allies.size() < 2
					&& ZOMBIE_MAX_AGE * 0.5 + Math.random() * ZOMBIE_MAX_AGE * 0.5 < age)
				move(0);

		enemies = getTargets(enemies, ZOMBIE_ENEMIES, true);

		if (!enemies.isEmpty())
		{
			enemy = enemies.firstEntry().getValue();
			status = NPC.STATUS_THREAT;
		}
		else
		{
			if (status >= NPC.STATUS_ALERT)
				status = NPC.STATUS_ALERT;
			else
				status = NPC.STATUS_IDLE;
		}

		if (status == NPC.STATUS_ALERT)
		{
			allies = getTargets(25, allies, 3.0, Math.PI, ZOMBIE_ALLIES, true);
			ally = getClosestNPC(allies, NPC.STATUS_THREAT);
		}
		if (status == NPC.STATUS_IDLE)
		{
			allies = getTargets(25, allies, 3.0, Math.PI, ZOMBIE_ALLIES, true);
			ally = getClosestNPC(allies, NPC.STATUS_ALERT);
			if (ally != null)
				status = NPC.STATUS_ALERT;
		}
		if (seeTarget(enemy, ZOMBIE_ENEMIES, true))
		{
			lockTarget(enemy);
			chase(ZOMBIE_SPEED, ZOMBIE_TURN);
			if (seeTarget(enemy, 0.1, Math.PI * 0.1, ZOMBIE_ENEMIES, true))
				bite();
			else
				enemy = null;
		}
		else if (seeTarget(ally, 5.0, Math.PI, ZOMBIE_ALLIES, true) && status <= NPC.STATUS_THREAT)
		{
			follow(ZOMBIE_SPEED, ZOMBIE_TURN, ally, 1);
		}
		else
			roam(ZOMBIE_SPEED * 0.5, ZOMBIE_TURN);
	}

	public void bite()
	{
		if (enemy.getType().equalsIgnoreCase("Entity.NPC.Soldier")
				|| enemy.getType().equalsIgnoreCase("Entity.NPC.Human"))
		{
			if (Math.random() * 100 < 1)
			{
				enemy.kill();
				getWorld().spawnEntity(
						new Zombie(enemy.getX(), enemy.getY(), enemy.getZ(), enemy.getDirection()));
			}
			else
				enemy.damage(1);
		}
		else
		{
			enemy.damage(30);
		}

	}

	public boolean isDead()
	{
		return ((age < 0) || (age < ZOMBIE_MAX_AGE * 0.1 && age > ZOMBIE_MAX_AGE * 0.07));
	}

	public String NPCType()
	{
		if (age < ZOMBIE_MAX_AGE * 0.09)
			return "Human_Infected";
		return "Zombie";
	}

}