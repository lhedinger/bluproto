package net.hedinger.prototype.entities.npcs;

import java.awt.Color;
import java.util.TreeMap;

import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.NPC;

public class Headcrab extends NPC
{
	private static final int HEADCRAB_SF = 10;
	// Randomised per class-load; keeps its own lifecycle semantics (birth-size
	// growth, isDead threshold) rather than the genome's maxAge.
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
		applyGenome(Genome.phenotype(3, 0.02, 25, 1.5, Math.PI, HEADCRAB_MAX_AGE));
		col = new Color(0, 150, 0);
		age = (int) (HEADCRAB_MAX_AGE * 0.25);
		SEARCH_FREQ = HEADCRAB_SF;
	}

	public Headcrab(double x, double y, double z, double d)
	{
		super(x, y, z, d);
		applyGenome(Genome.phenotype(3, 0.02, 25, 1.5, Math.PI, HEADCRAB_MAX_AGE));
		col = new Color(0, 150, 0);
		size = 1; // hatchling; grows to genome size with age
		SEARCH_FREQ = HEADCRAB_SF;
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

			roam(speed * 0.05, turnRate);
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
				chase(speed, turnRate);
			} else {
				chase(0, turnRate);
			}
			
			if (isInLOS(2, Math.PI * 0.01)) {
				leaping = 0;
			}	
		}
		else if (seeTarget(ally, 3.0, Math.PI, HEADCRAB_ALLIES, true)
				&& status <= NPC.STATUS_THREAT)
		{
			follow(speed, turnRate, ally, 2);
		} else {
			roam(speed * 0.5, turnRate);
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
	public String getNpcTypeName()
	{
		return "Headcrab";
	}
}
