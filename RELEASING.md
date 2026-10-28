# Releasing

A release publishes the engine to Maven Central, where a ScalarDB Cluster node depends on it.

## What gets published

| Artifact | Coordinate | Consumed by |
| --- | --- | --- |
| Engine | `com.scalar-labs:scalardb-postgres-frontend-engine` | ScalarDB Cluster nodes, which embed it |

With its sources and javadoc jars, under the Apache License 2.0 (`LICENSE`, also inside each jar).
`:server`, the standalone server, is not published to Maven Central: it ships as the fat jar and the
container image, and it compiles against the Cluster client SDK of the same version, which Cluster
releases after the engine it embeds.

Every commit on `main` and on a version branch also publishes the engine's `-SNAPSHOT` to the Maven
Central snapshot repository (`release-snapshot.yml`), so a Cluster build can track a line between
releases:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
dependencies {
    implementation("com.scalar-labs:scalardb-postgres-frontend-engine:3.20.0-SNAPSHOT")
}
```

## Branching model

The same as [ScalarDB](https://github.com/scalar-labs/scalardb): `main` is the trunk, a major version
branch carries the next minor release of an older major, and a release is tagged on its minor version
branch, never on `main`. The engine's version follows the ScalarDB version it is built against.

Once `3` and `3.20` are cut (below), the branches are:

| Branch | Carries | ScalarDB it builds against |
| --- | --- | --- |
| `main` | `4.0.0-SNAPSHOT` | `master`, `4.0.0-SNAPSHOT` |
| `3` | `3.21.0-SNAPSHOT` | `3`, the next 3.x |
| `3.20` | `3.20.1-SNAPSHOT` after `v3.20.0` | `3.20.0` |

Until then, only `main` exists, at `3.20.0-SNAPSHOT`.

Branch names carry no prefix. Every workflow matches them with the `[0-9]+` and `[0-9]+.[0-9]+`
patterns, so a branch such as `release/3.20` gets no CI and publishes no snapshot.

A change lands on `main` first and is then cherry-picked to the version branches that need it.
`main` is not merged into `3` once `main` has moved to the next major, since that would bring 4.0-only
changes into the 3.x line.

### Cutting a branch

A branch and its parent must never carry the same version: both would publish the same snapshot, and
`release-snapshot.yml` cannot tell which one is right. So the parent is bumped first, and the branch is
cut from the commit just before the bump.

To cut `3` from `main`, the first time:

1. On `main`, at `3.20.0-SNAPSHOT`: note the head commit, `C`.
2. On `main`: bump `version` in `gradle.properties` to `4.0.0-SNAPSHOT`, and `scalardb` and
   `scalardb-cluster` in `gradle/libs.versions.toml` to `4.0.0-SNAPSHOT`. Push.
3. Create `3` from `C` and push it. It publishes `3.20.0-SNAPSHOT`.

To cut `3.20` from `3`, when ScalarDB cuts its 3.20 line:

1. On `3`, at `3.20.0-SNAPSHOT`: note the head commit, `C`.
2. On `3`: bump `version` to `3.21.0-SNAPSHOT`, and the ScalarDB versions to what ScalarDB's `3`
   branch publishes. Push.
3. Create `3.20` from `C` and push it. It publishes `3.20.0-SNAPSHOT`.

## Cutting a release

`release.yml` runs on a `v<major>.<minor>.<patch>` tag. It refuses a tag that does not match
`gradle.properties`, a `-SNAPSHOT` version, and a commit that is not on the tag's minor version branch
(`3.20` for `v3.20.0`). It then uploads the engine to the Central Portal for someone to release.

For `3.20.0`, on `3.20`:

1. Wait for ScalarDB `3.20.0` on Maven Central: the engine's POM depends on it.
2. Make the release commit: `version=3.20.0` in `gradle.properties`, `scalardb = "3.20.0"` in
   `gradle/libs.versions.toml` (`scalardb-cluster` stays a snapshot until Cluster releases), and the
   `Unreleased` heading of `RELEASE-NOTES.md` renamed to `3.20.0`. Push it and wait for CI.
3. Tag that commit and push the tag:

   ```sh
   git tag -a v3.20.0 -m "ScalarDB PostgreSQL frontend 3.20.0"
   git push origin v3.20.0
   ```

4. When `release.yml` has finished, open the deployment on the
   [Central Portal](https://central.sonatype.com/publishing/deployments). Once it has passed
   validation, check the files and select **Publish**. A published version cannot be replaced or
   withdrawn. It takes a while to appear on `repo1.maven.org`.
5. On `3.20`, bump `version` to `3.20.1-SNAPSHOT` and push, so that later snapshots from the branch do
   not reuse the released version.
6. In ScalarDB Cluster, change the engine dependency to `3.20.0`.

To re-run a release whose tag exists, for example after the upload failed, run `release.yml` by
`workflow_dispatch` with the tag selected as the ref.

A patch release is the same on the same branch: cherry-pick the fixes to `3.20`, then follow the steps
from 2 with `3.20.1`.

## Credentials

The workflows read four repository secrets, the same ones ScalarDB and ScalarDB Cluster use:

| Secret | What it is |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | A Central Portal user token for the `com.scalar-labs` namespace |
| `MAVEN_CENTRAL_GPG_SECRET_KEY` | The ASCII-armored private key that signs the artifacts |
| `MAVEN_CENTRAL_GPG_PASSPHRASE` | Its passphrase |

A local `./gradlew publishToMavenLocal` needs none of them and publishes unsigned.
