import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

spec=importlib.util.spec_from_file_location('verify_fork',Path(__file__).parents[1]/'verify_fork.py')
verify=importlib.util.module_from_spec(spec);spec.loader.exec_module(verify)


class ArtifactSelectionTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.directory=self.root/'app/build/outputs/apk/debug';self.directory.mkdir(parents=True)
        self.apk=self.directory/'kotatsu-v1.6-debug.apk';self.apk.write_bytes(b'fixture apk')
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
        text="package: name='io.github.alexindigo.kotatsu.debug' versionCode='7' versionName='1.6-debug'\napplication-label:'Kotatsu Debug'\napplication-label-de:'Kotatsu Debug'\napplication-debuggable\n"
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
        for number,(path,resource,role) in enumerate(verify.asset_mapping(),1):
            data=('original-'+path).encode();p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
            self.originals[path]=data
            self.records.append({'assetId':f'branding-{number:03d}','path':path,'examplePath':path,'originalPath':path,'resourceId':resource,'role':role,
                'originalSha256':verify.sha(data),'byteSize':len(data),'fileMode':0o644,'gitMode':'100644','gitBlob':hashlib.sha1(data).hexdigest(),
                'native':{'format':{'.xml':'Android XML','.png':'PNG','.svg':'SVG','.webp':'WebP'}[Path(path).suffix]}})
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
    def tearDown(self):self.patch_git.stop();self.patch_bytes.stop();self.temp.cleanup()
    def write(self):self.index.write_text(json.dumps(self.value))
    def check(self):return verify.deferred_assets(self.root,verify.BASELINE,self.index)
    def test_valid_deferred_source_mapping(self):self.assertEqual(self.check()['assetCount'],27)
    def test_changed_graphic_and_forged_matching_index_hash_rejected(self):
        path=self.records[0]['path'];(self.root/path).write_bytes(b'new logo');self.records[0]['originalSha256']=verify.sha(b'new logo');self.records[0]['byteSize']=8;self.write()
        with self.assertRaises(verify.VerificationError):self.check()
    def test_wrong_count_type_or_baseline(self):
        for key,value in (('assetCount',26),('assetCount',True),('schemaVersion',True)):
            old=self.value[key];self.value[key]=value;self.write()
            with self.assertRaises(verify.VerificationError):self.check()
            self.value[key]=old
    def test_absolute_traversal_duplicate_mapping_rejected(self):
        for path in ('/tmp/logo.svg','../logo.svg',self.records[1]['path']):
            self.records[0]['path']=path;self.write()
            with self.assertRaises(verify.VerificationError):self.check()
    def test_wrong_resource_type_role_or_native_format(self):
        for key,value in (('resourceId','@color/ic_launcher'),('role','unused'),('native',{'format':'SVG'})):
            old=self.records[0][key];self.records[0][key]=value;self.write()
            with self.assertRaises(verify.VerificationError):self.check()
            self.records[0][key]=old
    def test_extra_alternate_graphic_cannot_hide_behind_unchanged_27(self):
        (self.root/'app/src/main/res/drawable/new_logo.xml').write_text('<vector/>')
        with self.assertRaises(verify.VerificationError):self.check()
    def test_wordmark_reference_swap_rejected(self):
        (self.root/'app/src/main/java/com/gezimos/katapult/ui/OnboardingScreen.kt').write_text('painterResource(R.drawable.ic_foreground)')
        with self.assertRaises(verify.VerificationError):self.check()
    def test_example_edits_are_not_build_inputs(self):
        examples=self.root/'designer';examples.mkdir();(examples/'logo.svg').write_text('designer edits')
        self.assertEqual(self.check()['assetCount'],27)


if __name__=='__main__':unittest.main()
