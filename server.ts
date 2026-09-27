import express from 'express';
import { createServer as createViteServer } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;
const isProd = process.env.NODE_ENV === 'production';

app.use(express.json());

// In-Memory Data Store for Sandman Doppler Devices & Events
export interface RGBColor {
  r: number;
  g: number;
  b: number;
}

export interface DopplerAlarm {
  id: number;
  name: string;
  time: string; // "HH:MM"
  repeat: string[]; // ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
  color: RGBColor;
  volume: number; // 1-100
  status: 'set' | 'unarmed' | 'snoozed' | 'active';
  sound: string;
}

export interface DopplerDevice {
  id: string; // e.g. "doppler-bed-01"
  dsn: string; // e.g. "Doppler-deadbeef"
  name: string; // "Bedroom Doppler"
  ipAddress: string;
  firmwareVersion: string;
  softwareVersion: string;
  modelNumber: string;
  manufacturer: string;
  online: boolean;
  uptimeSeconds: number;
  wifiSsid: string;
  wifiRssi: number; // -55 dBm
  alexaLoggedIn: boolean;

  // Display and buttons configuration
  dayDisplayColor: RGBColor;
  dayDisplayBrightness: number; // 0-100
  nightDisplayColor: RGBColor;
  nightDisplayBrightness: number; // 0-100
  dayButtonColor: RGBColor;
  dayButtonBrightness: number;
  nightButtonColor: RGBColor;
  nightButtonBrightness: number;
  smartButtonColor: RGBColor;

  // Sync flags
  syncDisplayAndButtonDay: boolean;
  syncDisplayAndButtonNight: boolean;
  syncDayAndNight: boolean;

  // Sensor & Light Thresholds
  ambientLightSensorLux: number;
  isNightMode: boolean;
  dayToNightThreshold: number; // 0-100
  nightToDayThreshold: number; // 0-100

  // Clock behavior
  time24Hour: boolean;
  leadingZero24Hour: boolean;
  colonBlink: boolean;
  colonVisible: boolean;
  displaySecondsOnMini: boolean;
  fadeTimeMode: boolean;
  timeOffsetMinutes: number;
  timezone: string;

  // Audio & Sound
  masterVolume: number; // 0-100
  soundPreset: 'Flat' | 'Rock' | 'Pop' | 'Jazz' | 'Talk' | 'Classical';
  volumeDependentEq: boolean;
  ascendingAlarms: boolean;
  alexaTapToTalkTone: boolean;
  alexaWakeWordTone: boolean;

  // Weather
  weatherLocation: string; // e.g. "94043, US" or "Palo Alto, CA"
  currentTemperatureF: number;
  weatherCondition: string;

  // Mini display override
  miniDisplayOverride: {
    number: number;
    color: RGBColor;
    expiresAt: number | null;
  } | null;

  // Main display text override (scrolling message)
  mainDisplayTextOverride: {
    text: string;
    speed: number;
    durationSeconds: number;
    color: RGBColor;
    expiresAt: number | null;
  } | null;

  // Light Bar Effect State (29 LEDs)
  lightBarEffect: {
    mode: 'off' | 'set' | 'set_each' | 'blink' | 'pulse' | 'comet' | 'sweep' | 'rainbow';
    color?: RGBColor;
    colors?: RGBColor[];
    rainbow?: boolean;
    speed?: number;
    duration?: number;
    sparkle?: 'low' | 'medium' | 'high' | null;
    gap?: number;
    size?: number;
    direction?: 'left' | 'right' | 'bounce';
    expiresAt?: number | null;
  } | null;

  // Rainbow Mode
  rainbowMode: {
    enabled: boolean;
    speed: number;
    mode: 'day' | 'night' | 'both';
  };

  // Alarms
  alarms: Record<number, DopplerAlarm>;
}

export interface DopplerEvent {
  id: string;
  timestamp: string;
  type: 'button_press' | 'alarm_trigger' | 'service_call' | 'sensor_change' | 'webhook';
  deviceId: string;
  deviceName: string;
  title: string;
  data: Record<string, unknown>;
}

