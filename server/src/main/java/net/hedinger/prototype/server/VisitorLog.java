package net.hedinger.prototype.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * How many distinct people have contacted this server since it started, without
 * keeping a record of who they were.
 *
 * <p><b>Addresses are never stored.</b> Each one is hashed with a salt generated
 * fresh at boot and then discarded — so the log answers "how many" and "how often"
 * while being unable to answer "which". An IP address is personal data, this is a
 * public URL anyone can open, and counting visitors does not require identifying
 * them. The salt being per-boot means the numbers cannot be correlated across
 * restarts either, which is the same window "during its uptime" already implies.
 *
 * <p>Bounded on purpose: the set of hashes stops growing at {@link #MAX_DISTINCT}
 * and simply stops admitting new ones, because an unbounded visitor log on a 2 GB
 * box is a slow memory leak wearing a useful hat. The request counter keeps
 * counting regardless, so traffic is still measured after that point — only the
 * distinct-visitor figure saturates, and it says so.
 */
final class VisitorLog {

	/** Distinct hashes held before the set stops admitting new ones. At 32 hex
	 *  characters each this is a few hundred kilobytes at worst. */
	private static final int MAX_DISTINCT = 20_000;

	/** Generated at boot and never written down. Nothing here survives a restart,
	 *  which is what makes the hashes uncorrelatable between uptimes. */
	private final String salt = UUID.randomUUID().toString();

	private final Set<String> seen = new LinkedHashSet<String>();
	private long requests = 0;
	private boolean saturated = false;

	/** Records one contact from {@code ip}. Null or blank is counted as a request
	 *  but identifies nobody, so it cannot inflate the distinct count. */
	synchronized void record(String ip) {
		requests++;
		if (ip == null || ip.isBlank()) {
			return;
		}
		if (seen.size() >= MAX_DISTINCT) {
			saturated = true;
			return;
		}
		seen.add(hash(ip));
	}

	synchronized int distinct() {
		return seen.size();
	}

	synchronized long requests() {
		return requests;
	}

	/** True once the distinct count has stopped rising because it hit its cap — so
	 *  a reader can tell "20000 visitors" from "at least 20000 visitors". */
	synchronized boolean saturated() {
		return saturated;
	}

	private String hash(String ip) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] out = md.digest((salt + ip).getBytes(StandardCharsets.UTF_8));
			StringBuilder b = new StringBuilder(32);
			for (int i = 0; i < 16; i++) { // half the digest is plenty to avoid collisions here
				b.append(Character.forDigit((out[i] >> 4) & 0xf, 16));
				b.append(Character.forDigit(out[i] & 0xf, 16));
			}
			return b.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every JVM", e);
		}
	}

	/**
	 * The address to attribute a request to, given the direct peer and whatever
	 * {@code X-Forwarded-For} says.
	 *
	 * <p>This deployment sits behind Caddy, so the direct peer is the proxy and
	 * every visitor would otherwise look like the same one. The first entry in
	 * X-Forwarded-For is the original client; the rest are intermediaries. It is
	 * client-supplied and therefore spoofable, which is fine for counting an
	 * audience and would not be fine for anything that granted access.
	 */
	static String clientAddress(String directPeer, String forwardedFor) {
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			int comma = forwardedFor.indexOf(',');
			String first = (comma < 0 ? forwardedFor : forwardedFor.substring(0, comma)).trim();
			if (!first.isEmpty()) {
				return first;
			}
		}
		return directPeer;
	}
}
