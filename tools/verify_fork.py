#!/usr/bin/env python3
"""Pinned identity and optional offline-capability checks; artwork stays deferred."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
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
    index=json.loads(Path(index_path).read_text())
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
        require(source==original and sha(source)==record.get('originalSha256'),'Graphical source changed or forged original hash: '+path)
        require(type(record.get('byteSize')) is int and len(source)==record['byteSize'],'Wrong asset byte count')
        tree=git(root,'ls-tree',base,'--',path).strip()
        mode,kind,blob,git_path=re.split(r'\s+',tree,maxsplit=3)
        require(kind=='blob' and git_path==path and record.get('gitBlob')==blob and record.get('gitMode')==mode,'Git asset provenance mismatch')
        require(type(record.get('fileMode')) is int and record['fileMode']==int(mode[-3:],8),'Wrong original file mode/type')
        require(stat.S_IMODE(confined(root,path).stat().st_mode)==record['fileMode'],'Original graphic mode changed: '+path)
    # Prevent alternate/new graphic resources from bypassing the fixed27 check.
    def production_graphic(path):
        return path.startswith('docs/img/') or re.match(r'app/src/main/res/(?:drawable[^/]*|mipmap[^/]*|font)/',path) or path=='app/src/main/ic_launcher-playstore.png'
    baseline_set={line.split('\t',1)[1] for line in git(root,'ls-tree','-r',base).splitlines() if production_graphic(line.split('\t',1)[1])}
    current_set={str(p.relative_to(root)) for prefix in ('docs/img','app/src/main/res','app/src/main') for p in (root/prefix).rglob('*') if p.is_file() and production_graphic(str(p.relative_to(root)))}
    require(current_set==baseline_set,'Added/removed production graphic/font files')
    for path in baseline_set:
        require(confined(root,path).read_bytes()==baseline_bytes(root,base,path),'Graphic/font outside fixed bundle changed: '+path)
    onboarding=(root/'app/src/main/java/com/gezimos/katapult/ui/OnboardingScreen.kt').read_text()
    painters=re.findall(r'painterResource\(\s*R\.drawable\.([A-Za-z0-9_]+)\s*\)',onboarding)
    require(painters==['katapult_logo'],'Inherited graphical wordmark reference replaced/duplicated')
    require(not re.search(r'\b(?:Canvas|Image|rememberVectorPainter)\s*\(',onboarding),'Alternate onboarding artwork implementation')
    return {'status':'graphics deferred; original source assets preserved','assetCount':27,'indexSha256':sha(Path(index_path).read_bytes())}


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
    require(build==original.replace('archivesName.set("katapult-v1.6")','archivesName.set("kotatsu-v1.6")').replace('applicationId = "com.gezimos.katapult"','applicationId = "'+RELEASE_ID+'"'),'Build versions/dependencies/signing/optimization or namespace changed beyond identity')
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
    require(index_path is not None,'Explicit deferred-assets index required for this code-first phase')
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
    entries={};current=None
    for line in text.splitlines():
        match=re.match(r'\s*resource (0x[0-9a-fA-F]+) ([A-Za-z0-9_]+/[A-Za-z0-9_]+)\s*$',line)
        if match:
            current=match[1].lower();require(current not in entries,'Duplicate resource ID');entries[current]={'name':match[2],'files':[]}
            continue
        if current:
            match=re.match(r'\s*\([^)]*\) \(file\) (\S+) type=XML\s*$',line)
            if match:entries[current]['files'].append(match[1])
    require(entries,'Malformed/empty resource table')
    return entries


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
    require(badging['package']==expected and badging['versionCode']=='7' and badging['versionName']==('1.6-debug' if args.variant=='debug' else '1.6'),'Compiled APK version/ID mismatch')
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
    capability=None
    if getattr(args,'require_offline',False):
        source_offline(root)
        dex_args=[args.apkanalyzer,'dex','packages','--defined-only']
        if args.variant=='release':dex_args+=['--proguard-mappings',str(mapping)]
        dex=command(*dex_args,apk)
        capability=compiled_offline(manifest,resource_text,dex)
    execution_identity(root,args.baseline,args)
    report={'schemaVersion':2,'path':str(apk),'sha256':sha(apk.read_bytes()),'variant':args.variant,'applicationId':expected,'signerSha256':cert,'labels':badging['labels'],'checks':checks,'sourceProvenance':checks.get('sourceProvenance'),'backupContractPreserved':True,'shortcutIntentVerified':True,'artworkStatus':'graphics deferred; original source assets preserved','offlineCertified':False,'offlineCapability':capability}
    target=Path(args.report);require(not target.exists() and target.parent.is_dir(),'Report collision/missing owned parent')
    with target.open('x') as handle:json.dump(report,handle,indent=2,sort_keys=True);handle.write('\n')
    print('PASS apk-identity',args.variant,expected,'; graphics deferred; original assets preserved')
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
        parser.add_argument('--root',required=True);parser.add_argument('--baseline',required=True);parser.add_argument('--deferred-assets-index',required=True)
        for flag in ('expected-source-head','expected-source-tree','source-receipt','expected-source-receipt-sha256'):parser.add_argument('--'+flag,required=True)
    apk.add_argument('--variant',choices=('debug','release'),required=True)
    for flag in ('apk-dir','expected-cert-sha256','apkanalyzer','aapt2','apksigner','report'):apk.add_argument('--'+flag,required=True)
    apk.add_argument('--require-offline',action='store_true')
    apk.add_argument('--build-receipt');apk.add_argument('--expected-build-receipt-sha256')
    args=p.parse_args()
    if args.mode=='prereqs':prerequisites(args)
    elif args.mode=='identity':source_identity(Path(args.root).absolute(),args.baseline,args.deferred_assets_index,execution=args);print('PASS identity; graphics deferred; original assets preserved')
    else:apk_identity(args)


if __name__=='__main__':
    try:main()
    except (VerificationError,OSError,ValueError,ET.ParseError,zipfile.BadZipFile) as exc:
        print('verify_fork: '+str(exc),file=sys.stderr);sys.exit(1)
