package io.freemytv;

public final class HisenseHomePolicyTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        HisenseHomePolicy p = new HisenseHomePolicy();
        check(!p.stock(true, 0), "explicit stock entry is allowed");
        check(p.home(10), "Home inside a deliberate stock visit now returns to Free");
        check(p.stock(false, 100), "native Home launch race is corrected once even from stock");
        check(!p.stock(false, 110), "duplicate stock events do not launch again");
        check(!p.home(200), "duplicate Home broadcasts are debounced");
        check(p.home(600), "the next normal Home request works");
        p.reset();
        check(!p.stock(false, 1000), "Back without Home never redirects stock");
        check(p.home(1100), "late Home notification still returns to Free");
        check(p.stock(false, 1200), "late native stock window is corrected");
        check(!p.stock(false, 1300), "fallback correction is single use");
        p.reset();
        check(p.home(2000), "Home from another app opens Free immediately");
        check(!p.stock(true, 2100), "a later explicit stock click cancels an older pending fallback");
        check(p.home(2600), "Home after another explicit stock visit always returns Free");
        check(!p.stock(false, 4601), "expired Home request cannot redirect an unrelated later window");
        p.reset();
        check(!p.stock(false, 5000), "a startup stock observation alone does not invent a Home press");
        check(p.home(6000), "Home from a settled startup stock window returns Free");
        p.reset();
        check(p.home(10000), "first Home requests one launch");
        check(!p.home(10001), "repeated broadcast does not relaunch");
        check(p.stock(false, 10010), "repeated broadcast retains one native-race fallback");
        check(!p.stock(false, 10020), "fallback cannot repeat");
        System.out.println("PASS: " + checks + " single-home routing assertions.");
    }
}
