package net.hedinger.prototype.sim;

/**
 * Reconstructs a {@link SimCommand} from the stable text form its {@code
 * describe()} produces — the inverse used when replaying a {@link Recording}.
 * Only world-mutating commands are ever logged (pacing verbs like pause/speed
 * do not change the world, so they are not recorded), which today means
 * {@link SpawnItemCommand}.
 */
public final class SimCommands {

	/** Parses one logged command line, or null if the verb is unknown. */
	public static SimCommand fromDescribe(String line) {
		String[] p = line.trim().split("\\s+");
		if (p.length == 0) {
			return null;
		}
		if (p[0].equals("spawnItem") && p.length >= 5) {
			return SpawnItemCommand.parse(p[1], Double.parseDouble(p[2]),
					Double.parseDouble(p[3]), Double.parseDouble(p[4]));
		}
		return null;
	}

	private SimCommands() {
	}
}
