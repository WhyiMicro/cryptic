# Cryptic's Media Controller bridge. Reads Windows' media sessions (the ones the volume flyout shows)
# and prints the current one as a line of JSON whenever it changes ("S {...}"), its cover art as a
# PNG in base64 ("A ..."), and errors ("E ..."). Reads commands on stdin: toggle, next, prev,
# seek <ms>. Exits when stdin closes, so it never outlives the game.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
$out = [Console]::Out

function Say([string]$line) {
	$out.WriteLine($line)
	$out.Flush()
}

try {
	Add-Type -AssemblyName System.Runtime.WindowsRuntime
	Add-Type -AssemblyName System.Drawing
	$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
		$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
	})[0]
	[void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
	[void][Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
	$asStreamForRead = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@([Windows.Storage.Streams.IInputStream]))
} catch {
	Say ("E unsupported " + $_.Exception.Message)
	exit 1
}

function Await($operation, [Type]$type) {
	$task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($operation))
	[void]$task.Wait(5000)
	$task.Result
}

$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]

# Console.In reads synchronously even when asked not to; a reader of our own doesn't.
$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
$pending = $stdin.ReadLineAsync()

$lastState = ''
$artKey = $null
$artTries = 0

function Send-Art($props) {
	$ras = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
	# PowerShell won't cast the WinRT stream itself; reflection lets the runtime do it.
	$stream = $asStreamForRead.Invoke($null, @($ras))
	$image = [System.Drawing.Image]::FromStream($stream)
	try {
		# A square from the middle: video thumbnails are wide.
		$side = [Math]::Min($image.Width, $image.Height)
		$source = New-Object System.Drawing.Rectangle ([int](($image.Width - $side) / 2)), ([int](($image.Height - $side) / 2)), $side, $side
		$bitmap = New-Object System.Drawing.Bitmap 96, 96
		$g = [System.Drawing.Graphics]::FromImage($bitmap)
		$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
		$g.DrawImage($image, (New-Object System.Drawing.Rectangle 0, 0, 96, 96), $source, [System.Drawing.GraphicsUnit]::Pixel)
		$g.Dispose()
		$memory = New-Object System.IO.MemoryStream
		$bitmap.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
		$bitmap.Dispose()
		Say ("A " + [Convert]::ToBase64String($memory.ToArray()))
	} finally {
		$image.Dispose()
		$stream.Dispose()
	}
}

while ($true) {
	try {
		if ($pending.IsCompleted) {
			$command = $pending.Result
			if ($null -eq $command) { break }
			$pending = $stdin.ReadLineAsync()
			$session = $manager.GetCurrentSession()
			if ($null -ne $session) {
				if ($command -eq 'toggle') { [void](Await ($session.TryTogglePlayPauseAsync()) ([bool])) }
				elseif ($command -eq 'next') { [void](Await ($session.TrySkipNextAsync()) ([bool])) }
				elseif ($command -eq 'prev') { [void](Await ($session.TrySkipPreviousAsync()) ([bool])) }
				elseif ($command -match '^seek (\d+)$') {
					[void](Await ($session.TryChangePlaybackPositionAsync([long]$matches[1] * 10000)) ([bool]))
				}
			}
		}

		$session = $manager.GetCurrentSession()
		if ($null -eq $session) {
			$state = '{"none":true}'
		} else {
			$props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
			$info = $session.GetPlaybackInfo()
			$timeline = $session.GetTimelineProperties()
			$state = [ordered]@{
				app = [string]$session.SourceAppUserModelId
				title = [string]$props.Title
				artist = [string]$props.Artist
				album = [string]$props.AlbumTitle
				status = [string]$info.PlaybackStatus
				position = [long]$timeline.Position.TotalMilliseconds
				end = [long]$timeline.EndTime.TotalMilliseconds
				updated = [long]$timeline.LastUpdatedTime.ToUnixTimeMilliseconds()
				canSeek = [bool]$info.Controls.IsPlaybackPositionEnabled
				canNext = [bool]$info.Controls.IsNextEnabled
				canPrev = [bool]$info.Controls.IsPreviousEnabled
			} | ConvertTo-Json -Compress

			# Some players fill the cover in a moment after the title, so keep trying for a while.
			$key = $session.SourceAppUserModelId + '|' + $props.Title + '|' + $props.Artist
			if ($key -ne $artKey) {
				$artKey = $key
				$artTries = 0
				Say 'A -'
			}
			if ($artTries -ge 0 -and $artTries -lt 8) {
				$artTries++
				if ($null -ne $props.Thumbnail) {
					Send-Art $props
					$artTries = -1
				}
			}
		}
		if ($state -ne $lastState) {
			Say ("S " + $state)
			$lastState = $state
		}
	} catch {
		Say ("E " + $_.Exception.Message)
		$lastState = ''
	}
	Start-Sleep -Milliseconds 400
}
