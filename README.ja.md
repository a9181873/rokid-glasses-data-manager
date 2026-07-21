# 眼鏡ファイルステーション（中国語名：眼鏡檔案站）

[繁體中文](README.md)｜[English](README.en.md)｜日本語

[![Android CI](https://github.com/a9181873/rokid-glasses-data-manager/actions/workflows/android.yml/badge.svg)](https://github.com/a9181873/rokid-glasses-data-manager/actions/workflows/android.yml)

## APK をダウンロード

通常の利用では Android SDK もローカルビルドも不要です。眼鏡へ直接インストールできるアプリをダウンロードしてください。

**[GlassesFiles.apk をダウンロード](https://github.com/a9181873/rokid-glasses-data-manager/raw/refs/heads/main/dist/GlassesFiles.apk)**

ダウンロード後、Hi Rokid の Toolbox でインストールできます。Toolbox を使用できない場合は、下記の ADB 手順を使用してください。

緑色モノクロ表示を採用する一般消費者向け **Rokid Glasses RV101／RV102** 専用に設計されています。アプリは眼鏡上で直接動作し、眼鏡本体、スマートフォンのブラウザ、またはパソコンから、眼鏡内の写真と動画を管理できます。アルバム全体をあらかじめスマートフォンへ同期する必要はなく、クラウド、アカウント、広告、アクセス解析サービスも使用しません。

> 「眼鏡ファイルステーション」は説明文での日本語名です。インストールされる APK と現在の画面は繁体字中国語のままで、ランチャーに表示される固定名は `眼鏡檔案站` です。Rokid は一般消費者向け YodaOS の Android 動作をすべて公開仕様として保証しているわけではありません。本格的に使用する前に、[実機検証チェックリスト](docs/DEVICE_CHECK.ja.md)に従って、ファイルパス、ストレージ権限、タッチパッドイベントを確認してください。

## 機能

- 眼鏡向け 480×640、黒背景・高コントラストの画面：スワイプで選択、タップで実行、ダブルタップで戻る操作に対応。
- 写真／動画／日付別の閲覧に対応。12MP の原画像全体をメモリへ読み込まず、縮小サムネイルを生成。
- スマートフォンまたはパソコンのブラウザから、ファイル一覧、プレビュー、Range ストリーミング、明示的なダウンロードを利用可能。原本を事前同期しません。
- 名前変更、ゴミ箱への移動、復元、アップロードに対応。誤操作による復元不能な損失を防ぐため、アプリは完全削除機能を提供しません。
- USB モードは眼鏡本体の `127.0.0.1:8765` だけを待ち受け、ADB port forwarding と組み合わせて使用。
- Wi‑Fi モードは現在のプライベート IPv4 アドレスだけを待ち受け。起動するたびにペアリングコードと 256-bit セッショントークンを再生成し、10 分間操作がなければ自動停止。
- Web 画面は APK に完全内蔵され、CDN、外部フォント、トラッキングコード、外部 API を読み込みません。

## ストレージ権限

初回起動時に Android の「すべてのファイルへのアクセス」を明示的に許可する必要があります。これにより、ファイルごとに眼鏡上で確認操作を繰り返さなくても、スマートフォンのブラウザからメディアの名前変更、移動、復元を行えます。それでもプログラム内部では、`DCIM/Camera`、`DCIM/album`、`Pictures`、`Movies` などのメディア用ホワイトリストにアクセス先を厳格に制限し、シンボリックリンクと canonical path からの逸脱を拒否します。権限がない場合は、MediaStore による読み取り専用の代替動作だけを提供します。

## 確認済みのデバイス条件

現在、公式情報に掲載されている Rokid Glasses の仕様は、両眼 480×640 の緑色モノクロ Micro‑LED、30° FOV、最大 1,500 nits、Snapdragon AR1 Gen 1、2GB RAM、32GB ストレージ、Wi‑Fi 6、Bluetooth 5.3、12MP Sony IMX681 です。操作方法には、テンプル部分のタッチパッド、物理ボタン、音声、Hi Rokid が含まれます。

- [Rokid 公式製品仕様](https://global.rokid.com/products/rokid-glasses)
- [Rokid Academy の操作方法と仕様](https://global.rokid.com/en-jp/pages/academy)
- [Rokid Security Center（RV101／RV102）](https://global.rokid.com/en-jp/pages/security-center)
- [台湾 Rokid RV101 製品ページ](https://www.rokid.com.tw/zh-TW/products/rokid-glasses)

公式に公開されている OS 名は **YodaOS-Sprite** だけです。Android 12／API 32、arm64-v8a、low-RAM などの詳細は、現時点では[コミュニティによるファームウェア調査](https://github.com/buildwithfenna/rokid-docs)に基づきます。そのため、本アプリは Google Play Services や Rokid の非公開 SDK に依存せず、Android API レベル 28 以降に対応します。

## ソースからビルド（開発者向け）

通常の利用では上記の APK を使用してください。SDK のインストールは不要です。ソースコードを変更する場合は、次の手順でビルドします。

1. [Android 公式サイトから Android Studio をダウンロードしてインストール](https://developer.android.com/studio)します。Setup Wizard が Android SDK も設定します。
2. **Tools → SDK Manager** を開き、**Android SDK Platform 35**、**Android SDK Build-Tools**、**Android SDK Platform-Tools** をインストールします。
3. Android Studio で本プロジェクトのフォルダーを開き、Gradle Sync の完了を待ちます。
4. **Build → Build App Bundle(s) or APK(s) → Build APK(s)** を選択します。
5. `app/build/outputs/apk/debug/app-debug.apk` からビルド結果を取得します。

コマンドラインだけを使用する場合は、[公式ダウンロードページ](https://developer.android.com/studio#command-tools)から Android SDK Command-Line Tools を取得し、JDK 17 も用意してください。環境設定後、プロジェクトのルートで次を実行します。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat test
```

macOS／Linux では `gradlew.bat` を `./gradlew` に置き換えてください。

リポジトリに含まれる正式署名済み APK：

```text
dist/GlassesFiles.apk
```
SHA-256：`9150e6856995862df1c279783ec6e5844f8ffb33b653f1bf2c9e398647d14cb0`。署名証明書フィンガープリント：`b1052559eb22898762d7867b0d799d631e9743f89b4e69f6b9efc8a29972b729`。

メンテナーのローカル環境にある `private-signing/` には、今後の上書き更新に必須となる秘密署名鍵があります。このフォルダは `.gitignore` で除外され、GitHub へは送信されません。オフラインでバックアップし、公開しないでください。

## インストール

Hi Rokid の Toolbox は、ローカル APK のインストールとスマートフォンからの眼鏡操作に対応します。操作前に Hi Rokid と YodaOS-Sprite のシステム更新を完了してください。Toolbox を使用できない場合は、データ端子を備えた Rokid デバッグケーブルを別途用意し、ADB を有効にする必要があります。

```bash
adb devices -l
adb install -r dist/GlassesFiles.apk
```

同梱の磁気充電ケーブルは、データ通信に対応していない場合があります。インストール後は固定表示名 `眼鏡檔案站` を探してください。通常は眼鏡のアプリ一覧の末尾に表示されますが、この動作は実機のファームウェアによって異なる可能性があります。

## 使い方

### USB でパソコンから管理（推奨）

1. 眼鏡で `眼鏡檔案站` → `USB 電腦管理`（USB パソコン管理）を開きます。
2. デバッグケーブルでパソコンへ接続し、次を実行します。

```bash
adb forward --no-rebind tcp:8765 tcp:8765
```

3. パソコンで `http://127.0.0.1:8765` を開き、眼鏡に表示されたワンタイムペアリングコードを入力します。
4. 終了後、眼鏡で共有を停止し、次を実行します。

```bash
adb forward --remove tcp:8765
```

### スマートフォンから Wi‑Fi で直接管理

1. 眼鏡を自分のスマートフォンのテザリングへ接続するか、スマートフォンと眼鏡を同じ信頼できる Wi‑Fi に接続します。
2. 眼鏡で `Wi‑Fi 手機管理`（Wi‑Fi スマートフォン管理）を開きます。
3. スマートフォンのブラウザで、眼鏡に表示された `http://私有IP:8765` を開き、その都度生成されるペアリングコードを入力します。
4. 終了後、直ちに共有を停止します。

Wi‑Fi の Web 画面は、必要になった一覧、サムネイル、または閲覧中のファイルだけを取得します。「ダウンロード」を押した場合に限り、スマートフォン／パソコン上へコピーが作成されます。

## 重要な制限

- Wi‑Fi ブラウザ版は現在、ローカルネットワーク上の HTTP を使用します。ペアリングコードは未承認の操作を防ぎますが、**通信内容を暗号化しません**。会社、ホテル、カフェなどの共有ネットワークでは使用しないでください。機密性の高いファイルには USB モードを使用してください。
- 「すべてのファイルへのアクセス」を許可していない場合、MediaStore の代替動作では閲覧しかできないことがあります。アプリの権限画面へ戻って許可を完了すると、ブラウザから直接、名前変更、移動、復元ができるようになります。
- 一般のサードパーティ製アプリは、OEM に MTP を強制的に有効化させることも、他のアプリの非公開 `/data/user/0` ディレクトリを読み取ることもできません。
- 緑色モノクロ表示はファイル名、日付、構図の確認には適していますが、元の色を判断する用途には適しません。写真と動画の詳細は、スマートフォン／パソコンのブラウザで確認することを推奨します。

セキュリティ設計と既知のリスクについては [SECURITY.ja.md](SECURITY.ja.md)、データの取り扱いについては [PRIVACY.ja.md](PRIVACY.ja.md) を参照してください。
