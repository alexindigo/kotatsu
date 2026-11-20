#!/usr/bin/env python3
"""JUnit XML from `am instrument -r` output, and per-suite result counts.

convert <instrument.txt> <out.xml> --suite <name>: exit 1 unless the run completed with
  at least one test and no failures or errors (an aborted run is an error case).
summary <dir>...: counts every TEST-*.xml below the directories, like the VM gate.
"""
import argparse
import os
import pathlib
import sys
import xml.etree.ElementTree as ET

# AndroidJUnitRunner status codes (InstrumentationResultParser).
START, OK, ERROR, FAILURE, IGNORED, ASSUMPTION = 1, 0, -1, -2, -3, -4


def blocks(text):
    """Yields ('status', fields, code) and ('result', fields, code) records."""
    fields, key, kind = {}, None, None
    for line in text.splitlines():
        for prefix, record in (('INSTRUMENTATION_STATUS: ', 'status'), ('INSTRUMENTATION_RESULT: ', 'result')):
            if line.startswith(prefix):
                key, _, value = line[len(prefix):].partition('=')
                fields[key] = value
                kind = record
                break
        else:
            if line.startswith('INSTRUMENTATION_STATUS_CODE: ') or line.startswith('INSTRUMENTATION_CODE: '):
                code = int(line.split(': ', 1)[1].strip())
                yield ('status' if line.startswith('INSTRUMENTATION_STATUS_CODE') else 'result'), fields, code
                fields, key, kind = {}, None, None
            elif line.startswith('INSTRUMENTATION_FAILED: '):
                yield 'failed', {'shortMsg': line.split(': ', 1)[1]}, None
            elif key is not None:
                fields[key] += '\n' + line
    if fields or kind:
        yield 'incomplete', fields, None


def parse(text):
    cases, problems, current, completed = [], [], None, False
    for kind, fields, code in blocks(text):
        if kind == 'status' and code == START:
            current = {'class': fields.get('class', ''), 'name': fields.get('test', ''), 'outcome': None}
            cases.append(current)
        elif kind == 'status' and current is not None and code in (OK, ERROR, FAILURE, IGNORED, ASSUMPTION):
            current['outcome'] = code
            current['stack'] = fields.get('stack', '').strip()
            current = None
        elif kind == 'result':
            if 'shortMsg' in fields:
                problems.append(fields['shortMsg'].strip())
            if fields.get('longMsg'):
                problems.append(fields['longMsg'].strip())
            completed = code == -1 and 'shortMsg' not in fields
        elif kind in ('failed', 'incomplete'):
            problems.append(fields.get('shortMsg', 'instrumentation output ended without a result').strip())
    if not completed and not problems:
        problems.append('instrumentation did not report completion')
    return cases, problems


def convert(source, target, suite):
    cases, problems = parse(pathlib.Path(source).read_text(errors='replace'))
    root = ET.Element('testsuite', name=suite)
    counts = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for case in cases:
        element = ET.SubElement(root, 'testcase', classname=case['class'], name=case['name'], time='0')
        counts['tests'] += 1
        outcome = case['outcome']
        if outcome is None:
            ET.SubElement(element, 'error', message='Test did not finish').text = '\n'.join(problems)
            counts['errors'] += 1
        elif outcome in (FAILURE, ERROR):
            tag = 'failure' if outcome == FAILURE else 'error'
            ET.SubElement(element, tag, message=case['stack'].split('\n', 1)[0]).text = case['stack']
            counts['failures' if outcome == FAILURE else 'errors'] += 1
        elif outcome in (IGNORED, ASSUMPTION):
            ET.SubElement(element, 'skipped')
            counts['skipped'] += 1
    if problems and all(case['outcome'] is not None for case in cases):
        element = ET.SubElement(root, 'testcase', classname=suite, name='instrumentation', time='0')
        ET.SubElement(element, 'error', message=problems[0]).text = '\n'.join(problems)
        counts['tests'] += 1
        counts['errors'] += 1
    for key, value in counts.items():
        root.set(key, str(value))
    ET.ElementTree(root).write(target, encoding='utf-8', xml_declaration=True)
    print(f"{suite}: tests={counts['tests']} failures={counts['failures']} errors={counts['errors']} skipped={counts['skipped']}")
    for problem in problems:
        print(f'{suite}: {problem}')
    return 0 if counts['tests'] and not counts['failures'] and not counts['errors'] else 1


def summary(directories):
    rows, failed = [], []
    for directory in directories:
        for path in sorted(pathlib.Path(directory).rglob('TEST-*.xml')):
            root = ET.parse(path).getroot()
            for suite in [root] if root.tag == 'testsuite' else root.iter('testsuite'):
                counts = [int(suite.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')]
                rows.append((suite.get('name') or path.stem, *counts))
                failed += [f"{suite.get('name')}: {case.get('classname')}.{case.get('name')}"
                           for case in suite.iter('testcase') if case.find('failure') is not None or case.find('error') is not None]
    lines = ['| # | Suite | Tests | Failures | Errors | Skipped |', '|---|---|---|---|---|---|']
    lines += [f'| {i} | {name} | {t} | {f} | {e} | {s} |' for i, (name, t, f, e, s) in enumerate(rows, 1)]
    totals = [sum(row[i] for row in rows) for i in range(1, 5)]
    lines.append(f'| | total | {totals[0]} | {totals[1]} | {totals[2]} | {totals[3]} |')
    lines += [''] + [f'- FAILED {name}' for name in failed]
    text = '\n'.join(lines) + '\n'
    print(text, end='')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write('## Connected tests\n\n' + text)
    return 0 if rows and not failed else 1


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    one = commands.add_parser('convert')
    one.add_argument('source')
    one.add_argument('target')
    one.add_argument('--suite', required=True)
    many = commands.add_parser('summary')
    many.add_argument('directories', nargs='+')
    args = parser.parse_args(argv)
    if args.command == 'convert':
        return convert(args.source, args.target, args.suite)
    return summary(args.directories)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
