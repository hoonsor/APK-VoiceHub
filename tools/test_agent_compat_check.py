"""agent_compat_check.py 的測試：python -m unittest discover -s tools"""
import copy
import unittest

from agent_compat_check import compare

# 測試自帶固定的範例 manifest，不讀 repo 的 docs/agent-manifest.json：
# 這個測試檔會隨工具一起複製到各 APP，各 APP 的動作不同（2026-10-24 待辦 APP 的 CI 因此失敗過）
BASE = {
    "api": 1,
    "package": "tw.hoonsor.example",
    "actions": [
        {
            "name": "timer_set", "risk": "reversible",
            "params": {"type": "object", "properties": {"seconds": {"type": "integer", "minimum": 1, "maximum": 86400}, "label": {"type": "string"}}, "required": ["seconds"]},
        },
        {
            "name": "volume_set", "risk": "reversible",
            "params": {"type": "object", "properties": {"stream": {"type": "string", "enum": ["media", "ring", "alarm"]}, "percent": {"type": "integer", "minimum": 0, "maximum": 100}}, "required": ["stream", "percent"]},
        },
    ],
}

def action(m, name):
    return next(a for a in m["actions"] if a["name"] == name)


class CompatTest(unittest.TestCase):
    def test_same_is_compatible(self):
        self.assertEqual(compare(BASE, copy.deepcopy(BASE))[0], [])

    def test_new_action_and_optional_param_ok(self):
        new = copy.deepcopy(BASE)
        new["actions"].append({"name": "note_add", "risk": "reversible", "params": {"type": "object"}})
        action(new, "timer_set")["params"]["properties"]["sound"] = {"type": "string"}
        breaking, notes = compare(BASE, new)
        self.assertEqual(breaking, [])
        self.assertTrue(any("note_add" in n for n in notes))

    def test_removed_action_breaks(self):
        new = copy.deepcopy(BASE)
        new["actions"] = [a for a in new["actions"] if a["name"] != "timer_set"]
        self.assertTrue(any("timer_set" in b for b in compare(BASE, new)[0]))

    def test_deprecated_is_ok(self):
        new = copy.deepcopy(BASE)
        action(new, "timer_set")["deprecated"] = True
        breaking, notes = compare(BASE, new)
        self.assertEqual(breaking, [])
        self.assertTrue(any("deprecated" in n for n in notes))

    def test_new_required_param_breaks(self):
        new = copy.deepcopy(BASE)
        a = action(new, "timer_set")
        a["params"]["properties"]["unit"] = {"type": "string"}
        a["params"]["required"].append("unit")
        self.assertTrue(any("必填" in b for b in compare(BASE, new)[0]))

    def test_type_change_breaks(self):
        new = copy.deepcopy(BASE)
        action(new, "timer_set")["params"]["properties"]["seconds"]["type"] = "string"
        self.assertTrue(any("型別" in b for b in compare(BASE, new)[0]))

    def test_enum_value_removed_breaks(self):
        new = copy.deepcopy(BASE)
        action(new, "volume_set")["params"]["properties"]["stream"]["enum"].remove("ring")
        self.assertTrue(any("ring" in b for b in compare(BASE, new)[0]))

    def test_narrowed_range_breaks(self):
        new = copy.deepcopy(BASE)
        action(new, "volume_set")["params"]["properties"]["percent"]["maximum"] = 50
        self.assertTrue(any("maximum" in b for b in compare(BASE, new)[0]))


if __name__ == "__main__":
    unittest.main()
