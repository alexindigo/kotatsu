#!/usr/bin/env python3
"""Compile the user-approved, pinned SVG selection into an offline icon catalogue.

Workloads run in the project VM. The compiler never modifies the review store,
guesses package names, regenerates artwork, or drops equal named glyphs.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import urllib.request
import xml.etree.ElementTree as ET

SELECTION_SHA256 = "45e75d97c1bb200a57a25b0ce0e279c526771b007f540b4a48e3c6a07d021d5a"
CATALOGUE_SHA256 = "7b621cb39230d1da70084e2338663841b3021a8f99e0df3717d232fa4453176e"
DRAFT_SHA256 = "079851b2d2c0c8b6eff4486109a06fc5ce4d5de1fb4598432c6d26650302815b"
EXPECTED_COUNTS = {"simple-icons": 843, "tabler-icons": 1034, "material-icons": 297}
MAPPING_PATH = "tools/icon-mappings/simple-icons-app-mapping.json"
ANDROIDSVG_AAR_SHA256 = "02a5b08a2b35d2d58eb2eaca9d84ac00fb341da725fdbd653ea3ed130437e95a"
ANDROIDSVG_LICENSE_SHA256 = "51b28821f223a6a07773a27780bb45a79a50bd147755303f8a20718f81f38b4f"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path, expected=None):
    data = path.read_bytes()
    if expected is not None:
        assert digest(data) == expected, f"Pinned input mismatch: {path}"
    return json.loads(data)


def dump(path, value):
    data = (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()
    if path.exists():
        assert path.read_bytes() == data, f"Preserve differing previous output: {path}"
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as handle:
            handle.write(data)


def compile_mappings(selected, root):
    mapping = read_json(root / MAPPING_PATH)
    assert mapping["schema"] == 1 and not mapping["conflicts"]
    packages = mapping["packages"]
    included = {icon["id"] for icon in selected if icon["collection"] == "simple-icons"}
    assert set(packages.values()) <= included
    assert len(packages) == mapping["counts"]["appIds"] == 640
    assert len(set(packages.values())) == mapping["counts"]["mappedLogos"] == 566
    evidence = {key: value for key, value in mapping.items() if key != "packages"}
    return packages, {}, evidence


def compile_catalogue(root, inputs, cache, fetch_missing=False):
    selection = read_json(inputs / "selection.json", SELECTION_SHA256)
    catalogue = read_json(inputs / "catalogue.json", CATALOGUE_SHA256)
    draft = read_json(inputs / "draft.json", DRAFT_SHA256)
    snapshot = read_json(inputs / "snapshot.json")
    assert selection["format"] == "kotatsu-icon-selection" and selection["schema"] == 1
    assert selection["revision"] == 3730 and selection["review"] == "main"
    assert selection["catalogueSha256"] == snapshot["catalogueSha256"] == CATALOGUE_SHA256
    assert selection["draftSha256"] == snapshot["draftSha256"] == DRAFT_SHA256
    assert selection["pins"] == snapshot["pins"] == catalogue["pins"] == draft["pins"]
    by_id = {i["id"]: i for i in catalogue["icons"]}
    included, excluded = set(selection["included"]), set(selection["excluded"])
    assert len(by_id) == 10780 and not included.intersection(excluded) and included | excluded == set(by_id)
    assert len(included) == len(selection["included"]) and len(excluded) == len(selection["excluded"])
    for identifier, icon in by_id.items():
        effective = selection["overrides"].get(identifier, draft["decisions"][identifier]["excluded"])
        assert type(effective) is bool and effective == (identifier in excluded)
    selected = [i for i in catalogue["icons"] if i["id"] in included]
    assert dict(Counter(i["collection"] for i in selected)) == EXPECTED_COUNTS
    output = root / "app/src/main/assets/icons"
    output.mkdir(parents=True, exist_ok=True)
    (output / "svg").mkdir(exist_ok=True)
    reference = inputs.parent / "reference"
    entries, sources, elements, payloads = [], [], Counter(), {}
    for icon in selected:
        blob = icon["blobSha"]
        assert re.fullmatch(r"[0-9a-f]{40}", blob)
        path = cache / (blob + ".svg")
        if not path.exists():
            assert fetch_missing, f"Selected SVG absent from preserved cache: {icon['id']}"
            assert icon["rawUrl"].startswith("https://raw.githubusercontent.com/")
            with urllib.request.urlopen(icon["rawUrl"], timeout=60) as response:
                data = response.read(2_000_001)
            assert len(data) <= 2_000_000
            with path.open("xb") as handle:
                handle.write(data)
        assert not path.is_symlink()
        data = path.read_bytes()
        assert len(data) == icon["byteSize"]
        assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == blob, icon["id"]
        xml = ET.fromstring(data)
        assert xml.tag.rsplit("}", 1)[-1] == "svg"
        for node in xml.iter():
            tag = node.tag.rsplit("}", 1)[-1]
            assert tag not in {"script", "foreignObject", "image", "text", "filter"}, f"Unexpected external/unsupported element: {icon['id']}/{tag}"
            for key, value in node.attrib.items():
                assert not key.lower().startswith("on")
                if key.rsplit("}", 1)[-1] in {"href", "src"}:
                    assert value.startswith("#"), f"External SVG dependency: {icon['id']}"
            elements[tag] += 1
        asset = "svg/" + blob + ".svg"
        destination = output / asset
        if destination.exists():
            assert destination.read_bytes() == data
        else:
            destination.write_bytes(data)
        label = icon.get("currentMaterial", {}).get("label") or icon["title"]
        terms = [icon["id"], label, icon["name"], *icon["aliases"], *icon["tags"], icon["category"]]
        entries.append({"id": icon["id"], "collection": icon["collection"], "name": icon["name"],
                        "label": label, "category": icon["category"], "aliases": icon["aliases"],
                        "search": " ".join(map(str, terms)), "asset": asset, "sha256": digest(data)})
        sources.append(icon)
        payloads[asset] = digest(data)
    packages, components, mapping_proof = compile_mappings(selected, root)
    data = {"format": "kotatsu-icon-catalogue", "schema": 1, "selectionRevision": selection["revision"],
            "selectionSha256": SELECTION_SHA256, "counts": EXPECTED_COUNTS, "entries": entries,
            "packages": packages, "components": components}
    dump(output / "catalogue.json", data)
    dump(output / "provenance/selected-sources.json", {"pins": selection["pins"], "icons": sources})
    dump(output / "provenance/mappings.json", mapping_proof)
    dump(output / "provenance/manifest.json", {"schema": 1, "selectionRevision": 3730,
          "selectionSha256": SELECTION_SHA256, "sourceCatalogueSha256": CATALOGUE_SHA256,
          "sourceDraftSha256": DRAFT_SHA256, "svgPayloads": payloads,
          "catalogueSha256": digest((output / "catalogue.json").read_bytes()),
          "androidsvgVersion": "1.4", "androidsvgAarSha256": ANDROIDSVG_AAR_SHA256,
          "mappingSourcePath": MAPPING_PATH, "mappingSourceSha256": digest((root / MAPPING_PATH).read_bytes())})
    notice_dir = output / "notices"
    notice_dir.mkdir(exist_ok=True)
    for name in snapshot["notices"]:
        source = inputs / name
        expected = next(i["sha256"] for i in snapshot["inputs"] if i["file"] == name)
        assert digest(source.read_bytes()) == expected
        shutil.copyfile(source, notice_dir / source.name)
    androidsvg_license = reference / "androidsvg-LICENSE"
    assert digest(androidsvg_license.read_bytes()) == ANDROIDSVG_LICENSE_SHA256
    shutil.copyfile(androidsvg_license, notice_dir / "androidsvg-LICENSE")
    dump(output / "notices/attribution.json", {
        "selectionRevision": 3730,
        "sources": ["Simple Icons 16.34.0 — original included SVGs; see per-logo metadata/disclaimer",
                    "Tabler 3.49.0 — original outline SVGs; MIT",
                    "Material Design Icons — original classic Outlined SVGs; Apache-2.0",
                    "AndroidSVG 1.4 by Paul LeBeau/Cave Rock Software Ltd.; Apache-2.0"]})
    result = {"namedEntries": len(entries), "counts": EXPECTED_COUNTS, "uniqueSvgPayloads": len(payloads),
              "packageMappings": len(packages), "componentMappings": len(components),
              "sourceElements": dict(elements), "catalogueSha256": digest((output / "catalogue.json").read_bytes())}
    print(json.dumps(result, indent=2, sort_keys=True))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--fetch-missing", action="store_true")
    args = parser.parse_args()
    compile_catalogue(args.root, args.inputs, args.cache, args.fetch_missing)
