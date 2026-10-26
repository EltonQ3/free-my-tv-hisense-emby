"""Exercise consent and non-destructive install behavior with a fake adb; never contacts a TV."""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('installer', Path(__file__).resolve().parents[1] / 'distribution/tools/restore.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class InstallerCheck(unittest.TestCase):
    def exercise(self, answers, fail_install=False, fail_dump=False):
        calls = []
        def adb(serial, *args, **kwargs):
            calls.append(args)
            error = fail_install and args[0] == 'install' or fail_dump and args[-1] == 'android.permission.DUMP'
            return subprocess.CompletedProcess(args, int(bool(error)), stdout=b'Failure' if error else b'Success', stderr=b'')
        with patch.object(installer, 'run_adb', side_effect=adb), patch('builtins.input', side_effect=answers), contextlib.redirect_stdout(io.StringIO()):
            if fail_install:
                with self.assertRaises(installer.SetupError):
                    installer.install('192.168.1.50:5555')
            else:
                installer.install('192.168.1.50:5555')
        self.assertFalse(any('uninstall' in c or 'clear' in c or 'disable-user' in c or '-d' in c for c in calls))
        return calls

    def test_decline_leaves_permissions_and_other_clients_alone(self):
        calls = self.exercise(['n', 'n'])
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][0:3], ('install', '--no-incremental', '-r'))
        self.assertFalse(any('grant' in c for c in calls))

    def test_tv_direct_only_grants_explicit_fixed_permission(self):
        calls = self.exercise(['n', 'y'])
        self.assertEqual([c for c in calls if 'grant' in c], [('shell', 'pm', 'grant', 'io.freemytv', 'android.permission.DUMP')])

    def test_debug_switch_only_is_independent(self):
        calls = self.exercise(['y', 'n'])
        self.assertEqual([c[-1] for c in calls if 'grant' in c], ['android.permission.WRITE_SECURE_SETTINGS'])

    def test_both_permissions_need_both_choices(self):
        calls = self.exercise(['y', 'y'])
        self.assertEqual([c[-1] for c in calls if 'grant' in c], ['android.permission.WRITE_SECURE_SETTINGS', 'android.permission.DUMP'])

    def test_dump_failure_keeps_desktop_install_available(self):
        calls = self.exercise(['n', 'y'], fail_dump=True)
        self.assertEqual(calls[-1], ('shell', 'am', 'start', '-n', 'io.freemytv/.SettingsActivity'))

    def test_signature_or_version_failure_stops_before_any_grant(self):
        calls = self.exercise([], fail_install=True)
        self.assertEqual(len(calls), 1)


if __name__ == '__main__':
    unittest.main()
