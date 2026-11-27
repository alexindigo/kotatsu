# Release tooling

The release set from this source tree is Kotatsu (`app`), Kotatsu Weather
(`kotatsu-weather`), Kotatsu Notes (`kotatsu-notes`) and Kotatsu Sync
(`kotatsu-sync`). `tools/verify_fork.py` owns their identity
inventory, shared version name and per-app version codes. Update those pins
alongside the Gradle versions when preparing a release.

`gradle/modules.txt` is the module manifest and drives inclusion in Gradle
settings. List every module once as `path=release`, `path=non-release` or
`path=non-app`; blank lines and `#` comment lines do not count. Use the source
directory's root-relative path. Each module must have exactly one build script:
`release` modules require `build.gradle.kts` for source identity checks;
`non-release` and `non-app` modules may use `build.gradle.kts` or `build.gradle`.
For a nested project, list its Gradle parent projects too.
Gradle checks the configured projects and whether each actually applies
`com.android.application`. Both release tools check the manifest against the
source directories, excluding generated Gradle and build trees, and reject
unclassified, missing or duplicate modules.

The two application classes must agree with the identity pins in
`tools/verify_fork.py`: `fake-mudita-calendar` is the only non-release app.
It and its test APK are emulator fixtures, never release assets or Kompakt
installs. Only `RELEASE_APPS` may supply release assets.

After building from the same checkout,
`tools/prepare_release.py --root . --tag v<version> --output release-apks`
checks the complete source/metadata set and copies the unique release output
of each module to its documented name: `kotatsu-v<version>.apk`,
`kotatsu-weather-v<version>.apk`, `kotatsu-notes-v<version>.apk` and, starting
with the next release, `kotatsu-sync-v<version>.apk`. It rejects mismatched versions,
packages, missing or extra APKs, symlinks and an existing output directory.
The `--tag` argument checks the source version; it neither creates nor
validates a Git tag. This naming step does not inspect compiled manifest
identity or sign APKs. Production APKs are prepared and signed manually;
the checked-in **Build APK** workflow is disabled. The published v0.7.1
release has only the launcher, Weather and Notes APKs.

## Identity gate

Run project workloads inside the assigned project VM, with a disk-backed
workspace and TMPDIR, following the project access instructions. All paths
below are guest paths. Source receipts and reports belong outside the checkout.

Generate `source.json` using `tools/source_receipt.py --root <checkout>
--output <evidence>/source.json`. Independently retain the expected full HEAD,
tree and receipt SHA-256 before building. The gate compares the current inputs
to that pinned receipt before and after APK inspection.

```sh
python3 tools/verify_fork.py release-identity \
  --root <checkout> --baseline c6601b8128089db8ad28cdcc1f57361575066d5f \
  --expected-source-head <full-sha> --expected-source-tree <tree-sha> \
  --source-receipt <evidence>/source.json \
  --expected-source-receipt-sha256 <receipt-sha256> \
  --variant release --apkanalyzer <sdk>/cmdline-tools/latest/bin/apkanalyzer \
  --aapt2 <sdk>/build-tools/36.0.0/aapt2 \
  --apksigner <sdk>/build-tools/36.0.0/apksigner \
  --expected-cert-sha256 <independently-approved-build-certificate> \
  --build-receipt <evidence>/build.json \
  --expected-build-receipt-sha256 <build-receipt-sha256> \
  --report <evidence>/release-identity.json
```

Source checks cover the module manifest and source directory inventory,
namespace, package, version code/name, debug suffixes, SDK levels, optimization
flags and app labels. APK checks cover each metadata output and its actual
compiled package, version, labels, SDK levels, debuggability and signer. Every
APK digest appears in the report. The Sync row also checks all eight translated
labels; its release-only check verifies the main entry points, the provider and
adapter queries, absence of known fixtures and the Internet permission, and
the exact bundled notice bytes. This is not a runtime or offline certification.
For debug pass `--variant debug` and the approved debug certificate instead,
omitting both build-receipt flags. Only the launcher adds `-debug` to the version
name; all four add `.debug` to the package. Sync debug and release have separate
permissions, settings and bridge state. There is no automatic migration.

The release build receipt retains `sourceBefore` and `sourceAfter` equal to
the verified source provenance, `deferredIndexSha256` equal to the pinned
original artwork index SHA-256, `tasks` and `artifacts`. The 12 tasks are ordered
by app (launcher, Weather, Notes, Sync), then `testReleaseUnitTest`, `lintRelease`,
`assembleRelease` for each app.
Pass `-Pandroid.onlyEnableUnitTestForTheTestedBuildType=false` to Gradle for
the release unit-test invocations. AGP otherwise registers host unit tests only
for the default tested build type (debug). This opt-in does not change which
build type runs instrumentation tests.
Each task record has `task` (fully qualified Gradle path), `exitCode`, `log`
and `logSha256`. Logs must show successful, freshly executed tests, lint,
R8, resource optimization and packaging. Artifacts map source-relative paths
to SHA-256s with 16 required files: each APK, its `output-metadata.json`,
`mapping.txt` and `resources.txt`. Replayed sources, omitted tasks and changed
companion artifacts fail the gate.

For unsigned build inspection only, replace certificate/build-receipt flags
with `--inspect-unsigned`. The report explicitly marks `inspectionOnly: true`,
`signaturesVerified: false` and no build provenance. This is useful for checking
all four `assembleRelease` outputs before manual signing; it is not the signed
build-receipt gate or proof of a tagged revision.

The older `identity` / `apk-identity` modes additionally audit the historical
launcher's whole-file and approved-branding baseline. They are retained for
that audit, rather than weakening its fixed asset contract to accommodate
later product changes. The current `release-identity` mode covers all four
release identities and does not certify artwork or offline capabilities.

## Next release checklist

1. Bump the shared release version, each app's independent code (Sync 13 if
   unchanged), verifier and positive fixture pins, and the launcher archive
   name and historical launcher-only version pins. Leave deliberately stale
   test inputs stale.
2. Add Sync as a fourth optional APK in the README and site download panels;
   update the notes link, displayed version, JSON-LD and versioned examples.
   In the new changelog and release notes, explain the separate DAVx5 setup,
   local calendar/contact bridge and lack of Internet permission. Carry forward
   relevant source history, including `7865cfd` (icon), `16624c6` (locales), and
   the merged release tooling change, without changing v0.7.1's history.
3. Before a separately authorized release, require green **Connected tests**
   on the exact checked-out release commit and confirm **Build APK** remains
   disabled. Use the established manually signed tag and clean tagged checkout.
4. Update the historical manual helpers together for four assets; restore their
   production signed-tag/clean-source guards. Pin the approved commit, tree, tag
   object, helper hashes and four metadata-selected unsigned APKs. Verify the
   embedded revision, compiled identities and byte hashes, then repeat the
   throwaway wrong-certificate refusal. Never promote the rehearsal helpers.
5. In the separately authorized production signing step, check alignment,
   v3 signing, one approved signer, identity and revision on all four APKs.
   Match Sync's signed manifest, resource, DEX and notice payloads to the
   inspected unsigned ones. Verify temporary credential and intermediate
   cleanup before handoff.
6. Publish exactly four APKs, not test artifacts or verification files. Only
   after production verification, append four signed checksums to the new
   release notes. Verify the downloaded bytes and links against those hashes.
