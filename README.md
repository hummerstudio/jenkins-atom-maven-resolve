# jenkins-atom-maven-resolve

按 GAV 从 Maven 仓库下载构件到工作区。这是 **repository-connector** 的上传侧之外的那一半：它的
`ArtifactResolver`（下载）移植为原子，`ArtifactDeployer`（上传）已由 `artifact-publish` 覆盖，这里不再重复。

## 证据来源（不是凭记忆）

插件包：`curl -L -o rc.hpi https://updates.jenkins.io/latest/repository-connector.hpi`（3.3 MB，
`Implementation-Version: 2.2.1`），解包后看 `WEB-INF/lib/repository-connector.jar` 的字节码：

```
javap -p org.jvnet.hudson.plugins.repositoryconnector.Artifact
  private java.lang.String artifactId;
  private java.lang.String classifier;
  private java.lang.String extension;
  private boolean failOnError;
  private java.lang.String groupId;
  private java.lang.String targetFileName;
  private java.lang.String version;
  (deployToLocal / deployToRemote / pomFile 属于上传侧)

javap -p org.jvnet.hudson.plugins.repositoryconnector.ArtifactResolver
  private java.lang.String repositoryId;
  private java.lang.String targetDirectory;
  private java.util.List<...Artifact> artifacts;
  ArtifactResolver.download(...): Aether.resolve(id, artifact) -> FilePath.copyTo(targetDirectory, targetFileName ?: 原名)
  捕获 AetherException 时：isFailOnError() 为真则抛出，否则打印 "Failed to resolve %s - %s"

javap -c ...aether.Aether#createResolvableArtifact
  new DefaultArtifact(getGroupId(), getArtifactId(), getClassifier(), extension ?: "jar", getVersion())

javap -c '...internal.impl.Maven2RepositoryLayoutFactory$Maven2RepositoryLayout'#getLocation
  getGroupId().replace('.', '/') + "/" + getArtifactId() + "/" + getBaseVersion() + "/"
    + getArtifactId() + "-" + getVersion() + (classifier.isEmpty() ? "" : "-" + classifier)
    + (extension.isEmpty() ? "" : "." + extension)
```

也就是 URL 布局 `<repo>/<groupId 点换斜杠>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<extension>`，
本原子的 `download()` 就按这条规则拼，`MavenResolveIT` 用假仓库的请求日志断言请求路径逐字节相同。

## 入参与出参

入参：`repoUrl`、`coordinates`（每行 `groupId:artifactId:version[:extension[:classifier]]`，版本可写
`RELEASE`/`LATEST`）、`username`、`password`、`localRepository`（默认 `.m2/repository`）、`targetDirectory`、
`targetFileName`、`checksumPolicy`（`fail`/`warn`/`ignore`）、`failOnError`、`timeoutSeconds`。
凭据也可以从 `x-jenkins.secrets` 绑定的同名凭据取（字段留空时生效）。

出参：`resolved`、`failed`、`snapshots`、`bytes`、`files`、`targets`、`urls`、`versions`、`checksums`、`first`、
`errors`。出参名与入参名不重名、也不只差大小写（runner 会把上一步的出参并入下一步的入参）。

## 与插件一致的地方

- 一个坐标一个构件，`extension` 默认 `jar`，`classifier` 可为空；目录与文件名按上面的布局。
- `targetDirectory` 默认工作区根；`targetFileName` 改副本的名字，可以带子目录；未设置时沿用本地仓库里的文件名。
- `RELEASE` / `LATEST` 通过 `maven-metadata.xml` 的 `<release>` / `<latest>` 解析，取不到时回落到 `<versions>` 列表。
- 快照按时间戳名从远端取（`artifactId-baseVersion-timestamp-buildNumber[-classifier].ext`），本地按
  `-SNAPSHOT` 名存放；版本级 `maven-metadata.xml` 里 `<localCopy>true</localCopy>` 表示远端没有时间戳名。
