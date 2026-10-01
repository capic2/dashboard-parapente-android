---
name: dashboard-parapente-apk
description: Build and deliver installable APKs for the dashboard-parapente-android project, using its signed GitHub release workflow for production APKs.
metadata:
  short-description: Generate the Dashboard Parapente APK
---

# Dashboard Parapente APK

Use this skill when the user asks to build, regenerate, or provide an APK for `capic2/dashboard-parapente-android`.

## Release APK

The project’s canonical signed release build is GitHub Actions workflow `.github/workflows/build-apk.yml` (`Publish Android Release`). It runs on pushes of tags matching `v*`, uses JDK 17 and the repository’s signing secrets, runs `./gradlew assembleRelease`, then publishes `dashboard-parapente-vX.Y.Z.apk` as a GitHub Release asset.

For a production/installable release APK:

1. Inspect the current branch, worktree, workflow, and latest release tag. Keep the user's changes intact and include only the requested APK changes.
2. Choose the next release version from the latest published tag. Set `versionName` in `app/build.gradle.kts` to the same `X.Y.Z` value so the installed app and release tag agree. The workflow supplies `versionCode` from `GITHUB_RUN_NUMBER`.
3. The release requires pushing the source commit to `main` and pushing an annotated tag `vX.Y.Z`; the tag triggers the build and public GitHub Release. Do not push or publish unless the user has authorized this release in the current task. If authorization is absent, prepare the local changes and ask before the first push/tag operation.
4. Follow the Actions run to completion and inspect its conclusion. If it fails, read the job logs, fix the cause, and only trigger another release attempt within the user's authorization.
5. After success, confirm the release contains the expected APK asset. Download it to `app/build/outputs/apk/release/`, identify it with `file`, and provide both the local APK link and GitHub Release link.

The workflow uses signing credentials from GitHub Actions secrets. Never print, copy, or place those secrets in the repository or build output.

## Debug APK

If the user specifically requests a debug build, or asks for a local build without publishing a release, use `./gradlew assembleDebug`. The output is `app/build/outputs/apk/debug/app-debug.apk`. A debug APK uses the debug signing key and is distinct from the signed production release APK.

## Environment

The local checkout may not have Java or an Android SDK configured. Check for a usable JDK 17 and Android SDK before attempting a local Gradle build. When they are missing, use the GitHub release workflow only if the user has authorized pushing and publishing; otherwise explain the blocker and leave the change ready for approval.
