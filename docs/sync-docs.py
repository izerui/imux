#!/usr/bin/env python3
"""
同步 docs/ 下的 Claude Code、Codex、Pi 官方文档快照。

用法：python3 docs/sync-docs.py [claude-code] [codex] [pi]（不带参数则全部同步）

每个快照先完整下载到临时目录，全部成功后才替换 docs/ 里的旧内容；
任何一页下载失败都会中止该快照，旧快照原样保留。
"""

import concurrent.futures
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

DOCS = Path(__file__).resolve().parent
LINK = re.compile(r"\((https://[^)\s]+)\)")


def fetch(url: str, attempts: int = 10) -> tuple[int, bytes]:
    """返回 (HTTP 状态码, 正文)；不跟随重定向，网络错误按次重试。"""
    last = ""
    for attempt in range(attempts):
        result = subprocess.run(
            ["curl", "-sS", "-m", "60", "-w", "\n%{http_code}", url],
            capture_output=True,
        )
        if result.returncode == 0:
            body, _, code = result.stdout.rpartition(b"\n")
            status = int(code)
            if status < 500:
                return status, body
            last = f"HTTP {status}"
        else:
            last = result.stderr.decode(errors="replace").strip()
        time.sleep(min(1 + attempt * 2, 10))
    raise RuntimeError(f"{url}: {last}")


def fetch_ok(url: str) -> bytes:
    status, body = fetch(url)
    if status != 200:
        raise RuntimeError(f"{url}: HTTP {status}")
    return body


def download_all(jobs: dict[Path, str]) -> dict[Path, int]:
    """并发下载 {目标路径: URL}，返回各自状态码；200 之外的不落盘。"""

    def one(item: tuple[Path, str]) -> tuple[Path, int]:
        target, url = item
        status, body = fetch(url)
        if status == 200:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(body)
        return target, status

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        return dict(pool.map(one, jobs.items()))


def replace(target: Path, staging: Path, keep: tuple[str, ...] = ("README.md",)) -> None:
    for name in keep:
        if (target / name).exists():
            shutil.copy2(target / name, staging / name)
    shutil.rmtree(target, ignore_errors=True)
    shutil.move(str(staging), str(target))
    # mkdtemp 建的目录是 0700，恢复成普通目录权限
    target.chmod(0o755)


def sync_claude_code(staging: Path) -> None:
    base = "https://code.claude.com/docs/"
    index = fetch_ok(base + "llms.txt")
    (staging / "llms.txt").write_bytes(index)
    (staging / "llms-full.txt").write_bytes(fetch_ok(base + "llms-full.txt"))

    slugs = sorted(
        {
            url.removeprefix(base + "en/")
            for url in LINK.findall(index.decode())
            if url.startswith(base + "en/") and url.endswith(".md")
        }
    )
    en = download_all({staging / "en" / slug: base + "en/" + slug for slug in slugs})
    # 索引里个别页面已迁出 code.claude.com（如 claude-tag 307 到 claude.com），不再是 Markdown
    moved = sorted(path.name for path, status in en.items() if status in (301, 302, 307, 308))
    failed = [path for path, status in en.items() if status not in (200, 301, 302, 307, 308)]
    if failed:
        raise RuntimeError(f"英文页面下载失败：{failed}")
    if moved:
        print(f"claude-code：已迁出站外、跳过 {moved}")
    slugs = [slug for slug in slugs if en[staging / "en" / slug] == 200]

    # 中文站对没有译文的页面返回重定向而不是 Markdown，记入 zh-CN-missing.txt。
    zh = download_all({staging / "zh-CN" / slug: base + "zh-CN/" + slug for slug in slugs})
    missing = sorted(
        base + "zh-CN/" + path.relative_to(staging / "zh-CN").as_posix()
        for path, status in zh.items()
        if status != 200
    )
    (staging / "zh-CN-missing.txt").write_text("".join(url + "\n" for url in missing), encoding="utf-8")
    print(f"claude-code：英文 {len(slugs)} 页，中文 {len(slugs) - len(missing)} 页，缺中文 {len(missing)} 页")


