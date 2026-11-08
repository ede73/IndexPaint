# Builds and releases

IndexPaint has two separate workflows. Pull requests get an unsigned GitHub hosted smoke build. Signed builds and Play uploads are manually started from the private `ede73/internal-testing-only` repository, whose Mac mini runner and release secrets are not available to this public repository.

## Pull request CI

Every pull request, including drafts, runs `.github/workflows/android-pr-ci.yml` on a GitHub hosted Ubuntu runner. It builds the unsigned debug app, runs unit tests, and runs instrumentation tests on an Android emulator. It has no signing secrets and cannot schedule the Mac mini. Android lint can be added after the current lint errors are addressed.

## Signed build and Internal testing operations

Open **Actions → IndexPaint signed release** in the private [internal-testing-only repository](https://github.com/ede73/internal-testing-only), select `main`, and choose one operation:

- `build_signed`: build and verify a signed AAB, then keep it as a downloadable workflow artifact.
- `build_and_publish_internal`: build a new signed AAB and publish it to the Internal testing track.
- `publish_existing_internal`: publish the exact AAB from a previous workflow run without rebuilding it. Supply its run ID, repository, and matching source ref.

Set `source_ref` to `main`, a branch or tag, a commit SHA, or `refs/pull/NUMBER/head` to build a draft or open PR. The workflow records the resolved source SHA and the AAB SHA-256 beside the AAB. Artifacts are retained for 90 days.

Only `ede73` can run the private release workflow. It has no automatic triggers. PRs, pushes, merges, and normal CI never start signed builds or Play uploads.

### Start operations with `gh`

```sh
# Signed AAB from current main
gh workflow run indexpaint-release.yml --repo ede73/internal-testing-only --ref main \
  -f operation=build_signed -f source_ref=main

# Signed AAB from draft PR 12
gh workflow run indexpaint-release.yml --repo ede73/internal-testing-only --ref main \
  -f operation=build_signed -f source_ref=refs/pull/12/head

# Build and publish that PR to Internal testing
gh workflow run indexpaint-release.yml --repo ede73/internal-testing-only --ref main \
  -f operation=build_and_publish_internal -f source_ref=refs/pull/12/head

# Publish a previously built AAB without rebuilding; use its controller run ID
gh workflow run indexpaint-release.yml --repo ede73/internal-testing-only --ref main \
  -f operation=publish_existing_internal -f source_ref=refs/pull/12/head \
  -f artifact_run_id=RUN_ID

# Publish an artifact from an old run in the public IndexPaint repository
gh workflow run indexpaint-release.yml --repo ede73/internal-testing-only --ref main -f operation=publish_existing_internal -f source_ref=SOURCE_SHA -f artifact_repository=ede73/IndexPaint -f artifact_run_id=RUN_ID
```

Check or download the retained artifact:

```sh
gh run list --repo ede73/internal-testing-only --workflow indexpaint-release.yml --limit 10
gh run watch RUN_ID --repo ede73/internal-testing-only
gh run download RUN_ID --repo ede73/internal-testing-only --name indexpaint-release-aab
```

For an artifact from the old public workflow, inspect its exact source commit with `gh run view RUN_ID --repo ede73/IndexPaint --json headSha,headBranch`, then use that SHA as `source_ref`. Set `artifact_repository=ede73/IndexPaint` and use the old run ID. The private controller needs a fine-grained GitHub token with **Actions: read** access to `ede73/IndexPaint` to download artifacts from that other repository. Private-controller artifacts do not need this token.

`publish_existing_internal` rejects an artifact unless its recorded provenance or source run metadata matches the selected source SHA. It checks the AAB signature against the upload keystore and uses the version code inside that AAB. A new signed build asks Gradle Play Publisher for the next version code from Google Play, so it needs the Play credential even when that build is not published yet.

## One time setup

The Mac mini runner is registered only to the private `internal-testing-only` repository. IndexPaint's public repo has no self-hosted runner. Public PR CI runs on GitHub hosted machines.

Set the three signing secrets in the private controller repository from the unchanged local upload keystore:

```sh
./scripts/update-github-signing-secrets.sh --repo ede73/internal-testing-only [path/to/original-upload.jks]
```

The script sets these repository Actions secrets:

- `INDEXPAINT_UPLOAD_KEYSTORE_BASE64`
- `INDEXPAINT_KEYSTORE_PASSWORD`
- `INDEXPAINT_KEY_PASSWORD`

It also sets the `INDEXPAINT_KEY_ALIAS` and `INDEXPAINT_KEYSTORE_TYPE` repository variables. It re-encrypts a temporary copy of the local JKS, verifies the copy and certificate, and leaves the source keystore unchanged.

Publishing also needs the `ANDROID_PUBLISHER_CREDENTIALS` repository secret in `internal-testing-only`. It contains the Google Play service-account JSON. The service account should have access to release IndexPaint to testing tracks, without Production release or account administration permissions. Add it with:

```sh
gh secret set ANDROID_PUBLISHER_CREDENTIALS --repo ede73/internal-testing-only < /path/to/service-account.json
```

To publish an AAB from an old public-repository run, create a fine-grained token with **Actions: read** on `ede73/IndexPaint`, then add it as `INDEXPAINT_ARTIFACT_READ_TOKEN` in the private controller:

```sh
gh secret set INDEXPAINT_ARTIFACT_READ_TOKEN --repo ede73/internal-testing-only
```

See Google's [API setup guide](https://developers.google.com/android-publisher/getting_started) and [Play Console permission reference](https://support.google.com/googleplay/android-developer/answer/9844686?hl=en).

## What this workflow does not do

- It never publishes to Production.
- It does not automatically build or publish on any Git event.
- A workflow artifact is available for 90 days; keep a separate copy if you need longer-term AAB storage.
- A Play upload to the `internal` track is completed for testers. This workflow does not create a Play draft release or promote an Internal release to Production.
