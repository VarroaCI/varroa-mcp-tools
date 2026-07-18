package io.jenkins.plugins.varroamcptools.util;

import java.util.Collections;
import java.util.List;

/**
 * Static pagination helper that bounds a list by {@code limit} and
 * {@code offset}, returning a {@link BoundedResult} with the sliced items
 * and a flag indicating whether the original list had more elements.
 *
 * <p>Default limit is 20; hard cap is 100. This keeps tool responses from
 * ballooning when an agent asks for "all" items.
 */
public final class ListBounding {

    /** Default limit applied when {@code limit} is {@code null}. */
    public static final int DEFAULT_LIMIT = 20;

    /** Hard cap — any limit above this is clamped to this value. */
    public static final int HARD_CAP = 100;

    private ListBounding() {
        // utility class — no instantiation
    }

    /**
     * Returns a bounded view of {@code items}, sliced by the given limit
     * and offset.
     *
     * @param <T>    the element type
     * @param items  the source list (may be {@code null}, treated as empty)
     * @param limit  maximum number of items to return ({@code null} → default
     *               20, clamped to hard cap of 100)
     * @param offset zero-based starting index ({@code null} → 0)
     * @return a {@link BoundedResult} with the sliced items and a
     *         {@code truncated} flag
     */
    public static <T> BoundedResult<T> bound(List<T> items, Integer limit, Integer offset) {
        List<T> source = items == null ? Collections.emptyList() : items;
        int effectiveLimit = clampLimit(limit);
        int effectiveOffset = Math.max(0, offset == null ? 0 : offset);

        if (effectiveOffset >= source.size()) {
            return new BoundedResult<>(Collections.emptyList(), !source.isEmpty());
        }

        int toIndex = Math.min(effectiveOffset + effectiveLimit, source.size());
        List<T> slice = source.subList(effectiveOffset, toIndex);
        boolean truncated = toIndex < source.size();

        return new BoundedResult<>(slice, truncated);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(1, limit), HARD_CAP);
    }
}
