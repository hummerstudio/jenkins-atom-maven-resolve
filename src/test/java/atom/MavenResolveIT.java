package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The maven-resolve atom, started the way the runner starts it, against real HTTP repositories over a fixture tree.
 *
 * <p>What this atom must get right is not "a file appeared": it is the request it makes (the Maven 2 layout path), the
 * bytes it stores, and what it says when the repository misbehaves. These are the checks the old
 * {@code tools/smoke.sh} made — every one of its eleven cases — written as JUnit, with each expectation recomputed
 * from the bytes on disk rather than read back from the atom.
 */
class MavenResolveIT {

    private static Path tmp;
    private static Path repo;
    private static Server plain;
    private static Server auth;
    private static Server slow;
    private static Server broken;

    @BeforeAll
    static void startRepositories() throws Exception {
        tmp = Files.createTempDirectory("maven-resolve-it-");
        repo = Files.createDirectories(tmp.resolve("repo"));
        publish("com/acme/widget/1.0.0/widget-1.0.0.jar", "widget-1.0.0 jar bytes", 40, "sha1");
        publish("com/acme/widget/1.0.0/widget-1.0.0-sources.jar", "widget sources bytes", 7, "sha1");
        publish("com/acme/bundle/2.0/bundle-2.0.zip", "bundle zip bytes", 5, "sha1");
        publish("com/acme/badsha/1.0/badsha-1.0.jar", "badsha bytes", 9);
        Files.writeString(repo.resolve("com/acme/badsha/1.0/badsha-1.0.jar.sha1"),
                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef\n", StandardCharsets.UTF_8);
        publish("com/acme/sha256only/1.0/sha256only-1.0.jar", "sha256 only bytes", 11, "sha256");
        publish("com/acme/unchecked/1.0/unchecked-1.0.jar", "unchecked bytes", 3);
        publish("com/acme/slow/1.0/slow-1.0.jar", "slow bytes", 4, "sha1");
        publish("com/acme/secret/1.0/secret-1.0.jar", "secret bytes", 6, "sha1");
        publish("com/acme/truncated/1.0/truncated-1.0.jar", "truncated bytes", 200, "sha1");

        plain = Server.start("plain", repo);
        auth = Server.start("auth", repo, "--require-basic", "builder:s3cret");
        slow = Server.start("slow", repo, "--delay", "3");
        broken = Server.start("broken", repo, "--truncate", "/com/acme/truncated/1.0/truncated-1.0.jar");
    }

    @AfterAll
    static void stopRepositories() {
        for (Server server : List.of(plain, auth, slow, broken)) {
            if (server != null) {
                server.close();
            }
        }
    }

    @Test
    @DisplayName("a release jar: the layout path, the exact bytes, the sha1 checked, one workspace copy")
    void resolvesARelease() throws Exception {
        long mark = plain.mark();
        AtomResult run = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:widget:1.0.0")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("1", run.field("resolved"), "resolved:\n" + run);
        Path served = repo.resolve("com/acme/widget/1.0.0/widget-1.0.0.jar");
        assertEquals(String.valueOf(Files.size(served)), run.field("bytes"), "byte count:\n" + run);

        List<Map<String, Object>> requests = plain.since(mark);
        assertTrue(requested(requests, "/com/acme/widget/1.0.0/widget-1.0.0.jar"),
                "the atom did not GET the layout path: " + requests);
        assertTrue(requests.stream()
                        .anyMatch(entry -> "jenkins-atom-maven-resolve".equals(entry.get("userAgent"))),
                "user agent: " + requests);

        assertArrayEquals(Files.readAllBytes(served),
                run.bytes(".m2/repository/com/acme/widget/1.0.0/widget-1.0.0.jar"),
                "the local repository bytes differ from what was served");
        assertArrayEquals(Files.readAllBytes(served), run.bytes("widget-1.0.0.jar"),
                "the workspace copy differs from what was served");
        assertTrue(run.field("checksums").contains("sha1=ok"), "checksum not verified: " + run.field("checksums"));
        assertTrue(run.field("versions").contains("com.acme:widget=1.0.0"), "versions: " + run.field("versions"));
        assertEquals(1, run.field("targets").split("\n").length,
                "more than one workspace copy: " + run.field("targets"));
    }

    @Test
    @DisplayName("a classifier and an extension change the file name, not the directory")
    void resolvesClassifiersAndExtensions() throws Exception {
        long mark = plain.mark();
        AtomResult run = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:widget:1.0.0:jar:sources\ncom.acme:bundle:2.0:zip")
                .run();

        run.assertOk();
        List<Map<String, Object>> requests = plain.since(mark);
        assertTrue(requested(requests, "/com/acme/widget/1.0.0/widget-1.0.0-sources.jar"),
                "classifier path: " + requests);
        assertTrue(requested(requests, "/com/acme/bundle/2.0/bundle-2.0.zip"), "extension path: " + requests);
        assertTrue(run.exists("widget-1.0.0-sources.jar"), "classifier artifact not copied");
        assertTrue(run.exists("bundle-2.0.zip"), "extension artifact not copied");
        assertEquals("2", run.field("resolved"), "two artifacts:\n" + run);
    }

    @Test
    @DisplayName("targetFileName renames the copy and may create sub-directories")
    void honoursTargetFileName() throws Exception {
        AtomResult run = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:widget:1.0.0")
                .input("targetFileName", "libs/app.jar")
                .run();

        run.assertOk();
        assertTrue(run.exists("libs/app.jar"), "targetFileName was not honoured:\n" + run);
        assertArrayEquals(Files.readAllBytes(repo.resolve("com/acme/widget/1.0.0/widget-1.0.0.jar")),
                run.bytes("libs/app.jar"), "the renamed copy has the wrong bytes");
        assertTrue(run.field("targets").contains(run.work().resolve("libs/app.jar").toString()),
                "targets: " + run.field("targets"));
    }

    @Test
    @DisplayName("a checksum that disagrees fails the step and leaves no artifact, unless the policy is warn")
    void failsOnAChecksumMismatch() throws Exception {
        AtomResult failed = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:badsha:1.0")
                .run();

        failed.assertRefused();
        assertEquals("40006", failed.field("errorCode"), "a bad sha1 must fail with 40006:\n" + failed);
        assertTrue(failed.field("message").contains("checksum mismatch"),
                "the failure must say what disagreed:\n" + failed);
        assertFalse(failed.exists("badsha-1.0.jar"), "a corrupt artifact reached the workspace");
        try (Stream<Path> part = Files.walk(failed.work())) {
            assertTrue(part.noneMatch(path -> path.getFileName().toString().endsWith(".part")),
                    "a .part file was left behind");
        }

        AtomResult warned = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:badsha:1.0")
                .input("checksumPolicy", "warn")
                .run();

        warned.assertOk();
        assertTrue(warned.field("checksums").contains("sha1=mismatch"),
                "warn must report the mismatch: " + warned.field("checksums"));
        assertTrue(warned.exists("badsha-1.0.jar"), "warn must keep the downloaded bytes");
    }

    @Test
    @DisplayName("sha256 is used when it is all the repository publishes; no checksum at all is refused")
    void fallsBackToSha256AndRefusesUnverifiableArtifacts() throws Exception {
        AtomResult sha256 = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:sha256only:1.0")
                .run();

        sha256.assertOk();
        assertTrue(sha256.field("checksums").contains("sha256=ok"),
                "sha256 was not used: " + sha256.field("checksums"));
        assertTrue(sha256.exists("sha256only-1.0.jar"), "sha256-only artifact missing");

        AtomResult refused = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:unchecked:1.0")
                .run();

        refused.assertRefused();
        assertEquals("40005", refused.field("errorCode"), "an unverifiable artifact must fail:\n" + refused);

        AtomResult ignored = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:unchecked:1.0")
                .input("checksumPolicy", "ignore")
                .run();

        ignored.assertOk();
        assertTrue(ignored.field("checksums").contains("ignored"),
                "ignore must say so: " + ignored.field("checksums"));
    }

    @Test
    @DisplayName("a 404 names the URL it asked for and is a repository problem, not a caller mistake")
    void reportsAMissingArtifact() throws Exception {
        AtomResult run = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:absent:9.9")
                .run();

        run.assertRefused();
        assertEquals("40003", run.field("errorCode"), "a missing artifact is 40003:\n" + run);
        assertTrue(run.field("message").matches("(?s).*not found: .*/com/acme/absent/9\\.9/absent-9\\.9\\.jar.*"),
                "the 404 must name the URL: " + run.field("message"));
        assertEquals("2", run.field("errorType"), "404 is a repository problem:\n" + run);
    }

    @Test
    @DisplayName("401 without credentials, then Basic from a bound secret that never reaches the output")
    void usesBasicAuthFromASecret() throws Exception {
        AtomResult anonymous = atom().input("repoUrl", auth.url)
                .input("coordinates", "com.acme:secret:1.0")
                .run();

        anonymous.assertRefused();
        assertEquals("40004", anonymous.field("errorCode"), "401 without credentials is 40004:\n" + anonymous);
        assertEquals("1", anonymous.field("errorType"), "401 is the caller's problem:\n" + anonymous);
        assertTrue(anonymous.field("message").contains("HTTP 401"), "401 not reported:\n" + anonymous);

        long mark = auth.mark();
        AtomResult bound = atom().file(".secrets.json", "{\"username\": \"builder\", \"password\": \"s3cret\"}")
                .input("repoUrl", auth.url)
                .input("coordinates", "com.acme:secret:1.0")
                .run();

        bound.assertOk();
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("builder:s3cret".getBytes(StandardCharsets.UTF_8));
        assertTrue(auth.since(mark).stream()
                        .anyMatch(entry -> expected.equals(entry.get("authorization"))),
                "basic auth header: " + auth.since(mark));
        assertTrue(bound.exists("secret-1.0.jar"), "authenticated artifact missing");
        assertFalse(bound.exists(".secrets.json"), "the runner must delete .secrets.json");
        assertFalse(bound.file("output.json").contains("s3cret"), "the password leaked into output.json");
    }

    @Test
    @DisplayName("failOnError=false resolves what it can and reports the rest")
    void continuesOnErrorWhenAsked() throws Exception {
        AtomResult run = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:absent:9.9\ncom.acme:widget:1.0.0")
                .input("failOnError", "false")
                .run();

        run.assertOk();
        assertEquals("1", run.field("resolved"), "resolved:\n" + run);
        assertEquals("1", run.field("failed"), "failed:\n" + run);
        assertTrue(run.field("errors").contains("not found"), "errors: " + run.field("errors"));
        assertTrue(run.exists("widget-1.0.0.jar"), "the resolvable artifact was not copied");
    }

    @Test
    @DisplayName("a slow repository times out as a timeout, not as a hang")
    void timesOut() throws Exception {
        long started = System.currentTimeMillis();
        AtomResult run = atom().input("repoUrl", slow.url)
                .input("coordinates", "com.acme:slow:1.0")
                .input("timeoutSeconds", "1")
                .run();

        run.assertRefused();
        assertTrue(System.currentTimeMillis() - started < 10_000, "the timeout did not fire");
        assertEquals("40013", run.field("errorCode"), "a timeout is 40013:\n" + run);
        assertTrue(run.field("message").contains("timed out after 1s"),
                "the failure must say it timed out: " + run.field("message"));
    }

    @Test
    @DisplayName("a short read is not mistaken for an artifact")
    void refusesATruncatedDownload() throws Exception {
        AtomResult run = atom().input("repoUrl", broken.url)
                .input("coordinates", "com.acme:truncated:1.0")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("truncated download"),
                "truncation not reported: " + run.field("message"));
        assertFalse(run.exists("truncated-1.0.jar"), "a truncated file reached the workspace");
    }

