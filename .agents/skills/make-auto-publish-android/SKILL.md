---
name: make-auto-publish-android
description: Set up an owner-controlled manual Android signing, build, and Google Play Internal testing release workflow for an existing Android project.
---

# Make an Android app build and publish automatically

Use this skill when asked to add the Android release workflow from IndexPaint to another existing Android project. Inspect the target repository first; adapt its existing Gradle, package ID, signing configuration, Play Console app, runner, and branch policies. Keep the app repository public or private as the owner requests. Do not create a shadow repository.

## What this workflow provides

- Pull requests, including drafts, run unsigned build, unit tests, and Android instrumentation tests on GitHub-hosted runners. CI receives no signing or Play secrets and never runs on the Mac mini.
- A manually dispatched `Android Release` workflow runs on the owner's Mac mini self-hosted runner. It has no push, merge, or pull-request trigger.
- A job-level actor check allows only `ede73` to reach the self-hosted runner and the signing secrets. Retain the check for both `github.actor` and `github.triggering_actor` so reruns by another account are rejected.
- The owner can select `main`, any branch, tag, commit SHA, or PR head—including a draft PR—as the source. The workflow ref containing the release workflow and the source ref containing the app are separate inputs.
- Operations are build-and-verify only, build-and-publish to Play Internal testing, or publish an exact earlier AAB artifact without rebuilding it. Nothing publishes to Production.
- New signed builds ask Gradle Play Publisher for the next Play version code. No manual version bump is required for this single-owner, nonparallel workflow. A previously built AAB keeps its original version code.

