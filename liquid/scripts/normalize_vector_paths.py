#!/usr/bin/env python3
"""Losslessly adapt compact SVG path syntax to Android's older PathParser.

SVG allows joined arc flags, e.g. a3 2.5 0 016 0, and leading-dot decimals.
Android VectorDrawable requires explicit arc parameters and zero-prefixed
fractions for reliable API-21+ parsing. No curve geometry is approximated.
"""
from decimal import Decimal
from pathlib import Path
import argparse
import re

ARITY = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7, 'Z': 0}
NUMBER = re.compile(r'[+-]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?')
PATH = re.compile(r'(android:pathData=")([^"]+)(")')


def normalize_path(data):
    output = []
    position = 0
    command = None
    count = 0

    def complete():
        if command is not None and ARITY[command.upper()]:
            if count == 0 or count % ARITY[command.upper()]:
                raise ValueError(f'Incomplete {command} command: {count} parameters')

    while position < len(data):
        char = data[position]
        if char.isspace() or char == ',':
            position += 1
            continue
        if char.upper() in ARITY:
            complete()
            command = char
            count = 0
            output.append(char)
            position += 1
            continue
        if command is None or command.upper() == 'Z':
            raise ValueError('Path needs a command before numbers')
        if command.upper() == 'A' and count % 7 in (3, 4):
            if char not in '01':
                raise ValueError('Arc flags must be 0 or 1')
            token = char
            position += 1
        else:
            match = NUMBER.match(data, position)
            if not match:
                raise ValueError(f'Invalid path token at {position}')
            token = format(Decimal(match.group()), 'f')
            position = match.end()
        output.append(token)
        count += 1
    complete()
    if not output or output[0].upper() != 'M':
        raise ValueError('Path must begin with a move command')
    return ' '.join(output)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    changed = []
    for path in sorted((root / 'app/src/main/res/drawable').glob('ic_*.xml')):
        if path.stem.startswith('ic_launcher'):
            continue  # Original brand paths are not interface artwork.
        old = path.read_text()
        new = PATH.sub(lambda m: m[1] + normalize_path(m[2]) + m[3], old)
        if new != old:
            changed.append(path.name)
            if not args.check:
                path.write_text(new)
    if args.check and changed:
        raise SystemExit('Non-canonical Android interface paths: ' + ', '.join(changed))
    print(('PASS ' if args.check else 'Normalized ') + str(len(changed)) + ' interface vector files')


if __name__ == '__main__':
    main()
