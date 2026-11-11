param(
    [string]$PackPath = (Join-Path $PSScriptRoot '../build-tools/photon.zip'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../build/flight-preview/photon-presets')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$expectedHash = '120897768eaa8cb1f14a3e98f88dff009ae3204ee3b96904a4fe8586edfc1ead'
$packHash = (Get-FileHash -LiteralPath $PackPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($packHash -ne $expectedHash) { throw 'These presets target the verified Photon v1.3b archive. Revalidate options before using another release.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $PackPath).Path)
try {
    $reader = [IO.StreamReader]::new($archive.GetEntry('shaders/settings.glsl').Open())
    try { $settings = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $reader = [IO.StreamReader]::new($archive.GetEntry('shaders/shaders.properties').Open())
    try { $menus = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $archive.Dispose() }
$definitions = @{}
foreach ($match in [regex]::Matches($settings, '(?m)^[ \t]*(?://[ \t]*)?#define[ \t]+(?<name>\w+)(?<tail>[^\r\n]*)')) {
    $tail = $match.Groups['tail'].Value
    $value = ($tail -split '//', 2)[0].Trim()
    $allowed = @()
    if ($tail -match '\[([^\]]+)\]') { $allowed = @($Matches[1] -split '\s+' | Where-Object { $_ }) }
    $definitions[$match.Groups['name'].Value] = @{ Boolean = ($value.Length -eq 0); Allowed = $allowed }
}
foreach ($match in [regex]::Matches($settings, '(?m)^[ \t]*const[ \t]+(?:int|float|bool)[ \t]+(?<name>\w+)[ \t]*=[^;]+;(?<tail>[^\r\n]*)')) {
    $allowed = @()
    if ($match.Groups['tail'].Value -match '\[([^\]]+)\]') { $allowed = @($Matches[1] -split '\s+' | Where-Object { $_ }) }
    $definitions[$match.Groups['name'].Value] = @{ Boolean = $false; Allowed = $allowed }
}

# Medium-inspired cost envelope, with explicit Earth-scale clouds and full-resolution terrain.
$balanced = [ordered]@{
    INFO='1'; shadowMapResolution='1536'; shadowDistance='96.0'
    SHADOW='true'; SHADOW_PCF='true'; SHADOW_VPS='false'; SHADOW_COLOR='false'; SHADOW_SSRT='false'
    ENTITY_SHADOWS='false'; BLOCK_ENTITY_SHADOWS='false'; CLOUD_SHADOWS='false'; COLORED_LIGHTS='false'
    SHADER_AO='SHADER_AO_SSAO'; SSAO_STEPS='8'; VANILLA_AO='true'; SH_SKYLIGHT='true'
    VL='true'; OVERWORLD_FOG_INTENSITY='0.35'; AIR_FOG_COLORED_LIGHT_SHAFTS='false'
    AIR_FOG_CLOUDY_NOISE='false'; CREPUSCULAR_RAYS='false'
    ENVIRONMENT_REFLECTIONS='false'; SKY_REFLECTIONS='true'
    WATER_CAUSTICS='false'; WATER_PARALLAX='false'; WATER_DISPLACEMENT='false'
    NORMAL_MAPPING='false'; SPECULAR_MAPPING='false'; POM='false'
    DOF='false'; MOTION_BLUR='false'; TAA='true'; FXAA='false'; TAAU='false'; CAS='true'; BLOOM='true'
    SEA_LEVEL='63.0'; RANDOM_WEATHER_VARIATION='true'; BIOME_WEATHER_VARIATION='true'
    CLOUDS_SCALE='1.0'; CLOUDS_TEMPORAL_UPSCALING='4'
    CLOUDS_CUMULUS='true'; CLOUDS_ALTOCUMULUS='true'; CLOUDS_CIRRUS='true'
    CLOUDS_CUMULUS_CONGESTUS='false'; CLOUDS_NOCTILUCENT='false'; BLOCKY_CLOUDS='false'
    CLOUDS_CUMULUS_ALTITUDE='2500.0'; CLOUDS_CUMULUS_THICKNESS='0.30'
    CLOUDS_CUMULUS_DENSITY='0.70'; CLOUDS_CUMULUS_COVERAGE='0.85'
    CLOUDS_CUMULUS_PRIMARY_STEPS_H='32'; CLOUDS_CUMULUS_PRIMARY_STEPS_Z='16'
    CLOUDS_CUMULUS_LIGHTING_STEPS='4'; CLOUDS_CUMULUS_AMBIENT_STEPS='1'
    CLOUDS_ALTOCUMULUS_ALTITUDE='5000.0'; CLOUDS_ALTOCUMULUS_THICKNESS='0.10'
    CLOUDS_ALTOCUMULUS_DENSITY='0.10'; CLOUDS_ALTOCUMULUS_COVERAGE='0.75'
    CLOUDS_ALTOCUMULUS_PRIMARY_STEPS_H='12'; CLOUDS_ALTOCUMULUS_PRIMARY_STEPS_Z='6'
    CLOUDS_ALTOCUMULUS_LIGHTING_STEPS='3'
    CLOUDS_CIRRUS_ALTITUDE='7500.0'; CLOUDS_CIRRUS_DENSITY='0.25'; CLOUDS_CIRRUS_COVERAGE='0.65'
    CLOUDS_CIRRUS_LIGHTING_STEPS='2'
}
$fast = [ordered]@{}
foreach ($key in $balanced.Keys) { $fast[$key] = $balanced[$key] }
$overrides = @{
    INFO='0'; shadowMapResolution='1024'; shadowDistance='80.0'; SHADER_AO='SHADER_AO_NONE'
    SH_SKYLIGHT='false'; VL='false'; OVERWORLD_FOG_INTENSITY='0.20'; CLOUDS_ALTOCUMULUS='false'
    CLOUDS_CUMULUS_PRIMARY_STEPS_H='24'; CLOUDS_CUMULUS_PRIMARY_STEPS_Z='12'; CLOUDS_CUMULUS_LIGHTING_STEPS='3'
}
foreach ($key in $overrides.Keys) { $fast[$key]=$overrides[$key] }

$reports = @()
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
foreach ($item in @(@{Name='Balanced'; Values=$balanced}, @{Name='Fast'; Values=$fast})) {
    $lines = @(('# Earth Platform / Photon v1.3b - ' + $item.Name),
        '# Import through Iris Shader Pack Settings after resetting Photon defaults.',
        '# Full-resolution terrain. Photon supplies clouds/weather; no mod settings are changed.')
    foreach ($key in $item.Values.Keys) {
        $value = [string]$item.Values[$key]
        if (-not $definitions.ContainsKey($key)) { throw "Unknown Photon option: $key" }
        if ($menus -notmatch ('\b' + [regex]::Escape($key) + '\b')) { throw "Option not exposed by Photon menus/profiles: $key" }
        $definition = $definitions[$key]
        if ($definition.Boolean) {
            if ($value -notin @('true','false')) { throw "Invalid boolean: $key=$value" }
        } elseif ($value -notin $definition.Allowed) { throw "Unsupported Photon value: $key=$value" }
        $lines += "$key=$value"
    }
    $destination = Join-Path $OutputDirectory ('Earth-Photon-' + $item.Name + '-v1.3b.txt')
    [IO.File]::WriteAllLines([IO.Path]::GetFullPath($destination), $lines, [Text.UTF8Encoding]::new($false))
    # Parse the written artifact back; ensure no options were dropped/duplicated.
    $written = @(Get-Content -LiteralPath $destination | Where-Object { $_ -and -not $_.StartsWith('#') })
    if ($written.Count -ne $item.Values.Count) { throw 'Preset round-trip count differs' }
    $reports += [ordered]@{ preset=[IO.Path]::GetFileName($destination); options=$written.Count; sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() }
}
if ((Get-FileHash -LiteralPath $PackPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'Input archive changed' }
$report = [ordered]@{ photon_version='v1.3b'; photon_sha256=$packHash; validation='All option names, UI exposure, booleans and enumerated values checked against supplied pack; settings files read back; input archive unchanged.'; in_game_tested=$false; presets=$reports }
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'validation.json') -Encoding UTF8
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../../docs/PHOTON-EARTH-PRESET.txt') -Destination (Join-Path $OutputDirectory 'READ-ME.txt') -Force
$report | ConvertTo-Json -Depth 5
