#requires -Version 7.4
# Native PowerShell unit cases with synthetic WSL responses. Never invoke real WSL/Docker.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/../ops/preflight-wsl.ps1"
$originalOs = $env:OS
$script:responses = @{}
$script:calls = [Collections.Generic.List[string]]::new()
function Invoke-WslRead {
    param([string[]]$Arguments)
    $key = $Arguments -join '|'
    $script:calls.Add($key)
    if (-not $script:responses.ContainsKey($key)) { throw 'UNEXPECTED_SYNTHETIC_COMMAND' }
    return $script:responses[$key]
}
function Reset-Fixture {
    $env:OS = 'Windows_NT'
    $script:calls.Clear()
    $script:responses = @{
        '--list|--verbose' = @{ Code = 0; Text = " NAME STATE VERSION`n* kali-linux Running 2`n  Ubuntu-24.04 Stopped 2" }
        '--distribution|Ubuntu-24.04|--exec|/usr/bin/cat|/etc/os-release' = @{ Code = 0; Text = "ID=ubuntu`nVERSION_ID=`"24.04`"" }
        '--distribution|Ubuntu-24.04|--exec|/usr/bin/uname|-m' = @{ Code = 0; Text = 'x86_64' }
        '--distribution|Ubuntu-24.04|--exec|docker|--context|default|context|inspect|default|--format|{{.Endpoints.docker.Host}}' = @{ Code = 0; Text = 'unix:///var/run/docker.sock' }
        '--distribution|Ubuntu-24.04|--exec|docker|--context|default|info|--format|{{json .}}' = @{ Code = 0; Text = '{"OSType":"linux","Architecture":"x86_64","OperatingSystem":"Docker Desktop","NCPU":2,"MemTotal":4294967296}' }
    }
}
function Set-Response {
    param([string]$Suffix, [string]$Text, [int]$Code = 0)
    $key = @($script:responses.Keys | Where-Object { $_.EndsWith($Suffix) })
    if ($key.Count -ne 1) { throw 'INVALID_SYNTHETIC_FIXTURE' }
    $script:responses[$key[0]] = @{ Code = $Code; Text = $Text }
}
function Require-Failure {
    param([string]$Code)
    $caught = ''
    try { Assert-WslPrerequisites } catch { $caught = $_.Exception.Message }
    if ($caught -ne $Code) { throw 'EXPECTED_FIXED_FAILURE_MISSING' }
}
$tests = [ordered]@{
    'ready_preserves_default_kali' = {
        $result = @(Assert-WslPrerequisites)
        if ($result.Count -ne 0 -or $script:calls.Count -ne 5) { throw 'READINESS_OR_OUTPUT_MISMATCH' }
        foreach ($command in $script:calls) {
            if ($command -ne '--list|--verbose' -and -not $command.StartsWith('--distribution|Ubuntu-24.04|--exec|')) { throw 'DISTRIBUTION_NOT_EXPLICIT' }
        }
    }
    'wrong_platform_stops_before_commands' = {
        $env:OS = 'Linux'; Require-Failure 'WINDOWS_HOST_REQUIRED'
        if ($script:calls.Count -ne 0) { throw 'UNEXPECTED_NATIVE_COMMAND' }
    }
    'unavailable_wsl_is_not_ready' = { Set-Response '--list|--verbose' 'synthetic private provider marker' 1; Require-Failure 'WSL_LIST_UNAVAILABLE' }
    'missing_ubuntu_never_uses_kali' = { Set-Response '--list|--verbose' '* kali-linux Running 2'; Require-Failure 'UBUNTU_2404_WSL2_REQUIRED' }
    'wsl1_is_not_converted' = { Set-Response '--list|--verbose' 'Ubuntu-24.04 Running 1'; Require-Failure 'UBUNTU_2404_WSL2_REQUIRED' }
    'ambiguous_distribution_fails' = { Set-Response '--list|--verbose' "Ubuntu-24.04 Running 2`nUbuntu-24.04 Stopped 2"; Require-Failure 'UBUNTU_2404_WSL2_REQUIRED' }
    'localized_state_and_crlf_are_supported' = { Set-Response '--list|--verbose' "  Ubuntu-24.04 Angehalten 2`r`n"; Assert-WslPrerequisites }
    'named_distribution_must_really_be_ubuntu' = { Set-Response '/etc/os-release' "ID=kali`nVERSION_ID=`"24.04`""; Require-Failure 'UBUNTU_2404_REQUIRED' }
    'wrong_ubuntu_release_fails' = { Set-Response '/etc/os-release' "ID=ubuntu`nVERSION_ID=`"22.04`""; Require-Failure 'UBUNTU_2404_REQUIRED' }
    'release_command_failure_is_closed' = { Set-Response '/etc/os-release' 'synthetic private provider marker' 1; Require-Failure 'UBUNTU_2404_REQUIRED' }
    'unsupported_architecture_fails' = { Set-Response '|-m' 'aarch64'; Require-Failure 'WSL_X86_64_REQUIRED' }
    'remote_docker_is_not_certified' = { Set-Response '|{{.Endpoints.docker.Host}}' 'tcp://synthetic.invalid:2376'; Require-Failure 'LOCAL_WSL_DOCKER_REQUIRED' }
    'docker_unavailable_fails' = { Set-Response '|{{json .}}' 'synthetic private provider marker' 1; Require-Failure 'DOCKER_DESKTOP_UNAVAILABLE' }
    'malformed_facts_fail' = { Set-Response '|{{json .}}' 'synthetic private provider marker'; Require-Failure 'INVALID_DOCKER_FACTS' }
    'wrong_runtime_and_resources_fail' = {
        foreach ($change in @(@{ OSType = 'windows' }, @{ OperatingSystem = 'Other engine' }, @{ NCPU = 1 }, @{ NCPU = 2.5 }, @{ NCPU = $true }, @{ MemTotal = 2147483648 }, @{ Architecture = 'aarch64' })) {
            $facts = @{ OSType = 'linux'; Architecture = 'amd64'; OperatingSystem = 'Docker Desktop'; NCPU = 2; MemTotal = 4294967296 }
            foreach ($key in $change.Keys) { $facts[$key] = $change[$key] }
            Set-Response '|{{json .}}' ($facts | ConvertTo-Json -Compress)
            Require-Failure 'DOCKER_DESKTOP_LINUX_2CPU_4GIB_REQUIRED'
        }
    }
    'private_facts_are_never_returned' = {
        $text = $script:responses[@($script:responses.Keys | Where-Object { $_.EndsWith('|{{json .}}') })[0]].Text
        $facts = $text | ConvertFrom-Json -AsHashtable
        $facts['Name'] = 'synthetic private identity marker'
        Set-Response '|{{json .}}' ($facts | ConvertTo-Json -Compress)
        if (@(Assert-WslPrerequisites).Count -ne 0) { throw 'PRIVATE_FACTS_ESCAPED' }
    }
}
$executed = 0
try {
    if ($tests.Count -ne 16) { throw 'INCOMPLETE_NATIVE_TEST_REGISTRY' }
    foreach ($test in $tests.GetEnumerator()) {
        Reset-Fixture
        & $test.Value
        $executed++
    }
    if ($executed -ne $tests.Count) { throw 'INCOMPLETE_NATIVE_TEST_EXECUTION' }
    $platform = if ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)) { 'windows' } else { 'other' }
    $receipt = @{ schemaVersion = 1; platform = $platform; powershell = $PSVersionTable.PSVersion.ToString(); tests = $executed; failures = 0; skipped = 0 }
    $path = Join-Path ([IO.Path]::GetTempPath()) ('purpuragenda-wsl-tests-' + [Guid]::NewGuid().ToString('N') + '.json')
    $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($receipt | ConvertTo-Json -Compress))
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
    if ($env:GITHUB_OUTPUT) { "receipt=$path" | Out-File -FilePath $env:GITHUB_OUTPUT -Encoding utf8 -Append }
    else { [IO.File]::Delete($path) }
    Write-Output 'WSL_PREFLIGHT_NATIVE_TESTS_PASSED'
} catch {
    [Console]::Error.WriteLine('WSL_PREFLIGHT_NATIVE_TESTS_FAILED')
    exit 1
} finally { $env:OS = $originalOs }
