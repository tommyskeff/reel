package dev.tommyjs.reel.codec;

/**
 * Defines a single recording layer with a cost threshold that controls
 * when accumulated deltas are condensed and emitted as a span.
 *
 * @param costThreshold the accumulated cost at which this layer emits a condensed span.
 *                      The base layer (index 0) must always have a threshold of 0,
 *                      meaning it emits on every change.
 */
public record LayerDefinition(int costThreshold) {

    public LayerDefinition {
        if (costThreshold < 0) {
            throw new IllegalArgumentException("costThreshold must be non-negative, got " + costThreshold);
        }
    }

}
