package net.hedinger.prototype.entities;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.View;


public class Grenade extends Entity
{

	private static int fly_duration;
	private double velocity = 0; // determines damage done and appearance
	private int size;

	public Grenade(double x, double y, double z, double d)
	{

		D = d;
		X = x;
		Y = y;
		Z = z + 0.25;

		velocity = 0.1;
		lifespan = 50;
		deathspan = 0;
		size = 4;

		double dur = lifespan;
		dur = dur*0.75;
		fly_duration = round(dur);
	}

	public Grenade(Weapon wpn)
	{
		if (wpn == null)
		{
			System.out.println("NULL WEAPON");
			return;
		}

		D = variation(wpn.getDirection(), wpn.getBulletSpread());
		X = wpn.getX() + wpn.getBarrelLength() * Math.cos(D);
		Y = wpn.getY() + wpn.getBarrelLength() * Math.sin(D);
		Z = wpn.getZ() + 0.25;

		velocity = wpn.getBulletVelocity();
		lifespan = wpn.getBulletSpan();
		deathspan = 0;
		size = 4;

		double dur = lifespan;
		dur = dur*0.5;
		fly_duration = round(dur);
	}

	@Override
	protected void think()
	{
		if (age > fly_duration)
		{
			Z = (int) Z;
			size = 3;
			velocity -= 0.001;
			if(velocity < 0) {
				velocity = 0;
			}
		}
		else if(age == fly_duration)
		{
			double newD = variation(D, Math.PI*0.25);
			velocity = velocity*Math.cos(newD - D);
			D = newD;
		}

		dX = velocity * Math.cos(D);
		dY = velocity * Math.sin(D);

		if (isColliding()) {
			kill();
		}

	}

	@Override
	protected void draw(Graphics g, View v)
	{
		Graphics2D g2 = (Graphics2D) g;

		g2.setColor(Color.WHITE);
		g2.fillOval(pixelX(v, 0), pixelY(v, 0), size, size);
	}

	@Override
	public String EntityType()
	{
		return "Grenade";
	}

	@Override
	public void kill()
	{
		getWorld().spawnEntity(new Explosion(X, Y, Z));
		age = -1;
	}

}
