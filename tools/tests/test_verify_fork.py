import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

spec=importlib.util.spec_from_file_location('verify_fork',Path(__file__).parents[1]/'verify_fork.py')
verify=importlib.util.module_from_spec(spec);spec.loader.exec_module(verify)

EXPECTED_BUNDLE='302937f109a3eef346d989771c71804286cbfa48c8b4778a340d6d4cdbab723d'
EXPECTED_MASTER='cfde3ba32f7ef3fb4ddcc65bfb6acc7c8fe8c01e1a21940ca229e0d06fa874c8'
EXPECTED_GRAPHICS={
    'app/src/main/res/mipmap-mdpi/ic_launcher.webp':'878f95458f8d67793b90fb990813122cbf68cd45183568f4e83819e7536ebf66',
    'app/src/main/res/mipmap-mdpi/ic_launcher_round.webp':'29ae725e32bec32dbc4f97e782315577528d6847bba8b65e186ff229913fd493',
    'app/src/main/res/mipmap-hdpi/ic_launcher.webp':'708dc442b9608c60a1d400365a66e55cf84e3c14ed8fa45697ab55a9f3971c11',
    'app/src/main/res/mipmap-hdpi/ic_launcher_round.webp':'c7f76a8baf1c8a0942effe49d9342544c0f599524b3d803c8bfc58e4d880da0e',
    'app/src/main/res/mipmap-xhdpi/ic_launcher.webp':'ce8cc4b84b637bcc74be744a5c7fcf4cfc059f5aab713039eed1cfc8a81c3f1b',
    'app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp':'416c143844e4de962c181846a5c5417e1cab277355edfda9ec0481516c3e9776',
    'app/src/main/res/mipmap-xxhdpi/ic_launcher.webp':'ef7744ed7b63684deef6827db3a510078a173bb70ac1c220f1ba3685ae90e7be',
    'app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp':'cdb0c32e09d83470e461e658230229b9224ad955c2538af5853edf53804a1e71',
    'app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp':'85c24bab72175f79de62fbae8303ffc74ee3e74c8239f1240d1a714ac2f0ea82',
    'app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp':'2adf8cdada0db64faca14607f2fbb1412a2e1e7f5687315a9de99b8f6f121af0',
    'app/src/main/res/drawable/ic_launcher_foreground.xml':'b09507a9db85bc9ba428001efc4ba31187dbbfa9ce66dd246a57e53cd997e4ce',
    'app/src/main/res/drawable/ic_launcher_monochrome.xml':'b09507a9db85bc9ba428001efc4ba31187dbbfa9ce66dd246a57e53cd997e4ce',
    'app/src/main/res/drawable/katapult_logo.xml':'730e097ae997cc96ff8ba3c82518642af1ccbecb1654a49be94530a929193ae8',
    'docs/img/K.svg':'e8fc26b0776f4975c2a9bb3ac0370692d5d6e715db75bc8af03b5029e9ed8f96',
    'docs/img/katapult.svg':'b4ec725d539e3d9acfb908b256d18552973c671778227a4d1e5c3dbb8c051bbd',
    'docs/img/katapult_w.svg':'e693c74c70e0713a43a412404287da486a0636ebcae91cc2838b3fd56ec5224c',
    'docs/img/katapult.png':'0d04dfc57af5a2a7fcf6637b76eb907a1ef01617035c35d6fd5107938f02eebb',
    'app/src/main/ic_launcher-playstore.png':'0d04dfc57af5a2a7fcf6637b76eb907a1ef01617035c35d6fd5107938f02eebb',
}


class ArtifactSelectionTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.directory=self.root/'app/build/outputs/apk/debug';self.directory.mkdir(parents=True)
        self.apk=self.directory/'kotatsu-v1.0-debug.apk';self.apk.write_bytes(b'fixture apk')
        self.metadata={'variantName':'debug','applicationId':verify.RELEASE_ID+'.debug','elements':[{'outputFile':self.apk.name}]}
        self.write()
    def tearDown(self):self.temp.cleanup()
    def write(self):(self.directory/'output-metadata.json').write_text(json.dumps(self.metadata))
    def test_unique_metadata_selected_immediate_apk(self):self.assertEqual(verify.unique_apk(self.directory,self.root,'debug'),self.apk)
    def test_zero_or_multiple_outputs(self):
        self.apk.unlink()
        with self.assertRaises(verify.VerificationError):verify.unique_apk(self.directory,self.root,'debug')
        self.apk.write_bytes(b'apk');(self.directory/'older.apk').write_bytes(b'old')
        with self.assertRaises(verify.VerificationError):verify.unique_apk(self.directory,self.root,'debug')
    def test_metadata_path_escape_rejected(self):
        for path in ('../other.apk','subdir/other.apk','/other.apk'):
            with self.subTest(path=path):
                self.metadata['elements'][0]['outputFile']=path;self.write()
                with self.assertRaises(verify.VerificationError):verify.unique_apk(self.directory,self.root,'debug')
    def test_wrong_variant_or_self_consistent_wrong_package_rejected(self):
        for key,value in (('applicationId','evil.application.debug'),('variantName','release')):
            with self.subTest(key=key):
                old=self.metadata[key];self.metadata[key]=value;self.write()
                with self.assertRaises(verify.VerificationError):verify.unique_apk(self.directory,self.root,'debug')
                self.metadata[key]=old
    def test_symlink_apk_rejected(self):
        target=self.root/'elsewhere.apk';target.write_bytes(b'other');self.apk.unlink();self.apk.symlink_to(target)
        with self.assertRaises(verify.VerificationError):verify.unique_apk(self.directory,self.root,'debug')
    def test_unknown_artifact_directory_rejected(self):
        with self.assertRaises(verify.VerificationError):verify.unique_apk(self.root,self.root,'debug')


class ToolParsingTest(unittest.TestCase):
    def test_actual_badging_shape_with_locale_labels(self):
        text="package: name='com.alexindigo.kotatsu.debug' versionCode='7' versionName='1.0-debug'\napplication-label:'Kotatsu Debug'\napplication-label-de:'Kotatsu Debug'\napplication-debuggable\n"
        result=verify.parse_badging(text);self.assertTrue(result['debuggable']);self.assertEqual(result['labels']['de'],'Kotatsu Debug')
    def test_malformed_badging_fails_closed(self):
        for text in ('','package: malformed','application-label:\'Kotatsu\''):
            with self.subTest(text=text),self.assertRaises(verify.VerificationError):verify.parse_badging(text)
    def test_resolves_actual_resource_ids_to_minified_xml_paths(self):
        resources=verify.resource_files('    resource 0x7f0d0005 xml/shortcuts\n      () (file) res/a.xml type=XML\n')
        self.assertEqual(verify.resolved_xml('@ref/0x7f0d0005',resources,{'res/a.xml'}),'res/a.xml')
    def test_missing_ambiguous_or_unsafe_resource_paths(self):
        for files in ([],['res/a.xml','res/b.xml'],['../escape.xml']):
            with self.subTest(files=files),self.assertRaises(verify.VerificationError):
                verify.resolved_xml('@ref/0x7f0d0005',{'0x7f0d0005':{'name':'xml/shortcuts','files':files}},set(files))
    def test_wrong_resource_type_and_malformed_table(self):
        with self.assertRaises(verify.VerificationError):verify.resolved_xml('@ref/0x7f000001',{'0x7f000001':{'name':'string/app_name','files':['res/a.xml']}},{'res/a.xml'})
        with self.assertRaises(verify.VerificationError):verify.resource_files('not a resource table')
    def test_signer_requires_one_real_fingerprint(self):
        with mock.patch.object(verify,'command',return_value='Signer #1 certificate SHA-256 digest: '+'a'*64+'\n'):
            self.assertEqual(verify.signer('/sdk/apksigner','apk'),'a'*64)
        with mock.patch.object(verify,'command',return_value='no certificate'):
            with self.assertRaises(verify.VerificationError):verify.signer('/sdk/apksigner','apk')
    def test_subprocess_failure_and_empty_output_fail_closed(self):
        class Result:returncode=1;stdout='';stderr='failed'
        with mock.patch.object(verify.subprocess,'run',return_value=Result()):
            with self.assertRaises(verify.VerificationError):verify.command('sdk-tool')
        Result.returncode=0
        with mock.patch.object(verify.subprocess,'run',return_value=Result()):
            with self.assertRaises(verify.VerificationError):verify.command('sdk-tool')


class DeferredAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.originals={};self.records=[]
        self.approved={}
        for number,(path,resource,role) in enumerate(verify.asset_mapping(),1):
            # Real baseline native contents, independently read before the fixture's
            # Git adapter is installed; tests never need the designer's Documents.
            data=verify.baseline_bytes(Path(__file__).parents[2],verify.BASELINE,path)
            p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
            self.originals[path]=data
            self.records.append({'assetId':f'branding-{number:03d}','path':path,'examplePath':path,'originalPath':path,'resourceId':resource,'role':role,
                'originalSha256':verify.sha(data),'byteSize':len(data),'fileMode':0o644,'gitMode':'100644','gitBlob':hashlib.sha1(data).hexdigest(),
                'native':verify.original_native(data,Path(path).suffix)})
            if number in set(range(1,11)) | {13,14,16,23,24,25,26,27}:
                replacement=('approved-'+path).encode();p.write_bytes(replacement);self.approved[path]=verify.sha(replacement)
        code=self.root/'app/src/main/java/com/gezimos/katapult/ui/OnboardingScreen.kt';code.parent.mkdir(parents=True);code.write_text('painterResource(R.drawable.katapult_logo)')
        self.index=self.root/'index.json';self.value={'schemaVersion':1,'assetCount':27,'repository':{'head':verify.BASELINE},'assets':self.records};self.write()
        def git(root,*args):
            if args[:1]==('ls-tree',) and '-r' in args:
                return '\n'.join('100644 blob '+hashlib.sha1(data).hexdigest()+'\t'+path for path,data in self.originals.items())+'\n'
            if args[:1]==('ls-tree',):
                path=args[-1];return '100644 blob '+hashlib.sha1(self.originals[path]).hexdigest()+'\t'+path+'\n'
            raise AssertionError(args)
        self.patch_git=mock.patch.object(verify,'git',side_effect=git);self.patch_git.start()
        self.patch_bytes=mock.patch.object(verify,'baseline_bytes',side_effect=lambda root,base,path:self.originals[path]);self.patch_bytes.start()
        self.patch_approval=mock.patch.object(verify,'APPROVED_GRAPHICS_SHA256',self.approved,create=True);self.patch_approval.start()
        self.patch_index=mock.patch.object(verify,'ORIGINAL_INDEX_SHA256',verify.sha(self.index.read_bytes()),create=True);self.patch_index.start()
    def tearDown(self):self.patch_index.stop();self.patch_approval.stop();self.patch_git.stop();self.patch_bytes.stop();self.temp.cleanup()
    def write(self):self.index.write_text(json.dumps(self.value))
    def check(self):return verify.deferred_assets(self.root,verify.BASELINE,self.index)
    def test_valid_deferred_source_mapping(self):self.assertEqual(self.check()['assetCount'],27)
    def test_changed_graphic_and_forged_matching_index_hash_rejected(self):
        self.check()
        path=self.records[0]['path'];(self.root/path).write_bytes(b'new logo');self.records[0]['originalSha256']=verify.sha(b'new logo');self.records[0]['byteSize']=8;self.write()
        with self.assertRaises(verify.VerificationError):self.check()
    def test_wrong_count_type_or_baseline(self):
        self.check()
        for key,value in (('assetCount',26),('assetCount',True),('schemaVersion',True)):
            old=self.value[key];self.value[key]=value;self.write()
            with self.assertRaises(verify.VerificationError):self.check()
            self.value[key]=old
    def test_absolute_traversal_duplicate_mapping_rejected(self):
        self.check()
        for path in ('/tmp/logo.svg','../logo.svg',self.records[1]['path']):
            self.records[0]['path']=path;self.write()
            with self.assertRaises(verify.VerificationError):self.check()
    def test_wrong_resource_type_role_or_native_format(self):
        self.check()
        for key,value in (('resourceId','@color/ic_launcher'),('role','unused'),('native',{'format':'SVG'})):
            old=self.records[0][key];self.records[0][key]=value;self.write()
            with self.assertRaises(verify.VerificationError):self.check()
            self.records[0][key]=old
    def test_extra_alternate_graphic_cannot_hide_behind_unchanged_27(self):
        self.check()
        (self.root/'app/src/main/res/drawable/new_logo.xml').write_text('<vector/>')
        with self.assertRaises(verify.VerificationError):self.check()
    def test_wordmark_reference_swap_rejected(self):
        self.check()
        (self.root/'app/src/main/java/com/gezimos/katapult/ui/OnboardingScreen.kt').write_text('painterResource(R.drawable.ic_foreground)')
        with self.assertRaises(verify.VerificationError):self.check()
    def test_example_edits_are_not_build_inputs(self):
        examples=self.root/'designer';examples.mkdir();(examples/'logo.svg').write_text('designer edits')
        self.assertEqual(self.check()['assetCount'],27)


