"""The network-enabled widget is an independent application, never a launcher dependency."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"


class WeatherAppBoundaryTest(unittest.TestCase):
    def test_network_is_only_in_weather_application(self):
        self.assertNotIn(":kotatsu-weather", (ROOT / "app/build.gradle.kts").read_text())
        permitted = ROOT / "kotatsu-weather/src/main/AndroidManifest.xml"
        for manifest in ROOT.rglob("AndroidManifest.xml"):
            if any(part in {"build", ".git", ".gradle"} for part in manifest.relative_to(ROOT).parts):
                continue
            permissions = (node for node in ET.parse(manifest).getroot()
                           if node.tag in {"uses-permission", "uses-permission-sdk-23"})
            for permission in permissions:
                if permission.get(ANDROID + "name") == "android.permission.INTERNET":
                    self.assertTrue(manifest == permitted or permission.get(TOOLS + "node") == "remove", str(manifest))
        launcher = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        self.assertTrue(any(p.get(ANDROID + "name") == "android.permission.INTERNET"
                            and p.get(TOOLS + "node") == "remove" for p in launcher.findall("uses-permission")))


if __name__ == "__main__":
    unittest.main()