- `failOnError=false` 时失败的坐标记进 `errors` 继续下一个，和插件 `download()` 里打印
  `Failed to resolve %s - %s` 的行为一致。
- 凭据用 Basic 认证，只发给与原始仓库同 scheme/host/port 的请求。

## 与插件不同的地方（刻意，且被集成测试断言）

1. **不用 Aether，不读缓存**：每次都从仓库取，本地仓库只是落盘的布局。`localRepository` 默认放在工作区
   （`<workspace>/.m2/repository`），要共享 Maven 的缓存就写 `~/.m2/repository`。
2. **校验默认 `fail`**：插件继承 `AetherConstants.DEFAULT_CHECKSUM = "warn"`，而一个"把错字节留在工作区"的
   下载步是静默的错误。需要插件行为时设 `checksumPolicy=warn`：仍然校验、仍然在 `checksums` 里报
   `mismatch`，只是保留字节。
3. **下载后回读**：写入 `.part` 再原子改名，改名前和改名后各算一次 SHA-1 与下载字节比对（40012）；工作区副本
   同样回读。失败不会留下 `.part`，也不会覆盖已有目标。
4. **HTTP 结果要检查**：404 是 40003 并带上 URL；401/403 是 40004（用户侧）；其他非 2xx 是 40008；
   `Content-Length` 与实际字节数不符是 40008 的 "truncated download"，不会变成一个短文件。
5. **重定向自己跟**（最多 5 跳），一旦离开原始 scheme/host/port 就丢掉 `Authorization`。
6. **`targetFileName` 配多个坐标直接拒绝**（40009），不把所有构件改成同一个名字；目标跑出工作区拒绝（40010）。
7. **一个 `repoUrl` 一步**：插件的 `repositoryId` 选择器和全局仓库列表是 job 级配置，这里不做多仓库回退，
   需要回退就多跑一步。

## 错误码

| 码 | 含义 | errorType |
| --- | --- | --- |
| 40001 | 入参不合法（坐标语法、用户名密码不配对、checksumPolicy 未知） | 1 USER |
| 40002 | `repoUrl` 缺失或不是 http(s) | 1 USER |
| 40003 | 构件不存在（HTTP 404），消息里带 URL | 2 THIRD_PART |
| 40004 | 仓库拒绝凭据（HTTP 401/403） | 1 USER |
| 40005 | 仓库没有发布任何校验和，且策略为 fail | 2 THIRD_PART |
| 40006 | 校验和与字节不符，且策略为 fail | 2 THIRD_PART |
| 40007 | `maven-metadata.xml` 缺失/无法解析/没有可用版本 | 2 THIRD_PART |
| 40008 | 其他 HTTP 错误、传输失败、短读、重定向异常 | 2 THIRD_PART |
| 40009 | `targetFileName` 与多个坐标冲突 | 1 USER |
| 40010 | 目标路径跑出工作区 | 1 USER |
| 40012 | 落盘后回读的 SHA-1 与下载字节不符 | 3 PLUGIN |
| 40013 | 请求超时 | 2 THIRD_PART |

## 构建与测试

```bash
mvn verify            # 需要 ../jenkins-atom-sdk/target/jenkins-atom-sdk-*.jar；构建 + JUnit 集成测试
```

`MavenResolveIT` 用 `tools/fake_maven_repo.py` 把一棵按 Maven 2 布局摆好的目录树用 HTTP 提供出去，并记录每个
请求的路径/Authorization；断言用的期望值（路径、字节、SHA-1、Basic 头）都在测试里自己算，不读原子的输出。
覆盖：布局路径与请求头、下载字节与工作区副本、`.sha1`/`.sha256` 校验与 `fail`/`warn`/`ignore`、classifier 与
extension、`targetFileName`（含子目录与越界）、404、401+Basic（含 `.secrets.json` 绑定与密码不外泄）、
`failOnError=false`、超时、短读、坏入参。
