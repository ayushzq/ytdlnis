package mod;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Row structure copied from a real feed dump: header, media, CTA, buttons, caption per post. */
public class AdLogicTest {
    static String fmt(boolean[] b) {
        StringBuilder s = new StringBuilder();
        for (boolean x : b) s.append(x ? 'A' : '.');
        return s.toString();
    }

    static void check(String name, boolean ok) {
        if (!ok) {
            System.err.println("FAIL " + name);
            System.exit(1);
        }
        System.out.println("ok   " + name);
    }

    static void eq(String name, String want, boolean[] got) {
        if (!want.equals(fmt(got))) {
            System.err.println("FAIL " + name + ": want " + want + " got " + fmt(got));
            System.exit(1);
        }
        System.out.println("ok   " + name);
    }

    public static void main(String[] args) {
        Set<Integer> mem = new HashSet<>();
        eq("whole ad group hidden, neighbours kept", ".AAAAA...",
                AdLogic.decide(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9},
                        new boolean[]{false, true, false, false, false, false, true, false, false},
                        new boolean[]{false, true, true, false, false, false, false, false, false}, mem, true));
        eq("ad header scrolled off, rest still hidden", "AAAA...",
                AdLogic.decide(new int[]{3, 4, 5, 6, 7, 8, 9},
                        new boolean[]{false, false, false, false, true, false, false},
                        new boolean[]{true, false, false, false, false, false, false}, mem, true));
        eq("only marker-less tail rows visible", "AA..",
                AdLogic.decide(new int[]{5, 6, 7, 8}, new boolean[]{false, false, true, false},
                        new boolean[]{false, false, false, false}, mem, true));
        eq("non-feed list: marked rows only", ".A.",
                AdLogic.decide(new int[]{1, 2, 3}, new boolean[3], new boolean[]{false, true, false}, new HashSet<Integer>(), false));
        eq("normal post after an ad stays visible", "A..",
                AdLogic.decide(new int[]{6, 7, 8}, new boolean[]{false, true, false}, new boolean[3],
                        new HashSet<>(Arrays.asList(2, 3, 4, 5, 6)), true));
        // sizes from the real feed dump (list 1080 wide, 2128 high) and the story tray
        check("tall feed video (87% of the screen) is a feed media row, not a reel", RowRules.isMediaRow(false, 1080, 1080, 1080, 1080L * 1847, 1080L * 1847));
        check("square photo row is a media row", RowRules.isMediaRow(false, 1080, 1080, 1080, 1080L * 1080, 1080L * 1080));
        check("story-tray avatar is not", !RowRules.isMediaRow(false, 250, 1080, 160, 160L * 160, 250L * 347));
        check("buttons/caption row without a big image is not", !RowRules.isMediaRow(false, 1080, 1080, 0, 0, 1080L * 401));
        check("small thumbnail inside a wide row is not", !RowRules.isMediaRow(false, 1080, 1080, 150, 150L * 150, 1080L * 143));
        check("every page of a pager is a media item", RowRules.isMediaRow(true, 1080, 1080, 0, 0, 1080L * 2128));
        System.out.println("all ok");
    }
}