// Initial devices
const devices: Record<string, DopplerDevice> = {
  'doppler-radar-01': {
    id: 'doppler-radar-01',
    dsn: 'Doppler-deadbeef',
    name: 'Radar (Master Bedroom)',
    ipAddress: '192.168.1.142',
    firmwareVersion: '1.4.12',
    softwareVersion: '2.1.0',
    modelNumber: 'PAI-DOPPLER-01',
    manufacturer: 'Palo Alto Innovation',
    online: true,
    uptimeSeconds: 86420,
    wifiSsid: 'HomeAutomation_5G',
    wifiRssi: -58,
    alexaLoggedIn: true,

    dayDisplayColor: { r: 0, g: 220, b: 255 }, // Cyan
    dayDisplayBrightness: 85,
    nightDisplayColor: { r: 255, g: 60, b: 20 }, // Dim Warm Amber/Red
    nightDisplayBrightness: 25,
    dayButtonColor: { r: 100, g: 200, b: 255 },
    dayButtonBrightness: 80,
    nightButtonColor: { r: 180, g: 50, b: 20 },
    nightButtonBrightness: 20,
    smartButtonColor: { r: 0, g: 255, b: 180 },

    syncDisplayAndButtonDay: false,
    syncDisplayAndButtonNight: false,
    syncDayAndNight: false,

    ambientLightSensorLux: 145,
    isNightMode: false,
    dayToNightThreshold: 35,
    nightToDayThreshold: 45,

    time24Hour: false,
    leadingZero24Hour: false,
    colonBlink: true,
    colonVisible: true,
    displaySecondsOnMini: false,
    fadeTimeMode: true,
    timeOffsetMinutes: 0,
    timezone: 'America/Los_Angeles',

    masterVolume: 65,
    soundPreset: 'Flat',
    volumeDependentEq: true,
    ascendingAlarms: true,
    alexaTapToTalkTone: true,
    alexaWakeWordTone: true,

    weatherLocation: '94301, USA',
    currentTemperatureF: 72,
    weatherCondition: 'Clear',

    miniDisplayOverride: null,
    mainDisplayTextOverride: null,
    lightBarEffect: {
      mode: 'pulse',
      colors: [
        { r: 0, g: 220, b: 255 },
        { r: 140, g: 0, b: 255 }
      ],
      speed: 25,
      duration: 30,
      sparkle: 'low',
      expiresAt: null
    },

    rainbowMode: {
      enabled: false,
      speed: 5,
      mode: 'both'
    },

    alarms: {
      0: {
        id: 0,
        name: 'Doppler System Alarm',
        time: '07:00',
        repeat: ['Mo', 'Tu', 'We', 'Th', 'Fr'],
        color: { r: 255, g: 140, b: 0 },
        volume: 75,
        status: 'set',
        sound: 'Sandman.mp3'
      },
      1: {
        id: 1,
        name: 'Morning Workout',
        time: '06:15',
        repeat: ['Mo', 'We', 'Fr'],
        color: { r: 0, g: 255, b: 128 },
        volume: 80,
        status: 'unarmed',
        sound: 'Alarming.mp3'
      },
      2: {
        id: 2,
        name: 'Weekend Gentle Wake',
        time: '08:30',
        repeat: ['Sa', 'Su'],
        color: { r: 180, g: 120, b: 255 },
        volume: 60,
        status: 'set',
        sound: 'Gentle.mp3'
      }
    }
  },
  'doppler-office-02': {
    id: 'doppler-office-02',
    dsn: 'Doppler-c001cafe',
    name: 'Office Doppler',
    ipAddress: '192.168.1.189',
    firmwareVersion: '1.4.12',
    softwareVersion: '2.1.0',
    modelNumber: 'PAI-DOPPLER-01',
    manufacturer: 'Palo Alto Innovation',
    online: true,
    uptimeSeconds: 142050,
    wifiSsid: 'HomeAutomation_5G',
    wifiRssi: -62,
    alexaLoggedIn: true,

    dayDisplayColor: { r: 255, g: 180, b: 50 },
    dayDisplayBrightness: 90,
    nightDisplayColor: { r: 200, g: 30, b: 10 },
    nightDisplayBrightness: 15,
    dayButtonColor: { r: 255, g: 180, b: 50 },
    dayButtonBrightness: 90,
    nightButtonColor: { r: 200, g: 30, b: 10 },
    nightButtonBrightness: 15,
    smartButtonColor: { r: 255, g: 215, b: 0 },

    syncDisplayAndButtonDay: true,
    syncDisplayAndButtonNight: true,
    syncDayAndNight: false,

    ambientLightSensorLux: 280,
    isNightMode: false,
    dayToNightThreshold: 30,
    nightToDayThreshold: 40,

    time24Hour: true,
    leadingZero24Hour: true,
    colonBlink: false,
    colonVisible: true,
    displaySecondsOnMini: true,
    fadeTimeMode: true,
    timeOffsetMinutes: 0,
    timezone: 'America/Los_Angeles',

    masterVolume: 50,
    soundPreset: 'Talk',
    volumeDependentEq: false,
    ascendingAlarms: false,
    alexaTapToTalkTone: true,
    alexaWakeWordTone: false,

    weatherLocation: '94301, USA',
    currentTemperatureF: 70,
    weatherCondition: 'Sunny',

    miniDisplayOverride: null,
    mainDisplayTextOverride: null,
    lightBarEffect: null,

    rainbowMode: {
      enabled: false,
      speed: 10,
      mode: 'day'
    },

    alarms: {
      0: {
        id: 0,
        name: 'Doppler System Alarm',
        time: '08:45',
        repeat: ['Mo', 'Tu', 'We', 'Th', 'Fr'],
        color: { r: 255, g: 200, b: 0 },
        volume: 60,
        status: 'set',
        sound: 'Jazz.mp3'
      }
    }
  }
};

