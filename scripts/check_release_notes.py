#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发版前校验 RELEASE_NOTES.md，规则见仓库根 RELEASE_GUIDE.md。

用法：python3 scripts/check_release_notes.py [RELEASE_NOTES.md]
不合规时逐条打印原因并以退出码 1 结束。
"""
import re
import sys

BULLET = re.compile(r"^-\s+\S")
HEADLINE = re.compile(r"^-\s+🎉(.+?)🎉\s*$")
BARE_HEADLINE = re.compile(r"^🎉.+🎉\s*$")
SECTION = re.compile(r"^##\s+\S")
PLACEHOLDER = ("TODO", "待补", "xxx", "XXX")


def fail(errors: list[str]) -> int:
    print("RELEASE_NOTES.md 不合规：")
    for e in errors:
        print(f"  - {e}")
    print("\n规范见 RELEASE_GUIDE.md")
    return 1


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "RELEASE_NOTES.md"
    try:
        raw = open(path, encoding="utf-8").read()
    except OSError as exc:
        return fail([f"读不到文件：{exc}"])

    lines = raw.splitlines()
    body = [(i + 1, ln.strip()) for i, ln in enumerate(lines) if ln.strip()]
    if not body:
        return fail(["正文是空的"])

    errors: list[str] = []

    # 规则 2/3/4：第一条非空行必须是 - 🎉短标签🎉
    first_no, first = body[0]
    m = HEADLINE.match(first)
    if not m:
        errors.append(
            f"第 {first_no} 行「{first[:30]}」不是条目形式的摘要，"
            f"应写成 `- 🎉功能更新🎉` 这样（必须以 `- ` 开头，🎉 成对包住短标签）"
        )
    else:
        label = m.group(1).strip()
        if not 2 <= len(label) <= 6:
            errors.append(f"摘要短标签「{label}」长度 {len(label)}，要求 2–6 字")

    # 规则 10：不允许裸露的 🎉…🎉 行，它不会进更新弹窗
    for no, ln in body:
        if BARE_HEADLINE.match(ln):
            errors.append(f"第 {no} 行是单独一行的「{ln[:20]}」，弹窗不显示，请改成 `- {ln}`")

    # 规则 6：条目至少 3 条，且不能有跨行段落混在中间
    bullets = [ln for _, ln in body if BULLET.match(ln)]
    if len(bullets) < 3:
        errors.append(f"条目只有 {len(bullets)} 条，至少 3 条")

    # 规则 7：不写实现细节
    for no, ln in body:
        for kw in ("fun ", "class ", "JSON", "return@", "BuildConfig", "versionCode"):
            if kw in ln:
                errors.append(f"第 {no} 行出现实现细节「{kw}」，用户看不懂，删掉")
                break

    # 占位符
    for no, ln in body:
        if any(p in ln for p in PLACEHOLDER):
            errors.append(f"第 {no} 行含占位符「{ln[:20]}」")

    if errors:
        return fail(errors)

    print(f"RELEASE_NOTES.md 通过校验（摘要：{m.group(1).strip()}，条目 {len(bullets)} 条）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
