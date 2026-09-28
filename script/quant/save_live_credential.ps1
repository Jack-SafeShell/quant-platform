param([Parameter(Mandatory=$true)][string]$CredentialPath)
$ErrorActionPreference='Stop'
$target=[System.IO.Path]::GetFullPath($CredentialPath)
$parent=[System.IO.Path]::GetDirectoryName($target)
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
$apiKey=Read-Host 'OKX API Key' -AsSecureString
$secretKey=Read-Host 'OKX Secret Key' -AsSecureString
$passphrase=Read-Host 'OKX Passphrase' -AsSecureString
function Reveal([Security.SecureString]$value){$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($value);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}}
$json=@{apiKey=(Reveal $apiKey);secretKey=(Reveal $secretKey);passphrase=(Reveal $passphrase)}|ConvertTo-Json -Compress
$encrypted=ConvertFrom-SecureString (ConvertTo-SecureString $json -AsPlainText -Force)
[System.IO.File]::WriteAllText($target,$encrypted,[Text.UTF8Encoding]::new($false))
$acl=Get-Acl $target;$acl.SetAccessRuleProtection($true,$false);$rule=[Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.WindowsIdentity]::GetCurrent().Name,'FullControl','Allow');$acl.SetAccessRule($rule);Set-Acl $target $acl
Write-Output "Credential stored with Windows DPAPI at $target"
