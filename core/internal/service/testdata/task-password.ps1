$ErrorActionPreference = 'Stop'
$pw = [Console]::In.ReadLine()
$ts = New-Object -ComObject Schedule.Service
$ts.Connect()
$def = $ts.NewTask(0)
$def.XmlText = Get-Content -Raw -LiteralPath 'C:\Users\O''Brien\AppData\Local\huginn\state\task.xml'
$null = $ts.GetFolder('\').RegisterTaskDefinition('Huginn Core', $def, 6, 'HOME\o''brien', $pw, 1)
