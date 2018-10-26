#!/usr/bin/env python
import subprocess
import logging
import argparse
import os
import re

logging.basicConfig(level=logging.DEBUG)

parser = argparse.ArgumentParser()
parser.add_argument('version')
args = parser.parse_args()
if not re.match('\\d+\\.\\d+\\.\\d+', args.version):
    args.error('version must be in the format N.N.N')

if subprocess.call(['git', 'diff-index', '--quiet', 'HEAD']) != 0:  # noqa
    raise RuntimeError('Working directory must be clean.')


# Update versions everywhere
def replace(path, *replacements):
    with open(path, 'r') as source:
        text = source.read()
        for a, b in replacements:
            text = re.sub(a, b, text, flags=re.S | re.M)
    temp = '{}.1'.format(path)
    with open(temp, 'w') as dest:
        dest.write(text)
    os.rename(temp, path)


mvnver = [
    '<artifactId>gettus</artifactId>([^<]*)<version>[^<]+</version>',  # noqa
    '<artifactId>gettus</artifactId>\\1<version>{}</version>'.format(args.version),  # noqa
]
replace('readme.md', mvnver)
replace('package/pom.xml', mvnver)
subprocess.check_call([
    'git', 'commit', '-a', '-m', 'Update version: {}'.format(args.version)])

# Push tag
subprocess.check_call(['git', 'tag', 'v' + args.version])
subprocess.check_call(['git', 'push', '--tags'])
subprocess.check_call(['git', 'push'])

# Build and publish
env = os.environ.copy()
env['JAVA_HOME'] = '/usr/lib/jvm/java-8-jdk'
subprocess.check_call(['mvn', 'clean', 'deploy', '-P', 'release'], env=env)