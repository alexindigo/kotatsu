#!/usr/bin/env python3
"""Stage the complete, version-matched release set with documented asset names."""
import argparse
import json
from pathlib import Path
import shutil

from verify_fork import RELEASE_APPS, RELEASE_VERSION, VerificationError, require, source_release_identities, unique_apk


def prepare(root, tag, output):
    root=Path(root).absolute();output=Path(output)
    require(tag=='v'+RELEASE_VERSION,'Release tag/source version mismatch')
    source_release_identities(root)
    inputs=[]
    for module,spec in RELEASE_APPS.items():
        apk=unique_apk(root/module/'build/outputs/apk/release',root,'release',module)
        metadata=json.loads((apk.parent/'output-metadata.json').read_text())['elements'][0]
        require(type(metadata.get('versionCode')) is int and metadata['versionCode']==spec['versionCode'] and metadata.get('versionName')==RELEASE_VERSION,'Release metadata version mismatch: '+module)
        stem='kotatsu' if module=='app' else module
        inputs.append((apk,stem+'-'+tag+'.apk'))
    # Validate the whole set before creating a publication directory.
    require(not output.exists(),'Release asset directory collision')
    output.mkdir()
    for apk,name in inputs:
        shutil.copyfile(apk,output/name)
        print(output/name)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for flag in ('root','tag','output'):parser.add_argument('--'+flag,required=True)
    args=parser.parse_args()
    try:prepare(args.root,args.tag,args.output)
    except (VerificationError,OSError,ValueError) as exc:parser.exit(1,str(exc)+'\n')


if __name__=='__main__':main()
