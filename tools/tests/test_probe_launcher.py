import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

spec=importlib.util.spec_from_file_location('probe_launcher',Path(__file__).parents[1]/'probe_launcher.py')
probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)


class ReportValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.apk=self.root/'app.apk';self.apk.write_bytes(b'fixture')
        self.cert='a'*64;self.report=self.root/'report.json'
        self.provenance={'head':'a'*40,'headTree':'b'*40,'sourceReceiptSha256':'c'*64}
        self.value={'path':str(self.apk),'sha256':probe.hashlib.sha256(b'fixture').hexdigest(),'variant':'debug','applicationId':probe.EXPECTED_IDS['debug'],
            'sourceProvenance':self.provenance,'signerSha256':self.cert,'backupContractPreserved':True,'shortcutIntentVerified':True,'artworkStatus':'graphics deferred; original source assets preserved'}
        self.report.write_text(json.dumps(self.value));self.patch=mock.patch.object(probe,'run',side_effect=self.tool);self.patch.start()
    def tearDown(self):self.patch.stop();self.temp.cleanup()
    def tool(self,*args):
        if 'verify' in args:return 'Signer #1 certificate SHA-256 digest: '+self.cert+'\n'
        if args[1:3]==('manifest','print'):return '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.alexindigo.kotatsu.debug"><application android:debuggable="true" /></manifest>'
        raise AssertionError(args)
    def validate(self):return probe.validate_report(self.report,'debug',probe.EXPECTED_IDS['debug'],self.cert,'/sdk/apksigner',self.provenance,probe.hashlib.sha256(b'fixture').hexdigest())
    def test_valid_report_and_actual_signer(self):self.assertEqual(self.validate()[1],self.apk)
    def test_self_consistent_wrong_report_signer_is_not_approval(self):
        self.value['signerSha256']='b'*64;self.report.write_text(json.dumps(self.value))
        with self.assertRaises(probe.ProbeError):self.validate()
    def test_self_consistent_wrong_report_package_or_variant(self):
        for key,value in (('variant','release'),('applicationId','evil.package')):
            old=self.value[key];self.value[key]=value;self.report.write_text(json.dumps(self.value))
            with self.assertRaises(probe.ProbeError):self.validate()
            self.value[key]=old
    def test_wrong_actual_apk_signer(self):
        with mock.patch.object(probe,'run',return_value='Signer #1 certificate SHA-256 digest: '+'b'*64+'\n'):
            with self.assertRaises(probe.ProbeError):self.validate()
    def test_digest_change_is_rejected(self):
        self.apk.write_bytes(b'changed')
        with self.assertRaises(probe.ProbeError):self.validate()
    def test_symlink_apk_or_missing_signer_is_rejected(self):
        other=self.root/'other.apk';other.write_bytes(b'fixture');self.apk.unlink();self.apk.symlink_to(other)
        with self.assertRaises(probe.ProbeError):self.validate()


class PublicHierarchyTest(unittest.TestCase):
    def test_home_observation_does_not_require_hidden_apps_button(self):
        ui=probe.PublicUi(Path('/owned/adb'),'emulator-5554','package',Path('/tmp/report.json'))
        root=ET.fromstring('<hierarchy><node text="12:34" package="package"/><node text="Phone" package="package"/><node text="SMS" package="package"/><node text="Contacts" package="package"/></hierarchy>')
        self.assertTrue(ui.is_home(root))
        ET.SubElement(root,'node',{'text':'Finish'})
        self.assertFalse(ui.is_home(root))
    def test_observed_api31_messaging_label_is_home_but_a_grid_of_apps_is_not(self):
        ui=probe.PublicUi(Path('/owned/adb'),'emulator-5554','io.github.alexindigo.kotatsu',Path('/tmp/report.json'))
        root=ET.fromstring('<hierarchy><node text="5:09" package="io.github.alexindigo.kotatsu"/><node text="Phone" package="io.github.alexindigo.kotatsu"/><node text="Messaging" package="io.github.alexindigo.kotatsu"/><node content-desc="Apps" package="io.github.alexindigo.kotatsu"/></hierarchy>')
        self.assertTrue(ui.is_home(root))
        root.remove(root.findall('node')[0])
        self.assertFalse(ui.is_home(root))
    def test_exact_current_label_and_ambiguity(self):
        ui=probe.PublicUi(Path('/owned/adb'),'emulator-5554','package',Path('/tmp/report.json'))
        root=ET.fromstring('<hierarchy><node text="Apps" bounds="[1,2][3,4]"/><node text="Settings"/></hierarchy>')
        self.assertIsNotNone(ui.find('Apps',root));self.assertIsNone(ui.find('App',root))
        self.assertIsNotNone(ui.wait('Leaf control exists',lambda:ui.find('Apps',root),timeout=.01))
        duplicate=ET.fromstring('<hierarchy><node text="Apps"/><node text="Apps"/></hierarchy>')
        with self.assertRaises(probe.ProbeError):ui.find('Apps',duplicate)
    def test_failed_public_command_does_not_round_to_success(self):
        class Result:returncode=1;stdout='';stderr='failure'
        with mock.patch.object(probe.subprocess,'run',return_value=Result()):
            with self.assertRaises(probe.ProbeError):probe.run('adb')


if __name__=='__main__':unittest.main()
