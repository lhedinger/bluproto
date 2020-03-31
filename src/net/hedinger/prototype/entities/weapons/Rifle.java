package net.hedinger.prototype.entities.weapons;

import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.*;

public class Rifle extends Weapon
{
	private void init()
	{
		clip_size = 100;
		reload_delay = 200;
		fire_delay = 5;
		fire_accuracy = 0.01;
		fire_spread = 0.01;
		fire_velocity = 0.3;
		fire_distance = 10;
		barrel_length = 0.06;
	}
	
	
	public Rifle(NPC npc)
	{
		super(npc);
		init();
	}

	public Rifle(double x, double y, double z)
	{
		super(x, y, z);
		init();
	}

	public Rifle(double x, double y, double z, double d)
	{
		super(x, y, z, d);
		init();
	}

	public void fire(World w)
	{
		w.spawnEntity(new Bullet(this));
	}
	
	public String WeaponType()
	{
		return "Rifle";
	}

}
