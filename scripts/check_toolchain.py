#!/usr/bin/env python3
"""Gradle upstream 8.7 pins (services.gradle.org/distributions/*.sha256)."""
import hashlib
from pathlib import Path
import sys


def check(root):
    wrapper = root / 'gradle/wrapper'
    digest = hashlib.sha256((wrapper / 'gradle-wrapper.jar').read_bytes()).hexdigest()
    if digest != 'cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8':
        raise ValueError('Gradle wrapper JAR checksum mismatch')
    properties = (wrapper / 'gradle-wrapper.properties').read_text().splitlines()
    pins = ['distributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip',
            'distributionSha256Sum=544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d']
    if any(properties.count(pin) != 1 for pin in pins):
        raise ValueError('Gradle distribution version/checksum mismatch')
    if sum(line.startswith('distributionUrl=') for line in properties) != 1 or sum(line.startswith('distributionSha256Sum=') for line in properties) != 1:
        raise ValueError('Ambiguous Gradle wrapper properties')


if __name__ == '__main__':
    try:
        check(Path('.'))
    except (ValueError, OSError) as error:
        sys.exit(str(error))
    print('Gradle 8.7 wrapper and distribution checksum pins verified')
