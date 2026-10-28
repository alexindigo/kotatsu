"""Exercise complete artifact checks with real ZIP/metadata and SDK-output fixtures."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import struct
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
        self.payloads={name:b'compiled fixture' for name in ('res/a.xml','res/b.xml','res/c.xml')}
        self.metadata = {'variantName': 'debug', 'applicationId': self.package, 'elements': [{'outputFile': self.apk.name}]}
        (self.directory / 'output-metadata.json').write_text(json.dumps(self.metadata))
        self.manifest = ET.fromstring(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{self.package}">
          <uses-feature android:name="android.hardware.telephony" android:required="false" />
          <application android:allowBackup="true" android:fullBackupContent="@ref/0x7f000001" android:dataExtractionRules="@ref/0x7f000002" android:icon="@ref/0x7f000005" android:roundIcon="@ref/0x7f000006">
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
        self.resources+='\n  resource 0x7f000004 color/ic_launcher_background\n    () #ffffffff\n'
        wrapper='<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@ref/0x7f000004"/><foreground android:drawable="@ref/0x7f000007"/><monochrome android:drawable="@ref/0x7f000008"/></adaptive-icon>'
        for i,name in enumerate(verify.ICON_NAMES,5):
            file=f'res/w{i}.xml';self.xml[file]=wrapper;self.payloads[file]=b'wrapper'
            self.resources+=f'  resource 0x7f00000{i} {name}\n    (anydpi) (file) {file} type=XML\n'
            for density in verify.DENSITIES:
                file=f'res/{i}-{density}.webp';data=(Path(__file__).parents[2]/f'app/src/main/res/mipmap-{density}/{name.split("/")[1]}.webp').read_bytes()
                self.payloads[file]=data;self.resources+=f'    ({density}) (file) {file}\n'
        for i,name in enumerate(verify.VECTOR_COUNTS,7):
            path='app/src/main/res/'+name+'.xml';data=(Path(__file__).parents[2]/path).read_bytes()
            p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
            file=f'res/v{i}.xml';self.xml[file]=data.decode();self.payloads[file]=b'compiled vector'
            self.resources+=f'  resource 0x7f00000{i} {name}\n    () (file) {file} type=XML\n'
        self.write_zip()
        self.version = '0.5.1-debug'
        self.labels = {locale.lstrip('-') or 'default': 'Kotatsu Debug' for locale in verify.LOCALES}
        self.debuggable = True
        self.min_sdk = '30'
        self.target_sdk = '36'
        self.args = argparse.Namespace(root=str(self.root), baseline=verify.BASELINE, deferred_assets_index='index.json',
            apk_dir=str(self.directory), variant='debug', expected_cert_sha256=self.cert, apkanalyzer='/sdk/apkanalyzer',
            aapt2='/sdk/aapt2', apksigner='/sdk/apksigner', report=str(self.root / 'report.json'))
        checks={'status':verify.SOURCE_ARTWORK_STATUS,'assetCount':27,'replacementCount':18,'indexSha256':verify.ORIGINAL_INDEX_SHA256,
                'approvedBundleIndexSha256':verify.APPROVED_BUNDLE_INDEX_SHA256,'approvedSourceMasterSha256':verify.APPROVED_SOURCE_MASTER_SHA256}
        for patch in (mock.patch.object(verify, 'source_identity', return_value=checks),
                      mock.patch.object(verify, 'execution_identity', return_value={'head': 'fixture'}),
                      mock.patch.object(verify, 'command', side_effect=self.tool),
                      mock.patch.object(verify, 'baseline_bytes', side_effect=self.baseline)):
            patch.start()
            self.addCleanup(patch.stop)
        # Every negative flow starts from an independently exercised valid fixture.
        verify.apk_identity(self.args)
        Path(self.args.report).unlink()

    def write_zip(self):
        with zipfile.ZipFile(self.apk,'w') as archive:
            for name,data in self.payloads.items():archive.writestr(name,data)

    def xmltree(self,file):
        lines=['N: android=http://schemas.android.com/apk/res/android (line=2)']
        def node(element,level):
            indent='  '*level;lines.append(indent+'E: '+element.tag+' (line=2)')
            for key,value in element.attrib.items():
                key=key.removeprefix(verify.ANDROID)
                if key in ('pathData','name'):value=json.dumps(value)
                elif key=='fillType':value={'nonZero':'0','evenOdd':'1'}[value]
                elif key=='fillColor':value=value.lower()
                else:
                    suffix='dp' if value.endswith('dp') else ''
                    value=format(struct.unpack('<f',struct.pack('<f',float(value.removesuffix('dp'))))[0],'.6g')+suffix
                lines.append(indent+'  A: http://schemas.android.com/apk/res/android:'+key+'(0x01010001)='+value)
            for child in element:node(child,level+2)
        node(ET.fromstring(self.xml[file]),1)
        return '\n'.join(lines)+'\n'

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
            return f"package: name='{self.package}' versionCode='13' versionName='{self.version}'\n{labels}\n" + ('application-debuggable\n' if self.debuggable else '')
        if args[1:3] == ('manifest', 'min-sdk'):
            return self.min_sdk
        if args[1:3] == ('manifest', 'target-sdk'):
            return self.target_sdk
        if args[1:3] == ('dump', 'resources'):
            return self.resources
        if args[1:3] == ('resources', 'xml'):
            return self.xml[args[4]]
        if args[1:3] == ('dump','xmltree'):
            return self.xmltree(args[5])
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
        verify.validate_packaged_branding(report['checks']['packagedBranding'],set(self.payloads))
        self.assertEqual(report['artworkStatus'],verify.PACKAGED_ARTWORK_STATUS)
        self.assertFalse(report['offlineCertified'])

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
        for wrong_version in ('1.6-debug', '2.0'):
            self.version = wrong_version
            self.rejected('Compiled APK version/ID mismatch')
        self.version = '0.5.1-debug'
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
        self.version = '0.5.1'
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

    def test_compiled_emblem_and_wordmark_sentinel_old_geometry_fill_and_groups_rejected(self):
        for file in ('res/v7.xml','res/v8.xml','res/v9.xml'):
            saved=self.xml[file]
            for mutation in ('sentinel','old','fill','transform','missing-path','flatten'):
                node=ET.fromstring(saved);path=next(node.iter('path'))
                if mutation=='sentinel':path.set(verify.ANDROID+'pathData','STRING_TOO_LARGE')
                elif mutation=='old':path.set(verify.ANDROID+'pathData','M0 0L1 1Z')
                elif mutation=='fill':path.set(verify.ANDROID+'fillType','evenOdd' if path.get(verify.ANDROID+'fillType')=='nonZero' else 'nonZero')
                elif mutation=='transform':node.find('group').set(verify.ANDROID+'scaleX','0.7')
                elif mutation=='missing-path':
                    parents={c:p for p in node.iter() for c in p};parents[path].remove(path)
                else:
                    group=node.find('group');node.remove(group);node.extend(list(group))
                self.xml[file]=ET.tostring(node,encoding='unicode')
                with self.subTest(file=file,mutation=mutation),self.assertRaises(verify.VerificationError):verify.apk_identity(self.args)
                self.assertFalse(Path(self.args.report).exists());self.xml[file]=saved

    def test_both_decoders_are_mandatory(self):
        original=self.tool
        def tool(*args):
            if args[1:3]==('dump','xmltree'):return self.xmltree(args[5]).replace('=108','=109',1)
            return original(*args)
        with mock.patch.object(verify,'command',side_effect=tool):self.rejected('float32 attribute differs')

    def test_actual_all_density_payloads_and_configuration_coverage(self):
        for name in [p for p in self.payloads if p.endswith('.webp')]:
            saved=self.payloads[name];self.payloads[name]=b'substituted';self.write_zip()
            with self.subTest(path=name):self.rejected('Substituted packaged WebP')
            self.payloads[name]=saved
        self.write_zip();saved=self.resources
        self.resources=self.resources.replace('    (mdpi) (file) res/5-mdpi.webp\n','');self.rejected('launcher configurations')
        self.resources=saved.replace('    (mdpi) (file) res/5-mdpi.webp\n','    (mdpi) (file) res/5-mdpi.webp\n    (mdpi) (file) res/5-mdpi.webp\n');self.rejected('Duplicate file configuration')

    def test_actual_wrong_manifest_layers_white_color_and_unsafe_paths(self):
        saved=self.resources
        self.manifest.find('application').set(verify.ANDROID+'icon','@ref/0x7f000007');self.rejected('wrong-type compiled branding')
        self.manifest.find('application').set(verify.ANDROID+'icon','@ref/0x7f000005')
        self.resources=saved.replace('#ffffffff','#ff000000');self.rejected('white background')
        self.resources=saved.replace('res/v7.xml','../v7.xml');self.rejected('Unsafe branding ZIP path')
        self.resources=saved
        self.xml['res/w5.xml']=self.xml['res/w5.xml'].replace('@ref/0x7f000008','@ref/0x7f000007');self.rejected('wrong-type compiled branding')

    def test_duplicate_zip_member_cannot_publish(self):
        with zipfile.ZipFile(self.apk,'a') as archive:archive.writestr('res/v7.xml',b'duplicate')
        self.rejected('Duplicate archive entries')
