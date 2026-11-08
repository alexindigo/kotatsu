"""Three-APK source, compiled identity and publication boundary regressions."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock

spec=importlib.util.spec_from_file_location('verify_fork',Path(__file__).parents[1]/'verify_fork.py')
verify=importlib.util.module_from_spec(spec);spec.loader.exec_module(verify)
spec=importlib.util.spec_from_file_location('prepare_release',Path(__file__).parents[1]/'prepare_release.py')
prepare=importlib.util.module_from_spec(spec);spec.loader.exec_module(prepare)

APPS={'app':('com.alexindigo.kotatsu',14,'Kotatsu'),
      'kotatsu-weather':('com.alexindigo.kotatsu.weather',9,'Kotatsu Weather'),
      'kotatsu-notes':('com.alexindigo.kotatsu.notes',9,'Kotatsu Notes')}


class ReleaseIdentityTest(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.root=Path(temp.name)
        repo=Path(__file__).parents[2]
        shutil.copyfile(repo/'settings.gradle.kts',self.root/'settings.gradle.kts')
        self.metadata={};self.badging={};self.packages={};self.certs={};self.calls=[]
        self.args=argparse.Namespace(root=str(self.root),baseline=verify.BASELINE,variant='release',inspect_unsigned=True,
            apkanalyzer='analyzer',aapt2='aapt2',apksigner='signer',expected_cert_sha256='a'*64,report=str(self.root/'report.json'))
        for module,(package,code,label) in APPS.items():
            source=self.root/module/'src/main/res/values';source.mkdir(parents=True)
            shutil.copyfile(repo/module/'build.gradle.kts',self.root/module/'build.gradle.kts')
            shutil.copyfile(repo/module/'src/main/res/values/strings.xml',source/'strings.xml')
            directory=self.root/module/'build/outputs/apk/release';directory.mkdir(parents=True)
            (directory/'fixture.apk').write_bytes((module+' release bytes').encode())
            self.metadata[module]={'variantName':'release','applicationId':package,'elements':[{'outputFile':'fixture.apk','versionCode':code,'versionName':'0.5.2'}]}
            self.write_metadata(module)
            self.packages[module]=package;self.certs[module]='a'*64
            self.badging[module]=f"package: name='{package}' versionCode='{code}' versionName='0.5.2'\napplication-label:'{label}'\n"
        self.provenance={'head':'a'*40,'headTree':'b'*40,'sourceReceiptSha256':'c'*64}

    def write_metadata(self,module):
        (self.root/module/'build/outputs/apk/release/output-metadata.json').write_text(json.dumps(self.metadata[module]))

    def tool(self,*args):
        self.calls.append(args)
        module=Path(args[-1]).relative_to(self.root).parts[0]
        if args[0]=='signer':return 'Signer #1 certificate SHA-256 digest: '+self.certs[module]+'\n'
        if args[1:3]==('manifest','print'):return '<manifest package="'+self.packages[module]+'"><application/></manifest>'
        if args[1:3]==('dump','badging'):return self.badging[module]
        if args[1:3]==('manifest','min-sdk'):return '30'
        if args[1:3]==('manifest','target-sdk'):return '36'
        raise AssertionError(args)

    def check(self):
        with mock.patch.object(verify,'execution_identity',return_value=self.provenance),mock.patch.object(verify,'command',side_effect=self.tool):
            verify.release_identity(self.args)
        return json.loads(Path(self.args.report).read_text())

    def test_source_inventory_and_versions_cover_every_app(self):
        self.assertEqual(set(verify.source_release_identities(self.root)),set(APPS))
        for module in APPS:
            path=self.root/module/'build.gradle.kts';original=path.read_text()
            for old,new in (('versionName = "0.5.2"','versionName = "stale"'),
                            ('versionCode = '+str(APPS[module][1]),'versionCode = 1'),
                            ('applicationId = "'+APPS[module][0]+'"','applicationId = "other.app"')):
                with self.subTest(module=module,field=old):
                    path.write_text(original.replace(old,new))
                    with self.assertRaises(verify.VerificationError):verify.source_release_identities(self.root)
                    path.write_text(original)

    def test_missing_or_extra_application_module_fails_closed(self):
        path=self.root/'settings.gradle.kts';path.write_text(path.read_text().replace('include(":kotatsu-notes")',''))
        with self.assertRaisesRegex(verify.VerificationError,'inventory'):verify.source_release_identities(self.root)
        shutil.copyfile(Path(__file__).parents[2]/'settings.gradle.kts',path)
        (self.root/'extra').mkdir();(self.root/'extra/build.gradle.kts').write_text('alias(libs.plugins.android.application)')
        with self.assertRaisesRegex(verify.VerificationError,'inventory'):verify.source_release_identities(self.root)

    def test_unsigned_inspection_reads_all_three_without_claiming_signing_or_build(self):
        report=self.check()
        self.assertEqual(set(report['apps']),set(APPS))
        for module,(package,code,_) in APPS.items():
            self.assertEqual(report['apps'][module]['applicationId'],package)
            self.assertEqual(report['apps'][module]['versionCode'],code)
            apk=Path(report['apps'][module]['path'])
            self.assertEqual(report['apps'][module]['sha256'],verify.sha(apk.read_bytes()))
        self.assertTrue(report['inspectionOnly']);self.assertFalse(report['signaturesVerified'])
        self.assertIsNone(report['buildProvenance'])
        self.assertEqual(len([c for c in self.calls if c[1:3]==('dump','badging')]),3)

    def test_companion_metadata_and_compiled_versions_cannot_agree_on_stale_release(self):
        for module in ('kotatsu-weather','kotatsu-notes'):
            original=copy.deepcopy(self.metadata[module]);badging=self.badging[module]
            with self.subTest(module=module):
                self.metadata[module]['elements'][0]['versionName']='0.5.1';self.write_metadata(module)
                self.badging[module]=badging.replace('0.5.2','0.5.1')
                with self.assertRaisesRegex(verify.VerificationError,'metadata version'):self.check()
                self.metadata[module]=original;self.write_metadata(module)
                with self.assertRaisesRegex(verify.VerificationError,'Compiled APK version'):self.check()
                self.badging[module]=badging
        self.assertFalse(Path(self.args.report).exists())

    def test_companion_package_label_and_debuggability_are_checked(self):
        for module in ('kotatsu-weather','kotatsu-notes'):
            original=self.badging[module]
            for bad in (original.replace(APPS[module][2],'Wrong label'),original+'application-debuggable\n',original.replace("versionCode='9'","versionCode='1'")):
                self.badging[module]=bad
                with self.subTest(module=module),self.assertRaises(verify.VerificationError):self.check()
            self.badging[module]=original
            self.packages[module]='other.package'
            with self.assertRaisesRegex(verify.VerificationError,'compiled package'):self.check()
            self.packages[module]=APPS[module][0]

    def test_signed_gate_rejects_companion_signers_before_publishing(self):
        self.args.inspect_unsigned=False
        for module in ('kotatsu-weather','kotatsu-notes'):
            self.certs[module]='b'*64
            with self.subTest(module=module),self.assertRaisesRegex(verify.VerificationError,'signer mismatch'):self.check()
            self.certs[module]='a'*64
        self.assertFalse(Path(self.args.report).exists())

    def test_signed_release_requires_full_build_receipt(self):
        self.args.inspect_unsigned=False;self.args.build_receipt=None;self.args.expected_build_receipt_sha256=None
        with self.assertRaisesRegex(verify.VerificationError,'build receipt'):self.check()

    def test_debug_set_respects_each_apps_existing_suffixes(self):
        self.args.variant='debug';self.args.inspect_unsigned=False
        for module,(package,code,label) in APPS.items():
            directory=self.root/module/'build/outputs/apk/release'
            directory.rename(directory.parent/'debug')
            version='0.5.2-debug' if module=='app' else '0.5.2'
            if module=='app':label+=' Debug'
            self.packages[module]=package+'.debug'
            self.badging[module]=f"package: name='{package}.debug' versionCode='{code}' versionName='{version}'\napplication-label:'{label}'\napplication-debuggable\n"
            metadata=self.metadata[module];metadata.update(variantName='debug',applicationId=package+'.debug')
            metadata['elements'][0]['versionName']=version
            (directory.parent/'debug/output-metadata.json').write_text(json.dumps(metadata))
        report=self.check()
        self.assertTrue(report['signaturesVerified'])
        self.assertEqual(report['apps']['app']['versionName'],'0.5.2-debug')
        self.assertEqual(report['apps']['kotatsu-weather']['versionName'],'0.5.2')
        self.assertEqual(report['apps']['kotatsu-notes']['versionName'],'0.5.2')

    def test_missing_or_duplicate_companion_apk_prevents_complete_set_report(self):
        for module in ('kotatsu-weather','kotatsu-notes'):
            directory=self.root/module/'build/outputs/apk/release'
            apk=directory/'fixture.apk';data=apk.read_bytes();apk.unlink()
            with self.assertRaises(verify.VerificationError):self.check()
            apk.write_bytes(data);extra=directory/'stale.apk';extra.write_bytes(b'stale')
            with self.assertRaises(verify.VerificationError):self.check()
            extra.unlink()

    def test_publication_names_are_complete_and_copy_exact_metadata_selected_bytes(self):
        output=self.root/'published'
        prepare.prepare(self.root,'v0.5.2',output)
        self.assertEqual({p.name for p in output.iterdir()},{'kotatsu-v0.5.2.apk','kotatsu-weather-v0.5.2.apk','kotatsu-notes-v0.5.2.apk'})
        for module in APPS:
            name=('kotatsu' if module=='app' else module)+'-v0.5.2.apk'
            self.assertEqual((output/name).read_bytes(),(module+' release bytes').encode())

    def test_wrong_tag_or_incomplete_set_creates_no_publication(self):
        output=self.root/'published'
        with self.assertRaisesRegex(prepare.VerificationError,'tag'):prepare.prepare(self.root,'v0.6',output)
        self.metadata['kotatsu-notes']['elements'][0]['versionCode']=1;self.write_metadata('kotatsu-notes')
        with self.assertRaisesRegex(prepare.VerificationError,'metadata version'):prepare.prepare(self.root,'v0.5.2',output)
        self.assertFalse(output.exists())


if __name__=='__main__':unittest.main()
