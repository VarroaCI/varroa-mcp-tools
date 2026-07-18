package io.jenkins.plugins.varroamcptools.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ListBoundingTest {

    /** Returns a list of integers from 0 (inclusive) to {@code size} (exclusive). */
    private static List<Integer> range(int size) {
        return IntStream.range(0, size).boxed().collect(Collectors.toList());
    }

    @Test
    void shouldApplyDefaultLimit() {
        List<Integer> items = range(50);
        BoundedResult<Integer> result = ListBounding.bound(items, null, null);

        assertThat(result.getItems()).hasSize(20);
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void shouldEnforceHardCap() {
        List<Integer> items = range(150);
        BoundedResult<Integer> result = ListBounding.bound(items, 500, 0);

        assertThat(result.getItems()).hasSize(100);
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void shouldReturnUntruncatedWhenFewerItemsThanLimit() {
        List<Integer> items = range(5);
        BoundedResult<Integer> result = ListBounding.bound(items, 20, 0);

        assertThat(result.getItems()).containsExactly(0, 1, 2, 3, 4);
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void shouldHonorNonZeroOffset() {
        List<Integer> items = range(20);
        BoundedResult<Integer> result = ListBounding.bound(items, 10, 5);

        // items[5..14] inclusive → 5,6,7,8,9,10,11,12,13,14
        assertThat(result.getItems()).containsExactly(5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
    }

    @Test
    void shouldTreatTruncatedAsTrueWhenItemsBeyondReturnedSlice() {
        List<Integer> items = range(30);
        BoundedResult<Integer> result = ListBounding.bound(items, 10, 5);

        assertThat(result.getItems()).hasSize(10);
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void shouldHandleOffsetBeyondListSize() {
        List<Integer> items = range(10);
        BoundedResult<Integer> result = ListBounding.bound(items, 5, 20);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void shouldHandleNullItemsList() {
        BoundedResult<Object> result = ListBounding.bound(null, 10, 0);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void shouldHandleNullOffsetAsZero() {
        List<Integer> items = range(10);
        BoundedResult<Integer> result = ListBounding.bound(items, 3, null);

        assertThat(result.getItems()).containsExactly(0, 1, 2);
    }

    @Test
    void shouldReturnAllItemsWhenLimitExceedsSize() {
        List<Integer> items = range(7);
        BoundedResult<Integer> result = ListBounding.bound(items, 50, 0);

        assertThat(result.getItems()).containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void shouldPreserveDefaultLimitConstant() {
        assertThat(ListBounding.DEFAULT_LIMIT).isEqualTo(20);
    }

    @Test
    void shouldPreserveHardCapConstant() {
        assertThat(ListBounding.HARD_CAP).isEqualTo(100);
    }

    @Test
    void shouldReturnEmptyForEmptySource() {
        BoundedResult<Integer> result = ListBounding.bound(Collections.emptyList(), 20, 0);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.isTruncated()).isFalse();
    }
}
