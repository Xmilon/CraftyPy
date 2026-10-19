# API 2.9 / protocol 6 verification

Recorded 19 October 2026 on Windows 11, Java 21, Minecraft 1.21.1 and NeoForge 21.1.259.
Native tests create disposable worlds under `build/native-tests*`; they never use the player's worlds,
inventory or editable projects. The distributed jars exclude these fixtures.

## Recorded results

- Full regression: **397 tests, zero failures/errors, one existing Windows symlink skip**.
- Python worker: **68 checks, zero failures**, including top-level execution/validation of all 120 templates,
  all 13 actual packaged `.cpm` files (host mocked), module actions, viewer isolation and existing APIs.
  Both client bundles also load through the normal client worker; all 11 server/both bundles load through the normal native loader with installed integrations.
- Hidden Modern UI designer/editor checks: passed drag/resize, source/design round trips, undo/redo/save,
  binding samples/errors, focused drafts/selections, disabled descendants and slider refresh deferral.
  All **16 shipped/template layouts** build and measure on native views. Actual Building Blocks controls
  passed search, result count, category filtering/label updates, unavailable-integration filtering and code/companion preview.
- Dedicated baseline: six native fixtures pass. Signed rotation, processing, overload, source capacity combination,
  conflicting speeds, axis/topology changes, unresolved-network pauses and completion under the minimum rebuild budget
  are exercised in actual block entities. Definition reload stops/resumes machinery. No Create is present.
- Dedicated Create 6.0.10: connectivity fixtures pass through a real Create shaft, including reversed rotation,
  stress, minimum-speed/full-output pauses, processing conservation, NBT, module reload and wrench detachment.
  The standalone-only fixture is intentionally inapplicable with this backend.
- Dedicated Curios 9.5.1 without Create: all five native fixtures pass, including actual packaged Curios Charms
  loading, player ring-slot assignment and native item-slot tags. Standalone machinery remains available.
- Dedicated installed integration set: all six fixtures pass, including FE/fluid transfers into real configured
  Mekanism handlers, actual Workshop/Curios Charms loader application and generated recipe/tag reloads.
- Native resources: item/FE/fluid simulation and execution, limits/filters, component preservation, full-output
  conservation, fixed two-slot NBT, cross-backend shared-state round trips, and retained resource data in block drops pass.
  Machine inventories drop separately and are excluded from the copied block-item data.
- Permission fixtures pass persisted grants, revocation, changed hashes (including equal size/timestamp), stale
  approval requests, duplicate-file identity, and per-server/per-module/per-hash acknowledgments. Server acknowledgments
  do not grant code permissions.
- Packaging fixtures pass deterministic output, metadata/layout inclusion, disabled installation, existing-file/id
  collisions, and deliberate-deletion receipts. Template companions and imports are checked; a collision leaves the
  entire requested bundle and existing main file untouched.
- Native-content/key fixtures pass compatible-goal checks without executing constructors, slot-family rejection,
  owner state/throttling, stable/remapped key actions, modifiers/conflicts, and malformed/forged/stale/rate-limited requests.
- Final package build passes. Jar inspection verifies 13 embedded modules, CraftPy rotor/wrench assets, protocol 6,
  no shipped Foundry/Stress Lab, and no native game-test classes or bundled Create implementation.

## Reproduce

```powershell
.\gradlew.bat --offline --no-daemon build uiDesignerSmokeTest
python -X utf8 src/test/python/test_worker.py
.\gradlew.bat --offline --no-daemon test runGameTestServer
.\gradlew.bat --offline --no-daemon runGameTestServer -PwithCreate
.\gradlew.bat --offline --no-daemon runGameTestServer -PwithCurios
.\gradlew.bat --offline --no-daemon runGameTestServer -PwithIntegrations
```

The graphics check needs a working desktop GPU. Optional test modes copy installed jars from `run/mods`
into separate disposable directories. Each native run clears only its validated test `world` and `pyforge`
directories, and fails unless the server explicitly reports a completed passing run. The all-mods test uses
the installed versions; it does not download or upgrade integrations.

Evidence: `build/independent-completion-final.log` (native baseline), `build/independent-create-final.log`,
`build/independent-curios-final.log`, `build/independent-collection-final.log` (full native collection), `build/independent-ui-final.log`,
`build/independent-package-final.log`, `build/independent-python-final.log`, and `build/test-results/test`.
Earlier failed UI fixture runs were corrected to use the real overlay host/clickable row. The asynchronous editor-open fixture now waits for its actual document. The extended
network fixture uses a larger isolated template so it cannot place machines outside its test area.

Integration versions: Create 6.0.10, Curios 9.5.1, GeckoLib 4.9.3, Iron's Spellbooks 3.16.3 with
irons_lib 2.2.0, Mekanism 10.7.19.85, player-animation-lib 2.0.4; Modern UI 3.13.0.1 on the client.
Installed jars are unchanged. Curios-only and baseline dedicated servers do not need Modern UI.

## Explicitly unverified

- A full Minecraft client session with only the baseline; the hidden standalone editor is a narrower graphics check.
- Real Controls interactions/restarts, interactive permission switches across game restarts, and a two-client session
  covering server shortcuts, machinery and synchronized animations.
- Actual chunk unload/reload; NBT round trips and native topology changes are narrower checks.
- Real hand/hopper/bucket gameplay beyond native handler/interactions fixtures.
- Native AI navigation/attacks, final renderer appearance and actual client animation playback.
- Flywheel enabled/disabled in a real client. The renderer uses the ordinary block-entity rendering path;
  source inspection and server startup do not prove both graphics modes.
- Complete gameplay for every optional template, unsupported integration versions, Linux and macOS.

Core rotation supplies axis-aligned shafts, not a general gear simulation. Only Create 6.0.10 has a native
rotation adapter; unsupported Create versions use core machinery and hide Create-adapter templates. Other
rotation systems require an explicit adapter. Item/fluid components are preserved by native stacks, while
Python snapshots intentionally expose bounded summaries. Foreign capability handlers must obey NeoForge
simulation/execution semantics; these tests cannot guarantee atomic transfers through broken third-party handlers.

Foundry, its Stress Lab, arena controllers, lab commands/networking/configuration and Python entry point are removed.
There is no production stress ramp or arena cleanup code in this release. Ordinary profiling and regression tests remain.
Existing user-installed modules and existing world structures are not removed by this feature cleanup.
