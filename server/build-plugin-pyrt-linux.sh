#!/usr/bin/env bash
# 打包 fpk 用的 Python 运行时载荷（linux）→ server/build/plugins/pyrt-<arch>/
#
# 为什么不复用 PC 的 pyrt 插件：那份自带 **Windows** embeddable CPython
# （python/python.exe + vendor/*.pyd），linux 上根本跑不了。fpk 侧解释器来自
# fnOS 依赖应用 python312 或系统 python3，本脚本只提供：
#   · pyrt.py（跨平台，纯 Python）
#   · scripts/base（`from base.spider import Spider` 的落地）
#   · vendor/（纯 Python 依赖 + abi3 的 pycryptodome，按目标架构下载）
#
# ★ 依赖取舍（2026-11-27 实测源包 import 统计，110 个 .py 源）：
#   requests 28 / Crypto(pycryptodome) 26 / bs4 12 / lxml 4 / pyquery 1
#   → 前三个都进 payload；lxml / pyquery 不打（lxml 的 wheel 是 cp311 专属，
#     而 fpk 设备的 python 可能是 3.11 也可能是 3.12，装错版本就是 ImportError，
#     比"明确不支持"更难查）。用到它们的 5 个源会得到 pyrt 的 ImportError 提示。
#
# 用法：bash server/build-plugin-pyrt-linux.sh [aarch64|x86_64]
set -euo pipefail

ARCH="${1:-x86_64}"
case "$ARCH" in
  aarch64|arm64) ARCH=aarch64; PLAT=manylinux2014_aarch64 ;;
  x86_64|amd64)  ARCH=x86_64;  PLAT=manylinux2014_x86_64 ;;
  *) echo "未知架构：$ARCH（只支持 aarch64 / x86_64）" >&2; exit 1 ;;
esac

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/pyrt"
OUT="$ROOT/build/plugins/pyrt-$ARCH"

[ -f "$SRC/pyrt.py" ] || { echo "缺 $SRC/pyrt.py" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

cp "$SRC/pyrt.py" "$OUT/pyrt.py"
if [ -d "$SRC/scripts" ]; then
    cp -a "$SRC/scripts" "$OUT/scripts"
    # __pycache__ 是构建机的字节码，可能含 Windows 路径且会与目标解释器版本不符
    find "$OUT/scripts" -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true
fi

# manifest：exec 写 python3 → fpk 的 meowd 会走 deps.python_path()（fnOS 依赖
# python312）或 PATH 里的系统 python3。
# ★ 2026-11-28 中期分层：pyrt 只声明 pyRuntimeAddr（.py 源直连它），不再抢
#   jsRuntime —— jsRuntime 归 drpy，两个引擎互不代理。键名用 pyRuntimeAddr
#   而不是 pyRuntime（iOS 用 policy.pyRuntime 存启动状态对象）。
cat > "$OUT/manifest.json" <<'JSON'
{
  "id": "pyrt",
  "name": "Python 运行时",
  "capability": "runtime",
  "required": false,
  "fatal": false,
  "version": "0.1.4",
  "exec": "python3",
  "execFallback": ["python"],
  "args": ["pyrt.py"],
  "cwd": ".",
  "port": 19331,
  "portFallback": [19332, 19333, 19334, 19335],
  "health": "/health",
  "healthExpect": "\"ok\":true",
  "healthTimeoutMs": 20000,
  "healthIntervalMs": 500,
  "autostart": true,
  "env": {
    "PYRT_PORT": "{port}",
    "PYRT_SCRIPTS": "{appData}/py-scripts"
  },
  "inject": {
    "target": "{appData}/data/policy.json",
    "key": "pyRuntimeAddr",
    "value": "http://127.0.0.1:{port}",
    "force": true
  }
}
JSON

# vendor：按目标架构下载 wheel 并解到 vendor/（pyrt.py 会把 <dir>/vendor 加进 sys.path）
echo "== vendor ($ARCH / $PLAT) =="
if python3 -m pip install --quiet --upgrade pip >/dev/null 2>&1 || true; then :; fi
set +e
python3 -m pip install \
    --target "$OUT/vendor" \
    --only-binary :all: \
    --implementation cp \
    --python-version 3.11 \
    --abi cp311 \
    --platform "$PLAT" \
    --no-compile \
    "requests>=2.31" "beautifulsoup4>=4.12" "pycryptodome>=3.20" 2>&1 | tail -6
PIP_RC=${PIPESTATUS[0]}
set -e
if [ "$PIP_RC" -ne 0 ]; then
    # 网络不可用时不要阻断 fpk 打包：payload 仍然可用（系统 python3 自带 requests），
    # 只是 bs4/Crypto 类源会报 ImportError。
    echo "::warning::vendor 下载失败（pip rc=$PIP_RC）—— pyrt 仍会打包，但 bs4/Crypto 类 .py 源不可用" >&2
    rm -rf "$OUT/vendor"
fi

# 自检：pyrt.py 至少能被语法编译（-B：别在载荷里留 __pycache__，那是构建机的
# 字节码，可能带 Windows 路径且会与目标解释器版本不符）
python3 -B -m py_compile "$OUT/pyrt.py" 2>/dev/null || python3 -m py_compile "$OUT/pyrt.py"
find "$OUT" -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true

SIZE="$(du -sh "$OUT" | cut -f1)"
echo "pyrt 载荷（$ARCH）: $SIZE → $OUT"
ls -1 "$OUT"
