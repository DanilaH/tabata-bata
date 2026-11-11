#!/usr/bin/env python3
"""Regression coverage for configuration mistakes which prevent monetization."""

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "production_config", Path(__file__).with_name("validate-production-config.py"))
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


class ProductionConfigTest(unittest.TestCase):
    def env(self, banner="R-M-100-1", interstitial="R-M-100-2", key="test-key"):
        return {"ORG_GRADLE_PROJECT_bannerId": banner,
                "ORG_GRADLE_PROJECT_interstitialId": interstitial,
                "ORG_GRADLE_PROJECT_appMetricaKey": key}

    def test_distinct_production_blocks_accepted(self):
        self.assertTrue(all(config.validate(self.env()).values()))

    def test_same_block_rejected(self):
        checks = config.validate(self.env(interstitial="R-M-100-1"))
        self.assertFalse(checks["ids_are_distinct"])

    def test_demo_missing_or_corrupted_id_rejected(self):
        for value in ("", "demo-banner-yandex", " R-M-100-1", "R-M-100-1\n", "R-M-100", "R-M-x-1"):
            for field in ("banner", "interstitial"):
                with self.subTest(value=value, field=field):
                    self.assertFalse(all(config.validate(self.env(**{field: value})).values()))

    def test_missing_analytics_key_rejected(self):
        for key in ("", "  "):
            self.assertFalse(all(config.validate(self.env(key=key)).values()))


if __name__ == "__main__":
    unittest.main()
