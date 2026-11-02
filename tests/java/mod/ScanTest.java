package mod;

import com.instagram.feed.media.Media;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import obf.Obf;

public class ScanTest {
    static void check(String name, boolean ok) {
        if (!ok) {
            System.err.println("FAIL " + name);
            System.exit(1);
        }
        System.out.println("ok   " + name);
    }

    static String efg(String tag) throws Exception {
        String json = "{\"vencode_tag\":\"" + tag + "\"}";
        return java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(json.getBytes("UTF-8")), "UTF-8");
    }

    public static void main(String[] a) throws Exception {
        String low = "https://x.fbcdn.net/o1/v/t2/m86/a.mp4?efg=" + efg("xpv_progressive.INSTAGRAM.CLIPS.C3.540.dash_baseline_540p");
        String hi = "https://x.fbcdn.net/o1/v/t2/m86/b.mp4?efg=" + efg("xpv_progressive.INSTAGRAM.CLIPS.C3.1080.dash_baseline_1080p");
        String aud = "https://x.fbcdn.net/o1/v/t2/m78/c.mp4?efg=" + efg("dash_ln_heaac_vbr3_audio");
        String img = "https://scontent.cdninstagram.com/v/t51/p1080x1350/f.jpg";
        String thumb = "https://scontent.cdninstagram.com/v/t51/p150x150/t.jpg";

        Media m = new Media();
        m.A00 = new Obf.Url(thumb);
        m.A01.add(new Obf.Url(img));
        m.A01.add(new Obf.Url(low));
        m.A01.add(new Obf.Url(hi));
        m.A01.add(new Obf.Url(aud));
        m.A01.add(new Obf.Url("https://evil.example.com/other.mp4"));
        Obf.Item it = new Obf.Item();
        it.A01 = m;
        Obf.Holder h = new Obf.Holder();
        h.A00 = it;

        check("media found through an obfuscated holder", Scan.findMedia(h, 4) == m);
        check("depth limit respected", Scan.findMedia(h, 1) == null);
        List<String> urls = Scan.urls(m);
        check("only CDN urls collected", urls.size() == 5);
        check("best progressive video chosen", Scan.pick(urls).equals(hi));
        check("audio-only is never picked", !Scan.pick(Arrays.asList(aud, thumb)).equals(aud));
        check("photo: largest image", Scan.pick(Arrays.asList(thumb, img)).equals(img));
        check("quality described", Scan.describe(hi).equals("1080p progressive"));

        Media organic = new Media();
        check("organic media has no ad signal", Scan.adSignal(organic) == null);
        organic.adAction = "none";
        check("ad_action 'none' is not an ad", Scan.adSignal(organic) == null);
        Media ad1 = new Media();
        ad1.adAction = "learn_more";
        check("ad_action marks an ad", Scan.adSignal(ad1) != null);
        Media ad2 = new Media();
        ad2.adMeta.add("x");
        check("ad_metadata marks an ad", Scan.adSignal(ad2) != null);
        check("null media is safe", Scan.adSignal(null) == null);
        System.out.println("all ok");
    }
}
