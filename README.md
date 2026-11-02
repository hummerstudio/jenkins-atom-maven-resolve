# jenkins-atom-maven-resolve

Resolve Maven artifacts by coordinate -- port of the resolve half of **repository-connector** (2,017 installs,
plugin 3.3.0, built 2023-03-15) as an atom.

The plugin is two halves in one artifact: `ArtifactDeployer` uploads (already covered by `artifact-publish`) and
`ArtifactResolver` downloads. Only `ArtifactResolver` is ported here. It is a `Builder` + `SimpleBuildStep` whose
`download()` is: ask Aether for one artifact, then copy the resolved file into the workspace under
`targetDirectory` / `targetFileName` (or the file's own name).

## What the atom copies from the plugin

Read out of `ArtifactResolver`, `Aether.resolve` and `AetherConstants` (javap, plugin 3.3.0):

- **one artifact per resolved coordinate**: `new DefaultArtifact(groupId, artifactId, classifier, extension, version)`,
  with `extension` defaulting to `"jar"` and `classifier` possibly empty. The UI fields are groupId, artifactId,
  version, optional extension, optional classifier.
- **`targetDirectory`** defaults to the workspace root; **`targetFileName`** replaces the artifact's file name and may
  contain sub-directories (`new FilePath(targetDir, targetFileName)`); an existing target is deleted before the copy.
  With neither set, the artifact keeps the name it had in the local repository.
- **RELEASE / LATEST** pseudo-versions, resolved through the artifact's `maven-metadata.xml` (`<release>` and
  `<latest>`, with the versions list as the fallback Aether uses).
- **snapshots** are fetched under their timestamped remote name
  (`artifactId-baseVersion-YYYYMMDD.HHmmss-buildNumber[-classifier].extension`) and stored locally under the
  `-SNAPSHOT` name, which is what a later step expects to find. `<localCopy>true</localCopy>` in the version-level
  metadata means "no timestamped name".
- **checksum policy** default `warn` (`AetherConstants.DEFAULT_CHECKSUM`); the alternatives are `fail` and `ignore`.
  The plugin inherits Aether's policy, which checks `.sha1` and falls back to `.md5`.
- **`failOnError=false`** on one artifact logs `Failed to resolve %s - %s` and lets the build continue; the rest of the
  artifacts are still attempted.
- **basic credentials** per repository, attached to the request by Aether's `Authentication`.

## Differences (deliberate, all asserted in `tools/check.py`)

1. **No Aether, no cache.** Aether keeps a `daily` update policy and can answer from the local repository without a
   request. This atom always fetches from the repository and writes the local repository as a layout, so the bytes in
   the build are always the bytes the server has now. `localRepository` defaults to `<workspace>/.m2/repository`
   instead of the agent's global `~/.m2/repository`; set it to `~/.m2/repository` to share Maven's cache.
2. **Every download is verified twice.** The checksum is compared with the bytes as they arrive, the file is written
   through a `.part` file and moved into place atomically, and then the file on disk is read back and hashed again
   (40012 if it differs). The workspace copy is hashed as well. A failed download or checksum never leaves a partial
   file and never replaces an existing target.
3. **Checksum mismatches are never silent.** Under `warn` the bytes are kept (Aether's behaviour) but the mismatch is
   logged with both digests and reported in the `checksums` output; under `fail` it is 40006. When no checksum is
   published: `fail` -> 40005, `warn` -> a warning and `none=missing`.
4. **The HTTP answer is checked, not assumed.** 404 is 40003 and names the URL; 401/403 is 40004; other non-2xx is
   40008. A `Content-Length` that disagrees with the received byte count is 40008 rather than a short file.
5. **Redirects are followed by hand** (max 5, 40008 beyond), and `Authorization` is dropped as soon as the redirect
   leaves the original scheme/host/port. The JDK client would carry the header to the new host.
6. **`targetFileName` with more than one coordinate is refused** (40009) instead of giving every artifact the same
   name; a target that leaves the workspace is refused (40010).
7. **A coordinate that fails before any request** (bad syntax, empty field, illegal character) is 40001 with the line
   quoted; the plugin's form validates some of this, its model does not.
8. **Multi-repository selection is not carried over.** One `repoUrl` per step; run the step twice for a fallback
   repository. The plugin's `repositoryId` selector and its global repository list are job-level configuration.
9. **`failOnError` is one switch for the step**, not one per artifact: `failed` and `errors` are outputs. Per-artifact
   toggles need a list model the atom input format does not have.

Outputs: `resolved`, `failed`, `snapshots`, `bytes`, `files`, `targets`, `urls`, `versions`, `checksums`, `first`,
`errors`.

## Build and test

```bash
./build.sh                 # needs ../jenkins-atom-sdk/target/jenkins-atom-sdk-*.jar
./tools/smoke.sh           # real python http.server: resolve + sha1 + copy, then 404 -> 40003
python3 tools/check.py     # 28 behaviour checks against a fake repository
python3 tools/mutations.py # 24 planted bugs, each must turn a named check red
```

`tools/check.py`'s fake repository serves fixture bytes and computes its own digests, records every request line and
header, and can redirect (same host, another host, a loop), fail with 500, cut a body short, or require basic auth.
The checks recompute the expected repository path, the expected sha1 and the expected basic-auth header themselves
before comparing them with what the atom wrote to disk and asked for.
