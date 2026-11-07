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
    private static Server errors;

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
        publish("com/acme/garbled/1.0/garbled-1.0.jar", "garbled bytes", 2);
        Files.writeString(repo.resolve("com/acme/garbled/1.0/garbled-1.0.jar.sha1"),
                "this is not a checksum\n", StandardCharsets.UTF_8);

        // --- pseudo versions: what maven-metadata.xml says RELEASE and LATEST are
        metadata("com/acme/widget", """
                <metadata><groupId>com.acme</groupId><artifactId>widget</artifactId><versioning>
                  <latest>1.0.0</latest><release>1.0.0</release>
                  <versions><version>0.9</version><version>1.0.0</version></versions>
                </versioning></metadata>
                """);
        // release declared as a snapshot is not a release: the versions list decides
        publish("com/acme/relonly/0.8/relonly-0.8.jar", "relonly bytes", 3, "sha1");
        metadata("com/acme/relonly", """
                <metadata><versioning>
                  <latest>1.0-SNAPSHOT</latest>
                  <versions><version>0.8</version><version>1.0-SNAPSHOT</version></versions>
                </versioning></metadata>
                """);
        metadata("com/acme/noversioning", "<metadata><groupId>com.acme</groupId></metadata>");
        metadata("com/acme/noversions", """
                <metadata><versioning><versions><version>1.0-SNAPSHOT</version></versions></versioning></metadata>
                """);

        // --- snapshots: the timestamped file name comes from the version's metadata
        publish("com/acme/snap/2.0-SNAPSHOT/snap-2.0-20260101.120000-3.jar", "snapshot bytes", 2, "sha1");
        metadata("com/acme/snap/2.0-SNAPSHOT", """
                <metadata><versioning><snapshot>
                  <timestamp>20260101.120000</timestamp><buildNumber>3</buildNumber>
                </snapshot></versioning></metadata>
                """);
        publish("com/acme/local/1.0-SNAPSHOT/local-1.0-SNAPSHOT.jar", "local copy bytes", 2, "sha1");
        metadata("com/acme/local/1.0-SNAPSHOT", """
                <metadata><versioning><snapshot><localCopy>true</localCopy></snapshot></versioning></metadata>
                """);
        metadata("com/acme/nosnapmeta/1.0-SNAPSHOT",
                "<metadata><versioning><lastUpdated>1</lastUpdated></versioning></metadata>");
        metadata("com/acme/nosnaptime/1.0-SNAPSHOT",
                "<metadata><versioning><snapshot><buildNumber>1</buildNumber></snapshot></versioning></metadata>");

        plain = Server.start("plain", repo);
        auth = Server.start("auth", repo, "--require-basic", "builder:s3cret");
        slow = Server.start("slow", repo, "--delay", "3");
        broken = Server.start("broken", repo, "--truncate", "/com/acme/truncated/1.0/truncated-1.0.jar");
        errors = Server.start("errors", repo, "--status", "500:/com/acme/widget/1.0.0/widget-1.0.0.jar");
    }

    @AfterAll
    static void stopRepositories() {
        for (Server server : List.of(plain, auth, slow, broken, errors)) {
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

    // ------------------------------------------------------------------ versions that come from metadata

    @Test
    @DisplayName("LATEST and RELEASE are read out of maven-metadata.xml and then resolved like any version")
    void resolvesPseudoVersions() throws Exception {
        long mark = plain.mark();
        AtomResult latest = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:widget:LATEST")
                .run();

        latest.assertOk();
        assertTrue(latest.field("versions").contains("com.acme:widget=1.0.0"),
                "LATEST did not become the version the metadata names: " + latest.field("versions"));
        assertTrue(requested(plain.since(mark), "/com/acme/widget/maven-metadata.xml"),
                "the metadata was not fetched: " + plain.since(mark));
        assertTrue(requested(plain.since(mark), "/com/acme/widget/1.0.0/widget-1.0.0.jar"),
                "the resolved version's jar was not fetched: " + plain.since(mark));
        assertTrue(latest.console().contains("LATEST -> 1.0.0"), latest.console());

        AtomResult release = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:relonly:RELEASE")
                .run();
        release.assertOk();
        assertTrue(release.field("versions").contains("com.acme:relonly=0.8"),
                "a release declared as a snapshot must not be used: " + release.field("versions"));
        assertTrue(release.exists("relonly-0.8.jar"), release.file("output.json"));
    }

    @Test
    @DisplayName("a snapshot's file name comes from its own metadata, and the local copy keeps -SNAPSHOT")
    void resolvesSnapshots() throws Exception {
        long mark = plain.mark();
        AtomResult run = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:snap:2.0-SNAPSHOT")
                .run();

        run.assertOk();
        assertEquals("1", run.field("snapshots"), run.file("output.json"));
        assertTrue(requested(plain.since(mark), "/com/acme/snap/2.0-SNAPSHOT/maven-metadata.xml"),
                "the snapshot metadata was not fetched: " + plain.since(mark));
        assertTrue(requested(plain.since(mark), "/com/acme/snap/2.0-SNAPSHOT/snap-2.0-20260101.120000-3.jar"),
                "the timestamped file was not fetched: " + plain.since(mark));
        assertTrue(run.exists("snap-2.0-SNAPSHOT.jar"),
                "the target keeps the -SNAPSHOT name, as Maven's local repository does: " + run.field("targets"));
        assertTrue(run.exists(".m2/repository/com/acme/snap/2.0-SNAPSHOT/snap-2.0-SNAPSHOT.jar"),
                "the local repository copy must be named -SNAPSHOT");

        AtomResult local = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:local:1.0-SNAPSHOT")
                .run();
        local.assertOk();
        assertTrue(local.exists("local-1.0-SNAPSHOT.jar"), local.file("output.json"));
        assertEquals("1", local.field("snapshots"));
    }

    @Test
    @DisplayName("metadata that cannot name a version is a repository problem with a message that says why")
    void reportsUnusableMetadata() throws Exception {
        AtomResult noVersioning = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:noversioning:RELEASE").run();
        noVersioning.assertRefused();
        assertEquals("40007", noVersioning.field("errorCode"), noVersioning.file("output.json"));
        assertTrue(noVersioning.field("message").contains("no <versioning>"), noVersioning.field("message"));

        AtomResult noVersions = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:noversions:RELEASE").run();
        noVersions.assertRefused();
        assertTrue(noVersions.field("message").contains("no usable version"), noVersions.field("message"));

        AtomResult noMetadata = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:nometa:LATEST").run();
        noMetadata.assertRefused();
        assertTrue(noMetadata.field("message").contains("no maven-metadata.xml"),
                noMetadata.field("message"));

        AtomResult noSnapshot = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:nosnapmeta:1.0-SNAPSHOT").run();
        noSnapshot.assertRefused();
        assertTrue(noSnapshot.field("message").contains("no <snapshot>"), noSnapshot.field("message"));

        AtomResult noStamp = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:nosnaptime:1.0-SNAPSHOT").run();
        noStamp.assertRefused();
        assertTrue(noStamp.field("message").contains("no timestamp/buildNumber"), noStamp.field("message"));
    }

    // ------------------------------------------------------------------ bad input and bad repositories

    @Test
    @DisplayName("a coordinate that is not g:a:v[:ext[:classifier]] is refused with the shape it wanted")
    void refusesBadCoordinates() throws Exception {
        for (String line : List.of("com.acme.widget", "com.acme::1.0", "a:b:c:d:e:f", "com.acme:widget:1.0:ja r")) {
            AtomResult run = atom().input("repoUrl", plain.url).input("coordinates", line).run();
            run.assertRefused();
            assertEquals("40001", run.field("errorCode"), "coordinate '" + line + "':\n" + run);
            assertTrue(run.field("message").contains("bad coordinate"), run.field("message"));
        }
    }

    @Test
    @DisplayName("repoUrl has to be an http(s) URL, the policy has to be known, and a password needs a username")
    void refusesBadRepositorySettings() throws Exception {
        AtomResult notAUrl = atom().input("repoUrl", "not a url").input("coordinates", "a:b:1").run();
        notAUrl.assertRefused();
        assertEquals("40002", notAUrl.field("errorCode"), notAUrl.file("output.json"));
        assertTrue(notAUrl.field("message").contains("not a URL"), notAUrl.field("message"));

        AtomResult ftp = atom().input("repoUrl", "ftp://repo.example.com/maven2")
                .input("coordinates", "a:b:1").run();
        ftp.assertRefused();
        assertEquals("40002", ftp.field("errorCode"), ftp.file("output.json"));
        assertTrue(ftp.field("message").contains("http or https"), ftp.field("message"));

        AtomResult policy = atom().input("repoUrl", plain.url).input("coordinates", "a:b:1")
                .input("checksumPolicy", "maybe").run();
        policy.assertRefused();
        assertEquals("40001", policy.field("errorCode"), policy.file("output.json"));
        assertTrue(policy.field("message").contains("fail, warn, ignore"), policy.field("message"));

        AtomResult password = atom().input("repoUrl", plain.url).input("coordinates", "a:b:1")
                .input("password", "s3cret").run();
        password.assertRefused();
        assertEquals("40001", password.field("errorCode"), password.file("output.json"));
        assertTrue(password.field("message").contains("username is required"), password.field("message"));
        assertFalse(password.file("output.json").contains("s3cret"), "the password leaked into output.json");
    }

    @Test
    @DisplayName("a repository that is not listening is an I/O failure, and failOnError=false carries on")
    void reportsAnUnreachableRepository() throws Exception {
        int closed = freePort();
        AtomResult failed = atom().input("repoUrl", "http://127.0.0.1:" + closed)
                .input("coordinates", "com.acme:widget:1.0.0").input("timeoutSeconds", "2").run();

        failed.assertRefused();
        assertEquals("40008", failed.field("errorCode"), failed.file("output.json"));
        assertEquals("2", failed.field("errorType"), "a closed port is an I/O failure of the third party:\n" + failed);
        assertTrue(failed.field("message").contains("I/O with the repository"), failed.field("message"));

        AtomResult continued = atom().input("repoUrl", "http://127.0.0.1:" + closed)
                .input("coordinates", "com.acme:widget:1.0.0").input("timeoutSeconds", "2")
                .input("failOnError", "false").run();

        continued.assertOk();
        assertEquals("0", continued.field("resolved"), continued.file("output.json"));
        assertEquals("1", continued.field("failed"), continued.file("output.json"));
        assertTrue(continued.field("errors").contains("I/O with the repository"), continued.field("errors"));
        assertTrue(continued.console().contains("[resolve] warning:"), continued.console());
    }

    @Test
    @DisplayName("a 500 is not a missing artifact, and it names the status")
    void reportsAServerError() throws Exception {
        AtomResult run = atom().input("repoUrl", errors.url)
                .input("coordinates", "com.acme:widget:1.0.0").run();

        run.assertRefused();
        assertEquals("40008", run.field("errorCode"), run.file("output.json"));
        assertEquals("2", run.field("errorType"), run.file("output.json"));
        assertTrue(run.field("message").contains("HTTP 500"), run.field("message"));
    }

    @Test
    @DisplayName("a checksum file that is not a checksum is the same as none: fail refuses, warn keeps and says so")
    void handlesAnUnusableChecksum() throws Exception {
        AtomResult failed = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:garbled:1.0").run();
        failed.assertRefused();
        assertEquals("40005", failed.field("errorCode"), failed.file("output.json"));
        assertTrue(failed.field("message").contains("no checksum published"), failed.field("message"));

        AtomResult warned = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:garbled:1.0").input("checksumPolicy", "warn").run();
        warned.assertOk();
        assertTrue(warned.field("checksums").contains("none=missing"),
                "warn must report that nothing was verified: " + warned.field("checksums"));
        assertTrue(warned.exists("garbled-1.0.jar"), "warn must keep the downloaded bytes");
        assertTrue(warned.console().contains("no checksum published"), warned.console());
    }

    @Test
    @DisplayName("a target directory outside the workspace is refused, and a trailing slash on repoUrl is fine")
    void gatesTheTargetDirectory() throws Exception {
        AtomResult escape = atom().input("repoUrl", plain.url)
                .input("coordinates", "com.acme:widget:1.0.0")
                .input("targetDirectory", "../outside").run();
        escape.assertRefused();
        assertEquals("40010", escape.field("errorCode"), escape.file("output.json"));
        assertFalse(escape.exists("../outside/widget-1.0.0.jar"), "a file was written outside the workspace");

        AtomResult slash = atom().input("repoUrl", plain.url + "/")
                .input("coordinates", "com.acme:widget:1.0.0").run();
        slash.assertOk();
        assertTrue(slash.field("urls").startsWith(plain.url + "/com/acme/"),
                "the double slash must be stripped: " + slash.field("urls"));
        assertTrue(slash.exists("widget-1.0.0.jar"), slash.file("output.json"));
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Atom atom() throws Exception {
        return Atom.of();
    }

    private static boolean requested(List<Map<String, Object>> requests, String path) {
        return requests.stream().anyMatch(entry -> path.equals(entry.get("path")));
    }

    /** One {@code maven-metadata.xml} at {@code <group path>/<artifact>/[<version>/]}. */
    private static void metadata(String dir, String xml) throws Exception {
        Path file = repo.resolve(dir).resolve("maven-metadata.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, xml.strip(), StandardCharsets.UTF_8);
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