let eventLogs: DopplerEvent[] = [
  {
    id: 'evt-init-1',
    timestamp: new Date(Date.now() - 3600000).toISOString(),
    type: 'webhook',
    deviceId: 'doppler-radar-01',
    deviceName: 'Radar (Master Bedroom)',
    title: 'Home Assistant Webhook Registered',
    data: {
      webhook_url: '/api/sandman_doppler/smart_button/doppler-radar-01',
      commands: ['HA Button 1', 'HA Button 2']
    }
  },
  {
    id: 'evt-init-2',
    timestamp: new Date(Date.now() - 1800000).toISOString(),
    type: 'button_press',
    deviceId: 'doppler-radar-01',
    deviceName: 'Radar (Master Bedroom)',
    title: 'Smart Button 1 Pressed',
    data: {
      button: 1,
      action: 'press',
      dsn: 'Doppler-deadbeef'
    }
  }
];

function logEvent(type: DopplerEvent['type'], deviceId: string, title: string, data: Record<string, unknown>) {
  const device = devices[deviceId];
  const newEvt: DopplerEvent = {
    id: `evt-${Date.now()}-${Math.random().toString(36).substr(2, 5)}`,
    timestamp: new Date().toISOString(),
    type,
    deviceId,
    deviceName: device ? device.name : deviceId,
    title,
    data
  };
  eventLogs.unshift(newEvt);
  if (eventLogs.length > 200) {
    eventLogs.pop();
  }
}

// REST Endpoints
app.get('/api/devices', (req, res) => {
  res.json(Object.values(devices));
});

app.get('/api/devices/:id', (req, res) => {
  const device = devices[req.params.id];
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }
  res.json(device);
});

