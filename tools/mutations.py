#!/usr/bin/env python3
"""Plant one bug at a time in the atom and prove the checks catch it.

Each mutation is one textual change to the real source, rebuilt into its own jar in a throwaway copy; the named check
must then go red. A survivor is a hole in the suite.

    python3 tools/mutations.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
CODE = "maven-resolve"
ATOM = HERE / "src" / "main" / "java" / "com" / "jenkinschina" / "atom" / "mavenresolve" / "MavenResolveAtom.java"

MUTATIONS = [
    ("the group id is not turned into directories", ATOM,
     "String groupPath = coord.groupId().replace('.', '/');", "String groupPath = coord.groupId();",
     "release.resolves"),
    ("the classifier is dropped from the file name", ATOM,
     'String suffix = coord.classifier().isEmpty() ? "" : "-" + coord.classifier();',
     'String suffix = "";', "classifier.resolves"),
    ("the default packaging is pom", ATOM,
     'String extension = parts.length > 3 && !parts[3].isBlank() ? parts[3].trim() : "jar";',
     'String extension = parts.length > 3 && !parts[3].isBlank() ? parts[3].trim() : "pom";',
     "release.resolves"),
    ("the checksum is compared with the digest of nothing", ATOM,
     '{"sha1", ".sha1", download.sha1()},',
     '{"sha1", ".sha1", "da39a3ee5e6b4b0d3255bfef95601890afd80709"},', "release.resolves"),
    ("a checksum mismatch passes under the fail policy", ATOM,
     'if ("fail".equals(policy)) {\n                throw new Failure(ErrorType.THIRD_PART, 40006, message);',
     'if (false) {\n                throw new Failure(ErrorType.THIRD_PART, 40006, message);',
     "checksum.mismatch.fails"),
    ("a missing checksum passes under the fail policy", ATOM,
     'if ("fail".equals(policy)) {\n            throw new Failure(ErrorType.THIRD_PART, 40005,',
     'if (false) {\n            throw new Failure(ErrorType.THIRD_PART, 40005,',
     "checksum.missing.fails"),
    ("the ignore policy still fetches checksums", ATOM,
     'if ("ignore".equals(policy)) {\n            return new Checksum("ignored", "ignored");\n        }',
     'if (false) {\n            return new Checksum("ignored", "ignored");\n        }',
     "checksum.missing.ignored"),
    ("the md5 checksum is never asked for", ATOM,
     '{"md5", ".md5", download.md5()},', '{"md5", ".md5.none", download.md5()},',
     "checksum.md5.fallback"),
    ("a 404 is reported as a server error", ATOM,
     'throw new Failure(ErrorType.THIRD_PART, 40003, "not found: " + url);',
     'throw new Failure(ErrorType.THIRD_PART, 40008, "not found: " + url);', "not.found"),
    ("credentials are sent across a redirect to another host", ATOM,
     "if (auth != null && sameOrigin(uri, origin)) {", "if (auth != null) {",
     "redirect.cross.host.strips.auth"),
    ("redirects are not followed", ATOM,
     "private static boolean isRedirect(int status) {", "private static boolean isRedirect(int status) {\n        if (true) {\n            return false;\n        }",
     "redirect.followed"),
    ("a snapshot downloads the -SNAPSHOT name instead of the timestamped one", ATOM,
     '''            fileName = snapshotMeta.localCopy()
                    ? coord.artifactId() + "-" + version + suffix + "." + extension
                    : coord.artifactId() + "-" + baseVersion + "-" + snapshotMeta.timestamp()
                            + "-" + snapshotMeta.buildNumber() + suffix + "." + extension;''',
     '''            fileName = coord.artifactId() + "-" + version + suffix + "." + extension;''',
     "snapshot.explicit"),
    ("the local snapshot keeps the timestamped name", ATOM,
     'String localName = coord.artifactId() + "-" + version + suffix + "." + extension;',
     "String localName = fileName;", "snapshot.explicit"),
    ("RELEASE reads the latest pointer", ATOM,
     'String declared = LATEST.equals(coord.version()) ? text(versioning, "latest") : text(versioning, "release");',
     'String declared = text(versioning, "latest");', "pseudo.release.pointer"),
    ("LATEST reads the release pointer", ATOM,
     'String declared = LATEST.equals(coord.version()) ? text(versioning, "latest") : text(versioning, "release");',
     'String declared = text(versioning, "release");', "pseudo.latest.snapshot"),
    ("an older file in the target directory is kept", ATOM,
     "Files.copy(local, target, StandardCopyOption.REPLACE_EXISTING);", "Files.copy(local, target);",
     "target.rename"),
    ("a failed checksum leaves its partial download behind", ATOM,
     '''        } catch (Failure f) {
            deleteQuietly(part);
            throw f;
        }
        if ("mismatch".equals(checksum.state())) {''',
     '''        } catch (Failure f) {
            throw f;
        }
        if ("mismatch".equals(checksum.state())) {''',
     "checksum.mismatch.fails"),
    ("the bytes are not what the checksum covered", ATOM,
     "out.write(buffer, 0, read);", "out.write(new byte[read]);", "release.resolves"),
    ("basic credentials are hex instead of base64", ATOM,
     'return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));',
     'return "Basic " + java.util.HexFormat.of().formatHex(pair.getBytes(StandardCharsets.UTF_8));',
     "auth.sent"),
    ("failOnError=false still stops at the first failure", ATOM,
     "if (failOnError) {\n                    return AtomResult.error(f.type, f.code, f.getMessage());",
     "if (true) {\n                    return AtomResult.error(f.type, f.code, f.getMessage());",
     "fail.on.error.false"),
    ("bytes counts the URL length", ATOM, "bytes += one.bytes();", "bytes += one.url().length();",
     "multiple.resolved"),
    ("targetFileName is allowed with several coordinates", ATOM,
     "if (!targetFileName.isEmpty() && coords.size() > 1) {", "if (false) {", "target.multi.refused"),
    ("a target path escapes the workspace", ATOM,
     "if (!target.startsWith(context.getWorkspace()) || target.equals(context.getWorkspace())) {",
     "if (false) {", "target.escape.refused"),
    ("snapshot metadata is fetched outside the version directory", ATOM,
     '+ encode(coord.artifactId()) + "/" + encode(version) + "/" + METADATA;',
     '+ encode(coord.artifactId()) + "/" + METADATA;', "snapshot.explicit"),
]


def sdk_jar():
    env = os.environ.get("SDK_JAR")
    if env:
        return env
    for root in (HERE.parent / "jenkins-atom-sdk", HERE / "jenkins-atom-sdk"):
        candidates = sorted((root / "target").glob("jenkins-atom-sdk-*.jar"))
        if candidates:
            return str(candidates[-1])
    raise SystemExit("build the SDK first: cd ../jenkins-atom-sdk && ./build.sh")


def build(tree):
    env = dict(os.environ)
    env["SDK_JAR"] = sdk_jar()
    proc = subprocess.run(["bash", "build.sh"], cwd=tree, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        return None, proc.stderr[-1200:]
    return tree / "dist" / CODE / (CODE + ".jar"), proc.stdout


def main():
    failures = 0
    for index, (name, source_file, old, new, case) in enumerate(MUTATIONS, 1):
        tree = pathlib.Path(tempfile.mkdtemp(prefix="mvn-resolve-mut-")) / "atom"
        shutil.copytree(HERE, tree, ignore=shutil.ignore_patterns("dist", ".git", "__pycache__"))
        source = tree / source_file.relative_to(HERE)
        text = source.read_text(encoding="utf-8")
        if old not in text:
            print("SKIP  %-2d %s: the mutation no longer matches the source" % (index, name))
            failures += 1
            continue
        source.write_text(text.replace(old, new, 1), encoding="utf-8")
        jar, output = build(tree)
        if jar is None:
            print("SKIP  %-2d %s: the mutated atom does not build: %s" % (index, name, output))
            failures += 1
            continue
        proc = subprocess.run([sys.executable, "tools/check.py", case, "--jar", str(jar)],
                              cwd=tree, capture_output=True, text=True)
        if proc.returncode == 0:
            print("SURVIVED %-2d %s (case '%s' stayed green)" % (index, name, case))
            failures += 1
        else:
            first = [line for line in (proc.stdout + proc.stderr).splitlines() if line.startswith("FAIL")][:1]
            print("caught   %-2d %s%s" % (index, name, ("  <- " + first[0][:110]) if first else ""))
    print("\n%d/%d mutations caught" % (len(MUTATIONS) - failures, len(MUTATIONS)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
