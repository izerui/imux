#!/usr/bin/env bash
#
# 开发快捷入口：上下方向键选择，回车执行。
#
# README 保持使用原始 Gradle 命令，不依赖本脚本。
set -euo pipefail

# 允许在任何目录下调用
cd "$(dirname "${BASH_SOURCE[0]}")"

print_command() {
  printf '\n$'
  printf ' %q' "$@"
  printf '\n\n'
}

cursor_hidden=0

restore_cursor() {
  if [[ "$cursor_hidden" -eq 1 ]]; then
    printf '\033[?25h'
    cursor_hidden=0
  fi
}

trap restore_cursor EXIT
trap 'exit 130' INT TERM

choose_interactive_action() {
  local -a labels=(
    "启动沙箱 IDEA"
    "运行测试"
    "打包插件"
    "退出"
  )
  local -a commands=(
    "./gradlew runIde"
    "./gradlew test"
    "./gradlew test buildPlugin"
    ""
  )
  local -a actions=("ide" "test" "package" "exit")
  local selected=0
  local rendered=0
  local key
  local sequence
  local i

  printf '\033[?25l'
  cursor_hidden=1

  while true; do
    if [[ "$rendered" -eq 1 ]]; then
      printf '\033[8A'
    fi

    printf '\033[2K\r请选择要执行的操作：\n'
    printf '\033[2K\r\n'
    for i in "${!labels[@]}"; do
      if [[ "$i" -eq "$selected" ]]; then
        printf '\033[2K\r\033[1;36m> %s\033[0m' "${labels[$i]}"
        if [[ -n "${commands[$i]}" ]]; then
          printf '\033[1;36m    %s\033[0m' "${commands[$i]}"
        fi
        printf '\n'
      else
        printf '\033[2K\r  %s' "${labels[$i]}"
        if [[ -n "${commands[$i]}" ]]; then
          printf '    %s' "${commands[$i]}"
        fi
        printf '\n'
      fi
    done
    printf '\033[2K\r\n'
    printf '\033[2K\r↑/↓ 选择，Enter 执行，q 退出\n'
    rendered=1

    if ! IFS= read -rsn1 key; then
      action="exit"
      break
    fi

    case "$key" in
      $'\x1b')
        sequence=""
        IFS= read -rsn2 sequence || true
        case "$sequence" in
          '[A')
            selected=$(((selected + ${#labels[@]} - 1) % ${#labels[@]}))
            ;;
          '[B')
            selected=$(((selected + 1) % ${#labels[@]}))
            ;;
        esac
        ;;
      '')
        action="${actions[$selected]}"
        break
        ;;
      q|Q)
        action="exit"
        break
        ;;
    esac
  done

  restore_cursor
  printf '\n'
}

if [[ "$#" -ne 0 ]]; then
  echo "dev.sh 不接受参数，请直接运行 ./dev.sh" >&2
  exit 1
fi

if [[ ! -t 0 || ! -t 1 ]]; then
  echo "dev.sh 需要交互式终端，请直接运行 ./dev.sh" >&2
  exit 1
fi

action=""
choose_interactive_action

case "$action" in
  ide)
    echo "▶ 起沙箱 IDE。代码改动后请 Ctrl+C 停止，再重新运行 ./dev.sh。"
    print_command ./gradlew runIde
    exec ./gradlew runIde
    ;;

  test)
    print_command ./gradlew test
    exec ./gradlew test
    ;;

  package)
    # 先跑测试再打包：装到正式 IDE 上的东西不该没过测试
    print_command ./gradlew test buildPlugin
    ./gradlew test buildPlugin
    zip=$(ls -t build/distributions/*.zip 2>/dev/null | head -1)
    echo
    echo "✔ 已打包：$(pwd)/${zip#./}"
    echo
    echo "  安装：Settings → Plugins → 齿轮 → Install Plugin from Disk… → 选上面这个 zip → 重启 IDE"
    echo "  注意：插件含 sqlite-jdbc，无法热卸载，每次更新都要重启 IDE。"
    ;;

  exit)
    exit 0
    ;;
esac
