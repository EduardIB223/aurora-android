Add-Type -AssemblyName System.Drawing

$src = 'C:\avc\proxyclient\src-tauri\icons\icon.png'
$res = 'C:\sfa\app\src\main\res'

function Resize-Png($srcPath, $dstPath, $size) {
    $img = [System.Drawing.Image]::FromFile($srcPath)
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.DrawImage($img, 0, 0, $size, $size)
    $dir = Split-Path $dstPath -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $bmp.Save($dstPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose(); $img.Dispose()
    Write-Output "  $dstPath ($size px)"
}

# Legacy launcher icons
$launcher = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
Write-Output "=== legacy launcher icons ==="
foreach ($d in $launcher.Keys) {
    Resize-Png $src "$res\mipmap-$d\ic_launcher.png" $launcher[$d]
    Resize-Png $src "$res\mipmap-$d\ic_launcher_round.png" $launcher[$d]
}

# Adaptive icon foreground (108dp canvas, full-bleed art masked by the system)
$fg = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }
Write-Output "=== adaptive foreground ==="
foreach ($d in $fg.Keys) {
    Resize-Png $src "$res\drawable-$d\ic_launcher_foreground.png" $fg[$d]
}

Write-Output "done"
