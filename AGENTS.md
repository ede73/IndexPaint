# IndexPaint project guidance

- Pull requests run Android CI on GitHub-hosted runners. The PR workflow builds the unsigned debug app and runs the configured tests; it must not use signing credentials or publish to Google Play.
- Signed builds and Google Play Internal testing uploads are started manually through the Android Release workflow. The workflow is owner-gated and supports selecting a branch, pull request head, or commit as the source.
- Keep signing and Google Play publisher credentials in GitHub Actions secrets. Never add credential values, keystores, service-account JSON, or generated secret files to this repository.
- See [RELEASE.md](RELEASE.md) for the release commands and operating procedure.
