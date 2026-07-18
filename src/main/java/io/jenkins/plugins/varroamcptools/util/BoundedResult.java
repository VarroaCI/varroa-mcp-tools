package io.jenkins.plugins.varroamcptools.util;

import java.util.Collections;
import java.util.List;

/**
 * The result of a {@link ListBounding#bound} call.
 *
 * @param <T>        the element type
 * @param items      the bounded sublist (never {@code null})
 * @param truncated  {@code true} when the original list had more elements
 *                   than were returned
 */
public final class BoundedResult<T> {

    private final List<T> items;
    private final boolean truncated;

    BoundedResult(List<T> items, boolean truncated) {
        this.items = Collections.unmodifiableList(items);
        this.truncated = truncated;
    }

    /**
     * The bounded sublist, in original order.
     */
    public List<T> getItems() {
        return items;
    }

    /**
     * Whether the original list had more elements than returned.
     */
    public boolean isTruncated() {
        return truncated;
    }
}
