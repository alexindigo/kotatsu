"""Exercise complete artifact checks with real ZIP/metadata and SDK-output fixtures."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location('verify_fork', Path(__file__).parents[1] / 'verify_fork.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class ApkIdentityFlowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cert = 'a' * 64
        self.package = verify.RELEASE_ID + '.debug'
        self.directory = self.root / 'app/build/outputs/apk/debug'
        self.directory.mkdir(parents=True)
        self.apk = self.directory / 'fixture.apk'
        with zipfile.ZipFile(self.apk, 'w') as archive:
            for name in ('res/a.xml', 'res/b.xml', 'res/c.xml'):
                archive.writestr(name, b'compiled fixture')
        self.metadata = {'variantName': 'debug', 'applicationId': self.package, 'elements': [{'outputFile': self.apk.name}]}
        (self.directory / 'output-metadata.json').write_text(json.dumps(self.metadata))
        self.manifest = ET.fromstring(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{self.package}">
          <uses-feature android:name="android.hardware.telephony" android:required="false" />
          <application android:allowBackup="true" android:fullBackupContent="@ref/0x7f000001" android:dataExtractionRules="@ref/0x7f000002">
            <activity android:name="com.gezimos.katapult.MainActivity"><meta-data android:name="android.app.shortcuts" android:resource="@ref/0x7f000003" /></activity>
            <activity android:name="com.gezimos.katapult.lockscreen.ScreensaverActivity" />
          </application>
        </manifest>''')
        self.baseline_manifest = ET.tostring(self.manifest)
        self.xml = {'res/a.xml': '<full-backup-content><include domain="sharedpref" path="." /></full-backup-content>',
                    'res/b.xml': '<data-extraction-rules><device-transfer><include domain="sharedpref" path="." /></device-transfer></data-extraction-rules>',
                    'res/c.xml': f'<shortcuts xmlns:android="http://schemas.android.com/apk/res/android"><shortcut><intent android:targetPackage="{self.package}" android:targetClass="com.gezimos.katapult.lockscreen.ScreensaverActivity" /></shortcut></shortcuts>'}
        self.original_xml = copy.deepcopy(self.xml)
        self.resources = '\n'.join(f'  resource 0x7f00000{i} xml/{name}\n    () (file) res/{letter}.xml type=XML' for i, name, letter in
                                   ((1, 'backup_rules', 'a'), (2, 'data_extraction_rules', 'b'), (3, 'shortcuts', 'c')))
        self.version = '1.6-debug'
        self.labels = {locale.lstrip('-') or 'default': 'Kotatsu Debug' for locale in verify.LOCALES}
        self.debuggable = True
        self.min_sdk = '30'
        self.target_sdk = '36'
        self.args = argparse.Namespace(root=str(self.root), baseline=verify.BASELINE, deferred_assets_index='index.json',
            apk_dir=str(self.directory), variant='debug', expected_cert_sha256=self.cert, apkanalyzer='/sdk/apkanalyzer',
            aapt2='/sdk/aapt2', apksigner='/sdk/apksigner', report=str(self.root / 'report.json'))
        for patch in (mock.patch.object(verify, 'source_identity', return_value={'status': 'deferred originals verified'}),
                      mock.patch.object(verify, 'execution_identity', return_value={'head': 'fixture'}),
                      mock.patch.object(verify, 'command', side_effect=self.tool),
                      mock.patch.object(verify, 'baseline_bytes', side_effect=self.baseline)):
            patch.start()
            self.addCleanup(patch.stop)

    def baseline(self, root, base, path):
        if path.endswith('AndroidManifest.xml'):
            return self.baseline_manifest
        name = 'res/a.xml' if path.endswith('/backup_rules.xml') else 'res/b.xml'
        return self.original_xml[name].encode()

    def tool(self, *args):
        if args[0] == '/sdk/apksigner':
            return 'Signer #1 certificate SHA-256 digest: ' + self.cert + '\n'
        if args[1:3] == ('manifest', 'print'):
            return ET.tostring(self.manifest, encoding='unicode')
        if args[1:3] == ('dump', 'badging'):
            labels = '\n'.join(f"application-label{'' if locale == 'default' else '-' + locale}:'{value}'" for locale, value in self.labels.items())
            return f"package: name='{self.package}' versionCode='7' versionName='{self.version}'\n{labels}\n" + ('application-debuggable\n' if self.debuggable else '')
        if args[1:3] == ('manifest', 'min-sdk'):
            return self.min_sdk
        if args[1:3] == ('manifest', 'target-sdk'):
            return self.target_sdk
        if args[1:3] == ('dump', 'resources'):
            return self.resources
        if args[1:3] == ('resources', 'xml'):
            return self.xml[args[4]]
        raise AssertionError(args)

    def rejected(self, message):
        with self.assertRaisesRegex(verify.VerificationError, message):
            verify.apk_identity(self.args)
        self.assertFalse(Path(self.args.report).exists(), 'Failure published a passing report')

    def test_complete_debug_flow_resolves_minified_xml_and_publishes_bound_report(self):
        verify.apk_identity(self.args)
        report = json.loads(Path(self.args.report).read_text())
        self.assertEqual(report['sha256'], verify.sha(self.apk.read_bytes()))
        self.assertEqual(report['applicationId'], self.package)
        self.assertEqual(report['signerSha256'], self.cert)
        self.assertTrue(report['backupContractPreserved'] and report['shortcutIntentVerified'])

    def test_actual_package_disagrees_with_correct_metadata(self):
        self.manifest.set('package', 'substituted.package')
        self.rejected('Actual compiled package mismatch')

    def test_actual_locale_label_disagrees_with_source(self):
        self.labels['de'] = 'Katapult'
        self.rejected('Wrong compiled locale label')

    def test_actual_missing_locale(self):
        del self.labels['de']
        self.rejected('Missing compiled existing locale')

    def test_actual_shortcut_package_class_and_component(self):
        self.xml['res/c.xml'] = self.xml['res/c.xml'].replace(self.package, 'com.gezimos.katapult.debug')
        self.rejected('Compiled shortcut intent mismatch')
        self.xml['res/c.xml'] = self.original_xml['res/c.xml'].replace('.lockscreen.ScreensaverActivity', '.MissingActivity')
        self.rejected('Compiled shortcut intent mismatch')
        self.xml['res/c.xml'] = self.original_xml['res/c.xml']
        self.manifest.find('application').remove(self.manifest.find('application').findall('activity')[1])
        self.rejected('Shortcut target component not packaged')

    def test_actual_backup_attributes_and_payload(self):
        self.manifest.find('application').set(verify.ANDROID + 'allowBackup', 'false')
        self.rejected('Compiled backup attribute changed')
        self.manifest.find('application').set(verify.ANDROID + 'allowBackup', 'true')
        self.xml['res/b.xml'] = '<data-extraction-rules />'
        self.rejected('Compiled backup contract changed')

    def test_actual_debuggability_version_and_sdk(self):
        self.debuggable = False
        self.rejected('Compiled debuggability mismatch')
        self.debuggable = True
        self.version = '2.0'
        self.rejected('Compiled APK version/ID mismatch')
        self.version = '1.6-debug'
        self.min_sdk = '32'
        self.rejected('Compiled SDK mismatch')

    def test_actual_required_telephony(self):
        self.manifest.find('uses-feature').set(verify.ANDROID + 'required', 'true')
        self.rejected('Compiled optional hardware missing')

    def test_actual_signer_mismatch(self):
        self.cert = 'b' * 64
        self.rejected('APK signer mismatch')

    def test_missing_and_empty_release_mapping(self):
        self.args.variant = 'release'
        self.package = verify.RELEASE_ID
        self.manifest.set('package', self.package)
        self.xml['res/c.xml'] = self.xml['res/c.xml'].replace(verify.RELEASE_ID + '.debug', self.package)
        self.labels = {locale: 'Kotatsu' for locale in self.labels}
        self.version = '1.6'
        self.debuggable = False
        release_dir = self.directory.parent / 'release'
        self.directory.rename(release_dir)
        self.directory = release_dir
        self.apk = release_dir / self.apk.name
        self.args.apk_dir = str(release_dir)
        self.metadata.update(variantName='release', applicationId=self.package)
        (release_dir / 'output-metadata.json').write_text(json.dumps(self.metadata))
        self.rejected('Missing file')
        mapping = self.root / 'app/build/outputs/mapping/release/mapping.txt'
        mapping.parent.mkdir(parents=True)
        mapping.touch()
        self.rejected('Empty release mapping')

    def test_report_collision_preserves_existing_evidence(self):
        Path(self.args.report).write_text('previous evidence')
        with self.assertRaisesRegex(verify.VerificationError, 'Report collision'):
            verify.apk_identity(self.args)
        self.assertEqual(Path(self.args.report).read_text(), 'previous evidence')
