# PocketAgent

PocketAgent is an open-source, approval-gated AI Agent workbench for Android phones and tablets. It is built for China-mainland network setups and keeps **local code** as the authority for tools, risk, and approvals—the model only proposes plans.

## Current release

- Version: `v0.2.5-alpha.1`
- Repository: `https://github.com/DoTrungHuy/PocketAgent`
- Release notes: [v0.2.5-alpha.1](https://github.com/DoTrungHuy/PocketAgent/releases/tag/v0.2.5-alpha.1)

The Android package id remains `com.agentpad.app` for upgrade compatibility. The app label and GitHub release assets use **PocketAgent**.

## What is new in v0.2.5

- Multi model / API Key profiles (DeepSeek, OpenAI-compatible, custom) with encrypted storage.
- Guided conversation before reckless tool calls.
- Sequential tool loop after planning; external actions still need approval.
- Photo Picker attachments and optional local album search by date (permission-gated).
- Optional `analyze_image` (action approval) for multimodal-capable models.
- Hardening: cancel races, working-memory body leak fix, profile key freeze, token consume.

## Security boundary

- Model output is never a permission source.
- Read-only tools may auto-run; external tools require task/action approval.
- Payment, passwords, OTP, lock-screen bypass, and silent install stay forbidden.
- HTTPS for model endpoints (HTTP only for explicit localhost).
- Diagnostics redaction; keys stay in Android Keystore-backed storage.

## Product docs

- [Architecture](docs/ARCHITECTURE.md)
- [Security](docs/SECURITY.md)
- [Roadmap](docs/ROADMAP.md)
- [Release process](docs/RELEASE.md)
- [Governed agent product design](docs/superpowers/specs/2026-07-16-governed-general-agent-design.md)

## Build (Windows)

Requirements: JDK 17, Android Platform 36, Build-Tools 35, Gradle wrapper in `android-app/`.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 -Test
```

Optional China mirrors:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 -ChinaMirrors -Test
```

## Repository layout

- `android-app/` — native Android app
- `docs/` — architecture, security, roadmap, release notes
- `.github/workflows/` — CI, instrumentation, signed prerelease
- `termux-lite/` — frozen research prototype (not a runtime dependency)

MIT License. GitHub Releases is the primary distribution channel.
