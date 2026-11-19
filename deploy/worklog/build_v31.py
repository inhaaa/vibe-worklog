"""Build both V31 images in the existing WorkLog builder and verify their JARs before the database rehearsal."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile

import release_v31 as release


ARCHIVE_SHA = '385d44d939998466d5c6167b66f9a8e468fe1f0457df0d2fb82c1a0782fbee06'
VERIFIER_SHA = 'fd677f5625e609455ff758f48b4c707aa9cdd0eb215a58c12301c8d70d652219'


def build():
    os.umask(0o077)
    stage = release.STAGE
    release.require(stage.resolve(strict=True) == stage and stage.stat().st_mode & 0o777 == 0o700,
                    'Unsafe staging directory')
    release.require(not {'COMPOSE_FILE', 'WORKLOG_IMAGE_TAG', 'DOCKER_HOST', 'DOCKER_CONTEXT'} & os.environ.keys(),
                    'Unexpected Docker environment')
    archive = stage / 'source.tar.gz'
    verifier = stage / 'verify_v31_artifact.py'
    for path, digest in ((archive, ARCHIVE_SHA), (verifier, VERIFIER_SHA)):
        release.require(path.resolve(strict=True) == path and release.file_sha(path) == digest,
                        'Pinned source or verifier differs')
    v18, prior, ops = release.old_helpers()
    before = ops.state()
    release.require(before['containers'][release.APP]['image'] == release.OLD_IMAGE, 'Live base image differs')
    ops.memory_available()
    ops.builder_limits()
    release.require(shutil.disk_usage(stage).free >= 4 * 1024**3, 'Insufficient build space')
    build_dir = Path(tempfile.mkdtemp(prefix='build-', dir=stage))
    print('WORKLOG_BUILD', build_dir.name, flush=True)
    revision = release.CANDIDATE
    with tarfile.open(archive, 'r:gz') as source:
        prefix = 'vibe-worklog-' + revision
        for item in source:
            parts = Path(item.name).parts
            release.require(parts and parts[0] == prefix and '..' not in parts and (item.isfile() or item.isdir()),
                            'Unexpected archive path or file type')
        source.extractall(build_dir, filter='data')
    tree = build_dir / prefix
    jars = {}
    images = {}
    with (build_dir / 'build.log').open('xb') as log:
        def run(args):
            subprocess.run(args, env=ops.ENV, stdout=log, stderr=subprocess.STDOUT, check=True)

        tags = {role: 'worklog-demo-' + role + ':' + revision[:7] for role in ('app', 'migrate')}
        missing = [role for role, tag in tags.items() if subprocess.run(
            ['docker', 'image', 'inspect', tag], env=ops.ENV, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL).returncode]
        if missing:
            try:
                run(['docker', 'buildx', 'inspect', '--bootstrap', ops.BUILDER])
                for role in missing:
                    target = 'runtime' if role == 'app' else 'migrate'
                    run(['docker', 'buildx', 'build', '--builder', ops.BUILDER, '--platform', 'linux/amd64',
                         '--target', target, '--tag', tags[role], '--label', 'org.opencontainers.image.revision=' + revision,
                         '--load', str(tree)])
            finally:
                run(['docker', 'buildx', 'stop', ops.BUILDER])
            ops.builder_limits()

        for role, tag in tags.items():
            image = v18.image_id(tag, revision)
            old = v18.image_id('worklog-demo-' + role + ':' + release.OLD_REV[:7], release.OLD_REV)
            originals = json.loads(subprocess.check_output(['docker', 'image', 'inspect', old, image], env=ops.ENV))
            prior.runtime_matches(*originals)
            images[role] = image
            jars[role] = build_dir / (role + '.jar')
            with jars[role].open('xb') as out:
                subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
                                '--security-opt', 'no-new-privileges:true', '--entrypoint', 'cat', image, '/app/application.jar'],
                               env=ops.ENV, stdout=out, stderr=log, check=True)
    proof = json.loads(subprocess.check_output([sys.executable, '-E', '-B', str(verifier), 'check', str(jars['app']),
                                                str(jars['migrate']), '--linux-lf']))
    after = ops.state()
    prior.preserved_state(before, after, release.APP, ops.BUILDER, '')
    release.require(all(before['containers'][release.APP][key] == after['containers'][release.APP][key]
                        for key in ('id', 'image', 'started')), 'Live app changed during build')
    proof.update(images=images, archive_sha256=ARCHIVE_SHA, build=build_dir.name)
    release.save(build_dir / 'image-proof.json', proof)
    print('WORKLOG_IMAGE_PROOF', json.dumps(proof, sort_keys=True), flush=True)


if __name__ == '__main__':
    build()
