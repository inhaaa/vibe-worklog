import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock


SCRIPT = Path(__file__).resolve().parents[1] / 'deploy/worklog/release_v30.py'
SPEC = importlib.util.spec_from_file_location('worklog_release_v30', SCRIPT)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class WorkLogReleaseTest(unittest.TestCase):
    def test_only_the_two_fresh_database_names_can_receive_a_restore(self):
        for name in ('worklog_demo', 'postgres', 'worklog_demo_old', 'worklog_demo_v30_20261119050204_368f6a0_test;'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                release.validate_restore_target(name)
        for phase in ('test', 'final'):
            self.assertEqual(release.clone_name('run-20261119050204-368f6a0', phase),
                             f'worklog_demo_v30_20261119050204_368f6a0_{phase}')

    def test_invalid_run_cannot_select_a_path_or_database(self):
        for name in ('../run-20261119050204-368f6a0', 'run-1234-abcd', 'run-20261119050204-368f6a0\n'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                release.clone_name(name, 'test')

    def test_restore_or_backup_failure_restarts_original_app(self):
        events = []
        def fail():
            events.append('backup')
            raise RuntimeError('backup incomplete')
        with self.assertRaisesRegex(RuntimeError, 'backup incomplete'):
            release.cutover(lambda: events.append('stop'), fail, lambda: False,
                            lambda: events.append('recover'))
        self.assertEqual(events, ['stop', 'backup', 'recover'])

    def test_migration_failure_does_not_restart_old_application(self):
        restart = Mock()
        operation = Mock(side_effect=RuntimeError('migration failed'))
        with self.assertRaisesRegex(RuntimeError, 'migration failed'):
            release.cutover(Mock(), operation, lambda: True, restart)
        restart.assert_not_called()

    def test_partial_stop_failure_recovers_and_success_has_no_extra_restart(self):
        restart = Mock()
        operation = Mock()
        with self.assertRaisesRegex(RuntimeError, 'stop'):
            release.cutover(Mock(side_effect=RuntimeError('stop')), operation, lambda: False, restart)
        operation.assert_not_called()
        restart.assert_called_once()
        restart.reset_mock()
        release.cutover(Mock(), operation, lambda: False, restart)
        operation.assert_called_once()
        restart.assert_not_called()

    def test_rehearsal_migrates_only_the_restored_clone_after_a_matching_backup(self):
        revision = '368f6a0' + '0' * 33
        with TemporaryDirectory() as temporary:
            for phase in ('test', 'final'):
                directory = Path(temporary) / 'run-20261119050204-368f6a0'
                directory.mkdir(exist_ok=True)
                target = release.clone_name(directory.name, phase)
                runner = object.__new__(release.Release)
                runner.directory = directory
                events = []
                runner.snapshot = lambda db, file: events.append(('snapshot', db, file.name))
                def dump(file):
                    events.append(('dump', file.name))
                    file.write_bytes(b'verified dump')
                    return release.file_sha(file)
                runner.dump = dump
                def restore(db, file, compare):
                    events.append(('restore', db, file.name))
                    compare()
                runner.restore_clone = restore
                runner.compare = lambda kind, left, right: events.append(('compare', kind, left.name, right.name))
                runner.migrate = lambda db, version: events.append(('migrate', db, version))
                runner.backup_and_check(phase, revision)
                expected = [
                    ('snapshot', release.DB, phase + '-before.json'), ('dump', phase + '.dump'),
                    ('restore', target, phase + '.dump'), ('snapshot', target, phase + '-restored.json'),
                    ('compare', 'restore', phase + '-before.json', phase + '-restored.json'),
                ]
                if phase == 'test':
                    expected.extend([('migrate', target, revision), ('snapshot', target, 'test-after.json'),
                                     ('compare', 'upgrade', 'test-restored.json', 'test-after.json')])
                self.assertEqual(events, expected)

    def test_failed_restore_seals_only_the_new_clone(self):
        target = 'worklog_demo_v30_20261119050204_368f6a0_final'
        with TemporaryDirectory() as temporary:
            dummy = Path(temporary) / 'snapshot'
            dummy.write_bytes(b'dump')
            runner = object.__new__(release.Release)
            runner.sql = Mock(side_effect=['0', 'postgres', '0', 'grants', '', '', '', ''])
            runner.run = Mock(side_effect=RuntimeError('restore failure'))
            compare = Mock()
            with self.assertRaisesRegex(RuntimeError, 'restore failure'):
                runner.restore_clone(target, dummy, compare)
            compare.assert_not_called()
            self.assertEqual(runner.sql.call_args.args, (release.DB, f'ALTER DATABASE "{target}" ALLOW_CONNECTIONS false'))
            self.assertIn('--single-transaction', runner.run.call_args.args)
            self.assertIn('--dbname=' + target, runner.run.call_args.args)
            self.assertNotIn('--dbname=' + release.DB, runner.run.call_args.args)
