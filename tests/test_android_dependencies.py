"""The Android cdylib compiles the same sources as the engine executable.

A missing direct dependency can otherwise take a full mobile release build to find.
Platform-specific feature selections remain the Android manifest's responsibility.
"""
from pathlib import Path
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AndroidDependencyContract(unittest.TestCase):
    def test_shared_gameplay_dependencies_are_available_on_android(self):
        engine = tomllib.loads((ROOT / "crates/horizon-client/Cargo.toml").read_text())
        android = tomllib.loads((ROOT / "crates/horizon-android/Cargo.toml").read_text())
        for group in ("dependencies", "build-dependencies"):
            for name, dependency in engine[group].items():
                if isinstance(dependency, dict) and dependency.get("optional"):
                    continue
                with self.subTest(group=group, dependency=name):
                    self.assertIn(name, android[group])
                    expected = dependency if isinstance(dependency, dict) else {"version": dependency}
                    actual = android[group][name]
                    actual = actual if isinstance(actual, dict) else {"version": actual}
                    for field in ("path", "version"):
                        if field in expected:
                            self.assertEqual(actual.get(field), expected[field])
