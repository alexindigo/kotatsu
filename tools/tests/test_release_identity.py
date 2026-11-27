"""Four-APK source, compiled identity and publication boundary regressions."""
import argparse
import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0,str(Path(__file__).parents[1]))
import prepare_release as prepare
import verify_fork as verify

APPS={'app':('com.alexindigo.kotatsu',18,'Kotatsu'),
       'kotatsu-weather':('com.alexindigo.kotatsu.weather',13,'Kotatsu Weather'),
       'kotatsu-notes':('com.alexindigo.kotatsu.notes',12,'Kotatsu Notes'),
       'kotatsu-sync':('com.alexindigo.kotatsu.sync',12,'Kotatsu Sync')}
ASSETS={'app':'kotatsu-v0.7.1.apk','kotatsu-weather':'kotatsu-weather-v0.7.1.apk',
        'kotatsu-notes':'kotatsu-notes-v0.7.1.apk','kotatsu-sync':'kotatsu-sync-v0.7.1.apk'}
COMPANIONS=('kotatsu-weather','kotatsu-notes','kotatsu-sync')
SYNC='kotatsu-sync'
NON_RELEASE_MODULES=('fake-mudita-calendar',)
NON_APP_MODULES=('paper-ui','weather-api','weather-mudita')
REPO=Path(__file__).resolve().parents[2]