def sync_codex(staging: Path) -> None:
    site = "https://learn.chatgpt.com/"
    # /llms.txt 会 307 到 /docs/llms.txt，直接取目标地址
    index = fetch_ok(site + "docs/llms.txt")
    (staging / "llms.txt").write_bytes(index)
    # llms-full.txt 是 pages/ 的拼接，大量章节只有标题；按主题整理的合集用 pages/docs/codex-manual.md
    (staging / "llms-full.txt").write_bytes(fetch_ok(site + "docs/llms-full.txt"))

    jobs = {}
    for url in LINK.findall(index.decode()):
        path = url.split("?", 1)[0]
        # `?surface=cli/ide` 是同一页的不同视图，只存一份；外站链接与 llms 索引不算独立页面
        if path.startswith(site) and path.endswith(".md"):
            jobs[staging / "pages" / path.removeprefix(site)] = path
    results = download_all(jobs)
    # 资源索引、视频页等条目没有 Markdown 版本，返回 404
    skipped = sorted(jobs[path] for path, status in results.items() if status == 404)
    failed = [path for path, status in results.items() if status not in (200, 404)]
    if failed:
        raise RuntimeError(f"Codex 页面下载失败：{failed}")
    if skipped:
        print(f"codex：无 Markdown 版本、跳过 {skipped}")
    print(f"codex：{len(jobs) - len(skipped)} 个独立页面")


# 只收 coding-agent 包里给使用者的参考资料：README、官方用户文档与官方示例。
# 仓库根与其它包（agent、ai、tui 等）的 README、各包 CHANGELOG、agent/docs 的设计稿、
# experimental、test fixtures、benchmark、native 构建说明都不收。
PI_FILES = ("coding-agent/README.md",)
PI_TREES = ("coding-agent/docs", "coding-agent/examples")


def sync_pi(staging: Path) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        clone = Path(tmp) / "pi"
        result = None
        for attempt in range(10):
            result = subprocess.run(
                ["git", "clone", "-q", "--depth", "1", "https://github.com/earendil-works/pi.git", str(clone)],
                capture_output=True,
            )
            if result.returncode == 0:
                break
            shutil.rmtree(clone, ignore_errors=True)
            time.sleep(min(2 + attempt * 2, 10))
        else:
            raise RuntimeError(f"克隆 Pi 仓库失败：{result.stderr.decode(errors='replace').strip() if result else ''}")

        commit = subprocess.run(
            ["git", "-C", str(clone), "rev-parse", "HEAD"], capture_output=True, text=True, check=True
        ).stdout.strip()
        repo = staging / "repo"
        packages = clone / "packages"
        files = [packages / name for name in PI_FILES]
        for tree in PI_TREES:
            files += [path for path in (packages / tree).rglob("*.md") if "node_modules" not in path.parts]
        for source in files:
            relative = source.relative_to(clone)
            if relative.parts[0] == "packages":
                relative = Path(*relative.parts[1:])
            (repo / relative).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, repo / relative)
        (staging / "COMMIT").write_text(commit + "\n", encoding="utf-8")
        print(f"pi：{len(files)} 个 Markdown 文件，commit {commit}")


SOURCES = {"claude-code": sync_claude_code, "codex": sync_codex, "pi": sync_pi}


def main() -> None:
    names = sys.argv[1:] or list(SOURCES)
    for name in names:
        target = DOCS / name
        staging = Path(tempfile.mkdtemp(prefix=f"imux-docs-{name}-"))
        try:
            SOURCES[name](staging)
        except Exception:
            shutil.rmtree(staging, ignore_errors=True)
            raise
        replace(target, staging)


if __name__ == "__main__":
    main()
