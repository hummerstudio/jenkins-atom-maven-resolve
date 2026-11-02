package com.jenkinschina.atom.mavenresolve;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Resolve Maven artifacts by coordinate and put them where the build can use them.
 *
 * <p>Port of the resolve half of <em>repository-connector</em> (the deploy half is covered by
 * {@code artifact-publish}). The plugin hands resolution to Eclipse Aether; an atom carries no dependencies, so the
 * Maven rules that matter are written out here: repository layout, {@code maven-metadata.xml} for RELEASE / LATEST /
 * SNAPSHOT, and checksum verification with the policy Aether itself defaults to ({@code warn}).
 *
 * <p>Deliberate differences are listed in README.md and each one is asserted by {@code tools/check.py}.
 */
public class MavenResolveAtom implements AtomTask {

    static final List<String> CHECKSUM_POLICIES = List.of("fail", "warn", "ignore");
    static final String METADATA = "maven-metadata.xml";
    static final int MAX_REDIRECTS = 5;
    static final int MAX_METADATA_BYTES = 1 << 20;

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String repoUrl = context.getParam("repoUrl", "").trim();
        String rawCoordinates = context.getParam("coordinates", "");
        String username = context.getParam("username", "");
        String password = context.getParam("password", "");
        String bearerToken = context.getParam("bearerToken", "");
        String checksumPolicy = context.getParam("checksumPolicy", "warn").trim().toLowerCase(Locale.ROOT);
        boolean failOnError = context.getBooleanParam("failOnError", true);
        boolean copyToWorkspace = context.getBooleanParam("copyToWorkspace", true);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 60), 600));

        if (repoUrl.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "repoUrl is required");
        }
        String base = stripTrailingSlash(repoUrl);
        URI baseUri;
        try {
            baseUri = URI.create(base);
        } catch (IllegalArgumentException e) {
            return AtomResult.error(ErrorType.USER, 40002, "repoUrl is not a URL: " + repoUrl);
        }
        if (!"http".equalsIgnoreCase(baseUri.getScheme()) && !"https".equalsIgnoreCase(baseUri.getScheme())) {
            return AtomResult.error(ErrorType.USER, 40002, "repoUrl must be http or https: " + repoUrl);
        }
        if (!CHECKSUM_POLICIES.contains(checksumPolicy)) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "unknown checksumPolicy '" + checksumPolicy + "'; known: " + String.join(", ", CHECKSUM_POLICIES));
        }

        List<Coord> coords = new ArrayList<>();
        for (String line : rawCoordinates.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            try {
                coords.add(Coord.parse(trimmed));
            } catch (IllegalArgumentException e) {
                return AtomResult.error(ErrorType.USER, 40001, e.getMessage());
            }
        }
        if (coords.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "coordinates is required: one groupId:artifactId:version[:extension[:classifier]] per line");
        }

        Path workspace = context.getWorkspace();
        Path localRepo;
        Path targetDir;
        String targetFileName = context.getParam("targetFileName", "").trim();
        try {
            localRepo = path(context, context.getParam("localRepository", ".m2/repository"), "localRepository", false);
            targetDir = path(context, context.getParam("targetDirectory", ""), "targetDirectory", true);
        } catch (Failure f) {
            return AtomResult.error(f.type, f.code, f.getMessage());
        }
        if (!targetFileName.isEmpty() && coords.size() > 1) {
            return AtomResult.error(ErrorType.USER, 40009,
                    "targetFileName needs exactly one coordinate; got " + coords.size());
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String auth = authorization(username, password, bearerToken);

        List<String> files = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        List<String> checksums = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int resolved = 0;
        int failed = 0;
        int snapshots = 0;
        long bytes = 0;

        for (Coord coord : coords) {
            try {
                One one = resolveOne(context, client, base, auth, coord, checksumPolicy, timeoutSeconds,
                        localRepo, targetDir, targetFileName, copyToWorkspace);
                resolved++;
                bytes += one.bytes();
                files.add(one.local().toString());
                urls.add(one.url());
                versions.add(coord.groupId() + ":" + coord.artifactId() + "=" + one.version());
                checksums.add(one.local() + "\t" + one.checksum());
                if (one.snapshot()) {
                    snapshots++;
                }
                if (one.target() != null) {
                    targets.add(one.target().toString());
                }
            } catch (Failure f) {
                if (failOnError) {
                    return AtomResult.error(f.type, f.code, f.getMessage());
                }
                failed++;
                errors.add(coord + " " + f.getMessage());
                context.log("[resolve] warning: " + coord + " " + f.getMessage());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String message = "I/O with the repository: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                if (failOnError) {
                    return AtomResult.error(ErrorType.THIRD_PART, 40008, message);
                }
                failed++;
                errors.add(coord + " " + message);
                context.log("[resolve] warning: " + coord + " " + message);
            }
        }

        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("resolved", String.valueOf(resolved));
        outputs.put("failed", String.valueOf(failed));
        outputs.put("snapshots", String.valueOf(snapshots));
        outputs.put("bytes", String.valueOf(bytes));
        outputs.put("files", String.join("\n", files));
        outputs.put("targets", String.join("\n", targets));
        outputs.put("urls", String.join("\n", urls));
        outputs.put("versions", String.join("\n", versions));
        outputs.put("checksums", String.join("\n", checksums));
        if (!errors.isEmpty()) {
            outputs.put("errors", String.join("\n", errors));
        }
        if (!files.isEmpty()) {
            outputs.put("first", files.get(0));
        }
        context.log("[resolve] " + resolved + " artifact(s), " + bytes + " bytes"
                + (failed == 0 ? "" : ", " + failed + " failed"));

        AtomResult result = AtomResult.success();
        outputs.forEach(result::data);
        return result;
    }

    // ------------------------------------------------------------------ one artifact

    private One resolveOne(
            AtomContext context,
            HttpClient client,
            String base,
            String auth,
            Coord coord,
            String checksumPolicy,
            int timeoutSeconds,
            Path localRepo,
            Path targetDir,
            String targetFileName,
            boolean copyToWorkspace) throws Failure, IOException, InterruptedException {

        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        String version = coord.version();
        if (isPseudo(version)) {
            version = pseudoVersion(client, base, auth, coord, timeout);
            context.log("[resolve] " + coord.groupId() + ":" + coord.artifactId() + " " + coord.version()
                    + " -> " + version);
        }
        boolean snapshot = version.endsWith("-SNAPSHOT");
        String baseVersion = snapshot ? version.substring(0, version.length() - "-SNAPSHOT".length()) : version;
        String suffix = coord.classifier().isEmpty() ? "" : "-" + coord.classifier();
        String extension = coord.extension();

        String fileName;
        if (snapshot) {
            Snapshot snapshotMeta = snapshotMetadata(client, base, auth, coord, version, timeout);
            fileName = snapshotMeta.localCopy()
                    ? coord.artifactId() + "-" + version + suffix + "." + extension
                    : coord.artifactId() + "-" + baseVersion + "-" + snapshotMeta.timestamp()
                            + "-" + snapshotMeta.buildNumber() + suffix + "." + extension;
        } else {
            fileName = coord.artifactId() + "-" + version + suffix + "." + extension;
        }
        String localName = coord.artifactId() + "-" + version + suffix + "." + extension;

        String groupPath = coord.groupId().replace('.', '/');
        String remoteUrl = base + "/" + encodePath(groupPath) + "/" + encode(coord.artifactId())
                + "/" + encode(version) + "/" + encode(fileName);
        Path dir = localRepo.resolve(groupPath).resolve(coord.artifactId()).resolve(version);
        Path local = dir.resolve(localName);
        Path part = dir.resolve(localName + ".part");
        Files.createDirectories(dir);

        context.log("[resolve] GET " + remoteUrl);
        Download download;
        try {
            download = download(client, URI.create(remoteUrl), auth, timeout, part);
        } catch (Failure f) {
            deleteQuietly(part);
            throw f;
        }

        Checksum checksum;
        try {
            checksum = verifyChecksum(client, URI.create(remoteUrl), auth, checksumPolicy, timeout, download);
        } catch (Failure f) {
            deleteQuietly(part);
            throw f;
        }
        if ("mismatch".equals(checksum.state())) {
            context.log("[resolve] warning: checksum mismatch for " + remoteUrl + " (" + checksum.algorithm()
                    + "); keeping the downloaded bytes because checksumPolicy=warn");
        } else if ("missing".equals(checksum.state())) {
            context.log("[resolve] warning: no checksum published for " + remoteUrl
                    + "; keeping the downloaded bytes because checksumPolicy=warn");
        }

        try {
            Files.move(part, local, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, local, StandardCopyOption.REPLACE_EXISTING);
        }

        String readback = digest(local, "SHA-1");
        if (!readback.equals(download.sha1())) {
            throw new Failure(ErrorType.PLUGIN, 40012,
                    "readback of " + local + " is " + readback + ", downloaded bytes were " + download.sha1());
        }

        Path target = null;
        if (copyToWorkspace) {
            String name = targetFileName.isEmpty() ? local.getFileName().toString() : targetFileName;
            target = targetDir.resolve(name).normalize();
            if (!target.startsWith(context.getWorkspace()) || target.equals(context.getWorkspace())) {
                throw new Failure(ErrorType.USER, 40010, "target path leaves the workspace: " + name);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(local, target, StandardCopyOption.REPLACE_EXISTING);
            String copied = digest(target, "SHA-1");
            if (!copied.equals(download.sha1())) {
                throw new Failure(ErrorType.PLUGIN, 40012,
                        "copy at " + target + " is " + copied + ", downloaded bytes were " + download.sha1());
            }
        }

        return new One(local, target, remoteUrl, download.bytes(), download.sha1(), version, snapshot, checksum.text());
    }

    // ------------------------------------------------------------------ coordinates and metadata

    private String pseudoVersion(HttpClient client, String base, String auth, Coord coord, Duration timeout)
            throws Failure, IOException, InterruptedException {
        String url = base + "/" + encodePath(coord.groupId().replace('.', '/')) + "/"
                + encode(coord.artifactId()) + "/" + METADATA;
        Document doc = metadata(client, url, auth, timeout);
        Element versioning = child(doc.getDocumentElement(), "versioning");
        if (versioning == null) {
            throw new Failure(ErrorType.THIRD_PART, 40007, "no <versioning> in " + url);
        }
        String declared = LATEST.equals(coord.version()) ? text(versioning, "latest") : text(versioning, "release");
        if (declared != null && !declared.isBlank()
                && !(RELEASE.equals(coord.version()) && declared.endsWith("-SNAPSHOT"))) {
            return declared.trim();
        }
        String fallback = lastListedVersion(versioning, LATEST.equals(coord.version()));
        if (fallback == null) {
            throw new Failure(ErrorType.THIRD_PART, 40007,
                    "no usable version for " + coord.version() + " in " + url);
        }
        return fallback;
    }

    private String lastListedVersion(Element versioning, boolean includeSnapshots) {
        NodeList versions = versioning.getElementsByTagName("version");
        String found = null;
        for (int i = 0; i < versions.getLength(); i++) {
            Node node = versions.item(i);
            if (node.getParentNode() == null || !"versions".equals(node.getParentNode().getNodeName())) {
                continue;
            }
            String value = node.getTextContent().trim();
            if (value.isEmpty() || (!includeSnapshots && value.endsWith("-SNAPSHOT"))) {
                continue;
            }
            found = value;
        }
        return found;
    }

    private Snapshot snapshotMetadata(
            HttpClient client, String base, String auth, Coord coord, String version, Duration timeout)
            throws Failure, IOException, InterruptedException {
        String url = base + "/" + encodePath(coord.groupId().replace('.', '/')) + "/"
                + encode(coord.artifactId()) + "/" + encode(version) + "/" + METADATA;
        Document doc = metadata(client, url, auth, timeout);
        Element versioning = child(doc.getDocumentElement(), "versioning");
        Element snapshot = versioning == null ? null : child(versioning, "snapshot");
        if (snapshot == null) {
            throw new Failure(ErrorType.THIRD_PART, 40007, "no <snapshot> in " + url);
        }
        String localCopy = text(snapshot, "localCopy");
        if ("true".equalsIgnoreCase(localCopy)) {
            return new Snapshot("", "", true);
        }
        String timestamp = text(snapshot, "timestamp");
        String buildNumber = text(snapshot, "buildNumber");
        if (timestamp == null || timestamp.isBlank() || buildNumber == null || buildNumber.isBlank()) {
            throw new Failure(ErrorType.THIRD_PART, 40007,
                    "snapshot metadata at " + url + " has no timestamp/buildNumber");
        }
        return new Snapshot(timestamp.trim(), buildNumber.trim(), false);
    }

    private Document metadata(HttpClient client, String url, String auth, Duration timeout)
            throws Failure, IOException, InterruptedException {
        Text response = text(client, URI.create(url), auth, timeout);
        if (response == null) {
            throw new Failure(ErrorType.THIRD_PART, 40007, "no " + METADATA + " at " + url);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new Failure(ErrorType.THIRD_PART, 40007, "cannot parse " + url + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ HTTP

    private Download download(HttpClient client, URI remote, String auth, Duration timeout, Path part)
            throws Failure, IOException, InterruptedException {
        URI origin = remote;
        URI current = remote;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<InputStream> response = sendStream(client, current, auth, origin, timeout);
            int status = response.statusCode();
            if (isRedirect(status)) {
                Optional<String> location = response.headers().firstValue("Location");
                closeQuietly(response);
                if (location.isEmpty()) {
                    throw new Failure(ErrorType.THIRD_PART, 40008,
                            "HTTP " + status + " without Location for " + current);
                }
                if (hop == MAX_REDIRECTS) {
                    throw new Failure(ErrorType.THIRD_PART, 40008,
                            "more than " + MAX_REDIRECTS + " redirects starting at " + remote);
                }
                current = current.resolve(location.get());
                continue;
            }
            checkStatus(status, current);
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            MessageDigest sha1 = sha1Digest();
            MessageDigest sha256 = sha256Digest();
            MessageDigest md5 = md5Digest();
            long bytes = 0;
            try (InputStream in = response.body(); var out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    sha1.update(buffer, 0, read);
                    sha256.update(buffer, 0, read);
                    md5.update(buffer, 0, read);
                    bytes += read;
                }
            }
            if (declared >= 0 && declared != bytes) {
                deleteQuietly(part);
                throw new Failure(ErrorType.THIRD_PART, 40008,
                        "truncated download of " + current + ": Content-Length " + declared + ", got " + bytes);
            }
            return new Download(bytes, hex(sha1.digest()), hex(sha256.digest()), hex(md5.digest()));
        }
        throw new Failure(ErrorType.THIRD_PART, 40008, "redirect loop for " + remote);
    }

    /** Returns {@code null} when the resource is absent (404). */
    private Text text(HttpClient client, URI url, String auth, Duration timeout)
            throws Failure, IOException, InterruptedException {
        URI current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = sendText(client, current, auth, url, timeout);
            int status = response.statusCode();
            if (isRedirect(status)) {
                Optional<String> location = response.headers().firstValue("Location");
                if (location.isEmpty()) {
                    throw new Failure(ErrorType.THIRD_PART, 40008,
                            "HTTP " + status + " without Location for " + current);
                }
                if (hop == MAX_REDIRECTS) {
                    throw new Failure(ErrorType.THIRD_PART, 40008,
                            "more than " + MAX_REDIRECTS + " redirects starting at " + url);
                }
                current = current.resolve(location.get());
                continue;
            }
            if (status == 404) {
                return null;
            }
            checkStatus(status, current);
            if (response.body().length() > MAX_METADATA_BYTES) {
                throw new Failure(ErrorType.THIRD_PART, 40007,
                        "metadata response from " + current + " is larger than " + MAX_METADATA_BYTES + " bytes");
            }
            return new Text(status, response.body());
        }
        throw new Failure(ErrorType.THIRD_PART, 40008, "redirect loop for " + url);
    }

    private Checksum verifyChecksum(
            HttpClient client, URI remote, String auth, String policy, Duration timeout, Download download)
            throws Failure, IOException, InterruptedException {
        if ("ignore".equals(policy)) {
            return new Checksum("ignored", "ignored");
        }
        String[][] candidates = {{"sha1", ".sha1", download.sha1()}, {"md5", ".md5", download.md5()},
                {"sha256", ".sha256", download.sha256()}};
        for (String[] candidate : candidates) {
            Text response = text(client, URI.create(remote + candidate[1]), auth, timeout);
            if (response == null) {
                continue;
            }
            String expected = firstToken(response.body());
            if (!expected.matches("(?i)[0-9a-f]{" + candidate[2].length() + "}")) {
                continue;
            }
            if (expected.equalsIgnoreCase(candidate[2])) {
                return new Checksum(candidate[0], "ok");
            }
            String message = "checksum mismatch for " + remote + ": " + candidate[0] + " says "
                    + expected + ", bytes are " + candidate[2];
            if ("fail".equals(policy)) {
                throw new Failure(ErrorType.THIRD_PART, 40006, message);
            }
            return new Checksum(candidate[0], "mismatch");
        }
        if ("fail".equals(policy)) {
            throw new Failure(ErrorType.THIRD_PART, 40005,
                    "no checksum published for " + remote + " (.sha1, .md5, .sha256 all absent)");
        }
        return new Checksum("none", "missing");
    }

    private HttpResponse<InputStream> sendStream(
            HttpClient client, URI uri, String auth, URI origin, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "jenkins-atom-maven-resolve")
                .GET();
        if (auth != null && sameOrigin(uri, origin)) {
            builder.header("Authorization", auth);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpResponse<String> sendText(
            HttpClient client, URI uri, String auth, URI origin, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "text/plain, application/xml, */*")
                .header("User-Agent", "jenkins-atom-maven-resolve")
                .GET();
        if (auth != null && sameOrigin(uri, origin)) {
            builder.header("Authorization", auth);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void checkStatus(int status, URI url) throws Failure {
        if (status == 401 || status == 403) {
            throw new Failure(ErrorType.USER, 40004,
                    "repository rejected the credentials (HTTP " + status + ") for " + url);
        }
        if (status == 404) {
            throw new Failure(ErrorType.THIRD_PART, 40003, "not found: " + url);
        }
        if (status < 200 || status >= 300) {
            throw new Failure(ErrorType.THIRD_PART, 40008, "HTTP " + status + " for " + url);
        }
    }

    // ------------------------------------------------------------------ paths and small helpers

    private Path path(AtomContext context, String raw, String name, boolean insideWorkspace) throws Failure {
        String value = raw == null ? "" : raw.trim();
        Path workspace = context.getWorkspace();
        if (value.isEmpty()) {
            return workspace;
        }
        if (value.startsWith("~")) {
            value = System.getProperty("user.home") + value.substring(1);
        }
        Path resolved = Path.of(value);
        if (!resolved.isAbsolute()) {
            resolved = workspace.resolve(resolved);
        }
        resolved = resolved.normalize();
        if (!resolved.isAbsolute()) {
            throw new Failure(ErrorType.USER, 40010, name + " is not a usable path: " + raw);
        }
        if (insideWorkspace && !(resolved.startsWith(workspace))) {
            throw new Failure(ErrorType.USER, 40010, name + " leaves the workspace: " + raw);
        }
        return resolved;
    }

    static boolean isPseudo(String version) {
        return LATEST.equals(version) || RELEASE.equals(version);
    }

    static final String LATEST = "LATEST";
    static final String RELEASE = "RELEASE";

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && b.getScheme() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && String.valueOf(a.getHost()).equalsIgnoreCase(String.valueOf(b.getHost()))
                && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String authorization(String username, String password, String token) {
        if (token != null && !token.isBlank()) {
            return "Bearer " + token;
        }
        if (username.isEmpty() && password.isEmpty()) {
            return null;
        }
        String pair = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripTrailingSlash(String url) {
        String value = url;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(encode(segment));
        }
        return out.toString();
    }

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstToken(String body) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            return "";
        }
        String[] parts = text.split("\\s+");
        String token = parts[0].trim();
        int slash = token.indexOf('/');
        return slash > 0 ? token.substring(0, slash) : token;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equals(element.getNodeName())) {
                return element;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : element.getTextContent().trim();
    }

    private static MessageDigest sha1Digest() {
        return digestOf("SHA-1");
    }

    private static MessageDigest sha256Digest() {
        return digestOf("SHA-256");
    }

    private static MessageDigest md5Digest() {
        return digestOf("MD5");
    }

    private static MessageDigest digestOf(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (Exception e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    private static String digest(Path file, String algorithm) throws IOException {
        MessageDigest digest = digestOf(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort: a leftover .part is never mistaken for the artifact
        }
    }

    private static void closeQuietly(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    // ------------------------------------------------------------------ value types

    record Coord(String groupId, String artifactId, String version, String extension, String classifier) {

        static Coord parse(String line) {
            String[] parts = line.split(":", -1);
            if (parts.length < 3 || parts.length > 5) {
                throw new IllegalArgumentException("bad coordinate '" + line
                        + "': expected groupId:artifactId:version[:extension[:classifier]]");
            }
            for (int i = 0; i < 3; i++) {
                if (parts[i].isBlank()) {
                    throw new IllegalArgumentException("bad coordinate '" + line + "': empty field " + (i + 1));
                }
            }
            String extension = parts.length > 3 && !parts[3].isBlank() ? parts[3].trim() : "jar";
            String classifier = parts.length > 4 && !parts[4].isBlank() ? parts[4].trim() : "";
            for (String value : new String[] {parts[0], parts[1], parts[2], extension, classifier}) {
                if (value.matches(".*[\\s/:].*")) {
                    throw new IllegalArgumentException("bad coordinate '" + line + "': illegal character in '" + value + "'");
                }
            }
            return new Coord(parts[0].trim(), parts[1].trim(), parts[2].trim(), extension, classifier);
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version
                    + ("jar".equals(extension) ? "" : ":" + extension)
                    + (classifier.isEmpty() ? "" : ":" + classifier);
        }
    }

    record Snapshot(String timestamp, String buildNumber, boolean localCopy) {}

    record Download(long bytes, String sha1, String sha256, String md5) {}

    record Text(int status, String body) {}

    record Checksum(String algorithm, String state) {
        String text() {
            return algorithm + "=" + state;
        }
    }

    record One(Path local, Path target, String url, long bytes, String sha1, String version,
            boolean snapshot, String checksum) {}

    static final class Failure extends Exception {
        final ErrorType type;
        final int code;

        Failure(ErrorType type, int code, String message) {
            super(message);
            this.type = type;
            this.code = code;
        }
    }
}
