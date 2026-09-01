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

release_base() {
  git log --format='%H%x09%s' |
    awk -F '\t' '
      !found && ($2 ~ /^chore\(release\): 发布 [0-9]/ || $2 ~ /^发布 ?[0-9].*版本/ || $2 ~ /并发布 ?[0-9].*版本/) {
        print $1
        found = 1
      }
    '
}

plugin_version() {
  awk -F '"' '/^version = "[^"]+"/ { print $2; exit }' build.gradle.kts
}

html_change_notes() {
  awk '
    function escape_html(value) {
      gsub(/&/, "\\&amp;", value)
      gsub(/</, "\\&lt;", value)
      gsub(/>/, "\\&gt;", value)
      gsub(/\"/, "\\&quot;", value)
      gsub(/\047/, "\\&#39;", value)
      return value
    }
    BEGIN { print "<ul>" }
    /^- / {
      item = substr($0, 3)
      print "  <li>" escape_html(item) "</li>"
    }
    END { print "</ul>" }
  '
}

generate_change_notes() {
  local base
  local version
  local context_file="build/generated/release-context.txt"
  local notes_file="build/generated/change-notes.txt"
  local html_file="build/generated/change-notes.html"
  local generated

  command -v pi >/dev/null 2>&1 || {
    echo "无法生成修改日志：PATH 中找不到 pi。" >&2
    return 1
  }

  base=$(release_base)
  if [[ -z "$base" ]]; then
    echo "无法生成修改日志：Git 历史中找不到最近一次发布提交。" >&2
    return 1
  fi
  version=$(plugin_version)
  if [[ -z "$version" ]]; then
    echo "无法生成修改日志：build.gradle.kts 中找不到插件版本。" >&2
    return 1
  fi

  mkdir -p build/generated
  {
    printf '目标版本：%s\n' "$version"
    printf '发布基线：%s\n\n' "$base"
    printf '=== 提交记录 ===\n'
    git log --format='%h %s%n%b' "$base..HEAD"
    printf '\n=== 变化统计（包含尚未提交的工作区）===\n'
    git diff --stat "$base" -- .
    printf '\n=== 实际差异（包含尚未提交的工作区）===\n'
    git diff --no-ext-diff --unified=2 "$base" -- .
  } > "$context_file"

  echo "▶ 正在让 pi 根据最近一次发布以来的 Git 变化生成 ${version} 修改日志…"
  if ! generated=$(
    PI_SKIP_VERSION_CHECK=1 pi \
      --print \
      --no-session \
      --no-tools \
      --no-extensions \
      --no-skills \
      --no-prompt-templates \
      --no-context-files \
      --no-approve \
      --system-prompt \
      "你是 IntelliJ 插件发布编辑。根据输入的 Git 提交和实际差异，生成面向插件用户的简体中文修改日志。合并重复变化，说明用户得到的能力或修复；忽略纯测试、计划文档、格式化和内部重构，除非它们改变用户行为。不得臆造。只输出 1 到 8 行，每行严格使用 '- ' 开头；不要标题、序号、代码块、HTML 或解释。" \
      < "$context_file"
  ); then
    echo "pi 生成修改日志失败，已停止打包。" >&2
    return 1
  fi

  generated=$(printf '%s\n' "$generated" | awk 'NF { sub(/[[:space:]]+$/, ""); print }')
  if [[ -z "$generated" ]] ||
    [[ $(printf '%s\n' "$generated" | wc -l | tr -d ' ') -gt 8 ]] ||
    printf '%s\n' "$generated" | grep -qv '^- .\+'; then
    echo "pi 返回的修改日志格式无效，已停止打包：" >&2
    printf '%s\n' "$generated" >&2
    return 1
  fi

  printf '%s\n' "$generated" > "$notes_file"
  printf '%s\n' "$generated" | html_change_notes > "$html_file"

  echo
  echo "本次修改日志："
  printf '%s\n' "$generated"
  echo
  CHANGE_NOTES_FILE="$html_file"
}

verify_packaged_change_notes() {
  local zip=$1
  local notes_file=$2
  local unpacked
  local plugin_jar=""
  local candidate
  local plugin_xml
  local packaged_notes
  local note

  unpacked=$(mktemp -d "${TMPDIR:-/tmp}/imux-release.XXXXXX")
  unzip -q "$zip" -d "$unpacked"
  for candidate in "$unpacked"/*/lib/*.jar; do
    if unzip -Z1 "$candidate" 2>/dev/null |
      awk '$0 == "META-INF/plugin.xml" { found = 1 } END { exit !found }'; then
      plugin_jar="$candidate"
      break
    fi
  done
  if [[ -z "$plugin_jar" ]]; then
    rm -rf "$unpacked"
    echo "打包校验失败：ZIP 中找不到包含 META-INF/plugin.xml 的插件 JAR。" >&2
    return 1
  fi

  plugin_xml="$unpacked/plugin.xml"
  unzip -p "$plugin_jar" META-INF/plugin.xml > "$plugin_xml"
  packaged_notes=$(xmllint --xpath 'string(/idea-plugin/change-notes)' "$plugin_xml" 2>/dev/null || true)
  if [[ -z "$packaged_notes" ]]; then
    rm -rf "$unpacked"
    echo "打包校验失败：plugin.xml 中没有有效的 change-notes。" >&2
    return 1
  fi
  while IFS= read -r note; do
    note=${note#- }
    case "$packaged_notes" in
      *"$note"*) ;;
      *)
        rm -rf "$unpacked"
        echo "打包校验失败：ZIP 中缺少修改日志：$note" >&2
        return 1
        ;;
    esac
  done < "$notes_file"
  rm -rf "$unpacked"
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
    "打包插件"
    "退出"
  )
  local -a commands=(
    "./gradlew runIde"
    "./gradlew test buildPlugin"
    ""
  )
  local -a actions=("ide" "package" "exit")
  local selected=0
  local rendered=0
  local key
  local sequence
  local i

  printf '\033[?25l'
  cursor_hidden=1

  while true; do
    if [[ "$rendered" -eq 1 ]]; then
      printf '\033[7A'
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

  package)
    # 先生成并校验发布日志；失败时不能继续打出一个缺日志或沿用旧日志的 ZIP。
    CHANGE_NOTES_FILE=""
    generate_change_notes
    # 先跑测试再打包：装到正式 IDE 上的东西不该没过测试
    print_command ./gradlew test buildPlugin "-PchangeNotesFile=$CHANGE_NOTES_FILE"
    ./gradlew test buildPlugin "-PchangeNotesFile=$CHANGE_NOTES_FILE"
    zip="build/distributions/$(basename "$PWD")-$(plugin_version).zip"
    verify_packaged_change_notes "$zip" build/generated/change-notes.txt
    echo
    echo "✔ 已打包并验证修改日志：$(pwd)/${zip#./}"
    echo
    echo "  安装：Settings → Plugins → 齿轮 → Install Plugin from Disk… → 选上面这个 zip → 重启 IDE"
    echo "  注意：插件含 sqlite-jdbc，无法热卸载，每次更新都要重启 IDE。"
    ;;

  exit)
    exit 0
    ;;
esac
