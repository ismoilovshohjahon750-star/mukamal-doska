require('dotenv').config();
const express = require('express');
const cors = require('cors');
const TelegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const BOT_TOKEN = (process.env.TELEGRAM_BOT_TOKEN || '').trim();
const SUPER_ADMIN_ID = parseInt(process.env.SUPER_ADMIN_ID || '8453381252', 10);

const DB_FILE = path.join(__dirname, 'data', 'smartboard_db.json');

// Papka mavjudligini ta'minlash
if (!fs.existsSync(path.join(__dirname, 'data'))) {
  fs.mkdirSync(path.join(__dirname, 'data'), { recursive: true });
}

// Baza strukturasini yuklash yoki yaratish (Shablonlarsiz, toza)
function loadDatabase() {
  const defaultData = {
    superAdminId: SUPER_ADMIN_ID,
    itAdmins: [],
    teachers: [],
    rooms: [],
    logs: []
  };

  try {
    if (fs.existsSync(DB_FILE)) {
      const content = fs.readFileSync(DB_FILE, 'utf8');
      return JSON.parse(content);
    }
  } catch (err) {
    console.error('Baza yuklashda xatolik:', err);
  }

  saveDatabase(defaultData);
  return defaultData;
}

function saveDatabase(data) {
  try {
    fs.writeFileSync(DB_FILE, JSON.stringify(data, null, 2), 'utf8');
  } catch (err) {
    console.error('Baza saqlashda xatolik:', err);
  }
}

const db = loadDatabase();

// Formatlangan vaqt: HH:mm:ss/dd/MM/yyyy
function getFormattedTime() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  const date = `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`;
  return `${time}/${date}`;
}

// Kirish jurnali yozish
function logAccess(userId, userName, role, action) {
  const entry = {
    timestamp: getFormattedTime(),
    userId: Number(userId),
    userName: userName || 'Noma\'lum',
    role: role || 'Foydalanuvchi',
    action: action || ''
  };
  db.logs.unshift(entry);
  if (db.logs.length > 500) db.logs = db.logs.slice(0, 500);
  saveDatabase(db);
}

// Foydalanuvchi huquqini tekshirish
function checkUserRole(userId) {
  const numId = Number(userId);
  if (numId === SUPER_ADMIN_ID) return { isAuthorized: true, role: 'SUPER_ADMIN' };
  const it = db.itAdmins.find((a) => a.id === numId);
  if (it) return { isAuthorized: true, role: 'IT_ADMIN', name: it.name };
  const t = db.teachers.find((tc) => tc.id === numId);
  if (t) return { isAuthorized: true, role: 'TEACHER', name: t.name };
  return { isAuthorized: false, role: 'UNAUTHORIZED' };
}

// ==========================================
// DOSKALAR VA BUYRUQLAR XOTIRASI
// ==========================================
// Har bir doska: { mac, boardId, roomName, isLocked, secondsRemaining, teacherName, lastSeen, ip }
const connectedBoards = new Map();

// Kutilayotgan buyruqlar navbati: targetKey (mac yoki boardId) -> Array of commands
const pendingCommands = new Map();

function enqueueCommand(targetKey, command) {
  const key = String(targetKey).trim().toUpperCase();
  if (!pendingCommands.has(key)) {
    pendingCommands.set(key, []);
  }
  const cmd = {
    id: `cmd_${Date.now()}_${Math.random().toString(36).substr(2, 5)}`,
    timestamp: Date.now(),
    ...command
  };
  pendingCommands.get(key).push(cmd);
  return cmd;
}

function broadcastCommand(command) {
  enqueueCommand('ALL', command);
  for (const key of connectedBoards.keys()) {
    enqueueCommand(key, command);
  }
}

// ==========================================
// EXPRESS SERVER SOZLAMALARI
// ==========================================
const app = express();
app.use(cors());
app.use(express.json());

