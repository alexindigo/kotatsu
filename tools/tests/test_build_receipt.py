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

APP_IDS={'app':'com.alexindigo.kotatsu','kotatsu-weather':'com.alexindigo.kotatsu.weather',
         'kotatsu-notes':'com.alexindigo.kotatsu.notes','kotatsu-sync':'com.alexindigo.kotatsu.sync'}
COMPANIONS=('kotatsu-weather','kotatsu-notes','kotatsu-sync')


class BuildReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.provenance = {'head': 'a'*40, 'headTree': 'b'*40, 'sourceReceiptSha256': 'c'*64}
        self.tasks = []
        release_tasks=[':'+module+':'+task for module in APP_IDS
                       for task in ('testReleaseUnitTest','lintRelease','assembleRelease')]
        for number, task in enumerate(release_tasks):
            prefix=task.rsplit(':',1)[0]
            log = self.root / ('task-' + str(number) + '.log')
            text = '> Task ' + task + '\nBUILD SUCCESSFUL\n'
            if task.endswith('lintRelease'):
                text += f'> Task {prefix}:lintAnalyzeRelease\n> Task {prefix}:lintReportRelease\n'
            if task.endswith('assembleRelease'):
                text += f'> Task {prefix}:minifyReleaseWithR8\n> Task {prefix}:optimizeReleaseResources\n> Task {prefix}:packageRelease\nR8 resource shrinking enabled\n'
            log.write_text(text)
            self.tasks.append({'task': task, 'exitCode': 0, 'log': str(log), 'logSha256': verify.sha(log.read_bytes())})
        self.files = {}
        for module,package in APP_IDS.items():
            for suffix in ('apk/release/app.apk','apk/release/output-metadata.json','mapping/release/mapping.txt','mapping/release/resources.txt'):
                name=module+'/build/outputs/'+suffix
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                data=json.dumps({'variantName':'release','applicationId':package,'elements':[{'outputFile':'app.apk'}]}).encode() if suffix.endswith('.json') else b'fresh fixture'
                path.write_bytes(data)
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
        result=self.check()
        self.assertEqual(result['mappingSha256'], self.files['app/build/outputs/mapping/release/mapping.txt'])
        self.assertEqual(result['apps'],list(APP_IDS))
        self.assertEqual(result['tasks'],[t['task'] for t in self.tasks])
        self.assertEqual((len(result['tasks']),len(self.files)),(12,16))

    def test_non_release_apk_cannot_be_a_receipted_release_asset(self):
        self.check()
        name='fake-mudita-calendar/build/outputs/apk/release/fake-mudita-calendar.apk'
        path=self.root/name
        path.parent.mkdir(parents=True)
        path.write_bytes(b'non-release APK')
        self.check()  # Output on disk is allowed but not part of the release receipt.
        self.files[name]=verify.sha(path.read_bytes());self.pin()
        with self.assertRaisesRegex(verify.VerificationError,'Non-release module.*release artifacts.*fake-mudita-calendar'):
            self.check()

    def test_wrong_original_provenance_index_even_with_repinned_receipt(self):
        self.check()
        self.value['deferredIndexSha256']=verify.APPROVED_BUNDLE_INDEX_SHA256;self.pin()
        with self.assertRaisesRegex(verify.VerificationError,'asset index mismatch'):self.check()

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

    def test_companion_tasks_and_artifacts_cannot_be_omitted_or_replayed(self):
        self.check()
        for module in COMPANIONS:
            with self.subTest(module=module):
                saved=self.value['tasks']
                self.value['tasks']=[t for t in saved if not t['task'].startswith(':'+module+':')]
                self.pin()
                with self.assertRaisesRegex(verify.VerificationError,'all four apps'):self.check()
                self.value['tasks']=saved;self.pin()
                for suffix in ('apk/release/app.apk','apk/release/output-metadata.json','mapping/release/mapping.txt','mapping/release/resources.txt'):
                    name=module+'/build/outputs/'+suffix
                    digest=self.files.pop(name);self.pin()
                    with self.assertRaisesRegex(verify.VerificationError,'Missing bound'):self.check()
                    self.files[name]=digest;self.pin()
                    path=self.root/name;original=path.read_bytes()
                    path.write_bytes(original+b' ' if suffix.endswith('.json') else b'stale companion artifact')
                    with self.assertRaisesRegex(verify.VerificationError,'Build artifact/evidence changed'):self.check()
                    path.write_bytes(original)

    def test_cached_companion_optimization_is_rejected(self):
        self.check()
        for module in COMPANIONS:
            task=next(t for t in self.tasks if t['task']==':'+module+':assembleRelease')
            log=Path(task['log']);original=log.read_text()
            log.write_text(original.replace(':minifyReleaseWithR8\n',':minifyReleaseWithR8 FROM-CACHE\n'))
            task['logSha256']=verify.sha(log.read_bytes());self.pin()
            with self.assertRaisesRegex(verify.VerificationError,'not freshly'):self.check()
            log.write_text(original);task['logSha256']=verify.sha(log.read_bytes());self.pin()

    def test_sync_task_log_cannot_be_replaced_with_other_app_evidence(self):
        task=self.tasks[-1];self.assertEqual(task['task'],':kotatsu-sync:assembleRelease')
        other=self.tasks[-4];self.assertEqual(other['task'],':kotatsu-notes:assembleRelease')
        task.update(log=other['log'],logSha256=other['logSha256']);self.pin()
        with self.assertRaisesRegex(verify.VerificationError,'Missing actual task output'):self.check()