class ReleaseIdentityTest(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.root=Path(temp.name)
        shutil.copyfile(REPO/'settings.gradle.kts',self.root/'settings.gradle.kts')
        self.manifest=self.root/'gradle/modules.txt';self.manifest.parent.mkdir()
        shutil.copyfile(REPO/'gradle/modules.txt',self.manifest)
        for module in (*NON_RELEASE_MODULES,*NON_APP_MODULES):
            (self.root/module).mkdir()
            shutil.copyfile(REPO/module/'build.gradle.kts',self.root/module/'build.gradle.kts')
        self.metadata={};self.badging={};self.packages={};self.certs={};self.calls=[]
        self.args=argparse.Namespace(root=str(self.root),baseline=verify.BASELINE,variant='release',inspect_unsigned=True,
            apkanalyzer='analyzer',aapt2='aapt2',apksigner='signer',expected_cert_sha256='a'*64,report=str(self.root/'report.json'))
        for module,(package,code,label) in APPS.items():
            source=self.root/module/'src/main/res/values';source.mkdir(parents=True)
            shutil.copyfile(REPO/module/'build.gradle.kts',self.root/module/'build.gradle.kts')
            shutil.copyfile(REPO/module/'src/main/res/values/strings.xml',source/'strings.xml')
            directory=self.root/module/'build/outputs/apk/release';directory.mkdir(parents=True)
            (directory/'fixture.apk').write_bytes((module+' release bytes').encode())
            self.metadata[module]={'variantName':'release','applicationId':package,'elements':[{'outputFile':'fixture.apk','versionCode':code,'versionName':'0.7.1'}]}
            self.write_metadata(module)
            self.packages[module]=package;self.certs[module]='a'*64
            self.badging[module]=f"package: name='{package}' versionCode='{code}' versionName='0.7.1'\napplication-label:'{label}'\n"
        self.provenance={'head':'a'*40,'headTree':'b'*40,'sourceReceiptSha256':'c'*64}
        self.badging[SYNC]+=''.join(f"application-label-{locale}:'Kotatsu Sync'\n" for locale in 'cs de es fr it nl pl pt'.split())
        self.sync_manifest=ET.parse(REPO/SYNC/'src/main/AndroidManifest.xml').getroot()
        self.sync_manifest.set('package',APPS[SYNC][0])
        self.sync_resources='Package name=com.alexindigo.kotatsu.sync id=7f\n  resource 0x7f010001 string/app_name\n    () "Kotatsu Sync"\n'
        self.sync_dex='P d 9 9 999 <TOTAL>\n'+''.join(
            f'C d 1 1 99 {APPS[SYNC][0]}' + node.get(verify.ANDROID+'name')+'\n'
            for node in self.sync_manifest.find('application').iter()
            if node.tag in ('application','activity','service','receiver'))
        self.mapping=self.root/SYNC/'build/outputs/mapping/release/mapping.txt'
        self.mapping.parent.mkdir(parents=True);self.mapping.write_text('Sync release mapping\n')
        self.sync_apk=self.root/SYNC/'build/outputs/apk/release/fixture.apk'
        self.notices={}
        for source in ('kotatsu-sync/NOTICE','paper-ui/LICENSE-Lato.txt'):
            data=(REPO/source).read_bytes();(self.root/source).write_bytes(data)
            asset='assets/notices/'+Path(source).name
            target=self.root/SYNC/'src/main'/asset
            target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
            self.notices[asset]=data
        self.write_sync_apk(self.notices.items())

    def write_sync_apk(self,notices):
        with zipfile.ZipFile(self.sync_apk,'w') as archive:
            for name,data in notices:archive.writestr(name,data)

    def write_metadata(self,module):
        (self.root/module/'build/outputs/apk/release/output-metadata.json').write_text(json.dumps(self.metadata[module]))

    def tool(self,*args):
        self.calls.append(args)
        module=Path(args[-1]).relative_to(self.root).parts[0]
        if args[0]=='signer':return 'Signer #1 certificate SHA-256 digest: '+self.certs[module]+'\n'
        if args[1:3]==('manifest','print'):
            if module==SYNC:
                self.sync_manifest.set('package',self.packages[module])
                return ET.tostring(self.sync_manifest,encoding='unicode')
            return '<manifest package="'+self.packages[module]+'"><application/></manifest>'
        if args[1:3]==('dump','badging'):return self.badging[module]
        if args[1:3]==('dump','resources') and module==SYNC:return self.sync_resources
        if args[1:3]==('dex','packages') and module==SYNC:
            self.assertEqual(args[3:-1],('--defined-only','--proguard-mappings',str(self.mapping)))
            return self.sync_dex
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
            for old,new in (('versionName = "0.7.1"','versionName = "stale"'),
                            ('versionCode = '+str(APPS[module][1]),'versionCode = 1'),
                            ('applicationId = "'+APPS[module][0]+'"','applicationId = "other.app"')):
                with self.subTest(module=module,field=old):
                    path.write_text(original.replace(old,new))
                    with self.assertRaises(verify.VerificationError):verify.source_release_identities(self.root)
                    path.write_text(original)

    def inventory_rejected(self,message):
        output=self.root/'published'
        with self.assertRaisesRegex(prepare.VerificationError,message):prepare.prepare(self.root,'v0.7.1',output)
        with self.assertRaisesRegex(verify.VerificationError,message):self.check()
        self.assertFalse(output.exists())
        self.assertFalse(Path(self.args.report).exists())
        self.assertEqual(self.calls,[])

    def test_real_source_tree_has_exactly_four_release_identities(self):
        self.assertEqual({m:(row['applicationId'],row['versionCode'],row['versionName'])
                          for m,row in verify.source_release_identities(REPO).items()},
                         {m:(package,code,'0.7.1') for m,(package,code,_) in APPS.items()})

    def test_unknown_module_fails_even_without_any_plugin_declaration(self):
        for module in ('extra','extra/nested'):
            directory=self.root/module;directory.mkdir()
            for name in ('build.gradle.kts','build.gradle'):
                with self.subTest(module=module,script=name):
                    path=directory/name;path.write_text('')
                    self.inventory_rejected('Unclassified Gradle module.*'+module)
                    path.unlink()

    def add_non_app_build(self,name,script):
        path=self.root/'extra'/name;path.parent.mkdir()
        path.write_text(script)
        with self.manifest.open('a') as manifest:manifest.write('extra=non-app\n')

    def test_groovy_builds_without_an_active_application_plugin_are_accepted(self):
        self.add_non_app_build('build.gradle',
            "// apply plugin: 'com.android.application'\n"
            "plugins { id 'com.android.application' version '8.13.2' apply false }\n")
        self.assertEqual(set(verify.source_release_identities(self.root)),set(APPS))

    def test_generated_gradle_builds_are_not_application_modules(self):
        for directory in ('build','app/build/generated','extras/.gradle/cache','app/.cxx/generated'):
            path=self.root/directory/'build.gradle.kts'
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text('plugins { id("com.android.application") }')
            path.with_name('build.gradle').write_text("apply plugin: 'com.android.application'")
        self.assertEqual(set(verify.source_release_identities(self.root)),set(APPS))

    def test_method_call_apply_false_does_not_make_a_module_an_application(self):
        self.add_non_app_build('build.gradle.kts','plugins { alias(libs.plugins.android.application).apply(false) }\n')
        self.assertEqual(set(verify.source_release_identities(self.root)),set(APPS))

    def test_plugin_declarations_in_strings_do_not_make_a_module_an_application(self):
        self.add_non_app_build('build.gradle.kts',
            'val example = "apply plugin: \'com.android.application\'"\n'
            'val multiline = """\nplugins { id("com.android.application") }\n"""\n')
        self.assertEqual(set(verify.source_release_identities(self.root)),set(APPS))

    def test_missing_classified_module_build_fails(self):
        for module in ('kotatsu-notes',SYNC,*NON_RELEASE_MODULES,*NON_APP_MODULES):
            with self.subTest(module=module):
                script=self.root/module/'build.gradle.kts';original=script.read_bytes()
                script.unlink()
                self.inventory_rejected('Missing Gradle module build script.*'+module)
                script.write_bytes(original)

    def test_release_modules_reject_groovy_only_build_scripts(self):
        for module in APPS:
            with self.subTest(module=module):
                kotlin=self.root/module/'build.gradle.kts'
                groovy=kotlin.rename(kotlin.with_name('build.gradle'))
                try:
                    self.inventory_rejected(rf'{module}: release modules require build\.gradle\.kts')
                finally:
                    groovy.rename(kotlin)

    def test_non_release_app_inventory_accepts_groovy_build_script(self):
        kotlin=self.root/'fake-mudita-calendar/build.gradle.kts'
        kotlin.rename(kotlin.with_name('build.gradle')).write_text("plugins { id 'com.android.application' }\n")
        self.assertEqual(set(verify.source_release_identities(self.root)),set(APPS))

    def test_missing_or_commented_manifest_entry_cannot_be_replaced_by_settings_text(self):
        settings=self.root/'settings.gradle.kts'
        settings.write_text(settings.read_text()+'\n/*\n/* Nested comment */\ninclude(":kotatsu-notes")\n*/\n')
        original=self.manifest.read_text()
        for module,role in (('kotatsu-notes','release'),(SYNC,'release'),('fake-mudita-calendar','non-release')):
            row=f'{module}={role}'
            for replacement in ('','# '+row):
                with self.subTest(module=module,replacement=replacement):
                    self.manifest.write_text(original.replace(row,replacement))
                    self.inventory_rejected('Missing .* application in gradle/modules.txt.*'+module)

    def test_duplicate_manifest_entry_fails_even_when_the_classes_agree(self):
        original=self.manifest.read_text()
        for module,role in (('kotatsu-notes','release'),(SYNC,'release'),('fake-mudita-calendar','non-release'),('paper-ui','non-app')):
            with self.subTest(module=module):
                self.manifest.write_text(original+f'{module}={role}\n')
                self.inventory_rejected('duplicate Gradle module '+module)

    def test_sync_wrong_role_fails_before_selecting_artifacts(self):
        self.manifest.write_text(self.manifest.read_text().replace('kotatsu-sync=release','kotatsu-sync=non-release'))
        self.inventory_rejected('Missing release application.*kotatsu-sync')

    def test_both_gradle_script_names_in_one_module_fail(self):
        (self.root/'paper-ui/build.gradle').write_text('')
        self.inventory_rejected('Duplicate Gradle build scripts.*paper-ui')

    def test_invalid_manifest_row_fails(self):
        with self.manifest.open('a') as manifest:manifest.write('../extra=non-app\n')
        self.inventory_rejected('invalid module declaration')

    def test_non_release_module_cannot_also_be_classified_as_release(self):
        for module in NON_RELEASE_MODULES:
            with self.subTest(module=module),mock.patch.dict(verify.RELEASE_APPS,{module:verify.RELEASE_APPS['app']}):
                self.inventory_rejected('Non-release module.*release inventory.*'+module)

    def test_manifest_cannot_on_its_own_promote_an_application(self):
        original=self.manifest.read_text()
        for module in NON_RELEASE_MODULES:
            with self.subTest(module=module):
                self.manifest.write_text(original.replace(module+'=non-release',module+'=release'))
                self.inventory_rejected('Unpinned release application.*'+module)

    def test_unsigned_inspection_reads_all_four_without_claiming_signing_or_build(self):
        report=self.check()
        self.assertEqual(set(report['apps']),set(APPS))
        for module,(package,code,_) in APPS.items():
            self.assertEqual(report['apps'][module]['applicationId'],package)
            self.assertEqual(report['apps'][module]['versionCode'],code)
            apk=Path(report['apps'][module]['path'])
            self.assertEqual(report['apps'][module]['sha256'],verify.sha(apk.read_bytes()))
        self.assertTrue(report['inspectionOnly']);self.assertFalse(report['signaturesVerified'])
        self.assertIsNone(report['buildProvenance'])
        self.assertEqual(len([c for c in self.calls if c[1:3]==('dump','badging')]),4)
        boundary=report['apps'][SYNC]['syncReleaseBoundary']
        self.assertTrue(boundary['providerQueriesVerified'])
        self.assertTrue(boundary['knownFixturesAbsent'])
        self.assertEqual(boundary['notices'],{name:verify.sha(data) for name,data in self.notices.items()})

    def test_companion_metadata_and_compiled_versions_cannot_agree_on_stale_release(self):
        for module in COMPANIONS:
            original=copy.deepcopy(self.metadata[module]);badging=self.badging[module]
            with self.subTest(module=module):
                self.metadata[module]['elements'][0]['versionName']='0.5.1';self.write_metadata(module)
                self.badging[module]=badging.replace('0.7.1','0.5.1')
                with self.assertRaisesRegex(verify.VerificationError,'metadata version'):self.check()
                self.metadata[module]=original;self.write_metadata(module)
                with self.assertRaisesRegex(verify.VerificationError,'Compiled APK version'):self.check()
                self.badging[module]=badging
        self.assertFalse(Path(self.args.report).exists())

    def test_companion_package_label_and_debuggability_are_checked(self):
        for module in COMPANIONS:
            original=self.badging[module]
            for bad in (original.replace(APPS[module][2],'Wrong label'),original+'application-debuggable\n',original.replace("versionCode='"+str(APPS[module][1])+"'","versionCode='1'")):
                self.badging[module]=bad
                with self.subTest(module=module),self.assertRaises(verify.VerificationError):self.check()
            self.badging[module]=original
            self.packages[module]='other.package'
            with self.assertRaisesRegex(verify.VerificationError,'compiled package'):self.check()
            self.packages[module]=APPS[module][0]

    def test_signed_gate_rejects_companion_signers_before_publishing(self):
        self.args.inspect_unsigned=False
        for module in COMPANIONS:
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
            locales=''.join(line+'\n' for line in self.badging[module].splitlines() if line.startswith('application-label-'))
            directory=self.root/module/'build/outputs/apk/release'
            directory.rename(directory.parent/'debug')
            version='0.7.1-debug' if module=='app' else '0.7.1'
            if module=='app':label+=' Debug'
            self.packages[module]=package+'.debug'
            self.badging[module]=f"package: name='{package}.debug' versionCode='{code}' versionName='{version}'\napplication-label:'{label}'\napplication-debuggable\n"+locales
            metadata=self.metadata[module];metadata.update(variantName='debug',applicationId=package+'.debug')
            metadata['elements'][0]['versionName']=version
            (directory.parent/'debug/output-metadata.json').write_text(json.dumps(metadata))
        ET.SubElement(self.sync_manifest.find('application'),'provider',{verify.ANDROID+'authorities':APPS[SYNC][0]+'.debug.test-entry'})
        self.sync_resources='';self.sync_dex='';self.mapping.unlink()
        report=self.check()
        self.assertTrue(report['signaturesVerified'])
        self.assertEqual(report['apps']['app']['versionName'],'0.7.1-debug')
        self.assertEqual(report['apps']['kotatsu-weather']['versionName'],'0.7.1')
        self.assertEqual(report['apps']['kotatsu-notes']['versionName'],'0.7.1')
        self.assertEqual(report['apps'][SYNC]['versionName'],'0.7.1')
        self.assertEqual(report['apps'][SYNC]['applicationId'],'com.alexindigo.kotatsu.sync.debug')
        self.assertNotIn('syncReleaseBoundary',report['apps'][SYNC])

    def test_missing_or_duplicate_companion_apk_prevents_complete_set_report(self):
        for module in COMPANIONS:
            directory=self.root/module/'build/outputs/apk/release'
            apk=directory/'fixture.apk';data=apk.read_bytes();apk.unlink()
            self.artifact_rejected('Zero/multiple/mismatched immediate APKs')
            apk.write_bytes(data);extra=directory/'stale.apk';extra.write_bytes(b'stale')
            self.artifact_rejected('Zero/multiple/mismatched immediate APKs')
            extra.unlink()

    def test_publication_names_are_complete_and_copy_exact_metadata_selected_bytes(self):
        # Gradle may build non-release modules; their outputs are not release assets.
        for module in NON_RELEASE_MODULES:
            directory=self.root/module/'build/outputs/apk/release'
            directory.mkdir(parents=True)
            (directory/f'{module}.apk').write_bytes(b'non-release APK')
        output=self.root/'published'
        prepare.prepare(self.root,'v0.7.1',output)
        self.assertEqual({p.name for p in output.iterdir()},set(ASSETS.values()))
        for module,name in ASSETS.items():
            source=self.root/module/'build/outputs/apk/release/fixture.apk'
            self.assertEqual((output/name).read_bytes(),source.read_bytes())
        report=self.check()
        self.assertEqual(set(report['sources']),set(APPS))
        self.assertEqual(set(report['apps']),set(APPS))

    def test_wrong_tag_or_incomplete_set_creates_no_publication(self):
        output=self.root/'published'
        with self.assertRaisesRegex(prepare.VerificationError,'tag'):prepare.prepare(self.root,'v0.5.2',output)
        self.metadata['kotatsu-notes']['elements'][0]['versionCode']=1;self.write_metadata('kotatsu-notes')
        with self.assertRaisesRegex(prepare.VerificationError,'metadata version'):prepare.prepare(self.root,'v0.7.1',output)
        self.assertFalse(output.exists())

    def artifact_rejected(self,message):
        output=self.root/'published'
        with self.assertRaisesRegex(prepare.VerificationError,message):prepare.prepare(self.root,'v0.7.1',output)
        self.compiled_rejected(message)
        self.assertFalse(output.exists())

    def compiled_rejected(self,message):
        with self.assertRaisesRegex(verify.VerificationError,message):self.check()
        self.assertFalse(Path(self.args.report).exists())

    def test_sync_metadata_rejection_is_atomic(self):
        original=copy.deepcopy(self.metadata[SYNC])
        self.metadata[SYNC]['applicationId']=APPS[SYNC][0]+'.debug';self.write_metadata(SYNC)
        self.artifact_rejected('metadata ID/variant mismatch: kotatsu-sync')
        self.metadata[SYNC]=original
        self.metadata[SYNC]['elements'][0]['versionCode']=11;self.write_metadata(SYNC)
        self.artifact_rejected('metadata version mismatch: kotatsu-sync')

    def test_sync_locale_missing_or_wrong(self):
        original=self.badging[SYNC]
        self.badging[SYNC]=original.replace("application-label-pt:'Kotatsu Sync'\n",'')
        self.compiled_rejected('Missing compiled existing locale labels: kotatsu-sync')
        self.badging[SYNC]=original.replace("application-label-pt:'Kotatsu Sync'","application-label-pt:'Wrong'")
        self.compiled_rejected('Wrong compiled locale label: kotatsu-sync')

    def test_sync_manifest_rejects_fixture_declarations_but_allows_queries(self):
        app=self.sync_manifest.find('application')
        for tag,field,value in (
            ('activity','name','.SyncSettingsHostActivity'),('provider','name','.TestEntryProvider'),
            ('service','name','.TestAccountService'),('service','name','.TestContactsSyncService'),
            ('provider','authorities','other.provider;com.mudita.calendar'),
            ('provider','authorities',APPS[SYNC][0]+'.test-entry'),
            ('receiver','name','com.alexindigo.kotatsu.fakemuditacalendar.SchemaReceiver'),
            ('provider','name','.FakeCalendarProvider'),('receiver','name','.FixtureReceiver'),
            ('meta-data','value','com.alexindigo.kotatsu.sync.test')):
            with self.subTest(value=value):
                node=ET.SubElement(app,tag,{verify.ANDROID+field:value})
                self.compiled_rejected('declares the Mudita Calendar provider authority' if 'com.mudita.calendar' in value else 'fixture in manifest')
                app.remove(node)
        node=ET.SubElement(self.sync_manifest,'uses-permission-sdk-23',{verify.ANDROID+'name':'android.permission.INTERNET'})
        self.compiled_rejected('declares INTERNET');self.sync_manifest.remove(node)
        self.assertTrue(self.check()['apps'][SYNC]['syncReleaseBoundary']['providerQueriesVerified'])

    def test_sync_requires_production_manifest_and_dex_components(self):
        app=self.sync_manifest.find('application');receiver=app.find('receiver')
        app.remove(receiver);self.compiled_rejected('Missing compiled Sync manifest component: .*BootReceiver');app.append(receiver)
        self.sync_dex='\n'.join(line for line in self.sync_dex.splitlines() if not line.endswith('.HorizonJob'))
        self.compiled_rejected('Missing compiled Sync DEX component: .*HorizonJob')

    def test_sync_requires_production_queries(self):
        queries=self.sync_manifest.find('queries')
        for node,message in ((queries.find('provider'),'Mudita Calendar provider query'),
                             (queries.findall('intent')[-1],'SyncAdapter query')):
            with self.subTest(message=message):
                queries.remove(node);self.compiled_rejected(message);queries.append(node)

    def test_sync_requires_usable_resources_and_no_fixture_names_or_values(self):
        original=self.sync_resources
        for entry in ('string/test_account_name','xml/test_account','xml/test_contacts_sync','string/other'):
            with self.subTest(entry=entry):
                value='Kotatsu Sync test account' if entry=='string/other' else 'fixture label'
                self.sync_resources=original+f'  resource 0x7f010002 {entry}\n    () "{value}"\n'
                self.compiled_rejected('fixture in resources')
        self.sync_resources='not a resource table'
        self.compiled_rejected('Malformed/empty resource table')

    def test_sync_requires_mapping_and_defined_dex_without_fixtures(self):
        original=self.sync_dex
        for symbol in ('fakesyncadapter.FixtureReceiver','fakemuditacalendar.FakeCalendarProvider','sync.TestAccountService'):
            with self.subTest(symbol=symbol):
                self.sync_dex=original+'C d 1 1 99 com.alexindigo.kotatsu.'+symbol+'\n'
                self.compiled_rejected('fixture in DEX')
        self.sync_dex=original+'C r 1 1 99 other.Reference\n';self.compiled_rejected('includes nondefined entries')
        self.sync_dex='';self.compiled_rejected('Missing/malformed Sync defined-only DEX output')
        self.mapping.write_text('');self.compiled_rejected('Empty Sync release mapping')

    def test_sync_notices_must_be_present_unique_and_byte_identical(self):
        for name,data in self.notices.items():
            other=[(path,content) for path,content in self.notices.items() if path!=name]
            with self.subTest(name=name):
                self.write_sync_apk(other);self.compiled_rejected('Missing/duplicate packaged Sync notice')
                self.write_sync_apk(other+[(name,b'bad')]);self.compiled_rejected('Changed packaged Sync notice')
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore',UserWarning)
                    self.write_sync_apk(other+[(name,data),(name,data)])
                self.compiled_rejected('Missing/duplicate packaged Sync notice')


if __name__=='__main__':unittest.main()
