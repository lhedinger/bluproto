package net.hedinger.prototype.engine;

/**
 * A large plant standing in the world -- a tree or a big shrub -- as world data,
 * not a simulated entity. Nothing about it changes once the terrain is laid, so
 * it is placed once, served once a level, and never walked by the tick: a
 * forest of entities would have cost the sim loop and the entity stream every
 * tick for things that do not move. When plants come to drop fruit, the system
 * that does it reads this list.
 *
 * <p>Placed at its FOOT: the art-pixel (world-absolute, {@code tileX * 12 + ai})
 * where the trunk meets the ground, which is where its shadow lies and where a
 * body walking under it is judged against. The art is the client's
 * ({@code client/src/flora.ts}); {@code layout} picks which of the kind's
 * hand-placed arrangements it wears, and {@code mirror} flips it east-west, so
 * no two neighbours look alike.
 */
public final class Flora {

	/** What stands there. Order is the wire format: the client indexes its art by it. */
	public enum Kind {
		BROADLEAF, OAK, CONIFER, ACACIA, SHRUB, BERRY, FLYTRAP, MONSTERA;

		/** A tree has a trunk and a crown over the ground; the rest stand low. */
		public boolean isTree() {
			return this == BROADLEAF || this == OAK || this == CONIFER || this == ACACIA;
		}
	}

	public final Kind kind;
	public final int z, gx, gy, layout;
	public final boolean mirror;

	public Flora(Kind kind, int z, int gx, int gy, int layout, boolean mirror) {
		this.kind = kind;
		this.z = z;
		this.gx = gx;
		this.gy = gy;
		this.layout = layout;
		this.mirror = mirror;
	}

	/** The tile its foot stands on. */
	public int tileX() {
		return Math.floorDiv(gx, GroundTextures.ART);
	}

	public int tileY() {
		return Math.floorDiv(gy, GroundTextures.ART);
	}
}
