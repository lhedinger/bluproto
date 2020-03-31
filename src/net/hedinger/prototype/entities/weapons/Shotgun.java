package net.hedinger.prototype.entities.weapons;

import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.*;

public class Shotgun extends Weapon
{
	private void init()
	{
		clip_size = 8;
		reload_delay = 200;
		fire_delay = 25;
		fire_accuracy = 0.01;
		fire_spread = 0.1;
		fire_velocity = 0.4;
		fire_distance = 5;
		barrel_length = 0.06;
	}
	
	
	public Shotgun(NPC npc)
	{
		super(npc);
		init();
	}

	public Shotgun(double x, double y, double z)
	{
		super(x, y, z);
		init();
	}

	public Shotgun(double x, double y, double z, double d)
	{
		super(x, y, z, d);
		init();
	}

	public void fire(World w)
	{
		w.spawnEntity(new Bullet(this));
		w.spawnEntity(new Bullet(this));
		w.spawnEntity(new Bullet(this));
		w.spawnEntity(new Bullet(this));
		w.spawnEntity(new Bullet(this));
		w.spawnEntity(new Bullet(this));
		
	}


	public String WeaponType()
	{
		return "Shotgun";
	}

}
