"""Summarizes connected (instrumentation) test results so skips are never silent.

Usage: python3 scripts/instrumentation_summary.py <module dir>...

Reads build/outputs/androidTest-results/connected/**/TEST-*.xml of each module,
prints a Markdown table (appended to $GITHUB_STEP_SUMMARY when set) and exits
non-zero when a module ran no test or a test failed.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def counts(module):
    total = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    skipped = []
    pattern = os.path.join(module, 'build/outputs/androidTest-results/connected/**/TEST-*.xml')
    for path in glob.glob(pattern, recursive=True):
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == 'testsuite' else root.findall('testsuite')
        for suite in suites:
            for key in total:
                total[key] += int(suite.get(key, 0))
            for case in suite.findall('testcase'):
                if case.find('skipped') is not None:
                    skipped.append(f"{case.get('classname')}#{case.get('name')}")
    return total, skipped


def main(modules):
    lines = ['| Module | Tests | Failed | Skipped |', '|---|---|---|---|']
    ok = True
    for module in modules:
        total, skipped = counts(module)
        failed = total['failures'] + total['errors']
        lines.append(f"| {module} | {total['tests']} | {failed} | {total['skipped']} |")
        for name in skipped:
            lines.append(f'| ↳ skipped | {name} | | |')
        if total['tests'] == 0 or failed:
            ok = False
    text = '\n'.join(lines) + '\n'
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as f:
            f.write(text)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
