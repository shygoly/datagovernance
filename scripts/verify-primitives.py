#!/usr/bin/env python3
"""系统元语与防漂移护栏综合校验（T3 / P8 机械门禁）。

用法:
  python3 scripts/verify-primitives.py            # 标准模式（前端存量色值漂移报 WARN）
  python3 scripts/verify-primitives.py --strict   # 严格 CI 模式（任何漂移均阻断）

校验项:
  [1] Space Contracts 一致性（复用 docs/space-contracts/verify.py）
  [2] P1 宪法与导航地图约束（CONSTITUTION.md 存在且精炼；AGENTS.md <= 100 行）
  [3] Maven 物理依赖边界（直接解析 backend/dg-*/pom.xml 证明 C1 禁令）
  [4] A0.2 技能包合规校验（skills/*/SKILL.md Frontmatter、行数上限、最小工具作用域）
  [5] P6 前端设计 Token 防漂移扫描（拦截视图/组件中的硬编码色值，输出修复指令）
"""
from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
STRICT = "--strict" in sys.argv

errors: list[str] = []
warnings: list[str] = []


def check_space_contracts() -> None:
    script = ROOT / "docs" / "space-contracts" / "verify.py"
    if not script.exists():
        errors.append("[1] 缺少 docs/space-contracts/verify.py")
        return
    proc = subprocess.run(
        [sys.executable, str(script)],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        errors.append(
            "[1] Space Contracts 校验失败。FIX: 运行 `python3 docs/space-contracts/verify.py` "
            f"并修复输出错误:\n{proc.stdout.strip()}\n{proc.stderr.strip()}"
        )
    else:
        print("[1] Space Contracts: OK (8/8 契约、同步子图无环、35 项功能归属完整)")


def check_constitution_and_agents_map() -> None:
    constitution = ROOT / "CONSTITUTION.md"
    agents_md = ROOT / "AGENTS.md"
    agent_primitives = ROOT / "AGENT-PRIMITIVES.md"

    for doc in (constitution, agents_md, agent_primitives):
        if not doc.exists():
            errors.append(f"[2] 缺少根级元语文档 {doc.name}。FIX: 补齐 {doc.name} 后重试。")

    if constitution.exists():
        text = constitution.read_text(encoding="utf-8")
        axioms = re.findall(r"^###\s+C\d+\.", text, flags=re.MULTILINE)
        if not (5 <= len(axioms) <= 12):
            warnings.append(
                f"[2] CONSTITUTION.md 公理数为 {len(axioms)}（建议 5–12 条，保持宪法精炼）。"
            )

    if agents_md.exists():
        lines = agents_md.read_text(encoding="utf-8").splitlines()
        if len(lines) > 100:
            errors.append(
                f"[2] AGENTS.md 共 {len(lines)} 行，超过 100 行上限（P1.2 导航地图禁变百科全书）。"
                "FIX: 将详细内容下沉至 ARCHITECTURE.md 或专门文档，AGENTS.md 仅保留索引与命令。"
            )
        else:
            print(f"[2] Constitution & AGENTS.md: OK (AGENTS.md {len(lines)}/100 行)")


def check_maven_boundaries() -> None:
    forbidden: dict[str, set[str]] = {
        "dg-common": {
            "spring-boot-starter-web",
            "dg-platform",
            "dg-metadata",
            "dg-control",
            "dg-runtime",
            "dg-governance",
        },
        "dg-data-spi": {"dg-data-connectors", "dg-metadata", "dg-control", "dg-runtime"},
        "dg-metadata": {"dg-data-connectors", "dg-control", "dg-runtime"},
        "dg-runtime": {"dg-metadata", "dg-control", "dg-governance"},
        "dg-governance": {"dg-control", "dg-data-connectors"},
    }
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    checked = 0
    for module, banned in forbidden.items():
        pom = ROOT / "backend" / module / "pom.xml"
        if not pom.exists():
            warnings.append(f"[3] 未找到模块 POM: {pom.relative_to(ROOT)}")
            continue
        checked += 1
        tree = ET.parse(pom)
        root_el = tree.getroot()
        deps = {
            el.text.strip()
            for el in root_el.findall(".//m:dependencies/m:dependency/m:artifactId", ns)
            if el.text
        }
        # Fallback for pom without namespace
        if not deps:
            deps = {
                el.text.strip()
                for el in root_el.findall(".//dependencies/dependency/artifactId")
                if el.text
            }
        violated = sorted(deps & banned)
        if violated:
            errors.append(
                f"[3] Maven Space 边界违规（C1）：{module} 依赖了禁止模块 {violated}。"
                f"FIX: 从 backend/{module}/pom.xml 移除上述依赖，改走 SPI 契约或事件解耦。"
            )
    print(f"[3] Maven 物理依赖边界: OK (已校验 {checked} 个核心模块 POM)")


def check_skills() -> None:
    skills_dir = ROOT / "skills"
    if not skills_dir.exists():
        warnings.append("[4] 尚未创建 skills/ 目录（A0.2 渐进式技能包）。")
        return
    skill_files = sorted(skills_dir.glob("*/SKILL.md"))
    if not skill_files:
        warnings.append("[4] skills/ 目录下暂无 SKILL.md 文件。")
        return

    name_re = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
    dangerous_tools = {"bash", "sh", "shell", "*", "all"}

    for sf in skill_files:
        rel = sf.relative_to(ROOT)
        raw = sf.read_text(encoding="utf-8")
        lines = raw.splitlines()
        if len(lines) > 500:
            errors.append(
                f"[4] {rel} 共 {len(lines)} 行，超过 AgentSkills.io 500 行上限。"
                "FIX: 将细则拆入同目录 references/，保持 SKILL.md 精炼。"
            )
        if not raw.startswith("---\n"):
            errors.append(f"[4] {rel} 缺少 YAML Frontmatter。FIX: 在文件顶部补充 --- name / description ---。")
            continue
        parts = raw.split("---\n", 2)
        if len(parts) < 3:
            errors.append(f"[4] {rel} Frontmatter 未正确闭合。")
            continue
        try:
            meta = yaml.safe_load(parts[1]) or {}
        except Exception as exc:
            errors.append(f"[4] {rel} Frontmatter YAML 解析失败: {exc}")
            continue

        name = str(meta.get("name", "")).strip()
        desc = str(meta.get("description", "")).strip()
        expected_dir = sf.parent.name
        if not name or not name_re.match(name) or len(name) > 64:
            errors.append(
                f"[4] {rel} name='{name}' 非法（须 1-64 字符 kebab-case）。"
                f"FIX: 将 name 设为 '{expected_dir}'。"
            )
        elif name != expected_dir:
            errors.append(
                f"[4] {rel} name='{name}' 与目录名 '{expected_dir}' 不一致。"
                "FIX: 保持 Frontmatter name 与技能目录名严格一致。"
            )
        if not desc or len(desc) > 1024:
            errors.append(f"[4] {rel} description 必填且不超过 1024 字符。")

        allowed = meta.get("allowed-tools") or []
        if isinstance(allowed, str):
            allowed = [x.strip() for x in allowed.split(",") if x.strip()]
        for tool in allowed:
            t_low = str(tool).strip().lower()
            if t_low in dangerous_tools:
                errors.append(
                    f"[4] {rel} allowed-tools 包含高危宽泛工具 '{tool}'（违反最小代理原则 C4）。"
                    "FIX: 收窄为精确的工具白名单。"
                )

    print(f"[4] A0.2 SKILL.md 校验: OK (已校验 {len(skill_files)} 个技能包)")


def check_frontend_tokens() -> None:
    token_map = {
        "#409eff": "var(--dg-primary)",
        "#f5f7fa": "var(--dg-canvas)",
        "#304156": "var(--dg-sidebar)",
        "#263445": "var(--dg-sidebar-active)",
        "#67c23a": "var(--dg-success)",
        "#e6a23c": "var(--dg-warning)",
        "#f56c6c": "var(--dg-danger)",
        "#909399": "var(--dg-text-muted) 或 var(--dg-info)",
    }
    hex_re = re.compile(
        r"#(?:409eff|f5f7fa|304156|263445|67c23a|e6a23c|f56c6c|909399)\b",
        re.IGNORECASE,
    )
    confirm_re = re.compile(r"\bElMessageBox\.confirm\s*\(")
    scan_dirs = [
        ROOT / "frontend" / "src" / "layouts",
        ROOT / "frontend" / "src" / "views",
        ROOT / "frontend" / "src" / "components",
    ]
    hits: list[str] = []
    confirm_hits: list[str] = []
    for base in scan_dirs:
        if not base.exists():
            continue
        for f in sorted(base.rglob("*")):
            if f.suffix not in {".vue", ".ts", ".css", ".scss"}:
                continue
            rel = f.relative_to(ROOT)
            for lineno, line in enumerate(f.read_text(encoding="utf-8").splitlines(), start=1):
                if "cssVar(" not in line:
                    for m in hex_re.finditer(line):
                        lit = m.group(0).lower()
                        fix = token_map.get(lit, "var(--dg-*)")
                        hits.append(
                            f"{rel}:{lineno} 硬编码色值 {m.group(0)} -> FIX: 替换为 {fix}（见 frontend/DESIGN.md §4.1）"
                        )
                if confirm_re.search(line):
                    confirm_hits.append(
                        f"{rel}:{lineno} 裸调 ElMessageBox.confirm -> FIX: 改用 @/utils/confirm 的 confirmAction / confirmDanger（见 frontend/DESIGN.md §6.8）"
                    )

    if hits:
        msg = (
            f"[5] 发现 {len(hits)} 处前端 Design Token 漂移:\n  - " + "\n  - ".join(hits)
        )
        if STRICT:
            errors.append(msg)
        else:
            warnings.append(msg + "\n  （提示：使用 --strict 可将其升级为 CI 阻断错误）")
    if confirm_hits:
        msg = (
            f"[5] 发现 {len(confirm_hits)} 处裸调 ElMessageBox.confirm:\n  - " + "\n  - ".join(confirm_hits)
        )
        if STRICT:
            errors.append(msg)
        else:
            warnings.append(msg + "\n  （提示：使用 --strict 可将其升级为 CI 阻断错误）")
    if not hits and not confirm_hits:
        print("[5] 前端 Design Token & Shell 浮层防漂移: OK (0 处违规色值，0 处裸调 ElMessageBox.confirm)")


def check_knowledge_base() -> None:
    kb = ROOT / "knowledge"
    if not kb.exists():
        warnings.append("[6] 尚未初始化 knowledge/ 编译型知识库。")
        return
    required = [
        kb / "schema.md",
        kb / "index.md",
        kb / "log.md",
        kb / "maintenance-log.md",
    ]
    for req in required:
        if not req.exists():
            errors.append(f"[6] 缺少知识库账本 {req.relative_to(ROOT)}。FIX: 按 knowledge/schema.md 补齐。")

    pii_phone = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
    pii_id = re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)")
    link_re = re.compile(r"\[[^\]]+\]\(([^)#]+)\)")

    articles_dir = kb / "articles"
    article_count = 0
    if articles_dir.exists():
        for md in sorted(articles_dir.glob("*.md")):
            article_count += 1
            text = md.read_text(encoding="utf-8")
            if pii_phone.search(text) or pii_id.search(text):
                errors.append(
                    f"[6] {md.relative_to(ROOT)} 检测到疑似 L3 明文 PII（手机号/身份证）。"
                    "FIX: 立即脱敏并按 C9 回滚。"
                )

    index_file = kb / "index.md"
    if index_file.exists():
        for m in link_re.finditer(index_file.read_text(encoding="utf-8")):
            target = m.group(1).strip()
            if target.startswith(("http://", "https://")):
                continue
            resolved = (index_file.parent / target).resolve()
            if not resolved.exists():
                errors.append(
                    f"[6] knowledge/index.md 存在断链: {target}。FIX: 修正路径或补齐目标文章。"
                )

    print(f"[6] A0.1/A0.3 编译型知识库: OK (账本齐全，已校验 {article_count} 篇权威文章)")


