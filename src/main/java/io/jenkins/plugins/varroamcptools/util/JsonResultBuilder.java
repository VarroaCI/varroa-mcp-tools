package io.jenkins.plugins.varroamcptools.util;

import java.util.LinkedHashMap;

/**
 * Fluent builder for compact, stable-key-order JSON result shapes.
 *
 * <p>Backed by {@link LinkedHashMap}, so keys serialize in insertion order.
 * Callers must not pass Jenkins core model objects
 * ({@link hudson.model.Run}, {@link hudson.model.Job},
 * {@link hudson.model.Computer}) as values — extract the fields you need
 * and add them individually. The type signature ({@code add(String, Object)})
 * cannot enforce this; it is a project convention upheld in review.
 */
public final class JsonResultBuilder {

    private final LinkedHashMap<String, Object> map;

    private JsonResultBuilder() {
        this.map = new LinkedHashMap<>();
    }

    /**
     * Creates a new builder with stable (insertion-order) key sequencing.
     */
    public static JsonResultBuilder create() {
        return new JsonResultBuilder();
    }

    /**
     * Adds a key-value pair to the result.
     *
     * @param key   the JSON key
     * @param value the value (may be {@code null})
     * @return this builder, for chaining
     */
    public JsonResultBuilder add(String key, Object value) {
        map.put(key, value);
        return this;
    }

    /**
     * Returns the accumulated map, preserving insertion order.
     */
    public LinkedHashMap<String, Object> build() {
        return map;
    }
}
