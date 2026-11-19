"""Keep Android fixture cleanup inside the shared failure-collecting boundary.

This is a deliberately narrow source guard, not a Kotlin parser. It ignores
comments/strings, checks raw finally blocks, and catches a helper call followed
by another statement in the same block. Ordered prerequisites belong inside
one helper action, as documented in FixtureCleanup.kt.
"""
import re
import unittest
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class Token:
    value: str
    start: int
    end: int
    line: int


def tokenize(source):
    """Lex Kotlin/Java tokens; literals are opaque and comments disappear."""
    lexeme = re.compile(
        r'\s+|//[^\n]*|/\*|"""|["\'`]|(?:[^\W\d]|\$)(?:\w|\$)*|\d+'
        r'|\?\.|->|::|==|!=|<=|>=|&&|\|\||\?:|.', re.S)
    index, line = 0, 1
    while index < len(source):
        match = lexeme.match(source, index)
        value, end = match[0], match.end()
        ignored = value.isspace() or value.startswith('//')
        if value == '/*':
            depth = 1
            while end < len(source) and depth:
                if source.startswith('/*', end):
                    depth += 1
                    end += 2
                elif source.startswith('*/', end):
                    depth -= 1
                    end += 2
                else:
                    end += 1
            ignored = True
        elif value == '"""':
            close = source.find(value, end)
            end = len(source) if close < 0 else close + 3
            value = '<literal>'
        elif value in ('"', "'", '`'):
            quote = value
            while end < len(source):
                char = source[end]
                end += 1
                if char == '\\' and quote != '`':
                    end = min(end + 1, len(source))
                elif char == quote:
                    break
            value = source[index:end] if quote == '`' else '<literal>'
        if not ignored:
            yield Token(value, index, end, line)
        line += source[index:end].count('\n')
        index = end


@dataclass(frozen=True)
class Function:
    name: int
    start: int
    end: int


class SourceScan:
    """Matched delimiters and explicit function spans, not a language parser."""
    def __init__(self, source):
        self.source = source
        self.tokens = list(tokenize(source))
        self.words = [token.value for token in self.tokens]
        self.pairs, self.brace = {}, {}
        stack, braces = [], []
        for index, word in enumerate(self.words):
            self.brace[index] = braces[-1] if braces else None
            if word in ('(', '[', '{'):
                stack.append(index)
                if word == '{':
                    braces.append(index)
            elif word in (')', ']', '}') and stack:
                opening = stack.pop()
                if self.words[opening] == {')': '(', ']': '[', '}': '{'}[word]:
                    self.pairs[opening] = index
                    self.pairs[index] = opening
                if word == '}' and braces:
                    braces.pop()
        self.functions = self.find_functions()
        self.declarations = {function.name for function in self.functions}

    def expression_end(self, start):
        index = start
        continuation = {'.', '?.', '?:', '+', '-', '*', '/', '=', ',', '->', '&&', '||', ':'}
        while index < len(self.words):
            word = self.words[index]
            if word in (';', '}', ')', ']'):
                break
            if index > start and '\n' in self.source[self.tokens[index - 1].end:self.tokens[index].start]:
                if self.words[index - 1] not in continuation and word not in continuation | {'{', 'else', 'catch', 'finally'}:
                    break
            index = self.pairs[index] + 1 if word in ('(', '[', '{') and index in self.pairs else index + 1
        return index

    def body_after(self, parameters):
        index = self.pairs[parameters] + 1
        while index < len(self.words):
            word = self.words[index]
            if word in ('{', '='):
                return index
            if word in (';', '}', 'fun', 'val', 'var', 'class'):
                return None
            index = self.pairs[index] + 1 if word in ('(', '[') and index in self.pairs else index + 1
        return None

    def find_functions(self):
        functions = []
        for index, word in enumerate(self.words):
            if word != 'fun':
                continue
            parameters = index + 1
            while parameters < len(self.words) and self.words[parameters] not in ('(', '{', '}', ';', '='):
                parameters += 1
            if parameters not in self.pairs or self.words[parameters] != '(':
                continue
            body = self.body_after(parameters)
            if body is not None:
                end = self.pairs.get(body) if self.words[body] == '{' else self.expression_end(body + 1)
                if end is not None:
                    functions.append(Function(parameters - 1, body + 1, end))
        # Java has no `fun`: require a declaration header, not a call/control flow.
        controls = {'if', 'for', 'while', 'switch', 'catch', 'synchronized', 'try', 'when'}
        for parameters, word in enumerate(self.words):
            if word != '(' or parameters not in self.pairs or parameters < 2:
                continue
            name = parameters - 1
            if self.words[name] in controls or self.words[name - 1] in ('.', '?.'):
                continue
            start = name - 1
            while start >= 0 and self.words[start] not in ('{', '}', ';'):
                start -= 1
            header = self.words[start + 1:name]
            if not header or any(word in header for word in ('fun', '=', 'return', 'new', 'val', 'var', '->')):
                continue
            body = self.body_after(parameters)
            if body is not None and self.words[body] == '{' and body in self.pairs:
                functions.append(Function(name, body + 1, self.pairs[body]))
        return sorted(functions, key=lambda function: function.start)

    def owner(self, index):
        return next((function for function in reversed(self.functions)
                     if function.start <= index < function.end), None)

    def wrapper(self, opening):
        before = opening - 1
        if before >= 0 and self.words[before] == ')':
            before = self.pairs.get(before, 0) - 1
        return before >= 0 and self.words[before] in ('main', 'onMain', 'fixtureMain', 'runOnMainSync')


