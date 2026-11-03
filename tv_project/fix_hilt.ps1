Get-ChildItem -Path "app/src/main/java" -Filter "*.kt" -Recurse | ForEach-Object {
    $content = Get-Content $_.FullName -Raw
    $content = $content -replace 'import javax\.inject\.\w+\r?\n', ''
    $content = $content -replace 'import dagger\.hilt\.\S+\r?\n', ''
    $content = $content -replace 'import dagger\.\w+\r?\n', ''
    $content = $content -replace '@Inject\r?\n\s*', ''
    $content = $content -replace '@Singleton\r?\n', ''
    $content = $content -replace '@HiltViewModel\r?\n', ''
    $content = $content -replace '@AndroidEntryPoint\r?\n', ''
    $content = $content -replace '@ApplicationContext\s+', ''
    Set-Content -Path $_.FullName -Value $content -NoNewline
}
Write-Host "Hilt references removed"