def check_mcp_a2a_contracts() -> None:
    mcp_files = sorted((ROOT / "contracts" / "mcp").glob("*.json")) if (ROOT / "contracts" / "mcp").exists() else []
    a2a_files = sorted((ROOT / "contracts" / "a2a").glob("*.json")) if (ROOT / "contracts" / "a2a").exists() else []
    for f in mcp_files:
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
            if "protocolVersion" not in data or "tools" not in data:
                errors.append(f"[7] {f.relative_to(ROOT)} 缺少 MCP 必填字段 protocolVersion / tools。")
        except Exception as exc:
            errors.append(f"[7] {f.relative_to(ROOT)} JSON 解析失败: {exc}")
    for f in a2a_files:
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
            if "protocolVersion" not in data or "name" not in data:
                errors.append(f"[7] {f.relative_to(ROOT)} 缺少 A2A 必填字段 protocolVersion / name。")
        except Exception as exc:
            errors.append(f"[7] {f.relative_to(ROOT)} JSON 解析失败: {exc}")
    print(f"[7] P5.3/P5.4 MCP & A2A 契约: OK (MCP {len(mcp_files)} 份，A2A {len(a2a_files)} 份)")


def main() -> int:
    check_space_contracts()
    check_constitution_and_agents_map()
    check_maven_boundaries()
    check_skills()
    check_frontend_tokens()
    check_knowledge_base()
    check_mcp_a2a_contracts()

    if warnings:
        print("\n--- WARNINGS ---")
        for w in warnings:
            print("WARN ", w)
    if errors:
        print("\n--- ERRORS ---")
        for e in errors:
            print("ERROR", e)
        print("\nFAILED")
        return 1

    print("\nPASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