The successful IndexPaint proof run is [Android Release run 42526525306](https://github.com/ede73/IndexPaint/actions/runs/42526525306), which committed version code 8 to Internal testing. Always inspect the current run and require its final publish/commit step to succeed; an uploaded AAB or staged Play edit alone does not mean the release is live.

## Work the agent can do

1. Inspect the project files, current GitHub Actions, Git remotes, package/application ID, Gradle Play Publisher configuration, existing Play Console setup docs, and the owner’s `AGENTS.md` rules. Inspect `gh auth status` and the repository’s live runner and ruleset settings with `gh`; do not infer these from an old README.
2. Keep ordinary PR CI on GitHub-hosted `ubuntu-24.04` (or another tested GitHub-hosted image). Keep all signing and publishing in a distinct `workflow_dispatch` release workflow.
3. Add or adapt the Gradle signing checks, Play Publisher configuration, automated version-code resolution, AAB signature verification, provenance/checksum, safe credential materialization and cleanup, and publish tasks.
4. Make the release workflow accept the app source ref independently from the workflow ref. Add a job-level owner check before allocating self-hosted hardware. Use only the intended runner labels.
5. Use the existing repository’s main branch ruleset as a reference. Preserve admin bypass so the repository owner can push directly when desired; do not configure a rule that blocks the owner’s admin bypass.
6. Test locally where credentials and SDKs are available, then run the public PR CI. Do not publish to Play as a test unless the owner explicitly asks for the end-to-end publish.
7. Open or update the requested task PR. Monitor every new check and fix failures. Do not merge unless the owner explicitly asks.

## Setup that requires the owner in Google Cloud or Play Console

Use one general Google Cloud project for Android Publisher API access across the owner’s Android apps; it does not need the app name. IndexPaint used project ID `android-publisher-project` and service account email `android-play-publisher@android-publisher-project.iam.gserviceaccount.com`.

The owner must complete UI-only steps when they require account consent, downloads, or Play Console permissions:

1. In Google Cloud Console, select the shared publisher project. Open **APIs & Services → Library**, enable **Google Play Android Developer API**, then open **IAM & Admin → Service Accounts**.
2. Create or select a publisher service account. Create a JSON key only if the organization policy permits it; download the file and keep it private. Do not commit it or paste it into chat.
3. In Google Play Console, open **Users and permissions → Invite new users** and enter the service account email. For each target app, add that app and grant the minimum needed permissions: **View app information** and **Release apps to testing tracks**. Do not grant Production release, financial, or account-admin permissions for an internal-only publisher.
4. After invitation, store the JSON as the target repository’s `ANDROID_PUBLISHER_CREDENTIALS` Actions secret. The app-level Play Console grant and the Google Cloud API enablement are both required; merely creating the service account is insufficient.

Official setup references: [Android Publisher API getting started](https://developers.google.com/android-publisher/getting_started) and [Play Console user permissions](https://support.google.com/googleplay/android-developer/answer/9844686?hl=en).

## Signing setup

Use the existing upload JKS and preserve it unchanged. The IndexPaint helper creates and verifies an encrypted temporary copy with new random passwords and writes the copy/passwords as GitHub Actions secrets. For another repository, first inspect the helper’s `--prefix` support and use a project-specific secret prefix; do not copy IndexPaint secret names blindly if the workflow has been parameterized.

Example for IndexPaint:

```sh
./scripts/update-github-signing-secrets.sh --repo ede73/IndexPaint --prefix INDEXPAINT /path/to/original-upload.jks
```

The helper writes three repository secrets (`<PREFIX>_UPLOAD_KEYSTORE_BASE64`, `<PREFIX>_KEYSTORE_PASSWORD`, `<PREFIX>_KEY_PASSWORD`) and two nonsecret repository variables (`<PREFIX>_KEY_ALIAS`, `<PREFIX>_KEYSTORE_TYPE`). It never prints the generated passwords. Do not expose the original JKS, its local passwords, or service-account JSON in PR builds or logs.

The Play JSON secret is added separately:

```sh
gh secret set ANDROID_PUBLISHER_CREDENTIALS --repo OWNER/REPO < /path/to/service-account.json
```

Secret values are not readable back via `gh`; verify only that secret names exist with `gh secret list`.

## Runner and repository access

Register the Mac mini runner to the target app repository (or to a supported organization runner group if the owner later chooses that model). For this personal-account setup, do not assume an organization or create a private controller repository. Restrict the runner to the app repository and use a dedicated label such as `android-release`. Confirm the runner appears online in that repository’s **Settings → Actions → Runners**.

Keep PR CI on GitHub-hosted runners with explicit `runs-on: ubuntu-24.04`; never use the self-hosted label in PR CI. A public repository may receive untrusted PRs, so signing secrets must be referenced only in the manually invoked, owner-gated release workflow. Do not add any `push`, `pull_request`, or `pull_request_target` trigger to the release workflow.

The release workflow checks `github.actor == 'ede73'` and `github.triggering_actor == 'ede73'` in the job condition. Preserve this check even if GitHub UI policies also restrict dispatch. A ruleset is separate from the workflow guard.

## Commands for the owner

After the workflow is merged to `main`, dispatch a signed build and publish the current `main` source to Internal testing with one command:

```sh
gh workflow run android-release.yml --repo OWNER/REPO --ref main -f operation=build_and_publish_internal -f source_ref=main
```

For a branch:

```sh
gh workflow run android-release.yml --repo OWNER/REPO --ref main -f operation=build_and_publish_internal -f source_ref=BRANCH
```

For a regular or draft PR:

```sh
gh workflow run android-release.yml --repo OWNER/REPO --ref main -f operation=build_and_publish_internal -f source_ref=refs/pull/NUMBER/head
```

To build and sign without publishing, set `operation=build_signed`. To publish the exact AAB from a successful earlier run without rebuilding, set `operation=publish_existing_internal` and pass its numeric `artifact_run_id`.

Find and watch the exact run ID returned by `gh workflow run` or the newest Actions run:

```sh
gh run list --repo OWNER/REPO --workflow android-release.yml --limit 5
gh run watch RUN_ID --repo OWNER/REPO --exit-status
```

Do not report a release as complete until the run succeeds through the Play edit commit and the Internal testing release is visible in Play Console. A failed commit may leave an uploaded-but-uncommitted bundle; inspect the exact run before retrying.

## Preserve the existing GitHub workflow

- Public PR CI must remain independent: unsigned build/tests only, GitHub-hosted runner only, no signing secrets.
- Signed builds and publishes are manual only and actor-gated to the owner.
- Use PR heads or branches as source refs without requiring them to be merged first.
- Keep the main ruleset’s Admin bypass so `ede73` retains direct push access. Other contributors should not be able to bypass it.
- Never change the remote `main` branch unless the owner explicitly asks; never force-push or rewrite remote history.
- Use clear user language such as “Build and publish Internal testing”; avoid vague phrases like “upload to a signing bundle.”
