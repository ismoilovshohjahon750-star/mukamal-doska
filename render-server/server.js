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

// Adminlarga xabar yuborilgan yangi doskalar MAC manzillari
const notifiedNewBoards = new Set();

// Admin bilan xona nomini kiritish suhbat holati: chatId -> targetMac
const awaitingRoomSetupForChat = new Map();

// Avval tasodifan xato bilan qo'shilib qolgan '⚙️' xonani tozalash
if (Array.isArray(db.rooms)) {
  const initialCount = db.rooms.length;
  db.rooms = db.rooms.filter((r) => r.id !== '⚙️' && r.name !== 'Yangi xona qo\'shish');
  if (db.rooms.length !== initialCount) {
    saveDatabase(db);
  }
}

// Buyruqlarni xavfsiz va aniq bitta doskaga yoki barchasiga yo'naltirish
function enqueueCommandForTarget(target, command) {
  const cleanTarget = String(target || 'ALL').trim().toUpperCase();
  const isAll = cleanTarget === 'ALL' || cleanTarget === '';

  const cmdId = `cmd_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;
  const baseCmd = {
    id: cmdId,
    timestamp: Date.now(),
    ...command,
    targetRoomId: cleanTarget
  };

  if (isAll) {
    // Barcha doskalarga yuborish: Har bir ulangan doskaga ALOHIDA o'z navbatiga qo'yiladi!
    // Hech qachon umumiy 'ALL' navbati yaratilmaydi - shunda bir doska boshqasining buyrug'ini yutib yubormaydi.
    const targetKeys = new Set();
    for (const key of connectedBoards.keys()) {
      targetKeys.add(key.toUpperCase());
    }
    for (const r of db.rooms) {
      targetKeys.add(r.id.toUpperCase());
    }

    if (targetKeys.size === 0) {
      if (!pendingCommands.has('ALL')) pendingCommands.set('ALL', []);
      pendingCommands.get('ALL').push(baseCmd);
      return baseCmd;
    }

    for (const key of targetKeys) {
      if (!pendingCommands.has(key)) pendingCommands.set(key, []);
      pendingCommands.get(key).push({ ...baseCmd, targetKey: key });
    }
    return baseCmd;
  }

  // Aniq bitta doska yoki xonaga yuborish
  const matchedBoardKeys = new Set();
  matchedBoardKeys.add(cleanTarget);

  // Bazadagi xonalardan moslik qidirish
  const matchedRooms = db.rooms.filter((r) => {
    const rId = r.id.trim().toUpperCase();
    const rName = r.name.trim().toUpperCase();
    return rId === cleanTarget ||
      rName === cleanTarget ||
      rId.replace(/:/g, '') === cleanTarget.replace(/:/g, '');
  });

  for (const r of matchedRooms) {
    matchedBoardKeys.add(r.id.trim().toUpperCase());
    matchedBoardKeys.add(r.name.trim().toUpperCase());
  }

  // Ulangan doskalar ichidan ham mos keladigan MAC va ID larni topish
  for (const [key, b] of connectedBoards.entries()) {
    const bKey = key.toUpperCase();
    const bMac = (b.mac || '').toUpperCase();
    const bBoardId = (b.boardId || '').toUpperCase();
    const bRoomName = (b.roomName || '').trim().toUpperCase();

    const matchesThisBoard =
      bKey === cleanTarget ||
      bMac === cleanTarget ||
      bBoardId === cleanTarget ||
      bRoomName === cleanTarget ||
      (bMac && bMac.replace(/:/g, '') === cleanTarget.replace(/:/g, '')) ||
      matchedRooms.some((r) => r.id.toUpperCase() === bKey || r.name.trim().toUpperCase() === bRoomName);

    if (matchesThisBoard) {
      matchedBoardKeys.add(bKey);
      if (bMac) matchedBoardKeys.add(bMac);
      if (bBoardId) matchedBoardKeys.add(bBoardId);
    }
  }

  for (const k of matchedBoardKeys) {
    if (!pendingCommands.has(k)) pendingCommands.set(k, []);
    pendingCommands.get(k).push({ ...baseCmd, targetKey: k });
  }

  return baseCmd;
}

function broadcastCommand(command) {
  return enqueueCommandForTarget('ALL', command);
}

function notifyAdminsNewBoard(macAddress, ipAddress) {
  if (!bot || notifiedNewBoards.has(macAddress)) return;
  notifiedNewBoards.add(macAddress);

  const text = `🔔 <b>Yangi interaktiv doska aniqlandi!</b>\n\n` +
    `Qurilma ID (MAC): <code>${macAddress}</code>\n` +
    (ipAddress ? `IP manzil: <code>${ipAddress}</code>\n` : '') +
    `Ulanish vaqti: <code>${getFormattedTime()}</code>\n\n` +
    `Ushbu doska uchun xona ma'lumotlarini kiritish uchun quyidagi tugmani bosing:`;

  const replyMarkup = {
    inline_keyboard: [
      [{ text: '📝 Ma\'lumot kiritish', callback_data: `cmd_setup_board:${macAddress}` }]
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
    const isOnline = now - b.lastSeen < 15000;
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
  const { mac, boardId, hardwareId, roomName, isLocked, secondsRemaining, teacherName, appVersion } = req.body;
  if (!mac && !boardId && !hardwareId) {
    return res.status(400).json({ error: 'Qurilma identifikatori (mac yoki boardId) talab qilinadi' });
  }

  const clientMac = (mac || '').trim().toUpperCase();
  const clientBoardId = (boardId || '').trim().toUpperCase();
  const clientHwId = (hardwareId || '').trim().toUpperCase();
  const clientIp = req.ip || req.headers['x-forwarded-for'] || '';

  // Doskaning serverdagi asosiy xos kaliti
  const boardKey = (clientMac && clientMac !== 'UNKNOWN' && clientMac.length >= 10)
    ? clientMac
    : (clientBoardId || clientHwId || `BOARD_${clientIp}`);

  // Bazada ushbu doskaga tegishli xona bormi?
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
  if (!existingRoom && clientMac && clientMac !== 'UNKNOWN') {
    notifyAdminsNewBoard(clientMac, clientIp);
  }

  // Ushbu aniq doskaga tegishli kalitlar to'plami
  const myKeys = new Set();
  myKeys.add(boardKey);
  if (clientMac) {
    myKeys.add(clientMac);
    myKeys.add(clientMac.replace(/:/g, ''));
  }
  if (clientBoardId) myKeys.add(clientBoardId);
  if (clientHwId) myKeys.add(clientHwId);
  if (existingRoom) {
    myKeys.add(existingRoom.id.trim().toUpperCase());
    myKeys.add(existingRoom.name.trim().toUpperCase());
  }
  if (roomName) {
    myKeys.add(roomName.trim().toUpperCase());
  }

  // Faqat ushbu doskaga tegishli navbatdan buyruq olish
  let targetCmd = null;
  for (const k of myKeys) {
    const cmds = pendingCommands.get(k);
    if (cmds && cmds.length > 0) {
      targetCmd = cmds.shift();
      if (cmds.length === 0) {
        pendingCommands.delete(k);
      }
      // Agar ushbu buyruq boshqa alias kalitlarda ham turgan bo'lsa, ularni tozalash (bir doska uchun takrorlanmasligi uchun)
      if (targetCmd && targetCmd.id) {
        for (const otherK of myKeys) {
          const otherCmds = pendingCommands.get(otherK);
          if (otherCmds) {
            const idx = otherCmds.findIndex((c) => c.id === targetCmd.id);
            if (idx !== -1) {
              otherCmds.splice(idx, 1);
            }
            if (otherCmds.length === 0) pendingCommands.delete(otherK);
          }
        }
      }
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

// Admin yoki tashqi tizim uchun doskani ochish API
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

// Doskani qulflash API
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

  // Interaktiv doskalar ro'yxati (Asosiy boshqaruv paneli)
  async function renderBoardList(chatId, messageId = null) {
    const now = Date.now();
    let listText = `<b>🖥 Interaktiv Doskalar Ro'yxati:</b>\n\n`;

    if (db.rooms.length === 0) {
      listText += `<i>Hozircha tizimga xona qo'shilmagan.</i>\n\n` +
        `Yangi 86" doskani ishga tushirganingizda, bot sizga avtomatik tarzda xona ma'lumotlarini kiritish tugmasini yuboradi.`;
    } else {
      listText += `Kerakli doskani tanlab, uni <b>ochishingiz (dars boshlash)</b> yoki <b>qulflashingiz (block)</b> mumkin:\n\n`;
      db.rooms.forEach((r, idx) => {
        const b = connectedBoards.get(r.id.toUpperCase());
        const isOnline = b && now - b.lastSeen < 15000;
        const statusIcon = isOnline
          ? (b.isLocked ? '🟢 Qulfda' : `🟡 Darsda (${Math.ceil(b.secondsRemaining / 60)} daq qoldi)`)
          : '🔴 Oflayn';
        listText += `${idx + 1}. <b>${r.name}</b> — ${statusIcon}\n`;
      });
    }

    const inline_keyboard = [];

    if (db.rooms.length > 1) {
      inline_keyboard.push([{ text: '🌐 Barcha doskalar (HAMMASI)', callback_data: 'select_board:ALL' }]);
    }

    for (const room of db.rooms) {
      const b = connectedBoards.get(room.id.toUpperCase());
      const isOnline = b && now - b.lastSeen < 15000;
      const statusIcon = isOnline ? (b.isLocked ? '🟢 Qulfda' : '🟡 Darsda') : '🔴 Oflayn';
      inline_keyboard.push([
        {
          text: `🖥 ${room.name} [${statusIcon}]`,
          callback_data: `select_board:${room.id}`
        }
      ]);
    }

    // Tarmoqdagi yangi (hali biriktirilmagan) doskalar
    for (const [key, b] of connectedBoards.entries()) {
      const isReg = db.rooms.some((r) => r.id.toUpperCase() === key.toUpperCase());
      if (!isReg) {
        inline_keyboard.push([
          { text: `🆕 Yangi doska: ${key} [📝 Nom berish]`, callback_data: `cmd_setup_board:${key}` }
        ]);
      }
    }

    inline_keyboard.push([{ text: '🔄 Ro\'yxatni yangilash', callback_data: 'show_board_list' }]);

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

  // Tanlangan doska uchun alohida boshqaruv kartasi
  async function renderBoardDetails(chatId, targetRoomId, messageId = null) {
    const now = Date.now();
    const isAll = targetRoomId === 'ALL';

    if (isAll) {
      const text = `🌐 <b>Barcha Interaktiv Doskalar Boshqaruvi</b>\n\n` +
        `Umumiy ro'yxatdagi xonalar: <b>${db.rooms.length} ta</b>\n` +
        `Serverga ulangan doskalar: <b>${connectedBoards.size} ta</b>\n\n` +
        `Quyidagi tugmalar orqali barcha doskalarni bir vaqtda boshqarishingiz mumkin:`;

      const inline_keyboard = [
        [
          { text: '🟢 Barchasini ochish (45 min)', callback_data: 'action_unlock:ALL' },
          { text: '🔒 Barchasini qulflash (Block)', callback_data: 'action_lock:ALL' }
        ],
        [
          { text: '⏱ Barchasiga +10 daqiqa', callback_data: 'action_extend:ALL' }
        ],
        [
          { text: '🔙 Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
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
          ? '🟢 Qulfda (Kutish rejimida)'
          : `🟡 DARS DAVOM ETMOQDA (${Math.ceil(b.secondsRemaining / 60)} daqiqa qoldi)\n👤 O'qituvchi: <b>${b.teacherName || 'Noma\'lum'}</b>`)
      : '🔴 Oflayn (Tarmoqqa ulanmagan)';

    const text = `🖥 <b>Doska: ${roomTitle}</b>\n\n` +
      `🆔 Qurilma (MAC): <code>${targetRoomId}</code>\n` +
      `📡 Aloqa holati: ${isOnline ? '🟢 Onlayn (Faol)' : '🔴 Oflayn'}\n` +
      `🔒 Ekran holati: ${statusStr}\n` +
      (b && b.ip ? `🌐 IP manzil: <code>${b.ip}</code>\n` : '') +
      `\nKerakli amalni tanlang:`;

    const inline_keyboard = [
      [
        { text: '🟢 Darsni boshlash (Ochish)', callback_data: `action_unlock:${targetRoomId}` },
        { text: '🔒 Qulflash (Block)', callback_data: `action_lock:${targetRoomId}` }
      ],
      [
        { text: '⏱ +10 daqiqa qo\'shish', callback_data: `action_extend:${targetRoomId}` }
      ],
      [
        { text: '🔙 Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
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

  // Xonalar tanlash inline klaviaturasi (Orqaga qaytish tugmasi bilan)
  function buildRoomSelectionKeyboard(actionPrefix, includeAll = false) {
    const inline_keyboard = [];
    const now = Date.now();

    if (includeAll && (db.rooms.length > 1 || connectedBoards.size > 1)) {
      inline_keyboard.push([{ text: '🌐 Barcha doskalar (HAMMASI)', callback_data: `${actionPrefix}:ALL` }]);
    }

    // Bazadagi ro'yxatdan o'tgan xonalar
    const renderedKeys = new Set();
    for (const room of db.rooms) {
      renderedKeys.add(room.id.toUpperCase());
      const b = connectedBoards.get(room.id.toUpperCase());
      const isOnline = b && now - b.lastSeen < 15000;
      const statusIcon = isOnline ? (b.isLocked ? '🟢 Qulfda' : '🟡 Darsda') : '🔴 Oflayn';
      inline_keyboard.push([
        {
          text: `🖥 ${room.name} [${statusIcon}]`,
          callback_data: `${actionPrefix}:${room.id}`
        }
      ]);
    }

    // Tarmoqda aniqlangan yangi / ro'yxatdan o'tmagan doskalar ham chiqsin
    for (const [key, b] of connectedBoards.entries()) {
      const upperKey = key.toUpperCase();
      if (!renderedKeys.has(upperKey) && !db.rooms.some((r) => r.id.toUpperCase() === upperKey)) {
        renderedKeys.add(upperKey);
        const isOnline = now - b.lastSeen < 15000;
        const statusIcon = isOnline ? (b.isLocked ? '🟢 Qulfda' : '🟡 Darsda') : '🔴 Oflayn';
        inline_keyboard.push([
          {
            text: `🖥 ${b.roomName || key} [${statusIcon}]`,
            callback_data: `${actionPrefix}:${key}`
          }
        ]);
      }
    }

    inline_keyboard.push([{ text: '🔙 Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }]);
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

    // 0. Yangi doska uchun xona nomini kiritish holati (Step 3 -> Step 4 -> Step 5)
    if (awaitingRoomSetupForChat.has(chatId)) {
      if (text === '/cancel' || text === '❌ Bekor qilish') {
        awaitingRoomSetupForChat.delete(chatId);
        return bot.sendMessage(chatId, '❌ Xona kiritish bekor qilindi.', {
          reply_markup: buildMainKeyboard(role)
        });
      }

      // Agar boshqa buyruq bo'lmasa, yuborilgan matn xona nomi sifatida qabul qilinadi
      if (!text.startsWith('/')) {
        const targetMac = awaitingRoomSetupForChat.get(chatId);
        awaitingRoomSetupForChat.delete(chatId);

        const roomName = text.trim();
        if (roomName.length < 2) {
          return bot.sendMessage(
            chatId,
            '⚠️ Xona nomi juda qisqa. Iltimos, xona nomini to\'liqroq yozing (masalan: <i>101-Fizika</i>):',
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

        return bot.sendMessage(
          chatId,
          `✅ <b>Yangi xona muvaffaqiyatli saqlandi!</b>\n\n` +
          `🏫 Xona: <b>${roomName}</b>\n` +
          `🆔 Qurilma (MAC): <code>${targetMac}</code>\n` +
          `👤 Kiritdi: <b>${displayName}</b>\n` +
          `🕒 Vaqt: <code>${getFormattedTime()}</code>\n\n` +
          `<i>Doska tizimga kiritildi! Endi ushbu doskani ro'yxatdan ochishingiz va boshqarishingiz mumkin.</i>`,
          { parse_mode: 'HTML', reply_markup: buildMainKeyboard(role) }
        );
      } else {
        awaitingRoomSetupForChat.delete(chatId);
      }
    }

    // 1. /start yoki /help
    if (text === '/start' || text === '/help' || text === 'yordam') {
      const msgText = `<b>SmartBoard 86" Doska Boshqaruv Markazi</b>\n\n` +
        `Assalomu alaykum, <b>${displayName}</b>!\n` +
        `Rolingiz: <b>${role}</b>\n` +
        `Faol doskalar: <b>${connectedBoards.size} ta</b>\n` +
        `Server holati: 🟢 <b>Render Server Faol</b>`;

      await bot.sendMessage(chatId, msgText, {
        parse_mode: 'HTML',
        reply_markup: buildMainKeyboard(role)
      });

      return renderBoardList(chatId);
    }

    // 2. Darsni boshlash (Ochish)
    if (
      text === '🟢 Darsni boshlash (Ochish)' ||
      text.toLowerCase().includes('darsni boshlash') ||
      text.toLowerCase().includes('dars boshlash') ||
      text === '/unlock' ||
      text.toLowerCase() === 'ochish'
    ) {
      if (db.rooms.length === 0 && connectedBoards.size === 0) {
        // Xonalar va ulangan doskalar bo'lmasa umumiy ochish
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
          `<b>86" Interaktiv Doska ochildi!</b>\n\nXona: <b>${singleRoom.name}</b>\nO'qituvchi: <b>${displayName}</b>\nDars vaqti: <b>45 daqiqa</b>\nVaqt: <code>${getFormattedTime()}</code>`,
          { parse_mode: 'HTML' }
        );
      }

      // 2 ta yoki undan ko'p doska/xona bo'lsa tanlash menyusini ko'rsatish
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

    // 5. Doskalar holati (Interaktiv ro'yxat)
    if (text === '📊 Doskalar holati' || text === '/status') {
      return renderBoardList(chatId);
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

    // 7. Yangi xona qo'shish tugmasi
    if (text === '⚙️ Yangi xona qo\'shish') {
      if (role !== 'SUPER_ADMIN' && role !== 'IT_ADMIN') {
        return bot.sendMessage(chatId, 'Bu amal faqat Adminlar uchun.');
      }

      // Hozirda ulangan, lekin hali nom berilmagan doskalarni aniqlash
      const unassignedBoards = [];
      for (const [key, b] of connectedBoards.entries()) {
        const isReg = db.rooms.some((r) => r.id.toUpperCase() === key.toUpperCase());
        if (!isReg) {
          unassignedBoards.push({ mac: key, ip: b.ip });
        }
      }

      if (unassignedBoards.length > 0) {
        const buttons = unassignedBoards.map((b) => [
          { text: `📝 ${b.mac} ga nom berish`, callback_data: `cmd_setup_board:${b.mac}` }
        ]);
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
        `ℹ️ <b>Yangi xona (doska) qo'shish tartibi:</b>\n\n` +
        `1. Yangi 86" interaktiv doskaga SmartBoard APK ilovasini o'rnating va oching.\n` +
        `2. Doska avtomatik tarzda serverga ulanadi va bot sizga:\n` +
        `   <i>"🔔 Yangi interaktiv doska aniqlandi, ID: (MAC)"</i> xabarini va <b>[📝 Ma'lumot kiritish]</b> tugmasini yuboradi.\n` +
        `3. Shu tugmani bosasiz.\n` +
        `4. Bot sizdan xona nomini so'raganda (masalan: <b>101-Fizika</b>) deb yozasiz.\n` +
        `5. Bot doskani xonaga muvaffaqiyatli biriktiradi!\n\n` +
        `<i>Agarda qurilma MAC manzilini bilsangiz, to'g'ridan-to'g'ri buyruq orqali ham qo'shishingiz mumkin:</i>\n` +
        `<code>/add_room &lt;MAC_MANZIL&gt; &lt;XONA_NOMI&gt;</code>`,
        { parse_mode: 'HTML' }
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

      if (!roomId || !roomName || roomId === '⚙️') {
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
        `✅ <b>Yangi xona muvaffaqiyatli saqlandi!</b>\n\nXona: <b>${roomName}</b>\nID (MAC): <code>${roomId}</code>`,
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

      const confirmText = `🟢 <b>86" Interaktiv Doska ochildi!</b>\n\n` +
        `🏫 Xona: <b>${roomTitle}</b>\n` +
        `👤 O'qituvchi: <b>${displayName}</b>\n` +
        `⏱ Dars vaqti: <b>45 daqiqa</b>\n` +
        `🕒 Vaqt: <code>${getFormattedTime()}</code>\n\n` +
        `<i>Boshqa doskaga o'tish yoki doskalar ro'yxatiga qaytish uchun:</i>`;

      const confirmMarkup = [
        [
          { text: '🔒 Doskani qulflash (Block)', callback_data: `action_lock:${targetRoomId}` },
          { text: '⏱ +10 daqiqa', callback_data: `action_extend:${targetRoomId}` }
        ],
        [
          { text: '🔙 Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
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

      logAccess(userId, displayName, role, `Doska qulflandi (Block): ${roomTitle}`);

      const confirmText = `🔒 <b>Doska qulflandi (Block qilindi)!</b>\n\n` +
        `🏫 Xona: <b>${roomTitle}</b>\n` +
        `👤 Mas'ul: <b>${displayName}</b>\n` +
        `🕒 Vaqt: <code>${getFormattedTime()}</code>\n\n` +
        `<i>Boshqa doskaga o'tish yoki doskalar ro'yxatiga qaytish uchun:</i>`;

      const confirmMarkup = [
        [
          { text: '🟢 Darsni boshlash (Ochish)', callback_data: `action_unlock:${targetRoomId}` }
        ],
        [
          { text: '🔙 Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
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

      const confirmText = `⏱ <b>Dars vaqti 10 daqiqaga uzaytirildi!</b>\n\n` +
        `🏫 Xona: <b>${roomTitle}</b>\n` +
        `👤 Mas'ul: <b>${displayName}</b>\n` +
        `🕒 Vaqt: <code>${getFormattedTime()}</code>`;

      const confirmMarkup = [
        [
          { text: '🔒 Qulflash (Block)', callback_data: `action_lock:${targetRoomId}` },
          { text: '🔙 Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }
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
        `📝 <b>Doskani xonaga biriktirish</b>\n\n` +
        `Qurilma ID (MAC): <code>${targetMac}</code>\n\n` +
        `Iltimos, ushbu doska o'rnatilgan <b>xona nomini</b> yozib yuboring:\n` +
        `<i>(Masalan: 101-Fizika xonasi yoki 204-Informatika)</i>`,
        {
          parse_mode: 'HTML',
          reply_markup: {
            inline_keyboard: [
              [{ text: '🔙 Doskalar ro\'yxatiga qaytish', callback_data: 'show_board_list' }]
            ]
          }
        }
      );
    }

    if (data === 'cmd_cancel_setup') {
      awaitingRoomSetupForChat.delete(chatId);
      return renderBoardList(chatId);
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