app.post('/api/devices', (req, res) => {
  const { name, ipAddress } = req.body;
  if (!name) {
    return res.status(400).json({ error: 'Device name is required' });
  }
  const id = `doppler-${name.toLowerCase().replace(/[^a-z0-9]/g, '-')}-${Math.floor(10 + Math.random() * 90)}`;
  const dsn = `Doppler-${Math.random().toString(16).substr(2, 8)}`;
  
  const newDevice: DopplerDevice = {
    id,
    dsn,
    name,
    ipAddress: ipAddress || `192.168.1.${Math.floor(50 + Math.random() * 150)}`,
    firmwareVersion: '1.4.12',
    softwareVersion: '2.1.0',
    modelNumber: 'PAI-DOPPLER-01',
    manufacturer: 'Palo Alto Innovation',
    online: true,
    uptimeSeconds: 120,
    wifiSsid: 'HomeAutomation_5G',
    wifiRssi: -50,
    alexaLoggedIn: true,

    dayDisplayColor: { r: 56, g: 189, b: 248 },
    dayDisplayBrightness: 80,
    nightDisplayColor: { r: 244, g: 63, b: 94 },
    nightDisplayBrightness: 20,
    dayButtonColor: { r: 56, g: 189, b: 248 },
    dayButtonBrightness: 80,
    nightButtonColor: { r: 244, g: 63, b: 94 },
    nightButtonBrightness: 20,
    smartButtonColor: { r: 34, g: 197, b: 94 },

    syncDisplayAndButtonDay: false,
    syncDisplayAndButtonNight: false,
    syncDayAndNight: false,

    ambientLightSensorLux: 100,
    isNightMode: false,
    dayToNightThreshold: 35,
    nightToDayThreshold: 45,

    time24Hour: false,
    leadingZero24Hour: false,
    colonBlink: true,
    colonVisible: true,
    displaySecondsOnMini: false,
    fadeTimeMode: true,
    timeOffsetMinutes: 0,
    timezone: 'America/Los_Angeles',

    masterVolume: 70,
    soundPreset: 'Flat',
    volumeDependentEq: true,
    ascendingAlarms: true,
    alexaTapToTalkTone: true,
    alexaWakeWordTone: true,

    weatherLocation: 'San Francisco, CA',
    currentTemperatureF: 68,
    weatherCondition: 'Clear',

    miniDisplayOverride: null,
    mainDisplayTextOverride: null,
    lightBarEffect: null,

    rainbowMode: {
      enabled: false,
      speed: 0,
      mode: 'both'
    },

    alarms: {
      0: {
        id: 0,
        name: 'Doppler System Alarm',
        time: '07:30',
        repeat: ['Mo', 'Tu', 'We', 'Th', 'Fr'],
        color: { r: 255, g: 140, b: 0 },
        volume: 70,
        status: 'set',
        sound: 'Sandman.mp3'
      }
    }
  };

  devices[id] = newDevice;
  logEvent('service_call', id, 'Device Added', { deviceId: id, name });
  res.status(201).json(newDevice);
});

app.patch('/api/devices/:id', (req, res) => {
  const device = devices[req.params.id];
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }

  // Update properties
  Object.assign(device, req.body);

  // Check sync logic
  if (device.syncDisplayAndButtonDay && req.body.dayDisplayColor) {
    device.dayButtonColor = { ...req.body.dayDisplayColor };
  }
  if (device.syncDisplayAndButtonDay && req.body.dayDisplayBrightness !== undefined) {
    device.dayButtonBrightness = req.body.dayDisplayBrightness;
  }
  if (device.syncDisplayAndButtonNight && req.body.nightDisplayColor) {
    device.nightButtonColor = { ...req.body.nightDisplayColor };
  }
  if (device.syncDisplayAndButtonNight && req.body.nightDisplayBrightness !== undefined) {
    device.nightButtonBrightness = req.body.nightDisplayBrightness;
  }
  if (device.syncDayAndNight && req.body.dayDisplayColor) {
    device.nightDisplayColor = { ...req.body.dayDisplayColor };
    device.nightButtonColor = { ...req.body.dayDisplayColor };
  }

  // Recalculate day/night state based on ambient lux vs thresholds
  if (req.body.ambientLightSensorLux !== undefined) {
    if (device.ambientLightSensorLux < device.dayToNightThreshold) {
      device.isNightMode = true;
    } else if (device.ambientLightSensorLux >= device.nightToDayThreshold) {
      device.isNightMode = false;
    }
  }

  logEvent('sensor_change', device.id, 'Device Settings Updated', req.body);
  res.json(device);
});

