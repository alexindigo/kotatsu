#!/usr/bin/env python3
"""Guest-only regression: run the whole sync suite with unrelated provider data.

From the checkout in a prepared, disposable CI-style emulator inside the Kotatsu VM:
    python3 kotatsu-sync/verify-test-isolation.py <disk-backed-results-directory>

APKs must already be built. The real all-phone entry points deliberately fail
their fresh-emulator preconditions and are discovered by @RequiresIsolatedPhoneBook;
every other test must pass. Run the ordinary
suite on a fresh emulator too, to exercise those entry points. Seeds remain on
the disposable device for inspection, never deleted to make a test pass.
"""

import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

FIXTURE = "com.alexindigo.kotatsu.fakemuditacalendar.test"
SYNC_TEST = "com.alexindigo.kotatsu.sync.debug.test"
ISOLATION_ANNOTATION = "com.alexindigo.kotatsu.sync.RequiresIsolatedPhoneBook"


def run_whole_suite(root, results):
    environment = {key: value for key, value in os.environ.items() if key not in ("CLASS", "SHARD")}
    return subprocess.run(["bash", str(root / "tools/ci/run-connected.sh"), "sync", str(results / "suite")],
                          cwd=root, timeout=1200, env=environment)


def test_identity(case):
    return case.attrib["classname"] + "." + case.attrib["name"]


def discover_guarded_tests(shell, root, results):
    runners = [line.split()[0].removeprefix("instrumentation:") for line in shell("pm", "list", "instrumentation").splitlines()
               if line.startswith(f"instrumentation:{SYNC_TEST}/")]
    if len(runners) != 1:
        raise RuntimeError(f"Expected one Sync test runner, got {runners}")
    output = shell("am", "instrument", "-r", "-w", "-e", "log", "true", "-e", "annotation", ISOLATION_ANNOTATION, runners[0])
    raw, xml = results / "guarded.txt", results / "guarded.xml"
    raw.write_text(output + "\n")
    subprocess.run([sys.executable, str(root / "tools/ci/junit.py"), "convert", str(raw), str(xml), "--suite", "guarded"],
                   check=True, timeout=30)
    guarded = {test_identity(case) for case in ET.parse(xml).getroot().findall("testcase")}
    if not guarded:
        raise AssertionError("No @RequiresIsolatedPhoneBook tests discovered")
    return guarded


