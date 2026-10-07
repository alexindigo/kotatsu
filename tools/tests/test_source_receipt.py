"""Real Git histories and dirty inputs, not mocked provenance agreement."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('source_receipt', Path(__file__).parents[1] / 'source_receipt.py')
receipt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receipt)


class SourceReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        self.call('init', '-q')
        (self.root / 'input').write_text('baseline')
        self.call('add', 'input')
        self.base = self.commit_tree()
        self.path = Path(self.temp.name) / 'receipt.json'
        self.pin()

    def call(self, *args, input=None):
        return subprocess.check_output(['git', '-C', str(self.root), *args], input=input,
            env={**os.environ, 'GIT_AUTHOR_NAME': 'Synthetic fixture', 'GIT_AUTHOR_EMAIL': 'fixture@example.invalid',
                 'GIT_COMMITTER_NAME': 'Synthetic fixture', 'GIT_COMMITTER_EMAIL': 'fixture@example.invalid'})

    def commit_tree(self, parent=None):
        tree = self.call('write-tree').decode().strip()
        args = ['-c', 'commit.gpgsign=false', 'commit-tree', tree]
        if parent:
            args += ['-p', parent]
        head = self.call(*args, input=b'Synthetic test history\n').decode().strip()
        self.call('update-ref', 'HEAD', head)
        return head

    def pin(self):
        self.value = receipt.snapshot(self.root)
        self.path.write_bytes(receipt.encode(self.value))
        self.sha = receipt.digest(self.path.read_bytes())

    def check(self, **overrides):
        args = dict(root=self.root, baseline=self.base, expected_head=self.value['head'],
                    expected_tree=self.value['headTree'], receipt=self.path, expected_digest=self.sha)
        args.update(overrides)
        return receipt.verify(**args)

    def test_baseline_and_descendant_with_empty_ancestry_stdout(self):
        self.check()
        (self.root / 'input').write_text('descendant')
        self.call('add', 'input')
        self.commit_tree(self.base)
        self.pin()
        self.check()

    def test_independent_wrong_head_tree_digest_and_unrelated_history(self):
        for args in ({'expected_head': '0' * 40}, {'expected_tree': '1' * 40}, {'expected_digest': '0' * 64}):
            with self.subTest(args=args), self.assertRaises(receipt.ReceiptError):
                self.check(**args)
        (self.root / 'input').write_text('unrelated')
        self.call('add', 'input')
        self.commit_tree()
        self.pin()
        with self.assertRaisesRegex(receipt.ReceiptError, 'ancestor'):
            self.check()

    def test_edit_delete_new_mode_symlink_and_stage_cannot_replay_receipt(self):
        mutations = [lambda: (self.root / 'input').write_text('changed'),
                     lambda: (self.root / 'input').unlink(),
                     lambda: (self.root / 'new').write_text('new'),
                     lambda: (self.root / 'input').chmod(0o755)]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                mutate()
                with self.assertRaises(receipt.ReceiptError):
                    self.check()
                (self.root / 'input').write_text('baseline')
                (self.root / 'input').chmod(0o644)
                (self.root / 'new').unlink(missing_ok=True)
        (self.root / 'link').symlink_to('input')
        self.pin()
        (self.root / 'link').unlink()
        (self.root / 'link').symlink_to('other')
        with self.assertRaises(receipt.ReceiptError):
            self.check()
        self.pin()
        self.call('add', 'link')
        with self.assertRaises(receipt.ReceiptError):
            self.check()

    def test_receipt_substitution_rejected_by_external_digest(self):
        value = json.loads(self.path.read_text())
        value['inputs'] = []
        self.path.write_bytes(receipt.encode(value))
        with self.assertRaisesRegex(receipt.ReceiptError, 'digest mismatch'):
            self.check()

    def test_preserved_generated_bytecode_is_excluded_from_status_and_inputs(self):
        directory = self.root / 'tools/__pycache__'
        directory.mkdir(parents=True)
        (directory / 'old.cpython-314.pyc').write_bytes(b'prior generated cache')
        self.check()
        (self.root / 'tools/new.py').write_text('new build input')
        with self.assertRaises(receipt.ReceiptError):
            self.check()
