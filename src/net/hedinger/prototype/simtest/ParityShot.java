package net.hedinger.prototype.simtest;

import java.io.File;

import javax.imageio.ImageIO;

import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.sim.Worlds;

/**
 * Renders a deterministic frame of the demo world through the full entity
 * render pipeline and writes it to a PNG — a byte-comparable parity probe for
 * renderer refactors: run before and after, diff the files.
 *
 * <p>Usage: {@code ParityShot <out.png> [ticks]} (default 400 ticks, enough
 * for creatures to move, act, die and emit pheromones).
 */
final class ParityShot {

	public static void main(String[] args) throws Exception {
		File out = new File(args.length > 0 ? args[0] : "parity.png");
		int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 400;
		net.hedinger.prototype.engine.RenderFx.foliage = false; // deterministic: no wind phase
		World w = Worlds.demo(42);
		for (int i = 0; i < ticks; i++) {
			w.think();
		}
		ImageIO.write(SnapshotRenderer.render(w), "png", out);
		System.out.println("wrote " + out + " (" + out.length() + " bytes) at tick " + ticks);
	}

	private ParityShot() {
	}
}