    @Test
    @DisplayName("bad input is named, and a target cannot leave the workspace")
    void refusesBadInput() throws Exception {
        AtomResult noCoordinates = atom().input("repoUrl", plain.url)
                .input("coordinates", "")
                .run();
        noCoordinates.assertRefused();
        assertEquals("40001", noCoordinates.field("errorCode"), "empty coordinates:\n" + noCoordinates);

        AtomResult noUrl = atom().input("repoUrl", "")
                .input("coordinates", "com.acme:widget:1.0.0")
                .run();
        noUrl.assertRefused();
        assertEquals("40002", noUrl.field("errorCode"), "empty repoUrl:\n" + noUrl);

        AtomResult twoTargets = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:widget:1.0.0\ncom.acme:bundle:2.0:zip")
                .input("targetFileName", "one.jar")
                .run();
        twoTargets.assertRefused();
        assertEquals("40009", twoTargets.field("errorCode"),
                "one target for two coordinates:\n" + twoTargets);

        AtomResult escape = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:widget:1.0.0")
                .input("targetFileName", "../evil.jar")
                .run();
        escape.assertRefused();
        assertEquals("40010", escape.field("errorCode"), "an escaping target:\n" + escape);
        assertFalse(Files.exists(escape.work().getParent().resolve("evil.jar")),
                "a file was written outside the workspace");
    }