// Server holati va Render keep-alive
app.get('/', (req, res) => {
  res.json({
    status: 'online',
    service: 'SmartBoard 86 Locker - Render Central Server',
    time: getFormattedTime(),
    activeBoardsCount: connectedBoards.size,
    roomsCount: db.rooms.length
  });
});

app.get('/health', (req, res) => {
  res.status(200).send('OK');
});

// Doska ro'yxati va holati
app.get('/api/board/list', (req, res) => {
  const now = Date.now();
  const list = [];
  for (const [key, b] of connectedBoards.entries()) {
    const isOnline = now - b.lastSeen < 15000; // 15 soniya ichida xabar kelgan bo'lsa
    list.push({
      ...b,
      isOnline
    });
  }
  res.json({ boards: list, rooms: db.rooms });
});

// Doska uchun POLLING endpoint (Heartbeat + Buyruqlarni olish)
// APK har 1.5 - 2 soniyada shu yerga so'rov yuboradi
app.post('/api/board/poll', (req, res) => {
  const { mac, boardId, roomName, isLocked, secondsRemaining, teacherName, appVersion } = req.body;
  if (!mac && !boardId) {
    return res.status(400).json({ error: 'mac yoki boardId talab qilinadi' });
  }

  const primaryKey = (mac || boardId).trim().toUpperCase();
  const altKey = (boardId || mac).trim().toUpperCase();
  const cleanMacNoColon = (mac || '').replace(/:/g, '').toUpperCase();

  const boardInfo = {
    mac: mac || 'N/A',
    boardId: boardId || 'N/A',
    roomName: roomName || 'Noma\'lum xona',
    isLocked: !!isLocked,
    secondsRemaining: Number(secondsRemaining) || 0,
    teacherName: teacherName || '',
    appVersion: appVersion || '1.0',
    lastSeen: Date.now(),
    ip: req.ip || req.headers['x-forwarded-for'] || ''
  };

  connectedBoards.set(primaryKey, boardInfo);

  // Agar bu xona bazada bo'lmasa, uni avtomatik qo'shish
  const existingRoom = db.rooms.find(
    (r) => r.id.toUpperCase() === primaryKey || r.id.toUpperCase() === altKey
  );
  if (!existingRoom && roomName) {
    db.rooms.push({
      id: boardId || mac,
      name: roomName,
      addedBy: 'Avtomatik ulanish (Doska)',
      addedAt: getFormattedTime()
    });
    saveDatabase(db);
  }

  // Ushbu doska uchun kutilayotgan buyruq bormi?
  let targetCmd = null;
  const keysToCheck = [primaryKey, altKey, cleanMacNoColon, 'ALL'];

  for (const k of keysToCheck) {
    const cmds = pendingCommands.get(k);
    if (cmds && cmds.length > 0) {
      targetCmd = cmds.shift(); // Birinchi buyruqni olamiz
      break;
    }
  }

  res.json({
    status: 'ok',
    serverTime: getFormattedTime(),
    command: targetCmd
  });
});

// Doska buyruqni qabul qilganini tasdiqlash
app.post('/api/board/ack', (req, res) => {
  const { commandId } = req.body;
  res.json({ status: 'acknowledged', commandId });
});

// Admin yoki tashqi tizim uchun doskani ochish API
app.post('/api/board/unlock', (req, res) => {
  const { targetRoomId, teacherName, durationMinutes, apiKey } = req.body;
  const target = targetRoomId || 'ALL';
  const duration = Number(durationMinutes) || 45;
  const teacher = teacherName || 'Mas\'ul O\'qituvchi';

  enqueueCommand(target, {
    type: 'UNLOCK',
    teacherName: teacher,
    durationMinutes: duration,
    targetRoomId: target
  });

  logAccess(0, teacher, 'API', `Doska ochildi: ${target} (${duration} daqiqa)`);
  res.json({ status: 'enqueued', targetRoomId: target, durationMinutes: duration });
});

