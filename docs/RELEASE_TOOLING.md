# Release tooling

The release set is Kotatsu (`app`), Kotatsu Weather (`kotatsu-weather`) and
Kotatsu Notes (`kotatsu-notes`). `tools/verify_fork.py` owns their identity
inventory, shared version name and per-app version codes. Update those pins
alongside the Gradle versions when preparing a release.

`tools/prepare_release.py --root . --tag v<version> --output release-apks`
checks the complete source/metadata set and copies the unique release output
of each module to its documented name. It rejects mismatched tags, versions,
packages, missing or extra APKs, symlinks and an existing output directory.
The tag workflow builds all three modules and attaches this staged set.
This naming step does not inspect compiled manifest identity or sign APKs.

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

Source checks cover the application-module inventory, settings inclusion,
namespace, package, version code/name, debug suffixes, SDK levels, optimization
flags and app labels. APK checks cover each metadata output and its actual
compiled package, version, labels, SDK levels, debuggability and signer. Every
APK digest appears in the report. Debug inspection uses `--variant debug`
without a release build receipt and respects each module's debug suffixes.

The release build receipt retains `sourceBefore` and `sourceAfter` equal to
the verified source provenance, `deferredIndexSha256` equal to the pinned
original artwork index SHA-256, `tasks` and `artifacts`. Task order is app,
Weather, Notes; for each: `testReleaseUnitTest`, `lintRelease`, `assembleRelease`.
Each task record has `task` (fully qualified Gradle path), `exitCode`, `log`
and `logSha256`. Logs must show successful, freshly executed tests, lint,
R8, resource optimization and packaging. Artifacts map source-relative paths
to SHA-256s and must include each APK, its `output-metadata.json`, `mapping.txt`
and `resources.txt`. Replayed sources, omitted companion tasks and changed
companion artifacts fail the gate.

For unsigned build inspection only, replace certificate/build-receipt flags
with `--inspect-unsigned`. The report explicitly marks `inspectionOnly: true`,
`signaturesVerified: false` and no build provenance. This is useful for checking
`assembleRelease` outputs; it is not the signed release gate.

The older `identity` / `apk-identity` modes additionally audit the historical
launcher's whole-file and approved-branding baseline. They are retained for
that audit, rather than weakening its fixed asset contract to accommodate
later product changes. The current `release-identity` mode covers all three
release identities and does not certify artwork or offline capabilities.
