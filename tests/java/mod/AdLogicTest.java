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
        System.out.println("all ok");
    }
}
