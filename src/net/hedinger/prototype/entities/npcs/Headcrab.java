package net.hedinger.prototype.entities.npcs;

import java.awt.Color;
import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;

public class Headcrab extends NPC
{
	private static final double HEADCRAB_RANGE = 1.5; // HEADCRAB los range
	private static final double HEADCRAB_FOV = Math.PI;
	private static final double HEADCRAB_SPEED = 0.02; // max speed
	private static final int HEADCRAB_TURN = 25; // max turn speed
	private static final int HEADCRAB_SF = 10;
	private static final int HEADCRAB_MAX_AGE = (int) variation(5000, 1000);
	private static final double HEADCRAB_MAXLEAP = 10;

	private static final String[] HEADCRAB_ENEMIES = { "Entity.NPC.Sentry", "Entity.NPC.Soldier",
			"Entity.NPC.Human", "Entity.NPC.Antlion", "Entity.NPC.Houndeye" };

	private static final String[] HEADCRAB_ALLIES = { "Entity.NPC.Headcrab", "Entity.NPC.Zombie" };

	private TreeMap<Double, NPC> enemies = null;
	private TreeMap<Double, NPC> allies = null;
	private NPC enemy = null;
	private NPC ally = null;

	public Headcrab(double x, double y, double z)
	{
		super(x, y, z);
		col = new Color(0, 150, 0);
		size = 3;
		age = (int) (HEADCRAB_MAX_AGE * 0.25);
		SEARCH_FREQ = HEADCRAB_SF;
		LOS_RANGE = HEADCRAB_RANGE;
		LOS_FOV = HEADCRAB_FOV;
	}

	public Headcrab(double x, double y, double z, double d)
	{
		super(x, y, z, d);
		col = new Color(0, 150, 0);
		size = 1;
		SEARCH_FREQ = HEADCRAB_SF;
		LOS_RANGE = HEADCRAB_RANGE;
		LOS_FOV = HEADCRAB_FOV;
	}

	@Override
	public boolean isDead()
	{
		return (age < (int) (HEADCRAB_MAX_AGE * 0.25));
	}
	
	@Override
	protected void think()
	{
		if (age < (int) (HEADCRAB_MAX_AGE * 0.25))
		{
			double temp = age;
			temp = temp / HEADCRAB_MAX_AGE;

			size = (int) Math.round(3 * Math.sqrt(temp));

			roam(HEADCRAB_SPEED * 0.05, HEADCRAB_TURN);
			return;
		}

		if (size != 3) {
			size = 3;
		}

		if (age > HEADCRAB_MAX_AGE)
		{
			kill();
			return;
		}
		
		if (leaping >= 0)
		{
			leap();
			return;
		}
		
		if (leaping < -1) {
			leaping++;
		}
		
		enemies = getTargets(enemies, HEADCRAB_ENEMIES, true);

		if (!enemies.isEmpty())
		{
			enemy = enemies.firstEntry().getValue();
			status = NPC.STATUS_THREAT;
		}
		else
		{
			if (status >= NPC.STATUS_ALERT) {
				status = NPC.STATUS_ALERT;
			} else {
				status = NPC.STATUS_IDLE;
			}
		}

		if (status <= NPC.STATUS_THREAT)
		{
			allies = getTargets(25, allies, 3.0, Math.PI, HEADCRAB_ALLIES, true);
			ally = getClosestNPC(allies, NPC.STATUS_THREAT);
		}
		
		if (seeTarget(enemy, HEADCRAB_ENEMIES, true) && leaping == -1)
		{
			lockTarget(enemy);
			
			if(distance() > 1) {
				chase(HEADCRAB_SPEED, HEADCRAB_TURN);
			} else {
				chase(0, HEADCRAB_TURN);
			}
			
			if (isInLOS(2, Math.PI * 0.01)) {
				leaping = 0;
			}	
		}
		else if (seeTarget(ally, 3.0, Math.PI, HEADCRAB_ALLIES, true)
				&& status <= NPC.STATUS_THREAT)
		{
			follow(HEADCRAB_SPEED, HEADCRAB_TURN, ally, 2);
		} else {
			roam(HEADCRAB_SPEED * 0.5, HEADCRAB_TURN);
		}
	}

	private int leaping = -1;

	private void leap()
	{
		leaping++;
		if (seeTarget(enemy, 0.1, Math.PI, HEADCRAB_ENEMIES, true))
		{
			if (enemy.getType().equalsIgnoreCase("Entity.NPC.Soldier")
					|| enemy.getType().equalsIgnoreCase("Entity.NPC.Human"))
			{
				enemy.kill();
				this.kill();
				getWorld().spawnEntity(new HeadcrabZombie(enemy.getX(), enemy.getY(), enemy.getZ(), enemy
						.getDirection()));
			}
			else
			{
				enemy.damage(30);
			}
			leaping = -200;
		}
		else
		{
			if (isColliding()) {
				leaping = -50;
			} else if (leaping > HEADCRAB_MAXLEAP) {
				leaping = -50;
			} else {
				move(0.1);
			}
		}
	}
	
	@Override
	public String NPCType()
	{
		return "Headcrab";
	}
}
