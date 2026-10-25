import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('idle_hud', HERE / 'deploy_when_empty.py')
D = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(D)


class DeploymentGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.stage = self.root / 'stage'
        self.stage.mkdir()
        self.server = self.root / 'server'
        (self.server / 'plugins').mkdir(parents=True)
        self.current = self.server / 'plugins/Noqui.jar'
        self.current.write_bytes(b'old-live-plugin')
        (self.stage / 'Noqui-hud.jar').write_bytes(b'reviewed-hud-plugin')
        (self.stage / 'pack.zip').write_bytes(b'public-pack')
        config = {'baselineSha256': D.sha(self.current), 'packName': 'pack.zip',
                  'artifacts': {name: D.sha(self.stage / name) for name in ('Noqui-hud.jar', 'pack.zip')}}
        (self.stage / 'deployment.json').write_text(json.dumps(config))
        self.count = 0
        self.busy_at_final_check = False
        self.fail_health = False
        self.calls = []

    def tearDown(self):
        self.temp.cleanup()

    def fake_run(self, *args, **kwargs):
        self.calls.append(args)
        if args[0] == D.JAVA:
            Path(args[-1], 'empty-check.txt').write_text('busy=1\n' if self.busy_at_final_check else
                                                       'shutdown-requested; players=0; main-thread=true\n')
        return SimpleNamespace(stdout='123\n', returncode=0)

    def check_health(self):
        if self.fail_health:
            raise RuntimeError('Fake startup failure')
        return 0

    def execute(self):
        real_path = Path
        def mapped_path(value):
            return self.stage if str(value) == '/var/www/noqui/packs' else real_path(value)
        with patch.object(D, '__file__', str(self.stage / 'deploy_when_empty.py')), \
             patch.object(D, 'SERVER', self.server), patch.object(D, 'Path', mapped_path), \
             patch.object(D, 'players', lambda: self.count), patch.object(D, 'run', self.fake_run), \
             patch.object(D, 'health', self.check_health), patch('sys.argv', ['test', '--deploy']), \
             contextlib.redirect_stdout(io.StringIO()):
            D.main()

    def test_online_player_blocks_all_stop_and_attach_actions(self):
        self.count = 1
        self.execute()
        self.assertEqual([], self.calls)
        self.assertEqual(b'old-live-plugin', self.current.read_bytes())

    def test_new_player_at_final_main_thread_check_blocks_service_stop(self):
        self.busy_at_final_check = True
        self.execute()
        self.assertEqual(2, len(self.calls))
        self.assertFalse(any('stop' in args for args in self.calls))
        self.assertEqual(b'old-live-plugin', self.current.read_bytes())

    def test_concurrent_deployment_is_not_overwritten(self):
        self.current.write_bytes(b'another-deployment')
        with self.assertRaisesRegex(RuntimeError, 'Live plugin changed'):
            self.execute()
        self.assertEqual([], self.calls)
        self.assertEqual(b'another-deployment', self.current.read_bytes())

    def test_empty_server_installs_once_and_records_receipt(self):
        self.execute()
        self.assertEqual(b'reviewed-hud-plugin', self.current.read_bytes())
        receipt = json.loads((self.stage / 'completed.json').read_text())
        self.assertEqual(0, receipt['onlineAtFinalCheck'])
        self.assertTrue(receipt['mainThreadCheck'])
        previous = list(self.calls)
        self.execute()
        self.assertEqual(previous, self.calls)

    def test_startup_failure_restores_old_plugin_and_starts_service(self):
        self.fail_health = True
        with self.assertRaisesRegex(RuntimeError, 'Fake startup failure'):
            self.execute()
        self.assertEqual(b'old-live-plugin', self.current.read_bytes())
        self.assertEqual(('sudo', 'systemctl', 'start', D.SERVICE), self.calls[-1])
        self.assertFalse((self.stage / 'completed.json').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
