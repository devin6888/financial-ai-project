# 把项目根目录的 .env 读入当前 PowerShell 进程的环境变量
#
# 用法（在项目根目录，注意前面有个点，表示「点源」）：
#   . .\scripts\load-env.ps1
#   mvn test
#
# .env 已在 .gitignore 中，不会被提交。
# 本文件以「UTF-8 带 BOM」保存，否则 Windows PowerShell 5.1 会把中文解析成乱码而报语法错误。

$envFile = Join-Path $PSScriptRoot '..\.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    Write-Warning ("未找到 .env: " + $envFile + "  —— 请先创建，参见 README「环境变量（凭据）」")
    return
}

$count = 0
foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $idx = $trimmed.IndexOf('=')
    if ($idx -lt 1) { continue }
    $name = $trimmed.Substring(0, $idx).Trim()
    $value = $trimmed.Substring($idx + 1).Trim().Trim('"').Trim("'")
    Set-Item -Path ("Env:" + $name) -Value $value
    $count++
}

Write-Host ("已从 .env 加载 " + $count + " 个环境变量")
