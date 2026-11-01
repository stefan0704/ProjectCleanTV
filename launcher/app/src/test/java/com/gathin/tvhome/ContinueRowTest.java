package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ContinueRowTest {
    private static TraktClient.Item item(String type, String title, String at) {
        TraktClient.Item i = new TraktClient.Item();
        i.type = type;
        i.title = title;
        i.updatedAt = at;
        return i;
    }

    @Test
    public void filtersByTypeNewestFirstWithLimit() {
        List<TraktClient.Item> all = new ArrayList<>();
        all.add(item("episode", "S1", "2026-10-01"));
        all.add(item("movie", "M1", "2026-10-02"));
        all.add(item("episode", "S2", "2026-10-03"));
        all.add(item("episode", "S3", "2026-10-04"));
        all.add(item("movie", "M2", "2026-10-06"));
        StringBuilder names = new StringBuilder();
        for (TraktClient.Item i : ContinueRow.filterByType(all, ContinueRow.TYPE_SERIES, 2)) names.append(i.title).append(' ');
        assertEquals("S3 S2 ", names.toString());
        assertEquals(2, ContinueRow.filterByType(all, ContinueRow.TYPE_MOVIES, 10).size());
        assertEquals(5, all.size());
    }

    @Test
    public void emptyOrNullListGivesNothing() {
        assertEquals(0, ContinueRow.filterByType(null, ContinueRow.TYPE_MOVIES, 10).size());
    }

    @Test
    public void exploreRowIsAGridOfSevenColumnsThatFillsTheWidth() {
        int w = ContinueRow.boxWidth(1472, 40, 7);
        assertEquals(176, w);
        assertTrue(7 * w + 6 * 40 <= 1472 && 7 * (w + 1) + 6 * 40 > 1472);
    }

    @Test
    public void snapNeverLeavesTheFocusedCellCutOrTheLeftEdgeInsideACell() {
        // selector 80 wide, then four 200 wide cells, 16 gaps; 700 px visible
        int[] left = {0, 96, 312, 528, 744}, right = {80, 296, 512, 728, 944};
        assertEquals(0, ContinueRow.snapFirst(0, 2, left, right, 700));
        int first = ContinueRow.snapFirst(0, 4, left, right, 700);
        assertTrue(right[4] - left[first] <= 700);
        assertTrue(first == 0 || right[4] - left[first - 1] > 700);
        assertEquals(1, ContinueRow.snapFirst(3, 1, left, right, 700));
    }

    @Test
    public void pagesAreWholeCellsAndNeverShareOne() {
        // featured 3 columns wide then boxes of 100, gap 10; 4 columns (430 px) in view
        int[] starts = ContinueRow.pageStarts(row(10), rowRight(10), 430);
        assertArrayEquals(new int[]{0, 2, 6}, starts);   // featured + 1 box, then 4 boxes, then 4 boxes
        // A short last page keeps its own cells instead of borrowing from the page before.
        assertArrayEquals(new int[]{0, 2, 6}, ContinueRow.pageStarts(row(8), rowRight(8), 430));
        assertEquals(0, ContinueRow.pageOf(starts, 0));
        assertEquals(1, ContinueRow.pageOf(starts, 5));
        assertEquals(2, ContinueRow.pageOf(starts, 9));
        assertEquals(1, ContinueRow.pageStarts(new int[]{0, 110}, new int[]{100, 210}, 430).length);
    }

    private static int[] row(int n) {
        int[] left = new int[n];
        for (int i = 1; i < n; i++) left[i] = 330 + (i - 1) * 110;
        return left;
    }

    private static int[] rowRight(int n) {
        int[] right = new int[n];
        for (int i = 0; i < n; i++) right[i] = i == 0 ? 320 : 330 + (i - 1) * 110 + 100;
        return right;
    }
}
