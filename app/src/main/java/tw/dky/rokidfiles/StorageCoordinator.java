package tw.dky.rokidfiles;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.IOException;

import tw.dky.rokidfiles.storage.AdvancedDirectGateway;
import tw.dky.rokidfiles.storage.ManagedStorageGateway;
import tw.dky.rokidfiles.storage.MediaStoreGateway;
import tw.dky.rokidfiles.storage.StorageGateway;

/** 集中選擇最小權限且目前可用的儲存後端。 */
public final class StorageCoordinator {
    public static final class Selection {
        private final StorageGateway gateway;
        private final String explanation;

        private Selection(StorageGateway gateway, String explanation) {
            this.gateway = gateway;
            this.explanation = explanation;
        }

        public StorageGateway getGateway() {
            return gateway;
        }

        public String getExplanation() {
            return explanation;
        }
    }

    private StorageCoordinator() {
    }

    public static Selection select(Context context) {
        Context app = context.getApplicationContext();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                AdvancedDirectGateway gateway = new AdvancedDirectGateway(app);
                if (gateway.isAvailable()) {
                    return new Selection(
                            new ManagedStorageGateway(app, gateway),
                            "媒體白名單檔案模式");
                }
            } catch (IOException ignored) {
                // 外部儲存空間尚未掛載或 canonical root 無法解析。
            }
        }

        if (app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            MediaStoreGateway gateway = new MediaStoreGateway(app);
            if (gateway.isAvailable()) {
                return new Selection(
                        new ManagedStorageGateway(app, gateway),
                        "MediaStore：目前只能瀏覽與下載");
            }
        }

        return new Selection(null, "尚未允許所有檔案存取");
    }
}
