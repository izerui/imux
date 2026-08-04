#!/usr/bin/env bash
#
# 开发用的两条命令。
#
#   ./dev.sh ide    起沙箱 IDE；改动代码后 Ctrl+C 停止并重新运行
#   ./dev.sh test   跑测试
set -euo pipefail

# 允许在任何目录下调用
cd "$(dirname "${BASH_SOURCE[0]}")"

case "${1:-}" in
  ide)
    echo "▶ 起沙箱 IDE。代码改动后请 Ctrl+C 停止，再重新运行 ./dev.sh ide。"
    exec ./gradlew runIde
    ;;

  test)
    exec ./gradlew test
    ;;

  *)
    cat <<'USAGE'
用法：./dev.sh <命令>

  ide    起沙箱 IDE；代码改动后停止并重新运行
  test   跑测试

典型流程：
  ./dev.sh ide
  # 修改代码后按 Ctrl+C
  ./dev.sh ide
USAGE
    exit 1
    ;;
esac