def cleanup_violations(source):
    scan = SourceScan(source)
    words, errors = scan.words, []
    allowed_finally = ['fixtureCleanup', '(', '*', 'actions', ',', 'failure', '=', 'failure', ')']
    restoration_prefixes = ('close', 'finish', 'delete', 'restore', 'drop', 'cancel', 'remove', 'release', 'clear', 'shutdown')
    for index, word in enumerate(words[:-1]):
        if word == 'finally' and words[index + 1] == '{':
            end = scan.pairs.get(index + 1, index + 2)
            if words[index + 2:end] != allowed_finally:
                errors.append((scan.tokens[index].line, 'use withFixtureCleanup instead of raw finally'))
        if word != 'fixtureCleanup' or words[index + 1] != '(' or index in scan.declarations:
            continue
        end = scan.pairs.get(index + 1)
        if end is None:
            continue
        owner, parent = scan.owner(index), scan.brace[index]
        # Only main-thread wrappers are transparent. Helper-action lambdas and
        # the call's own innermost function remain hard sequence boundaries.
        limit = owner.end if owner else len(words)
        while parent is not None and scan.wrapper(parent):
            after = end + 1
            while after < limit and words[after] == ';':
                after += 1
            if after != scan.pairs.get(parent):
                break
            end, parent = after, scan.brace[parent]
        limit = min(limit, scan.pairs.get(parent, len(words)))
        after = end + 1
        while after < limit and words[after] == ';':
            after += 1
        if after >= limit or words[after] in ('}', ')', ',', ']', '.', '?.'):
            continue
        for candidate in range(after, limit - 1):
            name = words[candidate]
            if (scan.owner(candidate) == owner and candidate not in scan.declarations
                    and words[candidate + 1] in ('(', '{')
                    and (name.startswith(restoration_prefixes) or name in ('stopListening', 'recycle'))):
                errors.append((scan.tokens[index].line, 'cleanup after fixtureCleanup can be skipped'))
                break
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

    def test_local_expression_declaration_does_not_exempt_a_later_cleanup(self):
        self.assertEqual([(4, 'cleanup after fixtureCleanup can be skipped')], cleanup_violations('''
            override fun close() {
                fun marker() = Unit
                val collected = fixtureCleanup({ first.close() })
                other.close()
            }
        '''))

    def test_identifiers_starting_with_fun_do_not_create_function_boundaries(self):
        for name in ('functionName', 'funny', 'fun'):
            for wrapper in ('main', 'onMain', 'fixtureMain', 'instrumentation.runOnMainSync'):
                with self.subTest(name=name, wrapper=wrapper):
                    self.assertTrue(cleanup_violations(f'''override fun close() {{
                        val `{name}` = {wrapper} {{ fixtureCleanup({{ first.close() }}) }}
                        other.close()
                    }}'''))
                    if name != 'fun':
                        self.assertTrue(cleanup_violations(f'''override fun close() {{
                            val {name} = {wrapper} {{ fixtureCleanup({{ first.close() }}) }}
                            other.close()
                        }}'''))

    def test_cleanup_belongs_to_the_innermost_function(self):
        self.assertEqual([], cleanup_violations('''class Fixture {
            fun close() {
                fun collected() = onMain { fixtureCleanup({ first.close() }) }
                collected()
                other.close()
            }
        }'''))
        self.assertTrue(cleanup_violations('''class Fixture {
            fun close() = onMain {
                fun marker() = Unit
                fixtureCleanup({ first.close() })
                other.close()
            }
        }'''))
        self.assertTrue(cleanup_violations('''fun close() {
            fun nested() {
                fixtureCleanup({ first.close() })
                other.close()
            }
        }'''))

    def test_java_method_headers_bound_cleanup_sequences(self):
        self.assertEqual([], cleanup_violations('''class Fixture {
            @Override public void close(
            ) throws Exception {
                fixtureCleanup(first::close);
            }
            void exercise() { other.close(); }
        }'''))
        self.assertTrue(cleanup_violations('''class Fixture {
            public void close() {
                fixtureCleanup(first::close);
                other.close();
            }
        }'''))
        self.assertEqual([], cleanup_violations('''class Fixture {
            void close() {
                Object nested = new Object() {
                    public void collect() { fixtureCleanup(first::close); }
                };
                other.close();
            }
        }'''))

    def test_lexer_skips_nested_comments_and_opaque_literals(self):
        self.assertEqual([], cleanup_violations('''fun close() {
            /* outer /* nested */ fixtureCleanup({ first.close() }); other.close() */
            val text = "fixtureCleanup({ first.close() }); other.close()"
            val raw = """finally { other.close() }"""
            val character = '}'
            val escaped = '\\''
            // finally { other.close() }
        }'''))

    def test_keywords_are_complete_identifier_tokens_in_both_languages(self):
        self.assertEqual(['functionName', '$fun', 'fun$marker', '`fun`', 'fun'],
                         [token.value for token in tokenize('functionName $fun fun$marker `fun` fun')])


if __name__ == '__main__':
    unittest.main()