class ApprovedAssetsTest(DeferredAssetsTest):
    def test_approved_replacements_keep_original_provenance(self):
        result=self.check()
        self.assertEqual(result['status'],'approved Kotatsu source assets verified')
        self.assertEqual(result['assetCount'],27)
        self.assertEqual(result['replacementCount'],18)
        self.assertEqual(result['indexSha256'],verify.sha(self.index.read_bytes()))
        self.assertEqual(result['approvedBundleIndexSha256'],EXPECTED_BUNDLE)
        self.assertEqual(result['approvedSourceMasterSha256'],EXPECTED_MASTER)

    def test_each_approved_path_rejects_tamper_old_bytes_and_swaps(self):
        self.check()
        for path in self.approved:
            p=self.root/path;saved=p.read_bytes()
            for data in (b'tampered',self.originals[path],('approved-'+next(q for q in self.approved if q!=path)).encode()):
                with self.subTest(path=path,data=data),self.assertRaises(verify.VerificationError):
                    p.write_bytes(data);self.check()
            p.write_bytes(saved)

    def test_every_unchanged_member_stays_protected(self):
        self.check()
        for path in self.originals.keys()-self.approved.keys():
            p=self.root/path;saved=p.read_bytes();p.write_bytes(b'tampered support')
            with self.subTest(path=path),self.assertRaises(verify.VerificationError):self.check()
            p.write_bytes(saved)

    def test_unrelated_graphics_fonts_and_site_screenshots_stay_protected(self):
        self.check()
        for path in ('app/src/main/res/font/lato_bold.ttf','app/src/main/res/drawable/unrelated.xml','docs/img/screenshots/home.png'):
            p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'original context')
            self.originals[path]=p.read_bytes();self.check();p.write_bytes(b'changed context')
            with self.subTest(path=path),self.assertRaises(verify.VerificationError):self.check()
            p.write_bytes(self.originals[path])

    def test_removed_symlink_and_mode_changed_member(self):
        self.check()
        p=self.root/self.records[0]['path'];saved=p.read_bytes();p.unlink()
        with self.assertRaises(verify.VerificationError):self.check()
        target=self.root/'elsewhere';target.write_bytes(saved);p.symlink_to(target)
        with self.assertRaises(verify.VerificationError):self.check()
        p.unlink();p.write_bytes(saved);p.chmod(0o600)
        with self.assertRaises(verify.VerificationError):self.check()

    def test_symlink_ancestor_and_duplicate_onboarding_painter(self):
        self.check()
        p=self.root/'docs/img';p.rename(self.root/'site-images');p.symlink_to(self.root/'site-images',target_is_directory=True)
        with self.assertRaises(verify.VerificationError):self.check()
        p.unlink();(self.root/'site-images').rename(p)
        (self.root/'app/src/main/java/com/gezimos/katapult/ui/OnboardingScreen.kt').write_text('painterResource(R.drawable.katapult_logo); painterResource(R.drawable.katapult_logo)')
        with self.assertRaises(verify.VerificationError):self.check()

    def test_forged_agreeing_metadata_cannot_authorize_current_or_baseline_bytes(self):
        self.check()
        path=self.records[0]['path'];data=b'forged';(self.root/path).write_bytes(data)
        self.records[0].update(originalSha256=verify.sha(data),byteSize=len(data),gitBlob=hashlib.sha1(data).hexdigest());self.write()
        with mock.patch.object(verify,'ORIGINAL_INDEX_SHA256',verify.sha(self.index.read_bytes())):
            with self.assertRaises(verify.VerificationError):self.check()

    def test_original_metadata_wrong_baseline_order_blob_mode_and_native(self):
        self.check()
        for key,value in (('assetId','branding-002'),('gitBlob','0'*40),('gitMode','100755'),('fileMode',True),('native',{'format':'SVG'})):
            saved=self.records[0][key];self.records[0][key]=value;self.write()
            with mock.patch.object(verify,'ORIGINAL_INDEX_SHA256',verify.sha(self.index.read_bytes())):
                with self.subTest(key=key),self.assertRaises(verify.VerificationError):self.check()
            self.records[0][key]=saved
        self.value['repository']['head']='0'*40;self.write()
        with self.assertRaises(verify.VerificationError):self.check()

    def test_original_index_symlink_and_incomplete_approval_map(self):
        self.check();target=self.root/'original.json';self.index.rename(target);self.index.symlink_to(target)
        with self.assertRaises(verify.VerificationError):self.check()
        self.index.unlink();target.rename(self.index)
        with mock.patch.object(verify,'APPROVED_GRAPHICS_SHA256',dict(list(self.approved.items())[:-1])):
            with self.assertRaises(verify.VerificationError):self.check()

    def test_wider_original_mode_protection(self):
        path='app/src/main/res/font/lato_bold.ttf';p=self.root/path;p.parent.mkdir(parents=True);p.write_bytes(b'font');self.originals[path]=b'font'
        self.check();p.chmod(0o755)
        with self.assertRaises(verify.VerificationError):self.check()


