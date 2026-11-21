param([string]$Cli)
$command = Get-Command $Cli -ErrorAction SilentlyContinue
if (!$command) { return }
if ($command.CommandType -notin 'Application','ExternalScript') { & $Cli --help; return }
try {
    $file = Get-Item -LiteralPath $command.Path -ErrorAction Stop
    $root = Join-Path $env:LOCALAPPDATA 'imux/cli-help-v1'
    $identity = '{0}:{1}:{2}' -f $file.FullName,$file.LastWriteTimeUtc.Ticks,$file.Length
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $key = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($identity))) } finally { $hash.Dispose() }
    $entry = Join-Path $root ($key + '.help')
    $cached = Get-Item -LiteralPath $entry -ErrorAction SilentlyContinue
    if ($cached -and $cached.Length -gt 0 -and $cached.LastWriteTimeUtc -gt [DateTime]::UtcNow.AddDays(-1)) {
        [IO.File]::ReadAllLines($entry); return
    }
} catch { & $Cli --help; return }
$lines = @(& $Cli --help)
if ($LASTEXITCODE -ne 0 -or !$lines.Count) { return }
$lines
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    $tmp = Join-Path $root ([Guid]::NewGuid().ToString())
    [IO.File]::WriteAllLines($tmp, [string[]]$lines)
    if ([IO.File]::Exists($entry)) { [IO.File]::Replace($tmp,$entry,$null) } else { [IO.File]::Move($tmp,$entry) }
} catch {} finally { if ($tmp) { Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue } }
