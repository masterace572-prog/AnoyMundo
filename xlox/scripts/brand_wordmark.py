#!/usr/bin/env python3
"""Compatibility entry point. Kenny now uses the owner-supplied artwork.

The historical Anoy vectors remain committed and must not be regenerated as
part of Kenny branding changes. See kenny_icons.py for generation and checks.
"""
from kenny_icons import main

if __name__ == '__main__':
    main()
