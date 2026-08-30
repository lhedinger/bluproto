package net.hedinger.prototype.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The unit a simulation constant is measured in — "energy/tick", "ticks",
 * "of the tank" — read at runtime by the tuning registry and shown beside the
 * number wherever the constant is displayed, so a value is never just a bare
 * figure. The registry refuses to survey a constant that lacks one: a
 * quantity without a unit is only half stated, and the panel would be showing
 * numbers nobody can read.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Unit {
	/** The unit, as the short human-readable string shown after the value. */
	String value();
}
