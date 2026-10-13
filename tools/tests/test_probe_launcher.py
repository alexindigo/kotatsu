import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zipfile

spec=importlib.util.spec_from_file_location('probe_launcher',Path(__file__).parents[1]/'probe_launcher.py')
probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)


def approved_report_fixture(root,cert,provenance):
    verify=probe.verify_fork
    packaged={'schemaVersion':1,'status':'verified','bundleIndexSha256':verify.APPROVED_BUNDLE_INDEX_SHA256,'noEncodingCorruption':True,
              'manifestIcons':{'icon':'mipmap/ic_launcher','roundIcon':'mipmap/ic_launcher_round'},'adaptive':{},'vectors':{},'legacyBitmaps':{}}
    payloads={}
    for i,name in enumerate(verify.ICON_NAMES):
        file=f'res/w{i}.xml';payloads[file]=b'compiled wrapper'
        packaged['adaptive'][name]={'zipPath':file,'foreground':'drawable/ic_launcher_foreground','monochrome':'drawable/ic_launcher_monochrome','background':'color/ic_launcher_background','backgroundValue':'#ffffffff'}
        packaged['legacyBitmaps'][name]={}
        for density in verify.DENSITIES:
            source=f'app/src/main/res/mipmap-{density}/{name.split("/")[1]}.webp';file=f'res/{i}-{density}.webp'
            payloads[file]=(Path(__file__).parents[2]/source).read_bytes()
            packaged['legacyBitmaps'][name][density]={'zipPath':file,'sha256':verify.APPROVED_GRAPHICS_SHA256[source]}
    for i,(name,count) in enumerate(verify.VECTOR_COUNTS.items()):
        file=f'res/v{i}.xml';payloads[file]=b'compiled vector'
        packaged['vectors'][name]={'zipPath':file,'sourceSha256':verify.APPROVED_GRAPHICS_SHA256['app/src/main/res/'+name+'.xml'],'pathCount':count,'verified':True,'decodedXmlSha256':'a'*64,'xmltreeSha256':'b'*64,'semanticSha256':'c'*64}
    apk=root/'app.apk'
    with zipfile.ZipFile(apk,'w') as a:
        for file,data in payloads.items():a.writestr(file,data)
    checks={'status':verify.SOURCE_ARTWORK_STATUS,'assetCount':27,'replacementCount':18,'indexSha256':verify.ORIGINAL_INDEX_SHA256,
            'approvedBundleIndexSha256':verify.APPROVED_BUNDLE_INDEX_SHA256,'approvedSourceMasterSha256':verify.APPROVED_SOURCE_MASTER_SHA256,'packagedBranding':packaged}
    value={'path':str(apk),'sha256':verify.sha(apk.read_bytes()),'variant':'debug','applicationId':probe.EXPECTED_IDS['debug'],
           'sourceProvenance':provenance,'signerSha256':cert,'backupContractPreserved':True,'shortcutIntentVerified':True,'artworkStatus':verify.PACKAGED_ARTWORK_STATUS,'checks':checks}
    return value,apk


class ReportValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.cert='a'*64;self.report=self.root/'report.json'
        self.provenance={'head':'a'*40,'headTree':'b'*40,'sourceReceiptSha256':'c'*64}
        self.value,self.apk=approved_report_fixture(self.root,self.cert,self.provenance)
        self.expected_sha=self.value['sha256']
        self.report.write_text(json.dumps(self.value));self.patch=mock.patch.object(probe,'run',side_effect=self.tool);self.patch.start()
        self.validate()
    def tearDown(self):self.patch.stop();self.temp.cleanup()
    def tool(self,*args):
        if 'verify' in args:return 'Signer #1 certificate SHA-256 digest: '+self.cert+'\n'
        if args[1:3]==('manifest','print'):return '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.alexindigo.kotatsu.debug"><application android:debuggable="true" /></manifest>'
        raise AssertionError(args)
    def validate(self):return probe.validate_report(self.report,'debug',probe.EXPECTED_IDS['debug'],self.cert,'/sdk/apksigner',self.provenance,self.expected_sha)
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

    def test_status_provenance_count_and_packaged_evidence_fail_closed(self):
        import copy
        saved=copy.deepcopy(self.value)
        mutations=[('status',None),('bundle',None),('assetCount',True),('replacementCount',True),('packaged',None),('corrupt',None),('vectors',None),('density',None),('verified',None),('pathCount',None),('digest',None),('path',None)]
        for case,_ in mutations:
            self.value=copy.deepcopy(saved)
            if case=='status':self.value['artworkStatus']='graphics deferred; original source assets preserved'
            elif case=='bundle':self.value['checks']['approvedBundleIndexSha256']='0'*64
            elif case in ('assetCount','replacementCount'):self.value['checks'][case]=True
            elif case=='packaged':del self.value['checks']['packagedBranding']
            else:
                p=self.value['checks']['packagedBranding']
                if case=='corrupt':p['noEncodingCorruption']=False
                elif case=='vectors':p['vectors'].pop('drawable/ic_launcher_monochrome')
                elif case=='density':p['legacyBitmaps']['mipmap/ic_launcher'].pop('mdpi')
                elif case=='verified':p['vectors']['drawable/ic_launcher_foreground']['verified']=False
                elif case=='pathCount':p['vectors']['drawable/ic_launcher_foreground']['pathCount']=True
                elif case=='digest':p['vectors']['drawable/katapult_logo']['xmltreeSha256']='not-a-digest'
                else:p['vectors']['drawable/katapult_logo']['zipPath']='res/not-present.xml'
            self.report.write_text(json.dumps(self.value))
            with self.subTest(case=case),self.assertRaises(probe.ProbeError):self.validate()


class PublicHierarchyTest(unittest.TestCase):
    def test_home_observation_does_not_require_hidden_apps_button(self):
        ui=probe.PublicUi(Path('/owned/adb'),'emulator-5554','package',Path('/tmp/report.json'))
        root=ET.fromstring('<hierarchy><node text="12:34" package="package"/><node text="Phone" package="package"/><node text="SMS" package="package"/><node text="Contacts" package="package"/></hierarchy>')
        self.assertTrue(ui.is_home(root))
        ET.SubElement(root,'node',{'text':'Finish'})
        self.assertFalse(ui.is_home(root))
    def test_observed_api31_messaging_label_is_home_but_a_grid_of_apps_is_not(self):
        ui=probe.PublicUi(Path('/owned/adb'),'emulator-5554','com.alexindigo.kotatsu',Path('/tmp/report.json'))
        root=ET.fromstring('<hierarchy><node text="5:09" package="com.alexindigo.kotatsu"/><node text="Phone" package="com.alexindigo.kotatsu"/><node text="Messaging" package="com.alexindigo.kotatsu"/><node content-desc="Apps" package="com.alexindigo.kotatsu"/></hierarchy>')
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
