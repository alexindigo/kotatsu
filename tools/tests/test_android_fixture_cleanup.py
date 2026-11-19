"""Keep Android fixture cleanup inside the shared failure-collecting boundary.

This is a deliberately narrow source guard, not a Kotlin parser. It ignores
comments/strings, checks raw finally blocks, and catches a helper call followed
by another statement in the same block. Ordered prerequisites belong inside
one helper action, as documented in FixtureCleanup.kt.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def code_only(source):
    """Blank comments and literals without changing line numbers or offsets."""
    pattern = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda m: re.sub(r'[^\n]', ' ', m[0]), source)


def closing(code, start):
    pairs = {'(': ')', '{': '}', '[': ']'}
    stack = []
    for index in range(start, len(code)):
        char = code[index]
        if char in pairs:
            stack.append(pairs[char])
        elif char in pairs.values():
            if not stack or stack.pop() != char:
                return None
            if not stack:
                return index
    return None


def cleanup_violations(source):
    code = code_only(source)
    errors = []
    for match in re.finditer(r'\bfinally\s*\{', code):
        start = code.index('{', match.start())
        end = closing(code, start)
        body = code[start + 1:end] if end is not None else ''
        # Only the shared wrapper's exception-aware finally is allowed. Other
        # callers use withFixtureCleanup or use, even for a single restoration.
        if not re.fullmatch(r'\s*fixtureCleanup\(\*actions,\s*failure\s*=\s*failure\)\s*', body):
            errors.append((code.count('\n', 0, match.start()) + 1, 'use withFixtureCleanup instead of raw finally'))
    for match in re.finditer(r'\bfixtureCleanup\s*\(', code):
        if re.search(r'\bfun\s*$', code[:match.start()]) or re.search(r'\bfun[^{};]*=\s*$', code[:match.start()]):
            continue
        end = closing(code, code.index('(', match.start()))
        if end is None:
            continue
        # Readback/assertions after collection are not additional cleanup. Only
        # flag recognizable restoration calls before the enclosing block ends.
        parents = []
        for index, char in enumerate(code[:match.start()]):
            if char == '{':
                parents.append(index)
            elif char == '}' and parents:
                parents.pop()
        # Main-thread wrappers do not collect errors themselves. Look past their
        # closing lambda too, but never cross a function or helper-action boundary.
        while parents and code[end + 1:].lstrip().startswith('}'):
            opening = parents[-1]
            if not re.search(r'\b(?:main|onMain|fixtureMain|runOnMainSync)\s*$', code[:opening]):
                break
            # A wrapper can itself be the entire function body. Its enclosing
            # class/file is not part of this cleanup sequence.
            if re.search(r'\bfun[^{};]*=\s*[^{};]*$', code[:opening]):
                break
            wrapper_end = closing(code, opening)
            if wrapper_end is None:
                break
            end = wrapper_end
            parents.pop()
        tail = code[end + 1:].lstrip().lstrip(';').lstrip()
        block_end = closing(code, parents[-1]) if parents else len(code)
        remainder = code[end + 1:block_end]
        cleanup = re.search(r'\b(?:close\w*|finish\w*|delete\w*|restore\w*|drop\w*|'
                            r'cancel\w*|stopListening|remove\w*|recycle|release\w*|clear\w*|shutdown\w*)\s*[({]',
                            remainder)
        if tail and tail[0] not in '}),].' and cleanup:
            errors.append((code.count('\n', 0, match.start()) + 1, 'cleanup after fixtureCleanup can be skipped'))
    return errors


class AndroidFixtureCleanupTest(unittest.TestCase):
    def test_android_test_sources_use_shared_cleanup(self):
        errors = []
        for path in sorted(ROOT.glob('*/src/androidTest/**/*.kt')):
            errors.extend(f'{path.relative_to(ROOT)}:{line}: {message}'
                          for line, message in cleanup_violations(path.read_text()))
        self.assertEqual([], errors, '\n'.join(errors))

    def test_sequential_finally_is_rejected_including_main_thread_cleanup(self):
        for source in ('try { test() } finally { host.delete(id); store.delete(id) }',
                       'try { test() } finally { main { finish() }; dropPermission() }',
                       'try { test() } finally { close() }'):
            with self.subTest(source=source):
                self.assertTrue(cleanup_violations(source))

    def test_cleanup_after_helper_is_rejected(self):
        for separator in ('\n', '; ', ';\n'):
            source = '{ fixtureCleanup({ stop() }, { delete() })' + separator + 'dropPermission() }'
            self.assertTrue(cleanup_violations(source))
        self.assertTrue(cleanup_violations('''{
            instrumentation.runOnMainSync { fixtureCleanup({ stop() }, { delete() }) }
            WeatherStore(context).delete(id)
            dropShellPermissionIdentity()
        }'''))

    def test_helper_nesting_prerequisites_and_literals_are_allowed(self):
        source = '''
            internal fun fixtureCleanup(vararg actions: () -> Unit, failure: Throwable? = null) { collect() }
            // finally { first(); second() }
            val label = "finally { ignored() }"
            val dump = """fixtureCleanup({ ignored() }); ignored()"""
            withFixtureCleanup(
                { main { fixtureCleanup({ stop() }, { delete() }) } },
                { drain(); restorePreferences() },
                { dropPermission() },
            ) { test() }
            try { body() } finally { fixtureCleanup(*actions, failure = failure) }
        '''
        self.assertEqual([], cleanup_violations(source))

    def test_braces_in_comments_and_strings_do_not_end_helper(self):
        self.assertTrue(cleanup_violations('''{
            fixtureCleanup({ log("}"); stop() /* ) } */ }, { delete() })
            dropPermission()
        }'''))

    def test_readback_after_collected_cleanup_is_not_another_restoration(self):
        self.assertEqual([], cleanup_violations('''class Fixture {
            override fun close() = fixtureCleanup({ first.close() }, { second.close() })
            fun exercise() { other.close() }
        }'''))
        self.assertEqual([], cleanup_violations('''{
            fixtureCleanup(*live.map { activity -> { finish(activity) } }.toTypedArray())
            fixtureMain { activities.all { it.isDestroyed } }
        }'''))
        self.assertTrue(cleanup_violations('''{
            fixtureCleanup({ stop() })
            main { host.deleteAppWidgetId(id) }
        }'''))

    def test_main_thread_wrappers_stop_at_expression_bodied_functions(self):
        for wrapper in ('main', 'onMain', 'fixtureMain', 'instrumentation.runOnMainSync'):
            for body in ('fixtureCleanup({ first.close() })',
                         'onMain { fixtureCleanup({ first.close() }) }'):
                with self.subTest(wrapper=wrapper, body=body):
                    for separator in (' ', '\n                            '):
                        self.assertEqual([], cleanup_violations(f'''class Fixture {{
                            override fun close() ={separator}{wrapper} {{ {body} }}
                            fun exercise() {{ other.close() }}
                        }}'''))
                    self.assertTrue(cleanup_violations(f'''class Fixture {{
                        override fun close() {{
                            {wrapper} {{ {body} }}
                            other.close()
                        }}
                    }}'''))

    def test_multiline_expression_bodied_signatures_are_function_boundaries(self):
        for signature in ('close(\n)', 'close(\n    reason: String,\n): Unit'):
            for wrapper in ('main', 'onMain', 'fixtureMain', 'instrumentation.runOnMainSync'):
                for body in ('fixtureCleanup({ first.close() })',
                             'onMain { fixtureCleanup({ first.close() }) }'):
                    with self.subTest(signature=signature, wrapper=wrapper, body=body):
                        self.assertEqual([], cleanup_violations(f'''class Fixture {{
                            override fun {signature} = {wrapper} {{ {body} }}
                            fun exercise() {{ other.close() }}
                        }}'''))
                        self.assertTrue(cleanup_violations(f'''class Fixture {{
                            override fun {signature} {{
                                {wrapper} {{ {body} }}
                                other.close()
                            }}
                        }}'''))
            with self.subTest(signature=signature, body='direct cleanup'):
                self.assertEqual([], cleanup_violations(f'''class Fixture {{
                    override fun {signature} = fixtureCleanup({{ first.close() }})
                    fun exercise() {{ other.close() }}
                }}'''))


if __name__ == '__main__':
    unittest.main()
