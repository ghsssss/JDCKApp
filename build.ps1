# JDCKApp 手动构建脚本（无需 Gradle/AGP）
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$sdk  = "$env:LOCALAPPDATA\Android\Sdk"
$bt   = "$sdk\build-tools\34.0.0"
$plat = "$sdk\platforms\android-34\android.jar"
$out  = "$root\build"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path "$out\gen","$out\classes","$out\dex" -Force | Out-Null

Write-Host '== 1/6 aapt2 compile 资源 =='
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

Write-Host '== 2/6 aapt2 link 生成 R 与基础 APK =='
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $plat `
    --manifest "$root\AndroidManifest.xml" `
    --java "$out\gen" --min-sdk-version 24 --target-sdk-version 34 `
    --auto-add-overlay "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '== 3/6 javac 编译 =='
& javac --release 8 -encoding UTF-8 -classpath $plat -d "$out\classes" `
    "$out\gen\com\yao\jdck\R.java" "$root\java\com\yao\jdck\MainActivity.java"
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '== 4/6 d8 转 dex =='
& "$bt\d8.bat" --release --lib $plat --min-api 24 --output "$out\dex" `
    (Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '== 5/6 合并 classes.dex 并 zipalign =='
& python "$root\repack.py" "$out\base.apk" "$out\dex\classes.dex" "$out\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw '合并 dex 失败' }
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host '== 6/6 apksigner 签名 =='
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Path "$env:USERPROFILE\.android" -Force | Out-Null
    & keytool -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out "$root\JDCK.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }

Write-Host "构建完成: $root\JDCK.apk"
