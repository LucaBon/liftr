# Lift Helper - Feature Roadmap

## Overview

This document outlines the next wave of features for Lift Helper, prioritized by
training value, implementation feasibility, and how well they build on the existing
architecture. Each feature has a dedicated design document in this directory.

## Current State (as of feat/add_portrait_mode)

- **Exercises**: Back Squat, Front Squat (2 of 8 declared ExerciseType values)
- **Tracking**: Real-time pose detection, rep counting, velocity measurement, depth validation
- **Feedback**: Visual overlays only (velocity, rep count, calibration quality)
- **History**: Basic workout list with aggregate metrics
- **Settings**: Squat depth type, calibration, anthropometric measurements

## Priority Features

| # | Feature | Effort | Impact | Depends On |
|---|---------|--------|--------|------------|
| 1 | [VBT Zones & Auto-Regulation](FEATURE_01_VBT_ZONES.md) | Low | High | — |
| 2 | [Audio & Haptic Feedback](FEATURE_02_AUDIO_HAPTIC_FEEDBACK.md) | Low | High | — |
| 3 | [Bench Press & Deadlift Strategies](FEATURE_03_BENCH_DEADLIFT_STRATEGIES.md) | Medium | High | — |
| 4 | [Rep Quality Scoring](FEATURE_04_REP_QUALITY_SCORING.md) | Medium | High | — |
| 5 | [Progressive Overload Analytics](FEATURE_05_PROGRESSIVE_OVERLOAD_ANALYTICS.md) | Medium | High | #4 (partial) |

## Implementation Order

Features 1 and 2 are independent, low-effort, and high-impact — implement first,
potentially in parallel. Feature 3 is independent and can start any time. Feature 4
builds on existing form validation. Feature 5 benefits from the scoring model in
Feature 4 but the analytics portion can start independently.

```
Sprint 1:  Feature 1 (VBT Zones) + Feature 2 (Audio/Haptic)
Sprint 2:  Feature 3 (Bench Press + Deadlift)
Sprint 3:  Feature 4 (Rep Quality Scoring)
Sprint 4:  Feature 5 (Analytics)
```

## Architectural Principles

All features must follow the existing patterns:

- **Clean Architecture**: domain layer has zero Android dependencies
- **Immutable state**: no mutable fields on shared objects, use copy() for updates
- **Thread safety**: AtomicReference / @Volatile for cross-thread state
- **Strategy pattern**: new exercises implement ExerciseStrategy
- **Hilt DI**: all dependencies injected, no manual instantiation
- **Sealed classes**: for exhaustive result handling
- **Flow/StateFlow**: for reactive UI updates

## Future Features (Not Planned Yet)

These are captured for reference but not designed in detail:

- **Bar Path Visualization** — live trajectory overlay on camera preview
- **Tempo Training Mode** — configurable eccentric/concentric tempo prescriptions
- **Workout Builder** — templates, programs, rest timers
- **Video Recording** — save workout clips with skeleton overlay for sharing
- **Cloud Sync** — Firebase/Supabase for cross-device history
- **Wear OS Companion** — heart rate integration, wrist-based rep counting
- **Multi-Angle Support** — front/rear view for symmetry analysis
- **Warm-up & Mobility Guidance** — pose-guided stretching routines
