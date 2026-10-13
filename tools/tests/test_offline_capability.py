"""Compiled SDK-output shapes: permissions, provider/resource and mapped definitions."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('verify_fork', Path(__file__).parents[1] / 'verify_fork.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class OfflineCapabilityTest(unittest.TestCase):
    def setUp(self):
        self.manifest = ET.fromstring('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><provider android:name="androidx.startup.InitializationProvider" android:authorities="com.alexindigo.kotatsu.androidx-startup" /></application></manifest>')
        self.resources = '    resource 0x7f000001 string/screensaver_update_static\n'
        self.dex = 'P d 1\t1\t100\t<TOTAL>\nC d 1\t1\t100\tcom.gezimos.katapult.MainActivity\nM d 1\t1\t36\tcom.gezimos.katapult.MainActivity void onCreate(android.os.Bundle)\n'

    def check(self):
        return verify.compiled_offline(self.manifest, self.resources, self.dex)

    def test_actual_defined_output_preserves_androidx_and_screensaver(self):
        self.assertTrue(self.check()['noInternetUpdaterInstallerCapability'])

    def test_both_actual_permission_tag_forms_reject_all_three_permissions(self):
        for tag in ('uses-permission', 'uses-permission-sdk-23'):
            for permission in ('INTERNET', 'REQUEST_INSTALL_PACKAGES', 'INSTALL_PACKAGES'):
                with self.subTest(tag=tag, permission=permission):
                    node = ET.SubElement(self.manifest, tag, {verify.ANDROID + 'name': 'android.permission.' + permission})
                    with self.assertRaisesRegex(verify.VerificationError, 'permission'):
                        self.check()
                    self.manifest.remove(node)

    def test_updater_authority_and_paths_metadata_rejected(self):
        provider = self.manifest.find('application/provider')
        provider.set(verify.ANDROID + 'authorities', 'com.alexindigo.kotatsu.fileprovider')
        with self.assertRaises(verify.VerificationError):
            self.check()
        provider.set(verify.ANDROID + 'authorities', 'unrelated')
        ET.SubElement(provider, 'meta-data', {verify.ANDROID + 'name': 'android.support.FILE_PROVIDER_PATHS',
                                             verify.ANDROID + 'resource': '@xml/file_paths'})
        with self.assertRaises(verify.VerificationError):
            self.check()

    def test_mapped_updater_class_or_method_rejected(self):
        for row in ('C d 1\t1\t40\tcom.gezimos.katapult.util.UpdateChecker',
                    'M d 1\t1\t36\tcom.gezimos.katapult.MainViewModel void downloadUpdate(java.lang.Object)',
                    'C d 1\t1\t40\tcom.gezimos.katapult.ui.SettingsScreenKt$UpdateDialog$1'):
            with self.subTest(row=row):
                self.dex += row + '\n'
                with self.assertRaisesRegex(verify.VerificationError, 'definition'):
                    self.check()
                self.dex = self.dex.removesuffix(row + '\n')

    def test_exclusive_resources_rejected_but_unrelated_refresh_retained(self):
        for name in ('check_updates', 'update_install', 'perm_install_apps', 'retry'):
            self.resources += '    resource 0x7f000002 string/' + name + '\n'
            with self.assertRaisesRegex(verify.VerificationError, 'resource'):
                self.check()
            self.resources = self.resources.split('    resource 0x7f000002')[0]

    def test_missing_or_nondefined_dex_output_fails_closed(self):
        for dex in ('', 'P d 1\t1\t100\t<TOTAL>\n', self.dex.replace('C d ', 'C r ')):
            self.dex = dex
            with self.assertRaises(verify.VerificationError):
                self.check()
