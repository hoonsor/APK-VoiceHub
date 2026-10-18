"""manual_coverage_check 的單元測試（不連網）：python -m unittest discover -s tools"""
import unittest

from manual_coverage_check import MANUAL, SELF, check, parse_manual


def manifest(pkg, *names, deprecated=()):
    return {"package": pkg, "app": pkg, "actions": [{"name": n, **({"deprecated": True} if n in deprecated else {})} for n in names]}


MD = """
# 照片
<!-- app: com.photo -->
## 列出
<!-- action: com.photo:photo_list -->
- 「列出照片」
<!--
<!-- action: com.photo:in_comment -->
-->
## 內建
<!-- action: voicehub:flashlight_set -->
"""


class ParseTest(unittest.TestCase):
    def test_parse(self):
        apps, actions = parse_manual(MD)
        self.assertEqual({"com.photo"}, apps)
        self.assertEqual({("com.photo", "photo_list"), (SELF, "flashlight_set")}, actions)


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.apps, self.actions = parse_manual(MD)

    def test_all_covered(self):
        ms = {SELF: manifest("tw.hoonsor.voicehub", "flashlight_set"), "o/APK-Photo": manifest("com.photo", "photo_list")}
        self.assertEqual([], check(self.apps, self.actions, ms))

    def test_lab_package_counts_as_same_app(self):
        self.assertEqual([], check(self.apps, self.actions, {"x": manifest("com.photo.lab", "photo_list")}))

    def test_missing_action(self):
        p = check(self.apps, self.actions, {"o/APK-Photo": manifest("com.photo", "photo_list", "album_list")})
        self.assertEqual(1, len(p))
        self.assertIn("album_list", p[0])

    def test_deprecated_action_not_required(self):
        self.assertEqual([], check(self.apps, self.actions, {"x": manifest("com.photo", "photo_list", "old", deprecated=("old",))}))

    def test_missing_app_chapter(self):
        p = check(self.apps, self.actions, {"o/APK-New": manifest("com.new", "thing_list")})
        self.assertTrue(any("缺少 APP 章節" in x for x in p))
        self.assertTrue(any("thing_list" in x for x in p))

    def test_stale_tag(self):
        p = check(self.apps, self.actions, {SELF: manifest("tw.hoonsor.voicehub")})
        self.assertTrue(any("已不存在" in x and "flashlight_set" in x for x in p))


class RealManualTest(unittest.TestCase):
    def test_real_manual_parses(self):
        apps, actions = parse_manual(MANUAL.read_text(encoding="utf-8"))
        self.assertIn("com.photobox.sorter", apps)
        self.assertIn((SELF, "dispatch_send"), actions)


if __name__ == "__main__":
    unittest.main()