// Doskani qulflash API
app.post('/api/board/lock', (req, res) => {
  const { targetRoomId, reason } = req.body;
  const target = targetRoomId || 'ALL';

  enqueueCommand(target, {
    type: 'LOCK',
    targetRoomId: target,
    reason: reason || 'Admin tomonidan qulflandi'
  });

  logAccess(0, 'Admin', 'API', `Doska qulflandi: ${target}`);
  res.json({ status: 'enqueued', targetRoomId: target });
});

// ==========================================
// TELEGRAM BOT SOZLAMALARI
// ==========================================
let bot = null;

if (BOT_TOKEN && BOT_TOKEN.includes(':') && !BOT_TOKEN.startsWith('YOUR_')) {
  try {
    bot = new TelegramBot(BOT_TOKEN, { polling: true });
    console.log('Telegram Bot muvaffaqiyatli ishga tushirildi (Polling).');
  } catch (e) {
    console.error('Telegram botni ishga tushirishda xatolik:', e.message);
  }
} else {
  console.log('-------------------------------------------------------------');
  console.log('ℹ️ TELEGRAM_BOT_TOKEN kiritilmagan.');
  console.log('Iltimos, Render.com -> Environment bo\'limiga o\'z Telegram bot tokeningizni kiriting.');
  console.log('-------------------------------------------------------------');
}

