$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ProxyRoot = 'D:\MC\server\[25565] 代理端'
$Javac = 'D:\Java\jdk-25\bin\javac.exe'
$JarTool = 'D:\Java\jdk-25\bin\jar.exe'
$VelocityJar = Join-Path $ProxyRoot 'velocity-3.5.0-SNAPSHOT-605.jar'
$OutClasses = Join-Path $ProjectRoot 'out\classes'
$JarPath = Join-Path $ProjectRoot 'outputs\KaelorvynPlaytime-1.0.0.jar'
$PluginJar = Join-Path $ProxyRoot 'plugins\KaelorvynPlaytime-1.0.0.jar'

$libraries = Get-ChildItem -LiteralPath (Join-Path $ProxyRoot 'libraries') -Recurse -Filter '*.jar' |
    ForEach-Object { $_.FullName }
$classpath = (@($VelocityJar) + $libraries) -join ';'

if (Test-Path -LiteralPath $OutClasses) {
    [System.IO.Directory]::Delete($OutClasses, $true)
}
New-Item -ItemType Directory -Force -Path $OutClasses | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $JarPath) | Out-Null

$sources = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'src\main\java') -Recurse -Filter '*.java' |
    ForEach-Object { $_.FullName }

& $Javac --release 21 -encoding UTF-8 -cp $classpath -d $OutClasses $sources
if ($LASTEXITCODE -ne 0) {
    throw '编译失败'
}

Copy-Item -LiteralPath (Join-Path $ProjectRoot 'src\main\resources\velocity-plugin.json') -Destination $OutClasses -Force
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'src\main\resources\config.properties') -Destination $OutClasses -Force

if (Test-Path -LiteralPath $JarPath) {
    [System.IO.File]::Delete($JarPath)
}
& $JarTool --create --file $JarPath -C $OutClasses .
if ($LASTEXITCODE -ne 0) {
    throw '打包失败'
}

Copy-Item -LiteralPath $JarPath -Destination $PluginJar -Force
Write-Output "构建完成：$JarPath"
Write-Output "已部署：$PluginJar"
