<#
把 MuMu 模拟器的出网口对齐到 PC：网关与 DNS 都指向 OpenClash 那台盒子。

## 为什么需要（2026-11-13 实测）
PC 与三台 MuMu 在同一个 /24，但出口不是同一台网关：
  · PC 的默认网关 = 192.168.1.100（OpenClash，fake-ip + TUN）→ github.com 解析成
    198.18.0.102，走代理，检查更新 1-2 秒完成。
  · MuMu 走 DHCP 给的 192.168.1.1（普通路由）→ github.com 解析成真实 IP
    （20.6.7.17 等）→ 443 被墙，检查更新要么转圈、要么靠 ghproxy.net 慢通。

把 MuMu 的默认路由与 DNS 指到 .100 之后：解析出 198.18.0.102、TCP 443 通，
在线更新（检查 → 下载 → 校验 → 安装）全链路正常（.76 实测 1.2.5 → 1.2.7）。

## 注意
- 这些规则是**运行时**的，模拟器重启后失效 —— 重启后重跑一次本脚本。
- 只改默认路由与 53 端口转发；192.168.1.0/24 直连不变，局域网后端照常访问。
- 需要 root（MuMu 自带 `adb root`）。

## 用法
  ./smoke/mumu-net.ps1                 # 对当前 adb 上所有 MuMu 设备生效
  ./smoke/mumu-net.ps1 -Check          # 只看现状，不改
  ./smoke/mumu-net.ps1 -Gateway 192.168.1.100 -Only 192.168.1.76:5555
#>
param(
    [string]$Gateway = '192.168.1.100',
    [string]$Only = '',
    [switch]$Check,
    [string]$Adb = ''
)

$ErrorActionPreference = 'Stop'

if (-not $Adb) {
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { $Adb = $c.Source }
    elseif (Test-Path 'C:\Android\platform-tools\adb.exe') { $Adb = 'C:\Android\platform-tools\adb.exe' }
    else { throw '找不到 adb：装 platform-tools，或用 -Adb 指定绝对路径' }
}

function Adb([string]$Serial, [string]$Command) {
    # adb 的 stderr 会被 PowerShell 当 NativeCommandError；这一层统一吞掉，
    # 由调用方看文本判断（否则一台没 root 的真机会直接把整个循环打断）。
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return (& $Adb -s $Serial shell $Command 2>&1 | Out-String).Trim()
    } finally {
        $ErrorActionPreference = $old
    }
}

# 只认 TV 壳的包名，避免把 PC/手机误当模拟器
$devices = @()
& $Adb devices | Select-Object -Skip 1 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line) { return }
    $parts = $line -split '\s+'
    if ($parts.Count -ge 2 -and $parts[1] -eq 'device') { $devices += $parts[0] }
}
if ($Only) { $devices = @($devices | Where-Object { $_ -eq $Only }) }
if ($devices.Count -eq 0) { throw '没有可用设备（adb devices 为空，或 -Only 没匹配到）' }

foreach ($d in $devices) {
    Write-Host "`n== $d" -ForegroundColor Cyan
    if ((Adb $d 'pm list packages com.meow.tv') -notmatch 'com.meow.tv') {
        Write-Host '   跳过：没装 com.meow.tv（不是 MuMu 的 TV 壳）' -ForegroundColor DarkGray
        continue
    }

    $defLine = Adb $d 'ip route show table all | grep -m1 default'
    $iface = 'wlan0'
    if ($defLine -match 'dev\s+(\S+)') { $iface = $Matches[1] }

    if ($Check) {
        Write-Host ('   默认路由: ' + ((Adb $d "ip route show table $iface | grep -m1 default") -replace "`n", ' '))
        Write-Host ('   github.com -> ' + ((Adb $d "ping -c1 -W2 github.com 2>/dev/null | head -1") -replace "`n", ' '))
        Write-Host ('   53 转发:  ' + ((Adb $d "iptables -t nat -S OUTPUT | grep 'dpt:53' | head -1") -replace "`n", ' '))
        continue
    }

    & $Adb -s $d root | Out-Null
    Start-Sleep -Milliseconds 800
    # 真机（极米投影仪等）没有 root：iptables/路由都改不了，明确说要手动配
    if ((Adb $d 'id -u') -ne '0') {
        Write-Host '   跳过：没有 root（真机请在路由器/DHCP 上把网关与 DNS 指到 OpenClash）' -ForegroundColor Yellow
        continue
    }
    # Android 用**每网络**的策略路由表（table wlan0），写 main 表不生效
    $out = Adb $d "ip route replace default via $Gateway dev $iface table $iface; ip route replace 198.18.0.0/16 via $Gateway dev $iface table $iface; echo ok"
    if ($out -notmatch 'ok') { Write-Host "   ! 路由设置失败: $out" -ForegroundColor Red; continue }

    # DNS 也必须走 .100：只有它给 fake-ip（198.18.x），普通 DNS 返回真实 IP 会被墙
    $rule = "iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination ${Gateway}:53 2>/dev/null; iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination ${Gateway}:53; iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination ${Gateway}:53 2>/dev/null; iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination ${Gateway}:53; echo ok"
    if ((Adb $d $rule) -notmatch 'ok') { Write-Host '   ! DNS 转发设置失败' -ForegroundColor Red; continue }

    $ping = Adb $d "ping -c1 -W3 github.com 2>/dev/null | head -1"
    $tcpOk = (Adb $d "nc -w 6 198.18.0.102 443 </dev/null >/dev/null 2>&1; echo `$?") -match '^0$'
    Write-Host ('   github.com: ' + ($ping -replace "`n", ' '))
    if ($ping -match '198\.18\.' -and $tcpOk) {
        Write-Host '   ✅ 已对齐（fake-ip + 443 可达）' -ForegroundColor Green
    } else {
        Write-Host "   ! 仍未对齐：确认网关 $Gateway 在跑 OpenClash（fake-ip/TUN）且允许本网段" -ForegroundColor Yellow
    }
}
