# Rokid Glasses 実機検証チェックリスト

[繁體中文](DEVICE_CHECK.md)｜[English](DEVICE_CHECK.en.md)｜日本語

一般消費者向け RV101／RV102 の Android 仕様は、すべて公開されているわけではありません。初回インストールの前後に、データ通信対応のデバッグケーブルを使用して、次の項目を確認してください。

## 1. デバイスとシステム

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
adb shell wm size
adb shell wm density
adb shell getprop sys.usb.config
adb shell dumpsys usb
```

コミュニティ情報から予想される値は、Android 12／API 32、`arm64-v8a`、480×640 表示です。異なる場合は、ビルド設定を調整できるように、コマンドの完全な出力を保存してください。

## 2. ファイル権限、MediaStore、メディアパス

```bash
adb shell ls -la /sdcard/DCIM
adb shell ls -la /sdcard/DCIM/Camera
adb shell ls -la /sdcard/DCIM/album
adb shell ls -la /sdcard/Pictures
adb shell ls -la /sdcard/Movies
adb shell content query --uri content://media/external/images/media --projection _id:_display_name:relative_path:owner_package_name
adb shell content query --uri content://media/external/video/media --projection _id:_display_name:relative_path:owner_package_name
```

判定：

- カメラのファイルが共有ストレージにある場合：アプリをインストールし、「すべてのファイルへのアクセス」を許可します。
- 特別な権限を許可していない場合：MediaStore の読み取り専用代替動作に限られ、変更に成功したように装ってはいけません。
- カメラのファイルが純正アプリの非公開ディレクトリだけにある場合：通常のサードパーティ APK は安全にアクセスできません。Rokid から公式メディア API／署名権限を取得する必要があります。

## 3. タッチパッドイベント

最初に入力デバイスを列挙し、アプリを前面に表示した状態でイベントを監視します（終了は `Ctrl+C`）。

```bash
adb shell getevent -pl
adb shell getevent -lt
```

前方／後方へのスワイプ、タップ、ダブルタップ、長押し、戻る、DPAD を 1 つずつ試し、アプリの選択、開く、戻る動作も同時に確認してください。製品版ファームウェアが shell からの `/dev/input` 読み取りを拒否する場合は、`adb shell dumpsys input` でデバイス／キー設定を保存し、各ジェスチャーに対する画面の反応を記録します。アプリは標準 DPAD、Generic Motion、Touch MotionEvent に対応しています。OEM 独自イベントが異なる場合は、実機の証拠に基づくマッピングが必要です。

## 4. 共有モード

USB：

```bash
adb forward --no-rebind tcp:8765 tcp:8765
curl --fail --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:8765/
adb forward --remove tcp:8765
```

上記の GET は `200` を出力する必要があります。

Wi‑Fi：自分のスマートフォンのテザリングだけでテストしてください。アプリが表示する IP が RFC1918 プライベートアドレスであること、共有停止後にポートが直ちに閉じること、10 分間操作がなければ自動停止することを確認します。

## 5. 破壊を伴う操作

テスト用ファイルだけを使い、名前変更、ゴミ箱への移動、復元、アップロードの中断、ファイル名の重複、空き容量不足、2GB を超える動画を検証します。処理に失敗しても公開済みの不完全なファイルが残らないことを確認してください。アプリは完全削除機能を提供しません。