    private static Atom atom() throws Exception {
        return Atom.of();
    }

    private static boolean requested(List<Map<String, Object>> requests, String path) {
        return requests.stream().anyMatch(entry -> path.equals(entry.get("path")));
    }

    /** A fixture artifact plus the checksum files a repository publishes next to it. */
    private static void publish(String path, String line, int count, String... algorithms) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            body.append(line).append('\n');
        }
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        for (String algorithm : algorithms) {
            MessageDigest digest = MessageDigest.getInstance("sha1".equals(algorithm) ? "SHA-1" : "SHA-256");
            Files.writeString(repo.resolve(path + "." + algorithm),
                    HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))) + "\n",
                    StandardCharsets.UTF_8);
        }
    }

    /** One fake Maven repository; it writes its port to a file once it is listening. */
    private record Server(Process process, String url, Path log) implements AutoCloseable {

        static Server start(String name, Path root, String... extra) throws Exception {
            Path portFile = tmp.resolve(name + ".port");
            Path log = tmp.resolve(name + ".log");
            List<String> command = new ArrayList<>(List.of("python3",
                    module().resolve("tools/fake_maven_repo.py").toString(),
                    "--root", root.toString(), "--log", log.toString(), "--port-file", portFile.toString()));
            command.addAll(List.of(extra));
            Path output = tmp.resolve(name + ".out");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (Files.isRegularFile(portFile) && Files.size(portFile) > 0) {
                    return new Server(process, "http://127.0.0.1:" + Files.readString(portFile).trim(), log);
                }
                if (!process.isAlive()) {
                    throw new IllegalStateException("fake repository '" + name + "' exited: "
                            + Files.readString(output));
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("fake repository '" + name + "' did not start: "
                    + Files.readString(output));
        }

        long mark() throws IOException {
            return Files.size(log);
        }

        List<Map<String, Object>> since(long offset) throws IOException {
            byte[] all = Files.readAllBytes(log);
            if (all.length <= offset) {
                return List.of();
            }
            String text = new String(all, (int) offset, all.length - (int) offset, StandardCharsets.UTF_8);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    entries.add(Json.parseObject(line));
                }
            }
            return entries;
        }

        @Override
        public void close() {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Path module() {
        return Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
    }
}
