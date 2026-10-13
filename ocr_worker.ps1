$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Storage.FileAccessMode, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime]
$asTaskMethod = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
function Await-WinRT($operation, $resultType) {
    $task = $asTaskMethod.MakeGenericMethod($resultType).Invoke($null, @($operation))
    $task.Wait()
    return $task.Result
}
function Emit-Json($object) {
    $json = $object | ConvertTo-Json -Depth 8 -Compress
    $asciiJson = [regex]::Replace($json, '[^\x00-\x7f]', { param($m) '\u' + ([int][char]$m.Value).ToString('x4') })
    [Console]::WriteLine($asciiJson)
}
$available = @([Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object { $_.LanguageTag })
$selected = @('zh-Hans-CN','zh-Hans','zh-CN','zh-Hant-TW','zh-TW') | Where-Object { $_ -in $available } | Select-Object -First 1
if (-not $selected) { $selected = $available | Where-Object { $_ -like 'zh-*' } | Select-Object -First 1 }
if (-not $selected) {
    Emit-Json @{ready=$false;available_languages=$available;error='未安装中文 OCR 组件。请在 Windows 设置 → 时间和语言 → 语言和区域 → 中文语言选项安装光学字符识别。当前语言无法可靠读取中文题目。'}
    exit 1
}
$language = New-Object Windows.Globalization.Language($selected)
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($language)
if ($null -eq $engine) {
    Emit-Json @{ready=$false;available_languages=$available;error='中文 OCR 引擎无法创建，请修复 Windows 中文光学字符识别组件。'}
    exit 1
}
Emit-Json @{ready=$true;language=$engine.RecognizerLanguage.LanguageTag;available_languages=$available}
while ($null -ne ($requestLine = [Console]::ReadLine())) {
    $stream = $null
    $bitmap = $null
    try {
        $request = $requestLine | ConvertFrom-Json
        if ($request.quit) { break }
        $timer = [System.Diagnostics.Stopwatch]::StartNew()
        $file = Await-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync([string]$request.path)) ([Windows.Storage.StorageFile])
        $stream = Await-WinRT ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
        $decoder = Await-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await-WinRT ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $result = Await-WinRT ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
        $lines = @()
        foreach ($line in $result.Lines) {
            $words = @()
            foreach ($word in $line.Words) {
                $r = $word.BoundingRect
                $words += @{text=$word.Text;x=$r.X;y=$r.Y;width=$r.Width;height=$r.Height}
            }
            $lines += @{text=$line.Text;words=$words}
        }
        $response = @{ok=$true;text=$result.Text;lines=$lines;ocr_ms=$timer.ElapsedMilliseconds}
    } catch {
        $response = @{ok=$false;error=$_.Exception.Message}
    } finally {
        if ($null -ne $bitmap) { $bitmap.Dispose() }
        if ($null -ne $stream) { $stream.Dispose() }
        $decoder = $null
        $file = $null
    }
    Emit-Json $response
}
