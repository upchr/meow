# 猫窝 iOS（最小壳）

这是第一个可跑的 iOS 壳：WKWebView + 进程内静态服务，加载构建期拷进来的
`web/dist`。它**不含** Java 后端、不含 dex/jar 执行；搜索/播放仍需连后端。
弹幕内置（nodejs-mobile + danmu_api）是下一阶段，见
[site/docs/dev/ios-integration.md](../../../site/docs/dev/ios-integration.md)。

## 目录

```text
client/apps/ios/
  project.yml            # XcodeGen 工程描述（Windows 上也能维护）
  Meow/
    AppDelegate.swift
    WebViewController.swift
    LocalWebServer.swift # 127.0.0.1 只读静态服务
    Web/                 # 构建期从 web/dist 拷入，gitignore
```

## 本地构建（需要 macOS + Xcode）

```bash
cd client
pnpm install --frozen-lockfile
pnpm --filter @meow/web build

rm -rf apps/ios/Meow/Web
mkdir -p apps/ios/Meow/Web
cp -R apps/web/dist/. apps/ios/Meow/Web/

cd apps/ios
brew install xcodegen
xcodegen generate
xcodebuild \
  -project Meow.xcodeproj \
  -scheme Meow \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  build
```

产物 `build/Build/Products/Release-iphoneos/Meow.app` 是**未签名**的；
打成 `Payload/Meow.app` 的 zip 才是 IPA，但仍需用户自己签名（AltStore /
Sideloadly，免费 Apple ID 7 天；或 $99 开发者证书）。

## CI

`.github/workflows/ios.yml`：

- 构建 `web-dist` → 拷进 `Meow/Web`
- XcodeGen 生成工程
- 未签名 IPA：`meow-iOS-<version>-unsigned.ipa`
- 模拟器启动 + 截图：`ios-screenshot.png`（不需要签名就能看渲染）

工作流只上传 artifact，不建 Release、不改 `packages.json`、不写 FNDepot。