def main():
    results = Path(sys.argv[1]).resolve()
    results.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parent.parent
    adb = os.environ.get("ADB", "adb")
    name = "kotatsu-external-test-preserved-" + uuid.uuid4().hex
    contacts = "content://com.android.contacts/"
    calendars = "content://com.android.calendar/"

    def shell(*args):
        result = subprocess.run([adb, "shell", shlex.join(args)], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        if result.returncode or "Exception" in result.stderr or "Error" in result.stderr:
            raise RuntimeError(f"{args[0]} exited {result.returncode}:\n{result.stdout}{result.stderr}")
        return result.stdout.strip()

    def ids(table):
        output = shell("content", "query", "--uri", contacts + table, "--projection", "_id", "--sort", "_id")
        if output == "No result found.":
            return set()
        found = set(map(int, re.findall(r"_id=(\d+)", output)))
        if not found:
            raise RuntimeError(f"Cannot read {table} IDs: {output}")
        return found

    if os.environ.get("ANDROID_SERIAL") != "emulator-5554" or shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("This regression seeds only the disposable emulator-5554, never a phone")

    def insert(table, **values):
        before = ids(table)
        args = ["content", "insert", "--uri", contacts + table]
        for key, value in values.items():
            kind = "n" if value is None else "l" if isinstance(value, int) else "s"
            args += ["--bind", f"{key}:{kind}:{'' if value is None else value}"]
        shell(*args)
        created = ids(table) - before
        if len(created) != 1:
            raise RuntimeError(f"Expected one inserted {table} row, got {created}")
        return created.pop()

    def query(uri, where):
        order = [] if uri.endswith("/settings") else ["--sort", "_id"]
        output = shell("content", "query", "--uri", uri, "--where", where, *order)
        if not output.startswith("Row: "):
            raise AssertionError(f"Seeded rows unavailable at {uri}: {output}")
        return output

    subprocess.run(["bash", str(root / "tools/ci/prepare-emulator.sh"), "install", "sync",
                    str(results / "seed-prep")], check=True, cwd=root, timeout=300)
    for permission in ("READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR"):
        shell("pm", "grant", FIXTURE, "android.permission." + permission)
    paths = shell("pm", "path", FIXTURE).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        raise RuntimeError(f"Expected one fixture APK, got {paths}")
    account_type = shell("su", "0", "timeout", "20", "env", "CLASSPATH=" + paths[0].removeprefix("package:"),
                         "app_process", "/system/bin", "com.alexindigo.kotatsu.fakesyncadapter.ExternalSyncFixture", "seed", name)
    if not re.fullmatch(r"[A-Za-z0-9._-]+", account_type):
        raise RuntimeError(f"External fixture didn't return its account type: {account_type}")
    phone = insert("raw_contacts", account_name=None, account_type=None)
    book = insert("raw_contacts", account_name=name, account_type=account_type)
    for raw, label in ((phone, "PreservedPhone"), (book, "PreservedBook")):
        insert("data", raw_contact_id=raw, mimetype="vnd.android.cursor.item/name", data1=label + name)
        insert("data", raw_contact_id=raw, mimetype="vnd.android.cursor.item/phone_v2",
               data1="2025550999" if raw == phone else "2025550998", data2=2)

    # Existing book contacts deliberately match the historical fixed fixture identities.
    preserved = [phone, book]
    for label, number in (("Joined", "2025550107"), (None, "2025550106"),
                          ("All fields", "+12025550101"), ("Two distinct photos", None),
                          ("Imported SIM contact", "2025550102"), ("Phone contact", "2025550103"),
                          ("Another", "2025550104"), ("Already personal", "2025550105")):
        raw = insert("raw_contacts", account_name=name, account_type=account_type)
        preserved.append(raw)
        if label is not None:
            insert("data", raw_contact_id=raw, mimetype="vnd.android.cursor.item/name", data1=label)
        if number is not None:
            insert("data", raw_contact_id=raw, mimetype="vnd.android.cursor.item/phone_v2", data1=number, data2=2)
        if label == "All fields":
            insert("data", raw_contact_id=raw, mimetype="vnd.android.cursor.item/email_v2", data1="example@example.invalid", data2=1)

    def snapshot():
        account = f"Account {{name={name}, type={account_type}}}"
        if account not in shell("dumpsys", "account"):
            raise AssertionError("Seeded address-book account disappeared")
        selection = f"account_name = '{name}' AND account_type = '{account_type}'"
        ids = ",".join(map(str, preserved))
        return {"account": account,
                "raw": query(contacts + "raw_contacts", f"_id IN ({ids})"),
                "data": query(contacts + "data", f"raw_contact_id IN ({ids})"),
                "book": query(contacts + "settings", selection),
                "calendars": query(calendars + "calendars", selection),
                "events": query(calendars + "events", selection)}

    def record(label):
        content = json.dumps(snapshot(), sort_keys=True, indent=2) + "\n"
        (results / (label + ".json")).write_text(content)
        return hashlib.sha256(content.encode()).hexdigest()

    before = record("before")
    run = run_whole_suite(root, results)
    after = record("after")
    receipt = {"before": before, "after": after, "suite_returncode": run.returncode}
    (results / "hashes.json").write_text(json.dumps(receipt, indent=2) + "\n")
    if before != after:
        raise AssertionError(f"Unrelated provider data changed: {before} != {after}")

    guarded = discover_guarded_tests(shell, root, results)
    suite = ET.parse(results / "suite/TEST-sync.xml").getroot()
    rejected = set()
    passed = 0
    for case in suite.findall("testcase"):
        identity = test_identity(case)
        failure = case.find("failure")
        if failure is not None:
            if identity not in guarded or not failure.attrib.get("message", "").startswith(
                    "java.lang.AssertionError: Fresh-emulator precondition:"):
                raise AssertionError(f"Unexpected failure: {identity}: {failure.attrib}")
            rejected.add(identity)
        elif case.find("error") is not None or case.find("skipped") is not None:
            raise AssertionError(f"Unexpected error/skip: {identity}")
        else:
            passed += 1
    if rejected != guarded or run.returncode != 1 or not passed:
        raise AssertionError(f"Expected explicit guard failures, got {rejected}; rc={run.returncode}")
    print(f"Seeded suite: {passed} passed; {len(rejected)} explicit fresh-emulator precondition failures")
    print(f"Unrelated contact/address-book SHA256 before={before} after={after}")


if __name__ == "__main__":
    main()
