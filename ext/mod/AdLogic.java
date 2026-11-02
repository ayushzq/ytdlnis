package mod;

import java.util.Set;

/**
 * Pure decision logic for "which list rows belong to an ad". A feed post is several RecyclerView rows
 * (header, media, CTA, buttons, caption), only some of which carry an ad marker, so ads are hidden as
 * a group that starts at an ad header (or an ad-marked row) and runs until the next post header.
 */
final class AdLogic {
    private AdLogic() {}

    /**
     * @param pos     adapter positions of the visible rows, ascending
     * @param header  row contains the post header (row_feed_profile_header)
     * @param marker  row carries an ad marker ("Ad", "Sponsored ...")
     * @param adPos   memory of positions already known to be ad rows (updated in place)
     * @param feed    this list has post headers (propagation through marker-less rows is allowed)
     * @return        ad flag per row
     */
    static boolean[] decide(int[] pos, boolean[] header, boolean[] marker, Set<Integer> adPos, boolean feed) {
        boolean[] ad = new boolean[pos.length];
        for (int i = 0; i < pos.length; i++) {
            boolean isAd = header[i] ? marker[i] : (marker[i] || (feed && adPos.contains(pos[i] - 1)));
            ad[i] = isAd;
            if (isAd) adPos.add(pos[i]);
            else adPos.remove(pos[i]);
        }
        return ad;
    }
}