class CompactGeometryTest(unittest.TestCase):
    def test_production_accessibility_and_local_branding_references(self):
        from html.parser import HTMLParser
        root=Path(__file__).parents[2]
        expected=('Kotatsu logo','Logo Kotatsu','Kotatsu-Logo','Logotipo de Kotatsu','Logo de Kotatsu','Logo di Kotatsu','Kotatsu-logo','Logo Kotatsu','Logótipo do Kotatsu')
        for locale,value in zip(verify.LOCALES,expected):self.assertEqual(verify.strings(root,'main',locale)['inherited_logo_description'],value)
        class Links(HTMLParser):
            def __init__(self):super().__init__();self.words=[];self.icons=[]
            def handle_starttag(self,tag,attrs):
                a=dict(attrs)
                if tag=='img' and a.get('alt')=='Kotatsu wordmark':self.words.append(a['src'])
                if tag=='link' and a.get('rel') in ('icon','apple-touch-icon'):self.icons.append(a['href'])
        for name in ('README.md','docs/index.html','docs/404.html'):
            p=root/name;text=p.read_text();links=Links();links.feed(text)
            self.assertEqual(len(links.words),1)
            for path in links.words+links.icons:self.assertTrue((p.parent/path).is_file(),path)
            self.assertNotIn('Temporary inherited upstream wordmark',text)
        self.assertIn('"softwareVersion": "1.0"',(root/'docs/index.html').read_text())

    def test_path_parser_normalizes_actual_relative_and_implicit_commands(self):
        self.assertEqual(verify.path_geometry('M1 2h3v4l-3 0z'),verify.path_geometry('m1,2 3,0 0,4 -3,0 Z'))
        self.assertEqual(verify.path_geometry('M0 0C1 2 3 4 5 6S7 8 9 10Z'),verify.path_geometry('M0 0C1 2 3 4 5 6C7 8 7 8 9 10Z'))
        self.assertEqual(verify.path_geometry('M0 0Q1 2 3 4T5 6Z'),verify.path_geometry('M0 0Q1 2 3 4Q5 6 5 6Z'))

    def test_path_parser_rejects_corruption_complete_consumption_and_arity(self):
        verify.path_geometry('M0 0L1 1Z')
        for path in ('STRING_TOO_LARGE','M0 0 A1 1 0 0 0 2 2','L0 0','M0','M0 0C1 2 3Z','M0 0LNaN 1','M0 0L1e999 1','M0 0Z1 2','M0 0??','M0,,0','M0 0'+' '*8192):
            with self.subTest(path=path[:40]),self.assertRaises(verify.VerificationError):verify.path_geometry(path)

    def test_real_production_pins_paths_and_compact_structure(self):
        self.assertEqual(verify.APPROVED_GRAPHICS_SHA256,EXPECTED_GRAPHICS)
        self.assertEqual(verify.APPROVED_BUNDLE_INDEX_SHA256,EXPECTED_BUNDLE)
        self.assertEqual(verify.APPROVED_SOURCE_MASTER_SHA256,EXPECTED_MASTER)
        self.assertEqual(verify.ORIGINAL_INDEX_SHA256,'fab7b450c008fdd5f6bee3abde03d9cb06cfb9f29fb8fce44f9cc4b5e2062472')
        root=Path(__file__).parents[2]
        for path,digest in EXPECTED_GRAPHICS.items():self.assertEqual(verify.sha((root/path).read_bytes()),digest,path)
        for name,count in (('ic_launcher_foreground',1),('ic_launcher_monochrome',1),('katapult_logo',9)):
            node=verify.ET.parse(root/f'app/src/main/res/drawable/{name}.xml').getroot()
            structure=verify.vector_semantics(node)
            self.assertEqual(len(list(node.iter('path'))),count)
            self.assertEqual(len(list(node.iter('group'))),1)
            self.assertEqual(structure['tag'],'vector')

    def test_order_groups_paint_viewport_and_path_geometry_cannot_be_dropped(self):
        import copy
        source=verify.ET.parse(Path(__file__).parents[2]/'app/src/main/res/drawable/katapult_logo.xml').getroot()
        expected=verify.vector_semantics(source);verify.compare_vectors(expected,verify.vector_semantics(source))
        for mutation in ('fill','viewport','transform','contour','glyph','group'):
            actual=copy.deepcopy(source)
            if mutation=='fill':next(actual.iter('path')).set(verify.ANDROID+'fillType','evenOdd')
            elif mutation=='viewport':actual.set(verify.ANDROID+'viewportWidth','301')
            elif mutation=='transform':next(actual.iter('group')).set(verify.ANDROID+'scaleX','0.9')
            elif mutation=='contour':next(actual.iter('path')).set(verify.ANDROID+'pathData','M0 0L1 1Z')
            elif mutation=='glyph':actual.remove(actual.findall('path')[-1])
            else:
                g=actual.find('group');actual.remove(g);actual.append(g)
            with self.subTest(mutation=mutation),self.assertRaises(verify.VerificationError):verify.compare_vectors(expected,verify.vector_semantics(actual))


if __name__=='__main__':unittest.main()
