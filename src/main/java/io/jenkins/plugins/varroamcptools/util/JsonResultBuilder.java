package io.jenkins.plugins.varroamcptools.util;

import java.util.LinkedHashMap;

/**
 * Fluent builder for compact, stable-key-order JSON result shapes.
 *
 * <p>Backed by {@link LinkedHashMap}, so keys serialize in insertion order.
 * The builder intentionally does not accept Jenkins core model objects
 * ({@link hudson.model.Run}, {@link hudson.model.Job},
 * {@link hudson.model.Computer}) — callers must extract the fields they
 * need and add them individually.
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
