package net.hedinger.prototype.sim;

import java.util.Locale;

import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Item;

/**
 * Drops an inanimate {@link Item} into the world — the first interactive verb
 * a web viewer gets. Deterministic: item constructors draw no RNG, and
 * {@link World#spawnEntity} rejects out-of-bounds coordinates, so a hostile or
 * confused client cannot corrupt the world through this path.
 */
public record SpawnItemCommand(Item.Kind kind, double x, double y, double z) implements SimCommand {

	/** Parses the wire form ("food" | "crate" | "hazard"); null if unknown. */
	public static SpawnItemCommand parse(String kind, double x, double y, double z) {
		try {
			return new SpawnItemCommand(Item.Kind.valueOf(kind.toUpperCase(Locale.ROOT)), x, y, z);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	@Override
	public void apply(World w) {
		Item item = switch (kind) {
		case FOOD -> Item.food(x, y, z);
		case CRATE -> Item.crate(x, y, z);
		case HAZARD -> Item.hazard(x, y, z);
		};
		w.spawnEntity(item);
	}

	@Override
	public String describe() {
		return String.format(Locale.ROOT, "spawnItem %s %.3f %.3f %.1f",
				kind.name().toLowerCase(Locale.ROOT), x, y, z);
	}
}
