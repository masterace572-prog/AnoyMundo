package com.xlox.tests;

import com.xlox.utils.GameLoginRouter;
import com.xlox.utils.LibraryHttp;
import com.xlox.utils.UpdateRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Executes the actual production router/transport classes, without an Android device or credentials. */
public final class RuntimeFixesTest {
    private static int passed;
    private static final Deque<Reply> replies = new ArrayDeque<>();
    private static final List<Reply> opened = new ArrayList<>();

    interface Test { void run() throws Exception; }

    private static void test(String name, Test body) throws Exception {
        replies.clear();
        opened.clear();
        body.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("Expectation failed");
    }

    private static void expect(Class<? extends Throwable> type, Test body) throws Exception {
        try {
            body.run();
        } catch (Throwable e) {
            if (type.isInstance(e)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + e.getClass().getSimpleName());
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static final class Game implements GameLoginRouter.Container {
        boolean running = true;
        GameLoginRouter.Target target = new GameLoginRouter.Target(GameLoginRouter.GAME, "game.Login", true, true);
        int resolutions;
        int launches;
        String returned;
        public boolean isRunning() { return running; }
        public GameLoginRouter.Target resolve(String uri) { resolutions++; return target; }
        public void launch(GameLoginRouter.Target selected, String uri) {
            require(selected == target);
            launches++;
            returned = uri;
        }
        GameLoginRouter.Result route(String uri) { return GameLoginRouter.route(GameLoginRouter.VIEW, uri, this); }
    }

    private static final class Reply extends HttpURLConnection {
        int code = 200;
        String location;
        boolean failHeaders;
        boolean blockHeaders;
        boolean blockBody;
        volatile boolean closed;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        Reply(int status, String redirect) throws Exception {
            super(new URL("https://library.invalid/file"));
            code = status;
            location = redirect;
            replies.add(this);
        }
        public int getResponseCode() throws IOException {
            entered.countDown();
            if (failHeaders) throw new IOException("fixture header failure");
            if (blockHeaders) awaitClose();
            return code;
        }
        public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        public InputStream getInputStream() {
            return new InputStream() {
                public int read() throws IOException {
                    if (blockBody) awaitClose();
                    return -1;
                }
            };
        }
        private void awaitClose() throws IOException {
            try {
                if (!disconnected.await(2, TimeUnit.SECONDS)) throw new AssertionError("Socket was not disconnected");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("fixture socket closed");
        }
        public void disconnect() { closed = true; disconnected.countDown(); }
        public boolean usingProxy() { return false; }
        public void connect() { }
    }

    private static HttpURLConnection open(UpdateRequest request, boolean metadata) throws IOException {
        return LibraryHttp.open("https://library.invalid/file", "application/octet-stream", "test-client", metadata, request);
    }

    private static FutureTask<Void> background(Test test) {
        FutureTask<Void> task = new FutureTask<>(() -> { test.run(); return null; });
        Thread worker = new Thread(task, "runtime-fixture");
        worker.setDaemon(true);
        worker.start();
        return task;
    }

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> !"https".equals(protocol) ? null : new URLStreamHandler() {
            protected URLConnection openConnection(URL url) throws IOException {
                Reply reply = replies.poll();
                if (reply == null) throw new IOException("Unexpected fixture request");
                opened.add(reply);
                return reply;
            }
        });

        test("all supported game callback schemes preserve the complete return URI", () -> {
            for (String scheme : new String[]{"twittersdk", "imobile1450", "tencent1106545419"}) {
                Game game = new Game();
                String uri = scheme + "://callback?oauth_token=fixture&oauth_verifier=fixture%2Bvalue#fragment";
                require(game.route(uri) == GameLoginRouter.Result.FORWARDED);
                require(game.launches == 1 && uri.equals(game.returned));
            }
        });
        test("Facebook delegates to the original SDK, without resolving/restarting a game", () -> {
            Game game = new Game();
            require(game.route("fbconnect://cct.com.pubg.imobile?fixture=1") == GameLoginRouter.Result.LEGACY);
            require(game.resolutions == 0 && game.launches == 0);
        });
        test("an absent/stopped game is never restarted for a login callback", () -> {
            Game game = new Game(); game.running = false;
            require(game.route("twittersdk://callback?fixture=1") == GameLoginRouter.Result.NO_RUNNING_GAME);
            require(game.resolutions == 0 && game.launches == 0);
        });
        test("outside apps and unexported/disabled game activities cannot receive a forwarded callback", () -> {
            for (GameLoginRouter.Target target : new GameLoginRouter.Target[]{
                    null, new GameLoginRouter.Target("outside.game", "Login", true, true),
                    new GameLoginRouter.Target(GameLoginRouter.GAME, "Login", false, true),
                    new GameLoginRouter.Target(GameLoginRouter.GAME, "Login", true, false),
                    new GameLoginRouter.Target(GameLoginRouter.GAME, "", true, true)}) {
                Game game = new Game(); game.target = target;
                require(game.route("twittersdk://callback") == GameLoginRouter.Result.NO_HANDLER);
                require(game.launches == 0);
            }
        });
        test("unrelated URLs, malformed data and non-VIEW intents are rejected", () -> {
            for (String uri : new String[]{null, "", "not a uri", "https://twitter.com/oauth/authorize",
                    "file:///data/private", "content://example/file", "intent://callback", "another://callback",
                    "twittersdk:opaque", "twittersdk://user@callback", "twittersdk://callback?bad=%zz"}) {
                Game game = new Game();
                require(game.route(uri) == GameLoginRouter.Result.IGNORED);
                require(game.launches == 0 && game.resolutions == 0);
            }
            Game game = new Game();
            require(GameLoginRouter.route("android.intent.action.SEND", "twittersdk://callback", game)
                    == GameLoginRouter.Result.IGNORED);
            require(game.launches == 0);
        });
        test("oversized callback URIs are refused before touching the container", () -> {
            Game game = new Game();
            require(game.route("twittersdk://callback?" + new String(new char[17 * 1024]).replace('\0', 'x'))
                    == GameLoginRouter.Result.IGNORED);
            require(game.resolutions == 0);
        });

        test("metadata is fresh; hash-verified blobs may use CDN caching", () -> {
            for (boolean metadata : new boolean[]{true, false}) {
                Reply reply = new Reply(200, null);
                try (UpdateRequest request = new UpdateRequest()) {
                    request.begin(5_000);
                    require(open(request, metadata) == reply);
                    require(reply.getUseCaches() != metadata);
                    require(metadata == "no-cache".equals(reply.getRequestProperty("Cache-Control")));
                    require("identity".equals(reply.getRequestProperty("Accept-Encoding")));
                    require(reply.getConnectTimeout() > 0 && reply.getConnectTimeout() <= 5_000);
                    require(reply.getReadTimeout() > 0 && reply.getReadTimeout() <= 5_000);
                }
                require(reply.closed);
            }
        });
        test("relative HTTPS redirects are followed and every old connection is closed", () -> {
            Reply redirect = new Reply(302, "/object");
            Reply file = new Reply(200, null);
            try (UpdateRequest request = new UpdateRequest()) {
                request.begin(5_000);
                require(open(request, false) == file);
                require(redirect.closed && !file.closed);
            }
            require(file.closed && opened.size() == 2);
        });
        test("HTTPS cannot redirect to HTTP, file URLs or credential-bearing URLs", () -> {
            for (String destination : new String[]{"http://library.invalid/file", "file:///tmp/lib", "https://user:pass@library.invalid/file"}) {
                Reply reply = new Reply(302, destination);
                try (UpdateRequest request = new UpdateRequest()) {
                    request.begin(5_000);
                    expect(ProtocolException.class, () -> open(request, false));
                }
                require(reply.closed);
            }
        });
        test("a failed header read releases its connection", () -> {
            Reply reply = new Reply(200, null); reply.failHeaders = true;
            try (UpdateRequest request = new UpdateRequest()) {
                request.begin(5_000);
                expect(IOException.class, () -> open(request, false));
            }
            require(reply.closed);
        });
        test("malformed and excessive redirects fail without leaking sockets", () -> {
            Reply missing = new Reply(302, null);
            try (UpdateRequest request = new UpdateRequest()) {
                request.begin(5_000);
                expect(ProtocolException.class, () -> open(request, false));
            }
            require(missing.closed);
            for (int i = 0; i < 6; i++) new Reply(302, "/loop");
            try (UpdateRequest request = new UpdateRequest()) {
                request.begin(5_000);
                expect(ProtocolException.class, () -> open(request, false));
            }
            for (Reply reply : opened) require(reply.closed);
        });
        test("closing a screen interrupts a request still waiting for headers", () -> {
            Reply reply = new Reply(200, null); reply.blockHeaders = true;
            UpdateRequest request = new UpdateRequest(); request.begin(5_000);
            FutureTask<Void> task = background(() -> expect(IOException.class, () -> open(request, false)));
            require(reply.entered.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            request.cancel();
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250);
            task.get(1, TimeUnit.SECONDS);
            require(reply.closed && request.isCancelled());
            request.close();
            expect(InterruptedIOException.class, () -> request.begin(5_000));
        });
        test("a deadline closes the socket during body reads, not just between reads", () -> {
            Reply reply = new Reply(200, null); reply.blockBody = true;
            UpdateRequest request = new UpdateRequest(); request.begin(150);
            InputStream in = open(request, false).getInputStream();
            FutureTask<Void> task = background(() -> expect(IOException.class, () -> in.read()));
            task.get(1, TimeUnit.SECONDS);
            require(reply.closed);
            expect(SocketTimeoutException.class, request::check);
            request.close();
            // Retry on this same still-visible screen must be possible.
            request.begin(5_000);
            request.check();
            request.close();
        });
        test("cancellation wakes retry backoff immediately", () -> {
            UpdateRequest request = new UpdateRequest(); request.begin(10_000);
            CountDownLatch entered = new CountDownLatch(1);
            FutureTask<Void> task = background(() -> {
                entered.countDown();
                expect(InterruptedIOException.class, () -> request.pause(5_000));
            });
            require(entered.await(1, TimeUnit.SECONDS));
            request.cancel();
            task.get(1, TimeUnit.SECONDS);
            request.close();
        });
        test("an old operation's deadline cannot abort a newer operation", () -> {
            UpdateRequest request = new UpdateRequest(); request.begin(100); request.close();
            Reply reply = new Reply(200, null);
            request.begin(5_000); open(request, false);
            request.pause(200);
            require(!reply.closed);
            request.check();
            request.close();
        });
        System.out.println(passed + " runtime regression groups passed");
    }
}
