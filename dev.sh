#!/usr/bin/env bash
#
# 开发用的两条命令。
#
#   ./dev.sh ide      起沙箱 IDE；改动代码后 Ctrl+C 停止并重新运行
#   ./dev.sh test     跑测试
#   ./dev.sh package  打出可安装的 zip
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

  package)
    # 先跑测试再打包：装到正式 IDE 上的东西不该没过测试
    ./gradlew test buildPlugin
    zip=$(ls -t build/distributions/*.zip 2>/dev/null | head -1)
    echo
    echo "✔ 已打包：$(pwd)/${zip#./}"
    echo
    echo "  安装：Settings → Plugins → 齿轮 → Install Plugin from Disk… → 选上面这个 zip → 重启 IDE"
    echo "  注意：插件含 sqlite-jdbc，无法热卸载，每次更新都要重启 IDE。"
    ;;

  *)
    cat <<'USAGE'
用法：./dev.sh <命令>

  ide      起沙箱 IDE；代码改动后停止并重新运行
  test     跑测试
  package  跑测试并打出可安装的 zip

典型流程：
  ./dev.sh ide
  # 修改代码后按 Ctrl+C
  ./dev.sh ide

装到正式 IDE：
  ./dev.sh package
USAGE
    exit 1
    ;;
esac
