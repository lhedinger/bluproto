package net.hedinger.prototype.entities;

/**
 * A compact, lossless, single-line text encoding of a {@link Genome} — a
 * creature's whole heritable definition, brain included — so an evolved minded
 * creature can be exported to a savefile and later re-injected as a seed.
 *
 * <p>The form is deliberately dependency-free (this is the engine module, which
 * has no JSON library) and, crucially, contains <b>no whitespace</b>: a
 * {@link net.hedinger.prototype.sim.SimCommand}'s {@code describe()} line is
 * split on whitespace when replayed, so the encoded genome has to survive as one
 * token. Fields are {@code key=value} pairs separated by {@code ;}; the brain is
 * its rows joined by {@code |}, each row's ints by {@code ,}. Doubles use
 * {@link Double#toString} (guaranteed to round-trip), so decode(encode(g))
 * reproduces the genome exactly.
 */
public final class GenomeCodec {

	private static final String VERSION = "g1";

	private GenomeCodec() {
	}

	/** The genome as one whitespace-free line (see the class doc for the form). */
	public static String encode(Genome g) {
		StringBuilder b = new StringBuilder(VERSION);
		b.append(";size=").append(g.size);
		b.append(";speed=").append(g.speed);
		b.append(";turn=").append(g.turnRate);
		b.append(";los=").append(g.losRange);
		b.append(";fov=").append(g.losFov);
		b.append(";metab=").append(g.metabolism);
		b.append(";maxAge=").append(g.maxAge);
		b.append(";flying=").append(g.flying ? 1 : 0);
		b.append(";markers=");
		for (int i = 0; i < g.markers.length; i++) {
			if (i > 0) {
				b.append(',');
			}
			b.append(g.markers[i]);
		}
		b.append(";pred=").append(g.predatory);
		b.append(";xeno=").append(g.xenophobia);
		b.append(";greg=").append(g.gregariousness);
		b.append(";bold=").append(g.boldness);
		b.append(";mate=").append(g.mateThreshold);
		b.append(";brain=");
		if (g.brain != null) {
			int[][] code = g.brain.code();
			for (int r = 0; r < code.length; r++) {
				if (r > 0) {
					b.append('|');
				}
				for (int c = 0; c < code[r].length; c++) {
					if (c > 0) {
						b.append(',');
					}
					b.append(code[r][c]);
				}
			}
		}
		return b.toString();
	}

	/** Rebuilds a genome from {@link #encode}'s output. Throws
	 *  {@link IllegalArgumentException} on a malformed line. */
	public static Genome decode(String line) {
		if (line == null || line.isBlank()) {
			throw new IllegalArgumentException("empty genome");
		}
		try {
			Genome g = new Genome();
			boolean sawVersion = false;
			for (String part : line.trim().split(";")) {
				int eq = part.indexOf('=');
				if (eq < 0) {
					sawVersion = sawVersion || part.equals(VERSION);
					continue; // the leading version token has no '='
				}
				String k = part.substring(0, eq);
				String v = part.substring(eq + 1);
				switch (k) {
				case "size" -> g.size = Double.parseDouble(v);
				case "speed" -> g.speed = Double.parseDouble(v);
				case "turn" -> g.turnRate = Integer.parseInt(v);
				case "los" -> g.losRange = Double.parseDouble(v);
				case "fov" -> g.losFov = Double.parseDouble(v);
				case "metab" -> g.metabolism = Double.parseDouble(v);
				case "maxAge" -> g.maxAge = Integer.parseInt(v);
				case "flying" -> g.flying = v.equals("1");
				case "markers" -> g.markers = doubles(v);
				case "pred" -> g.predatory = Double.parseDouble(v);
				case "xeno" -> g.xenophobia = Double.parseDouble(v);
				case "greg" -> g.gregariousness = Double.parseDouble(v);
				case "bold" -> g.boldness = Double.parseDouble(v);
				case "mate" -> g.mateThreshold = Double.parseDouble(v);
				case "brain" -> g.brain = v.isEmpty() ? null : new Brain(codeMatrix(v));
				default -> { /* forward-compatible: ignore unknown keys */ }
				}
			}
			if (!sawVersion) {
				throw new IllegalArgumentException("missing/unknown version tag");
			}
			return g;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("malformed genome: " + e.getMessage(), e);
		}
	}

	private static double[] doubles(String csv) {
		if (csv.isEmpty()) {
			return new double[0];
		}
		String[] p = csv.split(",");
		double[] out = new double[p.length];
		for (int i = 0; i < p.length; i++) {
			out[i] = Double.parseDouble(p[i]);
		}
		return out;
	}

	private static int[][] codeMatrix(String v) {
		String[] rows = v.split("\\|");
		int[][] code = new int[rows.length][];
		for (int r = 0; r < rows.length; r++) {
			String[] cells = rows[r].split(",");
			code[r] = new int[cells.length];
			for (int c = 0; c < cells.length; c++) {
				code[r][c] = Integer.parseInt(cells[c]);
			}
		}
		return code;
	}
}
