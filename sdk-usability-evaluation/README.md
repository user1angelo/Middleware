# SDK Usability Evaluation Kit

Two fill-in-the-blank skeletons for evaluating `nis-thesis-sdk` against Clarke's (2005) Cognitive
Dimensions, without requiring the evaluator to build a module from scratch. Not part of the
runtime system — never loaded by `ModuleRegistry`/`SdkModuleHost`.

## What to do

See **[TESTING_GUIDE.md](TESTING_GUIDE.md)** for the full walkthrough — orientation/required
reading, the order to tackle each file's TODOs in, compiling, troubleshooting, and how to rate the
SDK against Clarke's 12 dimensions once you're done. Short version:

1. Read `SDK_USABILITY_AUDIT.md` at the repo root (Part 1 + the 12 dimension definitions in Part 3).
2. Fill in `FooGuardStandaloneSkeleton.java` (Pattern A, TODO-1..6) against `SuricataModule.java` /
   `MaltrailModule.java` as your model.
3. Fill in `FooGuardMitigationSkeleton.java` (Pattern B, TODO-1..4) against `OpenDaylightModule.java`.
4. `mvn -pl sdk-usability-evaluation compile` — both files compile out of the box (every TODO is a
   placeholder `throw`), so this stays green as you replace each one.
5. Rate the SDK against Clarke's 12 dimensions from what you actually experienced — TESTING_GUIDE.md
   has the full rating methodology and a table template.

## Files

- `src/main/java/com/nis1/thesis/eval/FooGuardStandaloneSkeleton.java` — Pattern A exercise
- `src/main/java/com/nis1/thesis/eval/FooGuardMitigationSkeleton.java` — Pattern B exercise
- `src/main/java/com/nis1/thesis/eval/FooGuardAlertData.java` — provided complete, worth reading
- `config/foo-module.properties` — config for skeleton 1
- `sample-data/foo.log` — sample FooGuard log lines for skeleton 1 to parse
