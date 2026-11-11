#!/usr/bin/env python3
"""Reject unusable release configuration without revealing its values."""

import json
import os
import re
import sys


def validate(environ):
    prefix = "ORG_GRADLE_PROJECT_"
    banner = environ.get(prefix + "bannerId", "")
    interstitial = environ.get(prefix + "interstitialId", "")
    return {
        "appmetrica_present": bool(environ.get(prefix + "appMetricaKey", "").strip()),
        "banner_production_format": bool(re.fullmatch(r"R-M-\d+-\d+", banner)),
        "interstitial_production_format": bool(re.fullmatch(r"R-M-\d+-\d+", interstitial)),
        "ids_are_distinct": bool(banner and interstitial and banner != interstitial),
    }


if __name__ == "__main__":
    checks = validate(os.environ)
    print(json.dumps(checks, sort_keys=True))
    if not all(checks.values()):
        print("Release configuration rejected. Check APPMETRICA_API_KEY and the separate "
              "YANDEX_BANNER_ID (banner) / YANDEX_INTERSTITIAL_ID (interstitial) blocks. "
              "Values are intentionally omitted.", file=sys.stderr)
        sys.exit(1)
