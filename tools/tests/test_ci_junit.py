"""Instrumentation output becomes JUnit results; aborted runs never read as green."""
import importlib.util
import io
from contextlib import redirect_stdout
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('junit', Path(__file__).parents[1] / 'ci' / 'junit.py')
junit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(junit)


def status(test, code, stack=None):
    lines = ['INSTRUMENTATION_STATUS: class=com.example.SampleTest', 'INSTRUMENTATION_STATUS: current=1',
             'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner', 'INSTRUMENTATION_STATUS: numtests=2']
    if stack:
        lines.append('INSTRUMENTATION_STATUS: stack=' + stack)
    lines += ['INSTRUMENTATION_STATUS: stream=', 'INSTRUMENTATION_STATUS: test=' + test,
              f'INSTRUMENTATION_STATUS_CODE: {code}']
    return lines


COMPLETE = ['INSTRUMENTATION_RESULT: stream=', '', 'Time: 1.5', '', 'OK (2 tests)', '', 'INSTRUMENTATION_CODE: -1']


class JunitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def convert(self, lines):
        source, target = self.root / 'instrument.txt', self.root / 'TEST-sample.xml'
        source.write_text('\n'.join(lines) + '\n')
        with redirect_stdout(io.StringIO()):
            rc = junit.convert(source, target, 'sample')
        return rc, ET.parse(target).getroot()

    def test_passing_run_is_green(self):
        rc, root = self.convert(status('first', 1) + status('first', 0) + status('second', 1) + status('second', -3) + COMPLETE)
        self.assertEqual(rc, 0)
        self.assertEqual((root.get('tests'), root.get('failures'), root.get('skipped')), ('2', '0', '1'))

    def test_failure_keeps_its_multiline_stack(self):
        stack = 'java.lang.AssertionError: Missing\n\tat com.example.SampleTest.first(SampleTest.kt:9)'
        rc, root = self.convert(status('first', 1) + status('first', -2, stack) + COMPLETE)
        self.assertEqual(rc, 1)
        failure = root.find('testcase/failure')
        self.assertEqual(failure.get('message'), 'java.lang.AssertionError: Missing')
        self.assertIn('SampleTest.kt:9', failure.text)

    def test_process_crash_marks_the_running_test(self):
        crash = ['INSTRUMENTATION_RESULT: shortMsg=Process crashed.', 'INSTRUMENTATION_CODE: 0']
        rc, root = self.convert(status('first', 1) + status('first', 0) + status('second', 1) + crash)
        self.assertEqual(rc, 1)
        self.assertEqual(root.get('errors'), '1')
        self.assertEqual(root.findall('testcase')[1].find('error').text, 'Process crashed.')

    def test_truncated_or_empty_output_is_an_error(self):
        for lines in (status('first', 1) + status('first', 0), [], COMPLETE):
            rc, root = self.convert(lines)
            self.assertEqual(rc, 1, lines)

    def test_summary_counts_every_result_file(self):
        self.convert(status('first', 1) + status('first', -2, 'boom') + COMPLETE)
        output = io.StringIO()
        with redirect_stdout(output):
            rc = junit.summary([self.root])
        self.assertEqual(rc, 1)
        self.assertIn('| 1 | sample | 1 | 1 | 0 | 0 |', output.getvalue())
        self.assertIn('FAILED sample: com.example.SampleTest.first', output.getvalue())


if __name__ == '__main__':
    unittest.main()