if (bot) {
  let is401Reported = false;
  bot.on('polling_error', (error) => {
    if (error.code === 'ETELEGRAM' && (error.message.includes('401') || (error.response && error.response.statusCode === 401))) {
      if (!is401Reported) {
        console.error('-------------------------------------------------------------');
        console.error('❌ XATOLIK: Telegram Bot Token noto\'g\'ri yoki bekor qilingan (401 Unauthorized)!');
        console.error('Iltimos, Render.com -> Environment bo\'limida TELEGRAM_BOT_TOKEN ga');
        console.error('Telegram @BotFather dan olingan yangi haqiqiy tokenni kiriting.');
        console.error('-------------------------------------------------------------');
        is401Reported = true;
      }
      bot.stopPolling().catch(() => {});
      return;
    }
    console.error('Telegram Polling Error:', error.code, error.message);
  });

  // Inline tugmalar generatori
  function buildMainKeyboard(role) {
    const keyboard = [
      [{ text: '🟢 Darsni boshlash (Ochish)' }, { text: '🔒 Doskani qulflash' }],
      [{ text: '⏱ +10 daqiqa qo\'shish' }, { text: '📊 Doskalar holati' }]
    ];

    if (role === 'SUPER_ADMIN' || role === 'IT_ADMIN') {
      keyboard.push([
        { text: '🏫 Xonalar ro\'yxati' },
        { text: '👥 O\'qituvchilar' }
      ]);
      keyboard.push([
        { text: '📋 Kirish jurnali' },
        { text: '⚙️ Yangi xona qo\'shish' }
      ]);
    }

    return {
      keyboard,
      resize_keyboard: true
    };
  }

  // Xonalar tanlash inline klaviaturasi
  function buildRoomSelectionKeyboard(actionPrefix, includeAll = false) {
    const inline_keyboard = [];
    const now = Date.now();

    if (includeAll) {
      inline_keyboard.push([{ text: '🌐 Barcha doskalar (HAMMASI)', callback_data: `${actionPrefix}:ALL` }]);
    }

    // Bazadagi xonalar
    for (const room of db.rooms) {
      const b = connectedBoards.get(room.id.toUpperCase());
      const isOnline = b && now - b.lastSeen < 15000;
      const statusIcon = isOnline ? (b.isLocked ? '🟢 Qulfda' : '🟡 Darsda') : '🔴 Oflayn';
      inline_keyboard.push([
        {
          text: `${room.name} [${statusIcon}]`,
          callback_data: `${actionPrefix}:${room.id}`
        }
      ]);
    }

    inline_keyboard.push([{ text: '❌ Bekor qilish', callback_data: 'cmd_cancel' }]);
    return { inline_keyboard };
  }

  // Bot xabarlarini qayta ishlash
  bot.on('message', async (msg) => {
    const chatId = msg.chat.id;
    const userId = msg.from.id;
    const text = (msg.text || '').trim();

    const userName = [msg.from.first_name, msg.from.last_name].filter(Boolean).join(' ') ||
      (msg.from.username ? `@${msg.from.username}` : `Foydalanuvchi (${userId})`);

    // Ruxsat tekshiruvi
    const { isAuthorized, role, name: customName } = checkUserRole(userId);
    const displayName = customName || userName;

    if (!isAuthorized) {
      return bot.sendMessage(
        chatId,
        `Kechirasiz, sizda ushbu botdan foydalanish huquqi yo'q.\n\nSizning Telegram ID: <code>${userId}</code>\n\nRuxsat olish uchun maktab ma'muriyati yoki IT adminga murojaat qiling.`,
        { parse_mode: 'HTML' }
      );
    }

    // 1. /start yoki /help
    if (text === '/start' || text === '/help' || text === 'yordam') {
      const msgText = `<b>SmartBoard 86" Doska Boshqaruv Markazi</b>\n\n` +
        `Assalomu alaykum, <b>${displayName}</b>!\n` +
        `Rolingiz: <b>${role}</b>\n` +
        `Faol doskalar: <b>${connectedBoards.size} ta</b>\n` +
        `Server holati: 🟢 <b>Render Server Faol</b>\n\n` +
        `Quyidagi tugmalar orqali darsni boshlash yoki doskani boshqarishingiz mumkin:`;

      return bot.sendMessage(chatId, msgText, {
        parse_mode: 'HTML',
        reply_markup: buildMainKeyboard(role)
      });
    }

    // 2. Darsni boshlash (Ochish)
    if (
      text === '🟢 Darsni boshlash (Ochish)' ||
      text.toLowerCase().includes('darsni boshlash') ||
      text.toLowerCase().includes('dars boshlash') ||
      text === '/unlock' ||
      text.toLowerCase() === 'ochish'
    ) {
      if (db.rooms.length === 0) {
        // Xonalar bo'lmasa umumiy ochish
        broadcastCommand({
          type: 'UNLOCK',
          teacherName: displayName,
          durationMinutes: 45
        });
        logAccess(userId, displayName, role, 'Barcha doskalar ochildi (45 min)');
        return bot.sendMessage(
          chatId,
          `<b>86" Interaktiv Doska ochildi!</b>\n\nO'qituvchi: <b>${displayName}</b>\nDars vaqti: <b>45 daqiqa</b>\nVaqt: <code>${getFormattedTime()}</code>`,
          { parse_mode: 'HTML' }
        );
      }

      if (db.rooms.length === 1) {
        const singleRoom = db.rooms[0];
        enqueueCommand(singleRoom.id, {
          type: 'UNLOCK',
          teacherName: displayName,
          durationMinutes: 45,
          targetRoomId: singleRoom.id
        });
        logAccess(userId, displayName, role, `Dars boshlandi: ${singleRoom.name}`);
        return bot.sendMessage(
          chatId,
          `<b>86" Interaktiv Doska ochildi!</b>\n\nXona: <b>${singleRoom.name}</b>\nO'qituvchi: <b>${displayName}</b>\nDars vaqti: <b>45 daqiqa</b>\nVaqt: <code>${getFormattedTime()}</code>`,
          { parse_mode: 'HTML' }
        );
      }

      // Ko'p xonalar bo'lsa tanlash
      return bot.sendMessage(
        chatId,
        `<b>Qaysi xonadagi doskani ochmoqchisiz?</b>\n\nIltimos, dars o'tadigan xonangizni tanlang:`,
        {
          parse_mode: 'HTML',
          reply_markup: buildRoomSelectionKeyboard('unlock_room', false)
        }
      );
    }

    // 3. Doskani qulflash
    if (
      text === '🔒 Doskani qulflash' ||
      text.toLowerCase().includes('qulflash') ||
      text === '/lock' ||
      text.toLowerCase() === 'qulf'
    ) {
      return bot.sendMessage(
        chatId,
        `<b>Qaysi xonadagi doskani qulflamoqchisiz?</b>`,
        {
          parse_mode: 'HTML',
          reply_markup: buildRoomSelectionKeyboard('lock_room', true)
        }
      );
    }

    // 4. +10 daqiqa qo'shish
    if (text === '⏱ +10 daqiqa qo\'shish' || text === '/extend' || text.includes('10 daqiqa')) {
      broadcastCommand({ type: 'EXTEND', minutes: 10 });
      logAccess(userId, displayName, role, '+10 daqiqa qo\'shildi');
      return bot.sendMessage(
        chatId,
        `<b>Dars vaqti 10 daqiqaga uzaytirildi!</b>\nVaqt: <code>${getFormattedTime()}</code>`,
        { parse_mode: 'HTML' }
      );
    }

    // 5. Doskalar holati
    if (text === '📊 Doskalar holati' || text === '/status') {
      const now = Date.now();
      let statusMsg = `<b>Doskalar va Xonalar Holati:</b>\n\n`;

      if (db.rooms.length === 0) {
        statusMsg += `<i>Hozircha birorta ham xona qo'shilmagan.</i>\n`;
      } else {
        db.rooms.forEach((r, idx) => {
          const b = connectedBoards.get(r.id.toUpperCase());
          const isOnline = b && now - b.lastSeen < 15000;
          const statusStr = isOnline
            ? (b.isLocked
                ? '🟢 Qulfda (Kutish rejimida)'
                : `🟡 DARS DAVOM ETMOQDA (${Math.ceil(b.secondsRemaining / 60)} daqiqa qoldi) - O'qituvchi: ${b.teacherName}`)
            : '🔴 Oflayn (Ulanmagan)';

          statusMsg += `${idx + 1}. <b>${r.name}</b> (<code>${r.id}</code>)\n   Holat: ${statusStr}\n\n`;
        });
      }

      statusMsg += `Server vaqti: <code>${getFormattedTime()}</code>`;
      return bot.sendMessage(chatId, statusMsg, { parse_mode: 'HTML' });
    }

    // 6. Xonalar ro'yxati (Admin)
    if (text === '🏫 Xonalar ro\'yxati' || text === '/rooms') {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      let msgText = `<b>Ro'yxatdagi xonalar (${db.rooms.length} ta):</b>\n\n`;
      db.rooms.forEach((r, idx) => {
        msgText += `${idx + 1}. <b>${r.name}</b>\n   ID: <code>${r.id}</code>\n   Qo'shgan: ${r.addedBy}\n   O'chirish: <code>/del_room ${r.id}</code>\n\n`;
      });
      msgText += `Yangi qo'shish uchun: <code>/add_room &lt;ID&gt; &lt;Nomi&gt;</code>`;
      return bot.sendMessage(chatId, msgText, { parse_mode: 'HTML' });
    }

    // 7. Yangi xona qo'shish
    if (text.startsWith('/add_room') || text === '⚙️ Yangi xona qo\'shish') {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      const parts = text.replace('/add_room', '').trim().split(/\s+/);
      const roomId = parts[0]?.trim();
      const roomName = parts.slice(1).join(' ').trim();

      if (!roomId || !roomName) {
        return bot.sendMessage(
          chatId,
          `<b>Xona qo'shish formati:</b>\n\n<code>/add_room &lt;XONA_ID&gt; &lt;XONA_NOMI&gt;</code>\n\nMisol:\n<code>/add_room 101-XONA 101-Fizika xonasi</code>`,
          { parse_mode: 'HTML' }
        );
      }

      const existing = db.rooms.find((r) => r.id.toUpperCase() === roomId.toUpperCase());
      if (existing) {
        existing.name = roomName;
      } else {
        db.rooms.push({
          id: roomId.toUpperCase(),
          name: roomName,
          addedBy: displayName,
          addedAt: getFormattedTime()
        });
      }
      saveDatabase(db);
      logAccess(userId, displayName, role, `Xona qo'shildi: ${roomName} (${roomId})`);

      return bot.sendMessage(
        chatId,
        `<b>Yangi xona muvaffaqiyatli saqlandi!</b>\n\nXona: <b>${roomName}</b>\nID: <code>${roomId.toUpperCase()}</code>`,
        { parse_mode: 'HTML' }
      );
    }

    // 8. Xonani o'chirish
    if (text.startsWith('/del_room')) {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      const targetId = text.replace('/del_room', '').trim().toUpperCase();
      const index = db.rooms.findIndex((r) => r.id.toUpperCase() === targetId);
      if (index !== -1) {
        const removed = db.rooms.splice(index, 1)[0];
        saveDatabase(db);
        logAccess(userId, displayName, role, `Xona o'chirildi: ${removed.name}`);
        return bot.sendMessage(chatId, `Xona (${removed.name}) o'chirildi.`);
      }
      return bot.sendMessage(chatId, `Xona topilmadi: <code>${targetId}</code>`, { parse_mode: 'HTML' });
    }

    // 9. O'qituvchilar ro'yxati
    if (text === '👥 O\'qituvchilar' || text === '/teachers') {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      let msgText = `<b>Ruxsat berilgan o'qituvchilar (${db.teachers.length} ta):</b>\n\n`;
      db.teachers.forEach((t, idx) => {
        msgText += `${idx + 1}. <b>${t.name}</b>\n   ID: <code>${t.id}</code>\n   O'chirish: <code>/del_teacher ${t.id}</code>\n\n`;
      });
      msgText += `Yangi qo'shish: <code>/add_teacher &lt;Telegram_ID&gt; &lt;Ism&gt;</code>`;
      return bot.sendMessage(chatId, msgText, { parse_mode: 'HTML' });
    }

    // 10. Yangi o'qituvchi qo'shish
    if (text.startsWith('/add_teacher')) {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      const parts = text.replace('/add_teacher', '').trim().split(/\s+/);
      const targetId = parseInt(parts[0], 10);
      const targetName = parts.slice(1).join(' ').trim();

      if (!targetId || !targetName) {
        return bot.sendMessage(
          chatId,
          `Format: <code>/add_teacher &lt;Telegram_ID&gt; &lt;Ism Familiya&gt;</code>\n\nMisol:\n<code>/add_teacher 123456789 Jasur Rahim</code>`,
          { parse_mode: 'HTML' }
        );
      }

      const existing = db.teachers.find((t) => t.id === targetId);
      if (existing) {
        existing.name = targetName;
      } else {
        db.teachers.push({ id: targetId, name: targetName, addedAt: getFormattedTime() });
      }
      saveDatabase(db);
      logAccess(userId, displayName, role, `O'qituvchi qo'shildi: ${targetName} (${targetId})`);

      return bot.sendMessage(
        chatId,
        `<b>O'qituvchi muvaffaqiyatli qo'shildi!</b>\n\nIsm: <b>${targetName}</b>\nID: <code>${targetId}</code>`,
        { parse_mode: 'HTML' }
      );
    }

    // 11. O'qituvchini o'chirish
    if (text.startsWith('/del_teacher')) {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      const targetId = parseInt(text.replace('/del_teacher', '').trim(), 10);
      const index = db.teachers.findIndex((t) => t.id === targetId);
      if (index !== -1) {
        const removed = db.teachers.splice(index, 1)[0];
        saveDatabase(db);
        logAccess(userId, displayName, role, `O'qituvchi o'chirildi: ${removed.name}`);
        return bot.sendMessage(chatId, `O'qituvchi (${removed.name}) o'chirildi.`);
      }
      return bot.sendMessage(chatId, `O'qituvchi topilmadi.`);
    }

    // 12. Kirish jurnali
    if (text === '📋 Kirish jurnali' || text === '/logs') {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      const logs = db.logs.slice(0, 15);
      if (logs.length === 0) {
        return bot.sendMessage(chatId, 'Hozircha jurnal bo\'sh.');
      }
      let logMsg = `<b>Tizimga kirish jurnali (Oxirgi 15 ta):</b>\n\n`;
      logs.forEach((l, idx) => {
        logMsg += `${idx + 1}. [<code>${l.timestamp}</code>] <b>${l.userName}</b>\n   Amal: <i>${l.action}</i>\n\n`;
      });
      return bot.sendMessage(chatId, logMsg, { parse_mode: 'HTML' });
    }
  });

  // Callback query larni qayta ishlash
  bot.on('callback_query', async (query) => {
    const chatId = query.message?.chat?.id;
    const userId = query.from.id;
    const data = query.data || '';

    try {
      await bot.answerCallbackQuery(query.id);
    } catch (_) {}

    const { isAuthorized, role, name: customName } = checkUserRole(userId);
    const userName = [query.from.first_name, query.from.last_name].filter(Boolean).join(' ') || `Foydalanuvchi (${userId})`;
    const displayName = customName || userName;

    if (!isAuthorized) {
      return bot.sendMessage(chatId, 'Ruxsat yo\'q.');
    }

    if (data === 'cmd_cancel') {
      return bot.sendMessage(chatId, 'Amal bekor qilindi.');
    }

    // Xonani ochish callback
    if (data.startsWith('unlock_room:')) {
      const targetRoomId = data.replace('unlock_room:', '').trim().toUpperCase();
      const room = db.rooms.find((r) => r.id.toUpperCase() === targetRoomId);
      const roomTitle = room ? room.name : targetRoomId;

      enqueueCommand(targetRoomId, {
        type: 'UNLOCK',
        teacherName: displayName,
        durationMinutes: 45,
        targetRoomId
      });

      logAccess(userId, displayName, role, `Dars boshlandi: ${roomTitle} (45 daqiqa)`);

      return bot.sendMessage(
        chatId,
        `<b>86" Interaktiv Doska ochildi!</b>\n\nXona: <b>${roomTitle}</b>\nO'qituvchi: <b>${displayName}</b>\nVaqt: <b>45 daqiqa</b>\nSana: <code>${getFormattedTime()}</code>\n\n<i>Dars yakunlangach "🔒 Doskani qulflash" tugmasi orqali yopishingiz mumkin.</i>`,
        { parse_mode: 'HTML' }
      );
    }

    // Xonani qulflash callback
    if (data.startsWith('lock_room:')) {
      const targetRoomId = data.replace('lock_room:', '').trim().toUpperCase();
      const isAll = targetRoomId === 'ALL';
      const room = db.rooms.find((r) => r.id.toUpperCase() === targetRoomId);
      const roomTitle = isAll ? 'Barcha xonalar' : (room ? room.name : targetRoomId);

      enqueueCommand(targetRoomId, {
        type: 'LOCK',
        targetRoomId
      });

      logAccess(userId, displayName, role, `Doska qulflandi: ${roomTitle}`);

      return bot.sendMessage(
        chatId,
        `<b>Doska qulflanadi!</b>\n\nXona: <b>${roomTitle}</b>\nMas'ul: <b>${displayName}</b>\nVaqt: <code>${getFormattedTime()}</code>`,
        { parse_mode: 'HTML' }
      );
    }
  });
}

// Serverni tinglash
app.listen(PORT, '0.0.0.0', () => {
  console.log(`=========================================`);
  console.log(`SmartBoard Render Server ishga tushdi!`);
  console.log(`Port: ${PORT}`);
  console.log(`Super Admin ID: ${SUPER_ADMIN_ID}`);
  console.log(`Baza fayli: ${DB_FILE}`);
  console.log(`=========================================`);
});
