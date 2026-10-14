"""Only app/src/main/.../weather/WeatherSources.kt may name the Mudita weather module."""
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MUDITA_PACKAGE = 'com.gezimos.katapult.weather.mudita'
ALLOWED = Path('app/src/main/java/com/gezimos/katapult/weather/WeatherSources.kt')


def mudita_referencers(root):
    found = set()
    for path in (root / 'app' / 'src').rglob('*'):
        if path.is_file() and path.suffix in ('.kt', '.java'):
            if MUDITA_PACKAGE in path.read_text(encoding='utf-8'):
                found.add(path.relative_to(root))
    return found


class WeatherModuleBoundaryTest(unittest.TestCase):
    def test_only_weather_sources_references_mudita_module(self):
        self.assertEqual({ALLOWED}, mudita_referencers(ROOT))

    def test_other_app_file_importing_mudita_is_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            allowed = root / ALLOWED
            allowed.parent.mkdir(parents=True)
            allowed.write_text('import ' + MUDITA_PACKAGE + '.MuditaWeatherSource\n', encoding='utf-8')
            leak = root / 'app/src/main/java/com/gezimos/katapult/ui/Leak.kt'
            leak.parent.mkdir(parents=True)
            leak.write_text('import ' + MUDITA_PACKAGE + '.MuditaWeatherSource\n', encoding='utf-8')
            androidtest = root / 'app/src/androidTest/java/com/gezimos/katapult/Probe.kt'
            androidtest.parent.mkdir(parents=True)
            androidtest.write_text('val x = ' + MUDITA_PACKAGE + '.MuditaWeatherSource\n', encoding='utf-8')
            self.assertEqual(
                {ALLOWED, leak.relative_to(root), androidtest.relative_to(root)},
                mudita_referencers(root),
            )

    def test_only_allowed_file_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            allowed = root / ALLOWED
            allowed.parent.mkdir(parents=True)
            allowed.write_text('import ' + MUDITA_PACKAGE + '.MuditaWeatherSource\n', encoding='utf-8')
            self.assertEqual({ALLOWED}, mudita_referencers(root))


if __name__ == '__main__':
    unittest.main()
