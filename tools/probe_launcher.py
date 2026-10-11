#!/usr/bin/env python3
"""Owned-VM public-UI phase-a observations and actual cold-process readback."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parent))
import source_receipt
import verify_fork

EXPECTED_IDS={'debug':'io.github.alexindigo.kotatsu.debug','release':'io.github.alexindigo.kotatsu'}
COMPONENT='com.gezimos.katapult.MainActivity'
LEGACY=('com.gezimos.katapult.debug','com.gezimos.katapult.debug.test')


class ProbeError(Exception):pass


def require(condition,message):
    if not condition:raise ProbeError(message)


def run(*args,binary=False):
    result=subprocess.run([str(x) for x in args],stdin=subprocess.DEVNULL,capture_output=True,text=not binary)
    require(result.returncode==0,'Public tool failed: '+str(args[0])+': '+str(result.stderr))
    return result.stdout


def validate_report(path,variant,package,cert,apksigner,expected_provenance,expected_apk_sha256):
    require(variant in EXPECTED_IDS and package==EXPECTED_IDS[variant],'Independent expected ID/variant is not Kotatsu')
    require(re.fullmatch('[0-9a-fA-F]{64}',cert or ''),'Independent approved certificate required')
    require(re.fullmatch('[0-9a-f]{64}',expected_apk_sha256 or ''),'Independent build-selected APK SHA256 required')
    report=json.loads(Path(path).read_text())
    require(isinstance(expected_provenance,dict) and expected_provenance.get('sourceReceiptSha256'),'Independent source provenance required')
    require(report.get('sourceProvenance')==expected_provenance,'Substituted/replayed source provenance')
    require(report.get('variant')==variant and report.get('applicationId')==package and report.get('signerSha256')==cert.lower(),'Substituted report ID/variant/signer')
    apk=Path(report.get('path',''))
    require(apk.is_absolute() and apk.is_file() and not apk.is_symlink(),'Invalid APK path')
    require(hashlib.sha256(apk.read_bytes()).hexdigest()==report.get('sha256'),'APK/report digest mismatch')
    require(report.get('sha256')==expected_apk_sha256,'Substituted/replayed APK differs from independent build receipt')
    signature=run(apksigner,'verify','--print-certs',apk)
    certificates=re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',signature,re.M)
    require(certificates==[cert.lower()],'Actual APK signer differs from independent approval')
    analyzer=Path('/srv/kotatsu/android-sdk/cmdline-tools/23.0/bin/apkanalyzer')
    manifest=ET.fromstring(run(analyzer,'manifest','print',apk))
    require(manifest.tag=='manifest' and manifest.get('package')==package,'Actual APK package differs from independent approval')
    application=manifest.find('application')
    require(application is not None,'Actual APK application missing')
    debuggable=application.get('{http://schemas.android.com/apk/res/android}debuggable','false')
    require(debuggable in ('true','false') and (debuggable=='true')==(variant=='debug'),'Actual APK variant differs from independent approval')
    require(report.get('backupContractPreserved') is True and report.get('shortcutIntentVerified') is True,'Incomplete APK identity report')
    require(report.get('artworkStatus')==verify_fork.PACKAGED_ARTWORK_STATUS,'Unapproved artwork/report status')
    checks=report.get('checks',{})
    require(isinstance(checks,dict) and checks.get('status')==verify_fork.SOURCE_ARTWORK_STATUS,'Incomplete source artwork approval')
    require(type(checks.get('assetCount')) is int and checks['assetCount']==27 and type(checks.get('replacementCount')) is int and checks['replacementCount']==18,'Wrong artwork coverage/count types')
    require(checks.get('indexSha256')==verify_fork.ORIGINAL_INDEX_SHA256 and checks.get('approvedBundleIndexSha256')==verify_fork.APPROVED_BUNDLE_INDEX_SHA256 and checks.get('approvedSourceMasterSha256')==verify_fork.APPROVED_SOURCE_MASTER_SHA256,'Unapproved artwork provenance')
    try:
        with zipfile.ZipFile(apk) as archive:
            names=archive.namelist();require(len(names)==len(set(names)),'Duplicate APK ZIP members')
            verify_fork.validate_packaged_branding(checks.get('packagedBranding'),set(names))
    except (verify_fork.VerificationError,zipfile.BadZipFile) as exc:raise ProbeError('Incomplete/unapproved packaged artwork: '+str(exc)) from exc
    return report,apk


class PublicUi:
    def __init__(self,adb,serial,package,output):
        self.adb=adb;self.serial=serial;self.package=package;self.output=output;self.sequence=0
    def device(self,*args,binary=False):return run(self.adb,'-s',self.serial,*args,binary=binary)
    def shell(self,*args):return self.device('shell','-n',*args).strip()
    def hierarchy(self):
        self.sequence+=1;path=f'/sdcard/kotatsu-phase-a-probe-{self.sequence}.xml'
        self.shell('uiautomator','dump',path)
        return ET.fromstring(self.device('exec-out','cat',path,binary=True))
    def find(self,label,root=None):
        root=self.hierarchy() if root is None else root
        matches=[n for n in root.iter('node') if n.get('text')==label or n.get('content-desc')==label]
        require(len(matches)<=1,'Ambiguous public control: '+label)
        return matches[0] if matches else None
    def wait(self,message,predicate,timeout=20):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            result=predicate()
            if isinstance(result,ET.Element) or result:return result
            time.sleep(.2)
        raise ProbeError(message)
    def click(self,label,long=False):
        node=self.wait('Missing public control: '+label,lambda:self.find(label))
        values=list(map(int,re.findall(r'\d+',node.get('bounds',''))));require(len(values)==4,'Invalid current UI bounds')
        x=(values[0]+values[2])//2;y=(values[1]+values[3])//2
        if long:self.shell('input','swipe',str(x),str(y),str(x),str(y),'750')
        else:self.shell('input','tap',str(x),str(y))
    def is_home(self,root=None):
        root=self.hierarchy() if root is None else root
        # HOME has the public dock, including a configurable All Apps entry.
        # Fresh defaults can instead show Contacts and hide that entry.
        owned=[n for n in root.iter('node') if n.get('package')==self.package]
        clock=any(re.fullmatch(r'\d{1,2}:\d{2}(?::\d{2})?',n.get('text','')) for n in owned)
        dock=any(n.get('content-desc')=='Apps' for n in owned) or (
            any(n.get('text')=='Phone' for n in owned) and
            any(n.get('text') in ('SMS','Messaging') for n in owned))
        return self.find('Finish',root) is None and clock and dock
    def home_menu(self):
        root=self.hierarchy();require(self.is_home(root),'Public HOME/dock not observed')
        owned=next((n for n in root.iter('node') if n.get('package')==self.package and n.get('bounds')),None)
        require(owned is not None,'HOME ownership missing')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',owned.get('bounds','')))
        # The top HOME clock/background region owns the existing long-press
        # menu. Coordinates derive from current public bounds, not screenshots.
        x=x1+(x2-x1)//10;y=y1+(y2-y1)//3
        self.shell('input','swipe',str(x),str(y),str(x),str(y),'800')
        self.wait('Public HOME menu did not appear',lambda:self.find('Hidden Apps'))
    def open_grid(self):
        root=self.hierarchy()
        if self.find('All Apps',root) is not None:self.click('All Apps')
        elif self.find('Apps',root) is not None:self.click('Apps')
        else:
            self.home_menu();self.click('Apps')
        self.wait('Grid not observed',lambda:not self.is_home() and any(n.get('package')==self.package for n in self.hierarchy().iter('node')))
    def grid_control(self,label):
        for _ in range(8):
            root=self.hierarchy()
            if self.find(label,root) is not None:return
            owned=next((n for n in root.iter('node') if n.get('package')==self.package),None)
            require(owned is not None,'Grid ownership lost')
            x1,y1,x2,y2=map(int,re.findall(r'\d+',owned.get('bounds','')))
            self.shell('input','swipe',str((x1+x2)//2),str(y1+(y2-y1)*3//4),str((x1+x2)//2),str(y1+(y2-y1)//4),'300')
        raise ProbeError('Grid control not found through public pagination: '+label)
    def capture(self,name):
        require(not (self.output.parent/(name+'.xml')).exists() and not (self.output.parent/(name+'.png')).exists(),'Preserve existing capture: '+name)
        xml=ET.tostring(self.hierarchy(),encoding='utf-8')
        (self.output.parent/(name+'.xml')).write_bytes(xml)
        (self.output.parent/(name+'.png')).write_bytes(self.device('exec-out','screencap','-p',binary=True))
    def start(self):
        result=self.shell('am','start','-W','-n',self.package+'/'+COMPONENT)
        require('Status: ok' in result,'Explicit owned activity launch failed')
        self.wait('Owned UI did not render',lambda:any(n.get('package')==self.package for n in self.hierarchy().iter('node')))
    def package_state(self,package):
        dump=self.shell('dumpsys','package',package)
        require('Package ['+package+']' in dump,'Inherited/target package is missing: '+package)
        path=self.shell('pm','path',package).removeprefix('package:')
        require(path.startswith('/data/app/') and '\n' not in path,'Unexpected installed APK path')
        inode=re.search(r'ceDataInode=(\d+)',dump);uid=re.search(r'\buserId=(\d+)',dump)
        require(inode and uid,'Missing package-data identity')
        return {'apkSha256':self.shell('sha256sum',path).split()[0],'dataInode':inode[1],'uid':uid[1]}
    def home_and_grants(self):
        home=self.shell('cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME').splitlines()[-1]
        listeners=self.shell('settings','get','secure','enabled_notification_listeners')
        dump=self.shell('dumpsys','package',self.package)
        grants=[s.strip() for s in dump.splitlines() if 'granted=' in s]
        return {'home':home,'notificationListeners':listeners,'grants':grants}


def owned_preflight(adb,serial):
    require(os.getuid()==1000 and Path('/etc/machine-id').read_text().strip()=='9f129506519d4720b7b057818df39d54','Wrong Linux guest/account')
    require(os.environ.get('ADB_SERVER_SOCKET')=='tcp:localhost:5037' and os.environ.get('ANDROID_ADB_SERVER_PORT')=='5037' and os.environ.get('ADB_MDNS_AUTO_CONNECT')=='0','Unpinned ADB environment')
    require(serial=='emulator-5554' and adb==Path('/srv/kotatsu/android-sdk/platform-tools/adb'),'Wrong owned endpoint/tool')
    rows=[line.split() for line in run(adb,'devices').splitlines()[1:] if line.strip()]
    require(rows==[[serial,'device']],'Unexpected attached devices')
    require(run(adb,'-s',serial,'shell','-n','getprop','ro.build.version.sdk').strip()=='31','Wrong runtime SDK')
    pids=[];qemu=[]
    for p in Path('/proc').iterdir():
        if not p.name.isdecimal():continue
        try:exe=(p/'exe').readlink();command=(p/'cmdline').read_bytes().split(b'\0')
        except (FileNotFoundError,PermissionError):continue
        if exe==adb:
            require(p.stat().st_uid==1000,'ADB not owned');pids.append(int(p.name))
        if str(exe).startswith('/srv/kotatsu/android-sdk/emulator/qemu/'):
            require(p.stat().st_uid==1000 and b'kompakt_api31' in command and b'5554' in command,'Foreign emulator');qemu.append(int(p.name))
            handles=[]
            for fd in (p/'fd').iterdir():
                try:handles.append(str(fd.readlink()))
                except FileNotFoundError:continue
            require('/dev/kvm' in handles and any('kvm-vm' in h for h in handles),'Emulator not using actual KVM')
    require(len(pids)==len(qemu)==1,'Ambiguous Android processes')
    for line in run('ss','-ltnp').splitlines()[1:]:
        if any(f'pid={pid},' in line for pid in pids+qemu):
            host=line.split()[3].rsplit(':',1)[0].strip('[]');address=ipaddress.ip_address(host)
            require(address.is_loopback or (address.version==6 and address.ipv4_mapped and address.ipv4_mapped.is_loopback),'Non-loopback Android listener')
    return qemu[0]


def phase_a(args):
    provenance=source_receipt.verify(Path(args.root),args.baseline,args.expected_source_head,args.expected_source_tree,args.source_receipt,args.expected_source_receipt_sha256)
    report,apk=validate_report(args.apk_report,args.variant,args.expected_application_id,args.expected_cert_sha256,args.apksigner,provenance,args.expected_apk_sha256)
    output=Path(args.output);require(not output.exists() and output.parent.is_dir(),'New report path required')
    adb=Path(os.environ.get('ANDROID_HOME',''))/'platform-tools/adb';owned_preflight(adb,args.adb_serial)
    ui=PublicUi(adb,args.adb_serial,args.expected_application_id,output)
    legacy={package:ui.package_state(package) for package in LEGACY}
    installed_packages=ui.shell('pm','list','packages',args.expected_application_id).splitlines()
    if 'package:'+args.expected_application_id in installed_packages:
        existing=ui.shell('pm','path',args.expected_application_id)
        path=existing.removeprefix('package:');require(path.startswith('/data/app/') and '\n' not in path,'Unexpected existing release path')
        installed=output.parent/'existing-target.apk';require(not installed.exists(),'Preserve existing signer receipt')
        ui.device('pull',path,installed)
        signature=run(args.apksigner,'verify','--print-certs',installed)
        require(re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',signature,re.M)==[args.expected_cert_sha256.lower()],'Existing installed signer incompatible; preserve data')
    ui.device('install','-r',apk)
    require(ui.package_state(args.expected_application_id)['apkSha256']==report['sha256'],'Installed target APK differs from verified artifact')
    before=ui.home_and_grants();require(before['home']=='com.android.launcher3/.uioverrides.QuickstepLauncher' and before['notificationListeners']=='null','No-grant/nondefault precondition changed')
    ui.start();cases={};first=ui.hierarchy()
    if ui.find('Finish',first) is not None:
        ui.capture('phase-a-initial-onboarding');ui.click('Finish');ui.wait('Finish did not reach HOME',ui.is_home);cases['noGrantFinish']={'status':'passed','freshOnboardingObserved':True}
    else:
        ui.wait('HOME missing',ui.is_home);cases['noGrantFinish']={'status':'previously-completed; fresh result requires connected-test evidence','freshOnboardingObserved':False}
    ui.capture('phase-a-home')
    ui.open_grid();ui.grid_control('Settings')
    ui.capture('phase-a-grid')
    ui.click('Settings');ui.wait('AOSP Settings did not launch',lambda:any(n.get('package')=='com.android.settings' for n in ui.hierarchy().iter('node')))
    ui.device('shell','-n','input','keyevent','KEYCODE_BACK');ui.wait('Returning from AOSP Settings lost Kotatsu',lambda:any(n.get('package')==args.expected_application_id for n in ui.hierarchy().iter('node')))
    cases['gridAppLaunchReturn']={'status':'passed','consumer':'com.android.settings'}
    # Resolve internal grid Back, then observe root exit before any relaunch.
    ui.device('shell','-n','input','keyevent','KEYCODE_BACK');ui.wait('Internal Back did not render HOME',ui.is_home)
    ui.device('shell','-n','input','keyevent','KEYCODE_BACK')
    ui.wait('Nondefault root Back was swallowed',lambda:not any(n.get('package')==args.expected_application_id for n in ui.hierarchy().iter('node')))
    ui.capture('phase-a-root-exit');cases['nondefaultRootBack']={'status':'passed'}
    ui.start();ui.open_grid()
    labels=[n.get('text') for n in ui.hierarchy().iter('node') if n.get('package')==args.expected_application_id and n.get('text')]
    state=ui.package_state(args.expected_application_id)
    ui.device('shell','-n','am','force-stop',args.expected_application_id)
    ui.start();ui.open_grid()
    after_labels=[n.get('text') for n in ui.hierarchy().iter('node') if n.get('package')==args.expected_application_id and n.get('text')]
    require(after_labels==labels,'Confirmed rendered order differs after actual process restart')
    require(ui.package_state(args.expected_application_id)==state,'Cold restart changed APK/data identity')
    ui.capture('phase-a-cold-restart-grid');cases['realProcessRestartOrder']={'status':'passed','visibleLabels':labels}
    # Same writable AVD, clean emulator stop/start, no wipe or recreation.
    emulator_pid=owned_preflight(adb,args.adb_serial)
    config=Path('/srv/kotatsu/avd/kompakt_api31.avd/config.ini');config_hash=hashlib.sha256(config.read_bytes()).hexdigest()
    ui.device('emu','kill');end=time.monotonic()+120
    while (Path('/proc')/str(emulator_pid)/'exe').exists() and time.monotonic()<end:time.sleep(.2)
    require(not (Path('/proc')/str(emulator_pid)/'exe').exists(),'Owned emulator did not exit; no forced kill')
    listeners=run('ss','-ltnp')
    require(all(line.split()[3].rsplit(':',1)[-1] not in ('5554','5555') for line in listeners.splitlines()[1:]),'Control port collision after clean stop')
    emulator=Path('/srv/kotatsu/android-sdk/emulator/emulator')
    log_path=output.parent/'phase-a-same-avd-emulator.log';require(not log_path.exists(),'Preserve existing restart log')
    with log_path.open('xb') as log:
        process=subprocess.Popen([str(emulator),'-avd','kompakt_api31','-port','5554','-accel','on','-cores','2','-memory','2048','-skin','480x800','-gpu','swangle','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-no-metrics'],stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    end=time.monotonic()+300
    while time.monotonic()<end:
        require(process.poll() is None,'Restarted owned emulator exited')
        poll=subprocess.run([str(adb),'-s',args.adb_serial,'shell','-n','getprop','sys.boot_completed'],stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=20)
        if poll.returncode==0 and poll.stdout.strip()=='1':break
        time.sleep(1)
    else:raise ProbeError('Same AVD boot timed out; retain state/log')
    owned_preflight(adb,args.adb_serial)
    require(hashlib.sha256(config.read_bytes()).hexdigest()==config_hash,'AVD config changed')
    ui.start();ui.open_grid()
    reboot_labels=[n.get('text') for n in ui.hierarchy().iter('node') if n.get('package')==args.expected_application_id and n.get('text')]
    require(reboot_labels==labels and ui.package_state(args.expected_application_id)==state,'Same AVD restart lost confirmed order/package/data')
    ui.capture('phase-a-same-avd-grid');cases['sameAvdRestart']={'status':'passed','visibleLabels':reboot_labels,'avdConfigSha256':config_hash}
    require(ui.home_and_grants()==before,'HOME/grants changed')
    source_receipt.verify(Path(args.root),args.baseline,args.expected_source_head,args.expected_source_tree,args.source_receipt,args.expected_source_receipt_sha256)
    require({package:ui.package_state(package) for package in LEGACY}==legacy,'Inherited Katapult/test APK/data changed')
    cases['packageCoexistence']={'status':'passed','legacy':legacy}
    cases['defaultHomeBranch']={'status':'blocked','reason':'Separate user HOME-choice approval required'}
    result={'schemaVersion':2,'variant':args.variant,'applicationId':args.expected_application_id,'signerSha256':args.expected_cert_sha256,'apkSha256':report['sha256'],'sourceProvenance':provenance,'artworkStatus':report['artworkStatus'],'approvedBundleIndexSha256':report['checks']['approvedBundleIndexSha256'],'packagedBranding':report['checks']['packagedBranding'],'cases':cases,'status':'partial; required permission gates remain explicit'}
    with output.open('x') as handle:json.dump(result,handle,indent=2,sort_keys=True);handle.write('\n')
    print('OBSERVED public preview/app launch/root exit/process restart/coexistence; default-HOME blocked')
    print(output)


def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='mode',required=True);phase=sub.add_parser('phase-a')
    for flag in ('adb-serial','variant','expected-application-id','expected-cert-sha256','apk-report','apksigner','output'):phase.add_argument('--'+flag,required=True)
    for flag in ('root','baseline','expected-source-head','expected-source-tree','source-receipt','expected-source-receipt-sha256'):phase.add_argument('--'+flag,required=True)
    phase.add_argument('--expected-apk-sha256',required=True)
    args=parser.parse_args();phase_a(args)


if __name__=='__main__':
    try:main()
    except (ProbeError,OSError,ValueError,ET.ParseError) as exc:
        print('probe_launcher: '+str(exc),file=sys.stderr);sys.exit(1)
