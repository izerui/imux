#!/usr/bin/env bash
#
# 开发用的两条命令。沙箱 IDE 起一次就别关，改完代码 reload 一下即可。
#
#   ./dev.sh ide      起沙箱 IDE（长驻，Ctrl+C 会关掉它）
#   ./dev.sh reload   重新打包，沙箱里的插件自动重载
#
# 为什么不用重启：本插件的扩展点全是 dynamic，平台可以热卸载重装。
# 详见 README「开发时不要反复重启沙箱」。
set -euo pipefail

# 允许在任何目录下调用
cd "$(dirname "${BASH_SOURCE[0]}")"

case "${1:-}" in
  ide)
    echo "▶ 起沙箱 IDE。起来之后别关这个终端，改完代码在另一个终端跑：./dev.sh reload"
    exec ./gradlew runIde
    ;;

  reload)
    # prepareSandbox 而非 buildPlugin：自动重载盯的是沙箱 plugins 目录，
    # 而它正是往那儿写文件的任务，buildPlugin 还要多打一个 zip
    ./gradlew prepareSandbox
    echo
    echo "✔ 已更新沙箱插件，切回沙箱 IDE 即可看到改动（无需重启）。"
    echo "  注意：重载会杀掉所有正在跑的会话终端。"
    ;;

  test)
    exec ./gradlew test
    ;;

  *)
    cat <<'USAGE'
用法：./dev.sh <命令>

  ide      起沙箱 IDE（长驻，Ctrl+C 会关掉它）
  reload   重新打包，沙箱里的插件自动重载 —— 改完代码跑这个，不用重启沙箱
  test     跑测试

典型流程：
  终端 A   ./dev.sh ide        # 起一次，之后一直开着
  终端 B   ./dev.sh reload     # 每次改完代码跑一次
USAGE
    exit 1
    ;;
esac
