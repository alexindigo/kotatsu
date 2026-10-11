#!/usr/bin/env python3
"""Pinned source, packaged branding and optional bounded offline-capability checks."""
import argparse
from decimal import Decimal, localcontext
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parent))
import source_receipt

BASELINE='c6601b8128089db8ad28cdcc1f57361575066d5f'
RELEASE_ID='io.github.alexindigo.kotatsu'
NAMESPACE='com.gezimos.katapult'
ANDROID='{http://schemas.android.com/apk/res/android}'
LOCALES=('', '-cs','-de','-es','-fr','-it','-nl','-pl','-pt')
ORIGINAL_INDEX_SHA256='fab7b450c008fdd5f6bee3abde03d9cb06cfb9f29fb8fce44f9cc4b5e2062472'
APPROVED_BUNDLE_INDEX_SHA256='302937f109a3eef346d989771c71804286cbfa48c8b4778a340d6d4cdbab723d'
APPROVED_SOURCE_MASTER_SHA256='cfde3ba32f7ef3fb4ddcc65bfb6acc7c8fe8c01e1a21940ca229e0d06fa874c8'
APPROVED_SOURCE_FONT_SHA256='8a0aace75d33794eece4b28187bfc1df0bbd2888b5d8a56e01788c8d65d16be1'
SOURCE_ARTWORK_STATUS='approved Kotatsu source assets verified'
PACKAGED_ARTWORK_STATUS='approved Kotatsu packaged assets verified; runtime appearance not certified'
APPROVED_GRAPHICS_SHA256={
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
DENSITIES=('mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi')
ICON_NAMES=('mipmap/ic_launcher','mipmap/ic_launcher_round')
VECTOR_COUNTS={'drawable/ic_launcher_foreground':1,'drawable/ic_launcher_monochrome':1,'drawable/katapult_logo':9}


class VerificationError(Exception):pass


def require(condition,message):
    if not condition:raise VerificationError(message)


def sha(data):return hashlib.sha256(data).hexdigest()


def command(*args):
    result=subprocess.run([str(a) for a in args],stdin=subprocess.DEVNULL,capture_output=True,text=True)
    require(result.returncode==0,'Tool failed: '+str(args[0])+'\n'+result.stderr)
    require(bool(result.stdout.strip()),'Empty tool output: '+str(args[0]))
    return result.stdout


def confined(root,name):
    require(isinstance(name,str) and '\\' not in name,'Invalid confined path')
    pieces=name.split('/')
    require(pieces and all(p not in ('','.','..') for p in pieces) and not PurePosixPath(name).is_absolute(),'Path escape: '+name)
    current=Path(root)
    for p in pieces:
        current=current/p
        require(not current.is_symlink(),'Symlink in source/artifact path: '+name)
    require(current.is_file(),'Missing file: '+name)
    return current


def git(root,*args):return command('git','--no-optional-locks','-C',root,*args)


def baseline_bytes(root,base,path):
    require(base==BASELINE,'Unapproved Git baseline')
    result=subprocess.run(['git','--no-optional-locks','-C',str(root),'show',base+':'+path],capture_output=True)
    require(result.returncode==0,'Missing Git baseline blob: '+path)
    return result.stdout


def asset_mapping():
    rows=[]
    for density in ('mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi'):
        for stem in ('ic_launcher','ic_launcher_round'):
            rows.append((f'app/src/main/res/mipmap-{density}/{stem}.webp','@mipmap/'+stem,'active raster branding'))
    rows.extend([
      ('app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml','@mipmap/ic_launcher','supporting adaptive wrapper'),
      ('app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml','@mipmap/ic_launcher_round','supporting adaptive wrapper'),
      ('app/src/main/res/drawable/ic_launcher_foreground.xml','@drawable/ic_launcher_foreground','active vector branding'),
      ('app/src/main/res/drawable/ic_launcher_monochrome.xml','@drawable/ic_launcher_monochrome','active vector branding'),
      ('app/src/main/res/values/ic_launcher_background.xml','@color/ic_launcher_background','supporting active color'),
      ('app/src/main/res/drawable/katapult_logo.xml','@drawable/katapult_logo','active vector with baked-in lettering'),
      ('app/src/main/res/drawable/ic_foreground.xml','@drawable/ic_foreground','unused legacy reference'),
      ('app/src/main/res/drawable/ic_background.xml','@drawable/ic_background','unused legacy reference'),
      ('app/src/main/res/mipmap-anydpi-v26/ic_launcher_screensaver.xml','@mipmap/ic_launcher_screensaver','supporting screensaver adaptive wrapper'),
      ('app/src/main/res/drawable/ic_launcher_screensaver_foreground.xml','@drawable/ic_launcher_screensaver_foreground','active moon vector'),
      ('app/src/main/res/drawable/ic_screensaver.xml','@drawable/ic_screensaver','active static-shortcut moon vector'),
      ('app/src/main/res/drawable/ic_launcher_background.xml','@drawable/ic_launcher_background','unused Android-template reference'),
      ('docs/img/K.svg',None,'active website favicon'),('docs/img/katapult.svg',None,'active black graphical wordmark'),
      ('docs/img/katapult_w.svg',None,'active white graphical wordmark'),('docs/img/katapult.png',None,'active website touch icon'),
      ('app/src/main/ic_launcher-playstore.png',None,'store/export reference')])
    return rows


def deferred_assets(root,base,index_path):
    # Legacy CLI/call boundary: this is ORIGINAL provenance, not the delivery index.
    index_file=confined(Path('/'),str(Path(index_path).absolute()).lstrip('/'))
    index_bytes=index_file.read_bytes();require(sha(index_bytes)==ORIGINAL_INDEX_SHA256,'Original provenance index digest mismatch')
    index=json.loads(index_bytes)
    replacements={path for i,(path,_,_) in enumerate(asset_mapping(),1) if i in set(range(1,11))|{13,14,16,23,24,25,26,27}}
    require(set(APPROVED_GRAPHICS_SHA256)==replacements,'Incomplete/changed fixed18 approval map')
    require(type(index.get('schemaVersion')) is int and index['schemaVersion']==1,'Invalid deferred index schema')
    require(type(index.get('assetCount')) is int and index['assetCount']==27,'Deferred index must have exactly27 assets')
    require(index.get('repository',{}).get('head')==base,'Deferred index baseline mismatch')
    records=index.get('assets');require(isinstance(records,list) and len(records)==27,'Invalid deferred records')
    for number,((path,resource,role),record) in enumerate(zip(asset_mapping(),records),1):
        require(isinstance(record,dict) and record.get('assetId')==f'branding-{number:03d}','Wrong stable asset ID/order')
        require(record.get('path')==record.get('examplePath')==record.get('originalPath')==path,'Unsafe/changed asset mapping')
        require(record.get('resourceId')==resource and record.get('role')==role,'Wrong resource type/role')
        native=record.get('native')
        expected_format={'.webp':'WebP','.png':'PNG','.svg':'SVG','.xml':'Android XML'}[Path(path).suffix]
        require(isinstance(native,dict) and native.get('format')==expected_format,'Wrong original asset format/type')
        source=confined(root,path).read_bytes();original=baseline_bytes(root,base,path)
        require(sha(original)==record.get('originalSha256'),'Forged original hash: '+path)
        require(type(record.get('byteSize')) is int and len(original)==record['byteSize'],'Wrong original asset byte count')
        require(native==original_native(original,Path(path).suffix),'Wrong original native provenance: '+path)
        require(sha(source)==APPROVED_GRAPHICS_SHA256.get(path,sha(original)),'Unapproved graphical source: '+path)
        tree=git(root,'ls-tree',base,'--',path).strip()
        mode,kind,blob,git_path=re.split(r'\s+',tree,maxsplit=3)
        require(kind=='blob' and git_path==path and record.get('gitBlob')==blob and record.get('gitMode')==mode,'Git asset provenance mismatch')
        require(type(record.get('fileMode')) is int and record['fileMode']==int(mode[-3:],8),'Wrong original file mode/type')
        require(stat.S_IMODE(confined(root,path).stat().st_mode)==record['fileMode'],'Original graphic mode changed: '+path)
    # Prevent alternate/new graphic resources from bypassing the fixed27 check.
    def production_graphic(path):
        return path.startswith('docs/img/') or re.match(r'app/src/main/res/(?:drawable[^/]*|mipmap[^/]*|font)/',path) or path=='app/src/main/ic_launcher-playstore.png'
    baseline_modes={line.split('\t',1)[1]:int(line.split()[0][-3:],8) for line in git(root,'ls-tree','-r',base).splitlines() if production_graphic(line.split('\t',1)[1])}
    baseline_set=set(baseline_modes)
    current_set={str(p.relative_to(root)) for prefix in ('docs/img','app/src/main/res','app/src/main') for p in (root/prefix).rglob('*') if p.is_file() and production_graphic(str(p.relative_to(root)))}
    require(current_set==baseline_set,'Added/removed production graphic/font files')
    for path in baseline_set:
        file=confined(root,path)
        require(sha(file.read_bytes())==APPROVED_GRAPHICS_SHA256.get(path,sha(baseline_bytes(root,base,path))),'Graphic/font outside approved bundle changed: '+path)
        require(stat.S_IMODE(file.stat().st_mode)==baseline_modes[path],'Graphic/font mode changed: '+path)
    onboarding=(root/'app/src/main/java/com/gezimos/katapult/ui/OnboardingScreen.kt').read_text()
    painters=re.findall(r'painterResource\(\s*R\.drawable\.([A-Za-z0-9_]+)\s*\)',onboarding)
    require(painters==['katapult_logo'],'Inherited graphical wordmark reference replaced/duplicated')
    require(not re.search(r'\b(?:Canvas|Image|rememberVectorPainter)\s*\(',onboarding),'Alternate onboarding artwork implementation')
    return {'status':SOURCE_ARTWORK_STATUS,'assetCount':27,'replacementCount':18,'indexSha256':sha(index_bytes),
            'approvedBundleIndexSha256':APPROVED_BUNDLE_INDEX_SHA256,'approvedSourceMasterSha256':APPROVED_SOURCE_MASTER_SHA256}


def original_native(data,suffix):
    """Derive the original-index native descriptor from immutable baseline bytes."""
    if suffix=='.webp':
        require(data[:4]==b'RIFF' and data[8:12]==b'WEBP' and data[12:16]==b'VP8L' and data[20:21]==b'\x2f','Malformed original WebP')
        bits=int.from_bytes(data[21:25],'little')
        return {'format':'WebP','encoding':'VP8L','pixels':{'width':(bits&0x3fff)+1,'height':((bits>>14)&0x3fff)+1}}
    if suffix=='.png':
        require(data[:8]==b'\x89PNG\r\n\x1a\n' and data[12:16]==b'IHDR','Malformed original PNG')
        width,height=struct.unpack('>II',data[16:24])
        return {'format':'PNG','pixels':{'width':width,'height':height},'bitDepth':data[24],'colorType':data[25]}
    node=ET.fromstring(data)
    if suffix=='.svg':
        require(node.tag=='{http://www.w3.org/2000/svg}svg','Malformed original SVG')
        return {'format':'SVG','viewBox':[float(x) for x in node.get('viewBox','').split()],
                'declaredWidth':node.get('width'),'declaredHeight':node.get('height'),
                'transforms':[n.get('transform') for n in node.iter() if n.get('transform')],
                'unmeasured':['Raster pixel dimensions are not inferred from SVG viewBox.']}
    require(suffix=='.xml','Unknown original native format')
    result={'format':'Android XML','rootType':node.tag,'pixels':None,'unmeasured':['dp/viewports/transforms are not raster pixel dimensions.']}
    if node.tag=='adaptive-icon':result['layers']=[{'type':n.tag,'drawable':n.get(ANDROID+'drawable')} for n in node]
    elif node.tag=='resources':result['definitions']=[{'type':n.tag,'name':n.get('name'),'value':(n.text or '').strip()} for n in node]
    elif node.tag=='vector':
        result.update(dpWidth=node.get(ANDROID+'width'),dpHeight=node.get(ANDROID+'height'),viewportWidth=node.get(ANDROID+'viewportWidth'),
                      viewportHeight=node.get(ANDROID+'viewportHeight'),tint=node.get(ANDROID+'tint'),
                      fillColors=sorted({n.get(ANDROID+'fillColor') for n in node.iter('path') if n.get(ANDROID+'fillColor')}),
                      groupTransforms=[{k.removeprefix(ANDROID):v for k,v in n.attrib.items()} for n in node.iter('group')])
    else:raise VerificationError('Unknown original Android XML root')
    return result


def xml_signature(element):
    return (element.tag,tuple(sorted(element.attrib.items())),tuple(xml_signature(c) for c in element))


def strings(root,source_set,locale):
    p=confined(root,f'app/src/{source_set}/res/values{locale}/strings.xml')
    data=ET.fromstring(p.read_bytes());rows={n.get('name'):n.text for n in data.findall('string')}
    require(len(rows)==len(data.findall('string')),'Duplicate resource names')
    return rows


def execution_identity(root,base,args):
    require(base==BASELINE,'Unapproved immutable asset baseline')
    require(args is not None,'Independent execution HEAD/tree/input receipt required')
    return source_receipt.verify(root,base,args.expected_source_head,args.expected_source_tree,
                                 args.source_receipt,args.expected_source_receipt_sha256)


def source_identity(root,base,index_path,execution=None):
    provenance=execution_identity(root,base,execution)
    build=(root/'app/build.gradle.kts').read_text();original=baseline_bytes(root,base,'app/build.gradle.kts').decode()
    require(build==original.replace('archivesName.set("katapult-v1.6")','archivesName.set("kotatsu-v1.0")').replace('applicationId = "com.gezimos.katapult"','applicationId = "'+RELEASE_ID+'"').replace('versionName = "1.6"','versionName = "1.0"'),'Build versions/dependencies/signing/optimization or namespace changed beyond identity')
    settings=(root/'settings.gradle.kts').read_bytes()
    require(settings==baseline_bytes(root,base,'settings.gradle.kts').replace(b'rootProject.name = "Katapult"',b'rootProject.name = "Kotatsu"'),'Root settings changed beyond identity')
    baseline_manifest=ET.fromstring(baseline_bytes(root,base,'app/src/main/AndroidManifest.xml'));manifest=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
    require(manifest.find('application').attrib==baseline_manifest.find('application').attrib,'Application backup/icon/manifest attributes changed')
    optional=[n for n in manifest.findall('uses-feature') if n.get(ANDROID+'name')=='android.hardware.telephony']
    require(len(optional)==1 and optional[0].get(ANDROID+'required')=='false','Optional telephony feature missing')
    for name in ('backup_rules.xml','data_extraction_rules.xml'):
        path='app/src/main/res/xml/'+name;require(confined(root,path).read_bytes()==baseline_bytes(root,base,path),'Backup XML changed')
    for source_set,package in (('main',RELEASE_ID),('debug',RELEASE_ID+'.debug')):
        shortcut=ET.parse(root/f'app/src/{source_set}/res/xml/shortcuts.xml').getroot()
        intents=list(shortcut.iter('intent'));require(len(intents)==1,'Unexpected shortcut intent count')
        require(intents[0].get(ANDROID+'targetPackage')==package and intents[0].get(ANDROID+'targetClass')==NAMESPACE+'.lockscreen.ScreensaverActivity','Wrong source shortcut namespace/package')
        require(shortcut.find('shortcut').get(ANDROID+'icon')=='@drawable/ic_screensaver','Shortcut artwork reference changed')
    for locale in LOCALES:
        require(strings(root,'main',locale).get('app_name')=='Kotatsu','Wrong localized main label: '+locale)
        require(strings(root,'debug',locale).get('app_name')=='Kotatsu Debug','Missing/wrong localized debug label: '+locale)
    require(manifest.find('application').get(ANDROID+'icon')=='@mipmap/ic_launcher' and manifest.find('application').get(ANDROID+'roundIcon')=='@mipmap/ic_launcher_round','Launcher references changed')
    require(index_path is not None,'Explicit original provenance index required')
    checks=deferred_assets(root,base,index_path)
    checks['sourceProvenance']=provenance
    return checks


UPDATER_RESOURCES={'check_updates','check_updates_desc','perm_install_apps','perm_install_apps_desc',
    'update_checking','update_up_to_date','update_available','update_current_version','update_notes_title',
    'update_download_install','update_downloading','update_error','update_ready_install','update_install',
    'update_download_failed','retry'}
UPDATER_SYMBOLS=r'\b(?:UpdateChecker|UpdateDialog|UpdateState|downloadUpdate|clearDownloadedUpdate|updateReadyTag|updateProgress|updateFailed)\b'
PROHIBITED_PERMISSIONS={'android.permission.'+name for name in ('INTERNET','REQUEST_INSTALL_PACKAGES','INSTALL_PACKAGES')}


def source_offline(root):
    for relative in ('app/src/main/java/com/gezimos/katapult/util/UpdateChecker.kt','app/src/main/res/xml/file_paths.xml'):
        require(not (root/relative).exists(),'Updater source/path still present: '+relative)
    for path in (root/'app/src/main').rglob('*.kt'):
        text=path.read_text()
        require(not re.search(UPDATER_SYMBOLS,text) and not re.search(r'api\.github\.com/repos/.*/releases/latest|ACTION_MANAGE_UNKNOWN_APP_SOURCES|REQUEST_INSTALL_PACKAGES|update\.apk',text),'Updater source chain remains: '+str(path.relative_to(root)))
    for locale in LOCALES:
        require(not UPDATER_RESOURCES.intersection(strings(root,'main',locale)),'Updater source resources remain: '+locale)
    return {'updaterSourceChainAbsent':True}


def compiled_offline(manifest,resource_text,dex_text):
    permissions={node.get(ANDROID+'name') for node in manifest if node.tag.startswith('uses-permission')}
    require(not permissions.intersection(PROHIBITED_PERMISSIONS),'Prohibited compiled permission')
    resources=resource_files(resource_text)
    names={entry['name'] for entry in resources.values()}
    require(not {'string/'+name for name in UPDATER_RESOURCES}.intersection(names) and 'xml/file_paths' not in names,'Exclusive compiled updater resource')
    path_ids={rid for rid,entry in resources.items() if entry['name']=='xml/file_paths'}
    app=manifest.find('application');require(app is not None,'Compiled application missing')
    for provider in app.findall('provider'):
        authorities=(provider.get(ANDROID+'authorities') or '').split(';')
        require(not any(a.endswith('.fileprovider') for a in authorities),'Updater provider authority remains')
        for metadata in provider.findall('meta-data'):
            reference=metadata.get(ANDROID+'resource','')
            require(reference!='@xml/file_paths' and reference.removeprefix('@ref/') not in path_ids,'Updater paths metadata remains')
    rows=dex_text.splitlines()
    classes=[row for row in rows if re.match(r'^C d \d+\s+\d+\s+\d+\s+\S+',row)]
    require(classes and any(row.startswith('P d ') and row.endswith('<TOTAL>') for row in rows),'Missing/malformed actual defined-only DEX output')
    require(not any(re.match(r'^[CMF] [^d]',row) for row in rows),'DEX output includes nondefined entries')
    definitions='\n'.join(row for row in rows if row.startswith(('C d ','M d ','F d ')))
    # Mappings are applied by apkanalyzer, so this includes original names of
    # obfuscated classes AND members rather than an APK string-absence guess.
    require(not re.search(UPDATER_SYMBOLS,definitions.replace('$',' ')),'Compiled updater definition remains')
    return {'noInternetUpdaterInstallerCapability':True,'permissionTagsInspected':True,
            'definedDexSha256':sha(dex_text.encode()),'resourceTableSha256':sha(resource_text.encode()),
            'scope':'ordinary app Internet/install capability and known updater chain only; not whole-project privacy'}


def build_identity(args,root,apk,provenance,index_digest):
    require(isinstance(args.build_receipt,str) and re.fullmatch(r'[0-9a-f]{64}',args.expected_build_receipt_sha256 or ''),'Independent release build receipt/digest required')
    path=Path(args.build_receipt)
    require(path.is_file() and not path.is_symlink(),'Missing build receipt')
    data=path.read_bytes();require(sha(data)==args.expected_build_receipt_sha256,'Independent build receipt digest mismatch')
    receipt=json.loads(data)
    require(receipt.get('sourceBefore')==receipt.get('sourceAfter')==provenance,'Build source receipt mismatch/replay')
    require(receipt.get('deferredIndexSha256')==index_digest,'Build asset index mismatch')
    tasks=receipt.get('tasks',[])
    require([t.get('task') for t in tasks]==[':app:testReleaseUnitTest',':app:lintRelease',':app:assembleRelease'],'Missing current release task evidence')
    for task in tasks:
        require(task.get('exitCode')==0,'Unsuccessful build task receipt')
        log=Path(task.get('log',''));require(log.is_file() and sha(log.read_bytes())==task.get('logSha256'),'Build log changed/missing')
        text=log.read_text()
        require('BUILD SUCCESSFUL' in text,'Unsuccessful task output')
        observed={m.group(1):m.group(2) for m in re.finditer(r'^> Task (:[A-Za-z0-9_:]+)([^\n]*)$',text,re.M)}
        required={':app:testReleaseUnitTest':(':app:testReleaseUnitTest',),
                  ':app:lintRelease':(':app:lintAnalyzeRelease',':app:lintReportRelease'),
                  ':app:assembleRelease':(':app:minifyReleaseWithR8',':app:optimizeReleaseResources',':app:packageRelease')}[task['task']]
        require(task['task'] in observed,'Missing actual task output')
        require(all(name in observed and not observed[name].strip() for name in required),'Required build work not freshly executed')
        if task['task']==':app:assembleRelease':
            require('minifyReleaseWithR8' in text and re.search(r'R8|r8',text) and re.search(r'shrink|[Rr]esource.*optimi',text),'Missing current R8/resource-shrinking output')
    required=['app/build/outputs/apk/release/'+apk.name,'app/build/outputs/apk/release/output-metadata.json',
              'app/build/outputs/mapping/release/mapping.txt']
    files=receipt.get('artifacts',{})
    require(all(name in files for name in required),'Missing bound APK/metadata/current mapping')
    for name,digest in files.items():
        require(sha(confined(root,name).read_bytes())==digest,'Build artifact/evidence changed: '+name)
    require('app/build/outputs/mapping/release/resources.txt' in files,'Missing current resource shrink evidence')
    return {'buildReceiptSha256':sha(data),'mappingSha256':files[required[2]],'tasks':[t['task'] for t in tasks]}


def unique_apk(directory,root,variant):
    directory=Path(directory).absolute();expected=Path(root)/'app/build/outputs/apk'/variant
    require(directory==expected.absolute() and not directory.is_symlink(),'Variant artifact directory escape')
    require(directory.is_dir(),'Missing APK directory')
    for parent in directory.parents:
        require(not parent.is_symlink(),'Artifact ancestor symlink')
    metadata=json.loads((directory/'output-metadata.json').read_text())
    require(metadata.get('variantName')==variant and metadata.get('applicationId')==RELEASE_ID+('.debug' if variant=='debug' else ''),'APK metadata ID/variant mismatch')
    elements=metadata.get('elements');require(isinstance(elements,list) and len(elements)==1,'Need exactly one metadata output')
    name=elements[0].get('outputFile');require(isinstance(name,str) and Path(name).name==name and name.endswith('.apk'),'Metadata path escape')
    candidates=list(directory.glob('*.apk'));require(len(candidates)==1 and candidates[0].name==name,'Zero/multiple/mismatched immediate APKs')
    require(not candidates[0].is_symlink() and candidates[0].is_file(),'APK is not a confined regular file')
    return candidates[0]


def parse_badging(text):
    package=re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",text,re.M)
    require(package is not None,'Malformed aapt2 badging package')
    labels={m.group(1) or 'default':m.group(2) for m in re.finditer(r"^application-label(?:-([^:]+))?:'([^']*)'$",text,re.M)}
    require(labels and 'default' in labels,'Missing compiled labels')
    return {'package':package[1],'versionCode':package[2],'versionName':package[3],'labels':labels,'debuggable':'application-debuggable' in text.splitlines()}


def resource_files(text):
    entries={};current=None;names=set()
    for line in text.splitlines():
        match=re.match(r'\s*resource (0x[0-9a-fA-F]+) ([A-Za-z0-9_]+/[A-Za-z0-9_]+)\s*$',line)
        if match:
            current=match[1].lower();require(current not in entries and match[2] not in names,'Duplicate resource ID/name')
            names.add(match[2]);entries[current]={'name':match[2],'files':[],'configurations':{},'colors':{}}
            continue
        if current:
            match=re.fullmatch(r'\s*\(([^)]*)\) \(file\) (\S+)(?: type=([A-Z]+))?\s*',line)
            if match:
                config,file,kind=match.groups();entry=entries[current]
                require(config not in entry['configurations'],'Duplicate file configuration')
                kind=kind or Path(file).suffix[1:].upper()
                entry['files'].append(file);entry['configurations'][config]={'path':file,'type':kind}
            match=re.fullmatch(r'\s*\(([^)]*)\) (#[0-9a-fA-F]{8})\s*',line)
            if match:
                require(match[1] not in entries[current]['colors'],'Duplicate color configuration')
                entries[current]['colors'][match[1]]=match[2].lower()
    require(entries,'Malformed/empty resource table')
    return entries


def resource_entry(reference,resources,expected):
    match=re.fullmatch(r'@(?:ref/)?(0x[0-9a-fA-F]+)',reference or '')
    if match:entry=resources.get(match[1].lower())
    elif reference=='@'+expected:
        candidates=[e for e in resources.values() if e['name']==expected]
        require(len(candidates)==1,'Unknown/ambiguous named branding reference');entry=candidates[0]
    else:raise VerificationError('Malformed compiled branding reference')
    require(entry is not None and entry['name']==expected,'Missing/wrong-type compiled branding reference: '+expected)
    return entry


def resource_path(file,zip_names=None):
    require(isinstance(file,str) and file.startswith('res/') and '\\' not in file and all(p not in ('','.','..') for p in file.split('/')),'Unsafe branding ZIP path')
    require(zip_names is None or file in zip_names,'Missing branding ZIP payload')
    return file


def one_vector_path(entry,zip_names):
    configs=entry.get('configurations',{})
    require(set(configs)=={''},'Missing/ambiguous vector configurations')
    require(configs['']['type']=='XML','Wrong vector resource payload type')
    return resource_path(configs['']['path'],zip_names)


def path_geometry(data):
    with localcontext() as context:
        # More precision than any budget-compliant decimal plus the finite
        # exponent range; relative coordinates must not round away a change.
        context.prec=9000
        return _path_geometry(data)


def _path_geometry(data):
    """Lossless canonical events for the compact bundle's M/L/H/V/C/S/Q/T/Z grammar.

    Decimal coordinates keep path comparison exact, separately from SDK float32
    attribute displays. Ordered contours and explicit closure remain observable.
    Unsupported arc syntax is rejected rather than approximated.
    """
    require(isinstance(data,str) and data.isascii() and 0<len(data)<=8192 and 'STRING_TOO_LARGE' not in data,'Corrupt/oversized pathData')
    token=re.compile(r'[MmLlHhVvCcSsQqTtZz]|[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?')
    tokens=[];end=0;previous=None
    for match in token.finditer(data):
        gap=data[end:match.start()];value=match[0];is_command=value.isalpha()
        require(re.fullmatch(r'\s*(?:,\s*)?',gap) is not None,'Unconsumed/malformed pathData')
        require(',' not in gap or (previous is not None and not previous.isalpha() and not is_command),'Malformed path comma')
        tokens.append(value);previous=value;end=match.end()
    require(data[end:].strip()=='' and tokens,'Unconsumed pathData suffix')
    zero=Decimal(0);point=(zero,zero);start=None;events=[];last=None;control=None;i=0;cmd=None
    arities={'M':2,'L':2,'H':1,'V':1,'C':6,'S':4,'Q':4,'T':2}
    def encoded(values):
        require(all(math.isfinite(float(v)) and (not v or float(v)!=0) for v in values),'Nonfinite/underflowing path coordinate')
        return [str(v.normalize()) if v else '0' for v in values]
    while i<len(tokens):
        if tokens[i].isalpha():
            cmd=tokens[i];i+=1
            if cmd.upper()=='Z':
                require(start is not None and last not in (None,'Z'),'Invalid path closure')
                events.append(['Z']);point=start;last='Z';control=None;cmd=None;continue
        require(cmd is not None and cmd.upper() in arities,'Missing/unsupported path command')
        kind=cmd.upper();arity=arities[kind]
        require(i+arity<=len(tokens) and all(not t.isalpha() for t in tokens[i:i+arity]),'Wrong path command arity')
        nums=[Decimal(t) for t in tokens[i:i+arity]];encoded(nums);i+=arity
        require(kind=='M' or start is not None,'Path must begin with moveto')
        relative=cmd.islower()
        def pair(x,y):return (x+point[0],y+point[1]) if relative else (x,y)
        if kind in ('M','L','T'):
            target=pair(*nums)
            if kind=='T':
                c=(2*point[0]-control[0],2*point[1]-control[1]) if last in ('Q','T') else point
                events.append(['Q',*encoded((*c,*target))]);control=c
            else:
                events.append([kind,*encoded(target)]);control=None
                if kind=='M':start=target;cmd='l' if relative else 'L'
        elif kind in ('H','V'):
            target=((point[0]+nums[0] if relative else nums[0]),point[1]) if kind=='H' else (point[0],point[1]+nums[0] if relative else nums[0])
            events.append(['L',*encoded(target)]);control=None
        elif kind in ('C','S'):
            if kind=='C':c1=pair(*nums[:2]);c2=pair(*nums[2:4]);target=pair(*nums[4:])
            else:
                c1=(2*point[0]-control[0],2*point[1]-control[1]) if last in ('C','S') else point
                c2=pair(*nums[:2]);target=pair(*nums[2:])
            events.append(['C',*encoded((*c1,*c2,*target))]);control=c2
        else:
            c=pair(*nums[:2]);target=pair(*nums[2:]);events.append(['Q',*encoded((*c,*target))]);control=c
        point=target;last=kind
    require(events and any(e[0] not in ('M','Z') for e in events),'Empty path geometry')
    return events


def vector_attribute(key,value):
    if key=='pathData':return path_geometry(value)
    if key=='name':require(bool(value),'Empty vector name');return value
    enums={'fillType':{'nonZero':0,'evenOdd':1,'0':0,'1':1},'strokeLineCap':{'butt':0,'round':1,'square':2,'0':0,'1':1,'2':2},
           'strokeLineJoin':{'miter':0,'round':1,'bevel':2,'0':0,'1':1,'2':2}}
    if key in enums:require(value in enums[key],'Unknown vector enum');return enums[key][value]
    if key in ('fillColor','strokeColor','tint'):
        require(re.fullmatch(r'#[0-9a-fA-F]{3,8}',value) is not None,'Unsupported vector color')
        raw=value[1:].lower()
        if len(raw) in (3,4):raw=''.join(c*2 for c in raw)
        if len(raw)==6:raw='ff'+raw
        require(len(raw)==8,'Malformed vector color');return '#'+raw
    if key=='autoMirrored':require(value in ('true','false'),'Malformed vector boolean');return value=='true'
    numeric={'width','height','viewportWidth','viewportHeight','alpha','rotation','pivotX','pivotY','scaleX','scaleY','translateX','translateY',
             'fillAlpha','strokeAlpha','strokeWidth','strokeMiterLimit','trimPathStart','trimPathEnd','trimPathOffset'}
    require(key in numeric,'Unsupported vector attribute: '+key)
    if key in ('width','height'):require(value.endswith('dp'),'Unexpected vector dimension unit');value=value[:-2]
    require(re.fullmatch(r'[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?',value) is not None,'Malformed vector number')
    number=float(value);require(math.isfinite(number),'Nonfinite vector number')
    return number


def vector_semantics(node):
    allowed={'vector':{'width','height','viewportWidth','viewportHeight','alpha','tint','autoMirrored','name'},
             'group':{'name','rotation','pivotX','pivotY','scaleX','scaleY','translateX','translateY'},
             'path':{'name','pathData','fillType','fillColor','fillAlpha','strokeColor','strokeWidth','strokeAlpha','strokeLineCap','strokeLineJoin','strokeMiterLimit','trimPathStart','trimPathEnd','trimPathOffset'}}
    def visit(element,parent=None):
        require(element.tag in allowed and (parent is None and element.tag=='vector' or parent in ('vector','group') and element.tag in ('group','path')),'Unsupported vector structure')
        require(not (element.text or '').strip() and not (element.tail or '').strip(),'Unexpected vector text')
        attrs={}
        for key,value in element.attrib.items():
            require(key.startswith(ANDROID) and key[len(ANDROID):] in allowed[element.tag],'Unsupported vector attribute/namespace')
            key=key[len(ANDROID):];attrs[key]=vector_attribute(key,value)
        if element.tag=='vector':
            require({'width','height','viewportWidth','viewportHeight'}<=set(attrs) and all(attrs[k]>0 for k in ('width','height','viewportWidth','viewportHeight')),'Missing/invalid vector viewport/dimensions')
        if element.tag=='path':require('pathData' in attrs and len(element)==0,'Missing path geometry/invalid children')
        return {'tag':element.tag,'attributes':attrs,'children':[visit(n,element.tag) for n in element]}
    return visit(node)


def vector_xmltree(text):
    require('STRING_TOO_LARGE' not in text,'Encoding corruption in XML tree')
    root=None;stack=[]
    for line in text.splitlines():
        if re.fullmatch(r'\s*N: android=http://schemas.android.com/apk/res/android \(line=\d+\)',line):continue
        match=re.fullmatch(r'(\s*)E: (\w+) \(line=\d+\)',line)
        if match:
            indent=len(match[1]);node=ET.Element(match[2])
            while stack and stack[-1][0]>=indent:stack.pop()
            if stack:stack[-1][1].append(node)
            else:require(root is None,'Multiple XML tree roots');root=node
            stack.append((indent,node));continue
        match=re.fullmatch(r'(\s*)A: http://schemas.android.com/apk/res/android:(\w+)\(0x[0-9a-fA-F]+\)=(.*)',line)
        if match:
            require(stack and len(match[1])>stack[-1][0],'Unowned XML tree attribute')
            value=match[3].strip()
            if value.startswith('"'):
                value,end=json.JSONDecoder().raw_decode(value)
                suffix=match[3].strip()[end:];require(not suffix or re.fullmatch(r' \(Raw: ".*"\)',suffix),'Unparsed XML tree attribute')
            else:require(' (Raw:' not in value,'Unexpected typed raw vector value')
            key=ANDROID+match[2];require(key not in stack[-1][1].attrib,'Duplicate XML tree attribute');stack[-1][1].set(key,value);continue
        require(not line.strip(),'Unsupported XML tree output')
    require(root is not None,'Missing XML tree root')
    return vector_semantics(root)


def compare_vectors(source,observed,numeric_mode='decoded-xml'):
    require(source['tag']==observed['tag'] and source['attributes'].keys()==observed['attributes'].keys(),'Vector tag/attribute structure differs')
    for key,value in source['attributes'].items():
        other=observed['attributes'][key]
        if type(value) is float:
            if numeric_mode=='xmltree':
                expected=float(format(struct.unpack('<f',struct.pack('<f',value))[0],'.6g'))
                require(expected==other,'XML tree float32 attribute differs: '+key)
            else:require(type(other) is float and math.isclose(value,other,rel_tol=1e-6,abs_tol=1e-5),'Decoded vector attribute differs: '+key)
        else:require(value==other,'Vector geometry/paint/name differs: '+key)
    require(len(source['children'])==len(observed['children']),'Vector group/path membership differs')
    for a,b in zip(source['children'],observed['children']):compare_vectors(a,b,numeric_mode)


def validate_packaged_branding(value,zip_names=None):
    """One complete contract for artifact publication AND report consumption."""
    require(isinstance(value,dict) and set(value)=={'schemaVersion','status','bundleIndexSha256','noEncodingCorruption','manifestIcons','adaptive','vectors','legacyBitmaps'},'Incomplete packaged branding schema')
    require(type(value['schemaVersion']) is int and value['schemaVersion']==1 and value['status']=='verified' and value['noEncodingCorruption'] is True,'Invalid packaged branding status/type')
    require(value['bundleIndexSha256']==APPROVED_BUNDLE_INDEX_SHA256,'Packaged branding bundle mismatch')
    require(value['manifestIcons']=={'icon':ICON_NAMES[0],'roundIcon':ICON_NAMES[1]},'Wrong manifest branding coverage')
    require(isinstance(value['adaptive'],dict) and set(value['adaptive'])==set(ICON_NAMES),'Incomplete adaptive coverage')
    for row in value['adaptive'].values():
        require(isinstance(row,dict) and set(row)=={'zipPath','foreground','monochrome','background','backgroundValue'},'Incomplete adaptive evidence')
        require(row['foreground']=='drawable/ic_launcher_foreground' and row['monochrome']=='drawable/ic_launcher_monochrome' and row['background']=='color/ic_launcher_background' and row['backgroundValue']=='#ffffffff','Changed adaptive layers/white background')
        require(resource_path(row['zipPath'],zip_names).endswith('.xml'),'Wrong adaptive ZIP type')
    require(isinstance(value['vectors'],dict) and set(value['vectors'])==set(VECTOR_COUNTS),'Incomplete vector coverage')
    for name,row in value['vectors'].items():
        require(isinstance(row,dict) and set(row)=={'zipPath','sourceSha256','pathCount','verified','decodedXmlSha256','xmltreeSha256','semanticSha256'},'Incomplete vector evidence')
        path='app/src/main/res/'+name+'.xml'
        require(row['sourceSha256']==APPROVED_GRAPHICS_SHA256[path] and type(row['pathCount']) is int and row['pathCount']==VECTOR_COUNTS[name] and row['verified'] is True,'Unapproved vector proof')
        require(resource_path(row['zipPath'],zip_names).endswith('.xml'),'Wrong vector ZIP type')
        for field in ('sourceSha256','decodedXmlSha256','xmltreeSha256','semanticSha256'):require(isinstance(row[field],str) and re.fullmatch(r'[0-9a-f]{64}',row[field]),'Malformed vector evidence digest')
    require(isinstance(value['legacyBitmaps'],dict) and set(value['legacyBitmaps'])==set(ICON_NAMES),'Incomplete legacy bitmap coverage')
    for name,densities in value['legacyBitmaps'].items():
        require(isinstance(densities,dict) and set(densities)==set(DENSITIES),'Incomplete legacy density coverage')
        for density,row in densities.items():
            require(isinstance(row,dict) and set(row)=={'zipPath','sha256'},'Incomplete bitmap evidence')
            path=f'app/src/main/res/mipmap-{density}/{name.split("/")[1]}.webp'
            require(row['sha256']==APPROVED_GRAPHICS_SHA256[path],'Unapproved packaged WebP digest')
            require(resource_path(row['zipPath'],zip_names).endswith('.webp'),'Wrong bitmap ZIP type')
    return value


def packaged_branding(args,root,apk,app,resources,names):
    result={'schemaVersion':1,'status':'verified','bundleIndexSha256':APPROVED_BUNDLE_INDEX_SHA256,'noEncodingCorruption':True,
            'manifestIcons':{},'adaptive':{},'vectors':{},'legacyBitmaps':{}}
    with zipfile.ZipFile(apk) as archive:
        for attribute,name in zip(('icon','roundIcon'),ICON_NAMES):
            entry=resource_entry(app.get(ANDROID+attribute),resources,name);result['manifestIcons'][attribute]=name
            configs=entry.get('configurations',{});adaptive=set(configs)-set(DENSITIES)
            require(set(DENSITIES)<=set(configs) and len(adaptive)==1 and adaptive<={'anydpi','anydpi-v26'},'Missing/extra launcher configurations')
            config=next(iter(adaptive));require(configs[config]['type']=='XML','Wrong adaptive wrapper type')
            file=resource_path(configs[config]['path'],names)
            wrapper=ET.fromstring(command(args.apkanalyzer,'resources','xml','--file',file,apk))
            require(wrapper.tag=='adaptive-icon' and not wrapper.attrib and [n.tag for n in wrapper]==['background','foreground','monochrome'],'Changed adaptive wrapper structure')
            row={'zipPath':file}
            for node,dependency in zip(wrapper,('color/ic_launcher_background','drawable/ic_launcher_foreground','drawable/ic_launcher_monochrome')):
                require(set(node.attrib)=={ANDROID+'drawable'} and len(node)==0,'Changed adaptive wrapper attributes')
                dep=resource_entry(node.get(ANDROID+'drawable'),resources,dependency);row[node.tag]=dependency
                if node.tag=='background':
                    require(dep.get('colors')=={'':'#ffffffff'} and not dep['files'],'Changed compiled white background')
                    row['backgroundValue']='#ffffffff'
            result['adaptive'][name]=row;result['legacyBitmaps'][name]={}
            for density in DENSITIES:
                item=configs[density];require(item['type']=='WEBP','Wrong legacy bitmap type')
                path=resource_path(item['path'],names);digest=sha(archive.read(path))
                original_path=f'app/src/main/res/mipmap-{density}/{name.split("/")[1]}.webp'
                require(digest==APPROVED_GRAPHICS_SHA256[original_path],'Substituted packaged WebP: '+name+'/'+density)
                result['legacyBitmaps'][name][density]={'zipPath':path,'sha256':digest}
        for name,count in VECTOR_COUNTS.items():
            entry=resource_entry('@'+name,resources,name);file=one_vector_path(entry,names)
            source_path='app/src/main/res/'+name+'.xml';source_bytes=confined(root,source_path).read_bytes()
            require(sha(source_bytes)==APPROVED_GRAPHICS_SHA256[source_path],'Unapproved vector source')
            source_node=ET.fromstring(source_bytes);source=vector_semantics(source_node)
            require(len(list(source_node.iter('path')))==count and len(list(source_node.iter('group')))==1,'Changed compact vector organization')
            xml=command(args.apkanalyzer,'resources','xml','--file',file,apk)
            tree=command(args.aapt2,'dump','xmltree',apk,'--file',file)
            observed=vector_semantics(ET.fromstring(xml));compare_vectors(source,observed)
            compare_vectors(source,vector_xmltree(tree),'xmltree')
            result['vectors'][name]={'zipPath':file,'sourceSha256':sha(source_bytes),'pathCount':count,'verified':True,
                'decodedXmlSha256':sha(xml.encode()),'xmltreeSha256':sha(tree.encode()),
                'semanticSha256':sha(json.dumps(observed,sort_keys=True,separators=(',',':')).encode())}
    return validate_packaged_branding(result,names)


def resolved_xml(reference,resources,zip_names):
    match=re.fullmatch(r'@(?:ref/)?(0x[0-9a-fA-F]+)',reference or '')
    if match:entry=resources.get(match[1].lower())
    elif (reference or '').startswith('@xml/'):
        candidates=[v for v in resources.values() if v['name']==reference[1:]];require(len(candidates)==1,'Unknown/ambiguous named XML reference');entry=candidates[0]
    else:raise VerificationError('Malformed compiled XML reference: '+str(reference))
    require(entry is not None and entry['name'].startswith('xml/'),'XML resource ID missing/wrong type')
    files=set(entry['files']);require(len(files)==1,'Missing/ambiguous compiled XML paths')
    file=next(iter(files));parts=PurePosixPath(file).parts
    require(file in zip_names and not file.startswith('/') and '..' not in parts,'Compiled XML path escape/missing payload')
    return file


def signer(tool,apk):
    text=command(tool,'verify','--verbose','--print-certs',apk)
    values=re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',text,re.M)
    require(len(values)==1,'Missing/multiple/malformed APK signer')
    return values[0].lower()


def apk_identity(args):
    root=Path(args.root).absolute();checks=source_identity(root,args.baseline,args.deferred_assets_index,execution=args)
    apk=unique_apk(args.apk_dir,root,args.variant)
    require(re.fullmatch(r'[0-9a-fA-F]{64}',args.expected_cert_sha256 or ''),'Independent expected certificate required')
    cert=signer(args.apksigner,apk);require(cert==args.expected_cert_sha256.lower(),'APK signer mismatch')
    expected=RELEASE_ID+('.debug' if args.variant=='debug' else '')
    analyzer_xml=command(args.apkanalyzer,'manifest','print',apk);manifest=ET.fromstring(analyzer_xml)
    require(manifest.tag=='manifest' and manifest.get('package')==expected,'Actual compiled package mismatch')
    badging=parse_badging(command(args.aapt2,'dump','badging',apk))
    require(badging['package']==expected and badging['versionCode']=='7' and badging['versionName']==('1.0-debug' if args.variant=='debug' else '1.0'),'Compiled APK version/ID mismatch')
    label='Kotatsu Debug' if args.variant=='debug' else 'Kotatsu'
    require(all(v==label for v in badging['labels'].values()),'Wrong compiled locale label')
    require(set(x.removeprefix('-') for x in LOCALES if x)<=set(badging['labels']),'Missing compiled existing locale labels')
    require(badging['debuggable']==(args.variant=='debug'),'Compiled debuggability mismatch')
    require(command(args.apkanalyzer,'manifest','min-sdk',apk).strip()=='30' and command(args.apkanalyzer,'manifest','target-sdk',apk).strip()=='36','Compiled SDK mismatch')
    resource_text=command(args.aapt2,'dump','resources',apk);resources=resource_files(resource_text)
    with zipfile.ZipFile(apk) as archive:
        names=archive.namelist();require(len(names)==len(set(names)),'Duplicate archive entries');names=set(names)
    app=manifest.find('application');require(app is not None,'Compiled application missing')
    original=ET.fromstring(baseline_bytes(root,args.baseline,'app/src/main/AndroidManifest.xml')).find('application')
    require(app.get(ANDROID+'allowBackup')==original.get(ANDROID+'allowBackup'),'Compiled backup attribute changed')
    for attribute,source in (('fullBackupContent','backup_rules.xml'),('dataExtractionRules','data_extraction_rules.xml')):
        path=resolved_xml(app.get(ANDROID+attribute),resources,names)
        actual=ET.fromstring(command(args.apkanalyzer,'resources','xml','--file',path,apk))
        baseline=ET.fromstring(baseline_bytes(root,args.baseline,'app/src/main/res/xml/'+source))
        require(xml_signature(actual)==xml_signature(baseline),'Compiled backup contract changed')
    main=[n for n in app.findall('activity') if n.get(ANDROID+'name')==NAMESPACE+'.MainActivity'];require(len(main)==1,'Main component namespace missing')
    shortcut_meta=[n for n in main[0].findall('meta-data') if n.get(ANDROID+'name')=='android.app.shortcuts'];require(len(shortcut_meta)==1,'Packaged shortcut metadata missing')
    file=resolved_xml(shortcut_meta[0].get(ANDROID+'resource'),resources,names)
    shortcut=ET.fromstring(command(args.apkanalyzer,'resources','xml','--file',file,apk));intents=list(shortcut.iter('intent'))
    require(len(intents)==1 and intents[0].get(ANDROID+'targetPackage')==expected and intents[0].get(ANDROID+'targetClass')==NAMESPACE+'.lockscreen.ScreensaverActivity','Compiled shortcut intent mismatch')
    components={n.get(ANDROID+'name') for n in app.findall('activity')};require(NAMESPACE+'.lockscreen.ScreensaverActivity' in components,'Shortcut target component not packaged')
    optional=[n for n in manifest.findall('uses-feature') if n.get(ANDROID+'name')=='android.hardware.telephony'];require(len(optional)==1 and optional[0].get(ANDROID+'required')=='false','Compiled optional hardware missing')
    if args.variant=='release':
        mapping=confined(root,'app/build/outputs/mapping/release/mapping.txt');require(mapping.stat().st_size>0,'Empty release mapping/minification proof')
        checks['buildProvenance']=build_identity(args,root,apk,checks['sourceProvenance'],checks['indexSha256'])
    checks['packagedBranding']=packaged_branding(args,root,apk,app,resources,names)
    capability=None
    if getattr(args,'require_offline',False):
        source_offline(root)
        dex_args=[args.apkanalyzer,'dex','packages','--defined-only']
        if args.variant=='release':dex_args+=['--proguard-mappings',str(mapping)]
        dex=command(*dex_args,apk)
        capability=compiled_offline(manifest,resource_text,dex)
    execution_identity(root,args.baseline,args)
    report={'schemaVersion':2,'path':str(apk),'sha256':sha(apk.read_bytes()),'variant':args.variant,'applicationId':expected,'signerSha256':cert,'labels':badging['labels'],'checks':checks,'sourceProvenance':checks.get('sourceProvenance'),'backupContractPreserved':True,'shortcutIntentVerified':True,'artworkStatus':PACKAGED_ARTWORK_STATUS,'offlineCertified':False,'offlineCapability':capability}
    target=Path(args.report);require(not target.exists() and target.parent.is_dir(),'Report collision/missing owned parent')
    with target.open('x') as handle:json.dump(report,handle,indent=2,sort_keys=True);handle.write('\n')
    print('PASS apk-identity',args.variant,expected,';',PACKAGED_ARTWORK_STATUS)
    print(target)


def prerequisites(args):
    paths={'JDK':Path(args.jdk_home)/'bin/java','SDK root':Path(args.sdk_root),'Gradle cache':Path(args.gradle_user_home),'apkanalyzer':Path(args.apkanalyzer),'aapt2':Path(args.aapt2),'apksigner':Path(args.apksigner)}
    for name,p in paths.items():require(p.is_absolute() and p.exists(),'Missing/relative '+name+': '+str(p))
    version=subprocess.run([str(paths['JDK']),'-version'],capture_output=True,text=True)
    require(version.returncode==0 and re.search(r'version "21\.',version.stdout+version.stderr),'JDK21 required')
    for name in ('apkanalyzer','aapt2','apksigner'):require(paths[name].is_file() and os.access(paths[name],os.X_OK),'SDK tool not executable')
    for relative in ('platforms/android-36.1/android.jar','build-tools/36.0.0/aapt2','system-images/android-31/default/x86_64/package.xml'):
        require((Path(args.sdk_root)/relative).is_file(),'Missing pinned SDK package: '+relative)
    require('gradle-9.3.1-bin.zip' in (Path(args.root)/'gradle/wrapper/gradle-wrapper.properties').read_text(),'Wrapper version changed')
    print('PASS prereqs')


def main():
    p=argparse.ArgumentParser(description=__doc__);subs=p.add_subparsers(dest='mode',required=True)
    pre=subs.add_parser('prereqs');pre.add_argument('--root',required=True)
    for flag in ('jdk-home','sdk-root','gradle-user-home','apkanalyzer','aapt2','apksigner'):pre.add_argument('--'+flag,required=True)
    identity=subs.add_parser('identity');apk=subs.add_parser('apk-identity')
    for parser in (identity,apk):
        parser.add_argument('--root',required=True);parser.add_argument('--baseline',required=True);parser.add_argument('--deferred-assets-index',required=True,help='Original provenance index (legacy argument name); not the approved delivery manifest')
        for flag in ('expected-source-head','expected-source-tree','source-receipt','expected-source-receipt-sha256'):parser.add_argument('--'+flag,required=True)
    apk.add_argument('--variant',choices=('debug','release'),required=True)
    for flag in ('apk-dir','expected-cert-sha256','apkanalyzer','aapt2','apksigner','report'):apk.add_argument('--'+flag,required=True)
    apk.add_argument('--require-offline',action='store_true')
    apk.add_argument('--build-receipt');apk.add_argument('--expected-build-receipt-sha256')
    args=p.parse_args()
    if args.mode=='prereqs':prerequisites(args)
    elif args.mode=='identity':source_identity(Path(args.root).absolute(),args.baseline,args.deferred_assets_index,execution=args);print('PASS identity;',SOURCE_ARTWORK_STATUS)
    else:apk_identity(args)


if __name__=='__main__':
    try:main()
    except (VerificationError,OSError,ValueError,ET.ParseError,zipfile.BadZipFile) as exc:
        print('verify_fork: '+str(exc),file=sys.stderr);sys.exit(1)
