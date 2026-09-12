package net.hedinger.prototype.entities;

import java.util.LinkedHashMap;
import java.util.Map;

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
 *
 * <p>The numeric genes are read straight off {@link GeneSchema}, so a gene added
 * to the schema is serialized without touching this class — the same one-place
 * property the rest of heredity now has. Only the two non-numeric heritables,
 * the clade code and the brain, are written by hand.
 */
public final class GenomeCodec {

	/**
	 * Bumped whenever the {@link AgentIO} vectors change size OR the gene set
	 * changes shape. {@code Brain} masks operand indices modulo the live array
	 * length, so a genome encoded against 23 sensors reads <i>different</i>
	 * sensors once there are 28 — its wiring is silently rewritten rather than
	 * merely extended. Rejecting the old tag is the honest outcome: a stale token
	 * names a creature that can no longer be reconstructed, and loading it would
	 * produce a different animal wearing its name. (g1: 23 sensors / 11 actuators.
	 * g2: 28 / 13, intent commands added. g3: 29 / 13, the intent-status channel
	 * added. g4: schema-driven genes, markers split into m0..m2. g5: life-history
	 * genes added — reproF, reproC, mut, inst. g6: the MLP mind substrate — an
	 * optional "mlp=" weight vector alongside the LGP "brain=".)
	 */
	private static final String VERSION = "g6";

	/** Keys accepted on decode that are no longer emitted, mapped to the gene key
	 *  they now go by — so a recording made under an old name is not silently
	 *  dropped. ("loyal" was determination's key for its first afternoon.) */
	private static final Map<String, String> ALIASES = Map.of("loyal", "det");

	private GenomeCodec() {
	}

	/** The genome as one whitespace-free line (see the class doc for the form). */
	public static String encode(Genome g) {
		StringBuilder b = new StringBuilder(VERSION);
		for (GeneSchema.Gene gene : GeneSchema.genes()) {
			b.append(';').append(gene.key).append('=').append(gene.get(g));
		}
		b.append(";diet=").append(g.clade.code()); // frozen wire code, not ordinal
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
		b.append(";mlp=");
		if (g.mlp != null) {
			double[] w = g.mlp.weights();
			for (int i = 0; i < w.length; i++) {
				if (i > 0) {
					b.append(',');
				}
				b.append(w[i]);
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
			Map<String, GeneSchema.Gene> byKey = new LinkedHashMap<>();
			for (GeneSchema.Gene gene : GeneSchema.genes()) {
				byKey.put(gene.key, gene);
			}
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
				k = ALIASES.getOrDefault(k, k);
				GeneSchema.Gene gene = byKey.get(k);
				if (gene != null) {
					gene.setRaw(g, Double.parseDouble(v)); // lossless: no living clamp on transport
				} else if (k.equals("diet")) {
					g.clade = Genome.Clade.ofCode(Integer.parseInt(v));
				} else if (k.equals("brain")) {
					g.brain = v.isEmpty() ? null : new Brain(codeMatrix(v));
				} else if (k.equals("mlp")) {
					g.mlp = v.isEmpty() ? null : MlpBrain.fromWeights(doubles(v));
				}
				// else: forward-compatible — ignore an unknown key.
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
