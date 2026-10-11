"""Report claims must agree with the actual signed APK before installation."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('probe_launcher', Path(__file__).parents[1] / 'probe_launcher.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
fixture_spec=importlib.util.spec_from_file_location('probe_fixture_owner',Path(__file__).with_name('test_probe_launcher.py'))
fixtures=importlib.util.module_from_spec(fixture_spec);fixture_spec.loader.exec_module(fixtures)


class ActualProbeArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.cert = 'a' * 64
        self.package = probe.EXPECTED_IDS['debug']
        self.actual_package = self.package
        self.debuggable = 'true'
        self.report = root / 'report.json'
        self.provenance = {'head': 'a'*40, 'headTree': 'b'*40, 'sourceReceiptSha256': 'c'*64}
        value,self.apk=fixtures.approved_report_fixture(root,self.cert,self.provenance)
        self.expected_sha=value['sha256'];self.report.write_text(json.dumps(value))
        self.patch = mock.patch.object(probe, 'run', side_effect=self.tool)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.validate()

    def tool(self, *args, **kwargs):
        if 'verify' in args:
            return 'Signer #1 certificate SHA-256 digest: ' + self.cert + '\n'
        if args[1:3] == ('manifest', 'print'):
            return f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{self.actual_package}"><application android:debuggable="{self.debuggable}" /></manifest>'
        raise AssertionError(args)

    def validate(self):
        return probe.validate_report(self.report, 'debug', self.package, self.cert, '/sdk/apksigner', self.provenance,
            self.expected_sha)

    def test_valid_report_actual_apk_and_independent_approval(self):
        self.assertEqual(self.validate()[1], self.apk)

    def test_replayed_source_receipt_with_self_consistent_artifact_rejected(self):
        value = json.loads(self.report.read_text())
        value['sourceProvenance']['sourceReceiptSha256'] = '0'*64
        self.report.write_text(json.dumps(value))
        with self.assertRaisesRegex(probe.ProbeError, 'provenance'):
            self.validate()

    def test_old_apk_with_forged_current_provenance_and_matching_report_hash_rejected(self):
        self.apk.write_bytes(b'older same-key same-ID APK')
        value = json.loads(self.report.read_text())
        value['sha256'] = probe.hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.report.write_text(json.dumps(value))
        with self.assertRaisesRegex(probe.ProbeError, 'independent build receipt'):
            self.validate()

    def test_self_consistent_digest_approved_signer_but_wrong_actual_package(self):
        self.actual_package = 'com.gezimos.katapult.debug'
        with self.assertRaises(probe.ProbeError):
            self.validate()

    def test_self_consistent_report_but_wrong_actual_variant(self):
        self.debuggable = 'false'
        with self.assertRaises(probe.ProbeError):
            self.validate()
