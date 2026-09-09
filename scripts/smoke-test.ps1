$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ProxyRoot = 'D:\MC\server\[25565] 代理端'
$Javac = 'D:\Java\jdk-25\bin\javac.exe'
$Java = 'D:\Java\jdk-25\bin\java.exe'
$VelocityJar = Join-Path $ProxyRoot 'velocity-3.5.0-SNAPSHOT-605.jar'
$MainClasses = Join-Path $ProjectRoot 'out\classes'
$TestClasses = Join-Path $ProjectRoot 'out\test-classes'
$TestSource = Join-Path $ProjectRoot 'src\test\java\com\kael\playtime\SmokeTest.java'

if (-not (Test-Path -LiteralPath $MainClasses)) {
    throw '请先运行 build.ps1'
}

$libraries = Get-ChildItem -LiteralPath (Join-Path $ProxyRoot 'libraries') -Recurse -Filter '*.jar' |
    ForEach-Object { $_.FullName }
$classpath = (@($VelocityJar, $MainClasses) + $libraries) -join ';'

if (Test-Path -LiteralPath $TestClasses) {
    [System.IO.Directory]::Delete($TestClasses, $true)
}
New-Item -ItemType Directory -Force -Path $TestClasses | Out-Null

& $Javac --release 21 -encoding UTF-8 -cp $classpath -d $TestClasses $TestSource
if ($LASTEXITCODE -ne 0) {
    throw '自检代码编译失败'
}

$runClasspath = (@($TestClasses, $MainClasses, $VelocityJar) + $libraries) -join ';'
& $Java -ea -cp $runClasspath com.kael.playtime.SmokeTest
if ($LASTEXITCODE -ne 0) {
    throw '自检失败'
}

Write-Output 'SmokeTest 通过。'
