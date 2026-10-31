# SmartBoard 86 Locker — Markaziy Render Server va Telegram Bot

Ushbu server **SmartBoard 86" Doska** ilovasi uchun markaziy boshqaruv markazidir.
2 ta yoki 50 ta doska bo'lganda ham Telegram Bot bitta joyda (Render serverida) xatosiz ishlaydi va Telegram API 409 Conflict ("terminated by other getUpdates request") xatosi butunlay bartaraf etiladi.

---

## 🚀 Render.com ga O'rnatish (3 qadamda)

### 1-qadam: GitHub ga yuklash
1. Ushbu `render-server` papkasini alohida GitHub omboriga (repository) yuklang (masalan `smartboard-bot-server`).

### 2-qadam: Render.com da Web Service ochish
1. [render.com](https://render.com) saytiga kiring va kiring (Login/Sign up).
2. **"New +"** tugmasini bosing -> **"Web Service"** ni tanlang.
3. Yuqoridagi GitHub repository-ni tanlang (Connect).
4. Quyidagi parametrlarni kiriting:
   - **Name**: `smartboard-bot-server` (yoki ixtiyoriy nom)
   - **Region**: Frankfurt (EU Central) yoki Singapore
   - **Runtime**: `Node`
   - **Build Command**: `npm install`
   - **Start Command**: `node server.js`
   - **Instance Type**: `Free`

### 3-qadam: Environment Variables (O'zgaruvchilar)
Pastdagi **Environment Variables** bo'limiga quyidagilarni kiriting:
- `TELEGRAM_BOT_TOKEN`: `8882740210:AAEgrUFC2ooQ-RRuH10SAqq7Fvcvhq0D_6Y` (yoki o'zingizning bot tokeningiz)
- `SUPER_ADMIN_ID`: `8453381252`

So'ng **"Create Web Service"** tugmasini bosing.
1-2 daqiqada server ishga tushadi va sizga quyidagicha havola beradi:
`https://smartboard-bot-server.onrender.com`

---

## 📱 Doskada (APK) Sozlash:

1. Doskani yoqing va qulflangan ekranda **"Admin PIN"** tugmasini bosing (Standart PIN: `1234`).
2. **"Sozlamalar"** (Settings) bo'limiga o'ting.
3. **"Ulanish usuli"** ni **"🟢 Render Server orqali"** qilib belgilang.
4. **"Render Server Manzili"** ga Render bergan URL manzilini kiriting:
   `https://smartboard-bot-server.onrender.com`
5. **"Server aloqasini tekshirish"** tugmasini bosing — "Ulanish muvaffaqiyatli!" xabari chiqadi.
6. **"Saqlash"** tugmasini bosing.

Endi 2 ta doska bo'lsa ham:
- 1-doska: Xona ID = `101-XONA`
- 2-doska: Xona ID = `102-XONA`
O'qituvchi Telegram botda aynan qaysi xona doskasini ochishni tanlaydi va o'sha doska avtomatik ochiladi!
