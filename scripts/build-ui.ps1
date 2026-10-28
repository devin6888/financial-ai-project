# 构建前端并把产物输出到 src/main/resources/static（阶段 10 Task 7）。
#
# 前端是独立仓库：与本仓库平级，目录名 financial-ai-web（2026-10-28 从 web/ 拆出）。
# 为什么还需要这个脚本：Vite 的 outDir 指回本仓库的 src/main/resources/static，
# 所以「构建前端」不是独立产物，而是直接改本仓库的 resources —— 先跑它再 mvn package / spring-boot:run，
# 单端口就能用。
#
# 用法： .\scripts\build-ui.ps1
#
# 注意：PowerShell 5.1 按「无 BOM 就当日志 GBK」解析脚本，本文件必须存成 UTF-8 with BOM，
# 否则中文注释会让整个脚本语法错误（踩过）。

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$web = Join-Path (Split-Path -Parent $root) 'financial-ai-web'

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw '找不到 npm：先安装 Node.js 22+（本项目实测 Node 24）'
}

if (-not (Test-Path (Join-Path $web 'package.json'))) {
    throw ('找不到前端仓库：' + $web + '（期望与 financial-ai-project 平级，名字 financial-ai-web）')
}

# 清掉上一次的打包副本：mvn 的 copy-resources 只增不删，会留下旧 hash 的 chunk 进 jar
$stale = Join-Path $root 'target/classes/static'
if (Test-Path $stale) {
    Remove-Item $stale -Recurse -Force
}

Push-Location $web
try {
    if (-not (Test-Path (Join-Path $web 'node_modules'))) {
        Write-Host ('首次构建：安装依赖（走 ' + $web + '\.npmrc 里的镜像源）…')
        npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'npm ci 失败' }
    }

    npm run build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
} finally {
    Pop-Location
}

$index = Join-Path $root 'src/main/resources/static/index.html'
if (-not (Test-Path $index)) {
    throw '构建完成但没有找到 src/main/resources/static/index.html（检查 vite.config.ts 的 build.outDir）'
}

Write-Host ('前端产物已就绪：' + $index)
Write-Host '下一步： mvn spring-boot:run  然后打开 http://localhost:8080'