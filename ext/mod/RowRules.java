package mod;

/** Pure size rules that decide whether a list row is a media post (testable without Android). */
final class RowRules {
    private RowRules() {}

    /**
     * @param pager    the list belongs to a ViewPager2 (Reels): every page is a media item
     * @param rowW     row width, rvW list width
     * @param bigW     width of the biggest image/video view inside the row (0 if none)
     * @param bigArea  its area, rowArea the row's area
     */
    static boolean isMediaRow(boolean pager, int rowW, int rvW, int bigW, long bigArea, long rowArea) {
        if (pager) return true;
        return bigW > 0 && rowW >= 0.9f * rvW && bigW >= 0.8f * rvW && bigArea >= 0.4f * rowArea;
    }
}
