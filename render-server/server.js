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

// Baza strukturasini yuklash yoki yaratish
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
      const parsed = JSON.parse(content);
      // Noto'g'ri yoki shablon ma'lumotlarni tozalash
      if (Array.isArray(parsed.rooms)) {
        parsed.rooms = parsed.rooms.filter(
          (r) => r && r.id && !r.id.includes('Yangi') && r.name !== 'Yangi xona qo\'shish' && r.id.length >= 4
        );
      }
      return {
        ...defaultData,
        ...parsed,
        superAdminId: SUPER_ADMIN_ID
      };
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
  if (numId === SUPER_ADMIN_ID) return { isAuthorized: true, role: 'SUPER_ADMIN', name: 'Bosh Admin' };
  const it = db.itAdmins.find((a) => a.id === numId);
  if (it) return { isAuthorized: true, role: 'IT_ADMIN', name: it.name };
  const t = db.teachers.find((tc) => tc.id === numId);
  if (t) return { isAuthorized: true, role: 'TEACHER', name: t.name };
  return { isAuthorized: false, role: 'UNAUTHORIZED' };
}

// ==========================================
// DOSKALAR VA BUYRUQLAR XOTIRASI
// ==========================================
// Har bir doska ma'lumotlari: boardKey -> { mac, boardId, hardwareId, roomName, isLocked, secondsRemaining, teacherName, lastSeen, ip }
const connectedBoards = new Map();

// Har bir doska uchun ALOHIDA kutilayotgan buyruqlar navbati:
// boardKey -> Array of command objects
const boardQueues = new Map();

// Adminlarga xabar yuborilgan yangi doskalar
const notifiedNewBoards = new Set();

// Admin bilan xona nomini kiritish suhbat holati: chatId -> targetMac
const awaitingRoomSetupForChat = new Map();

// Doska uchun asosiy unikal kalitni aniqlash
function resolveBoardKey(mac, boardId, hardwareId, ip) {
  const cleanMac = (mac || '').trim().toUpperCase();
  const cleanBoardId = (boardId || '').trim().toUpperCase();
  const cleanHwId = (hardwareId || '').trim().toUpperCase();

  if (cleanMac && cleanMac !== 'UNKNOWN' && cleanMac.length >= 10 && cleanMac !== '02:00:00:00:00:00') {
    return cleanMac;
  }
  if (cleanBoardId && cleanBoardId.startsWith('BOARD_')) {
    return cleanBoardId;
  }
  if (cleanHwId && cleanHwId !== 'UNKNOWN') {
    return cleanHwId;
  }
  return cleanBoardId || cleanHwId || `BOARD_IP_${(ip || 'LOCAL').replace(/[^a-zA-Z0-9]/g, '_')}`;
}

// Buyruqlarni xavfsiz va qat'iy ravishda aniq bitta doskaga yoki barchasiga yo'naltirish
function enqueueCommandForTarget(target, command) {
  const cleanTarget = String(target || 'ALL').trim().toUpperCase();
  const isAll = cleanTarget === 'ALL' || cleanTarget === '';

  const cmdId = `cmd_${Date.now()}_${Math.random().toString(36).substring(2, 8)}`;
  const baseCmd = {
    id: cmdId,
    timestamp: Date.now(),
    ...command,
    targetRoomId: cleanTarget
  };

  if (isAll) {
    // Barcha doskalarga yuborish: Har bir ulangan doskaga ALOHIDA o'z navbatiga qo'yiladi
    const allKeys = new Set();
    for (const key of connectedBoards.keys()) {
      allKeys.add(key);
    }
    for (const r of db.rooms) {
      allKeys.add(r.id.toUpperCase());
    }

    for (const key of allKeys) {
      if (!boardQueues.has(key)) boardQueues.set(key, []);
      boardQueues.get(key).push({ ...baseCmd, targetKey: key });
    }
    return baseCmd;
  }

  // Aniq bitta doska yoki xonaga yo'naltirish
  const matchedBoardKeys = new Set();
  matchedBoardKeys.add(cleanTarget);

  // Bazadagi xonalardan qidirish
  const matchedRooms = db.rooms.filter((r) => {
    const rId = r.id.trim().toUpperCase();
    const rName = r.name.trim().toUpperCase();
    return rId === cleanTarget ||
      rName === cleanTarget ||
      rId.replace(/:/g, '') === cleanTarget.replace(/:/g, '');
  });

  for (const r of matchedRooms) {
    matchedBoardKeys.add(r.id.trim().toUpperCase());
  }

  // Ulangan doskalar ichidan ham qidirish
  for (const [key, b] of connectedBoards.entries()) {
    const bKey = key.toUpperCase();
    const bMac = (b.mac || '').toUpperCase();
    const bBoardId = (b.boardId || '').toUpperCase();
    const bRoomName = (b.roomName || '').trim().toUpperCase();

    const matchesThisBoard =
      bKey === cleanTarget ||
      bMac === cleanTarget ||
      bBoardId === cleanTarget ||
      (bRoomName && bRoomName === cleanTarget) ||
      (bMac && bMac.replace(/:/g, '') === cleanTarget.replace(/:/g, '')) ||
      matchedRooms.some((r) => r.id.toUpperCase() === bKey || (bRoomName && r.name.trim().toUpperCase() === bRoomName));

    if (matchesThisBoard) {
      matchedBoardKeys.add(bKey);
    }
  }

  for (const k of matchedBoardKeys) {
    if (!boardQueues.has(k)) boardQueues.set(k, []);
    boardQueues.get(k).push({ ...baseCmd, targetKey: k });
  }

  return baseCmd;
}