// Home Assistant Services Execution Route
app.post('/api/devices/:id/services/:service', (req, res) => {
  const device = devices[req.params.id];
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }

  const { service } = req.params;
  const payload = req.body || {};

  switch (service) {
    case 'set_weather_location': {
      device.weatherLocation = payload.location || device.weatherLocation;
      logEvent('service_call', device.id, 'HA Service: set_weather_location', { location: payload.location });
      return res.json({ success: true, weatherLocation: device.weatherLocation });
    }

    case 'add_alarm': {
      const { id, name, time, repeat, color, volume, status, sound } = payload;
      let targetId = id;
      if (!targetId || device.alarms[targetId]) {
        // find first available id >= 1
        let candidate = 1;
        while (device.alarms[candidate]) {
          candidate++;
        }
        targetId = candidate;
      }
      const newAlarm: DopplerAlarm = {
        id: targetId,
        name: name || `Alarm ${targetId}`,
        time: time || '08:00',
        repeat: Array.isArray(repeat) ? repeat : [],
        color: color || { r: 255, g: 100, b: 0 },
        volume: volume ?? 70,
        status: status === 'Disabled' ? 'unarmed' : 'set',
        sound: sound || 'Sandman.mp3'
      };
      device.alarms[targetId] = newAlarm;
      logEvent('service_call', device.id, 'HA Service: add_alarm', newAlarm as unknown as Record<string, unknown>);
      return res.json({ success: true, alarm: newAlarm });
    }

    case 'update_alarm': {
      const { id, ...updates } = payload;
      if (id === undefined || !device.alarms[id]) {
        return res.status(400).json({ error: `Alarm with id ${id} not found` });
      }
      const alarm = device.alarms[id];
      if (updates.name !== undefined) alarm.name = updates.name;
      if (updates.time !== undefined) alarm.time = updates.time;
      if (updates.repeat !== undefined) alarm.repeat = updates.repeat;
      if (updates.color !== undefined) alarm.color = updates.color;
      if (updates.volume !== undefined) alarm.volume = updates.volume;
      if (updates.status !== undefined) {
        if (updates.status === 'Enabled') alarm.status = 'set';
        else if (updates.status === 'Disabled') alarm.status = 'unarmed';
        else if (updates.status === 'Snoozed') alarm.status = 'snoozed';
        else alarm.status = updates.status;
      }
      if (updates.sound !== undefined) alarm.sound = updates.sound;

      logEvent('service_call', device.id, `HA Service: update_alarm (ID ${id})`, updates);
      return res.json({ success: true, alarm });
    }

    case 'delete_alarm': {
      const { id } = payload;
      if (id === undefined || id === 0) {
        return res.status(400).json({ error: 'Alarm 0 (System Alarm) cannot be deleted' });
      }
      delete device.alarms[id];
      logEvent('service_call', device.id, `HA Service: delete_alarm (ID ${id})`, { id });
      return res.json({ success: true, deletedId: id });
    }

    case 'set_main_display_text': {
      const { text, duration, speed, color } = payload;
      const durationSeconds = typeof duration === 'number' ? duration : 10;
      device.mainDisplayTextOverride = {
        text: text || 'HELLO',
        speed: speed || 50,
        durationSeconds,
        color: color || { r: 0, g: 255, b: 255 },
        expiresAt: Date.now() + durationSeconds * 1000
      };
      logEvent('service_call', device.id, 'HA Service: set_main_display_text', {
        text,
        duration: durationSeconds,
        speed,
        color
      });
      return res.json({ success: true, mainDisplayTextOverride: device.mainDisplayTextOverride });
    }

    case 'set_mini_display_number': {
      const { number, duration, color } = payload;
      const durationSeconds = typeof duration === 'number' ? duration : 15;
      device.miniDisplayOverride = {
        number: number !== undefined ? number : 99,
        color: color || { r: 255, g: 150, b: 0 },
        expiresAt: Date.now() + durationSeconds * 1000
      };
      logEvent('service_call', device.id, 'HA Service: set_mini_display_number', {
        number,
        duration: durationSeconds,
        color
      });
      return res.json({ success: true, miniDisplayOverride: device.miniDisplayOverride });
    }

    case 'activate_light_bar_set':
    case 'activate_light_bar_set_each':
    case 'activate_light_bar_blink':
    case 'activate_light_bar_pulse':
    case 'activate_light_bar_comet':
    case 'activate_light_bar_sweep': {
      const mode = service.replace('activate_light_bar_', '') as DopplerDevice['lightBarEffect']['mode'];
      const durationSeconds = payload.duration ? (typeof payload.duration === 'number' ? payload.duration : 15) : 15;
      device.lightBarEffect = {
        mode,
        color: payload.color,
        colors: payload.colors,
        rainbow: payload.rainbow,
        speed: payload.speed ?? 50,
        duration: durationSeconds,
        sparkle: payload.sparkle || null,
        gap: payload.gap ?? 2,
        size: payload.size ?? 5,
        direction: payload.direction || 'right',
        expiresAt: Date.now() + durationSeconds * 1000
      };
      logEvent('service_call', device.id, `HA Service: ${service}`, payload);
      return res.json({ success: true, lightBarEffect: device.lightBarEffect });
    }

    case 'set_rainbow_mode': {
      const { speed, mode } = payload;
      device.rainbowMode = {
        enabled: speed > 0,
        speed: speed ?? 5,
        mode: mode || 'both'
      };
      logEvent('service_call', device.id, 'HA Service: set_rainbow_mode', payload);
      return res.json({ success: true, rainbowMode: device.rainbowMode });
    }

    case 'stop_light_bar': {
      device.lightBarEffect = null;
      logEvent('service_call', device.id, 'Lightbar Effect Cleared', {});
      return res.json({ success: true });
    }

    default:
      return res.status(400).json({ error: `Unknown service: ${service}` });
  }
});

