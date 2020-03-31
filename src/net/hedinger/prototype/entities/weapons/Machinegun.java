package net.hedinger.prototype.entities.weapons;

import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.*;

public class Machinegun extends Weapon
{
	private void init()
	{
		clip_size = 50;
		reload_delay = 200;
		fire_delay = 3;
		fire_accuracy = 0.01;
		fire_spread = 0.001;
		fire_velocity = 0.3;
		fire_distance = 10;
		barrel_length = 0.1;
	}
	
	
	public Machinegun(NPC npc)
	{
		super(npc);
		init();
	}

	public Machinegun(double x, double y, double z)
	{
		super(x, y, z);
		init();
	}

	public Machinegun(double x, double y, double z, double d)
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
		return "Machinegun";
	}

}
