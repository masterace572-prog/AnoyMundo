package com.xlox.tests;

import com.xlox.utils.GameLoginRouter;
import com.xlox.utils.GameWebCallRouter;
import com.xlox.utils.GameWebNavigator;
import com.xlox.utils.GameWebPolicy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Provider-style web/redirect fixtures. No real provider, user account, game or SDK code is executed. */
public final class GameWebFlowTest {
    private static int passed;
    interface Test { void run() throws Exception; }
    private static void test(String name, Test body) throws Exception {
        body.run(); passed++; System.out.println("PASS " + name);
    }
    private static void require(boolean ok) { if (!ok) throw new AssertionError("Expectation failed"); }

    private static final class Page implements GameWebNavigator.Callbacks {
        boolean ownedByVirtualGame;
        String fallback;
        String received;
        int calls;
        public boolean forwardToGame(String url) { calls++; received = url; return ownedByVirtualGame; }
        public String httpsFallback(String url) { return fallback; }
        GameWebNavigator.Decision open(String url) { return GameWebNavigator.navigate(url, this); }
    }

    public static final class Platform {
        public Object startActivity(Object resultTo, Object intent, int requestCode, Object options) { return null; }
    }
    private static final Method START;
    static {
        try { START = Platform.class.getMethod("startActivity", Object.class, Object.class, int.class, Object.class); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static Object invoke(GameWebCallRouter router, Object[] args) throws Exception {
        try { return router.invoke(new Platform(), START, args); }
        catch (Exception e) { throw e; }
        catch (Throwable e) { throw new AssertionError(e); }
    }

    public static void main(String[] args) throws Exception {
        test("a game HTTPS browser request is contained even with an explicit outside browser package", () -> {
            for (String target : new String[]{null, "com.android.chrome", "org.mozilla.firefox"}) {
                require(GameWebPolicy.contain(GameLoginRouter.VIEW,
                        "https://api.twitter.com/oauth/authenticate?oauth_token=fixture", target));
            }
        });
        test("existing game-owned embedded activities and unrelated actions are left alone", () -> {
            require(!GameWebPolicy.contain(GameLoginRouter.VIEW, "https://api.twitter.com/oauth/authenticate", GameLoginRouter.GAME));
            require(!GameWebPolicy.contain("android.intent.action.SEND", "https://twitter.com", null));
            require(!GameWebPolicy.contain(GameWebPolicy.ACTION, "https://twitter.com", null));
            require(!GameWebPolicy.contain(GameLoginRouter.VIEW, "https://www.pubgmobile.com/news", null));
            require(!GameWebPolicy.contain(GameLoginRouter.VIEW, "https://payments.example/checkout?order=fixture", null));
            require(GameWebPolicy.contain(GameLoginRouter.VIEW, "https://game.example/web_login?channel=fixture", null));
            require(GameWebPolicy.contain(GameLoginRouter.VIEW, "https://account.example/authorize?client_id=fixture&redirect_uri=fixture%3A%2F%2Freturn&response_type=code", null));
        });
        test("only well-formed HTTPS entry pages can reach the contained WebView", () -> {
            for (String url : new String[]{null, "", "http://twitter.com", "https://user:pass@twitter.com", "https:opaque",
                    "file:///data/private", "javascript:alert(1)", "intent://callback", "https://twitter.com/%zz"}) {
                require(!GameWebPolicy.contain(GameLoginRouter.VIEW, url, null));
            }
        });
        test("the visible origin never includes provider-token query, fragment, path or credentials", () -> {
            String display = GameWebPolicy.origin("https://api.twitter.com/oauth/authenticate?oauth_token=fixture#private");
            require(display.equals("https://api.twitter.com"));
            require(GameWebPolicy.origin("https://user:pass@twitter.com").isEmpty());
            require(GameWebPolicy.origin("https://login.example:8443/path?fixture=1").equals("https://login.example:8443"));
        });
        test("ordinary provider-page navigation stays within WebView", () -> {
            Page page = new Page();
            require(page.open("https://twitter.com/i/flow/login").action == GameWebNavigator.Action.LOAD_WEB);
            require(page.calls == 1);
        });
        test("the actual game callback scheme is resolved dynamically, not guessed from three scheme names", () -> {
            Page page = new Page(); page.ownedByVirtualGame = true;
            String original = "fixture-game-sdk://return?oauth_token=fixture&oauth_verifier=a%2Bb#payload";
            require(page.open(original).action == GameWebNavigator.Action.RETURNED_TO_GAME);
            require(original.equals(page.received) && page.calls == 1);
        });
        test("an HTTPS game App Link also returns to the virtual handler", () -> {
            Page page = new Page(); page.ownedByVirtualGame = true;
            require(page.open("https://game.example/return?state=fixture&code=fixture").action
                    == GameWebNavigator.Action.RETURNED_TO_GAME);
        });
        test("intent-style callbacks are consumed by virtual dispatch without an OS/outside-app fallback", () -> {
            Page page = new Page(); page.ownedByVirtualGame = true;
            String value = "intent://return#Intent;scheme=fixture-game-sdk;package=com.pubg.imobile;end";
            require(page.open(value).action == GameWebNavigator.Action.RETURNED_TO_GAME);
            require(page.received.equals(value) && page.calls == 1);
        });
        test("a browser fallback remains a WebView HTTPS load, never another Android activity", () -> {
            Page page = new Page(); page.fallback = "https://twitter.com/oauth/authorize?oauth_token=fixture";
            GameWebNavigator.Decision decision = page.open("intent://unavailable#Intent;end");
            require(decision.action == GameWebNavigator.Action.LOAD_FALLBACK && decision.fallback.equals(page.fallback));
            for (String unsafe : new String[]{"http://outside.example", "javascript:bad()", "https://u:p@outside.example", null}) {
                page.fallback = unsafe;
                require(page.open("intent://unavailable#Intent;end").action == GameWebNavigator.Action.BLOCK);
            }
        });
        test("an unmatched custom callback is blocked instead of opening real BGMI", () -> {
            Page page = new Page();
            require(page.open("fixture-game-sdk://return").action == GameWebNavigator.Action.BLOCK);
            require(page.calls == 1);
        });
        test("local-file/content/JavaScript top-level navigation never reaches the game dispatcher", () -> {
            for (String url : new String[]{"file:///data/private", "content://private", "javascript:bad()", "data:text/html,private", "invalid input", null}) {
                Page page = new Page();
                require(page.open(url).action == GameWebNavigator.Action.BLOCK && page.calls == 0);
            }
        });
        test("service chaining replaces only the Intent slot and preserves result binder/request code/options/return object", () -> {
            Object webIntent = new Object(), containedIntent = new Object(), binder = new Object(), options = new Object(), result = new Object();
            Object[] original = {binder, webIntent, 71, options};
            int[] calls = {0};
            GameWebCallRouter router = new GameWebCallRouter(value -> value == webIntent ? containedIntent : null);
            require(router.install(handler -> (base, method, forwarded) -> {
                calls[0]++;
                require(method == START && forwarded != original);
                require(forwarded[0] == binder && forwarded[1] == containedIntent);
                require(forwarded[2].equals(71) && forwarded[3] == options);
                return result;
            }));
            require(invoke(router, original) == result && calls[0] == 1);
            require(original[1] == webIntent);
        });
        test("unrelated service calls and null argument arrays delegate unchanged", () -> {
            Object[] original = {new Object(), new Object(), -1, null};
            GameWebCallRouter router = new GameWebCallRouter(value -> null);
            require(router.install(handler -> (base, method, forwarded) -> { require(forwarded == original); return 12; }));
            require(invoke(router, original).equals(12));
            GameWebCallRouter empty = new GameWebCallRouter(value -> null);
            require(empty.install(handler -> (base, method, forwarded) -> { require(forwarded == null); return null; }));
            require(invoke(empty, null) == null);
        });
        test("the original SDK exception is preserved, not swallowed as a fake successful launch", () -> {
            IllegalArgumentException failure = new IllegalArgumentException("fixture");
            GameWebCallRouter router = new GameWebCallRouter(value -> null);
            require(router.install(handler -> (base, method, forwarded) -> { throw new InvocationTargetException(failure); }));
            try { invoke(router, new Object[0]); throw new AssertionError("Missing exception"); }
            catch (IllegalArgumentException e) { require(e == failure); }
        });
        test("a concurrent launch waits until the SDK's previous handler has been assigned", () -> {
            GameWebCallRouter router = new GameWebCallRouter(value -> null);
            @SuppressWarnings("unchecked") FutureTask<Object>[] pending = new FutureTask[1];
            require(router.install(handler -> {
                pending[0] = new FutureTask<>(() -> invoke(router, null));
                Thread thread = new Thread(pending[0], "hook-registration-fixture");
                thread.setDaemon(true); thread.start();
                return (base, method, forwarded) -> 99;
            }));
            require(pending[0].get(1, TimeUnit.SECONDS).equals(99));
            GameWebCallRouter absent = new GameWebCallRouter(value -> null);
            require(!absent.install(handler -> null));
        });
        System.out.println(passed + " contained-web-login regression groups passed");
    }
}
