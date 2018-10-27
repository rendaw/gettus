#!/usr/bin/env python
import subprocess
import logging
import argparse
import os
import re
import lxml.etree

logging.basicConfig(level=logging.DEBUG)

parser = argparse.ArgumentParser()
parser.add_argument('version')
args = parser.parse_args()
if not re.match('\\d+\\.\\d+\\.\\d+', args.version):
    args.error('version must be in the format N.N.N')

jenv = os.environ.copy()
jenv['JAVA_HOME'] = '/usr/lib/jvm/java-8-jdk'
subprocess.check_call(['mvn', 'clean', 'verify', '-P', 'prerelease'], env=jenv)

if subprocess.call([
    'git', 'diff', '--exit-code', '--quiet', 'HEAD'
]) != 0:
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


artifacts = []

for root, dirs, files in os.walk('.'):
    for f in files:
        if f != 'pom.xml':
            continue
        path = os.path.join(root, f)
        doc = lxml.etree.parse(path)
        root = doc.getroot()
        pomver = root.find('version', namespaces=root.nsmap)
        if pomver.text == '0.0.0':
            continue
        artifacts.append(root.find('artifactId', namespaces=root.nsmap).text)
        pomver.text = args.version
        doc.write(path)


replace('readme.md', *[
    [
        '<artifactId>{}</artifactId>([^<]*)<version>[^<]+</version>'.format(artifact),  # noqa
        '<artifactId>{}</artifactId>\\1<version>{}</version>'.format(artifact, args.version),  # noqa
    ] for artifact in artifacts
])
subprocess.check_call([
    'git', 'commit', '-a', '-m', 'Update version: {}'.format(args.version)])

# Push tag
subprocess.check_call(['git', 'tag', 'v' + args.version])
subprocess.check_call(['git', 'push', '--tags'])
subprocess.check_call(['git', 'push'])

# Build and publish
subprocess.check_call([
    'mvn', 'clean', 'deploy', '-P', 'prerelease,release'], env=jenv)