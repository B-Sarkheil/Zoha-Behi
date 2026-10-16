Add-Type -AssemblyName System.Drawing
$src = 'D:\Behnam\Zoha&Behi\graphic\Logo\Zoha_Behi_logo.png'
$img = [System.Drawing.Image]::FromFile($src)
$sizes = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($density in $sizes.Keys) {
    $size = $sizes[$density]
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, $size, $size)
    $g.Dispose()
    $outDir = 'D:\Behnam\Zoha&Behi\app\src\main\res\mipmap-' + $density
    $bmp.Save($outDir + '\ic_launcher.png', [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host ("Generated {0}\ic_launcher.png ({1}x{1})" -f $outDir, $size)
}
$img.Dispose()