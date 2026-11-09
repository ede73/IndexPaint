# IndexPaint build and release guide

## Publish a source to Internal testing

Each command below starts one Mac runner job that builds, signs, verifies, uploads, and commits the AAB to the Internal testing track. No separate build and publish commands or run ID are needed. The workflow ref (`--ref`) selects the workflow version; `source_ref` selects the app code to publish. After PR #5 is merged, use `--ref main` as shown. While it is still open, use `--ref codex/release-lint-fix`.

Publish whatever `main` points to when the runner checks it out:

```sh
gh workflow run android-release.yml --repo ede73/IndexPaint --ref main -f operation=build_and_publish_internal -f source_ref=main
```

Pin the run to the exact `origin/main` commit resolved by GitHub when you issue the command:

```sh
gh workflow run android-release.yml --repo ede73/IndexPaint --ref main -f operation=build_and_publish_internal -f source_ref="$(gh api repos/ede73/IndexPaint/git/ref/heads/main --jq .object.sha)"
```

Publish the head of a pull request, including a draft PR (this example uses PR #5):

```sh
gh workflow run android-release.yml --repo ede73/IndexPaint --ref main -f operation=build_and_publish_internal -f source_ref=refs/pull/5/head
```

Publish a branch (this example uses the tested release branch):

```sh
gh workflow run android-release.yml --repo ede73/IndexPaint --ref main -f operation=build_and_publish_internal -f source_ref=codex/release-lint-fix
```

To publish an exact commit, put its full or abbreviated Git SHA after `source_ref=`. The run records the resolved source commit and AAB SHA-256 in its artifact provenance.

If you prefer the GitHub website, open **Actions → Android Release → Run workflow**, choose `main` as the workflow branch, choose `build_and_publish_internal`, and enter the source ref (`main`, a commit SHA, branch, or `refs/pull/NUMBER/head`). Then click **Run workflow**. This one run builds and publishes to Internal testing.

The rest of this guide covers build-only runs, publishing an already-built AAB, and setup details.

## What runs automatically

Pull requests, including draft pull requests, run **Android CI** on GitHub-hosted Ubuntu. It builds the unsigned debug app and runs unit and Android emulator tests. It does not sign an AAB or publish to Google Play.

Signed builds and Internal testing uploads use the manually started **Android Release** workflow on the Mac mini runner. It has no push or pull-request trigger. Its release job checks that both the workflow actor and the person who started or reran it are `ede73`.

## Choose an operation

| Operation | Result |
| --- | --- |
| `verify_signing_inputs` | Checks the configured upload keystore and signing certificate. |
| `build_signed` | Builds and verifies a signed AAB, then saves it as the `indexpaint-release-aab` workflow artifact. Does not upload to Play. |
| `build_and_publish_internal` | Builds and verifies a signed AAB, then uploads and commits that AAB to the Google Play **Internal testing** track. |
| `publish_existing_internal` | Uploads an AAB from an earlier workflow run and commits it to Internal testing. Does not rebuild it. Requires that run's ID. |

The first three operations take `source_ref`: a branch, tag, commit SHA, or pull request head such as `refs/pull/12/head`. This lets you test a branch or draft PR before merging. For `publish_existing_internal`, `source_ref` is still a required workflow input but is ignored; the selected workflow revision supplies the publishing scripts, and `artifact_run_id` identifies the AAB.

For a new build, Gradle Play Publisher gets the next version code from Google Play. For `publish_existing_internal`, the AAB keeps the version code it was built with. The workflow verifies the AAB checksum and signing certificate before upload. Successful publishing commits a completed release to the Internal testing track; it does not create a Play draft or publish to Production.

## Other command-line operations

First check that GitHub CLI is authenticated as `ede73`:

```sh
gh auth status
```

For these operations, use `--ref main` after the workflow changes are merged. Until then, use `--ref codex/release-lint-fix`. `source_ref` is the app source and can be different from the workflow ref.

```sh
REPO=ede73/IndexPaint
WORKFLOW_REF=main
SOURCE_REF=main
```

Build and verify a signed AAB without publishing it:

```sh
gh workflow run android-release.yml --repo "$REPO" --ref "$WORKFLOW_REF" \
  -f operation=build_signed \
  -f source_ref="$SOURCE_REF"
```

Build and publish a branch or pull request to Internal testing:

```sh
SOURCE_REF=refs/pull/12/head
gh workflow run android-release.yml --repo "$REPO" --ref "$WORKFLOW_REF" \
  -f operation=build_and_publish_internal \
  -f source_ref="$SOURCE_REF"
```

Publish an AAB from an earlier successful `build_signed` run without rebuilding it. Copy that run's numeric ID from the Actions run page or the `gh run list` output. `source_ref` is required by the form but ignored for this operation.

```sh
gh workflow run android-release.yml --repo "$REPO" --ref "$WORKFLOW_REF" \
  -f operation=publish_existing_internal \
  -f source_ref=main \
  -f artifact_run_id=RUN_ID
```

To check the run and download its AAB artifact:

```sh
gh run list --repo "$REPO" --workflow android-release.yml --limit 10
gh run watch RUN_ID --repo "$REPO" --exit-status
gh run view RUN_ID --repo "$REPO"
gh run download RUN_ID --repo "$REPO" --name indexpaint-release-aab
```

Each AAB artifact includes `app-release.aab`, its SHA-256 file, and `provenance.txt` with the build run ID, source ref, resolved source commit, and operation. GitHub retains the artifact for 90 days.

## Run it on GitHub

1. Open the IndexPaint repository and choose **Actions → Android Release**.
2. Choose **Run workflow**.
3. Select the workflow branch in the branch dropdown. Use `main` after the workflow changes are merged; until then select `codex/release-lint-fix`.
4. Select the operation.
5. For `verify_signing_inputs`, no source is built. For `build_signed` or `build_and_publish_internal`, enter the source branch, tag, commit SHA, or PR head ref in `source_ref`. For example, use `main` or `refs/pull/12/head`.
6. For `publish_existing_internal`, enter the earlier build's run ID in `artifact_run_id`; `source_ref` remains required by the form and can be `main`.
7. Click **Run workflow**, open the new run, and wait for it to finish. A publish is complete only when the **Publish ... to Internal testing** step succeeds and the run is successful.

Draft PRs are selectable in the same way as regular PRs. Starting the workflow manually does not merge the PR. Only `build_and_publish_internal` or `publish_existing_internal` uploads to Play; `build_signed` only creates a downloadable artifact.

## Required repository configuration

The public `ede73/IndexPaint` repository uses these Actions secrets:

- `INDEXPAINT_UPLOAD_KEYSTORE_BASE64`
- `INDEXPAINT_KEYSTORE_PASSWORD`
- `INDEXPAINT_KEY_PASSWORD`
- `ANDROID_PUBLISHER_CREDENTIALS`

It also uses these repository variables:

- `INDEXPAINT_KEY_ALIAS`
- `INDEXPAINT_KEYSTORE_TYPE`

From the repository root, the signing-secrets helper prepares and verifies an encrypted copy of the local keystore, then sets the three signing secrets and two variables. It leaves the original keystore unchanged. Pass the JKS path as the final argument, or omit it and the script will prompt for the path:

```sh
./scripts/update-github-signing-secrets.sh --repo ede73/IndexPaint /path/to/original-upload.jks
```

Set the Play service-account JSON secret separately. The file path is local to your computer; the JSON contents are sent to GitHub as an Actions secret and are not printed by this command:

```sh
gh secret set ANDROID_PUBLISHER_CREDENTIALS --repo ede73/IndexPaint < /path/to/service-account.json
```

The service account must be invited in Play Console and granted the app-level **Release apps to testing tracks** permission for IndexPaint. The publisher credential is also needed during `build_signed`: Gradle queries Play for the next version code even though that operation does not publish.

## Result of the final test build

Run [42526525306](https://github.com/ede73/IndexPaint/actions/runs/42526525306) built and signed source commit `1ba1dbbcd562e4d98052ccfaa94eb87aa3b57b09`, then uploaded and committed version code **8** to Internal testing. Its AAB SHA-256 is `1dbcd0701e2ba3afc3dfa7e88cb3494687c21523fee2757d6b4176a26a3036a3`.
