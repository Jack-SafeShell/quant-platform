param([Parameter(Mandatory=$true)][string]$CredentialPath,[ValidateSet('okx','binance')][string]$Exchange='okx')
$ErrorActionPreference='Stop'
$target=[System.IO.Path]::GetFullPath($CredentialPath)
$parent=[System.IO.Path]::GetDirectoryName($target)
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
function Reveal([Security.SecureString]$value){$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($value);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}}
function Read-Secret([string]$prompt){
    while($true){
        $inputValue=Read-Host $prompt -AsSecureString
        $plain=Reveal $inputValue
        # Legacy ConsoleHost passes Ctrl+V as U+0016 to secure input instead of pasting.
        # Read the clipboard only after that explicit paste gesture; never print its contents.
        if([string]::Equals($plain,[string][char]0x16,[StringComparison]::Ordinal)){$plain=[string](Get-Clipboard -Raw)}
        $plain=$plain.Trim()
        if($plain.Length -lt 2 -or $plain -match '[\x00-\x1F\x7F]'){
            Write-Host 'Input was empty/incomplete or contained control characters. Please paste again.'
            $plain=$null
            continue
        }
        $result=ConvertTo-SecureString $plain -AsPlainText -Force
        Write-Host "$prompt accepted ($($plain.Length) characters)."
        $plain=$null
        return $result
    }
}
$apiKey=Read-Secret "$Exchange API Key"
$secretKey=Read-Secret "$Exchange Secret Key (HMAC)"
$passphrase=if($Exchange -eq 'okx'){Read-Secret 'OKX Passphrase'}else{$null}
$fields=@{exchange=$Exchange;apiKey=(Reveal $apiKey);secretKey=(Reveal $secretKey)}
if($passphrase){$fields.passphrase=Reveal $passphrase}
$json=$fields|ConvertTo-Json -Compress
$encrypted=ConvertFrom-SecureString (ConvertTo-SecureString $json -AsPlainText -Force)
[System.IO.File]::WriteAllText($target,$encrypted,[Text.UTF8Encoding]::new($false))
# Persist only the DACL: copying a complete descriptor through Set-Acl can
# request SACL privileges that an ordinary interactive user does not possess.
$acl=[Security.AccessControl.FileSecurity]::new()
$acl.SetAccessRuleProtection($true,$false)
$rule=[Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.WindowsIdentity]::GetCurrent().User,'FullControl','Allow')
$acl.SetAccessRule($rule)
[System.IO.FileSystemAclExtensions]::SetAccessControl([System.IO.FileInfo]::new($target),$acl)
Write-Output "Credential stored with Windows DPAPI at $target"
