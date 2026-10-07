"""A current independently pinned build receipt is required, not stale mapping bytes."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('verify_fork', Path(__file__).parents[1] / 'verify_fork.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class BuildReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.provenance = {'head': 'a'*40, 'headTree': 'b'*40, 'sourceReceiptSha256': 'c'*64}
        self.tasks = []
        for number, task in enumerate((':app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease')):
            log = self.root / ('task-' + str(number) + '.log')
            text = '> Task ' + task + '\nBUILD SUCCESSFUL\n'
            if task.endswith('lintRelease'):
                text += '> Task :app:lintAnalyzeRelease\n> Task :app:lintReportRelease\n'
            if task.endswith('assembleRelease'):
                text += '> Task :app:minifyReleaseWithR8\n> Task :app:optimizeReleaseResources\n> Task :app:packageRelease\nR8 resource shrinking enabled\n'
            log.write_text(text)
            self.tasks.append({'task': task, 'exitCode': 0, 'log': str(log), 'logSha256': verify.sha(log.read_bytes())})
        self.files = {}
        for name in ('app/build/outputs/apk/release/app.apk', 'app/build/outputs/apk/release/output-metadata.json',
                     'app/build/outputs/mapping/release/mapping.txt', 'app/build/outputs/mapping/release/resources.txt'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fresh fixture')
            self.files[name] = verify.sha(path.read_bytes())
        self.apk = self.root / 'app/build/outputs/apk/release/app.apk'
        self.receipt = self.root / 'receipt.json'
        self.value = {'sourceBefore': self.provenance, 'sourceAfter': self.provenance,
                      'deferredIndexSha256': 'd'*64, 'tasks': self.tasks, 'artifacts': self.files}
        self.args = argparse.Namespace(build_receipt=str(self.receipt), expected_build_receipt_sha256='')
        self.pin()

    def pin(self):
        self.receipt.write_text(json.dumps(self.value))
        self.args.expected_build_receipt_sha256 = verify.sha(self.receipt.read_bytes())

    def check(self):
        return verify.build_identity(self.args, self.root, self.apk, self.provenance, 'd'*64)

    def test_current_tasks_inputs_and_artifacts(self):
        self.assertEqual(self.check()['mappingSha256'], self.files['app/build/outputs/mapping/release/mapping.txt'])

    def test_no_action_prerequisites_may_be_up_to_date_but_required_work_may_not(self):
        log = Path(self.tasks[0]['log'])
        log.write_text('> Task :app:preBuild UP-TO-DATE\n' + log.read_text())
        self.tasks[0]['logSha256'] = verify.sha(log.read_bytes())
        self.pin()
        self.check()
        log.write_text(log.read_text().replace('> Task :app:testReleaseUnitTest\n', '> Task :app:testReleaseUnitTest UP-TO-DATE\n'))
        self.tasks[0]['logSha256'] = verify.sha(log.read_bytes())
        self.pin()
        with self.assertRaisesRegex(verify.VerificationError, 'not freshly'):
            self.check()

    def test_source_replay_or_after_mutation_rejected(self):
        self.value['sourceAfter'] = {**self.provenance, 'sourceReceiptSha256': '0'*64}
        self.pin()
        with self.assertRaisesRegex(verify.VerificationError, 'source receipt'):
            self.check()

    def test_changed_mapping_or_receipt_rejected(self):
        (self.root / 'app/build/outputs/mapping/release/mapping.txt').write_text('stale or replaced')
        with self.assertRaisesRegex(verify.VerificationError, 'artifact'):
            self.check()
        self.receipt.write_text('{}')
        with self.assertRaisesRegex(verify.VerificationError, 'digest'):
            self.check()

    def test_failed_missing_or_cached_task_rejected(self):
        for mutation in ('failed', 'missing', 'cached'):
            with self.subTest(mutation=mutation):
                saved = list(self.value['tasks'])
                if mutation == 'failed':
                    self.value['tasks'] = [{**t, 'exitCode': 1} for t in saved]
                elif mutation == 'missing':
                    self.value['tasks'] = saved[:-1]
                else:
                    log = Path(saved[0]['log'])
                    log.write_text(log.read_text().replace('> Task :app:testReleaseUnitTest\n', '> Task :app:testReleaseUnitTest UP-TO-DATE\n'))
                    self.value['tasks'] = [{**saved[0], 'logSha256': verify.sha(log.read_bytes())}, *saved[1:]]
                self.pin()
                with self.assertRaises(verify.VerificationError):
                    self.check()
                self.value['tasks'] = saved
