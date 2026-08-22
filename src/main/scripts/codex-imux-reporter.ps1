# codex 的 SessionStart hook：把「这个终端标签现在在跑哪个会话」报回 imux。
#
# 为什么需要它：Windows 上读不到别的进程打开的文件句柄（要 Sysinternals handle.exe，
# 不自带、要管理员权限），而 codex 没有运行态文件（state_5.sqlite 的 threads 表
# 无 pid 字段）。两条观测面全断，只能由 codex 自己说。
#
# 本脚本由 codex 执行，因此继承 codex 进程的环境变量——IMUX_TAB / IMUX_REPORT_URL /
# IMUX_TOKEN 都是这么拿到的（实测 hook 子进程确实继承得到）。
#
# 报文体与 pi 扩展发出的**同一套语法**（type / tabId / sessionId / cwd），
# 因此服务端复用同一个 parsePiReport；只有路径是并列的另一条，
# 好让 pi 那一侧一个字节都不用动。
#
# 缺了 cwd 这条上报会被整条丢弃：服务端靠它判断这个会话属于哪个项目。
$ErrorActionPreference = 'Stop'
try {
    $tab = $env:IMUX_TAB
    $url = $env:IMUX_REPORT_URL
    $token = $env:IMUX_TOKEN
    if (-not $tab -or -not $url -or -not $token) { exit 0 }

    $payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
    $sessionId = $payload.session_id
    $cwd = $payload.cwd
    if (-not $sessionId -or -not $cwd) { exit 0 }

    $body = @{
        type      = 'session_start'
        tabId     = $tab
        sessionId = $sessionId
        cwd       = $cwd
    } | ConvertTo-Json -Compress

    Invoke-RestMethod -Method Post -Uri "$url" -Body $body `
        -ContentType 'application/json' -Headers @{ 'x-imux-token' = $token } | Out-Null
} catch {
    # 上报失败只是标签不自动跟随，绝不能让 codex 的会话启动受影响：
    # hook 退出码非 0 时 codex 会在会话里显示报错。
    exit 0
}
exit 0
