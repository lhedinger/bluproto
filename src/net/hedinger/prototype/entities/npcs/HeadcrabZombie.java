package net.hedinger.prototype.entities.npcs;

import java.awt.*;
import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;


public class HeadcrabZombie extends NPC
{
	private static final Color ZOMBIE_COLOR = new Color(0, 150, 0);
	private Color ZOMBIE_COLOR_INIT = new Color(0, 150, 0);
	private static final double ZOMBIE_RANGE = 4; // zombie los range (pixels)
	private static final double ZOMBIE_FOV = Math.PI;
	private static final double ZOMBIE_SPEED = 0.03; // max speed
	private static final int ZOMBIE_TURN = 10; // max turn speed
	private static final int ZOMBIE_SF = 20;
	private static final int ZOMBIE_MAX_AGE = 5000;
	private static final int ZOMBIE_INFEST_DURATION = 250;

	private static final String[] ZOMBIE_ENEMIES = { "Entity.NPC.Sentry", "Entity.NPC.Soldier",
			"Entity.NPC.Human", "Entity.NPC.Antlion", "Entity.NPC.Houndeye", "Entity.NPC.Elite"};

	private static final String[] ZOMBIE_ALLIES = { "Entity.NPC.Headcrab","Entity.NPC.Zombie"};

	private TreeMap<Double, NPC> enemies = null;
	private TreeMap<Double, NPC> allies = null;
	private NPC enemy = null;
	private NPC ally = null;

	public HeadcrabZombie(double x, double y, double z)
	{
		super(x, y, z);
		col = ZOMBIE_COLOR;
		size = 6;
		health = 200;
		lifespan = ZOMBIE_MAX_AGE;
		deathspan = (int) (ZOMBIE_MAX_AGE*0.5);
		age = (int) (ZOMBIE_MAX_AGE * 0.5);
		SEARCH_FREQ = ZOMBIE_SF;
		LOS_RANGE = ZOMBIE_RANGE;
		LOS_FOV = ZOMBIE_FOV;
	}

	public HeadcrabZombie(double x, double y, double z, double d)
	{
		super(x, y, z, d);
		size = 6;
		health = 200;
		lifespan = ZOMBIE_MAX_AGE;
		deathspan = (int) (ZOMBIE_MAX_AGE*0.5);
		SEARCH_FREQ = ZOMBIE_SF;
		LOS_RANGE = ZOMBIE_RANGE;
		LOS_FOV = ZOMBIE_FOV;
	}

	protected void think()
	{
		if (age < ZOMBIE_MAX_AGE * 0.1)
		{
			move(0);
			return;
		}
		else if (age > ZOMBIE_MAX_AGE)
		{
			kill();
		}

		if (infesting < -1)
			infesting++;
		if (infesting >= 0)
		{
			infest();
			return;
		}
		if (allies != null && infesting == -1)
			if (allies.size() < 2
					&& ZOMBIE_MAX_AGE * 0.5 + Math.random() * ZOMBIE_MAX_AGE * 0.5 < age)
				infesting = 0;

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
			if (seeTarget(ally, 1.0, Math.PI, "Entity.NPC.Headcrab", true))
				chase(0, ZOMBIE_TURN);
			else
			{
				chase(ZOMBIE_SPEED, ZOMBIE_TURN);
				if (seeTarget(enemy, 0.1, Math.PI * 0.1, ZOMBIE_ENEMIES, true))
					enemy.damage((int) variation(75, 25));
			}
		}
		else if (seeTarget(ally, 3.0, Math.PI, ZOMBIE_ALLIES, true) && status <= NPC.STATUS_THREAT)
		{
			follow(ZOMBIE_SPEED, ZOMBIE_TURN, ally, 1);

		}
		else
			roam(ZOMBIE_SPEED * 0.5, ZOMBIE_TURN);
	}

	private int infesting = -1;

	private void infest()
	{
		infesting++;

		double temp = infesting;
		temp = temp / ZOMBIE_INFEST_DURATION;

		size = round(6 + 2 * Math.sqrt(ratio(infesting,ZOMBIE_INFEST_DURATION)));

		if (infesting > ZOMBIE_INFEST_DURATION)
		{
			remove();

			int n = (int) (Math.random() * 4) + 4;

			for (int i = 0; i < n; i++)
				getWorld().spawnEntity(new Headcrab(variation(X, 0.05), variation(Y, 0.05), Z, D));
		}

	}

	public void kill()
	{
		if (Math.random() * 4 < 1)
			getWorld().spawnEntity(new Headcrab(X, Y, Z, D));
		age = -1;
	}

	public boolean isDead()
	{
		return (age < 0);
	}

	private Color colorMorph(Color start, Color end, double ratio)
	{
		int r, g, b;
		float dR, dG, dB;
		int fR, fG, fB;

		r = start.getRed();
		g = start.getGreen();
		b = start.getBlue();

		dR = end.getRed() - r;
		dG = end.getGreen() - g;
		dB = end.getBlue() - b;

		fR = (int) (r + Math.round(dR * ratio));
		fG = (int) (g + Math.round(dG * ratio));
		fB = (int) (b + Math.round(dB * ratio));

		return new Color(fR, fG, fB);
	}
	
	public String getNpcTypeName()
	{
		return "Zombie";
	}

}