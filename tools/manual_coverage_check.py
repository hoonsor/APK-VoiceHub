#!/usr/bin/env python3
"""使用說明書收錄檢查（只用標準函式庫）。

確保 docs/manual/MANUAL.md 收錄了「所有開放 Hoonsor Agent API 的 APP」與「它們的每個動作」。
規則見 docs/AGENT_PROTOCOL.md「說明書」一節；說明書格式見 MANUAL.md 開頭的撰寫規則。

用法：
  python tools/manual_coverage_check.py --github hoonsor
      列出 GitHub 上 hoonsor 的所有 APK-* repo，讀各自預設分支的 docs/agent-manifest.json 比對
      （CI 每天跑一次；有 GITHUB_TOKEN 環境變數時會帶上，避免 API 次數限制）
  python tools/manual_coverage_check.py --local D:\\dev
      讀本機資料夾底下各專案的 docs/agent-manifest.json（開發時用，不連網）

一律也會檢查本 repo 的 docs/agent-manifest.json（內建動作，說明書裡的套件代號是 voicehub）。

結束碼：0＝全部收錄；1＝有沒收錄的 APP 或動作、或標了已不存在的動作；2＝用法或檔案錯誤。
"""
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

SELF = "voicehub"
SELF_REPO = "APK-VoiceHub"
ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs" / "manual" / "MANUAL.md"
SELF_MANIFEST = ROOT / "docs" / "agent-manifest.json"

ACTION_TAG = re.compile(r"^<!--\s*action:\s*([A-Za-z0-9_.]+):([a-z0-9_]+)\s*-->$")
APP_TAG = re.compile(r"^<!--\s*app:\s*([A-Za-z0-9_.]+)\s*-->$")


def parse_manual(text: str) -> tuple[set[str], set[tuple[str, str]]]:
    """回傳（有章節的 APP 套件名、收錄的 (套件名, 動作名)）。多行 HTML 註解裡的內容不算。"""
    apps: set[str] = set()
    actions: set[tuple[str, str]] = set()
    in_comment = False
    for raw in text.splitlines():
        line = raw.strip()
        if in_comment:
            if "-->" in line:
                in_comment = False
            continue
        if line.startswith("<!--"):
            if m := ACTION_TAG.match(line):
                actions.add((m.group(1), m.group(2)))
            elif m := APP_TAG.match(line):
                apps.add(m.group(1))
            elif "-->" not in line:
                in_comment = True
    return apps, actions


def base_package(pkg: str) -> str:
    return pkg[:-4] if pkg.endswith(".lab") else pkg


def check(apps: set[str], actions: set[tuple[str, str]], manifests: dict[str, dict]) -> list[str]:
    """manifests：{來源名稱: manifest}；內建動作的來源用 SELF。回傳問題清單（空＝通過）。"""
    problems: list[str] = []
    for source, m in sorted(manifests.items()):
        pkg = SELF if source == SELF else base_package(m.get("package") or "")
        if not pkg:
            problems.append(f"{source}：manifest 沒有 package 欄位")
            continue
        app_name = m.get("app") or pkg
        live = {a["name"] for a in m.get("actions", []) if "name" in a and not a.get("deprecated")}
        all_names = {a["name"] for a in m.get("actions", []) if "name" in a}
        if pkg != SELF and pkg not in apps:
            problems.append(f"缺少 APP 章節：{app_name}（{source}），請在 MANUAL.md 新增一章並在標題下一行加 <!-- app: {pkg} -->")
        for name in sorted(live):
            if (pkg, name) not in actions:
                problems.append(f"缺少動作：{app_name} 的 {name}，請新增一節並加 <!-- action: {pkg}:{name} -->")
        for tagged_pkg, name in sorted(actions):
            if tagged_pkg == pkg and name not in all_names:
                problems.append(f"說明書標了已不存在的動作：{pkg}:{name}（{source}），請刪除或改名那一節")
    return problems


def _get(url: str) -> bytes | None:
    req = urllib.request.Request(url, headers={"User-Agent": "voicehub-manual-check"})
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token and "api.github.com" in url:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def github_manifests(owner: str) -> dict[str, dict]:
    out: dict[str, dict] = {}
    page = 1
    while True:
        data = _get(f"https://api.github.com/users/{owner}/repos?per_page=100&page={page}")
        repos = json.loads(data or b"[]")
        if not repos:
            break
        for repo in repos:
            name = repo.get("name", "")
            if not name.startswith("APK-") or name == SELF_REPO or repo.get("archived"):
                continue
            branch = repo.get("default_branch") or "main"
            raw = _get(f"https://raw.githubusercontent.com/{owner}/{name}/{branch}/docs/agent-manifest.json")
            if raw is not None:  # 沒有 manifest＝還沒規劃語音控制，不用收錄
                out[f"{owner}/{name}"] = json.loads(raw.decode("utf-8"))
        page += 1
    return out


def local_manifests(folder: Path) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for f in sorted(folder.glob("*/docs/agent-manifest.json")):
        if f.resolve() == SELF_MANIFEST.resolve():
            continue
        out[f.parent.parent.name] = json.loads(f.read_text(encoding="utf-8"))
    return out


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[0] not in ("--github", "--local"):
        print(__doc__)
        return 2
    try:
        apps, actions = parse_manual(MANUAL.read_text(encoding="utf-8"))
        manifests = {SELF: json.loads(SELF_MANIFEST.read_text(encoding="utf-8"))}
        manifests.update(github_manifests(argv[1]) if argv[0] == "--github" else local_manifests(Path(argv[1])))
    except (OSError, ValueError) as e:
        print(f"讀取失敗：{e}")
        return 2

    problems = check(apps, actions, manifests)
    print(f"比對 {len(manifests)} 份 manifest：" + "、".join(sorted(manifests)))
    if problems:
        print(f"::error::使用說明書缺 {len(problems)} 項，請更新 docs/manual/MANUAL.md")
        for p in problems:
            print(f"::error::{p}")
        return 1
    print(f"說明書已收錄全部動作（{len(actions)} 節）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