function broadcastCommand(command) {
  return enqueueCommandForTarget('ALL', command);
}

// Yangi doska aniqlanganda adminlarga xabar yuborish
function notifyAdminsNewBoard(macAddress, ipAddress) {
  if (!bot || notifiedNewBoards.has(macAddress)) return;
  notifiedNewBoards.add(macAddress);

  const text = `<b>[Yangi Doska] Yangi interaktiv doska aniqlandi</b>\n\n` +
    `Qurilma ID (MAC): <code>${macAddress}</code>\n` +
    (ipAddress ? `IP manzil: <code>${ipAddress}</code>\n` : '') +
    `Ulanish vaqti: <code>${getFormattedTime()}</code>\n\n` +
    `Ushbu doska uchun xona ma'lumotlarini kiritish uchun quyidagi tugmani bosing:`;

  const replyMarkup = {
    inline_keyboard: [
      [{ text: 'Ma\'lumot kiritish', callback_data: `cmd_setup_board:${macAddress}` }]
    ]
  };

  const adminIds = new Set([SUPER_ADMIN_ID, ...db.itAdmins.map((a) => a.id)]);
  for (const adminId of adminIds) {
    bot.sendMessage(adminId, text, { parse_mode: 'HTML', reply_markup: replyMarkup }).catch(() => {});
  }
}

// ==========================================
// EXPRESS SERVER SOZLAMALARI
// ==========================================
const app = express();
app.use(cors());
app.use(express.json());

// Server holati
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

// Doskalar ro'yxati va holati
app.get('/api/board/list', (req, res) => {
  const now = Date.now();
  const list = [];
  for (const [key, b] of connectedBoards.entries()) {
    const isOnline = now - b.lastSeen < 15000;
    list.push({
      ...b,
      isOnline
    });
  }
  res.json({ boards: list, rooms: db.rooms });
});

// Doska uchun POLLING endpoint (Heartbeat + Buyruqlarni olish)
// APK har 1.5 soniyada so'rov yuboradi
app.post('/api/board/poll', (req, res) => {
  const { mac, boardId, hardwareId, roomName, isLocked, secondsRemaining, teacherName, appVersion } = req.body;
  if (!mac && !boardId && !hardwareId) {
    return res.status(400).json({ error: 'Qurilma identifikatori talab qilinadi' });
  }

  const clientMac = (mac || '').trim().toUpperCase();
  const clientBoardId = (boardId || '').trim().toUpperCase();
  const clientHwId = (hardwareId || '').trim().toUpperCase();
  const clientIp = req.ip || req.headers['x-forwarded-for'] || '';

  const boardKey = resolveBoardKey(clientMac, clientBoardId, clientHwId, clientIp);

  // Bazada ushbu doskaga biriktirilgan xonani qidirish
  const existingRoom = db.rooms.find((r) => {
    const rId = r.id.trim().toUpperCase();
    const rIdNoColon = rId.replace(/:/g, '');
    const macNoColon = clientMac.replace(/:/g, '');
    return rId === boardKey ||
      (clientMac && rId === clientMac) ||
      (clientBoardId && rId === clientBoardId) ||
      (clientHwId && rId === clientHwId) ||
      (macNoColon && rIdNoColon === macNoColon) ||
      (r.name && roomName && r.name.trim().toLowerCase() === roomName.trim().toLowerCase());
  });

  const boardInfo = {
    mac: clientMac || 'N/A',
    boardId: clientBoardId || 'N/A',
    hardwareId: clientHwId || 'N/A',
    roomId: existingRoom ? existingRoom.id : boardKey,
    roomName: existingRoom ? existingRoom.name : (roomName || 'Noma\'lum xona'),
    isLocked: !!isLocked,
    secondsRemaining: Number(secondsRemaining) || 0,
    teacherName: teacherName || '',
    appVersion: appVersion || '1.0',
    lastSeen: Date.now(),
    ip: clientIp,
    isRegistered: !!existingRoom
  };

  connectedBoards.set(boardKey, boardInfo);

  // Agar doska hali xonaga biriktirilmagan bo'lsa, adminlarga xabar yuborish
  if (!existingRoom && clientMac && clientMac !== 'UNKNOWN' && clientMac !== '02:00:00:00:00:00') {
    notifyAdminsNewBoard(clientMac, clientIp);
  }

  // Ushbu doskaga tegishli mumkin bo'lgan kalitlar
  const myPossibleKeys = [boardKey];
  if (clientMac) myPossibleKeys.push(clientMac);
  if (clientBoardId) myPossibleKeys.push(clientBoardId);
  if (existingRoom) myPossibleKeys.push(existingRoom.id.toUpperCase());

  let targetCmd = null;
  for (const k of myPossibleKeys) {
    const q = boardQueues.get(k);
    if (q && q.length > 0) {
      targetCmd = q.shift();
      if (q.length === 0) boardQueues.delete(k);
      break;
    }
  }

  res.json({
    status: 'ok',
    serverTime: getFormattedTime(),
    isRegistered: !!existingRoom,
    registeredRoomName: existingRoom ? existingRoom.name : null,
    command: targetCmd
  });
});