// The official Webhook route registered by Sandman Doppler integration:
// __init__.py line 202: /api/sandman_doppler/smart_button/{device_entry.id}
app.post('/api/sandman_doppler/smart_button/:deviceId', (req, res) => {
  const device = devices[req.params.deviceId];
  const { button = 1, action = 'press' } = req.body || {};

  logEvent('webhook', req.params.deviceId, `Webhook: Smart Button ${button} ${action}`, {
    event_type: 'sandman_doppler_button_pressed',
    device_id: req.params.deviceId,
    dsn: device ? device.dsn : 'unknown',
    doppler_name: device ? device.name : 'Unknown Doppler',
    button,
    action
  });

  res.json({
    status: 'ok',
    event_dispatched: 'sandman_doppler_button_pressed',
    data: {
      device_id: req.params.deviceId,
      dsn: device ? device.dsn : 'unknown',
      button,
      action
    }
  });
});

// Physical Button Trigger Endpoint (Simulates pressing button 1, 2, alarm, snooze, etc.)
app.post('/api/devices/:id/button-press', (req, res) => {
  const device = devices[req.params.id];
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }
  const { button } = req.body; // 1, 2, 'alarm', 'snooze', 'vol_up', 'vol_down'

  if (button === 1 || button === 2) {
    logEvent('button_press', device.id, `Smart Button ${button} Pressed`, {
      button,
      action: 'press',
      dsn: device.dsn
    });
  } else if (button === 'snooze') {
    // find any active alarms and snooze them
    let snoozedCount = 0;
    Object.values(device.alarms).forEach(a => {
      if (a.status === 'active') {
        a.status = 'snoozed';
        snoozedCount++;
      }
    });
    logEvent('button_press', device.id, 'Snooze Button Pressed', { snoozedCount });
  } else if (button === 'alarm') {
    // toggle system alarm 0
    const sysAlarm = device.alarms[0];
    if (sysAlarm) {
      if (sysAlarm.status === 'active') {
        sysAlarm.status = 'unarmed';
      } else {
        sysAlarm.status = sysAlarm.status === 'set' ? 'unarmed' : 'set';
      }
    }
    logEvent('button_press', device.id, 'Alarm Button Pressed', { systemAlarmStatus: sysAlarm?.status });
  } else if (button === 'vol_up') {
    device.masterVolume = Math.min(100, device.masterVolume + 5);
    logEvent('button_press', device.id, 'Volume Up (+5)', { masterVolume: device.masterVolume });
  } else if (button === 'vol_down') {
    device.masterVolume = Math.max(0, device.masterVolume - 5);
    logEvent('button_press', device.id, 'Volume Down (-5)', { masterVolume: device.masterVolume });
  }

  res.json({ success: true, device });
});

// Event Logs Route
app.get('/api/events', (req, res) => {
  res.json(eventLogs);
});

app.post('/api/events/clear', (req, res) => {
  eventLogs = [];
  res.json({ success: true });
});

// Trigger Alarm Ringing Route (for testing automations & sound)
app.post('/api/devices/:id/alarms/:alarmId/trigger', (req, res) => {
  const device = devices[req.params.id];
  if (!device) return res.status(404).json({ error: 'Device not found' });
  const alarm = device.alarms[parseInt(req.params.alarmId, 10)];
  if (!alarm) return res.status(404).json({ error: 'Alarm not found' });

  alarm.status = 'active';
  logEvent('alarm_trigger', device.id, `ALARM RINGING: ${alarm.name}`, {
    alarm_id: alarm.id,
    name: alarm.name,
    time: alarm.time,
    sound: alarm.sound,
    volume: alarm.volume
  });

  res.json({ success: true, alarm });
});

async function startServer() {
  if (!isProd) {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa'
    });
    app.use(vite.middlewares);
  } else {
    app.use(express.static(path.resolve(__dirname, 'dist')));
    app.get('*', (req, res) => {
      res.sendFile(path.resolve(__dirname, 'dist', 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`[Sandman Doppler Server] Running on http://0.0.0.0:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
