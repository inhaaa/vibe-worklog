"""Roll out the verified WorkLog demo images from V30 to V31 on their existing host.

rehearse FULL_COMMIT APP_JAR_SHA256 MIGRATE_JAR_SHA256
apply run-YYYYMMDDhhmmss-abcdef0
verify run-YYYYMMDDhhmmss-abcdef0
The two hashes come from comparing both image JARs against the tested local JAR.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
from datetime import datetime, timezone


STAGE = Path('/srv/worklog/.local/public-month-close-details-20261119')
PREVIOUS = STAGE.with_name('public-month-close-20261119') / 'run-20261119052617-368f6a0'
OLD = STAGE.with_name('public-checkout-ops-20261118')
OLD_REV = '368f6a029ca96b6cb0e8a1796ba1c043a2a697f5'
CANDIDATE = '1d351fbb600a514c6ce02a8540ea0894c0da4412'
OLD_IMAGE = 'sha256:3e742229abce0b6c1a452e9155640989deb19fc2a70b080e958c4524562b38b1'
OLD_HELPER_SHA = '8b535b3e3e224873c5a6d8b87f07f0917facb9d1ee47fe37779b30cc39eb9c9c'
DB = 'worklog_demo'
PG = 'worklog-demo-db-1'
APP = 'worklog-demo-app-1'
VERIFIER = Path(__file__).resolve().with_name('verify_v31_data.py')
RUN_NAME = re.compile(r'run-([0-9]{14})-([a-f0-9]{7})')
CLONE = re.compile(r'worklog_demo_v31_[0-9]{14}_[a-f0-9]{7}_(test|final)')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def clone_name(run_name, phase):
    match = RUN_NAME.fullmatch(run_name)
    if not match or phase not in ('test', 'final'):
        raise ValueError('Invalid WorkLog release identifier')
    return validate_restore_target(f'worklog_demo_v31_{match[1]}_{match[2]}_{phase}')


def validate_restore_target(name):
    if not CLONE.fullmatch(name):
        raise ValueError('Restore target must be a dedicated new verification database')
    return name


def cutover(stop, operation, migration_started, recover):
    try:
        stop()
        operation()
    except BaseException:
        if not migration_started():
            recover()
        raise


def file_sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def read(path):
    require(path.resolve(strict=True) == path and path.is_file(), 'Unsafe evidence path')
    return json.loads(path.read_bytes())


def save(path, data):
    # An interrupted write never appears as a completed gate.
    part = path.with_name(path.name + '.pending')
    with part.open('x', encoding='utf-8') as stream:
        json.dump(data, stream, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(part, path)


def old_helpers():
    helper = OLD / 'main_public_ops.py'
    require(helper.resolve(strict=True) == helper and file_sha(helper) == OLD_HELPER_SHA,
            'Previous verified release helper fingerprint differs')
    spec = importlib.util.spec_from_file_location('worklog_previous_checkout', helper)
    checkout = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checkout)
    v18 = checkout.load_personnel().load_role().load_org().load_v18()
    prior = v18.load_prior()
    ops = prior.load_helpers()
    v18.ENV = ops.ENV
    prior.safe_root(STAGE)
    prior.safe_root(OLD)
    manifest = read(prior.LEGACY / 'candidate/.worklog-release.json')
    tree = {key: manifest[key] for key in ('files', 'modes')}
    require(manifest['revision'] == prior.LIVE_REVISION and
            prior.digest(json.dumps(tree, sort_keys=True, separators=(',', ':')).encode()) == prior.LIVE_TREE,
            'Live Compose source inventory changed')
    prior.verify_tree(prior.LIVE, manifest, exact=False)
    return v18, prior, ops


class Release:
    def __init__(self, directory):
        self.directory = directory
        self.v18, self.prior, self.ops = old_helpers()
        self.prior.safe_root(directory)
        self.prior.safe_root(PREVIOUS)
        final = read(PREVIOUS / 'complete.json')
        ready = read(PREVIOUS / 'ready.json')
        self.old_image = self.v18.image_id('worklog-demo-app:' + OLD_REV[:7], OLD_REV)
        self.old_jar = final['jar']
        require(self.old_image == OLD_IMAGE == final['image'] == ready['images']['app']
                and final['schema'] == '30' and final['revision'] == ready['revision'] == OLD_REV
                and self.old_jar == ready['app_jar'] and self.old_jar.startswith('32a29c375039'),
                'Previous V30 release identity differs')

    def run(self, *command, env=None, source=None, out=subprocess.PIPE):
        return subprocess.run(command, check=True, stdin=source, stdout=out, stderr=None,
                              env=self.ops.ENV if env is None else env).stdout

    def sql(self, name, sql):
        require(name == DB or CLONE.fullmatch(name), 'Unexpected database')
        return self.run('docker', 'exec', '-u', 'postgres', PG, 'psql', '-XAtq', '-v', 'ON_ERROR_STOP=1',
                        '--dbname=' + name, '-c', sql).decode().strip()

    def version(self, name):
        return self.sql(name, """SELECT version FROM public.flyway_schema_history WHERE success
            AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1""")

    def snapshot(self, database, file):
        with file.open('xb') as stream:
            self.run(sys.executable, '-E', '-B', str(VERIFIER), 'snapshot', database, out=stream)
        read(file)

    def compare(self, operation, first, second):
        self.run(sys.executable, '-E', '-B', str(VERIFIER), 'compare-' + operation,
                 str(first), str(second), out=None)

    def image_proof(self, revision, app_jar, migrate_jar):
        images = {}
        for role, digest in (('app', app_jar), ('migrate', migrate_jar)):
            image = self.v18.image_id('worklog-demo-' + role + ':' + revision[:7], revision)
            checksum = self.run('docker', 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
                                '--security-opt', 'no-new-privileges:true', '--entrypoint', 'sha256sum',
                                image, '/app/application.jar').decode().split()
            require(checksum == [digest, '/app/application.jar'], role + ' JAR fingerprint differs')
            images[role] = image
        originals = json.loads(self.run('docker', 'image', 'inspect', self.old_image, images['app']))
        self.prior.runtime_matches(*originals)
        return images

    def preserve_services(self, before):
        self.prior.preserved_state(before, self.ops.state(), APP, self.ops.BUILDER, '')

    def dump(self, file):
        with file.open('xb') as stream:
            self.run('docker', 'exec', '-u', 'postgres', PG, 'pg_dump', '--format=custom',
                     '--dbname=' + DB, out=stream)
            stream.flush()
            os.fsync(stream.fileno())
        return file_sha(file)

    def restore_clone(self, target, dump, compare):
        validate_restore_target(target)
        require(self.sql(DB, f"SELECT count(*) FROM pg_database WHERE datname='{target}'") == '0',
                'Verification database already exists')
        require(self.sql(DB, """SELECT pg_get_userbyid(datdba) FROM pg_database
                       WHERE datname=current_database()""") == 'postgres', 'Unexpected owner')
        require(self.sql(DB, """SELECT count(*) FROM pg_database d CROSS JOIN LATERAL aclexplode(d.datacl) a
                 WHERE d.datname=current_database() AND a.grantee=0""") == '0', 'Unexpected public DB privilege')
        grants = self.sql(DB, f"""SELECT format('GRANT %s ON DATABASE %I TO %I%s;', a.privilege_type,
                 '{target}', pg_get_userbyid(a.grantee), CASE WHEN a.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END)
                 FROM pg_database d CROSS JOIN LATERAL aclexplode(d.datacl) a WHERE d.datname=current_database()
                 AND a.grantee NOT IN (d.datdba, 0) ORDER BY a.grantee, a.privilege_type""")
        # Deny connections from creation until the default PUBLIC connect grant has been removed.
        self.sql(DB, f'CREATE DATABASE "{target}" OWNER postgres TEMPLATE template0 ALLOW_CONNECTIONS false')
        try:
            self.sql(DB, f'REVOKE ALL ON DATABASE "{target}" FROM PUBLIC; {grants}')
            self.sql(DB, f'ALTER DATABASE "{target}" ALLOW_CONNECTIONS true')
            with dump.open('rb') as stream:
                self.run('docker', 'exec', '-i', '-u', 'postgres', PG, 'pg_restore', '--exit-on-error',
                         '--single-transaction', '--dbname=' + target, source=stream)
            compare()
        finally:
            self.sql(DB, f'ALTER DATABASE "{target}" ALLOW_CONNECTIONS false')

    def require_sealed(self, target):
        validate_restore_target(target)
        require(self.sql(DB, f"SELECT NOT datallowconn FROM pg_database WHERE datname='{target}'") == 't'
                and self.sql(DB, f"SELECT count(*) FROM pg_stat_activity WHERE datname='{target}'") == '0',
                'Restored verification database still accepts connections')

    def migrate(self, name, revision):
        require(name == DB or CLONE.fullmatch(name), 'Unexpected migration target')
        command = self.v18.compose('run', '--rm', '--no-deps', '--pull', 'never', '-T',
                                   '-e', f'TNA_JDBC_URL=jdbc:postgresql://db:5432/{name}', 'migrate')
        with (self.directory / (name + '-flyway.log')).open('ab') as stream:
            result = subprocess.run(command, env={**self.ops.ENV, 'WORKLOG_IMAGE_TAG': revision[:7]},
                                    stdout=stream, stderr=subprocess.STDOUT)
        require(result.returncode == 0, 'Flyway failed; see the private ' + name + '-flyway.log')
        require(self.version(name) == '31', 'Flyway did not reach V31')

    def backup_and_check(self, phase, revision):
        target = clone_name(self.directory.name, phase)
        dump = self.directory / (phase + '.dump')
        before = self.directory / (phase + '-before.json')
        self.snapshot(DB, before)
        dump_hash = self.dump(dump)
        def compare():
            restored = self.directory / (phase + '-restored.json')
            self.snapshot(target, restored)
            self.compare('restore', before, restored)
            self.migrate(target, revision)
            after = self.directory / (phase + '-after.json')
            self.snapshot(target, after)
            self.compare('upgrade', restored, after)
            if phase == 'final':
                self.compare('schema', self.directory / 'test-after.json', after)
        self.restore_clone(target, dump, compare)
        self.require_sealed(target)
        require(file_sha(dump) == dump_hash, 'Backup fingerprint changed')
        print(f'WORKLOG_BACKUP_VERIFIED {phase} {dump_hash}', flush=True)
        return dump_hash

    def rehearse(self, revision, app_jar, migrate_jar):
        require(revision == CANDIDATE, 'Unexpected V31 revision')
        require(all(re.fullmatch('[a-f0-9]{64}', sha) for sha in (app_jar, migrate_jar)), 'Invalid JAR digest')
        self.v18.network(self.ops)
        self.v18.app_running(self.ops, self.old_image)
        self.prior.verify_running_jar(self.ops, self.old_jar)
        self.ops.health()
        sites = self.prior.sites()
        state = self.ops.state()
        previous = read(PREVIOUS / 'verified-data.json')
        previous_file = PREVIOUS / previous['snapshot']
        require(file_sha(previous_file) == previous['sha256'], 'Previous V30 schema evidence changed')
        baseline = self.directory / 'baseline.json'
        self.snapshot(DB, baseline)
        require(self.version(DB) == '30' and read(baseline)['previous_schema'] == read(previous_file)['schema'],
                'Live V30 schema differs from the previous deployment')
        image = self.image_proof(revision, app_jar, migrate_jar)
        original = self.v18.original_env()
        updated = self.prior.replace_tag(original, OLD_REV[:7], revision[:7])
        require(shutil.disk_usage(self.directory).free > 4 * 1024**3, 'Insufficient disk for release backup')
        self.run(*self.v18.compose('config', '--quiet'))
        rehearsal = self.backup_and_check('test', revision)
        self.preserve_services(state)
        save(self.directory / 'ready.json', dict(revision=revision, images=image, app_jar=app_jar, migrate_jar=migrate_jar,
             dump=rehearsal, state=state, sites=sites, env_before=hashlib.sha256(original).hexdigest(),
             env_after=hashlib.sha256(updated).hexdigest(), validator=file_sha(VERIFIER)))
        print('WORKLOG_REHEARSED', self.directory.name, flush=True)

    def check_stopped(self):
        self.v18.app_running(self.ops, self.old_image, False)
        require(self.sql(DB, """SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
            AND usename IN ('tna_app','tna_auth','tna_migration','tna_operator')""") == '0',
                'WorkLog has active application connections')

    def recover_old(self, ready):
        require(self.version(DB) == '30' and file_sha(self.v18.ENVFILE) == ready['env_before'],
                'Review schema and deployment tag before recovery')
        require(self.run('docker', 'inspect', '--format', '{{.Image}}', APP).decode().strip() == self.old_image,
                'Previous app identity differs')
        self.run('docker', 'start', APP)
        self.ops.health()
        print('WORKLOG_ORIGINAL_APP_HEALTHY', flush=True)

    def apply(self):
        ready = read(self.directory / 'ready.json')
        require(file_sha(VERIFIER) == ready['validator'], 'Verification script changed after rehearsal')
        require(ready['images'] == self.image_proof(ready['revision'], ready['app_jar'], ready['migrate_jar']),
                'Image changed after rehearsal')
        require(ready['dump'] == file_sha(self.directory / 'test.dump'), 'Rehearsal dump changed')
        if (self.directory / 'complete.json').exists():
            self.verify()
            return
        self.preserve_services(ready['state'])
        checkpoint = self.directory / 'migration-started.json'
        if not checkpoint.exists():
            self.v18.network(self.ops)
            self.v18.app_running(self.ops, self.old_image)
            require(self.version(DB) == '30' and file_sha(self.v18.ENVFILE) == ready['env_before'],
                    'Production baseline changed before cutover')
            def freeze():
                self.check_stopped()
                digest = self.backup_and_check('final', ready['revision'])
                frozen = self.directory / 'frozen.json'
                self.snapshot(DB, frozen)
                self.compare('restore', self.directory / 'final-before.json', frozen)
                self.check_stopped()
                save(checkpoint, dict(dump=digest, revision=ready['revision']))
            cutover(lambda: self.run(*self.v18.compose('stop', 'app')), freeze,
                    checkpoint.exists, lambda: self.recover_old(ready))

        checkpoint_value = read(checkpoint)
        require(checkpoint_value['dump'] == file_sha(self.directory / 'final.dump') and
                checkpoint_value['revision'] == ready['revision'], 'Final backup or operation identity changed')
        checked = self.directory / 'verified-data.json'
        if not checked.exists():
            self.check_stopped()
            current = self.version(DB)
            require(current in ('30', '31'), 'Unexpected live Flyway version; inspect before continuing')
            if current == '30':
                self.migrate(DB, ready['revision'])
            stamp = datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S%f')
            after = self.directory / ('live-after-' + stamp + '.json')
            self.snapshot(DB, after)
            self.compare('upgrade', self.directory / 'final-before.json', after)
            self.compare('schema', self.directory / 'test-after.json', after)
            save(checked, dict(snapshot=after.name, sha256=file_sha(after)))
        else:
            proof = read(checked)
            require(file_sha(self.directory / proof['snapshot']) == proof['sha256'] and self.version(DB) == '31',
                    'Verified V31 snapshot mismatch')

        if file_sha(self.v18.ENVFILE) == ready['env_before']:
            updated = self.prior.replace_tag(self.v18.original_env(), OLD_REV[:7], ready['revision'][:7])
            require(hashlib.sha256(updated).hexdigest() == ready['env_after'], 'Unexpected image-tag replacement')
            self.prior.replace_env(updated)
        require(file_sha(self.v18.ENVFILE) == ready['env_after'], 'Current environment tag differs from candidate')
        self.run(*self.v18.compose('config', '--quiet'))
        self.run(*self.v18.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'app'))
        evidence = self.live(ready)
        save(self.directory / 'complete.json', evidence)
        print('WORKLOG_DEPLOYED', self.directory.name, ready['revision'], flush=True)

    def live(self, ready):
        for phase in ('test', 'final'):
            self.require_sealed(clone_name(self.directory.name, phase))
        self.v18.app_running(self.ops, ready['images']['app'])
        self.v18.network(self.ops)
        self.prior.verify_running_jar(self.ops, ready['app_jar'])
        require(file_sha(self.v18.ENVFILE) == ready['env_after'] and self.version(DB) == '31',
                'Live deployment fingerprint mismatch')
        self.ops.health()
        sites = self.prior.sites()
        self.preserve_services(ready['state'])
        return dict(revision=ready['revision'], image=ready['images']['app'], jar=ready['app_jar'], schema='31',
                    final_dump=read(self.directory / 'migration-started.json')['dump'], sites=sites,
                    finished=datetime.now(timezone.utc).isoformat())

    def verify(self):
        require((self.directory / 'complete.json').is_file(), 'Deployment not marked complete')
        evidence = self.live(read(self.directory / 'ready.json'))
        print(json.dumps(evidence, sort_keys=True))
        print('WORKLOG_VERIFIED', self.directory.name, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    rehearsal = actions.add_parser('rehearse')
    for arg in ('revision', 'app_jar', 'migrate_jar'):
        rehearsal.add_argument(arg)
    for action in ('apply', 'verify'):
        actions.add_parser(action).add_argument('run')
    args = parser.parse_args()
    import fcntl
    require(os.geteuid() == 0 and not sys.flags.optimize, 'Use the authenticated host console with python3 -E -B')
    require(not {'WORKLOG_IMAGE_TAG', 'COMPOSE_FILE', 'DOCKER_HOST', 'DOCKER_CONTEXT'} & os.environ.keys(),
            'Unexpected Docker/Compose override')
    require(STAGE.resolve(strict=True) == STAGE and stat.S_IMODE(STAGE.stat().st_mode) == 0o700,
            'Release staging directory must be private')
    os.umask(0o077)
    with (STAGE / 'release.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == 'rehearse':
            require(re.fullmatch('[a-f0-9]{40}', args.revision), 'Invalid release commit')
            name = f"run-{datetime.now(timezone.utc):%Y%m%d%H%M%S}-{args.revision[:7]}"
            directory = STAGE / name
            directory.mkdir(mode=0o700)
            print('WORKLOG_RUN', name, flush=True)
            Release(directory).rehearse(args.revision, args.app_jar, args.migrate_jar)
        else:
            require(RUN_NAME.fullmatch(args.run), 'Invalid release directory')
            release = Release(STAGE / args.run)
            if args.action == 'apply':
                release.apply()
            else:
                release.verify()


if __name__ == '__main__':
    main()