// Doska buyruqni qabul qilganini tasdiqlash
app.post('/api/board/ack', (req, res) => {
  const { commandId } = req.body;
  res.json({ status: 'acknowledged', commandId });
});

// Tashqi tizim yoki API uchun doskani ochish
app.post('/api/board/unlock', (req, res) => {
  const { targetRoomId, teacherName, durationMinutes } = req.body;
  const target = targetRoomId || 'ALL';
  const duration = Number(durationMinutes) || 45;
  const teacher = teacherName || 'Mas\'ul O\'qituvchi';

  enqueueCommandForTarget(target, {
    type: 'UNLOCK',
    teacherName: teacher,
    durationMinutes: duration,
    targetRoomId: target
  });

  logAccess(0, teacher, 'API', `Doska ochildi: ${target} (${duration} daqiqa)`);
  res.json({ status: 'enqueued', targetRoomId: target, durationMinutes: duration });
});

// Tashqi tizim yoki API uchun doskani qulflash
app.post('/api/board/lock', (req, res) => {
  const { targetRoomId, reason } = req.body;
  const target = targetRoomId || 'ALL';

  enqueueCommandForTarget(target, {
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
  console.log('TELEGRAM_BOT_TOKEN kiritilmagan.');
  console.log('Render.com -> Environment bo\'limida Telegram bot tokeningizni kiriting.');
  console.log('-------------------------------------------------------------');
}

if (bot) {
  let is401Reported = false;
  bot.on('polling_error', (error) => {
    if (error.code === 'ETELEGRAM' && (error.message.includes('401') || (error.response && error.response.statusCode === 401))) {
      if (!is401Reported) {
        console.error('-------------------------------------------------------------');
        console.error('XATOLIK: Telegram Bot Token noto\'g\'ri yoki bekor qilingan (401 Unauthorized)!');
        console.error('Render.com -> Environment bo\'limida TELEGRAM_BOT_TOKEN ga yangi tokenni kiriting.');
        console.error('-------------------------------------------------------------');
        is401Reported = true;
      }
      bot.stopPolling().catch(() => {});
      return;
    }
    console.error('Telegram Polling Error:', error.code, error.message);
  });

  // Asosiy tugmalar (Barcha emojilardan xoli, professional matn)
  function buildMainKeyboard(role) {
    const keyboard = [
      [{ text: 'Darsni boshlash (Ochish)' }, { text: 'Doskani qulflash (Bloklash)' }],
      [{ text: '+10 daqiqa qo\'shish' }, { text: 'Doskalar ro\'yxati va holati' }]
    ];

    if (role === 'SUPER_ADMIN' || role === 'IT_ADMIN') {
      keyboard.push([
        { text: 'Xonalar ro\'yxati' },
        { text: 'O\'qituvchilar' }
      ]);
      keyboard.push([
        { text: 'Kirish jurnali' },
        { text: 'Yangi xona qo\'shish' }
      ]);
    }

    return {
      keyboard,
      resize_keyboard: true
    };
  }

  // Interaktiv doskalar ro'yxati (Asosiy boshqaruv paneli)
  async function renderBoardList(chatId, messageId = null) {
    const now = Date.now();
    let listText = `<b>Interaktiv Doskalar Boshqaruv Markazi</b>\n\n`;

    if (db.rooms.length === 0 && connectedBoards.size === 0) {
      listText += `<i>Hozircha tizimda birorta doska ro'yxatdan o'tmagan.</i>\n\n` +
        `Yangi 86" doskani ishga tushirganingizda, bot sizga avtomatik tarzda xona ma'lumotlarini kiritish tugmasini yuboradi.`;
    } else {
      listText += `Kerakli doskani tanlab, uni <b>ochishingiz (dars boshlash)</b> yoki <b>qulflashingiz (bloklash)</b> mumkin:\n\n`;
      db.rooms.forEach((r, idx) => {
        const b = connectedBoards.get(r.id.toUpperCase());
        const isOnline = b && now - b.lastSeen < 15000;
        const statusText = isOnline
          ? (b.isLocked ? '[Qulfda]' : `[Darsda - ${Math.ceil(b.secondsRemaining / 60)} daq qoldi]`)
          : '[Oflayn]';
        listText += `${idx + 1}. <b>${r.name}</b> — ${statusText}\n`;
      });
    }

    const inline_keyboard = [];

    if (db.rooms.length > 1 || connectedBoards.size > 1) {
      inline_keyboard.push([{ text: 'Barcha doskalar (HAMMASI)', callback_data: 'select_board:ALL' }]);
    }

    for (const room of db.rooms) {
      const b = connectedBoards.get(room.id.toUpperCase());
      const isOnline = b && now - b.lastSeen < 15000;
      const statusText = isOnline ? (b.isLocked ? '[Qulfda]' : '[Darsda]') : '[Oflayn]';
      inline_keyboard.push([
        {
          text: `${room.name} ${statusText}`,
          callback_data: `select_board:${room.id}`
        }
      ]);
    }

    // Tarmoqdagi yangi (hali biriktirilmagan) doskalar
    for (const [key, b] of connectedBoards.entries()) {
      const isReg = db.rooms.some((r) => r.id.toUpperCase() === key.toUpperCase());
      if (!isReg) {
        inline_keyboard.push([
          { text: `Yangi doska: ${key} [Nom berish]`, callback_data: `cmd_setup_board:${key}` }
        ]);
      }
    }

    inline_keyboard.push([{ text: 'Ro\'yxatni yangilash', callback_data: 'show_board_list' }]);

    if (messageId) {
      try {
        return await bot.editMessageText(listText, {
          chat_id: chatId,
          message_id: messageId,
          parse_mode: 'HTML',
          reply_markup: { inline_keyboard }
        });
      } catch (_) {}
    }

    return bot.sendMessage(chatId, listText, {
      parse_mode: 'HTML',
      reply_markup: { inline_keyboard }
    });
  }

  // Tanlangan doska uchun boshqaruv kartasi
  async function renderBoardDetails(chatId, targetRoomId, messageId = null) {
    const now = Date.now();
    const isAll = targetRoomId === 'ALL';

    if (isAll) {
      const text = `<b>Barcha Interaktiv Doskalar Boshqaruvi</b>\n\n` +
        `Ro'yxatdagi xonalar: <b>${db.rooms.length} ta</b>\n` +
        `Ulangan doskalar: <b>${connectedBoards.size} ta</b>\n\n` +
        `Amalni tanlang:`;

      const inline_keyboard = [
        [
          { text: 'Barchasini ochish (45 min)', callback_data: 'action_unlock:ALL' },
          { text: 'Barchasini qulflash (Bloklash)', callback_data: 'action_lock:ALL' }
        ],
        [
          { text: 'Barchasiga +10 daqiqa', callback_data: 'action_extend:ALL' }
        ],
        [
          { text: 'Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
        ]
      ];

      if (messageId) {
        try {
          return await bot.editMessageText(text, {
            chat_id: chatId,
            message_id: messageId,
            parse_mode: 'HTML',
            reply_markup: { inline_keyboard }
          });
        } catch (_) {}
      }

      return bot.sendMessage(chatId, text, {
        parse_mode: 'HTML',
        reply_markup: { inline_keyboard }
      });
    }

    const room = db.rooms.find((r) => r.id.toUpperCase() === targetRoomId.toUpperCase());
    const b = connectedBoards.get(targetRoomId.toUpperCase());
    const isOnline = b && now - b.lastSeen < 15000;

    const roomTitle = room ? room.name : targetRoomId;
    const statusStr = isOnline
      ? (b.isLocked
          ? '[Qulfda] Kutish rejimida'
          : `[Darsda] Dars davom etmoqda (${Math.ceil(b.secondsRemaining / 60)} daqiqa qoldi)\nO'qituvchi: <b>${b.teacherName || 'Noma\'lum'}</b>`)
      : '[Oflayn] Tarmoqqa ulanmagan';

    const text = `<b>Doska: ${roomTitle}</b>\n\n` +
      `Qurilma ID (MAC): <code>${targetRoomId}</code>\n` +
      `Aloqa holati: <b>${isOnline ? 'Onlayn (Faol)' : 'Oflayn'}</b>\n` +
      `Ekran holati: <b>${statusStr}</b>\n` +
      (b && b.ip ? `IP manzil: <code>${b.ip}</code>\n` : '') +
      `\nAmalni tanlang:`;

    const inline_keyboard = [
      [
        { text: 'Darsni boshlash (Ochish)', callback_data: `action_unlock:${targetRoomId}` },
        { text: 'Doskani qulflash (Bloklash)', callback_data: `action_lock:${targetRoomId}` }
      ],
      [
        { text: '+10 daqiqa qo\'shish', callback_data: `action_extend:${targetRoomId}` }
      ],
      [
        { text: 'Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
      ]
    ];

    if (messageId) {
      try {
        return await bot.editMessageText(text, {
          chat_id: chatId,
          message_id: messageId,
          parse_mode: 'HTML',
          reply_markup: { inline_keyboard }
        });
      } catch (_) {}
    }

    return bot.sendMessage(chatId, text, {
      parse_mode: 'HTML',
      reply_markup: { inline_keyboard }
    });
  }

  // Xonalar tanlash inline klaviaturasi (Har doim "Doskalar ro'yxatiga qaytish" tugmasi bilan)
  function buildRoomSelectionKeyboard(actionPrefix, includeAll = false) {
    const inline_keyboard = [];
    const now = Date.now();

    if (includeAll && (db.rooms.length > 1 || connectedBoards.size > 1)) {
      inline_keyboard.push([{ text: 'Barcha doskalar (HAMMASI)', callback_data: `${actionPrefix}:ALL` }]);
    }

    const renderedKeys = new Set();
    for (const room of db.rooms) {
      renderedKeys.add(room.id.toUpperCase());
      const b = connectedBoards.get(room.id.toUpperCase());
      const isOnline = b && now - b.lastSeen < 15000;
      const statusText = isOnline ? (b.isLocked ? '[Qulfda]' : '[Darsda]') : '[Oflayn]';
      inline_keyboard.push([
        {
          text: `${room.name} ${statusText}`,
          callback_data: `${actionPrefix}:${room.id}`
        }
      ]);
    }

    for (const [key, b] of connectedBoards.entries()) {
      const upperKey = key.toUpperCase();
      if (!renderedKeys.has(upperKey) && !db.rooms.some((r) => r.id.toUpperCase() === upperKey)) {
        renderedKeys.add(upperKey);
        const isOnline = now - b.lastSeen < 15000;
        const statusText = isOnline ? (b.isLocked ? '[Qulfda]' : '[Darsda]') : '[Oflayn]';
        inline_keyboard.push([
          {
            text: `${b.roomName || key} ${statusText}`,
            callback_data: `${actionPrefix}:${key}`
          }
        ]);
      }
    }

    inline_keyboard.push([{ text: 'Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }]);
    return { inline_keyboard };
  }

  // Xabar tinglovchisi
  bot.on('message', async (msg) => {
    const chatId = msg.chat.id;
    const userId = msg.from.id;
    const text = (msg.text || '').trim();

    const userName = [msg.from.first_name, msg.from.last_name].filter(Boolean).join(' ') ||
      (msg.from.username ? `@${msg.from.username}` : `Foydalanuvchi (${userId})`);

    const { isAuthorized, role, name: customName } = checkUserRole(userId);
    const displayName = customName || userName;

    if (!isAuthorized) {
      return bot.sendMessage(
        chatId,
        `Sizda ushbu tizimdan foydalanish ruxsati yo'q.\n\nSizning Telegram ID: <code>${userId}</code>\n\nRuxsat olish uchun maktab IT administratoriga murojaat qiling.`,
        { parse_mode: 'HTML' }
      );
    }

    // 0. Xona nomini kiritish bosqichi (Step 3 -> Step 4 -> Step 5)
    if (awaitingRoomSetupForChat.has(chatId)) {
      if (text === '/cancel' || text.toLowerCase().includes('bekor')) {
        awaitingRoomSetupForChat.delete(chatId);
        return bot.sendMessage(chatId, 'Xona kiritish bekor qilindi.', {
          reply_markup: buildMainKeyboard(role)
        });
      }

      if (!text.startsWith('/')) {
        const targetMac = awaitingRoomSetupForChat.get(chatId);
        awaitingRoomSetupForChat.delete(chatId);

        const roomName = text.trim();
        if (roomName.length < 2) {
          return bot.sendMessage(
            chatId,
            'Xona nomi juda qisqa. Iltimos, xona nomini to\'liqroq yozing (masalan: 101-Fizika):',
            { parse_mode: 'HTML' }
          );
        }

        const existingIdx = db.rooms.findIndex((r) => r.id.toUpperCase() === targetMac.toUpperCase());
        if (existingIdx !== -1) {
          db.rooms[existingIdx].name = roomName;
          db.rooms[existingIdx].addedBy = displayName;
          db.rooms[existingIdx].addedAt = getFormattedTime();
        } else {
          db.rooms.push({
            id: targetMac.toUpperCase(),
            name: roomName,
            addedBy: displayName,
            addedAt: getFormattedTime()
          });
        }
        saveDatabase(db);
        logAccess(userId, displayName, role, `Yangi xona qo'shildi: ${roomName} (${targetMac})`);

        await bot.sendMessage(
          chatId,
          `<b>Yangi xona muvaffaqiyatli saqlandi!</b>\n\n` +
          `Xona: <b>${roomName}</b>\n` +
          `Qurilma ID (MAC): <code>${targetMac}</code>\n` +
          `Kiritdi: <b>${displayName}</b>\n` +
          `Vaqt: <code>${getFormattedTime()}</code>\n\n` +
          `<i>Doska tizimga muvaffaqiyatli biriktirildi.</i>`,
          { parse_mode: 'HTML', reply_markup: buildMainKeyboard(role) }
        );

        return renderBoardList(chatId);
      } else {
        awaitingRoomSetupForChat.delete(chatId);
      }
    }

    // 1. /start yoki /help
    if (text === '/start' || text === '/help') {
      const msgText = `<b>SmartBoard 86 Boshqaruv Markazi</b>\n\n` +
        `Foydalanuvchi: <b>${displayName}</b>\n` +
        `Rolingiz: <b>${role}</b>\n` +
        `Faol doskalar: <b>${connectedBoards.size} ta</b>\n` +
        `Server holati: <b>Render Server Faol</b>`;

      await bot.sendMessage(chatId, msgText, {
        parse_mode: 'HTML',
        reply_markup: buildMainKeyboard(role)
      });

      return renderBoardList(chatId);
    }

    // 2. Darsni boshlash (Ochish)
    if (
      text === 'Darsni boshlash (Ochish)' ||
      text.toLowerCase().includes('darsni boshlash') ||
      text.toLowerCase().includes('dars boshlash') ||
      text === '/unlock' ||
      text.toLowerCase() === 'ochish'
    ) {
      if (db.rooms.length === 0 && connectedBoards.size === 0) {
        broadcastCommand({
          type: 'UNLOCK',
          teacherName: displayName,
          durationMinutes: 45
        });
        logAccess(userId, displayName, role, 'Barcha doskalar ochildi (45 min)');
        return bot.sendMessage(
          chatId,
          `<b>Interaktiv Doska ochildi!</b>\n\nO'qituvchi: <b>${displayName}</b>\nDars vaqti: <b>45 daqiqa</b>\nVaqt: <code>${getFormattedTime()}</code>`,
          { parse_mode: 'HTML' }
        );
      }

      if (db.rooms.length === 1 && connectedBoards.size <= 1) {
        const singleRoom = db.rooms[0];
        enqueueCommandForTarget(singleRoom.id, {
          type: 'UNLOCK',
          teacherName: displayName,
          durationMinutes: 45,
          targetRoomId: singleRoom.id
        });
        logAccess(userId, displayName, role, `Dars boshlandi: ${singleRoom.name}`);
        return bot.sendMessage(
          chatId,
          `<b>Interaktiv Doska ochildi!</b>\n\nXona: <b>${singleRoom.name}</b>\nO'qituvchi: <b>${displayName}</b>\nDars vaqti: <b>45 daqiqa</b>\nVaqt: <code>${getFormattedTime()}</code>`,
          { parse_mode: 'HTML' }
        );
      }

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
      text === 'Doskani qulflash (Bloklash)' ||
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
    if (text === '+10 daqiqa qo\'shish' || text === '/extend' || text.includes('10 daqiqa')) {
      broadcastCommand({ type: 'EXTEND', minutes: 10 });
      logAccess(userId, displayName, role, '+10 daqiqa qo\'shildi');
      return bot.sendMessage(
        chatId,
        `<b>Dars vaqti 10 daqiqaga uzaytirildi!</b>\nVaqt: <code>${getFormattedTime()}</code>`,
        { parse_mode: 'HTML' }
      );
    }

    // 5. Doskalar holati
    if (text === 'Doskalar ro\'yxati va holati' || text === '/status') {
      return renderBoardList(chatId);
    }

    // 6. Xonalar ro'yxati
    if (text === 'Xonalar ro\'yxati' || text === '/rooms') {
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

    // 7. Yangi xona qo'shish tugmasi
    if (text === 'Yangi xona qo\'shish') {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }

      const unassignedBoards = [];
      for (const [key, b] of connectedBoards.entries()) {
        const isReg = db.rooms.some((r) => r.id.toUpperCase() === key.toUpperCase());
        if (!isReg) {
          unassignedBoards.push({ mac: key, ip: b.ip });
        }
      }

      if (unassignedBoards.length > 0) {
        const buttons = unassignedBoards.map((b) => [
          { text: `${b.mac} ga nom berish`, callback_data: `cmd_setup_board:${b.mac}` }
        ]);
        buttons.push([{ text: 'Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }]);
        return bot.sendMessage(
          chatId,
          `<b>Aniqlangan yangi doskalar:</b>\n\n` +
          `Quyidagi doskalar ilovasi ishga tushgan, ammo hali xona nomi kiritilmagan. Ma'lumot kiritish uchun tanlang:`,
          {
            parse_mode: 'HTML',
            reply_markup: { inline_keyboard: buttons }
          }
        );
      }

      return bot.sendMessage(
        chatId,
        `<b>Yangi xona (doska) qo'shish tartibi:</b>\n\n` +
        `1. Yangi 86" interaktiv doskaga SmartBoard ilovasini yuklang va ishga tushiring.\n` +
        `2. Doska avtomatik tarzda serverga ulanadi va bot sizga:\n` +
        `   <i>"Yangi interaktiv doska aniqlandi, ID: (MAC)"</i> xabarini va <b>[Ma'lumot kiritish]</b> tugmasini yuboradi.\n` +
        `3. Shu tugmani bosasiz.\n` +
        `4. Bot sizdan xona nomini so'raganda (masalan: <b>101-Fizika</b>) deb yozasiz.\n` +
        `5. Bot doskani xonaga muvaffaqiyatli saqlaydi.\n\n` +
        `<i>Qurilma MAC manzilini bilsangiz, buyruq orqali ham kiritishingiz mumkin:</i>\n` +
        `<code>/add_room &lt;MAC_MANZIL&gt; &lt;XONA_NOMI&gt;</code>`,
        {
          parse_mode: 'HTML',
          reply_markup: {
            inline_keyboard: [
              [{ text: 'Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }]
            ]
          }
        }
      );
    }

    // 7.1 Qo'lda buyruq orqali xona qo'shish (/add_room MAC Xona_Nomi)
    if (text.startsWith('/add_room')) {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      const parts = text.replace('/add_room', '').trim().split(/\s+/);
      const roomId = parts[0]?.trim()?.toUpperCase();
      const roomName = parts.slice(1).join(' ').trim();

      if (!roomId || !roomName || roomId.length < 4 || !/^[A-Z0-9_:-]+$/.test(roomId)) {
        return bot.sendMessage(
          chatId,
          `<b>Xona qo'shish formati:</b>\n\n<code>/add_room &lt;QURILMA_MAC_ID&gt; &lt;XONA_NOMI&gt;</code>\n\nMisol:\n<code>/add_room 00:1A:2B:3C:4D:5E 101-Fizika xonasi</code>`,
          { parse_mode: 'HTML' }
        );
      }

      const existing = db.rooms.find((r) => r.id.toUpperCase() === roomId);
      if (existing) {
        existing.name = roomName;
        existing.addedBy = displayName;
        existing.addedAt = getFormattedTime();
      } else {
        db.rooms.push({
          id: roomId,
          name: roomName,
          addedBy: displayName,
          addedAt: getFormattedTime()
        });
      }
      saveDatabase(db);
      logAccess(userId, displayName, role, `Xona qo'shildi: ${roomName} (${roomId})`);

      return bot.sendMessage(
        chatId,
        `<b>Yangi xona muvaffaqiyatli saqlandi!</b>\n\nXona: <b>${roomName}</b>\nID (MAC): <code>${roomId}</code>`,
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
        logAccess(userId, displayName, role, `Xona o'chirildi: ${removed.name} (${targetId})`);
        return bot.sendMessage(chatId, `Xona (${removed.name}) ro'yxatdan o'chirildi.`);
      }
      return bot.sendMessage(chatId, `Xona topilmadi: ${targetId}`);
    }

    // 9. O'qituvchilar ro'yxati
    if (text === 'O\'qituvchilar' || text === '/teachers') {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      let msgText = `<b>Ruxsat etilgan o'qituvchilar (${db.teachers.length} ta):</b>\n\n`;
      db.teachers.forEach((t, idx) => {
        msgText += `${idx + 1}. <b>${t.name}</b>\n   ID: <code>${t.id}</code>\n   O'chirish: <code>/del_teacher ${t.id}</code>\n\n`;
      });
      msgText += `Yangi o'qituvchi qo'shish: <code>/add_teacher &lt;Telegram_ID&gt; &lt;F.I.SH&gt;</code>`;
      return bot.sendMessage(chatId, msgText, { parse_mode: 'HTML' });
    }

    // 10. O'qituvchi qo'shish
    if (text.startsWith('/add_teacher')) {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      const parts = text.replace('/add_teacher', '').trim().split(/\s+/);
      const targetId = parseInt(parts[0], 10);
      const teacherName = parts.slice(1).join(' ').trim();

      if (!targetId || !teacherName) {
        return bot.sendMessage(
          chatId,
          `Format: <code>/add_teacher &lt;Telegram_ID&gt; &lt;F.I.SH&gt;</code>\nMisol:\n<code>/add_teacher 12345678 Ali Valiyev</code>`,
          { parse_mode: 'HTML' }
        );
      }

      const existing = db.teachers.find((t) => t.id === targetId);
      if (existing) {
        existing.name = teacherName;
      } else {
        db.teachers.push({ id: targetId, name: teacherName, addedAt: getFormattedTime() });
      }
      saveDatabase(db);
      logAccess(userId, displayName, role, `O'qituvchi qo'shildi: ${teacherName} (${targetId})`);
      return bot.sendMessage(chatId, `O'qituvchi (${teacherName}) muvaffaqiyatli saqlandi.`);
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
    if (text === 'Kirish jurnali' || text === '/logs') {
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

    // 1. Doskalar ro'yxatiga qaytish
    if (data === 'show_board_list' || data === 'cmd_cancel') {
      return renderBoardList(chatId, query.message?.message_id);
    }

    // 2. Doskani tanlash va alohida boshqaruv kartasini ochish
    if (data.startsWith('select_board:')) {
      const targetRoomId = data.replace('select_board:', '').trim().toUpperCase();
      return renderBoardDetails(chatId, targetRoomId, query.message?.message_id);
    }

    // 3. Doskani ochish (action_unlock / unlock_room)
    if (data.startsWith('action_unlock:') || data.startsWith('unlock_room:')) {
      const targetRoomId = data.replace('action_unlock:', '').replace('unlock_room:', '').trim().toUpperCase();
      const isAll = targetRoomId === 'ALL';
      const room = db.rooms.find((r) => r.id.toUpperCase() === targetRoomId);
      const roomTitle = isAll ? 'Barcha doskalar' : (room ? room.name : targetRoomId);

      if (isAll) {
        broadcastCommand({
          type: 'UNLOCK',
          teacherName: displayName,
          durationMinutes: 45
        });
      } else {
        enqueueCommandForTarget(targetRoomId, {
          type: 'UNLOCK',
          teacherName: displayName,
          durationMinutes: 45,
          targetRoomId
        });
      }

      logAccess(userId, displayName, role, `Dars boshlandi (Ochildi): ${roomTitle} (45 daqiqa)`);

      const confirmText = `<b>Interaktiv Doska ochildi</b>\n\n` +
        `Xona: <b>${roomTitle}</b>\n` +
        `O'qituvchi: <b>${displayName}</b>\n` +
        `Dars vaqti: <b>45 daqiqa</b>\n` +
        `Vaqt: <code>${getFormattedTime()}</code>\n\n` +
        `<i>Boshqa doskaga o'tish yoki ro'yxatga qaytish:</i>`;

      const confirmMarkup = [
        [
          { text: 'Doskani qulflash (Bloklash)', callback_data: `action_lock:${targetRoomId}` },
          { text: '+10 daqiqa', callback_data: `action_extend:${targetRoomId}` }
        ],
        [
          { text: 'Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
        ]
      ];

      try {
        return await bot.editMessageText(confirmText, {
          chat_id: chatId,
          message_id: query.message?.message_id,
          parse_mode: 'HTML',
          reply_markup: { inline_keyboard: confirmMarkup }
        });
      } catch (_) {
        return bot.sendMessage(chatId, confirmText, {
          parse_mode: 'HTML',
          reply_markup: { inline_keyboard: confirmMarkup }
        });
      }
    }

    // 4. Doskani qulflash / bloklash (action_lock / lock_room)
    if (data.startsWith('action_lock:') || data.startsWith('lock_room:')) {
      const targetRoomId = data.replace('action_lock:', '').replace('lock_room:', '').trim().toUpperCase();
      const isAll = targetRoomId === 'ALL';
      const room = db.rooms.find((r) => r.id.toUpperCase() === targetRoomId);
      const roomTitle = isAll ? 'Barcha doskalar' : (room ? room.name : targetRoomId);

      if (isAll) {
        broadcastCommand({ type: 'LOCK' });
      } else {
        enqueueCommandForTarget(targetRoomId, { type: 'LOCK', targetRoomId });
      }

      logAccess(userId, displayName, role, `Doska qulflandi (Bloklandi): ${roomTitle}`);

      const confirmText = `<b>Doska qulflandi (Bloklandi)</b>\n\n` +
        `Xona: <b>${roomTitle}</b>\n` +
        `Mas'ul: <b>${displayName}</b>\n` +
        `Vaqt: <code>${getFormattedTime()}</code>\n\n` +
        `<i>Boshqa doskaga o'tish yoki ro'yxatga qaytish:</i>`;

      const confirmMarkup = [
        [
          { text: 'Darsni boshlash (Ochish)', callback_data: `action_unlock:${targetRoomId}` }
        ],
        [
          { text: 'Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
        ]
      ];

      try {
        return await bot.editMessageText(confirmText, {
          chat_id: chatId,
          message_id: query.message?.message_id,
          parse_mode: 'HTML',
          reply_markup: { inline_keyboard: confirmMarkup }
        });
      } catch (_) {
        return bot.sendMessage(chatId, confirmText, {
          parse_mode: 'HTML',
          reply_markup: { inline_keyboard: confirmMarkup }
        });
      }
    }

    // 5. Vaqt uzaytirish
    if (data.startsWith('action_extend:')) {
      const targetRoomId = data.replace('action_extend:', '').trim().toUpperCase();
      const isAll = targetRoomId === 'ALL';
      const room = db.rooms.find((r) => r.id.toUpperCase() === targetRoomId);
      const roomTitle = isAll ? 'Barcha doskalar' : (room ? room.name : targetRoomId);

      if (isAll) {
        broadcastCommand({ type: 'EXTEND', minutes: 10 });
      } else {
        enqueueCommandForTarget(targetRoomId, { type: 'EXTEND', minutes: 10, targetRoomId });
      }

      logAccess(userId, displayName, role, `+10 daqiqa qo'shildi: ${roomTitle}`);

      const confirmText = `<b>Dars vaqti 10 daqiqaga uzaytirildi</b>\n\n` +
        `Xona: <b>${roomTitle}</b>\n` +
        `Mas'ul: <b>${displayName}</b>\n` +
        `Vaqt: <code>${getFormattedTime()}</code>`;

      const confirmMarkup = [
        [
          { text: 'Doskani qulflash (Bloklash)', callback_data: `action_lock:${targetRoomId}` },
          { text: 'Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
        ]
      ];

      try {
        return await bot.editMessageText(confirmText, {
          chat_id: chatId,
          message_id: query.message?.message_id,
          parse_mode: 'HTML',
          reply_markup: { inline_keyboard: confirmMarkup }
        });
      } catch (_) {
        return bot.sendMessage(chatId, confirmText, {
          parse_mode: 'HTML',
          reply_markup: { inline_keyboard: confirmMarkup }
        });
      }
    }

    // 6. Yangi doskaga xona nomi berish (Step 2 -> Step 3)
    if (data.startsWith('cmd_setup_board:')) {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }
      const targetMac = data.replace('cmd_setup_board:', '').trim().toUpperCase();
      awaitingRoomSetupForChat.set(chatId, targetMac);

      return bot.sendMessage(
        chatId,
        `<b>Doskani xonaga biriktirish</b>\n\n` +
        `Qurilma ID (MAC): <code>${targetMac}</code>\n\n` +
        `Iltimos, ushbu doska o'rnatilgan <b>xona nomini</b> yozib yuboring:\n` +
        `<i>(Masalan: 101-Fizika yoki 204-Informatika)</i>`,
        {
          parse_mode: 'HTML',
          reply_markup: {
            inline_keyboard: [
              [{ text: 'Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }]
            ]
          }
        }
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
