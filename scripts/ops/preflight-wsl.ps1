#requires -Version 7.4
# Read-only development prerequisites. Always target Ubuntu explicitly; preserve Kali and data.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-WslRead {
    param([string[]]$Arguments)
    $process = [Diagnostics.Process]::new()
    try {
        $process.StartInfo.FileName = 'wsl.exe'
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        foreach ($argument in $Arguments) { $process.StartInfo.ArgumentList.Add($argument) }
        if (-not $process.Start()) { return @{ Code = 1; Text = '' } }
        # Drain both streams concurrently, without printing either private stream.
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            $process.Kill($true)
            return @{ Code = 1; Text = '' }
        }
        if (-not $output.Wait(5000) -or -not $errors.Wait(5000)) { return @{ Code = 1; Text = '' } }
        return @{ Code = $process.ExitCode; Text = $output.Result.Replace([string][char]0, '') }
    } catch { return @{ Code = 1; Text = '' } }
    finally { $process.Dispose() }
}

function Assert-WslPrerequisites {
    if ($env:OS -ne 'Windows_NT') { throw 'WINDOWS_HOST_REQUIRED' }
    $listed = Invoke-WslRead -Arguments @('--list', '--verbose')
    if ($listed.Code -ne 0) { throw 'WSL_LIST_UNAVAILABLE' }
    $matching = @($listed.Text -split "`r?`n" | Where-Object { $_ -match '^\s*\*?\s*Ubuntu-24\.04\s+.+\s+2\s*$' })
    if ($matching.Count -ne 1) { throw 'UBUNTU_2404_WSL2_REQUIRED' }
    $release = Invoke-WslRead -Arguments @('--distribution', 'Ubuntu-24.04', '--exec', '/usr/bin/cat', '/etc/os-release')
    if ($release.Code -ne 0 -or $release.Text -notmatch '(?m)^ID="?ubuntu"?\r?$' -or
        $release.Text -notmatch '(?m)^VERSION_ID="?24\.04"?\r?$') { throw 'UBUNTU_2404_REQUIRED' }
    $architecture = Invoke-WslRead -Arguments @('--distribution', 'Ubuntu-24.04', '--exec', '/usr/bin/uname', '-m')
    if ($architecture.Code -ne 0 -or $architecture.Text.Trim() -ne 'x86_64') { throw 'WSL_X86_64_REQUIRED' }
    # Force a local Unix-socket Docker context; never certify an environment-selected remote daemon.
    $endpoint = Invoke-WslRead -Arguments @('--distribution', 'Ubuntu-24.04', '--exec', 'docker', '--context', 'default', 'context', 'inspect', 'default', '--format', '{{.Endpoints.docker.Host}}')
    if ($endpoint.Code -ne 0 -or $endpoint.Text.Trim() -ne 'unix:///var/run/docker.sock') { throw 'LOCAL_WSL_DOCKER_REQUIRED' }
    $runtime = Invoke-WslRead -Arguments @('--distribution', 'Ubuntu-24.04', '--exec', 'docker', '--context', 'default', 'info', '--format', '{{json .}}')
    if ($runtime.Code -ne 0) { throw 'DOCKER_DESKTOP_UNAVAILABLE' }
    try { $facts = $runtime.Text | ConvertFrom-Json } catch { throw 'INVALID_DOCKER_FACTS' }
    if ($facts.OSType -ne 'linux' -or $facts.Architecture -notin @('x86_64', 'amd64') -or
        $facts.OperatingSystem -notmatch '^Docker Desktop(?:\s|$)' -or
        ($facts.NCPU -isnot [int] -and $facts.NCPU -isnot [long]) -or
        ($facts.MemTotal -isnot [int] -and $facts.MemTotal -isnot [long]) -or
        $facts.NCPU -lt 2 -or $facts.MemTotal -lt 4294967296) { throw 'DOCKER_DESKTOP_LINUX_2CPU_4GIB_REQUIRED' }
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        if ($args.Count -ne 0) { throw 'Usage: preflight-wsl.ps1' }
        Assert-WslPrerequisites
        Write-Output 'WSL_DEVELOPMENT_PREREQUISITES_READY'
    } catch {
        $safe = @('WINDOWS_HOST_REQUIRED', 'WSL_LIST_UNAVAILABLE', 'UBUNTU_2404_WSL2_REQUIRED',
            'UBUNTU_2404_REQUIRED', 'WSL_X86_64_REQUIRED', 'LOCAL_WSL_DOCKER_REQUIRED',
            'DOCKER_DESKTOP_UNAVAILABLE', 'INVALID_DOCKER_FACTS', 'DOCKER_DESKTOP_LINUX_2CPU_4GIB_REQUIRED',
            'Usage: preflight-wsl.ps1')
        if ($_.Exception.Message -in $safe) { [Console]::Error.WriteLine($_.Exception.Message) }
        else { [Console]::Error.WriteLine('WSL_PREFLIGHT_FAILED') }
        exit 1
    }
}
