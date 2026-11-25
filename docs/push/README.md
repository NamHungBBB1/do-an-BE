# Push notification — phần việc bên mobile (Expo, Android, chạy demo)

Tài liệu này gửi cho bên `trieu-teach/seq-mobile`. Mục tiêu duy nhất của đợt này: **máy Android
nhận được 1 tin test từ Firebase Console**. Làm xong bước đó thì bên BE mới viết API đăng ký token.

Phạm vi đã chốt với nhóm: app **chỉ để demo, không phát hành Play Store, chỉ Android**. Nhờ vậy bỏ
được toàn bộ phần iOS/khoá ký/APNs.

- Project Firebase: tạo **mới**, riêng cho mobile, không đụng project `finteen-fa26` của web.
- App **không** dùng Firebase Auth. Đăng nhập vẫn bằng email/mật khẩu + JWT của BE như hiện tại.
- File `app.json` trong cùng thư mục này là **bản đề xuất đầy đủ**, thay thế `app.json` hiện tại.

## 1. Thay đổi so với `app.json` hiện tại

Chỉ thêm 3 chỗ, không đổi gì khác:

| Vị trí | Thêm |
|---|---|
| `expo.android.package` | `com.finteen.mobile` |
| `expo.android.googleServicesFile` | `./google-services.json` |
| `expo.plugins` | `["expo-notifications", { "color": "#208AEF" }]` |

Phần `ios` để **nguyên như cũ**, không thêm `bundleIdentifier` hay `googleServicesFile` — không làm
iOS nên không cần, và thêm đường dẫn tới file không tồn tại sẽ làm `expo prebuild` báo lỗi.

Repo đang ignore `/android` (CNG), nên `google-services.json` để ở **gốc repo**, không phải
`android/app/`. `expo prebuild` sẽ tự copy vào đúng chỗ.

Package name `com.finteen.mobile` chỉ là **đề xuất**. Vì không lên Play Store nên đổi lúc nào cũng
được (chỉ cần tạo lại app trên Firebase và tải lại `google-services.json`). Quan trọng là **chuỗi
trong `app.json` phải khớp từng ký tự** với package đã đăng ký trên Firebase.

## 2. Việc phải làm, theo thứ tự

> `expo-notifications` và `app.json` đã có trong PR
> https://github.com/trieu-teach/seq-mobile/pull/3. Nếu không dùng PR thì tự chạy
> `npx expo install expo-notifications` rồi sửa `app.json` theo mục 1.

1. Gửi cho người tạo project Firebase: **package name Android** (`com.finteen.mobile`) + tên app.
2. Nhận lại `google-services.json` → để ở **gốc repo** (ngang hàng `package.json`).
3. `npm ci` (nếu merge PR) rồi `npx expo prebuild --clean` và `npx expo run:android`.

Không cần EAS, không cần keystore release, không cần `eas.json`. Debug build là đủ.

## 3. Xin quyền và lấy token

Gọi sau khi app đã có phiên đăng nhập (token chỉ có ý nghĩa khi biết gửi cho tài khoản nào):

```ts
import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

export async function getPushToken(): Promise<string | null> {
  if (Platform.OS === 'android') {
    // Android 8+ bắt buộc phải có channel thì tin mới hiện.
    await Notifications.setNotificationChannelAsync('default', {
      name: 'Thông báo',
      importance: Notifications.AndroidImportance.HIGH,
    });
  }

  const { status } = await Notifications.requestPermissionsAsync();
  if (status !== 'granted') return null;

  // Token NATIVE (FCM) — đây là token BE lưu.
  const { data } = await Notifications.getDevicePushTokenAsync();
  return String(data);
}
```

Lưu ý:

- Dùng `getDevicePushTokenAsync()`, **không** dùng `getExpoPushTokenAsync()`. BE gửi trực tiếp qua
  Firebase Admin SDK (FCM HTTP v1), không qua Expo Push Service.
- **Không test được trên Expo Go** (từ SDK 53 Expo Go không còn nhận push từ project riêng). Phải
  dev build: `npx expo run:android`. Vẫn không cần EAS.
- Thiết bị: **máy thật** là chắc ăn nhất. Nếu dùng emulator thì phải là image **có Google Play**
  (Google APIs trở xuống không có Play Services → không có token FCM).
- Token đổi theo thời gian (xoá app, đổi máy). Chuẩn bị sẵn chỗ nhận token mới — BE sẽ có API đăng
  ký lại; tạm thời cứ in token ra log.

## 4. Test trước khi bàn giao

1. Build dev, đăng nhập, in token ra log.
2. Firebase Console (project **mới**) → **Messaging** → **Create campaign** → **Send test message**
   → dán token → gửi.
3. Máy phải hiện thông báo trong vài giây, bấm vào mở được app.

Bước 3 fail thì **đừng** chuyển sang bước BE: lỗi nằm ở app hoặc `google-services.json`.

## 5. Hợp đồng dữ liệu (để app chuẩn bị trước)

Khi BE gửi, payload sẽ có dạng:

```json
{
  "notification": { "title": "Minh vừa xong chương 3", "body": "Chương 3 · 12/40 chương" },
  "data": {
    "notificationId": "uuid",
    "type": "CHAPTER_COMPLETED",
    "actionType": "OPEN_LEARNER_REPORT",
    "resourceId": "uuid"
  }
}
```

- `type`: `CHAPTER_COMPLETED` | `QUIZ_SUBMITTED` | `REPORT_READY`
- `actionType`: `OPEN_LEARNER_REPORT` (resourceId = slotId) | `OPEN_GROUP_REPORT` (groupId) |
  `OPEN_QUIZ_RESULTS` (quizId) | `OPEN_LEARNER_LIST` (groupId)
- `notificationId` để **chống trùng**: FCM có thể gửi lặp, app bỏ qua nếu đã hiện id đó.
- Khi bấm vào tin, app đọc `data.actionType` + `data.resourceId` để điều hướng, rồi gọi
  `PATCH /api/notifications/{id}/read` của BE.

## 6. Không cần làm

Firebase Auth, Firestore, Analytics, APNs, iOS signing, EAS, ProGuard, Play Store listing. Project
mới chỉ dùng đúng một thứ: **Cloud Messaging (FCM)**.
