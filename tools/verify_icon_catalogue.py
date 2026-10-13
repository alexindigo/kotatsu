#!/usr/bin/env python3
"""Verify the curated catalogue and optional actual APK payloads without mutation."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import zipfile

from compile_icon_catalogue import EXPECTED_COUNTS, SELECTION_SHA256, ANDROIDSVG_AAR_SHA256, ANDROIDSVG_LICENSE_SHA256, MAPPING_PATH

COMPILED_CATALOGUE_SHA256 = "87ec3a37ef6675302a9d096f42d103182e81c6baa2eb8c5bd74e9dc58b22bf60"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify(root, apk=None):
    assets = root / "app/src/main/assets/icons"
    catalogue_bytes = (assets / "catalogue.json").read_bytes()
    assert digest(catalogue_bytes) == COMPILED_CATALOGUE_SHA256
    document = json.loads(catalogue_bytes)
    manifest = json.loads((assets / "provenance/manifest.json").read_bytes())
    source = json.loads((assets / "provenance/selected-sources.json").read_bytes())
    proof = json.loads((assets / "provenance/mappings.json").read_bytes())
    assert document["selectionRevision"] == manifest["selectionRevision"] == 3730
    assert document["selectionSha256"] == manifest["selectionSha256"] == SELECTION_SHA256
    assert manifest["androidsvgAarSha256"] == ANDROIDSVG_AAR_SHA256
    entries = document["entries"]
    by_id = {row["id"]: row for row in entries}
    originals = {row["id"]: row for row in source["icons"]}
    assert len(by_id) == len(entries) == 2174 and set(by_id) == set(originals)
    assert dict(Counter(row["collection"] for row in entries)) == EXPECTED_COUNTS == document["counts"]
    assert "simple-icons:archlinux" not in by_id
    assert all("tabler-icons:outline:zodiac-" + name in by_id for name in
               "aquarius aries cancer capricorn gemini leo libra pisces sagittarius scorpio taurus virgo".split())
    payloads = {}
    for row in entries:
        assert re.fullmatch(r"svg/[0-9a-f]{40}\.svg", row["asset"])
        svg = (assets / row["asset"]).read_bytes()
        original = originals[row["id"]]
        assert row["asset"] == "svg/" + original["blobSha"] + ".svg"
        assert hashlib.sha1(b"blob " + str(len(svg)).encode() + b"\0" + svg).hexdigest() == original["blobSha"]
        assert len(svg) == original["byteSize"]
        assert digest(svg) == row["sha256"] == manifest["svgPayloads"][row["asset"]]
        payloads[row["asset"]] = row["sha256"]
    assert {"svg/" + p.name for p in (assets / "svg").iterdir()} == set(payloads)
    for identifier in [*document["packages"].values(), *document["components"].values()]:
        assert by_id[identifier]["collection"] == "simple-icons"
    mapping_bytes = (root / MAPPING_PATH).read_bytes()
    mapping = json.loads(mapping_bytes)
    assert manifest["catalogueSha256"] == digest(catalogue_bytes)
    assert manifest["mappingSourcePath"] == MAPPING_PATH
    assert manifest["mappingSourceSha256"] == digest(mapping_bytes)
    assert document["packages"] == mapping["packages"]
    assert document["components"] == {}
    assert len(document["packages"]) == mapping["counts"]["appIds"] == 640
    assert len(set(document["packages"].values())) == mapping["counts"]["mappedLogos"] == 566
    assert proof == {key: value for key, value in mapping.items() if key != "packages"}
    assert not proof["conflicts"]
    assert {row["appId"]: identifier for identifier, rows in proof["mappings"].items()
            for row in rows} == document["packages"]
    assert document["packages"]["org.thoughtcrime.securesms"] == "simple-icons:signal"
    assert "com.tplink.tether" not in document["packages"]
    assert "ca.bnc.android" not in document["packages"]
    assert "com.android.settings" not in document["packages"]
    assert all((assets / "notices" / name).is_file() for name in
                ("simple-license.md", "simple-disclaimer.md", "tabler-license.txt", "material-license.txt", "attribution.json"))
    assert digest((assets / "notices/androidsvg-LICENSE").read_bytes()) == ANDROIDSVG_LICENSE_SHA256
    packaged = 0
    if apk is not None:
        with zipfile.ZipFile(apk) as archive:
            assert len(archive.namelist()) == len(set(archive.namelist()))
            expected = {"assets/icons/" + str(path.relative_to(assets)): path.read_bytes()
                        for path in assets.rglob("*") if path.is_file()}
            actual = {name for name in archive.namelist() if name.startswith("assets/icons/") and not name.endswith("/")}
            assert actual == set(expected), "Missing/extra packaged icon payload"
            for name, data in expected.items():
                assert archive.read(name) == data, "Packaged payload changed: " + name
            packaged = len(actual)
    result = {"namedEntries": len(entries), "counts": EXPECTED_COUNTS,
              "uniqueSvgPayloads": len(payloads), "packageMappings": len(document["packages"]),
              "componentMappings": len(document["components"]), "packagedFilesVerified": packaged,
              "catalogueSha256": COMPILED_CATALOGUE_SHA256, "selectionRevision": 3730}
    print(json.dumps(result, indent=2, sort_keys=True))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    verify(args.root, args.apk)
