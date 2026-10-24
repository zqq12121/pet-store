#!/usr/bin/env python3
"""校验"只加注释"改动：剥离 Java 注释与空白后，比较新旧文件的代码 token 序列。

用法：
  python3 scripts/verify-comments-only.py            # 比对工作区 git 改动
  python3 scripts/verify-comments-only.py --base HEAD~1
  python3 scripts/verify-comments-only.py path/A.java path/B.java   # 显式指定文件（需已 git add 暂存旧版）

设计要点：必须用状态机剥离注释，不能正则替换——字符串字面量里的 "//"（如 URL）
和字符常量 '{' 都会误导朴素实现。注释剥离后再压缩所有空白，逐个字符比较。
"""

import re
import subprocess
import sys

COMMENT_OR_STRING = re.compile(
    r"""
      (?P<line>//[^\n]*)
    | (?P<block>/\*.*?\*/)
    | (?P<text>"(?:\\.|[^"\\])*")
    | (?P<char>'(?:\\.|[^'\\])*')
    """,
    re.VERBOSE | re.DOTALL,
)


def strip_comments(source: str) -> str:
    """移除注释，保留字符串与字符字面量原样，随后压缩全部空白。"""

    def replace(m: re.Match) -> str:
        if m.lastgroup in ("line", "block"):
            return " "
        return m.group(0)  # 字符串/字符常量里的内容不动

    without_comments = COMMENT_OR_STRING.sub(replace, source)
    return re.sub(r"\s+", "", without_comments)


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=True
    ).stdout


def changed_java_files(base: str) -> list[str]:
    out = git("diff", "--name-only", base, "--", "*.java")
    staged = git("diff", "--cached", "--name-only", base, "--", "*.java")
    return sorted({*out.split(), *staged.split()})


def old_content(path: str, base: str) -> str:
    return git("show", f"{base}:{path}")


def main() -> int:
    args = sys.argv[1:]
    base = "HEAD"
    if args and args[0] == "--base":
        base = args[1]
        args = args[2:]

    files = args or changed_java_files(base)
    if not files:
        print("没有检测到 Java 改动")
        return 0

    failures = []
    total_added = 0
    for path in files:
        try:
            before = old_content(path, base)
        except subprocess.CalledProcessError:
            print(f"  跳过（新文件）: {path}")
            continue
        try:
            with open(path, encoding="utf-8") as fh:
                after = fh.read()
        except FileNotFoundError:
            print(f"  跳过（已删除）: {path}")
            continue

        a, b = strip_comments(before), strip_comments(after)
        delta = after.count("\n") - before.count("\n")
        total_added += delta
        if a == b:
            print(f"  OK    +{delta:>4} 行  {path}")
        else:
            # 定位第一处差异，便于快速修回
            idx = next((i for i, (x, y) in enumerate(zip(a, b)) if x != y), min(len(a), len(b)))
            failures.append((path, idx, a[idx - 60:idx + 60], b[idx - 60:idx + 60]))
            print(f"  DIFF  +{delta:>4} 行  {path}")

    print(f"\n共 {len(files)} 个文件，新增 {total_added} 行，代码实体不一致 {len(failures)} 个")
    for path, idx, old, new in failures:
        print(f"\n[!] {path} 首个差异位置 {idx}")
        print(f"    旧: ...{old}...")
        print(f"    新: ...{new}...")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
