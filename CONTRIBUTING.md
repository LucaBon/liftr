# Contributing to LiftHelper

Thanks for your interest in improving LiftHelper. This document explains the ground rules for contributions.

## License of contributions

LiftHelper is licensed under **GNU Affero General Public License v3.0** (see [`LICENSE`](./LICENSE)).

**By submitting a contribution (pull request, patch, code snippet, or any other material) to this repository, you agree that your contribution is licensed under the same AGPL-3.0 license** and may be redistributed by the project under those terms.

## Developer Certificate of Origin (DCO)

All commits must be signed off under the [Developer Certificate of Origin 1.1](https://developercertificate.org/). This is a lightweight alternative to a formal Contributor License Agreement: by signing off, you certify that you have the right to submit the code under the project license.

**How to sign off:**

```bash
git commit -s -m "your commit message"
```

This adds a trailer to your commit message:

```
Signed-off-by: Your Name <your.email@example.com>
```

The name and email must match your real identity (or a pseudonym you consistently use) and be reachable. Anonymous contributions cannot be accepted.

**Amending existing commits to add sign-off:**

```bash
git commit --amend -s          # last commit
git rebase --signoff HEAD~N    # last N commits
```

## What we look for in a PR

- **Scope:** keep PRs focused. One feature or one fix per PR. Refactors should be separate from behavior changes.
- **Tests:** new domain/data logic must come with unit tests. See `app/src/test/` for examples. Target coverage thresholds are enforced by `./gradlew jacocoTestCoverageVerification` (80% line / 70% branch for non-UI code).
- **Architecture:** respect the layer boundaries described in [`CLAUDE.md`](./CLAUDE.md):
  - `domain/` — pure Kotlin, no Android imports.
  - `data/` and `vision/` — Android/framework code, implements `domain/` interfaces.
  - `ui/` — Compose, ViewModels, navigation.
- **Value objects:** prefer `Kilograms`, `Meters`, `MetersPerSecond`, etc. over raw `Float` in domain signatures.
- **New lift support:** add the strategy under `domain/exercise/strategy/`, register it in `ExerciseStrategyFactory` (both the `when` block and `getAllStrategies()`), and add an integration test under `app/src/test/java/com/lifthelper/domain/exercise/strategy/`.
- **Commit messages:** short imperative subject (`fix:`, `feat:`, `refactor:`, `test:`, `docs:`, `chore:`), optional body explaining the why.

## What we do NOT accept

- **Code copied from incompatibly-licensed sources** (proprietary, GPL-2-only, BSD-4-clause with advertising clause, etc.). AGPL-3.0 can absorb MIT/Apache/BSD-2/BSD-3/GPLv3 code, but not the reverse.
- **PRs without DCO sign-off.**
- **PRs that commit secrets, API keys, keystores, or real user data.** See `.gitignore` for the patterns we already filter.
- **PRs that break `./gradlew testDebugUnitTest`.**
- **PRs that add dependencies without justification.** Every new dependency increases APK size, build time, and attack surface.

## Reporting bugs

Open an issue with:
- Device model + Android version
- Steps to reproduce
- Expected vs actual behavior
- If it's a tracking/accuracy issue: the lift, approximate load, and camera setup (distance, angle, lighting)

## Reporting security issues

**Do not open a public issue for security vulnerabilities.** Email **luca.bonura@gmail.com** with details and a proof-of-concept if possible. We'll respond within a reasonable timeframe and coordinate disclosure.

## Code of conduct

Be respectful. Technical disagreements are fine; personal attacks are not. Maintainers reserve the right to close PRs, lock issues, or block users who are disruptive.

## Commercial licensing

If your intended use of LiftHelper is incompatible with AGPL-3.0 (closed-source product, internal corporate deployment without source release, etc.), contact **luca.bonura@gmail.com** for commercial licensing options. Contributions to the open-source project do not grant any commercial license to your own derivative works.
