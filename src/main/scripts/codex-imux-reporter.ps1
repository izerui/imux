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

    # 报文体必须自己编成 UTF-8 字节再发。
    #
    # Windows PowerShell 5.1 在 -ContentType 不带 charset 时，把字符串 -Body 按
    # ISO-8859-1 编码发出（PowerShell 7 才换成 UTF-8）。cwd 里只要有一个非 ASCII
    # 字符——`C:\Users\刘宇华\...`、带重音的用户名——就会被打成 `?`，而服务端做的是
    # **精确字符串比较**，这条上报于是永远匹配不上任何项目，被整条丢弃且不报错。
    #
    # 传字节数组而不是往 -ContentType 里塞 charset：前者绕开整个字符串编码环节，
    # 不依赖某个版本怎么解读 charset。注意 Content-Type 只能走 -ContentType 形参，
    # 塞进 -Headers 哈希表在 5.1 上无效。
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)

    # -TimeoutSec 不是可选的：hook 是 codex **会话启动路径上的同步步骤**，
    # 这一句卡住就是会话起不来——越过了「上报失败只该让标签不跟随」这条线。
    # 对端是 IDE 内置的本机 HTTP 服务，正常是毫秒级；真卡住多半是 IDE 正忙或已退出，
    # 那时等下去也不会有结果。5 秒之后放弃，走 catch 里的 exit 0。
    Invoke-RestMethod -Method Post -Uri "$url" -Body $bytes `
        -ContentType 'application/json; charset=utf-8' `
        -TimeoutSec 5 `
        -Headers @{ 'x-imux-token' = $token } | Out-Null
} catch {
    # 上报失败只是标签不自动跟随，绝不能让 codex 的会话启动受影响：
    # hook 退出码非 0 时 codex 会在会话里显示报错。
    exit 0
}
exit 0
