param([Parameter(Mandatory=$true)][string]$CredentialPath)
$ErrorActionPreference='Stop'
$target=[System.IO.Path]::GetFullPath($CredentialPath)
$secure=ConvertTo-SecureString ([System.IO.File]::ReadAllText($target,[Text.Encoding]::UTF8))
$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try{[Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr))}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
